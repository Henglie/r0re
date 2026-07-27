#!/bin/bash
# so-strings.sh — 搜索SO文件中的敏感字符串和符号
# 用法: so-strings.sh <target.so> [pattern]

set -euo pipefail

SO="$1"
PATTERN="${2:-key|secret|password|encrypt|decrypt|aes|rsa|sha|md5|hmac|token|sign|verify|flag|hidden|debug|unlock|auth}"

if [ ! -f "$SO" ]; then
    echo "ERROR: File not found: $SO"
    exit 1
fi

echo "=== SO Analysis: $(basename "$SO") ==="
echo ""

# ELF info
echo "--- ELF Header ---"
file "$SO"
readelf -h "$SO" | head -10
echo ""

# Exported symbols
echo "--- Exported Symbols (JNI_onLoad + crypto-related) ---"
nm -D "$SO" 2>/dev/null | grep -iE "JNI_OnLoad|encrypt|decrypt|key|sign|verify|crypt|aes|rsa|sha|md5" || echo "(none)"
echo ""

# Full symbol table
echo "--- All Symbols (filtered) ---"
readelf -s "$SO" 2>/dev/null | grep -iE "$PATTERN" | head -20 || echo "(none)"
echo ""

# String search
echo "--- Strings (filtered) ---"
strings -a "$SO" | grep -iE "$PATTERN" | head -30 || echo "(none)"
echo ""

echo "=== Done ==="