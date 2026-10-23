#!/usr/bin/env bash
# retrieval-and-contract-hardening G1 · 近重复机制决策复算脚本（零外呼离线）
#
# 做什么：以写模式跑唯一写入口 NearDuplicateMechanismDecisionTest（写 docs/rag-quality/near-dup-mechanism-decision-v1.json），
#         把固定表格（基线 / M1 200 行 / M2 25 行 / M3 帕累托 / 逐判据照判）落到 target/near-dup-mechanism-report.txt。
#
# 退出码语义（这就是"判定"）：
#   0 = 全网格复算成功（C1–C6 自洽、键对齐、同图自证通过）；**NO-GO 也是 0**——那是合法结论，不是故障。
#   非 0 = 复算本身失败（键对不上 / 语料不可复现 / 锚被改写），按 proposal §5 走 G1 停步裁决。
#
# 零真外呼：本脚本只跑离线单测（不设 RAG_*_REAL、不读 .env、不访问网络）。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
mkdir -p target
LOG="target/near-dup-mechanism-report.txt"

echo "[near-dup-report] mvn test -Dtest=NearDuplicateMechanismDecisionTest -Dnear.dup.decision.write=true"
mvn -B -ntp test -Dtest=NearDuplicateMechanismDecisionTest -Dnear.dup.decision.write=true \
  -DfailIfNoTests=false >"$LOG" 2>&1
status=$?

echo "[near-dup-report] 表格已落盘：$LOG"
if [ -f "$LOG" ]; then
  grep -E '^\[G1\]' "$LOG" | grep -E '表 1|表 5|写模式|默认模式' || true
fi

DECISION="docs/rag-quality/near-dup-mechanism-decision-v1.json"
if [ -f "$DECISION" ]; then
  verdict=$(grep -o '"verdict"[[:space:]]*:[[:space:]]*"[^"]*"' "$DECISION" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
  external=$(grep -o '"externalCalls"[[:space:]]*:[[:space:]]*[0-9]*' "$DECISION" | head -1 | grep -o '[0-9]*$')
  echo "[near-dup-report] 决策锚：$DECISION"
  echo "[near-dup-report] verdict=${verdict:-<未取到>} externalCalls=${external:-<未取到>}"
else
  echo "[near-dup-report] 决策锚缺失：$DECISION"
  status=1
fi

echo "[near-dup-report] 退出码 = $status（0 = 复算成功；NO-GO 不失败）"
exit "$status"
