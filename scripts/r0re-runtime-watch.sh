#!/bin/bash

set -euo pipefail

SERVER_URL="${SERVER_URL:-http://127.0.0.1:8001}"
PROJECT_ID="${1:-}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"
MAX_ROUNDS="${MAX_ROUNDS:-0}"

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || fail "command not found: $1"
}

require curl
require python3

[ -n "$PROJECT_ID" ] || fail "usage: ./scripts/r0re-runtime-watch.sh <project_id>"

round=0
while true; do
  round=$((round + 1))
  echo "=== runtime watch: $PROJECT_ID (round $round) ==="
  RUNTIME_JSON="$(curl --noproxy '*' -fsS "$SERVER_URL/projects/$PROJECT_ID/runtime")" || fail "failed to fetch runtime for $PROJECT_ID"
  python3 - <<'PY' "$RUNTIME_JSON"
import json, sys
runtime = json.loads(sys.argv[1])
project = runtime.get("project") or {}
phase_states = runtime.get("phase_states") or []
recovery_history = runtime.get("recovery_history") or []
recent_events = runtime.get("recent_events") or []
intents = runtime.get("intents") or []
schema_mode = "structured" if ("phase_states" in runtime or "recovery_history" in runtime) else "legacy"
schema_version = runtime.get("schema_version")
if not phase_states and intents:
    phase_states = [{
        "scope": f"{scope.get('scope_type')}:{scope.get('scope_id')}",
        "phase": scope.get("phase"),
        "artifact_focus": None,
        "stalled": scope.get("stalled"),
        "latest_progress": scope.get("message") or scope.get("step"),
        "latest_stop_condition": None,
        "latest_recovery_action": None,
    } for scope in intents]
print(json.dumps({
    "project_id": project.get("id"),
    "status": project.get("status"),
    "schema_version": schema_version,
    "runtime_schema": schema_mode,
    "active_reason": (runtime.get("reason") or {}).get("phase"),
    "phase_states": [{
        "scope": state.get("scope") or f"{state.get('scope_type')}:{state.get('scope_id')}",
        "phase": state.get("phase"),
        "artifact_focus": state.get("artifact_focus"),
        "stalled": state.get("stalled"),
        "latest_progress": state.get("latest_progress"),
        "latest_stop_condition": state.get("latest_stop_condition"),
        "latest_recovery_action": state.get("latest_recovery_action"),
    } for state in phase_states],
    "recent_recovery_events": recovery_history[:5],
    "recent_event_types": [event.get("event_type") for event in recent_events[:8]],
    "hint": None if schema_mode == "structured" else "runtime endpoint is still legacy shape; restart r0re to load newest structured runtime fields"
}, ensure_ascii=False, indent=2))
PY
  echo

  if [ "$MAX_ROUNDS" -gt 0 ] && [ "$round" -ge "$MAX_ROUNDS" ]; then
    break
  fi
  sleep "$INTERVAL_SECONDS"
done
