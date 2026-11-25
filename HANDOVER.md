# Phone Bridge – handover for the next Claude chat

## What this is
The user (GitHub **T-339**) built an Android app with Claude that lets Claude control his phone through a
custom connector called **Phone**. It's for times he can't get to his PC. Everything works end to end.

- **Phone:** Nothing Phone (model A059P), Android 17, screen 1080x2392, usually on 4G/EE with no Wi-Fi at work
- **Repo:** github.com/T-339/phone-bridge (public). Attach it with `add_repo` (owner `T-339`, repo `phone-bridge`, push access).
  - `android/` – Kotlin accessibility-service app (minSdk 30, no AndroidX, OkHttp WebSocket)
  - `cloudflare/` – **live relay** (Worker + Durable Object) at `https://phone-bridge.terrygibbs339.workers.dev`
  - `relay/` – alternative Python relay for his Raspberry Pi 3 with Tailscale Funnel (not set up, kept as a backup)
- **Builds:** pushing to `main` makes GitHub Actions build the APK and publish it as release **latest**
  (`/releases/download/latest/PhoneBridge.apk`). Changes under `cloudflare/` redeploy the Worker automatically.
  Claude's workspace can't reach Google's Maven or Cloudflare directly, so all builds and deploys go through Actions.
  Check run status with `curl https://api.github.com/repos/T-339/phone-bridge/actions/runs` (the repo is public).
- **Tool list:** `relay/relay.py` `TOOLS` is the source of truth. Regenerate `cloudflare/tools.json` from it after
  any change (see the git history for the one-liner).
- The connector URL and the secret are stored in the user's Claude connector settings and GitHub secrets.
  They're not in this file.

## Phone tools (connector "Phone")
`phone_status`, `get_screen`, `screenshot`, `tap_element`, `tap`, `swipe`, `scroll`, `type_text`, `press_key`,
`open_app`, `list_apps`, `open_url`, `wait`, and **`recent_events`** (new, see below).
Tool names appear as `mcp__phone__<name>`. If one isn't loaded, find it with ToolSearch.

**Important:** "check recent events" means the **Phone** connector's `recent_events` tool,
**not** the calendar. If it isn't there, the Phone connector may be off in this chat, or the chat started
before the tool existed.

## Essential Key hold-to-talk (done, working)
- Key arrives as KEYCODE_UNKNOWN code 0, **scan 250**. Nothing's system opens Essential Space
  (`com.nothing.ntessentialrecorder`) on key-down *before* accessibility sees it, so consuming the key can't stop it.
- Fix: Nothing setting **Intelligence toolkit → Essential Key Settings → "Activate with Single Tap Before Use" = ON**.
  Then a lone press only arms the key; Phone Bridge handles it. Tap + press again within 2 s = Essential Space.
- App (`EssentialKey.kt`, setting "4. Essential Key", notification toggle): hold = open Claude + tap "Start speech
  input", release = tap "Stop dictation". Single tap opens Claude after the 2 s window. A second press within 2 s is
  left to Essential Space. Voice method "hold the reply box" exists too but is untested.

- Vibration: one buzz when Claude's "Stop dictation" appears (listening), two if voice didn't start.

- Screen off: the key-down is used by the system to wake the screen, so only key-UP reaches the app
  (duration from event.downTime). Then "toggle mode": wake + open Claude + start voice; next press stops it.

- Auto-send (setting, default on): after "Stop dictation" the app waits for the text, then taps Send.
  Text starting "new chat …" → app taps "New session", sends the rest there (or listens again if nothing
  follows; press the key to stop+send). "cancel (that)"/"scratch that"/"never mind"/"forget it" → clears the box, double buzz.

## Screen off / locking
- Phone settings: "Power button instantly locks" OFF, "Lock after screen timeout" long, so the power button
  only turns the screen off. Lock on purpose with the notification's **Lock** button, the **Lock phone** QS tile,
  or "Lock now" in the app (all GLOBAL_ACTION_LOCK_SCREEN = immediate secure lock).
- `Commands.ensureAwake()`: before most tools, if the screen is off it starts `WakeActivity` (turn screen on +
  dismiss swipe keyguard). If `isDeviceLocked` (needs PIN/fingerprint) it returns a clear error instead.

## Original plan (history)
**Goal:** holding the phone's **Essential Key** (Nothing hardware button) should open Claude and start voice
input, so his speech goes to Claude. Right now a hold starts Nothing's own Essential Space dictation. That's
fine to keep, but **never block or consume the key**, so the system isn't confused.

**Step 1 (done and installed):** the latest build logs every hardware key press
(`onKeyEvent`, `flagRequestFilterKeyEvents`; it always returns `false`) and every window change (package/class)
into an in-memory log. That log is what `recent_events(clear?)` reads.

**Next:**
1. Ask him to press the Essential Key once briefly and then hold it for about 2 s. Then call `recent_events`.
2. If a **key code** shows up, add an option in the app: on a long press of that code, open Claude
   (`com.anthropic.claude`), then start voice input. Either tap "Start speech input" in the input bar, or
   use Android `SpeechRecognizer` in Phone Bridge and type the text into Claude's input field and send it.
   Make it a setting with an on/off switch.
3. If **no key** shows up, use the window events instead: the
   `com.nothing.ntessentialspace` overlay appearing is the trigger, so react to that.
4. If he has to turn the accessibility service off and on after updating, that's normal.

## Other notes
- **Safety:** Claude never types passwords. The app has a **Blocked apps** list and a **Turn off** kill switch in
  its notification. Ask before sending messages, buying anything, deleting data or changing security settings.
- **Steam (side issue):** Steam on the phone says "Something went wrong… try again later" after a password change.
  The app is up to date, Claude force stopped it and its cache is now empty. A 1-hour timer was set at
  ~14:55. **Don't clear Steam's storage:** it could wipe his Steam Guard authenticator.
- **User preference:** he wants Claude to do things himself, such as installing files and putting things in place,
  instead of handing steps back to him.
