#!/usr/bin/env bash
# evidence-to-operations G3 · 预检脚本自测（六 fixture 用例，逐案断言退出码 + 原因码）
#
# 六案覆盖（proposal §3.3 Q1/Q2）：
#   1. GO           ：B=true / 白名单非空 / A=false → 退出 0（Q2 的「可进入第 4 步」态）
#   2. A_WITHOUT_B  ：A=true / B=false            → 退出 1，且输出必须含「链路完全不动（§2/§12 已实证）」
#   3. B_OFF        ：四键全关 + 白名单空          → 退出 1（Q2 的「真实快照判定」之一）
#   4. NO_ALLOWLIST ：B=true / A=false / 白名单空   → 退出 1
#   5. ALREADY_ON   ：A=true / B=true / 白名单非空  → 退出 1
#   6. BAD_INPUT    ：快照缺键                     → 退出 2
#
# 附加：TSV 与扁平 JSON 两种快照形态、watch --times 1 的单轮判定与 preflight 一致。
# 零真外呼：全部走本地 fixture；不访问网络。
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PREFLIGHT="$SCRIPT_DIR/../rag-quality/image-vector-ingest-preflight.sh"

KEY_A="rag.retrieval.image-vector-ingest.enabled"
KEY_B="rag.retrieval.image-slice-ingest.enabled"
KEY_AL="rag.retrieval.image-slice-ingest.kb-allowlist"
KEY_CAP="rag.retrieval.image-slice-ingest.max-images-per-document"

TMPDIR_SELF=$(mktemp -d)
trap 'rm -rf "$TMPDIR_SELF"' EXIT

passed=0
failed=0

write_tsv() {
  local file="$1" a="$2" b="$3" al="$4" cap="$5"
  {
    printf '# preflight fixture (TSV)\n'
    printf '%s\t%s\n' "$KEY_A" "$a"
    printf '%s\t%s\n' "$KEY_B" "$b"
    printf '%s\t%s\n' "$KEY_AL" "$al"
    printf '%s\t%s\n' "$KEY_CAP" "$cap"
  } >"$file"
}

write_json() {
  local file="$1" a="$2" b="$3" al="$4" cap="$5"
  printf '{"%s":"%s","%s":"%s","%s":"%s","%s":"%s"}\n' \
    "$KEY_A" "$a" "$KEY_B" "$b" "$KEY_AL" "$al" "$KEY_CAP" "$cap" >"$file"
}

# expect <case> <snapshot> <want_exit> <want_reason> [extra_pattern]
expect() {
  local case_name="$1" snapshot="$2" want_exit="$3" want_reason="$4" extra="${5:-}"
  local out rc
  out=$("$PREFLIGHT" preflight --snapshot "$snapshot" 2>&1)
  rc=$?
  local ok=1
  [ "$rc" -eq "$want_exit" ] || { echo "[selftest] $case_name: 退出码 $rc != $want_exit"; ok=0; }
  echo "$out" | grep -Eq "reason=$want_reason([[:space:]]|\$)" || { echo "[selftest] $case_name: 输出缺 reason=$want_reason"; echo "$out" | sed 's/^/  | /'; ok=0; }
  if [ -n "$extra" ]; then
    echo "$out" | grep -q "$extra" || { echo "[selftest] $case_name: 输出缺关键内容 [$extra]"; echo "$out" | sed 's/^/  | /'; ok=0; }
  fi
  if [ "$ok" -eq 1 ]; then
    passed=$((passed + 1))
    echo "[selftest] $case_name: PASS (exit=$rc reason=$want_reason)"
  else
    failed=$((failed + 1))
    echo "[selftest] $case_name: FAIL"
  fi
}

F1="$TMPDIR_SELF/go.tsv"
F2="$TMPDIR_SELF/a_without_b.json"
F3="$TMPDIR_SELF/b_off.tsv"
F4="$TMPDIR_SELF/no_allowlist.json"
F5="$TMPDIR_SELF/already_on.tsv"
F6="$TMPDIR_SELF/missing_key.json"

write_tsv "$F1" false true "12,15" 5
write_json "$F2" true false "" 5
write_tsv "$F3" false false "" 5
write_json "$F4" false true "" 5
write_tsv "$F5" true true "12,15" 5
printf '{"%s":"false","%s":"false"}\n' "$KEY_B" "$KEY_AL" >"$F6"

expect "1-GO"            "$F1" 0 GO
expect "2-A_WITHOUT_B"   "$F2" 1 A_WITHOUT_B "链路完全不动（§2/§12 已实证）"
expect "3-B_OFF"         "$F3" 1 B_OFF
expect "4-NO_ALLOWLIST"  "$F4" 1 NO_ALLOWLIST
expect "5-ALREADY_ON"    "$F5" 1 ALREADY_ON
expect "6-BAD_INPUT"     "$F6" 2 BAD_INPUT

# watch 单轮判定与 preflight 一致（退出码 + 原因码）
wout=$(timeout 30 "$PREFLIGHT" watch --snapshot "$F1" --times 1 2>&1)
wrc=$?
if [ "$wrc" -eq 0 ] && echo "$wout" | grep -Eq "reason=GO([[:space:]]|\$)"; then
  passed=$((passed + 1)); echo "[selftest] 7-watch-single-round: PASS"
else
  failed=$((failed + 1)); echo "[selftest] 7-watch-single-round: FAIL (exit=$wrc)"; echo "$wout" | sed 's/^/  | /'
fi

echo "[selftest] 汇总: passed=$passed failed=$failed"
[ "$failed" -eq 0 ]
