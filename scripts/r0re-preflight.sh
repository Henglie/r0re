#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DISPATCH_CONFIG="${1:-$ROOT_DIR/dispatch.kimi.swarm.yaml}"
JAVA_HOME_DEFAULT="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
JAVA_HOME_VALUE="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
GRADLE_BIN="${GRADLE_BIN:-}"

java_major_version() {
  local java_bin="$1"
  local version
  version="$("$java_bin" -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  if [[ "$version" == 1.* ]]; then
    echo "${version#1.}" | cut -d. -f1
  else
    echo "$version" | cut -d. -f1
  fi
}

resolve_gradle_java_home() {
  if [ -n "$JAVA_HOME_VALUE" ] && [ -x "$JAVA_HOME_VALUE/bin/java" ]; then
    if [ "$(java_major_version "$JAVA_HOME_VALUE/bin/java")" -ge 17 ]; then
      echo "$JAVA_HOME_VALUE"
      return 0
    fi
  fi
  if [ -x "$JAVA_HOME_DEFAULT/bin/java" ] && [ "$(java_major_version "$JAVA_HOME_DEFAULT/bin/java")" -ge 17 ]; then
    echo "$JAVA_HOME_DEFAULT"
    return 0
  fi
  return 1
}

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

warn() {
  echo "[WARN] $1"
}

pass() {
  echo "[ OK ] $1"
}

echo "=== r0re preflight ==="
echo "root:   $ROOT_DIR"
echo "config: $DISPATCH_CONFIG"
echo

[ -d "$ROOT_DIR" ] || fail "root dir missing"
[ -f "$DISPATCH_CONFIG" ] || fail "dispatch config not found"
if [ -z "$GRADLE_BIN" ]; then
  if [ -x "$ROOT_DIR/gradlew" ]; then
    GRADLE_BIN="$ROOT_DIR/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
  else
    fail "Gradle not found. Install Gradle or add ./gradlew"
  fi
fi
[ -x "$GRADLE_BIN" ] || fail "gradle not executable: $GRADLE_BIN"
[ -d "$JAVA_HOME_VALUE" ] || fail "JAVA_HOME not found: $JAVA_HOME_VALUE"
[ -x "$JAVA_HOME_VALUE/bin/java" ] || fail "java not found under JAVA_HOME: $JAVA_HOME_VALUE/bin/java"

GRADLE_JAVA_HOME="$(resolve_gradle_java_home)" || fail "unable to find Java 17 runtime for Gradle"
pass "gradle java home: $GRADLE_JAVA_HOME"

command -v docker >/dev/null 2>&1 || fail "docker command not found"
command -v curl >/dev/null 2>&1 || fail "curl command not found"
command -v lsof >/dev/null 2>&1 || warn "lsof not found; port checks will be limited"

pass "basic binaries present"

docker info >/dev/null 2>&1 || fail "docker daemon not available"
pass "docker daemon reachable"

SERVER_URL="$(grep -E '^server:' "$DISPATCH_CONFIG" | head -n1 | cut -d: -f2- | tr -d ' "' )"
IMAGE_NAME="$(grep -E '^[[:space:]]*image:' "$DISPATCH_CONFIG" | head -n1 | cut -d: -f2- | tr -d ' "' )"

[ -n "$SERVER_URL" ] || fail "server url missing in dispatch config"
[ -n "$IMAGE_NAME" ] || fail "container image missing in dispatch config"
pass "dispatch config has server and image"

if docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
  pass "worker image present: $IMAGE_NAME"
else
  warn "worker image missing locally: $IMAGE_NAME"
fi

if [ -d "$ROOT_DIR/output/live-analysis" ] && [ -d "$ROOT_DIR/output/pi-sessions" ]; then
  pass "output directories present"
else
  warn "output directories missing; they will be created on demand"
fi

if curl --noproxy '*' -fsS "$SERVER_URL/projects" >/dev/null 2>&1; then
  pass "server already reachable: $SERVER_URL"
else
  warn "server not reachable yet: $SERVER_URL"
fi

echo
echo "preflight complete"
