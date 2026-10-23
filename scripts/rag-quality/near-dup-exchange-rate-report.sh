#!/usr/bin/env bash
# evidence-to-operations G1 · 近重复交换率重定价复算脚本（零外呼离线）
#
# 做什么：以写模式跑唯一写入口 NearDuplicateExchangeRateDecisionTest（写 docs/rag-quality/near-dup-exchange-rate-v1.json），
#         把固定表格（基线 / 交换率全网格 200 行 / 免费档与判定 / 逐判据照判）落到 target/near-dup-exchange-rate-report.txt。
#
# 退出码语义（这就是"判定"）：
#   0 = 全网格复算成功（E1–E6 自洽、E2 恒等式与跨锚逐点相等通过、strata 对齐）；**EXCHANGE-RATE / UNDECIDABLE-BY-DATA 也是 0**——那是合法结论，不是故障。
#   非 0 = 复算本身失败（跨锚 R 不等 / 恒等式破坏 / 语料锚 SHA 漂移），按 proposal §5 走 G1 停步裁决。
#
# 零真外呼：本脚本只跑离线单测（不设 RAG_*_REAL、不读 .env、不访问网络）。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
mkdir -p target
LOG="target/near-dup-exchange-rate-report.txt"

echo "[exchange-rate-report] mvn test -Dtest=NearDuplicateExchangeRateDecisionTest -Dnear.dup.exchange.write=true"
mvn -B -ntp test -Dtest=NearDuplicateExchangeRateDecisionTest -Dnear.dup.exchange.write=true \
  -DfailIfNoTests=false >"$LOG" 2>&1
status=$?

echo "[exchange-rate-report] 表格已落盘：$LOG"
if [ -f "$LOG" ]; then
  grep -E '^\[G1E\]' "$LOG" | grep -E '表 1|表 3|表 4|写模式|默认模式' || true
fi

DECISION="docs/rag-quality/near-dup-exchange-rate-v1.json"
if [ -f "$DECISION" ]; then
  verdict=$(grep -o '"verdict"[[:space:]]*:[[:space:]]*"[^"]*"' "$DECISION" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
  external=$(grep -o '"externalCalls"[[:space:]]*:[[:space:]]*[0-9]*' "$DECISION" | head -1 | grep -o '[0-9]*$')
  firstImproving=$(grep -o '"firstImprovingFloor"[[:space:]]*:[[:space:]]*[0-9.]*' "$DECISION" | head -1 | grep -o '[0-9.]*$')
  bestValue=$(grep -o '"bestValueFloor"[[:space:]]*:[[:space:]]*[0-9.]*' "$DECISION" | head -1 | grep -o '[0-9.]*$')
  echo "[exchange-rate-report] 交换率锚：$DECISION"
  echo "[exchange-rate-report] verdict=${verdict:-<未取到>} externalCalls=${external:-<未取到>} firstImprovingFloor=${firstImproving:-<无>} bestValueFloor=${bestValue:-<无>}"
else
  echo "[exchange-rate-report] 交换率锚缺失：$DECISION"
  status=1
fi

echo "[exchange-rate-report] 退出码 = $status（0 = 复算成功；EXCHANGE-RATE/UNDECIDABLE 不失败）"
exit "$status"
