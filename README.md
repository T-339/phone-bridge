# Phone Bridge

Lets Claude see and control your Android phone from any Claude conversation, with no PC needed.

```
Claude ──HTTPS (MCP)──▶ Raspberry Pi relay ◀──WebSocket (outgoing)── Phone Bridge app
                         (Tailscale Funnel)                         (accessibility service)
```

- **android/**: the app. GitHub Actions builds it on every push and publishes `PhoneBridge.apk` as the
  **latest** release.
- **relay/**: a small Python server for the Pi. Claude connects to `/mcp/<secret>`, and the phone keeps
  `/phone/<secret>` open.

## Setup

### 1. Pi (one time)
SSH into the Pi (e.g. from your phone with Termux or JuiceSSH), then run:
```bash
git clone https://github.com/T-339/phone-bridge.git
cd phone-bridge/relay && sudo ./install.sh
```
The installer sets up the relay as a service and installs Tailscale. Open the login link it prints, and
the Funnel link if it shows one. At the end it prints **two URLs**. Keep both private.

Show them again later with: `sudo /opt/phone-bridge/show-urls.sh`
Update the relay: `cd phone-bridge && git pull && cd relay && sudo ./install.sh`

### 2. Phone
1. On the phone, open **github.com/T-339/phone-bridge/releases/latest** and download `PhoneBridge.apk`.
   Install it and allow installs from your browser if Android asks.
2. Open **Phone Bridge**, paste the `wss://…/phone/…` URL and tap **Save and connect**.
3. Tap **Open accessibility settings**, then **Phone Bridge**, and turn it on.
   If the switch is greyed out, go to App info → ⋮ → **Allow restricted settings** and try again.
4. Tap **Allow running without battery limits**.

The notification should say **Connected**. Tap **Turn off** in it at any time to cut access completely.

### 3. Claude
On claude.ai, go to **Settings → Connectors → Add custom connector**. Name it *Phone* and use the
`https://…/mcp/…` URL. Turn the connector on in a chat and ask Claude to do something on your phone.

## Tools Claude gets
`phone_status`, `get_screen` (element list with ids), `screenshot`, `tap_element`, `tap`, `swipe`,
`scroll`, `type_text`, `press_key` (back/home/recents/notifications/…), `open_app`, `list_apps`,
`open_url`, `wait`.

## Safety
- The secret in the URLs is the only key. If it leaks, delete `/etc/phone-bridge.env` on the Pi, rerun
  `install.sh`, and update both the app and the connector.
- Add banking or other sensitive apps to **Blocked apps** in the app. Apps that block screenshots stay blocked
  from screenshots anyway.
- Claude can't get past your lock screen. The phone needs to be unlocked.
- The signing key in `android/keystore` is only there so updates install over each other. It doesn't protect anything.
