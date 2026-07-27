#!/bin/bash
# frida-auto.sh — 根据分析场景自动选择hook组合
# 用法: frida-auto.sh <package> <mode> [options]
#   mode: crypto    — 加密API hook
#         ssl       — SSL Pinning绕过
#         root      — Root检测绕过
#         native    — Native层追踪
#         custom:<script.js> — 自定义脚本
#         all       — crypto + ssl + root
#         full      — 所有hook
#
#   连接模式 (通过环境变量或选项):
#     默认:                  等同 -U (USB设备，容器外直接使用)
#     -H <host:port>        连接远程 frida-server
#     --remote <host:port>  同 -H
#     FRIDA_HOST=host:port  环境变量方式
#
#   示例:
#     frida-auto.sh com.target crypto                          # USB模式
#     frida-auto.sh com.target crypto -H 192.168.1.100:14725  # 远程frida
#     frida-auto.sh com.target root -H host.docker.internal:14725  # Docker容器内
#     FRIDA_HOST=192.168.1.100:14725 frida-auto.sh com.target ssl  # 环境变量

set -euo pipefail

# ── 参数解析 ──
PKG=""
MODE="crypto"
FRIDA_CONN=""

while [ $# -gt 0 ]; do
    case "$1" in
        -H|--remote)
            FRIDA_CONN="$2"
            shift 2
            ;;
        -U|--usb)
            FRIDA_CONN=""
            shift
            ;;
        -*)
            echo "Unknown option: $1"
            exit 1
            ;;
        *)
            if [ -z "$PKG" ]; then
                PKG="$1"
            else
                MODE="$1"
            fi
            shift
            ;;
    esac
done

# 环境变量覆盖
if [ -n "${FRIDA_HOST:-}" ] && [ -z "$FRIDA_CONN" ]; then
    FRIDA_CONN="$FRIDA_HOST"
fi

# Docker 容器内默认连接宿主机 adb forward 暴露的 14725 端口。
if [ -z "$FRIDA_CONN" ] && [ -f /.dockerenv ]; then
    FRIDA_CONN="${HOST_GATEWAY:-host.docker.internal}:${FRIDA_SERVER_PORT:-14725}"
fi

if [ -z "$PKG" ]; then
    echo "Usage: frida-auto.sh <package> [mode] [-H host:port | -U]"
    echo ""
    echo "Modes: crypto, ssl, root, native, custom:<script.js>, all, full"
    echo ""
    echo "Connection:"
    echo "  (default)            USB device (-U)"
    echo "  -H host:port         Remote frida-server"
    echo "  FRIDA_HOST=host:port Environment variable"
    echo ""
    echo "Examples:"
    echo "  frida-auto.sh com.target crypto"
    echo "  frida-auto.sh com.target crypto -H 192.168.1.100:14725"
    echo "  frida-auto.sh com.target root -H host.docker.internal:14725"
    exit 1
fi

HOOK_DIR="$(dirname "$0")"

# ── 构造 frida 连接参数 ──
if [ -n "$FRIDA_CONN" ]; then
    FRIDA_ARGS="-H $FRIDA_CONN"
    echo "[*] Remote mode: connecting to frida-server at $FRIDA_CONN"
else
    FRIDA_ARGS="-U"
    echo "[*] USB mode: connecting to local USB device"
fi

# ── 选择 hook 脚本 ──
COMBINED=""
CLEANUP=false

case "$MODE" in
    crypto)
        echo "[*] Mode: crypto hook for $PKG"
        HOOK_FILES=("$HOOK_DIR/crypto-hook.js")
        ;;
    ssl)
        echo "[*] Mode: SSL pinning bypass for $PKG"
        HOOK_FILES=("$HOOK_DIR/ssl-pinning-bypass.js")
        ;;
    root)
        echo "[*] Mode: root bypass for $PKG"
        HOOK_FILES=("$HOOK_DIR/root-bypass.js")
        ;;
    native)
        echo "[*] Mode: native hook for $PKG"
        HOOK_FILES=("$HOOK_DIR/native-hook.js")
        ;;
    custom:*)
        CUSTOM_SCRIPT="${MODE#custom:}"
        if [ ! -f "$CUSTOM_SCRIPT" ]; then
            echo "ERROR: Custom script not found: $CUSTOM_SCRIPT"
            exit 1
        fi
        echo "[*] Mode: custom script for $PKG"
        HOOK_FILES=("$CUSTOM_SCRIPT")
        ;;
    all)
        echo "[*] Mode: all Java hooks for $PKG (crypto + ssl + root)"
        HOOK_FILES=("$HOOK_DIR/crypto-hook.js" "$HOOK_DIR/ssl-pinning-bypass.js" "$HOOK_DIR/root-bypass.js")
        ;;
    full)
        echo "[*] Mode: ALL hooks for $PKG"
        HOOK_FILES=("$HOOK_DIR/crypto-hook.js" "$HOOK_DIR/ssl-pinning-bypass.js" "$HOOK_DIR/root-bypass.js" "$HOOK_DIR/native-hook.js")
        ;;
    *)
        echo "Unknown mode: $MODE"
        echo "Available: crypto, ssl, root, native, custom:<script>, all, full"
        exit 1
        ;;
esac

# ── 合并脚本 (多个时需要合并) ──
if [ ${#HOOK_FILES[@]} -eq 1 ]; then
    COMBINED="${HOOK_FILES[0]}"
else
    COMBINED=$(mktemp /tmp/frida-combined-XXXXXX.js)
    CLEANUP=true
    for f in "${HOOK_FILES[@]}"; do
        cat "$f" >> "$COMBINED"
        echo -e "\n" >> "$COMBINED"
    done
fi

# ── 执行 frida ──
echo "[*] Starting frida..."
echo "[*]   Package: $PKG"
echo "[*]   Hooks:   $(basename "$COMBINED")"
echo "[*]   Args:    $FRIDA_ARGS"
echo ""

cleanup() {
    if $CLEANUP && [ -f "$COMBINED" ]; then
        rm -f "$COMBINED"
    fi
}
trap cleanup EXIT

frida $FRIDA_ARGS -f "$PKG" -l "$COMBINED" --no-pause
