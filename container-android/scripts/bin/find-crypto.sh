#!/bin/bash
# find-crypto.sh — 搜索加密相关API调用
# 用法: find-crypto.sh <jadx_out_dir>

set -euo pipefail

DIR="${1:-~/workspace/analysis/jadx_out}"
DIR="${DIR/#\~/$(eval echo ~)}"

echo "=== Crypto API Search in: $DIR ==="
echo ""

# Java crypto API patterns
echo "--- javax.crypto usage ---"
grep -rn "Cipher\|SecretKeySpec\|MessageDigest\|Mac\|KeyGenerator\|KeyFactory\|AlgorithmParameters" \
    "$DIR/sources" --include="*.java" -l | head -20 || echo "(none)"
echo ""

# Common crypto patterns
echo "--- Crypto keywords ---"
grep -rn "AES\|RSA\|DES\|MD5\|SHA1\|SHA256\|HMAC\|PBKDF\|encrypt\|decrypt\|password\|secret\|key" \
    "$DIR/sources" --include="*.java" -l | head -20 || echo "(none)"
echo ""

# Base64 encoding
echo "--- Base64 usage ---"
grep -rn "Base64\|android.util.Base64" \
    "$DIR/sources" --include="*.java" -l | head -10 || echo "(none)"
echo ""

# Key storage
echo "--- Key storage ---"
grep -rn "SharedPreferences\|AndroidKeyStore\|Keystore" \
    "$DIR/sources" --include="*.java" -l | head -10 || echo "(none)"
echo ""

echo "=== Done ==="