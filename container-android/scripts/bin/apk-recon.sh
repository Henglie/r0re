#!/bin/bash
# apk-recon.sh — APK快速侦察，输出结构化信息
# 用法: apk-recon.sh <target.apk>

set -euo pipefail

APK="$1"
OUT="${2:-.}"

if [ ! -f "$APK" ]; then
    echo "ERROR: File not found: $APK"
    exit 1
fi

echo "=== APK Recon: $(basename "$APK") ==="
echo ""

# Basic info
echo "--- File Info ---"
file "$APK"
echo ""

# Package info
echo "--- Package Info ---"
aapt dump badging "$APK" 2>/dev/null | head -5
echo ""

# Permissions
echo "--- Permissions ---"
aapt dump permissions "$APK" 2>/dev/null | grep "uses-permission" | sed 's/.*name=.//' | sed 's/".*//'
echo ""

# Exported components
echo "--- Exported Components ---"
aapt dump xmltree "$APK" AndroidManifest.xml 2>/dev/null | grep -A2 "exported" | head -30
echo ""

# Native libraries
echo "--- Native Libraries ---"
unzip -l "$APK" "lib/*" 2>/dev/null | grep ".so" || echo "(none)"
echo ""

# DEX files
echo "--- DEX Files ---"
unzip -l "$APK" "*.dex" 2>/dev/null || echo "(none)"
echo ""

# Cert info
echo "--- Signing Certificate ---"
unzip -p "$APK" META-INF/*.RSA 2>/dev/null | keytool -printcert 2>/dev/null | head -10 || echo "(unable to read)"
echo ""

echo "=== Recon Complete ==="
