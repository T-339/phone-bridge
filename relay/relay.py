#!/usr/bin/env python3
"""Phone Bridge relay.

Runs on the Raspberry Pi. Two endpoints, both protected by a secret in the path:

  /phone/<secret>  WebSocket that the Android app keeps open (outgoing from the phone)
  /mcp/<secret>    MCP (streamable HTTP, JSON responses) that Claude connects to

Each MCP tool call is forwarded to the phone as {"id", "tool", "args"} and the
phone answers with {"id", "ok", "text", "image", "mime", "error"}.
"""
import asyncio
import itertools
import json
import logging
import os
import sys

from aiohttp import web, WSMsgType

SECRET = os.environ.get("BRIDGE_SECRET", "")
PORT = int(os.environ.get("BRIDGE_PORT", "8787"))
CALL_TIMEOUT = 45

log = logging.getLogger("relay")

COORD_NOTE = "Coordinates are real screen pixels (the same units get_screen reports)."

TOOLS = [
    {
        "name": "phone_status",
        "description": "Check whether the phone is connected, and get its screen size, battery and the app in front.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "get_screen",
        "description": "Read what is on the phone screen as a list of UI elements, each with an [id], text, type, flags and its bounds. "
                       "Prefer this over screenshot: it is faster and the ids can be used with tap_element and type_text. "
                       "Ids are only valid until the next get_screen call.",
        "inputSchema": {"type": "object", "properties": {
            "all": {"type": "boolean", "description": "Include non-interactive layout elements too (default false)."}}},
    },
    {
        "name": "screenshot",
        "description": "Take a screenshot of the phone (JPEG, downscaled). Use when get_screen is not enough, e.g. images, games or canvas apps. "
                       "The reply states the scale factor to convert image pixels into screen pixels.",
        "inputSchema": {"type": "object", "properties": {
            "max_size": {"type": "integer", "description": "Longest side in pixels (default 1000, max 1600)."}}},
    },
    {
        "name": "tap_element",
        "description": "Tap a UI element by its id from the last get_screen call.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "integer"},
            "long": {"type": "boolean", "description": "Long-press instead of tap."}}, "required": ["id"]},
    },
    {
        "name": "tap",
        "description": "Tap at a screen position. " + COORD_NOTE,
        "inputSchema": {"type": "object", "properties": {
            "x": {"type": "number"}, "y": {"type": "number"},
            "long": {"type": "boolean", "description": "Long-press instead of tap."}}, "required": ["x", "y"]},
    },
    {
        "name": "swipe",
        "description": "Swipe from (x1,y1) to (x2,y2). " + COORD_NOTE,
        "inputSchema": {"type": "object", "properties": {
            "x1": {"type": "number"}, "y1": {"type": "number"},
            "x2": {"type": "number"}, "y2": {"type": "number"},
            "duration_ms": {"type": "integer", "description": "Default 300."}},
            "required": ["x1", "y1", "x2", "y2"]},
    },
    {
        "name": "scroll",
        "description": "Scroll the screen (or a scrollable element) up, down, left or right. 'down' shows content further down.",
        "inputSchema": {"type": "object", "properties": {
            "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
            "id": {"type": "integer", "description": "Optional scrollable element id from get_screen."}},
            "required": ["direction"]},
    },
    {
        "name": "type_text",
        "description": "Type text into a text field: the element with the given id, otherwise the focused field.",
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string"},
            "id": {"type": "integer"},
            "append": {"type": "boolean", "description": "Add to the existing text instead of replacing it."},
            "submit": {"type": "boolean", "description": "Press the keyboard's enter/send/search key afterwards."}},
            "required": ["text"]},
    },
    {
        "name": "press_key",
        "description": "Press a system button.",
        "inputSchema": {"type": "object", "properties": {
            "key": {"type": "string", "enum": ["back", "home", "recents", "notifications", "quick_settings",
                                               "enter", "lock_screen", "power_dialog"]}},
            "required": ["key"]},
    },
    {
        "name": "open_app",
        "description": "Open an app by its name (e.g. 'Spotify') or package name.",
        "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
    },
    {
        "name": "list_apps",
        "description": "List installed launchable apps (name and package).",
        "inputSchema": {"type": "object", "properties": {
            "filter": {"type": "string", "description": "Only apps whose name or package contains this."}}},
    },
    {
        "name": "open_url",
        "description": "Open a URL or deep link (https:, tel:, geo:, mailto: ...) on the phone.",
        "inputSchema": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]},
    },
    {
        "name": "recent_events",
        "description": "Show the phone's recent hardware button presses (key codes) and app/screen changes, oldest first, with times.",
        "inputSchema": {"type": "object", "properties": {
            "clear": {"type": "boolean", "description": "Empty the log after reading."}}},
    },
    {
        "name": "wait",
        "description": "Wait a number of seconds (max 20) for something on the phone to load.",
        "inputSchema": {"type": "object", "properties": {"seconds": {"type": "number"}}, "required": ["seconds"]},
    },
]

INSTRUCTIONS = (
    "These tools control the user's own Android phone through an accessibility-service app. "
    "Usual loop: get_screen -> act (tap_element / type_text / scroll / press_key) -> get_screen to check. "
    "Use screenshot only when the element list does not show what you need. "
    "Ask the user before sending messages, buying anything, deleting data or changing security settings."
)


class Phone:
    def __init__(self):
        self.ws = None
        self.info = {}
        self.pending = {}
        self.ids = itertools.count(1)

    async def call(self, tool, args):
        if self.ws is None or self.ws.closed:
            raise RuntimeError("The phone is not connected to the relay. Check the Phone Bridge app on the phone "
                               "(accessibility service on, internet available).")
        cid = next(self.ids)
        fut = asyncio.get_running_loop().create_future()
        self.pending[cid] = fut
        try:
            await self.ws.send_str(json.dumps({"id": cid, "tool": tool, "args": args}))
            return await asyncio.wait_for(fut, CALL_TIMEOUT)
        except asyncio.TimeoutError:
            raise RuntimeError("The phone did not answer within %ds." % CALL_TIMEOUT)
        finally:
            self.pending.pop(cid, None)

    def fail_all(self, reason):
        for fut in self.pending.values():
            if not fut.done():
                fut.set_exception(RuntimeError(reason))
        self.pending.clear()


phone = Phone()


def check_secret(request):
    if not SECRET or request.match_info.get("secret") != SECRET:
        raise web.HTTPNotFound()


async def phone_ws(request):
    check_secret(request)
    ws = web.WebSocketResponse(heartbeat=25, max_msg_size=32 * 1024 * 1024)
    await ws.prepare(request)
    if phone.ws is not None and not phone.ws.closed:
        log.info("new phone connection replaces the old one")
        await phone.ws.close()
    phone.ws = ws
    log.info("phone connected from %s", request.headers.get("X-Forwarded-For", request.remote))
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                data = json.loads(msg.data)
            except ValueError:
                continue
            if data.get("type") == "hello":
                phone.info = data.get("info", {})
                log.info("phone hello: %s", phone.info)
                continue
            fut = phone.pending.get(data.get("id"))
            if fut and not fut.done():
                fut.set_result(data)
    finally:
        if phone.ws is ws:
            phone.ws = None
            phone.fail_all("The phone disconnected during the call.")
        log.info("phone disconnected")
    return ws


def text_result(text, is_error=False):
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


async def run_tool(name, args):
    if name == "wait":
        secs = max(0.0, min(float(args.get("seconds", 1)), 20.0))
        await asyncio.sleep(secs)
        return text_result("Waited %.1f s." % secs)
    if name == "phone_status" and (phone.ws is None or phone.ws.closed):
        return text_result("Phone is NOT connected to the relay.", True)
    if name not in {t["name"] for t in TOOLS}:
        return text_result("Unknown tool: %s" % name, True)
    try:
        reply = await phone.call(name, args)
    except RuntimeError as e:
        return text_result(str(e), True)
    if not reply.get("ok"):
        return text_result("Phone error: %s" % reply.get("error", "unknown"), True)
    content = []
    if reply.get("text"):
        content.append({"type": "text", "text": reply["text"]})
    if reply.get("image"):
        content.append({"type": "image", "data": reply["image"], "mimeType": reply.get("mime", "image/jpeg")})
    if not content:
        content.append({"type": "text", "text": "Done."})
    return {"content": content, "isError": False}


async def handle_rpc(msg):
    method = msg.get("method")
    mid = msg.get("id")
    params = msg.get("params") or {}
    if mid is None:  # notification
        return None
    if method == "initialize":
        result = {
            "protocolVersion": params.get("protocolVersion", "2025-06-18"),
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "phone-bridge", "version": "0.1.0"},
            "instructions": INSTRUCTIONS,
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        result = await run_tool(params.get("name"), params.get("arguments") or {})
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "Method not found: %s" % method}}
    return {"jsonrpc": "2.0", "id": mid, "result": result}


async def mcp_post(request):
    check_secret(request)
    try:
        body = await request.json()
    except ValueError:
        return web.json_response({"jsonrpc": "2.0", "id": None,
                                  "error": {"code": -32700, "message": "Parse error"}}, status=400)
    if isinstance(body, list):
        replies = [r for r in await asyncio.gather(*(handle_rpc(m) for m in body)) if r is not None]
        return web.json_response(replies) if replies else web.Response(status=202)
    reply = await handle_rpc(body)
    if reply is None:
        return web.Response(status=202)
    if body.get("method") == "tools/call":
        log.info("tool %s -> %s", (body.get("params") or {}).get("name"),
                 "error" if reply["result"].get("isError") else "ok")
    return web.json_response(reply)


async def mcp_other(request):
    check_secret(request)
    if request.method == "DELETE":
        return web.Response(status=200)
    return web.Response(status=405, headers={"Allow": "POST"})


async def health(request):
    return web.Response(text="phone-bridge relay ok\n")


def make_app():
    app = web.Application(client_max_size=4 * 1024 * 1024)
    app.router.add_get("/", health)
    app.router.add_get("/phone/{secret}", phone_ws)
    app.router.add_post("/mcp/{secret}", mcp_post)
    app.router.add_route("GET", "/mcp/{secret}", mcp_other)
    app.router.add_route("DELETE", "/mcp/{secret}", mcp_other)
    return app


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if len(SECRET) < 16:
        sys.exit("BRIDGE_SECRET is missing or too short (need 16+ characters).")
    web.run_app(make_app(), host="127.0.0.1", port=PORT, access_log=None)
