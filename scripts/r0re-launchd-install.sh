#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PLIST_SRC="$ROOT_DIR/launchd/com.fyr.r0re.plist"
PLIST_DST="$HOME/Library/LaunchAgents/com.fyr.r0re.plist"
PLIST_TMP="$ROOT_DIR/output/com.fyr.r0re.launchd.plist"
JAVA_HOME_DEFAULT="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
JAVA_HOME_VALUE="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
GRADLE_BIN="${GRADLE_BIN:-}"
GRADLE_USER_HOME_VALUE="${GRADLE_USER_HOME:-/private/tmp/r0re-gradle-home17}"
SKIP_BUILD_VALUE="${SKIP_BUILD:-0}"
R0RE_PORT_VALUE="${R0RE_PORT:-8001}"
R0RE_CONFIG_VALUE="${R0RE_CONFIG:-$ROOT_DIR/dispatch.kimi.swarm.yaml}"
DOMAIN="gui/$(id -u)"

export JAVA_HOME="$JAVA_HOME_VALUE"
export GRADLE_USER_HOME="$GRADLE_USER_HOME_VALUE"
export PATH="$JAVA_HOME/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"

mkdir -p "$HOME/Library/LaunchAgents"
mkdir -p "$ROOT_DIR/output"
chmod +x "$ROOT_DIR/start-r0re.sh" "$ROOT_DIR/scripts/r0re-serve.sh" "$ROOT_DIR/scripts/r0re-dispatch.sh"

escape_sed() {
  printf '%s' "$1" | sed -e 's/[\/&]/\\&/g'
}

if [ "$SKIP_BUILD_VALUE" != "1" ]; then
  echo "[*] Building bootJar..."
  if [ -z "$GRADLE_BIN" ]; then
    if [ -x "$ROOT_DIR/gradlew" ]; then
      GRADLE_BIN="$ROOT_DIR/gradlew"
    elif command -v gradle >/dev/null 2>&1; then
      GRADLE_BIN="$(command -v gradle)"
    else
      echo "unable to find Gradle; install gradle or add ./gradlew" >&2
      exit 1
    fi
  fi
  "$GRADLE_BIN" -p "$ROOT_DIR" bootJar
else
  echo "[*] SKIP_BUILD=1, reusing existing bootJar"
fi

[ -f "$R0RE_CONFIG_VALUE" ] || {
  echo "missing dispatch config: $R0RE_CONFIG_VALUE" >&2
  exit 1
}

echo "[*] Rendering plist for port=$R0RE_PORT_VALUE config=$R0RE_CONFIG_VALUE"
sed \
  -e "s#__R0RE_ROOT__#$(escape_sed "$ROOT_DIR")#g" \
  -e "s/__R0RE_PORT__/$(escape_sed "$R0RE_PORT_VALUE")/g" \
  -e "s#__R0RE_CONFIG__#$(escape_sed "$R0RE_CONFIG_VALUE")#g" \
  "$PLIST_SRC" > "$PLIST_TMP"

echo "[*] Installing plist to $PLIST_DST"
cp "$PLIST_TMP" "$PLIST_DST"

if launchctl print "$DOMAIN/com.fyr.r0re" >/dev/null 2>&1; then
  echo "[*] Service already loaded, reloading..."
  launchctl bootout "$DOMAIN/com.fyr.r0re" || true
fi

launchctl bootstrap "$DOMAIN" "$PLIST_DST"
launchctl enable "$DOMAIN/com.fyr.r0re"
launchctl kickstart -k "$DOMAIN/com.fyr.r0re"

echo "[+] launchd service installed"
echo "    Label: com.fyr.r0re"
echo "    Port: $R0RE_PORT_VALUE"
echo "    Config: $R0RE_CONFIG_VALUE"
echo "    Stack: $ROOT_DIR/output/r0re-stack.log"
echo "    Serve: $ROOT_DIR/output/r0re-serve.log"
echo "    Disp.: $ROOT_DIR/output/r0re-dispatch.log"
echo "    WebUI: http://127.0.0.1:$R0RE_PORT_VALUE"
