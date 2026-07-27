#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_URL="${SERVER_URL:-http://127.0.0.1:8001}"
APK_PATH="${1:-$ROOT_DIR/test_apk/ZygiskDetector_1.7.5.apk}"
TITLE="${TITLE:-Smoke Reverse Task}"
ORIGIN="${ORIGIN:-$APK_PATH 分析这个APK的关键检测路径，优先识别 JNI/native/root/zygisk 线索。}"
GOAL="${GOAL:-输出当前样本的关键检测入口、JNI 桥接、以及下一步绕过思路。}"
BOOTSTRAP_ENABLED="${BOOTSTRAP_ENABLED:-true}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || fail "command not found: $1"
}

require curl
require python3

[ -f "$APK_PATH" ] || fail "apk not found: $APK_PATH"

curl --noproxy '*' -fsS "$SERVER_URL/projects" >/dev/null 2>&1 || fail "server not reachable: $SERVER_URL"

PAYLOAD="$(python3 - <<'PY' "$TITLE" "$ORIGIN" "$GOAL" "$BOOTSTRAP_ENABLED"
import json, sys
title, origin, goal, bootstrap = sys.argv[1:5]
print(json.dumps({
    "title": title,
    "origin": origin,
    "goal": goal,
    "bootstrap_enabled": bootstrap.lower() == "true"
}, ensure_ascii=False))
PY
)"

echo "=== create smoke task ==="
echo "server: $SERVER_URL"
echo "apk:    $APK_PATH"
echo

RESPONSE="$(curl --noproxy '*' -fsS -X POST "$SERVER_URL/projects" \
  -H 'content-type: application/json' \
  -d "$PAYLOAD")"

PROJECT_ID="$(python3 - <<'PY' "$RESPONSE"
import json, sys
data = json.loads(sys.argv[1])
print(data["project"]["id"])
PY
)"

echo "[ OK ] project created: $PROJECT_ID"
echo

echo "=== initial runtime ==="
curl --noproxy '*' -fsS "$SERVER_URL/projects/$PROJECT_ID/runtime"
echo
echo

echo "=== initial project ==="
curl --noproxy '*' -fsS "$SERVER_URL/projects/$PROJECT_ID"
echo
echo

echo "=== wait for dispatcher ==="
python3 - <<'PY' "$SERVER_URL" "$PROJECT_ID" "$WAIT_SECONDS"
import json, sys, time, urllib.request
server, project_id, wait_seconds = sys.argv[1], sys.argv[2], int(sys.argv[3])
deadline = time.time() + wait_seconds
last_runtime = None
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
while time.time() < deadline:
    with opener.open(f"{server}/projects/{project_id}/runtime") as resp:
        runtime = json.load(resp)
    last_runtime = runtime
    events = runtime.get("recent_events") or []
    intents = runtime.get("intents") or []
    reason = runtime.get("reason")
    if intents or reason or events:
        print(json.dumps({
            "dispatcher_started": True,
            "intent_count": len(intents),
            "reason_active": reason is not None,
            "recent_event_types": [event.get("event_type") for event in events[:6]]
        }, ensure_ascii=False))
        break
    time.sleep(2)
else:
    print(json.dumps({
        "dispatcher_started": False,
        "intent_count": len((last_runtime or {}).get("intents") or []),
        "reason_active": ((last_runtime or {}).get("reason") is not None),
        "recent_event_types": [event.get("event_type") for event in ((last_runtime or {}).get("recent_events") or [])[:6]]
    }, ensure_ascii=False))
PY
echo
echo

echo "=== host output paths ==="
echo "$ROOT_DIR/output/live-analysis/$PROJECT_ID"
echo "$ROOT_DIR/output/pi-sessions/$PROJECT_ID"
