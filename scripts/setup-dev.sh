#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
CONFIG_FILE="$ROOT_DIR/dispatch.kimi.swarm.yaml"
EXAMPLE_ENV="$ROOT_DIR/.env.example"
EXAMPLE_CONFIG="$ROOT_DIR/dispatch.kimi.swarm.example.yaml"

resolve_gradle() {
  if [ -n "${GRADLE_BIN:-}" ]; then
    echo "$GRADLE_BIN"
  elif [ -x "$ROOT_DIR/gradlew" ]; then
    echo "$ROOT_DIR/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    command -v gradle
  else
    return 1
  fi
}

write_env() {
  if [ -f "$ENV_FILE" ]; then
    echo "[*] Keeping existing .env"
    return
  fi
  sed "s#^R0RE_WORKSPACE=.*#R0RE_WORKSPACE=$ROOT_DIR#" "$EXAMPLE_ENV" > "$ENV_FILE"
  echo "[+] Created .env"
}

write_config() {
  if [ -f "$CONFIG_FILE" ]; then
    echo "[*] Keeping existing dispatch.kimi.swarm.yaml"
    return
  fi
  cp "$EXAMPLE_CONFIG" "$CONFIG_FILE"
  echo "[+] Created dispatch.kimi.swarm.yaml"
}

load_env() {
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
}

mkdir -p "$ROOT_DIR/output/live-analysis" "$ROOT_DIR/output/kimi-sessions" "$ROOT_DIR/container-android/test_apk"

"$ROOT_DIR/scripts/check-env.sh"
write_env
write_config
load_env

echo "[*] Building Android reverse worker image..."
docker build -t cairn-android-reverse:latest -f "$ROOT_DIR/container-android/Dockerfile" "$ROOT_DIR/container-android"

echo "[*] Building r0re application..."
GRADLE_CMD="$(resolve_gradle)" || {
  echo "Gradle not found. Install Gradle or add ./gradlew, then rerun setup." >&2
  exit 1
}
"$GRADLE_CMD" -p "$ROOT_DIR" bootJar

echo
echo "Setup complete."
echo "Next:"
echo "  1. Put APK files under container-android/test_apk/"
echo "  2. Fill KIMI_MODEL_API_KEY in .env"
echo "  3. Run: source .env"
echo "  4. Run: ./start-r0re.sh --no-bridge --config=$CONFIG_FILE"
