#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_URL="${SERVER_URL:-http://127.0.0.1:8001}"
DB_PATH="${DB_PATH:-$ROOT_DIR/datas/r0re/r0re.db}"
LOCK_DIR="$ROOT_DIR/output/locks"
REPAIR="${1:-}"

status_line() {
  printf "%-24s %s\n" "$1" "$2"
}

pid_status() {
  local name="$1"
  local file="$2"
  if [ ! -f "$file" ]; then
    status_line "$name" "missing pid file"
    return
  fi
  local pid
  pid="$(cat "$file" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    status_line "$name" "running pid=$pid"
  else
    status_line "$name" "stale pid=${pid:-empty}"
  fi
}

echo "=== r0re health ==="
pid_status "serve" "$ROOT_DIR/output/r0re-serve.pid"
pid_status "dispatch" "$ROOT_DIR/output/r0re-dispatch.pid"

if command -v curl >/dev/null 2>&1 && curl --noproxy '*' -fsS "$SERVER_URL/projects" >/dev/null 2>&1; then
  status_line "http" "ok $SERVER_URL"
else
  status_line "http" "unreachable $SERVER_URL"
fi

echo
echo "=== locks ==="
if [ -d "$LOCK_DIR" ] && find "$LOCK_DIR" -type f -name 'r0re-*.lock' -print -quit | grep -q .; then
  while IFS= read -r lock; do
    pid="$(sed -n 's/^pid=//p' "$lock" | head -1)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      status="live"
    else
      status="stale"
    fi
    status_line "$(basename "$lock")" "$status pid=${pid:-empty}"
  done < <(find "$LOCK_DIR" -type f -name 'r0re-*.lock' | sort)
else
  status_line "locks" "none"
fi

if [ -f "$DB_PATH" ] && command -v sqlite3 >/dev/null 2>&1; then
  echo
  echo "=== project health ==="
  sqlite3 "$DB_PATH" "select health_status || '/' || completion_status || ' ' || count(*) from project_health group by health_status, completion_status order by 1;"
  echo
  echo "=== automation jobs ==="
  sqlite3 "$DB_PATH" "select id || ' ' || coalesce(last_status,'never') || ' ' || coalesce(last_run_at,'-') from automation_jobs order by id;"
else
  status_line "database" "unavailable $DB_PATH"
fi

if [ "$REPAIR" = "--repair" ]; then
  echo
  "$ROOT_DIR/scripts/r0re-repair.sh"
fi
