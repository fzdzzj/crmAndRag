#!/usr/bin/env bash
# Self-test for scripts/merge-gate.sh（harness-gates 组 5.3，spec R4）。
#
# 锁住的是**聚合逻辑**：任一子门禁失败时整条命令必须非零退出、并且指名是哪一步；
# 全过时零退出；[it] 默认不跑；失败可累积；[hook] 与 [bijection] 两道存在性判别确实会红。
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

# 所有重型子门禁一律打桩、[hook] 指向夹具、[bijection] 打桩的基线环境。
# 每个 case 只在其上覆盖需要变的那一项。
run_case() { # <outfile> [额外 env 赋值...]
  local out=$1
  shift
  (
    export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" \
      MERGE_GATE_CMD_IT="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD" \
      MERGE_GATE_CMD_BIJECTION="$OK_CMD" MERGE_GATE_CMD_FRONTEND="$OK_CMD"
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
for id in unit spotbugs baseline bijection; do
  case "$id" in
  unit) env_over="MERGE_GATE_CMD_UNIT=$BAD_CMD" ;;
  spotbugs) env_over="MERGE_GATE_CMD_SPOTBUGS=$BAD_CMD" ;;
  baseline) env_over="MERGE_GATE_CMD_BASELINE=$BAD_CMD" ;;
  bijection) env_over="MERGE_GATE_CMD_BIJECTION=$BAD_CMD" ;;
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
    export MERGE_GATE_CMD_UNIT="$OK_CMD" MERGE_GATE_CMD_SPOTBUGS="$OK_CMD" \
      MERGE_GATE_CMD_IT="$OK_CMD" MERGE_GATE_CMD_BASELINE="$OK_CMD" \
      MERGE_GATE_CMD_BIJECTION="$OK_CMD" MERGE_GATE_CMD_FRONTEND="$OK_CMD"
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
  export MERGE_GATE_CMD_FRONTEND="$OK_CMD"
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
  export MERGE_GATE_CMD_FRONTEND="$OK_CMD"
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

echo
if [ "$failures" -gt 0 ]; then
  echo "merge-gate 自测失败：$checks 条断言中 $failures 条 NOT OK" >&2
  exit 1
fi
echo "merge-gate 自测通过：$checks 条断言全绿（无 Docker / 无外网 / 未执行 Maven）"
