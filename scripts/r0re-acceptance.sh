#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_URL="${SERVER_URL:-http://127.0.0.1:8001}"
DISPATCH_CONFIG="${DISPATCH_CONFIG:-$ROOT_DIR/dispatch.kimi.swarm.yaml}"
APK_PATH="${1:-$ROOT_DIR/container-android/test_apk/sample.apk}"
WAIT_SECONDS="${WAIT_SECONDS:-12}"
WATCH_ROUNDS="${WATCH_ROUNDS:-1}"
WATCH_INTERVAL_SECONDS="${WATCH_INTERVAL_SECONDS:-2}"

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || fail "command not found: $1"
}

require python3

echo "=== r0re acceptance ==="
echo "server: $SERVER_URL"
echo "config: $DISPATCH_CONFIG"
echo "apk:    $APK_PATH"
echo

"$ROOT_DIR/scripts/r0re-preflight.sh" "$DISPATCH_CONFIG"
echo

SMOKE_OUTPUT="$("$ROOT_DIR/scripts/r0re-smoke-task.sh" "$APK_PATH" 2>&1)"
echo "$SMOKE_OUTPUT"
echo

PROJECT_ID="$(python3 - <<'PY' "$SMOKE_OUTPUT"
import re
import sys

text = sys.argv[1]
match = re.search(r"project created:\s*(proj_\d+)", text)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
)" || fail "failed to extract project id from smoke output"

echo "=== runtime acceptance watch ==="
INTERVAL_SECONDS="$WATCH_INTERVAL_SECONDS" MAX_ROUNDS="$WATCH_ROUNDS" \
  "$ROOT_DIR/scripts/r0re-runtime-watch.sh" "$PROJECT_ID"
