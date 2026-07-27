#!/bin/bash
# start-r0re.sh — dual-process stack launcher (serve + dispatch)

set -euo pipefail

R0RE_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_SCRIPT="${R0RE_BRIDGE_SCRIPT:-$R0RE_DIR/container-android/scripts/bin/device-bridge.sh}"
DISPATCH_CONFIG="$R0RE_DIR/dispatch.kimi.swarm.yaml"
NO_BRIDGE=false
BRIDGE_ONLY=false
STOP=false
SKIP_PREFLIGHT=false
SKIP_BUILD=false
SUPERVISE=false
SERVER_PORT="${R0RE_SERVER_PORT:-8001}"
SERVER_URL="http://127.0.0.1:$SERVER_PORT"
SERVE_READY_TIMEOUT="${R0RE_SERVE_READY_TIMEOUT:-60}"

for arg in "$@"; do
    case "$arg" in
        --no-bridge) NO_BRIDGE=true ;;
        --bridge-only) BRIDGE_ONLY=true ;;
        --stop) STOP=true ;;
        --skip-preflight) SKIP_PREFLIGHT=true ;;
        --skip-build) SKIP_BUILD=true ;;
        --supervise) SUPERVISE=true ;;
        --config=*) DISPATCH_CONFIG="${arg#*=}" ;;
        --port=*) SERVER_PORT="${arg#*=}"; SERVER_URL="http://127.0.0.1:$SERVER_PORT" ;;
        *) echo "unknown arg: $arg" >&2; exit 1 ;;
    esac
done

start_bridge() {
    if $NO_BRIDGE; then
        echo "[*] USB bridge skipped (--no-bridge)"
        return
    fi
    if [ ! -x "$BRIDGE_SCRIPT" ]; then
        echo "[!] Bridge script not found: $BRIDGE_SCRIPT"
        return
    fi
    echo "=== Starting USB Device Bridge ==="
    "$BRIDGE_SCRIPT" usb start || {
        echo "[!] USB bridge failed; static analysis can still run"
        $BRIDGE_ONLY && exit 1
    }
}

stop_all() {
    echo "=== Stopping r0re stack ==="
    bash "$R0RE_DIR/scripts/r0re-dispatch.sh" --stop
    bash "$R0RE_DIR/scripts/r0re-serve.sh" --stop "--port=$SERVER_PORT"
    if [ -x "$BRIDGE_SCRIPT" ] && ! $NO_BRIDGE; then
        "$BRIDGE_SCRIPT" usb stop || true
    fi
}

wait_for_serve() {
    local deadline=$((SECONDS + SERVE_READY_TIMEOUT))
    echo "=== Waiting for r0re serve ready ==="
    while [ "$SECONDS" -lt "$deadline" ]; do
        if command -v curl >/dev/null 2>&1 && curl --noproxy '*' -fsS "$SERVER_URL/projects" >/dev/null 2>&1; then
            echo "serve ready: $SERVER_URL"
            return 0
        fi
        sleep 1
    done
    echo "serve did not become ready within ${SERVE_READY_TIMEOUT}s: $SERVER_URL" >&2
    echo "Serve log: $R0RE_DIR/output/r0re-serve.log" >&2
    return 1
}

read_pid_file() {
    local file="$1"
    if [ ! -f "$file" ]; then
        return
    fi
    tr -d '[:space:]' < "$file"
}

repair_runtime_state() {
    bash "$R0RE_DIR/scripts/r0re-repair.sh" >/dev/null
}

start_serve_process() {
    echo "=== Starting r0re serve ==="
    repair_runtime_state
    bash "$R0RE_DIR/scripts/r0re-serve.sh" "${SERVE_ARGS[@]}"
    wait_for_serve
}

start_dispatch_process() {
    echo "=== Starting r0re dispatch ==="
    repair_runtime_state
    R0RE_DISPATCH_CONFIG="$DISPATCH_CONFIG" bash "$R0RE_DIR/scripts/r0re-dispatch.sh" "${DISPATCH_ARGS[@]}"
}

restart_dispatch_child() {
    echo "=== Restarting r0re dispatch ==="
    bash "$R0RE_DIR/scripts/r0re-dispatch.sh" --stop || true
    sleep 2
    start_dispatch_process
}

restart_serve_child() {
    echo "=== Restarting r0re serve ==="
    bash "$R0RE_DIR/scripts/r0re-dispatch.sh" --stop || true
    bash "$R0RE_DIR/scripts/r0re-serve.sh" --stop "--port=$SERVER_PORT" || true
    sleep 2
    start_serve_process
    start_dispatch_process
}

supervise_children() {
    local serve_pid="$1"
    local dispatch_pid="$2"
    echo "=== Supervising r0re stack ==="
    echo "serve pid: $serve_pid"
    echo "dispatch pid: $dispatch_pid"
    trap 'stop_all' EXIT INT TERM
    while true; do
        if ! kill -0 "$serve_pid" 2>/dev/null; then
            echo "serve exited pid=$serve_pid" >&2
            restart_serve_child
            serve_pid="$(read_pid_file "$R0RE_DIR/output/r0re-serve.pid")"
            dispatch_pid="$(read_pid_file "$R0RE_DIR/output/r0re-dispatch.pid")"
            echo "serve restarted pid=$serve_pid"
            echo "dispatch restarted pid=$dispatch_pid"
            continue
        fi
        if ! kill -0 "$dispatch_pid" 2>/dev/null; then
            echo "dispatch exited pid=$dispatch_pid" >&2
            restart_dispatch_child
            dispatch_pid="$(read_pid_file "$R0RE_DIR/output/r0re-dispatch.pid")"
            echo "dispatch restarted pid=$dispatch_pid"
            continue
        fi
        sleep 2
    done
}

if $STOP; then
    stop_all
    exit 0
fi

if $BRIDGE_ONLY; then
    start_bridge
    exit 0
fi

if ! $SKIP_PREFLIGHT; then
    "$R0RE_DIR/scripts/r0re-preflight.sh" "$DISPATCH_CONFIG"
    echo
fi

start_bridge

SERVE_ARGS=(--background "--port=$SERVER_PORT")
DISPATCH_ARGS=(--background "--config=$DISPATCH_CONFIG")
if $SKIP_BUILD; then
    SERVE_ARGS+=(--skip-build)
    DISPATCH_ARGS+=(--skip-build)
fi

start_serve_process
start_dispatch_process

echo
echo "WebUI: $SERVER_URL"
echo "Serve log: $R0RE_DIR/output/r0re-serve.log"
echo "Dispatch log: $R0RE_DIR/output/r0re-dispatch.log"
echo "Use './start-r0re.sh --stop' to stop both processes."

if $SUPERVISE; then
    SERVE_PID="$(read_pid_file "$R0RE_DIR/output/r0re-serve.pid")"
    DISPATCH_PID="$(read_pid_file "$R0RE_DIR/output/r0re-dispatch.pid")"
    if [ -z "${SERVE_PID:-}" ] || [ -z "${DISPATCH_PID:-}" ]; then
        echo "missing child pid after startup" >&2
        exit 1
    fi
    supervise_children "$SERVE_PID" "$DISPATCH_PID"
fi
