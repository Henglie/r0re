#!/bin/bash

set -euo pipefail

SERVER_URL="${SERVER_URL:-http://127.0.0.1:8001}"
JOB_ID="${1:-all}"

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || fail "command not found: $1"
}

require curl

if [ "$JOB_ID" = "list" ]; then
  curl --noproxy '*' -fsS "$SERVER_URL/automation/jobs"
  echo
  exit 0
fi

if [ "$JOB_ID" = "all" ]; then
  curl --noproxy '*' -fsS -X POST "$SERVER_URL/automation/run"
  echo
  exit 0
fi

curl --noproxy '*' -fsS -X POST "$SERVER_URL/automation/jobs/$JOB_ID/run"
echo
