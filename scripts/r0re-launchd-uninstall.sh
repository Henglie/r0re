#!/bin/bash

set -euo pipefail

PLIST_DST="$HOME/Library/LaunchAgents/com.fyr.r0re.plist"
DOMAIN="gui/$(id -u)"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if launchctl print "$DOMAIN/com.fyr.r0re" >/dev/null 2>&1; then
  echo "[*] Unloading com.fyr.r0re"
  launchctl bootout "$DOMAIN/com.fyr.r0re" || true
fi

if [ -f "$PLIST_DST" ]; then
  echo "[*] Removing $PLIST_DST"
  rm -f "$PLIST_DST"
fi

"$ROOT_DIR/scripts/r0re-dispatch.sh" --stop >/dev/null 2>&1 || true
"$ROOT_DIR/scripts/r0re-serve.sh" --stop >/dev/null 2>&1 || true

echo "[+] launchd service removed"
