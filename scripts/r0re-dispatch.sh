#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
JAVA_HOME_VALUE="${JAVA_HOME:-$DEFAULT_JAVA_HOME}"
GRADLE_BIN="${GRADLE_BIN:-}"
DISPATCH_CONFIG="${R0RE_DISPATCH_CONFIG:-$ROOT_DIR/dispatch.kimi.swarm.yaml}"
PID_FILE="$ROOT_DIR/output/r0re-dispatch.pid"
LOG_FILE="$ROOT_DIR/output/r0re-dispatch.log"
LOCK_DIR="$ROOT_DIR/output/locks"
LOCK_FILE=""
BACKGROUND=false
SKIP_BUILD=false
STOP=false
ALLOW_EMBEDDED=false

for arg in "$@"; do
    case "$arg" in
        --background) BACKGROUND=true ;;
        --skip-build) SKIP_BUILD=true ;;
        --stop) STOP=true ;;
        --allow-embedded) ALLOW_EMBEDDED=true ;;
        --config=*) DISPATCH_CONFIG="${arg#*=}" ;;
        --pid-file=*) PID_FILE="${arg#*=}" ;;
        --log-file=*) LOG_FILE="${arg#*=}" ;;
        *) echo "unknown arg: $arg" >&2; exit 1 ;;
    esac
done

mkdir -p "$ROOT_DIR/output" "$LOCK_DIR"

resolve_gradle_bin() {
    if [ -n "$GRADLE_BIN" ]; then
        echo "$GRADLE_BIN"
        return 0
    fi
    if [ -x "$ROOT_DIR/gradlew" ]; then
        echo "$ROOT_DIR/gradlew"
        return 0
    fi
    if command -v gradle >/dev/null 2>&1; then
        command -v gradle
        return 0
    fi
    echo "unable to find Gradle; install gradle or add ./gradlew" >&2
    return 1
}

profile_id() {
    local raw="$1"
    local resolved
    if command -v realpath >/dev/null 2>&1; then
        resolved="$(realpath "$raw" 2>/dev/null || printf "%s" "$raw")"
    else
        resolved="$raw"
    fi
    printf "%s" "$resolved" | sed 's#[^A-Za-z0-9_.-]#_#g'
}

LOCK_FILE="$LOCK_DIR/r0re-dispatch-$(profile_id "$DISPATCH_CONFIG").lock"

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

resolve_java_bin() {
    local candidate_home="${JAVA_HOME_VALUE:-}"
    local candidate_bin=""
    local candidate_major=""
    if [ -n "$candidate_home" ] && [ -x "$candidate_home/bin/java" ]; then
        candidate_bin="$candidate_home/bin/java"
        candidate_major="$(java_major_version "$candidate_bin")"
        if [ "${candidate_major:-0}" -ge 17 ]; then
            echo "$candidate_bin"
            return 0
        fi
    fi
    if [ "$candidate_home" != "$DEFAULT_JAVA_HOME" ] && [ -x "$DEFAULT_JAVA_HOME/bin/java" ]; then
        candidate_bin="$DEFAULT_JAVA_HOME/bin/java"
        candidate_major="$(java_major_version "$candidate_bin")"
        if [ "${candidate_major:-0}" -ge 17 ]; then
            echo "$candidate_bin"
            return 0
        fi
    fi
    echo "unable to find Java 17 runtime; current JAVA_HOME=${JAVA_HOME_VALUE:-unset}" >&2
    return 1
}

resolve_gradle_java_home() {
    local candidate_home="${JAVA_HOME_VALUE:-}"
    local candidate_major=""
    if [ -n "$candidate_home" ] && [ -x "$candidate_home/bin/java" ]; then
        candidate_major="$(java_major_version "$candidate_home/bin/java")"
        if [ "${candidate_major:-0}" -ge 17 ]; then
            echo "$candidate_home"
            return 0
        fi
    fi
    if [ -x "$DEFAULT_JAVA_HOME/bin/java" ]; then
        candidate_major="$(java_major_version "$DEFAULT_JAVA_HOME/bin/java")"
        if [ "${candidate_major:-0}" -ge 17 ]; then
            echo "$DEFAULT_JAVA_HOME"
            return 0
        fi
    fi
    echo "unable to find Java 17 runtime for Gradle; current JAVA_HOME=${JAVA_HOME_VALUE:-unset}" >&2
    return 1
}

stop_process() {
    if [ -f "$PID_FILE" ]; then
        PID="$(cat "$PID_FILE" 2>/dev/null || true)"
        if [ -n "${PID:-}" ] && kill -0 "$PID" 2>/dev/null; then
            kill "$PID" 2>/dev/null || true
        fi
        rm -f "$PID_FILE"
    fi
    rm -f "$LOCK_FILE"
}

assert_no_live_lock() {
    if [ ! -f "$LOCK_FILE" ]; then
        return
    fi
    local pid
    pid="$(sed -n 's/^pid=//p' "$LOCK_FILE" | head -1)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        echo "dispatch already running for config=$DISPATCH_CONFIG pid=$pid lock=$LOCK_FILE" >&2
        exit 1
    fi
    rm -f "$LOCK_FILE"
}

write_lock() {
    local pid="$1"
    {
        echo "pid=$pid"
        echo "config=$DISPATCH_CONFIG"
        echo "profile=$(profile_id "$DISPATCH_CONFIG")"
        echo "started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "log=$LOG_FILE"
    } > "$LOCK_FILE"
}

if $STOP; then
    stop_process
    exit 0
fi

if ! $SKIP_BUILD; then
    GRADLE_BIN="$(resolve_gradle_bin)"
    GRADLE_JAVA_HOME="$(resolve_gradle_java_home)"
    JAVA_HOME="$GRADLE_JAVA_HOME" "$GRADLE_BIN" -p "$ROOT_DIR" bootJar -q
fi

JAR="$ROOT_DIR/build/libs/r0re-0.1.0.jar"
[ -f "$JAR" ] || { echo "missing jar: $JAR" >&2; exit 1; }
[ -f "$DISPATCH_CONFIG" ] || { echo "missing dispatch config: $DISPATCH_CONFIG" >&2; exit 1; }
JAVA_BIN="$(resolve_java_bin)"

CMD=("$JAVA_BIN" "-jar" "$JAR" "dispatch" "--config" "$DISPATCH_CONFIG")
if $ALLOW_EMBEDDED; then
    CMD+=("--allow-embedded")
fi

if $BACKGROUND; then
    assert_no_live_lock
    stop_process
    nohup "${CMD[@]}" >>"$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    write_lock "$(cat "$PID_FILE")"
    echo "dispatch started pid=$(cat "$PID_FILE") config=$DISPATCH_CONFIG log=$LOG_FILE"
else
    assert_no_live_lock
    write_lock "$$"
    trap 'rm -f "$LOCK_FILE"' EXIT
    exec "${CMD[@]}"
fi
