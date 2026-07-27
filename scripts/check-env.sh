#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

failures=0

check_cmd() {
  if command -v "$1" >/dev/null 2>&1; then
    echo "[ OK ] $1"
  else
    echo "[FAIL] missing command: $1" >&2
    failures=$((failures + 1))
  fi
}

check_java() {
  if command -v java >/dev/null 2>&1; then
    local version major
    version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
    if [[ "$version" == 1.* ]]; then
      major="$(echo "${version#1.}" | cut -d. -f1)"
    else
      major="$(echo "$version" | cut -d. -f1)"
    fi
    if [ "${major:-0}" -ge 17 ]; then
      echo "[ OK ] Java $version"
      return
    fi
    echo "[FAIL] Java 17+ required, found $version" >&2
  else
    echo "[FAIL] missing command: java" >&2
  fi
  failures=$((failures + 1))
}

check_gradle() {
  if [ -x "$ROOT_DIR/gradlew" ]; then
    echo "[ OK ] Gradle wrapper"
  elif command -v gradle >/dev/null 2>&1; then
    echo "[ OK ] gradle"
  else
    echo "[FAIL] Gradle not found. Install Gradle or add a Gradle wrapper." >&2
    failures=$((failures + 1))
  fi
}

echo "=== r0re environment check ==="
check_java
check_gradle
check_cmd docker
check_cmd curl
check_cmd python3

if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    echo "[ OK ] Docker daemon reachable"
  else
    echo "[FAIL] Docker is installed but the daemon is not running" >&2
    failures=$((failures + 1))
  fi
fi

if [ "$failures" -ne 0 ]; then
  echo
  echo "Environment check failed with $failures issue(s)." >&2
  exit 1
fi

echo
echo "Environment check passed."
