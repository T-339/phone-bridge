#!/usr/bin/env bash
# Phone Bridge relay installer for Raspberry Pi OS (or any Debian-based Linux).
# Run from the repo:   cd phone-bridge/relay && sudo ./install.sh
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Please run with sudo:  sudo ./install.sh"; exit 1
fi
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "==> Installing packages"
apt-get update -qq
apt-get install -y -qq python3 python3-aiohttp curl >/dev/null

echo "==> Installing relay to /opt/phone-bridge"
install -d /opt/phone-bridge
install -m 0644 "$HERE/relay.py" /opt/phone-bridge/relay.py

if [ ! -f /etc/phone-bridge.env ]; then
  SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
  printf 'BRIDGE_SECRET=%s\nBRIDGE_PORT=8787\n' "$SECRET" > /etc/phone-bridge.env
  chmod 600 /etc/phone-bridge.env
fi
# shellcheck disable=SC1091
. /etc/phone-bridge.env

cat > /etc/systemd/system/phone-bridge.service <<'EOF'
[Unit]
Description=Phone Bridge relay (Claude <-> Android)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=/etc/phone-bridge.env
ExecStart=/usr/bin/python3 /opt/phone-bridge/relay.py
Restart=always
RestartSec=3
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now phone-bridge.service
systemctl restart phone-bridge.service

echo "==> Setting up Tailscale"
if ! command -v tailscale >/dev/null; then
  curl -fsSL https://tailscale.com/install.sh | sh
fi
if ! tailscale status >/dev/null 2>&1; then
  echo
  echo "Tailscale needs you to log in. Open the link it prints below on your phone and sign in."
  tailscale up
fi

echo "==> Turning on Funnel (public HTTPS) for port $BRIDGE_PORT"
echo "    If it prints a link, open it to allow Funnel for this device, then it will continue."
tailscale funnel --bg "$BRIDGE_PORT"

HOST="$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"

cat <<EOF

============================================================
 Phone Bridge relay is running.

 1) In the Android app, paste this as the Relay URL:

    wss://$HOST/phone/$BRIDGE_SECRET

 2) In Claude (Settings > Connectors > Add custom connector),
    use this URL:

    https://$HOST/mcp/$BRIDGE_SECRET

 Keep both URLs private: anyone with them can control the phone.
 Show them again any time with:  sudo /opt/phone-bridge/show-urls.sh
============================================================
EOF

cat > /opt/phone-bridge/show-urls.sh <<'EOF'
#!/usr/bin/env bash
. /etc/phone-bridge.env
HOST="$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
echo "App relay URL:     wss://$HOST/phone/$BRIDGE_SECRET"
echo "Claude connector:  https://$HOST/mcp/$BRIDGE_SECRET"
EOF
chmod 700 /opt/phone-bridge/show-urls.sh
