#!/bin/bash
# device-bridge.sh — macOS 宿主机端口桥接脚本
# 支持 USB 直连和 WiFi 两种模式
#
# 用法:
#   ./device-bridge.sh usb start      # USB模式 (推荐，更简单更稳定)
#   ./device-bridge.sh wifi <IP> start # WiFi模式
#   ./device-bridge.sh usb stop
#   ./device-bridge.sh usb status

set -euo pipefail

MODE="${1:-usb}"
ACTION="${2:-start}"
PHONE_IP="${3:-}"

FRIDA_PORT=14725
PIDFILE_ADB_SERVER="/tmp/device-bridge-adb-server.pid"
PIDFILE_ADB_FORWARD="/tmp/device-bridge-adb-forward.pid"
PIDFILE_SOCAT_ADB="/tmp/device-bridge-socat-adb.pid"
PIDFILE_SOCAT_FRIDA="/tmp/device-bridge-socat-frida.pid"

check_socat() {
    if ! command -v socat &>/dev/null; then
        echo "ERROR: socat not found. Install with: brew install socat"
        exit 1
    fi
}

# ============================================================
# USB 模式
# 原理: Mac 上 adb server 监听 TCP → 容器通过 host.docker.internal 连接
#       Mac 上 adb forward 转发 frida 端口 → 容器通过 host.docker.internal 连 frida
# 不需要 WiFi，不需要 socat，只需要 USB 线
# ============================================================

start_usb() {
    echo "=== Starting USB Device Bridge ==="
    echo "Mode: USB (direct connection)"
    echo ""

    # 检查 USB 设备
    USB_DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "^$" | grep -v "emulator" | head -5)
    if [ -z "$USB_DEVICES" ]; then
        echo "ERROR: No USB device detected. Check:"
        echo "  1. USB cable connected"
        echo "  2. USB debugging enabled on phone"
        echo "  3. Authorized on phone (check for RSA key dialog)"
        echo ""
        echo "Run 'adb devices' to check"
        exit 1
    fi
    echo "[+] USB device detected:"
    echo "$USB_DEVICES"
    echo ""

    # 1. 杀掉默认 adb server，用 TCP 监听模式重启
    echo "[*] Restarting ADB server in TCP mode (0.0.0.0:5037)..."
    adb kill-server 2>/dev/null || true
    sleep 1

    # 启动 adb server 监听所有接口
    adb -a nodaemon server start &
    echo $! > "$PIDFILE_ADB_SERVER"
    sleep 2

    # 验证 adb server 启动
    if ! kill -0 "$(cat "$PIDFILE_ADB_SERVER")" 2>/dev/null; then
        echo "[-] ADB server failed to start"
        exit 1
    fi
    echo "[+] ADB server started (PID $(cat "$PIDFILE_ADB_SERVER"), listening on 0.0.0.0:5037)"

    # 2. 转发 frida-server 端口
    echo "[*] Setting up ADB forward for frida-server (port $FRIDA_PORT)..."
    adb forward tcp:$FRIDA_PORT tcp:$FRIDA_PORT
    echo "[+] ADB forward: localhost:$FRIDA_PORT → device:$FRIDA_PORT"

    # 3. 验证
    echo ""
    echo "=== USB Bridge Active ==="
    echo ""
    echo "From Docker container:"
    echo ""
    echo "  # ADB (set environment variable once)"
    echo "  export ADB_SERVER_SOCKET=tcp:host.docker.internal:5037"
    echo "  adb devices"
    echo ""
    echo "  # Or use helper script"
    echo "  adb-setup.sh connect-usb"
    echo ""
    echo "  # Frida"
    echo "  frida-auto.sh com.target crypto -H host.docker.internal:$FRIDA_PORT"
    echo "  frida -H host.docker.internal:$FRIDA_PORT -f com.target --no-pause"
    echo ""
    echo "  # Frida with -U (USB mode via remote adb server)"
    echo "  # After setting ADB_SERVER_SOCKET, -U works too:"
    echo "  frida -U -f com.target --no-pause"
}

stop_usb() {
    echo "[*] Stopping USB bridge..."

    # 停止 adb server (会恢复默认模式)
    if [ -f "$PIDFILE_ADB_SERVER" ]; then
        PID=$(cat "$PIDFILE_ADB_SERVER")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID" 2>/dev/null || true
            echo "[+] ADB server stopped (PID $PID)"
        fi
        rm -f "$PIDFILE_ADB_SERVER"
    fi

    # 移除端口转发
    adb forward --remove tcp:$FRIDA_PORT 2>/dev/null || true
    echo "[+] ADB forward removed"

    # 恢复默认 adb server
    adb start-server 2>/dev/null || true
    echo "[+] Default ADB server restored"
    echo "[+] USB bridge stopped"
}

status_usb() {
    echo "=== USB Bridge Status ==="

    # 检查 adb server
    if [ -f "$PIDFILE_ADB_SERVER" ] && kill -0 "$(cat "$PIDFILE_ADB_SERVER")" 2>/dev/null; then
        echo "[+] ADB server: RUNNING (PID $(cat "$PIDFILE_ADB_SERVER"), TCP 0.0.0.0:5037)"
    else
        echo "[-] ADB server: NOT RUNNING"
        return
    fi

    # 检查 USB 设备
    USB_DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "^$" | head -5)
    if [ -n "$USB_DEVICES" ]; then
        echo "[+] USB device: CONNECTED"
        echo "$USB_DEVICES"
    else
        echo "[-] USB device: NOT CONNECTED"
    fi

    # 检查端口转发
    FORWARD_LIST=$(adb forward --list 2>/dev/null | grep "$FRIDA_PORT" || true)
    if [ -n "$FORWARD_LIST" ]; then
        echo "[+] Frida forward: ACTIVE"
        echo "    $FORWARD_LIST"
    else
        echo "[-] Frida forward: NOT SET"
    fi

    # 测试端口
    if timeout 2 bash -c "echo >/dev/tcp/localhost/$FRIDA_PORT" 2>/dev/null; then
        echo "[+] localhost:$FRIDA_PORT (frida): accepting connections"
    else
        echo "[-] localhost:$FRIDA_PORT (frida): not responding"
    fi
}

# ============================================================
# WiFi 模式 (原有方案，备选)
# ============================================================

start_wifi() {
    if [ -z "$PHONE_IP" ]; then
        echo "ERROR: WiFi mode requires phone IP"
        echo "Usage: $0 wifi <phone_ip> start"
        exit 1
    fi

    check_socat

    echo "=== Starting WiFi Device Bridge ==="
    echo "Phone: $PHONE_IP"
    echo ""

    stop_wifi 2>/dev/null || true
    sleep 1

    echo "[*] Starting ADB bridge (port 5555)..."
    socat TCP-LISTEN:5555,fork,reuseaddr,bind=0.0.0.0 TCP:${PHONE_IP}:5555 &
    echo $! > "$PIDFILE_SOCAT_ADB"

    echo "[*] Starting Frida bridge (port $FRIDA_PORT)..."
    socat TCP-LISTEN:${FRIDA_PORT},fork,reuseaddr,bind=0.0.0.0 TCP:${PHONE_IP}:${FRIDA_PORT} &
    echo $! > "$PIDFILE_SOCAT_FRIDA"

    sleep 1

    if kill -0 "$(cat "$PIDFILE_SOCAT_ADB")" 2>/dev/null; then
        echo "[+] ADB bridge: RUNNING (localhost:5555 → $PHONE_IP:5555)"
    fi
    if kill -0 "$(cat "$PIDFILE_SOCAT_FRIDA")" 2>/dev/null; then
        echo "[+] Frida bridge: RUNNING (localhost:$FRIDA_PORT → $PHONE_IP:$FRIDA_PORT)"
    fi

    echo ""
    echo "From Docker container:"
    echo "  adb connect host.docker.internal:5555"
    echo "  frida -H host.docker.internal:$FRIDA_PORT -f com.target --no-pause"
}

stop_wifi() {
    for pidfile in "$PIDFILE_SOCAT_ADB" "$PIDFILE_SOCAT_FRIDA"; do
        if [ -f "$pidfile" ]; then
            PID=$(cat "$pidfile")
            kill "$PID" 2>/dev/null || true
            rm -f "$pidfile"
        fi
    done
    pkill -f "socat.*TCP-LISTEN:5555" 2>/dev/null || true
    pkill -f "socat.*TCP-LISTEN:${FRIDA_PORT}" 2>/dev/null || true
    echo "[+] WiFi bridge stopped"
}

# ============================================================
# Main
# ============================================================

case "$MODE" in
    usb)
        case "$ACTION" in
            start)   start_usb ;;
            stop)    stop_usb ;;
            status)  status_usb ;;
            restart) stop_usb; sleep 1; start_usb ;;
            *)       echo "Usage: $0 usb [start|stop|status|restart]"; exit 1 ;;
        esac
        ;;
    wifi)
        case "$ACTION" in
            start)   start_wifi ;;
            stop)    stop_wifi ;;
            *)       echo "Usage: $0 wifi <phone_ip> [start|stop]"; exit 1 ;;
        esac
        ;;
    *)
        echo "Usage:"
        echo "  $0 usb [start|stop|status]         # USB模式 (推荐)"
        echo "  $0 wifi <phone_ip> [start|stop]    # WiFi模式 (备选)"
        echo ""
        echo "USB mode: Mac USB → adb server TCP → container"
        echo "WiFi mode: socat port forward → container"
        ;;
esac
