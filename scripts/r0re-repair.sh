#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_DIR="$ROOT_DIR/output"
LOCK_DIR="$OUTPUT_DIR/locks"

removed=0

remove_if_stale() {
  local kind="$1"
  local path="$2"
  [ -f "$path" ] || return 0
  local pid=""
  while IFS= read -r line; do
    if [[ "$line" == pid=* ]]; then
      pid="${line#pid=}"
      break
    fi
  done < "$path"
  if [ -z "$pid" ]; then
    pid="$(head -1 "$path" 2>/dev/null || true)"
  fi
  if [ -z "$pid" ]; then
    rm -f "$path"
    echo "[fix] removed empty $kind: $path"
    removed=$((removed + 1))
    return 0
  fi
  if kill -0 "$pid" 2>/dev/null; then
    echo "[ok]  live $kind: $path pid=$pid"
    return 0
  fi
  rm -f "$path"
  echo "[fix] removed stale $kind: $path pid=$pid"
  removed=$((removed + 1))
}

echo "=== r0re repair ==="

remove_if_stale "pid file" "$OUTPUT_DIR/r0re-serve.pid"
remove_if_stale "pid file" "$OUTPUT_DIR/r0re-dispatch.pid"

if [ -d "$LOCK_DIR" ]; then
  while IFS= read -r lock; do
    remove_if_stale "lock" "$lock"
  done < <(find "$LOCK_DIR" -type f -name 'r0re-*.lock' | sort)
fi

echo
echo "removed=$removed"
