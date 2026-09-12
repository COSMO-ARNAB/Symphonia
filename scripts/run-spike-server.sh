#!/bin/sh
# Starts the Gate 2 spike rig server on the laptop's Wi-Fi LAN.
# - detects the LAN IPv4 (skips Windows Mobile Hotspot 192.168.137.x)
# - runs signaling with UNSAFE_ALLOW_REMOTE=true + announced address
# - prints the URLs for the phone app and the Chrome listener tab
#
# THROWAWAY spike helper - local network only, never expose to the internet.
set -e
cd "$(dirname "$0")/../signaling"

LAN_IP=$(node -e "
const os = require('os');
const ifs = os.networkInterfaces();
const picks = [];
for (const list of Object.values(ifs)) {
  for (const i of list || []) {
    if (i.family === 'IPv4' && !i.internal) picks.push(i.address);
  }
}
// Prefer non-hotspot (Windows Mobile Hotspot uses 192.168.137.x)
const preferred = picks.find(a => !a.startsWith('192.168.137.')) || picks[0];
if (!preferred) { console.error('no LAN IPv4 found'); process.exit(1); }
console.log(preferred);
")

PORT=8080
WEBRTC_PORT=44444
PAGE_ORIGIN="http://${LAN_IP}:${PORT}"

echo "=============================================="
echo " Symphonia Gate2 Spike rig"
echo "=============================================="
echo " LAN IP:        ${LAN_IP}"
echo " Phone app URL: ws://${LAN_IP}:${PORT}   (pre-filled in the spike APK)"
echo " Chrome tab:    ${PAGE_ORIGIN}/          (laptop, this machine)"
echo ""
echo " If Windows Firewall asks: ALLOW both Private and Public for Node.js"
echo " (or run once as admin: netsh advfirewall firewall add rule name=\"gate2-spike\" dir=in action=allow protocol=TCP localport=${PORT},${WEBRTC_PORT})"
echo " UDP ${WEBRTC_PORT} must also be open for WebRTC media; simplest is the firewall GUI prompt."
echo "=============================================="
echo ""

HOST=0.0.0.0 \
UNSAFE_ALLOW_REMOTE=true \
ANNOUNCED_ADDRESS="${LAN_IP}" \
WEBRTC_LISTEN_IP=0.0.0.0 \
WEBRTC_PORT=${WEBRTC_PORT} \
ALLOWED_ORIGINS="${PAGE_ORIGIN}" \
PORT=${PORT} \
node --import tsx src/index.ts
