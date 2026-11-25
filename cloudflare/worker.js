// Phone Bridge relay for Cloudflare Workers (alternative to the Pi relay). Deployed by GitHub Actions.
//   /phone/<secret>  WebSocket the Android app keeps open
//   /mcp/<secret>    MCP endpoint (streamable HTTP, JSON responses) for Claude
// One Durable Object holds the phone's socket and forwards tool calls to it.
import { DurableObject } from "cloudflare:workers";
import spec from "./tools.json";

const CALL_TIMEOUT_MS = 45000;
const TOOL_NAMES = new Set(spec.tools.map((t) => t.name));

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const [, kind, secret] = url.pathname.split("/");
    if (url.pathname === "/") return new Response("phone-bridge relay ok\n");
    if (!env.BRIDGE_SECRET || env.BRIDGE_SECRET.length < 16 || secret !== env.BRIDGE_SECRET ||
        (kind !== "phone" && kind !== "mcp")) {
      return new Response("Not found", { status: 404 });
    }
    const stub = env.BRIDGE.get(env.BRIDGE.idFromName("main"));
    return stub.fetch(new Request(`https://bridge/${kind}`, request));
  },
};

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

const textResult = (text, isError = false) => ({ content: [{ type: "text", text }], isError });

export class Bridge extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map();
    this.nextId = 1;
  }

  async fetch(request) {
    const kind = new URL(request.url).pathname.slice(1);
    if (kind === "phone") return this.acceptPhone(request);
    if (request.method === "POST") return this.mcp(request);
    if (request.method === "DELETE") return new Response(null, { status: 200 });
    return new Response(null, { status: 405, headers: { Allow: "POST" } });
  }

  // ---------- phone side ----------

  acceptPhone(request) {
    if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected WebSocket", { status: 426 });
    for (const old of this.ctx.getWebSockets()) {
      try { old.close(1000, "replaced by a new connection"); } catch {}
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  phone() {
    const open = this.ctx.getWebSockets().filter((w) => w.readyState === WebSocket.OPEN);
    return open[open.length - 1] || null;
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string") return;
    let data;
    try { data = JSON.parse(message); } catch { return; }
    if (data.type === "hello") return;
    const p = this.pending.get(data.id);
    if (p) { this.pending.delete(data.id); clearTimeout(p.timer); p.resolve(data); }
  }

  async webSocketClose(ws) {
    if (!this.phone()) this.failAll("The phone disconnected during the call.");
  }

  async webSocketError(ws) {
    if (!this.phone()) this.failAll("The phone connection failed during the call.");
  }

  failAll(reason) {
    for (const [id, p] of this.pending) { clearTimeout(p.timer); p.resolve({ id, ok: false, local: true, error: reason }); }
    this.pending.clear();
  }

  callPhone(tool, args) {
    const ws = this.phone();
    if (!ws) {
      return Promise.resolve({ ok: false, local: true,
        error: "The phone is not connected to the relay. Check the Phone Bridge app on the phone (accessibility service on, internet available)." });
    }
    const id = this.nextId++;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ id, ok: false, local: true, error: `The phone did not answer within ${CALL_TIMEOUT_MS / 1000}s.` });
      }, CALL_TIMEOUT_MS);
      this.pending.set(id, { resolve, timer });
      ws.send(JSON.stringify({ id, tool, args }));
    });
  }

  // ---------- Claude side (MCP) ----------

  async runTool(name, args) {
    if (name === "wait") {
      const secs = Math.max(0, Math.min(Number(args.seconds ?? 1) || 0, 20));
      await new Promise((r) => setTimeout(r, secs * 1000));
      return textResult(`Waited ${secs.toFixed(1)} s.`);
    }
    if (!TOOL_NAMES.has(name)) return textResult(`Unknown tool: ${name}`, true);
    const reply = await this.callPhone(name, args);
    if (!reply.ok) {
      return textResult(reply.local ? reply.error : `Phone error: ${reply.error || "unknown"}`, true);
    }
    const content = [];
    if (reply.text) content.push({ type: "text", text: reply.text });
    if (reply.image) content.push({ type: "image", data: reply.image, mimeType: reply.mime || "image/jpeg" });
    if (!content.length) content.push({ type: "text", text: "Done." });
    return { content, isError: false };
  }

  async handleRpc(msg) {
    const { method, id } = msg;
    const params = msg.params || {};
    if (id === undefined || id === null) return null; // notification
    let result;
    switch (method) {
      case "initialize":
        result = {
          protocolVersion: params.protocolVersion || "2025-06-18",
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: "phone-bridge", version: "0.1.0" },
          instructions: spec.instructions,
        };
        break;
      case "ping": result = {}; break;
      case "tools/list": result = { tools: spec.tools }; break;
      case "tools/call": result = await this.runTool(params.name, params.arguments || {}); break;
      default:
        return { jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } };
    }
    return { jsonrpc: "2.0", id, result };
  }

  async mcp(request) {
    let body;
    try { body = await request.json(); } catch {
      return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400);
    }
    if (Array.isArray(body)) {
      const replies = (await Promise.all(body.map((m) => this.handleRpc(m)))).filter(Boolean);
      return replies.length ? json(replies) : new Response(null, { status: 202 });
    }
    const reply = await this.handleRpc(body);
    return reply ? json(reply) : new Response(null, { status: 202 });
  }
}
