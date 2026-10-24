#!/usr/bin/env bash
# query-relevance-truth G1 · 近重复融合级代价边界复算脚本（零外呼离线）
#
# 做什么：以写模式跑唯一写入口 NearDupFusionImpactDecisionTest（写 docs/rag-quality/near-dup-fusion-impact-v1.json），
#         把固定表格（链路与权重 / 单调性与上界 / gap 分布 / 逐判据照判）落到 target/near-dup-fusion-impact-report.txt。
#
# 退出码语义（这就是"判定"）：
#   0 = 复算成功（R1 源码实测、R2 单调性、R3 上界断言、R4 分布自洽全过）；**INVISIBLE-IN-ROUTE-ONLY 也是 0**——那是合法结论，不是故障。
#   非 0 = 复算本身失败（R2 反序 / R3 上界不符 / 源码行号漂移 / 语料锚 SHA 漂移），按 proposal §5 走 G1 停步裁决。
#
# 零真外呼：本脚本只跑离线单测（不设 RAG_*_REAL、不读 .env、不访问网络）。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
mkdir -p target
LOG="target/near-dup-fusion-impact-report.txt"

echo "[fusion-impact-report] mvn test -Dtest=NearDupFusionImpactDecisionTest -Dnear.dup.fusion.write=true"
mvn -B -ntp test -Dtest=NearDupFusionImpactDecisionTest -Dnear.dup.fusion.write=true \
  -DfailIfNoTests=false >"$LOG" 2>&1
status=$?

echo "[fusion-impact-report] 表格已落盘：$LOG"
if [ -f "$LOG" ]; then
  grep -E '^\[QRT1\]' "$LOG" || true
fi

DECISION="docs/rag-quality/near-dup-fusion-impact-v1.json"
if [ -f "$DECISION" ]; then
  verdict=$(grep -o '"verdict"[[:space:]]*:[[:space:]]*"[^"]*"' "$DECISION" | tail -1 | sed 's/.*"\([^"]*\)"$/\1/')
  external=$(grep -o '"externalCalls"[[:space:]]*:[[:space:]]*[0-9]*' "$DECISION" | head -1 | grep -o '[0-9]*$')
  echo "[fusion-impact-report] 融合影响锚：$DECISION"
  echo "[fusion-impact-report] verdict=${verdict:-<未取到>} externalCalls=${external:-<未取到>}"
  grep -o '"bound"[[:space:]]*:[[:space:]]*[0-9.]*' "$DECISION" | while read -r line; do
    echo "[fusion-impact-report] $line"
  done
else
  echo "[fusion-impact-report] 融合影响锚缺失：$DECISION"
  status=1
fi

echo "[fusion-impact-report] 退出码 = $status（0 = 复算成功；INVISIBLE-IN-ROUTE-ONLY 不失败）"
exit "$status"
