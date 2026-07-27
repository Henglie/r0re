#!/bin/bash

set -euo pipefail

DOMAIN="gui/$(id -u)"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_URL="${SERVER_URL:-http://127.0.0.1:8001}"

launchctl print "$DOMAIN/com.fyr.r0re"
echo
"$ROOT_DIR/scripts/r0re-health.sh"
echo
echo "--- stack log ---"
tail -n 20 "$ROOT_DIR/output/r0re-stack.log" 2>/dev/null || true
echo
echo "--- serve log ---"
tail -n 20 "$ROOT_DIR/output/r0re-serve.log" 2>/dev/null || true
echo
echo "--- dispatch log ---"
tail -n 20 "$ROOT_DIR/output/r0re-dispatch.log" 2>/dev/null || true
