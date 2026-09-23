#!/usr/bin/env bash
# Self-test for scripts/merge-gate.sh（harness-gates 组 5.3，spec R4）。
#
# 锁住的是**聚合逻辑**：任一子门禁失败时整条命令必须非零退出、并且指名是哪一步；
# 全过时零退出；[it] 默认不跑；失败可累积；[hook] 与 [bijection] 两道存在性判别确实会红；
# [pmd] 与 [pmd-baseline]（wire-pmd-ruleset 组 4.3 新增）同样"失败即被指名"，且场景 11 用真实的
# scripts/tests/pmd-baseline-check.sh + 临时夹具锁住三种态（相等 / 登记偏高 / 实测超登记）与 CRLF 双向，
# 另加零值路径双向锁（11h：零违规 + 新鲜报告 → 绿；11i：零违规 + 报告过期 → 红）。
#
# 无 Docker、无外网、不跑 Maven —— 靠 merge-gate.sh 顶部声明的 MERGE_GATE_* 测试钩子注入桩命令，
# 口径同 scripts/check-test-baseline.sh 的 BASELINE_* 钩子。真实门禁各自的正确性由自己的
# 判别式负责（[baseline] 见本目录 check-test-baseline-selftest.sh，[bijection] 见
# spotbugs-exclude-staleness-check.sh，[unit]/[spotbugs]/[it] 就是 Maven 构建本身）。
#
# 跑：bash scripts/tests/merge-gate-selftest.sh    （退出 0 = 全部断言成立）
set -uo pipefail

selftest_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
scripts_dir=$(CDPATH= cd -- "$selftest_dir/.." && pwd)
repo_root=$(CDPATH= cd -- "$scripts_dir/.." && pwd)
gate="$scripts_dir/merge-gate.sh"

checks=0
failures=0
pass() { checks=$((checks + 1)); echo "ok - $*"; }
die() { checks=$((checks + 1)); failures=$((failures + 1)); echo "NOT OK - $*" >&2; }

expect_eq() { # <label> <expected> <actual>
  if [ "$2" = "$3" ]; then pass "$1"; else die "$1: expected [$2] but got [$3]"; fi
}
expect_has() { # <label> <needle> <file>
  if LC_ALL=C grep -qF -- "$2" "$3"; then pass "$1"; else die "$1: output missing [$2]"; fi
}
expect_lacks() { # <label> <needle> <file>
  if LC_ALL=C grep -qF -- "$2" "$3"; then die "$1: output unexpectedly contains [$2]"; else pass "$1"; fi
}

[ -f "$gate" ] || { echo "cannot find gate: $gate" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

# 桩命令：注意必须是"在子 shell 里失败"，不能用裸 exit —— merge-gate 用 eval 在当前 shell 执行命令，
# 裸 exit 会直接把 merge-gate 本身退出掉，那就测不到"指名失败"了。
OK_CMD="sh -c 'echo stub-ok'"
BAD_CMD="sh -c 'echo stub-fail >&2; exit 3'"

# 一个"看起来像本仓安装器装的"转发器夹具。
mk_hooks_dir() { # <dir> <body>
  mkdir -p "$1"
  printf '%s\n' "$2" >"$1/pre-commit"
}
FORWARDER_BODY='#!/bin/sh
exec sh "$repo/frontend/.githooks/pre-commit" "$@"   # forwards to frontend/.githooks/pre-commit'

# 所有重型子门禁一律打桩、[hook] 指向夹具、[bijection] 与 [pmd-baseline] 打桩的基线环境。
# 每个 case 只在其上覆盖需要变的那一项。
run_case() { # <outfile> [额外 env 赋值...]
  local out=$1
  shift
  (
    export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_PMD="$OK_CMD" \
      MERGE_GATE_CMD_IT="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD" \
      MERGE_GATE_CMD_BIJECTION="$OK_CMD" MERGE_GATE_CMD_FRONTEND="$OK_CMD" \
      MERGE_GATE_CMD_PMD_BASELINE="$OK_CMD"
    export MERGE_GATE_HOOKS_DIR="$work/hooks-ok"
    for kv in "$@"; do export "$kv"; done
    cd "$repo_root" && bash "$gate"
  ) >"$out" 2>&1
  echo $?
}

mk_hooks_dir "$work/hooks-ok" "$FORWARDER_BODY"

# ---------------------------------------------------------------- 1. 全过 → 零退出
rc=$(run_case "$work/all-green.out")
expect_eq "1 全部子门禁通过时聚合零退出" "0" "$rc"
expect_has "1 输出里每个子门禁都留了 PASS" "PASS [unit]" "$work/all-green.out"

# ---------------------------------------------------------------- 2. 任一子门禁失败 → 非零且指名
for id in unit spotbugs pmd baseline bijection pmd-baseline; do
  case "$id" in
  unit) env_over="MERGE_GATE_CMD_UNIT=$BAD_CMD" ;;
  spotbugs) env_over="MERGE_GATE_CMD_SPOTBUGS=$BAD_CMD" ;;
  pmd) env_over="MERGE_GATE_CMD_PMD=$BAD_CMD" ;;
  baseline) env_over="MERGE_GATE_CMD_BASELINE=$BAD_CMD" ;;
  bijection) env_over="MERGE_GATE_CMD_BIJECTION=$BAD_CMD" ;;
  pmd-baseline) env_over="MERGE_GATE_CMD_PMD_BASELINE=$BAD_CMD" ;;
  esac
  out="$work/fail-$id.out"
  rc=$(run_case "$out" "$env_over")
  expect_eq "2 [$id] 失败时聚合非零" "1" "$rc"
  expect_has "2 [$id] 失败被指名" "FAIL [$id]" "$out"
  expect_has "2 [$id] 出现在尾部未通过清单里" "未通过的子门禁：$id" "$out"
done

# ---------------------------------------------------------------- 3. 多个失败要一次性全报出来，不能只报第一个
rc=$(run_case "$work/multi.out" "MERGE_GATE_CMD_UNIT=$BAD_CMD" "MERGE_GATE_CMD_BASELINE=$BAD_CMD")
expect_eq "3 多子门禁失败时聚合非零" "1" "$rc"
expect_has "3 同时指名 unit" "FAIL [unit]" "$work/multi.out"
expect_has "3 同时指名 baseline" "FAIL [baseline]" "$work/multi.out"
expect_has "3 尾部清单把两个失败都列全" "未通过的子门禁：unit baseline" "$work/multi.out"

# ---------------------------------------------------------------- 4. [it] 默认不跑，--with-verify 才跑
rc=$(run_case "$work/it-off.out")
expect_eq "4 默认序列零退出" "0" "$rc"
expect_has "4 [it] 默认被显式声明为未执行" "未执行" "$work/it-off.out"
expect_lacks "4 [it] 默认不产生 PASS" "PASS [it]" "$work/it-off.out"

run_case_verify() { # <outfile> [额外 env...]
  local out=$1
  shift
  (
    export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_PMD="$OK_CMD" \
      MERGE_GATE_CMD_IT="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD" \
      MERGE_GATE_CMD_BIJECTION="$OK_CMD" MERGE_GATE_CMD_FRONTEND="$OK_CMD" \
      MERGE_GATE_CMD_PMD_BASELINE="$OK_CMD"
    export MERGE_GATE_HOOKS_DIR="$work/hooks-ok"
    for kv in "$@"; do export "$kv"; done
    cd "$repo_root" && bash "$gate" --with-verify
  ) >"$out" 2>&1
  echo $?
}
rc=$(run_case_verify "$work/it-on.out" "MERGE_GATE_CMD_IT=$BAD_CMD")
expect_eq "4 --with-verify 时 [it] 失败会把整条拉红" "1" "$rc"
expect_has "4 --with-verify 的失败被指名" "FAIL [it]" "$work/it-on.out"

# ---------------------------------------------------------------- 5. [hook] 三道判别分别会红
mk_hooks_dir "$work/hooks-missing-file" "#!/bin/sh
exit 0"
rc=$(run_case "$work/hook-absent.out" "MERGE_GATE_HOOKS_DIR=$work/hooks-missing-file")
expect_eq "5 转发器内容追踪不到被转发目标时聚合非零" "1" "$rc"
expect_has "5 失败被指名到 [hook]" "FAIL [hook]" "$work/hook-absent.out"

mkdir -p "$work/hooks-empty"
rc=$(run_case "$work/hook-none.out" "MERGE_GATE_HOOKS_DIR=$work/hooks-empty")
expect_eq "5 生效目录内没有 pre-commit 时聚合非零" "1" "$rc"
expect_has "5 无 pre-commit 的失败被指名到 [hook]" "FAIL [hook]" "$work/hook-none.out"

# ---------------------------------------------------------------- 6. 未知参数必须硬错，不许静默当作没传
rc=$( (cd "$repo_root" && bash "$gate" --no-such-flag) >"$work/badarg.out" 2>&1; echo $? )
expect_eq "6 未知参数退出 2" "2" "$rc"

# ---------------------------------------------------------------- 7. 两道存在性判别在真仓上确实跑通（不打桩）
# 只把重型 Maven/基线步骤打桩，[hook] 用真实 git 解析出的 hooks 目录、[bijection] 用真实台账与被跟踪快照。
rc=$( (
  export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD"
  export MERGE_GATE_CMD_FRONTEND="$OK_CMD" MERGE_GATE_CMD_PMD="$OK_CMD" MERGE_GATE_CMD_PMD_BASELINE="$OK_CMD"
  cd "$repo_root" && bash "$gate"
) >"$work/real-existence.out" 2>&1; echo $? )
expect_eq "7 真实 [hook]+[bijection] 在当前工作副本上为零退出" "0" "$rc"
expect_has "7 [bijection] 真跑并给出双射结论" "RESULT=BIJECTION_OK" "$work/real-existence.out"
expect_has "7 [hook] 真跑指向 git 解析出的目录" "PASS [hook]" "$work/real-existence.out"

# ---------------------------------------------------------------- 8. merge-gate 与双射校验器真的串在一起
# 不注入桩命令：[bijection] 用真实校验器，只把校验器自己的 pom 路径钩子换成一份"没接线"的假 pom，
# 期望校验器判前置条件失败(2) → merge-gate 读成 [bijection] 失败(1) 并把原因透传出来。
printf '<project/>\n' >"$work/pom-nowiring.xml"
rc=$( (
  export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD"
  export MERGE_GATE_CMD_FRONTEND="$OK_CMD" MERGE_GATE_CMD_PMD="$OK_CMD" MERGE_GATE_CMD_PMD_BASELINE="$OK_CMD"
  export MERGE_GATE_HOOKS_DIR="$work/hooks-ok"
  export SPOTBUGS_POM_FILE="$work/pom-nowiring.xml"
  cd "$repo_root" && bash "$gate"
) >"$work/wiring.out" 2>&1; echo $? )
expect_eq "8 台账接线缺失经 merge-gate 聚合后非零" "1" "$rc"
expect_has "8 失败被指名到 [bijection]" "FAIL [bijection]" "$work/wiring.out"
expect_has "8 双射校验器给出的原因被透传" "excludeFilterFile" "$work/wiring.out"

# ---------------------------------------------------------------- 9. [frontend-unit]：会红，且缺依赖时降级不许静默
rc=$(run_case "$work/fe-fail.out" "MERGE_GATE_CMD_FRONTEND=$BAD_CMD")
expect_eq "9 前端单元轨失败时聚合非零" "1" "$rc"
expect_has "9 失败被指名到 [frontend-unit]" "FAIL [frontend-unit]" "$work/fe-fail.out"
expect_has "9 尾部清单列出 frontend-unit" "未通过的子门禁：frontend-unit" "$work/fe-fail.out"

rc=$(run_case "$work/fe-nodeps.out" "MERGE_GATE_FRONTEND_MODULES=$work/no-such-modules")
expect_eq "9 依赖缺失时不判红（安装属联网动作）" "0" "$rc"
expect_has "9 依赖缺失被显式声明为未执行" "[frontend-unit] 未执行" "$work/fe-nodeps.out"
expect_has "9 通过态里显式声明本证据未覆盖前端轨" "未覆盖前端 Vitest 单元轨" "$work/fe-nodeps.out"
expect_lacks "9 降级时不误报前端轨 PASS" "PASS [frontend-unit]" "$work/fe-nodeps.out"
expect_lacks "9 依赖在位时不多报未覆盖" "未覆盖前端 Vitest 单元轨" "$work/all-green.out"

# ---------------------------------------------------------------- 10. 行尾差异不得影响双射（autocrlf 回归锁）
# 起因：2026-09-21 在 Windows（core.autocrlf=true）上跑真实 merge-gate 时 [bijection] 红了，
# MISS 与 STALE 是同一批 11 行 —— 被跟踪的台账与快照被检出成 CRLF，\r 挂在第 4 列尾巴上，
# 只有文件末行（无尾换行）能配上。这是平台差异不是缺陷差异。此处复刻该形态并锁住两侧：
# CRLF 必须照样成立，而"真的对不上"必须照样变红（证明这条断言不恒真）。
mk_crlf() { tr -d '\r' < "$1" | sed -e 's/$/\r/' > "$2"; }
mk_crlf "$repo_root/src/main/resources/spotbugs-exclude.xml" "$work/exclude-crlf.xml"
mk_crlf "$repo_root/scripts/tests/spotbugs-high-baseline.tsv" "$work/snapshot-crlf.tsv"
crlf_gate() { # <outfile> <snapshot>
  local out=$1 snap=$2
  (
    export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD"
    export MERGE_GATE_CMD_FRONTEND="$OK_CMD" MERGE_GATE_HOOKS_DIR="$work/hooks-ok"
    export MERGE_GATE_CMD_PMD="$OK_CMD" MERGE_GATE_CMD_PMD_BASELINE="$OK_CMD"
    export SPOTBUGS_EXCLUDE_FILE="$work/exclude-crlf.xml" SPOTBUGS_HIGH_SNAPSHOT="$snap"
    cd "$repo_root" && bash "$gate"
  ) >"$out" 2>&1
  echo $?
}
rc=$(crlf_gate "$work/crlf.out" "$work/snapshot-crlf.tsv")
expect_eq "10 CRLF 台账 + CRLF 快照下双射仍成立" "0" "$rc"
expect_has "10 双射真跑出 OK 结论" "RESULT=BIJECTION_OK" "$work/crlf.out"

sed -e 's/threadLocal/threadLocalRenamed/' "$work/snapshot-crlf.tsv" > "$work/snapshot-crlf-bad.tsv"
rc=$(crlf_gate "$work/crlf-bad.out" "$work/snapshot-crlf-bad.tsv")
expect_eq "10 台账与快照真的对不上时必须变红" "1" "$rc"
expect_has "10 失败被指名到 [bijection]" "FAIL [bijection]" "$work/crlf-bad.out"
# 被跟踪的台账与快照必须一字未动（本场景只许改临时副本）
if [ -z "$(cd "$repo_root" && git status --porcelain -- src/main/resources/spotbugs-exclude.xml scripts/tests/spotbugs-high-baseline.tsv)" ]; then
  pass "10 未污染被跟踪的台账与快照"
else
  die "10 被跟踪的台账或快照被本场景改动"
fi

# ---------------------------------------------------------------- 11. [pmd] 与 [pmd-baseline]（wire-pmd-ruleset 组 4.3）
# 场景 2 只锁住"这两道失败会被聚合指名"；本场景锁的是判定本身：
#   用**真实的** scripts/tests/pmd-baseline-check.sh（[pmd] 那条 Maven 仍打桩，保持不跑 Maven），
#   只把它的三个输入换成临时夹具，证明三种态各自的对错，并且 CRLF 夹具不许把对的说成错的、
#   也不许把错的说成对的（autocrlf 回归锁，与场景 10 同一族坑）。
mk_pmd_report() { # <out> <violations>
  local out=$1 n=$2 i=0
  {
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.9.0">\n'
    printf '<file name="/a/A.java">\n'
    while [ "$i" -lt "$n" ]; do
      printf '<violation beginline="1" endline="1" begincolumn="1" endcolumn="1" rule="OnlyOneReturn" ruleset="Code Style" class="A" priority="3">m</violation>\n'
      i=$((i + 1))
    done
    printf '</file>\n</pmd>\n'
  } >"$out"
}
mk_pmd_pom() { # <out> <maxAllowedViolations> <crlf:0|1>
  local out=$1 n=$2 crlf=$3
  {
    printf '<project><build><plugins><plugin><artifactId>maven-pmd-plugin</artifactId><configuration>'
    printf '<rulesets><ruleset>src/main/resources/pmd-rules.xml</ruleset></rulesets>'
    printf '<maxAllowedViolations>%s</maxAllowedViolations>' "$n"
    printf '</configuration></plugin></plugins></build></project>\n'
  } >"$out"
  if [ "$crlf" = "1" ]; then sed -e 's/$/\r/' "$out" >"$out.cr" && mv "$out.cr" "$out"; fi
}
mk_pmd_ledger() { # <out> <pmd.violations> <crlf:0|1>
  local out=$1 n=$2 crlf=$3
  printf '# 临时台账（自测夹具）\npmd.violations=%s\npmd.files=1\n' "$n" >"$out"
  if [ "$crlf" = "1" ]; then sed -e 's/$/\r/' "$out" >"$out.cr" && mv "$out.cr" "$out"; fi
}
pmd_gate() { # <outfile> <ledger> <pom>
  local out=$1 ledger=$2 pompom=$3
  (
    export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_PMD="$OK_CMD"
    export MERGE_GATE_CMD_IT="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD" MERGE_GATE_CMD_FRONTEND="$OK_CMD"
    export MERGE_GATE_CMD_BIJECTION="$OK_CMD" MERGE_GATE_HOOKS_DIR="$work/hooks-ok"
    export PMD_REPORT="$work/pmd-report.xml" PMD_BASELINE_LEDGER="$ledger" PMD_POM_FILE="$pompom"
    cd "$repo_root" && bash "$gate"
  ) >"$out" 2>&1
  echo $?
}
mk_pmd_report "$work/pmd-report.xml" 7
# 夹具跑完之后必须证明被跟踪的 pom / 台账一个字节没动（pom 在本包里本来就是改动态，
# 所以这里比对的是"进入本场景时的高度"而不是 git status 是否干净）。
pom_hash_before=$(LC_ALL=C tr -d '\r' < "$repo_root/pom.xml" | cksum)
ledger_hash_before=""
if [ -f "$repo_root/scripts/tests/pmd-violation-baseline.txt" ]; then
  ledger_hash_before=$(LC_ALL=C tr -d '\r' < "$repo_root/scripts/tests/pmd-violation-baseline.txt" | cksum)
fi

# 11a 实测 == 登记 == pom 阈值（LF 夹具）→ 必须绿
mk_pmd_ledger "$work/pmd-ledger-eq.txt" 7 0
mk_pmd_pom "$work/pmd-pom-eq.xml" 7 0
rc=$(pmd_gate "$work/pmd-eq.out" "$work/pmd-ledger-eq.txt" "$work/pmd-pom-eq.xml")
expect_eq "11 实测=登记=pom 时聚合零退出" "0" "$rc"
expect_has "11 [pmd-baseline] 真跑出 OK 结论" "RESULT=PMD_BASELINE_OK" "$work/pmd-eq.out"
expect_has "11 PASS 归属 [pmd-baseline]" "PASS [pmd-baseline]" "$work/pmd-eq.out"

# 11b 同一组数据换成 CRLF 夹具 → 必须照样绿（否则 autocrlf 会把绿判成红）
mk_pmd_ledger "$work/pmd-ledger-eq-crlf.txt" 7 1
mk_pmd_pom "$work/pmd-pom-eq-crlf.xml" 7 1
rc=$(pmd_gate "$work/pmd-eq-crlf.out" "$work/pmd-ledger-eq-crlf.txt" "$work/pmd-pom-eq-crlf.xml")
expect_eq "11 CRLF 台账 + CRLF pom 下依然为零退出" "0" "$rc"
expect_has "11 CRLF 夹具下双射结论照样成立" "RESULT=PMD_BASELINE_OK" "$work/pmd-eq-crlf.out"

# 11c 登记 > 实测（台账留了冗余，CRLF 夹具）→ 必须红并指名
mk_pmd_ledger "$work/pmd-ledger-stale.txt" 9 1
mk_pmd_pom "$work/pmd-pom-stale.xml" 9 1
rc=$(pmd_gate "$work/pmd-stale.out" "$work/pmd-ledger-stale.txt" "$work/pmd-pom-stale.xml")
expect_eq "11 登记值高于实测时聚合非零" "1" "$rc"
expect_has "11 失败被指名到 [pmd-baseline]" "FAIL [pmd-baseline]" "$work/pmd-stale.out"
expect_has "11 过期原因被透传" "基线过期" "$work/pmd-stale.out"
expect_has "11 尾部清单列出 pmd-baseline" "未通过的子门禁：pmd-baseline" "$work/pmd-stale.out"

# 11d 实测 > 登记（新引入违规）→ 必须红
mk_pmd_ledger "$work/pmd-ledger-exceed.txt" 5 0
mk_pmd_pom "$work/pmd-pom-exceed.xml" 5 0
rc=$(pmd_gate "$work/pmd-exceed.out" "$work/pmd-ledger-exceed.txt" "$work/pmd-pom-exceed.xml")
expect_eq "11 实测超出登记时聚合非零" "1" "$rc"
expect_has "11 超基线原因被透传" "新增违规超基线" "$work/pmd-exceed.out"

# 11e pom 与台账不等（条数口径出现两个数字）→ 必须红
mk_pmd_ledger "$work/pmd-ledger-drift.txt" 7 0
mk_pmd_pom "$work/pmd-pom-drift.xml" 8 0
rc=$(pmd_gate "$work/pmd-drift.out" "$work/pmd-ledger-drift.txt" "$work/pmd-pom-drift.xml")
expect_eq "11 pom 阈值与台账漂移时聚合非零" "1" "$rc"
expect_has "11 漂移原因被透传" "两个数字" "$work/pmd-drift.out"

# 11f <skip> 回到 pmd 块里 → 前置缺失（exit 2）也必须被聚合成红并透传
{
  printf '<project><build><plugins><plugin><artifactId>maven-pmd-plugin</artifactId><configuration>'
  printf '<rulesets><ruleset>src/main/resources/pmd-rules.xml</ruleset></rulesets>'
  printf '<skip>true</skip><maxAllowedViolations>7</maxAllowedViolations>'
  printf '</configuration></plugin></plugins></build></project>\n'
} >"$work/pmd-pom-skip.xml"
rc=$(pmd_gate "$work/pmd-skip.out" "$work/pmd-ledger-eq.txt" "$work/pmd-pom-skip.xml")
expect_eq "11 pmd 块重新出现 <skip> 时聚合非零" "1" "$rc"
expect_has "11 跳过态的拒绝裁决理由被透传" "拒绝裁决" "$work/pmd-skip.out"

# 11h 零违规报告（PMD 只为"有违规的文件"输出 <file name=，所以全清后报告里 0 个 <file>）：
#     report 新鲜 → 必须绿，基线可以登记为 0（2026-09-23 F-4 批补的零值路径）。
mk_pmd_report_zero() { # <out>
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.9.0">\n</pmd>\n' >"$1"
}
mk_pmd_report_zero "$work/pmd-report-zero.xml"
mk_pmd_ledger "$work/pmd-ledger-zero.txt" 0 0
mk_pmd_pom "$work/pmd-pom-zero.xml" 0 0
(
  export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_PMD="$OK_CMD"
  export MERGE_GATE_CMD_IT="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD" MERGE_GATE_CMD_FRONTEND="$OK_CMD"
  export MERGE_GATE_CMD_BIJECTION="$OK_CMD" MERGE_GATE_HOOKS_DIR="$work/hooks-ok"
  export PMD_REPORT="$work/pmd-report-zero.xml" PMD_BASELINE_LEDGER="$work/pmd-ledger-zero.txt"
  export PMD_POM_FILE="$work/pmd-pom-zero.xml"
  cd "$repo_root" && bash "$gate"
) >"$work/pmd-zero.out" 2>&1
rc=$?
expect_eq "11 零违规 + 新鲜报告时聚合零退出（基线可登记为 0）" "0" "$rc"
expect_has "11 零值路径真跑出 OK 结论" "RESULT=PMD_BASELINE_OK" "$work/pmd-zero.out"

# 11i 同一份零违规报告但把 mtime 退到 2000 年（＝比源文件旧，等价于"没跑过/拿旧报告裁决"）
#     → 必须按前置缺失判红并指名到 [pmd-baseline]，不许把"没分析"读成"零违规"。
cp "$work/pmd-report-zero.xml" "$work/pmd-report-zero-stale.xml"
touch -t 200001010000 "$work/pmd-report-zero-stale.xml"
(
  export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" MERGE_GATE_CMD_PMD="$OK_CMD"
  export MERGE_GATE_CMD_IT="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD" MERGE_GATE_CMD_FRONTEND="$OK_CMD"
  export MERGE_GATE_CMD_BIJECTION="$OK_CMD" MERGE_GATE_HOOKS_DIR="$work/hooks-ok"
  export PMD_REPORT="$work/pmd-report-zero-stale.xml" PMD_BASELINE_LEDGER="$work/pmd-ledger-zero.txt"
  export PMD_POM_FILE="$work/pmd-pom-zero.xml"
  cd "$repo_root" && bash "$gate"
) >"$work/pmd-zero-stale.out" 2>&1
rc=$?
expect_eq "11 零违规但报告比源文件旧时聚合非零" "1" "$rc"
expect_has "11 失败被指名到 [pmd-baseline]" "FAIL [pmd-baseline]" "$work/pmd-zero-stale.out"
expect_has "11 不可裁决态的理由被透传" "拒绝把" "$work/pmd-zero-stale.out"

# 11g 被跟踪的 PMD 台账与 pom 不得被这些夹具改动（按进入本场景时的 cksum 比，忽略行尾差异）
pom_hash_after=$(LC_ALL=C tr -d '\r' < "$repo_root/pom.xml" | cksum)
ledger_hash_after=""
if [ -f "$repo_root/scripts/tests/pmd-violation-baseline.txt" ]; then
  ledger_hash_after=$(LC_ALL=C tr -d '\r' < "$repo_root/scripts/tests/pmd-violation-baseline.txt" | cksum)
fi
expect_eq "11 未污染被跟踪的 pom（正文 cksum 不变）" "$pom_hash_before" "$pom_hash_after"
expect_eq "11 未污染被跟踪的 PMD 台账（正文 cksum 不变）" "$ledger_hash_before" "$ledger_hash_after"

echo
if [ "$failures" -gt 0 ]; then
  echo "merge-gate 自测失败：$checks 条断言中 $failures 条 NOT OK" >&2
  exit 1
fi
echo "merge-gate 自测通过：$checks 条断言全绿（无 Docker / 无外网 / 未执行 Maven）"
