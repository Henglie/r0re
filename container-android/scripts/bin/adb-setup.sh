#!/bin/bash
# adb-setup.sh — adb连接管理 + 应用信息获取
# 用法: adb-setup.sh [command] [args]
#   (no args)           — 检查adb连接状态
#   devices             — 列出已连接设备
#   connect <ip[:port]> — WiFi ADB连接 (默认端口5555)
#   connect-docker      — 兼容旧别名，等同 connect-usb
#   connect-usb         — 通过宿主机 adb server 使用 USB 直连手机
#   shell               — 进入设备shell
#   dump <pkg>          — 导出APK + 数据
#   pull <pkg>          — 拉取APK到workspace
#   frida <pkg>         — 检查frida-server状态
#   frida-push          — 推送容器内的 frida-server 到手机 (需root)
#   frida-start         — 启动手机上的 frida-server
#
# 环境变量:
#   ADB_TARGET          — 默认连接目标 (IP:PORT)
#   FRIDA_SERVER_PORT   — frida-server 端口 (默认 14725)

set -euo pipefail

CMD="${1:-status}"
PKG="${2:-}"
OUTDIR=~/workspace/analysis
OUTDIR="${OUTDIR/#\~/$(eval echo ~)}"
ADB_TARGET="${ADB_TARGET:-}"
FRIDA_SERVER_PORT="${FRIDA_SERVER_PORT:-14725}"

# 检测 Docker 容器内环境
IN_DOCKER=false
if [ -f /.dockerenv ] || grep -q docker /proc/1/cgroup 2>/dev/null; then
    IN_DOCKER=true
fi

# Docker Desktop macOS 上的宿主机地址
HOST_GATEWAY="${HOST_GATEWAY:-host.docker.internal}"

check_adb() {
    if ! command -v adb &>/dev/null; then
        echo "ERROR: adb not found. Is Android SDK platform-tools in PATH?"
        echo "  export PATH=\$PATH:\$ANDROID_HOME/platform-tools"
        exit 1
    fi
}

# ── 自动检测并连接 ADB ──
auto_connect() {
    check_adb
    echo "=== Auto-connecting ADB ==="
    
    # 如果有环境变量，直接用
    if [ -n "$ADB_TARGET" ]; then
        echo "[*] Using ADB_TARGET=$ADB_TARGET"
        adb connect "$ADB_TARGET"
        return $?
    fi
    
    # Docker 容器内默认走宿主机 adb server（USB 直连手机）
    if $IN_DOCKER; then
        echo "[*] Running inside Docker container"
        echo "[*] Attempting USB bridge via $HOST_GATEWAY:5037 ..."

        export ADB_SERVER_SOCKET="tcp:${HOST_GATEWAY}:5037"
        if timeout 3 bash -c "echo >/dev/tcp/$HOST_GATEWAY/5037" 2>/dev/null; then
            echo "[+] Host adb server reachable on $HOST_GATEWAY:5037"
            DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "^$" | head -5 || true)
            if [ -n "$DEVICES" ]; then
                echo "[+] USB device visible through host adb server"
            else
                echo "[-] Host adb server reachable, but no authorized USB device is visible"
                echo "    Make sure the host ran: ./device-bridge.sh usb start"
                echo "    Then confirm the phone USB debugging authorization dialog"
            fi
        else
            echo "[-] Host adb server not reachable on $HOST_GATEWAY:5037"
            echo "    Start the host-side USB bridge first:"
            echo "    ./device-bridge.sh usb start"
        fi
        
        if timeout 3 bash -c "echo >/dev/tcp/$HOST_GATEWAY/$FRIDA_SERVER_PORT" 2>/dev/null; then
            echo "[+] Port $FRIDA_SERVER_PORT (frida-server) reachable on $HOST_GATEWAY"
        else
            echo "[-] Port $FRIDA_SERVER_PORT (frida-server) not reachable on $HOST_GATEWAY"
            echo "    Make sure frida-server is running on the phone and adb forward is active"
        fi
        return 0
    fi
    
    # 非容器环境，尝试直连
    echo "[*] Not in Docker, checking direct USB/TCP connections..."
    DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "^$" | head -5)
    if [ -z "$DEVICES" ]; then
        echo "No devices connected."
        echo ""
        echo "Connection methods:"
        echo "  adb connect <phone_ip>:5555           # WiFi ADB"
        echo "  adb connect localhost:5555             # Emulator"
        echo "  adb connect 127.0.0.1:7555            # MuMu"
        echo "  adb connect 127.0.0.1:62001           # LDPlayer"
    else
        echo "Already connected:"
        echo "$DEVICES"
    fi
}

case "$CMD" in
    status)
        auto_connect
        echo ""
        echo "=== ADB Status ==="
        DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "^$" | head -5)
        if [ -z "$DEVICES" ]; then
            echo "No devices connected."
            if $IN_DOCKER; then
                echo ""
                echo "Docker container detected. Run: adb-setup.sh connect-usb"
            fi
        else
            echo "$DEVICES"
            echo ""
            # 显示设备信息
            adb shell getprop ro.build.version.release 2>/dev/null | xargs -I{} echo "Android version: {}" || true
            adb shell getprop ro.product.model 2>/dev/null | xargs -I{} echo "Device: {}" || true
            adb shell getprop ro.build.type 2>/dev/null | xargs -I{} echo "Build type: {}" || true
            # Root检查
            ROOT_STATUS=$(adb shell "su -c 'id'" 2>/dev/null || echo "not available")
            echo "Root: $ROOT_STATUS"
            # Frida检查
            FRIDA_STATUS=$(adb shell "su -c 'ps -A | grep frida-server'" 2>/dev/null || echo "not running")
            echo "Frida server: $FRIDA_STATUS"
        fi
        ;;
    devices)
        check_adb
        adb devices -l
        ;;
    connect)
        check_adb
        TARGET="${2:-}"
        if [ -z "$TARGET" ]; then
            echo "Usage: adb-setup.sh connect <ip[:port]>"
            echo "  Default port is 5555"
            exit 1
        fi
        # 自动补全端口
        if [[ ! "$TARGET" =~ :[0-9]+$ ]]; then
            TARGET="$TARGET:5555"
        fi
        echo "[*] Connecting to $TARGET ..."
        adb connect "$TARGET"
        ;;
    connect-docker)
        # 兼容旧命令，默认切到 USB host adb server
        export ADB_SERVER_SOCKET="tcp:${HOST_GATEWAY}:5037"
        auto_connect
        ;;
    connect-usb)
        # Docker 容器内 USB 连接 (通过宿主机 adb server)
        echo "=== USB Mode (via host adb server) ==="
        echo "[*] Setting ADB_SERVER_SOCKET to host.docker.internal:5037"
        echo ""
        # 设置环境变量连接宿主机 adb server
        export ADB_SERVER_SOCKET="tcp:${HOST_GATEWAY}:5037"
        echo "ADB_SERVER_SOCKET=$ADB_SERVER_SOCKET"
        echo ""
        # 验证连接
        DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "^$" | head -5)
        if [ -z "$DEVICES" ]; then
            echo "[-] No devices found via host adb server"
            echo "    Make sure host is running: ./device-bridge.sh usb start"
        else
            echo "[+] Connected to host adb server:"
            echo "$DEVICES"
        fi
        ;;
    pull)
        check_adb
        if [ -z "$PKG" ]; then echo "Usage: adb-setup.sh pull <package>"; exit 1; fi
        echo "[*] Finding APK path for $PKG..."
        APKPATH=$(adb shell pm path "$PKG" 2>/dev/null | head -1 | sed 's/package://')
        if [ -z "$APKPATH" ]; then
            echo "ERROR: Package not found: $PKG"
            exit 1
        fi
        mkdir -p "$OUTDIR"
        echo "[*] Pulling $APKPATH ..."
        adb pull "$APKPATH" "$OUTDIR/${PKG}.apk"
        echo "[+] Saved to $OUTDIR/${PKG}.apk"
        ;;
    dump)
        check_adb
        if [ -z "$PKG" ]; then echo "Usage: adb-setup.sh dump <package>"; exit 1; fi
        mkdir -p "$OUTDIR/$PKG"
        echo "[*] Dumping info for $PKG..."
        adb shell dumpsys package "$PKG" > "$OUTDIR/$PKG/package_info.txt" 2>/dev/null || true
        adb shell "su -c 'ls /data/data/$PKG/shared_prefs/'" 2>/dev/null > "$OUTDIR/$PKG/shared_prefs_list.txt" || true
        adb shell "su -c 'ls /data/data/$PKG/databases/'" 2>/dev/null > "$OUTDIR/$PKG/databases_list.txt" || true
        APKPATH=$(adb shell pm path "$PKG" 2>/dev/null | head -1 | sed 's/package://')
        [ -n "$APKPATH" ] && adb pull "$APKPATH" "$OUTDIR/$PKG/base.apk" 2>/dev/null || true
        echo "[+] Dump saved to $OUTDIR/$PKG/"
        ;;
    frida)
        check_adb
        echo "[*] Checking frida-server..."
        FRIDA_RUNNING=$(adb shell "su -c 'ps -A | grep frida-server'" 2>/dev/null || true)
        if [ -n "$FRIDA_RUNNING" ]; then
            echo "[+] Frida server is running:"
            echo "$FRIDA_RUNNING"
        else
            echo "[-] Frida server NOT running."
            echo ""
            echo "Start with:"
            echo "  adb-setup.sh frida-push    # Push frida-server to phone (first time)"
            echo "  adb-setup.sh frida-start   # Start frida-server on phone"
            echo ""
            echo "Manual:"
            echo "  adb shell 'su -c /data/local/tmp/frida-server -D -l 0.0.0.0:$FRIDA_SERVER_PORT &'"
        fi
        ;;
    frida-push)
        check_adb
        echo "[*] Looking for frida-server binary..."
        
        # 尝试找到匹配的 frida-server
        FRIDA_VER=$(python3 -c "import frida; print(frida.__version__)" 2>/dev/null || echo "")
        if [ -z "$FRIDA_VER" ]; then
            echo "ERROR: Cannot detect frida version"
            exit 1
        fi
        echo "[*] Frida client version: $FRIDA_VER"
        
        # 获取手机架构
        ARCH=$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
        echo "[*] Device architecture: $ARCH"
        
        # 映射架构到 frida-server 文件名
        case "$ARCH" in
            arm64-v8a)  FRIDA_ARCH="arm64" ;;
            armeabi-v7a) FRIDA_ARCH="arm" ;;
            x86_64)     FRIDA_ARCH="x86_64" ;;
            x86)        FRIDA_ARCH="x86" ;;
            *)          echo "ERROR: Unknown architecture: $ARCH"; exit 1 ;;
        esac
        
        SERVER_NAME="frida-server-${FRIDA_VER}-android-${FRIDA_ARCH}"
        URL="https://github.com/frida/frida/releases/download/${FRIDA_VER}/${SERVER_NAME}.xz"
        
        echo "[*] Downloading $SERVER_NAME.xz ..."
        TMPDIR=$(mktemp -d)
        curl -fSL -o "$TMPDIR/frida-server.xz" "$URL"
        xz -d "$TMPDIR/frida-server.xz"
        
        echo "[*] Pushing to device..."
        adb push "$TMPDIR/frida-server" /data/local/tmp/frida-server
        adb shell "su -c 'chmod 755 /data/local/tmp/frida-server'"
        
        rm -rf "$TMPDIR"
        echo "[+] frida-server pushed successfully"
        echo "    Start with: adb-setup.sh frida-start"
        ;;
    frida-start)
        check_adb
        echo "[*] Starting frida-server on device (port $FRIDA_SERVER_PORT)..."
        adb shell "su -c 'killall frida-server 2>/dev/null; /data/local/tmp/frida-server -D -l 0.0.0.0:$FRIDA_SERVER_PORT &'" 2>/dev/null || true
        sleep 2
        # 验证
        FRIDA_RUNNING=$(adb shell "su -c 'ps -A | grep frida-server'" 2>/dev/null || true)
        if [ -n "$FRIDA_RUNNING" ]; then
            echo "[+] Frida server started successfully"
            echo "    Listening on port $FRIDA_SERVER_PORT"
            echo ""
            if $IN_DOCKER; then
                echo "    In Docker, connect with:"
                echo "      frida-auto.sh <package> <mode> -H $HOST_GATEWAY:$FRIDA_SERVER_PORT"
                echo "    Or directly:"
                echo "      frida -H $HOST_GATEWAY:$FRIDA_SERVER_PORT -f <package> --no-pause"
            else
                echo "    Connect with:"
                echo "      frida -U -f <package> --no-pause"
            fi
        else
            echo "[-] Failed to start frida-server"
            echo "    Check: adb shell 'su -c /data/local/tmp/frida-server'"
        fi
        ;;
    *)
        echo "Usage: adb-setup.sh [command] [args]"
        echo ""
        echo "Commands:"
        echo "  status              Check connection status (auto-connect in Docker)"
        echo "  devices             List connected devices"
        echo "  connect <ip:port>   Connect via WiFi ADB"
        echo "  connect-docker      Alias of connect-usb"
        echo "  connect-usb         Connect from Docker container through host USB adb server"
        echo "  shell               Enter device shell"
        echo "  pull <package>      Pull APK to workspace"
        echo "  dump <package>      Dump app data"
        echo "  frida <package>     Check frida-server status"
        echo "  frida-push          Download & push frida-server to phone"
        echo "  frida-start         Start frida-server on phone"
        ;;
esac
