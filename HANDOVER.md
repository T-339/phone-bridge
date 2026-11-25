# Phone Bridge: handover for the next Claude chat

*Last updated 25 Nov 2026.*

## What this is
The user (GitHub **T-339**) built an Android app with Claude that lets Claude control his phone through a
custom connector called **Phone**. It's for times he can't get to his PC. On top of that, the phone's
**Essential Key** is now a press-to-talk button for Claude. Everything below works and is installed.

- **Phone:** Nothing Phone (3a) Pro (model A059P), Android 17, screen 1080x2392. Usually on 4G/EE, with no
  Wi-Fi at work.
- **Repo:** github.com/T-339/phone-bridge (public). Attach it with `add_repo` (owner `T-339`, repo
  `phone-bridge`, push access).
  - `android/`: Kotlin accessibility-service app (minSdk 30, no AndroidX, OkHttp WebSocket)
  - `cloudflare/`: the **live relay** (Worker + Durable Object) at `https://phone-bridge.terrygibbs339.workers.dev`
  - `relay/`: an alternative Python relay for his Raspberry Pi 3 with Tailscale Funnel. It isn't set up and
    is kept as a backup.
  - `BRIEF.md`: design brief for Nothing about the Essential Key feature (see "Plans").
- **Builds:** pushing to `main` makes GitHub Actions build the APK and publish it as release **latest**.
  Changes under `cloudflare/` redeploy the Worker automatically. Claude's workspace can't reach Google's Maven
  or Cloudflare, so all builds go through Actions. To check runs:
  `curl -s https://api.github.com/repos/T-339/phone-bridge/actions/runs?per_page=1`.
  The "Publish" step sometimes fails with GitHub HTTP 500s. If so, run
  `gh run rerun <id> --repo T-339/phone-bridge` later.
- **Tool list:** `TOOLS` in `relay/relay.py` is the source of truth. Regenerate `cloudflare/tools.json` from it
  after any change (the one-liner is in the git history).
- The connector URL and the secret are kept in the user's Claude connector settings and in GitHub secrets.
  They're not in this repo.

## Installing a new build on the phone (Claude does this itself)
1. `open_url` → `https://github.com/T-339/phone-bridge/releases/download/latest/PhoneBridge.apk?b=<sha>`.
   The `?b=` query defeats Firefox's cache; without it Firefox can serve an old APK.
2. In the "Download file with" dialog, pick **Firefox**. Then go to Firefox menu → **Downloads** and tap the
   newest `PhoneBridge(N).apk`.
3. Android shows "Update this app?". The **Update** button is hidden from accessibility, so **the user must tap
   it**.
4. After an update the accessibility service stops. The user turns Phone Bridge off and on again in
   Accessibility settings, and the connector is back within a few seconds.
   If you get "Can't install app / problem with the file", the download was cut short. Download it again.

## Phone tools (connector "Phone")
`phone_status`, `get_screen`, `screenshot`, `tap_element`, `tap`, `swipe`, `scroll`, `type_text`, `press_key`,
`open_app`, `list_apps`, `open_url`, `wait` and `recent_events`. They appear as `mcp__phone__<name>`; if one is
deferred, load it with ToolSearch.
- **"Check recent events" means the Phone connector's `recent_events` tool, not the calendar.** It returns an
  in-memory log of key presses (key code and scan code), window changes, and the Essential Key feature's
  `essential ...` lines. This is the first thing to read when the key misbehaves. It's cleared when the app
  restarts.
- **Screen off:** most tools now wake the phone themselves (`Commands.ensureAwake()` → `WakeActivity`: screen on,
  swipe keyguard dismissed). `phone_status`, `recent_events` and `list_apps` don't wake it.
- **Securely locked** (needs fingerprint/PIN): tools return "The phone is locked…". Claude can't unlock it and
  never types passwords, so ask the user to unlock.

## Essential Key → talk to Claude (done, working)
**How the user uses it: press once to talk, press again to send.** This works with the screen on, the screen
off, and whether or not Claude is open.
- First press: wake the screen if needed → open Claude (`com.anthropic.claude`) → tap **"Start speech input"**
  → **one buzz** when "Stop dictation" appears (listening).
- Second press: tap **"Stop dictation"** → wait for the text → tap **"Send"**.
- Voice commands, handled by the app before anything is sent:
  - **"New chat, …"** at the start → taps "New session" and sends the rest in the new chat. With nothing
    after it, the app listens again; the next press sends.
  - **"Cancel (that)" / "scratch that" / "never mind" / "forget it"** → clears the box, sends nothing,
    double buzz.
- **Two buzzes** = voice didn't start, or the phone is securely locked.
- A press while it's still starting up is ignored.
- Settings in the app under "4. Essential Key": master switch (off = normal Essential Space), "Send on the
  second press" (auto-send, default on), "If Essential Space still opens, close it".
- The notification has three buttons: **"Essential Key: Claude/normal"** (toggle), **Lock**, **Turn off**.
- For Essential Space, switch the key to normal with the notification button.

**Technical facts learned:**
- The key arrives as `KEYCODE_UNKNOWN` (code 0), **scan code 250**.
- Nothing's system opens Essential Space (`com.nothing.ntessentialrecorder`) on key-down **before** accessibility
  sees it, so consuming the key can't stop it. Nothing OS has no remap option on this phone (checked 25 Nov).
- Workaround, and it's set: **Settings → Intelligence toolkit → Essential Key Settings → "Activate with Single
  Tap Before Use" = ON**. A lone press then only arms the key. A second press within 2 s still opens Essential
  Space; the app closes it.
- With the screen off, the system usually uses key-down to wake the screen and the app gets only key-up. That's
  why hold-to-talk was dropped. The app treats a key-up with no key-down as a press.
- Code: `android/app/src/main/java/dev/phonebridge/EssentialKey.kt`. Claude's buttons are found by their
  accessibility labels ("Start speech input", "Stop dictation", "Send", "New session"), so a Claude app update
  could break this. If so, `dumpButtons()` lines in `recent_events` show the new labels.

**Known limitations:**
- Claude's dictation picks up background voices. The user knows; quieter room or mic close to the mouth. If a
  message looks like stray background talk, ask before acting on it.
- History: tried hold-to-talk first (unreliable from a dark screen), and a "hold the reply box" voice method
  that was never tested and has been removed from the UI.

## Screen off / locking (phone settings changed by Claude, 25 Nov)
- **"Power button instantly locks" = OFF**, **"Lock after screen timeout" = 30 minutes** (Settings → Security →
  Screen lock). The power button now only turns the screen off; the phone locks properly 30 minutes later.
- Locking on purpose (immediate secure lock, `GLOBAL_ACTION_LOCK_SCREEN`): the **Lock** button in the Phone
  Bridge notification, the **"Lock phone"** Quick Settings tile, or **Lock now** in the app.
- Trade-off the user accepted: for those 30 minutes anyone can open the phone.

## Battery (checked 25 Nov)
Phone Bridge used **under 1%** since the last full charge, with about 2 h in the background. The big users were
Claude (screen time), Firefox (around 40 open tabs) and Spotify. If battery ever becomes a problem, the
WebSocket ping (every 20 s, `BridgeService`) is the knob to turn.

## Plans
- **Reminder on 9 Dec 2026, 18:12**, in the original chat: ask how the key has been day to day, fill in the
  "Day-to-day notes" section of `BRIEF.md`, then help draft an email to Nothing linking the brief and repo.
  The user is happy for Nothing to use the idea and code freely. Nothing has said Nothing OS 5.1 will add
  Essential Key remapping.

## Other notes
- **Safety:** Claude never types passwords. The app has a **Blocked apps** list and a **Turn off** kill switch.
  Ask before sending messages, buying anything, deleting data or changing security settings.
- **Steam (side issue, unresolved):** Steam on the phone says "Something went wrong… try again later" after a
  password change. **Don't clear Steam's storage:** it could wipe his Steam Guard authenticator.
- **User preference:** he wants Claude to do things himself (install builds, change settings, put files in
  place) rather than hand steps back.
