#!/bin/bash
# quick-jadx.sh — 快速反编译APK/DEX并搜索关键字
# 用法: quick-jadx.sh <target.apk> [search_pattern]

set -euo pipefail

TARGET="$1"
PATTERN="${2:-}"
OUTDIR="~/workspace/analysis/jadx_out"
OUTDIR="${OUTDIR/#\~/$(eval echo ~)}"

echo "=== Quick JADX Analysis ==="
echo "Target: $(basename "$TARGET")"
echo "Output: $OUTDIR"
echo ""

# Decompile
jadx --no-res --show-bad-code -d "$OUTDIR" "$TARGET"
echo "Decompilation complete."
echo ""

# Quick stats
echo "--- Stats ---"
find "$OUTDIR/sources" -name "*.java" | wc -l | xargs -I{} echo "Java files: {}"
echo ""

# Search if pattern given
if [ -n "$PATTERN" ]; then
    echo "--- Search: '$PATTERN' ---"
    grep -rn "$PATTERN" "$OUTDIR/sources" --include="*.java" | head -20 || echo "(no matches)"
    echo ""
fi

echo "=== Done. Files in $OUTDIR ==="