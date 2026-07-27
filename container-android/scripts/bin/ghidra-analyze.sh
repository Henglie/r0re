#!/bin/bash
# ghidra-analyze.sh — Ghidra headless自动化分析SO/ELF文件
# 用法: ghidra-analyze.sh <target.so> [function_name]
# 输出: 函数列表 + 反编译结果 (保存到 ~/workspace/analysis/)

set -euo pipefail

TARGET="$1"
FUNC="${2:-}"
OUTDIR=~/workspace/analysis/ghidra_out
OUTDIR="${OUTDIR/#\~/$(eval echo ~)}"
PROJECT="CairnAnalysis"

if [ ! -f "$TARGET" ]; then
    echo "ERROR: File not found: $TARGET"
    exit 1
fi

BASENAME=$(basename "$TARGET" .so)
BASENAME=$(basename "$BASENAME" .elf)
mkdir -p "$OUTDIR"

echo "=== Ghidra Headless Analysis: $(basename "$TARGET") ==="

# Step 1: 导入并分析 (如果项目已存在会复用)
echo "[*] Importing and analyzing..."
$GHIDRA_HOME/support/analyzeHeadless "$OUTDIR" "$PROJECT" \
    -import "$TARGET" \
    -overwrite \
    -postScript /dev/null \
    2>&1 | tail -5

echo "[*] Analysis complete."
echo ""

# Step 2: 导出函数列表
echo "[*] Exporting function list..."
$GHIDRA_HOME/support/analyzeHeadless "$OUTDIR" "$PROJECT" \
    -process "$BASENAME" \
    -noanalysis \
    -postScript ListFunctions.java "$OUTDIR/${BASENAME}_functions.txt" \
    2>&1 | tail -3

if [ -f "$OUTDIR/${BASENAME}_functions.txt" ]; then
    FUNCS=$(wc -l < "$OUTDIR/${BASENAME}_functions.txt")
    echo "    Functions found: $FUNCS"
    echo ""
    # 显示过滤后的函数
    echo "--- Key functions ---"
    grep -iE "JNI_|encrypt|decrypt|key|sign|verify|crypt|aes|rsa|sha|md5|hmac|init|check|verify|auth" \
        "$OUTDIR/${BASENAME}_functions.txt" | head -20 || echo "(no matches)"
    echo ""
else
    echo "    (function list export not available, using nm fallback)"
    echo "--- Exported symbols ---"
    nm -D "$TARGET" | grep " T " | head -20
    echo ""
fi

# Step 3: 如果指定了函数名，尝试反编译
if [ -n "$FUNC" ]; then
    echo "[*] Decompiling function: $FUNC"
    DECOMP_OUT="$OUTDIR/${BASENAME}_${FUNC}.c"
    $GHIDRA_HOME/support/analyzeHeadless "$OUTDIR" "$PROJECT" \
        -process "$BASENAME" \
        -noanalysis \
        -postScript DecompileToStdout.java "$FUNC" \
        2>&1 | grep -v "INFO\|WARN" > "$DECOMP_OUT" || true
    
    if [ -s "$DECOMP_OUT" ]; then
        echo "--- Decompiled: $FUNC ---"
        cat "$DECOMP_OUT"
        echo ""
        echo "Saved to: $DECOMP_OUT"
    else
        echo "    (decompilation failed, try radare2 instead)"
        echo "    Fallback: r2 -q -c 'aaa; s sym.$FUNC; pdf' $TARGET"
    fi
fi

echo ""
echo "=== Analysis output in: $OUTDIR ==="
