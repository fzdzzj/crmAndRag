#!/usr/bin/env bash
# Self-test for scripts/check-test-baseline.sh (TASK-19 E1).
#
# What it locks down: xargs splits the report list into several awk batches as soon as the
# argument list exceeds the OS batch limit, and each batch used to print its own END tuple.
# The gate then read a glued / multi-line tuple and produced nonsense such as "Skipped=047 0 0 0"
# (TASK-17 saw 697 real tests counted as 650 on a Windows runner). measure() must now return
# exactly one "<reports> <tests> <failures> <errors> <skipped>" tuple however the batching works.
#
# Run it directly (no Docker, no network, no maven):
#   bash scripts/tests/check-test-baseline-selftest.sh
# Exit code 0 = every assertion held.
#
# The gate is sourced with BASELINE_LIB_ONLY=1 so the real measure()/aggregate_tuples()
# implementations are exercised, not a copy of them. BASELINE_XARGS_LIMIT=<n> is the test-only
# hook inside the gate that forces "xargs -0 -n <n>", which turns a 12-file fixture directory into
# 12 genuine awk batches and makes the split reproducible at a small scale.
#
# Second fix locked down here (残留报告抬高基线计数, 2026-09-28): the gate counts only reports whose
# class simple name matches the pom's default surefire/failsafe includes ("默认口径"), and treats any
# other *.txt in target/*-reports as 口径外残留 (典型: opt-in 基准 KB_SCOPE_AUTH_MEASURE=1 留下的
# *Benchmark 报告): check goes red and lists them by name; --update refuses to write them into the
# ledger. An unparseable pom must fail closed (拒绝裁决) instead of counting everything. Fixture
# report names below carry real default-scope suffixes (*Test / *IT) for exactly that reason, and
# the e2e fixture repo root now ships its own pom.xml.
set -uo pipefail

selftest_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
scripts_dir=$(CDPATH= cd -- "$selftest_dir/.." && pwd)
gate_path="$scripts_dir/check-test-baseline.sh"

failures=0
checks=0

pass() {
  checks=$((checks + 1))
  echo "ok - $*"
}

die_case() {
  checks=$((checks + 1))
  failures=$((failures + 1))
  echo "NOT OK - $*" >&2
}

expect_eq() { # <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    pass "$1"
  else
    die_case "$1: expected [$2] but got [$3]"
  fi
}

expect_ne() { # <label> <unexpected> <actual>
  if [ "$2" != "$3" ]; then
    pass "$1"
  else
    die_case "$1: value must NOT be [$2]"
  fi
}

expect_line() { # <label> <file> <expected line>
  local actual
  actual=$(sed -n "s/^$3=//p" "$2" | tail -1 | tr -d '[:space:]')
  if [ -n "$actual" ]; then
    pass "$1 ($3=$actual)"
  else
    die_case "$1: no $3= entry in $2"
  fi
}

if [ ! -f "$gate_path" ]; then
  echo "cannot find gate script: $gate_path" >&2
  exit 1
fi

# Load the gate functions without running the gate.
BASELINE_LIB_ONLY=1
# shellcheck source=/dev/null
. "$gate_path"

# ---------------------------------------------------------------------------
# 1) aggregate_tuples: the cross-batch summation itself
# ---------------------------------------------------------------------------
expect_eq "aggregate: one batch passes through" \
  "717 0 0 5" "$(printf '717 0 0 5\n' | aggregate_tuples)"

expect_eq "aggregate: three batches are summed field by field" \
  "700 1 3 6" "$(printf '300 1 2 3\n250 0 1 0\n150 0 0 3\n' | aggregate_tuples)"

expect_eq "aggregate: batch order does not matter" \
  "700 1 3 6" "$(printf '150 0 0 3\n300 1 2 3\n250 0 1 0\n' | aggregate_tuples)"

expect_eq "aggregate: no input at all yields zeros, not garbage" \
  "0 0 0 0" "$(printf '' | aggregate_tuples)"

agg_two_batches=$(printf '300 1 2 3\n250 0 1 0\n' | aggregate_tuples)
expect_eq "aggregate: two batches collapse into one line" "550 1 3 3" "$agg_two_batches"
expect_eq "aggregate: that line holds exactly four numbers" "4" \
  "$(printf '%s\n' "$agg_two_batches" | wc -w | tr -d ' ')"

# ---------------------------------------------------------------------------
# 2) measure(): real fixture directory, forced into many awk batches
# ---------------------------------------------------------------------------
tmp_root=$(mktemp -d 2>/dev/null) || {
  echo "mktemp -d failed" >&2
  exit 1
}
# shellcheck disable=SC2064
trap "rm -rf '$tmp_root'" EXIT

write_report() { # <dir> <name> <tests> <failures> <errors> <skipped> <duplicate-line:yes/no>
  {
    echo "-------------------------------------------------------------------------------"
    echo "Test set: $2"
    echo "-------------------------------------------------------------------------------"
    echo "Tests run: $3, Failures: $4, Errors: $5, Skipped: $6, Time elapsed: 0.066 s -- in $2"
    if [ "$7" = "yes" ]; then
      # Second Tests run line in the same file: measure() must only ever count the first one.
      echo "Tests run: 999, Failures: 9, Errors: 9, Skipped: 9, Time elapsed: 0.066 s -- in $2"
    fi
  } >"$1/$2.txt"
}

sf_dir="$tmp_root/surefire-reports"
fa_dir="$tmp_root/failsafe-reports"
mkdir -p "$sf_dir" "$fa_dir"

# surefire: tests 5 7 3 11 2 8 1 4 6 9 10 12 = 78, skipped 1 (r03) + 2 (r07) = 3, failures/errors 0.
# Fixture names carry the default-scope suffix so the pom-driven scope filter sees them as in scope;
# "fixture.sf.99Benchmark" is added later as the out-of-scope counterpart.
sf_tests=(5 7 3 11 2 8 1 4 6 9 10 12)
sf_skipped=(0 0 1 0 0 0 2 0 0 0 0 0)
for i in "${!sf_tests[@]}"; do
  name=$(printf 'fixture.sf.%02dTest' "$((i + 1))")
  dup="no"
  if [ "$((i + 1))" -eq 5 ]; then
    dup="yes"
  fi
  write_report "$sf_dir" "$name" "${sf_tests[$i]}" 0 0 "${sf_skipped[$i]}" "$dup"
done

# failsafe: tests 3 4 2 1 = 10, skipped 1 1 0 0 = 2.
fa_tests=(3 4 2 1)
fa_skipped=(1 1 0 0)
for i in "${!fa_tests[@]}"; do
  name=$(printf 'fixture.fa.%02dIT' "$((i + 1))")
  write_report "$fa_dir" "$name" "${fa_tests[$i]}" 0 0 "${fa_skipped[$i]}" "no"
done

# 12 files, one of them carrying a duplicate Tests-run line that must stay ignored: 78 not 1077.
expect_eq "measure: default batching over 12 reports" \
  "12 78 0 0 3" "$(measure "$sf_dir")"

# One file per awk batch = 12 batches = the exact failure mode of E1.
expect_eq "measure: every file in its own batch (xargs -n 1)" \
  "12 78 0 0 3" "$(BASELINE_XARGS_LIMIT=1 measure "$sf_dir")"

expect_eq "measure: five files per batch (xargs -n 5)" \
  "12 78 0 0 3" "$(BASELINE_XARGS_LIMIT=5 measure "$sf_dir")"

expect_eq "measure: failsafe totals" \
  "4 10 0 0 2" "$(measure "$fa_dir")"

expect_eq "measure: failsafe totals under forced batching" \
  "4 10 0 0 2" "$(BASELINE_XARGS_LIMIT=1 measure "$fa_dir")"

# The test hook is a hook, not a licence to invent numbers: a non-numeric limit must fail closed.
if BASELINE_XARGS_LIMIT=abc measure "$sf_dir" >/dev/null 2>&1; then
  die_case "measure: a non-numeric BASELINE_XARGS_LIMIT must fail"
else
  pass "measure: a non-numeric BASELINE_XARGS_LIMIT fails closed"
fi

# Missing / empty directories must stay unmeasurable instead of reporting 0 tests.
if measure "$tmp_root/no-such-dir" >/dev/null 2>&1; then
  die_case "measure: missing report dir must not be measurable"
else
  pass "measure: missing report dir is unmeasurable"
fi
mkdir -p "$tmp_root/empty-reports"
if measure "$tmp_root/empty-reports" >/dev/null 2>&1; then
  die_case "measure: empty report dir must not be measurable"
else
  pass "measure: empty report dir is unmeasurable"
fi

# ---------------------------------------------------------------------------
# 2b) 默认口径：只统计 pom 默认 includes 内的报告；口径外残留可列出、可从计数中剔除
# ---------------------------------------------------------------------------
# The scope stems come from the *real* pom (baseline_pom was fixed when the gate was sourced), so
# this block also locks that the shipped pom parses.
if load_scope_patterns; then
  pass "scope: the real pom's surefire/failsafe includes parse"
else
  die_case "scope: load_scope_patterns failed on the real pom"
fi

expect_eq "scope: pattern_stem normalizes **/*Foo.java" "Foo" "$(pattern_stem '**/*Foo.java')"
if pattern_stem 'Foo.java' >/dev/null 2>&1; then
  die_case "scope: a pattern without the **/* prefix must be rejected, not guessed"
else
  pass "scope: unknown pattern shapes are rejected (宁红不猜)"
fi

if report_in_scope surefire "com.pkg.FooTest"; then
  pass "scope: *Test is inside the surefire defaults"
else
  die_case "scope: *Test must be in surefire scope"
fi
if report_in_scope surefire "com.pkg.FooIT"; then
  die_case "scope: surefire's <exclude>**/*IT.java</exclude> must keep *IT out"
else
  pass "scope: *IT is out of surefire scope (pom exclude honoured)"
fi
if report_in_scope failsafe "com.pkg.FooIT"; then
  pass "scope: *IT is inside the failsafe defaults"
else
  die_case "scope: *IT must be in failsafe scope"
fi
if report_in_scope failsafe "com.pkg.FooIntegrationTest"; then
  pass "scope: *IntegrationTest is inside the failsafe defaults"
else
  die_case "scope: *IntegrationTest must be in failsafe scope"
fi

expect_eq "scope: all 12 surefire fixtures are in scope" \
  "12 78 0 0 3" "$(measure "$sf_dir" surefire)"
expect_eq "scope: all 4 failsafe fixtures are in scope" \
  "4 10 0 0 2" "$(measure "$fa_dir" failsafe)"

# An opt-in benchmark report is the residual that used to inflate the counts silently: scoped
# counting must ignore it while the unscoped (legacy) count still shows what the old gate saw.
sf_mixed="$tmp_root/sf-mixed"
mkdir -p "$sf_mixed"
cp "$sf_dir"/*.txt "$sf_mixed/"
write_report "$sf_mixed" "fixture.sf.99Benchmark" 7 0 0 0 "no"

expect_eq "scope: the benchmark report is listed as out of scope" \
  "fixture.sf.99Benchmark.txt" "$(out_of_scope_reports "$sf_mixed" surefire)"
expect_eq "scope: a clean dir lists no out-of-scope reports" \
  "" "$(out_of_scope_reports "$sf_dir" surefire)"
expect_eq "scope: scoped measure drops the residual" \
  "12 78 0 0 3" "$(measure "$sf_mixed" surefire)"
expect_eq "scope: unscoped measure shows what the old gate counted" \
  "13 85 0 0 3" "$(measure "$sf_mixed")"

# ---------------------------------------------------------------------------
# 2c) 单趟分区（F-1a）：一趟遍历同时产出口径内列表、口径外名单与份数
# ---------------------------------------------------------------------------
# 旧实现每个 suite 走 3 趟（列名 1 趟 + measure 内数数/求和各 1 趟）且每份报告 1-2 次
# $(stems_of …) 派生，153 份报告的 clean 态实测 1m14s。这里锁住新分区的产出契约：
# 三个输出来自同一趟，且与 scoped measure / report_in_scope 的既有结论逐条一致。
mixed_list=$(mktemp)
partition_reports "$sf_mixed" surefire "$mixed_list"
expect_eq "partition: 口径内份数与 scoped measure 一致" "12" "$SCOPE_IN_COUNT"
count_nul_entries "$mixed_list"
expect_eq "partition: 口径内 NUL 列表条数与份数一致" "12" "$NUL_COUNT"
expect_eq "partition: 口径外名单正是那份基准残留" \
  "fixture.sf.99Benchmark.txt" "$(printf '%s' "$SCOPE_FOREIGN")"
partition_mismatch=0
while IFS= read -r -d '' pf; do
  pb="${pf##*/}"
  report_in_scope surefire "${pb%.txt}" || partition_mismatch=1
done <"$mixed_list"
expect_eq "partition: 口径内列表逐条与 report_in_scope 一致" "0" "$partition_mismatch"
rm -f "$mixed_list"

# ---------------------------------------------------------------------------
# 2d) strip_xml_comments 必须跨行（F-1b）：注释续行里的 include/exclude 不得进入任何词干
# ---------------------------------------------------------------------------
# 旧实现逐行配对 <!--/-->：开启行把行内剩余部分丢弃，但续行不带 <!--，被整行原样输出 ——
# 注释里写的 <include>**/*Benchmark.java</include> 就被当成真配置读进词干，opt-in 基准残留
# （如 com.slz.crm.quality.KnowledgeBaseScopeAuthBaselineBenchmark）被静默当成口径内计数，
# 正好重开「残留报告抬高基线计数」缺口且不报错、不判红（fail-open）。
stripped_multi=$(printf 'a <!-- one --> b <!-- two --> c\n' | strip_xml_comments)
expect_eq "strip: 单行内多段注释仍逐段剥净（既有性质保留）" "a  b  c" "$stripped_multi"

stripped_cross=$(printf '<!-- 跨行注释开启（注释体内故意放 > 尖括号），\n<include>**/*Benchmark.java</include>\n<exclude>**/*Never.java</exclude> 更多正文 -->\n<include>**/*Test.java</include>\n' | strip_xml_comments)
expect_eq "strip: 跨行注释 3 行整段丢弃、收口后真 include 保留（行行对应）" \
  "$(printf '\n\n\n<include>**/*Test.java</include>')" "$stripped_cross"
case "$stripped_cross" in
  *Benchmark* | *Never*) die_case "strip: 跨行注释续行里的词干泄漏进了输出" ;;
  *) pass "strip: 跨行注释续行里的 Benchmark/Never 与注释体一起被丢弃" ;;
esac

comment_pom="$tmp_root/pom-comment-crossline.xml"
{
  printf '<project><build><plugins>\n'
  printf '<plugin><artifactId>maven-surefire-plugin</artifactId><configuration>\n'
  printf '<includes>\n'
  printf '<!-- 跨行注释开启（注释体内故意放 > 尖括号），延续到下面两行：\n'
  printf '<include>**/*Benchmark.java</include>\n'
  printf '<exclude>**/*Never.java</exclude> 更多正文 -->\n'
  printf '<include>**/*Test.java</include><include>**/*Tests.java</include>\n'
  printf '</includes>\n'
  printf '<excludes><exclude>**/*IT.java</exclude></excludes>\n'
  printf '</configuration></plugin>\n'
  printf '<plugin><artifactId>maven-failsafe-plugin</artifactId><configuration>\n'
  printf '<includes><include>**/*IT.java</include><include>**/*IntegrationTest.java</include></includes>\n'
  printf '</configuration></plugin>\n'
  printf '</plugins></build></project>\n'
} >"$comment_pom"
saved_baseline_pom=$baseline_pom
baseline_pom="$comment_pom"
if load_scope_patterns; then
  pass "scope: 跨行注释 pom 仍可解析（注释不把口径判为不可判定）"
  case "$SUREFIRE_INCLUDES" in
    *Benchmark*) die_case "scope: 跨行注释续行里的 Benchmark 进了 include 词干（fail-open）" ;;
    *) pass "scope: 跨行注释续行里的 Benchmark 未进入 include 词干" ;;
  esac
  case "$SUREFIRE_EXCLUDES" in
    *Never*) die_case "scope: 跨行注释续行里的 Never 进了 exclude 词干" ;;
    *) pass "scope: 跨行注释续行里的 Never 未进入 exclude 词干" ;;
  esac
  expect_eq "scope: 注释外的真 include 仍是全部 include 词干" \
    "$(printf 'Test\nTests')" "$SUREFIRE_INCLUDES"
  if report_in_scope surefire "com.slz.crm.quality.KnowledgeBaseScopeAuthBaselineBenchmark"; then
    die_case "scope: opt-in 基准类简名被判进口径内 —— F-1b fail-open 复现路径未封死"
  else
    pass "scope: 跨行注释不再把 *Benchmark 放进口径（任务卡复现路径封死）"
  fi
  if report_in_scope surefire "com.pkg.FooTest"; then
    pass "scope: 注释外的真 include 仍把 *Test 放进口径"
  else
    die_case "scope: 注释剥过头，真 include 丢了"
  fi
else
  die_case "scope: 跨行注释 pom 解析失败（不该把注释当成不可判定）"
fi
baseline_pom=$saved_baseline_pom
if load_scope_patterns; then
  pass "scope: 恢复真 pom 后词干重新加载"
else
  die_case "scope: 恢复真 pom 后 load_scope_patterns 失败"
fi

# ---------------------------------------------------------------------------
# 2e) exclude 分支的真命中（F-1c）：必须构造出同时命中 include 与 exclude 词干的类简名
# ---------------------------------------------------------------------------
# 上面 "*IT is out of surefire scope" 那条断言为假的原因是 FooIT 不匹配 include 词干
# （Test/Tests）—— exclude 分支根本没被走到，是空过。真 pom 的 surefire include（Test/Tests）
# 与 exclude（IT）后缀互斥：一个类简名只有一种结尾，两组词干要同时命中，只有当 exclude 词干
# 以 include 词干为结尾（如 include=Test、exclude=FooTest）才构造得出，真 pom 的词干组合
# 覆盖不到这条分支 —— 所以按任务卡允许的手段临时换词干，断言后立即恢复。
saved_sf_inc=$SUREFIRE_INCLUDES
saved_sf_exc=$SUREFIRE_EXCLUDES
SUREFIRE_INCLUDES=$(printf 'Test\nTests')
SUREFIRE_EXCLUDES=$(printf 'FooTest')
if report_in_scope surefire "com.pkg.BarFooTest"; then
  die_case "scope: 同时命中 include(Test) 与 exclude(FooTest) 的名字必须判口径外（exclude 真生效）"
else
  pass "scope: 同时命中两组词干的名字判口径外（exclude 分支真命中，而非 include 不命中）"
fi
if report_in_scope surefire "com.pkg.BarBazTest"; then
  pass "scope: 同一组词干下不命中 exclude 的名字仍在口径内（对照组）"
else
  die_case "scope: 对照组 BarBazTest 应在口径内"
fi
exc_dir="$tmp_root/exc-fixture"
mkdir -p "$exc_dir"
printf 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n' >"$exc_dir/com.pkg.BarFooTest.txt"
printf 'Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n' >"$exc_dir/com.pkg.BarBazTest.txt"
exc_list=$(mktemp)
partition_reports "$exc_dir" surefire "$exc_list"
expect_eq "partition: exclude 真命中的报告进口径外名单" \
  "com.pkg.BarFooTest.txt" "$(printf '%s' "$SCOPE_FOREIGN")"
expect_eq "partition: 口径内份数只算对照组" "1" "$SCOPE_IN_COUNT"
count_nul_entries "$exc_list"
expect_eq "partition: 口径内 NUL 列表条数与份数一致" "1" "$NUL_COUNT"
rm -f "$exc_list"
SUREFIRE_INCLUDES=$saved_sf_inc
SUREFIRE_EXCLUDES=$saved_sf_exc

# ---------------------------------------------------------------------------
# 3) Prove this self-test CAN go red: the pre-fix pipeline, same fixtures.
# ---------------------------------------------------------------------------
legacy_measure() { # <dir> <xargs-opts> -- the shape of measure() before TASK-19
  local dir="$1" opts="$2"
  # shellcheck disable=SC2086
  find "$dir" -maxdepth 1 -name '*.txt' -type f -print0 \
    | xargs -0 $opts awk -F'[:,]' '
        /^Tests run:/ && !seen[FILENAME]++ {
          tests += $2; failures += $4; errors += $6; skipped += $8
        }
        END { printf "%d %d %d %d", tests, failures, errors, skipped }
      '
}

expect_eq "legacy pipeline was correct only while xargs used a single batch" \
  "78 0 0 3" "$(legacy_measure "$sf_dir" "")"

legacy_batched=$(legacy_measure "$sf_dir" "-n 1")
expect_ne "legacy pipeline breaks once xargs splits into batches (this is E1)" \
  "78 0 0 3" "$legacy_batched"

# 12 newline-less tuples weld together at every batch boundary: 4*12 numbers minus the 11 glued
# junctions = 37 fields. The junction is where "Skipped: 0" of one batch swallows the leading
# digit of the next batch's tests total -- the "Skipped=047 0 0 0" artefact in the TASK-19 spec.
expect_eq "legacy output is one glued blob of 37 fields, not 4 numbers" \
  "37" "$(printf '%s' "$legacy_batched" | wc -w | tr -d ' ')"
case " $legacy_batched " in
  *" 07 "*) pass "legacy: batch boundary welded 0 onto the next tests total (the 047 artefact)" ;;
  *) die_case "legacy: expected a welded token in [$legacy_batched]" ;;
esac

# What the old caller saw after `read`: the first five words of "<files> <sums>", i.e. a wildly
# under-counted suite (5 tests instead of 78) that still sailed through the gate.
legacy_reports=""
legacy_tests=""
legacy_failures=""
legacy_errors=""
legacy_skipped=""
IFS=' ' read -r legacy_reports legacy_tests legacy_failures legacy_errors legacy_skipped <<EOF
12 $legacy_batched
EOF
expect_eq "legacy: reports field still parsed" "12" "$legacy_reports"
expect_eq "legacy: tests field was silently wrong" "5" "$legacy_tests"
expect_eq "fixed: the same caller read now sees the true total" \
  "78" "$(measure "$sf_dir" | awk '{print $2}')"

# ---------------------------------------------------------------------------
# 4) End-to-end: --update then check against a throwaway repo root, fully batched.
# ---------------------------------------------------------------------------
e2e_root="$tmp_root/e2e"
mkdir -p "$e2e_root/scripts" "$e2e_root/target/surefire-reports" "$e2e_root/target/failsafe-reports"
cp "$sf_dir"/*.txt "$e2e_root/target/surefire-reports/"
cp "$fa_dir"/*.txt "$e2e_root/target/failsafe-reports/"
cp "$gate_path" "$e2e_root/scripts/check-test-baseline.sh"

# The gate now reads the pom's default includes as the scope source of truth, so the fixture repo
# root needs its own pom.xml in the shipped shape (single-line <include>/<exclude> elements).
mk_scope_pom() { # <out>
  {
    printf '<project><build><plugins>\n'
    printf '<plugin><artifactId>maven-surefire-plugin</artifactId><configuration>\n'
    printf '<includes><include>**/*Test.java</include><include>**/*Tests.java</include></includes>\n'
    printf '<excludes><exclude>**/*IT.java</exclude></excludes>\n'
    printf '</configuration></plugin>\n'
    printf '<plugin><artifactId>maven-failsafe-plugin</artifactId><configuration>\n'
    printf '<includes><include>**/*IT.java</include><include>**/*IntegrationTest.java</include></includes>\n'
    printf '</configuration></plugin>\n'
    printf '</plugins></build></project>\n'
  } >"$1"
}
mk_scope_pom "$e2e_root/pom.xml"

if BASELINE_XARGS_LIMIT=1 bash "$e2e_root/scripts/check-test-baseline.sh" --update >"$tmp_root/update.log" 2>&1; then
  pass "e2e: --update accepts a fully batched report set"
else
  die_case "e2e: --update failed: $(cat "$tmp_root/update.log")"
fi
baseline_copy="$e2e_root/scripts/test-baseline.txt"
expect_eq "e2e: --update wrote the summed surefire total" "78" \
  "$(sed -n 's/^surefire.tests=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the summed surefire skipped total" "3" \
  "$(sed -n 's/^surefire.skipped=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the summed failsafe total" "10" \
  "$(sed -n 's/^failsafe.tests=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the summed failsafe skipped total" "2" \
  "$(sed -n 's/^failsafe.skipped=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the scoped surefire report count" "12" \
  "$(sed -n 's/^surefire.reports=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the scoped failsafe report count" "4" \
  "$(sed -n 's/^failsafe.reports=//p' "$baseline_copy" | tr -d '[:space:]')"

check_rc=0
check_out=$(bash "$e2e_root/scripts/check-test-baseline.sh" 2>&1) || check_rc=$?
echo "$check_out" | sed 's/^/    | /'
expect_eq "e2e: check right after --update exits 0" "0" "$check_rc"
case "$check_out" in
  *"Tests run=78"*"Skipped=3"*) pass "e2e: check prints the batched totals verbatim" ;;
  *) die_case "e2e: expected Tests run=78 and Skipped=3 in: $check_out" ;;
esac
# Same number the gate prints, re-parsed on its own so a truncated total cannot hide in a
# substring match ("5" is a substring of nothing here, but the exact value is what matters).
parsed_sf_tests=$(printf '%s\n' "$check_out" \
  | sed -n 's/.*Tests run=\([0-9][0-9]*\).*/\1/p' | head -1 | tr -d '[:space:]')
expect_eq "e2e: the surefire total the gate compared is the summed one" "78" "$parsed_sf_tests"

# The gate must still bite after the refactor: an inflated baseline has to go red.
sed 's/^surefire.tests=.*/surefire.tests=999/' "$baseline_copy" >"$baseline_copy.tmp" &&
  mv "$baseline_copy.tmp" "$baseline_copy"
inflated_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || inflated_rc=$?
if [ "$inflated_rc" -ne 0 ]; then
  pass "e2e: baseline above the measured total still fails the gate (rc=$inflated_rc)"
else
  die_case "e2e: threshold judgement stopped working (rc=0 with a tests=999 baseline)"
fi

# And a silently skipped class must still be caught (restore tests, drop the skipped allowance).
sed -e 's/^surefire.tests=.*/surefire.tests=78/' -e 's/^surefire.skipped=.*/surefire.skipped=0/' \
  "$baseline_copy" >"$baseline_copy.tmp" && mv "$baseline_copy.tmp" "$baseline_copy"
skipped_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || skipped_rc=$?
if [ "$skipped_rc" -ne 0 ]; then
  pass "e2e: skipped above baseline still fails the gate (rc=$skipped_rc)"
else
  die_case "e2e: skipped-over-baseline went unnoticed"
fi

# ---- 默认口径的端到端行为（「残留报告抬高基线计数」缺口的回归锁）----
# Put the ledger back into the state --update wrote (tests=78, skipped=3) and confirm green.
sed -e 's/^surefire.tests=.*/surefire.tests=78/' -e 's/^surefire.skipped=.*/surefire.skipped=3/' \
  "$baseline_copy" >"$baseline_copy.tmp" && mv "$baseline_copy.tmp" "$baseline_copy"
green_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || green_rc=$?
expect_eq "e2e: scope-aware gate is green on the clean fixture" "0" "$green_rc"

# The reports count is now judged too: a ledger promising more report files than the default run
# produced must go red (a whole test class report went missing).
sed 's/^surefire.reports=.*/surefire.reports=13/' "$baseline_copy" >"$baseline_copy.tmp" &&
  mv "$baseline_copy.tmp" "$baseline_copy"
reports_rc=0
reports_out=$(bash "$e2e_root/scripts/check-test-baseline.sh" 2>&1) || reports_rc=$?
expect_ne "e2e: a reports baseline above the measured count fails the gate" "0" "$reports_rc"
case "$reports_out" in
  *"疑似丢测试类"*) pass "e2e: the missing-report reason is printed" ;;
  *) die_case "e2e: expected a missing-report reason in: $reports_out" ;;
esac
sed 's/^surefire.reports=.*/surefire.reports=12/' "$baseline_copy" >"$baseline_copy.tmp" &&
  mv "$baseline_copy.tmp" "$baseline_copy"

# Drop the opt-in benchmark residual: scoped counting must stay at 78, and the gate must go red
# while naming the residual (the old gate silently passed with 85 tests over 13 reports).
write_report "$e2e_root/target/surefire-reports" "fixture.sf.99Benchmark" 7 0 0 0 "no"
foreign_rc=0
foreign_out=$(bash "$e2e_root/scripts/check-test-baseline.sh" 2>&1) || foreign_rc=$?
expect_ne "e2e: a residual report makes the gate go red" "0" "$foreign_rc"
case "$foreign_out" in
  *"fixture.sf.99Benchmark.txt"*) pass "e2e: the residual is named in the output" ;;
  *) die_case "e2e: residual file not named in: $foreign_out" ;;
esac
case "$foreign_out" in
  *"Tests run=78"*) pass "e2e: the scoped total still ignores the residual" ;;
  *) die_case "e2e: expected scoped Tests run=78 in: $foreign_out" ;;
esac
case "$foreign_out" in
  *"Tests run=85"*) die_case "e2e: the residual leaked into the counted total" ;;
  *) pass "e2e: the residual did not leak into the counted total" ;;
esac

# --update must refuse to write a ledger while the residual is present ...
update_rc=0
update_out=$(bash "$e2e_root/scripts/check-test-baseline.sh" --update 2>&1) || update_rc=$?
expect_ne "e2e: --update refuses to write while the residual is present" "0" "$update_rc"
case "$update_out" in
  *"拒绝写入基线"*) pass "e2e: --update prints its refusal reason" ;;
  *) die_case "e2e: expected a refusal message in: $update_out" ;;
esac
expect_eq "e2e: the refused --update left the ledger untouched" "78" \
  "$(sed -n 's/^surefire.tests=//p' "$baseline_copy" | tr -d '[:space:]')"
# 单 suite 残留（2026-09-28 P3 回归锁）：名单挂在它自己的 suite 分组下，干净的 suite
# 不许出现空分组标题（否则空标题会被误读成那一组也被点名）。
case "$update_out" in
  *"surefire-reports："*) pass "e2e: single-suite refusal groups the residual under its own suite" ;;
  *) die_case "e2e: expected a surefire-reports group header in: $update_out" ;;
esac
case "$update_out" in
  *"failsafe-reports："*) die_case "e2e: single-suite refusal must not print an empty failsafe group" ;;
  *) pass "e2e: single-suite refusal prints no group header for the clean suite" ;;
esac

# ... and removing just that one file must restore green without any mvn clean.
rm -f "$e2e_root/target/surefire-reports/fixture.sf.99Benchmark.txt"
restored_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || restored_rc=$?
expect_eq "e2e: removing just the residual restores green (no clean needed)" "0" "$restored_rc"

# 双 suite 同时残留（2026-09-28 P3 回归锁，主 agent 台 G/H 复现）：--update 的拒写名单必须
# 一次列全两个 suite 的分组与文件名 —— 旧实现 if/elif 只打印第一个非空 suite，failsafe 那份
# 要等用户删完 surefire 残留再跑一轮才可见。判定语义不变：仍拒绝、台账不动、处置提示一句。
write_report "$e2e_root/target/surefire-reports" "fixture.sf.99Benchmark" 7 0 0 0 "no"
write_report "$e2e_root/target/failsafe-reports" "fixture.fa.99VerifyBenchmark" 3 0 0 0 "no"
dual_rc=0
dual_out=$(bash "$e2e_root/scripts/check-test-baseline.sh" --update 2>&1) || dual_rc=$?
expect_ne "e2e: dual-suite residual still refuses to write" "0" "$dual_rc"
case "$dual_out" in
  *"fixture.sf.99Benchmark.txt"*) pass "e2e: dual refusal names the surefire residual" ;;
  *) die_case "e2e: surefire residual not named in: $dual_out" ;;
esac
case "$dual_out" in
  *"fixture.fa.99VerifyBenchmark.txt"*) pass "e2e: dual refusal names the failsafe residual in the same run" ;;
  *) die_case "e2e: failsafe residual not named in: $dual_out" ;;
esac
case "$dual_out" in
  *"surefire-reports："*"failsafe-reports："*) pass "e2e: dual refusal groups both suites, surefire first" ;;
  *) die_case "e2e: expected both suite groups in: $dual_out" ;;
esac
dual_hints=$(printf '%s\n' "$dual_out" | LC_ALL=C grep -c '处置：')
expect_eq "e2e: the disposal hint is printed once, not per group" "1" "$dual_hints"
expect_eq "e2e: dual refusal left the surefire ledger untouched" "78" \
  "$(sed -n 's/^surefire.tests=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: dual refusal left the failsafe ledger untouched" "10" \
  "$(sed -n 's/^failsafe.tests=//p' "$baseline_copy" | tr -d '[:space:]')"
rm -f "$e2e_root/target/surefire-reports/fixture.sf.99Benchmark.txt" \
  "$e2e_root/target/failsafe-reports/fixture.fa.99VerifyBenchmark.txt"
dual_clean_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || dual_clean_rc=$?
expect_eq "e2e: removing both residuals restores green for the later scenarios" "0" "$dual_clean_rc"

# An unparseable pom must fail closed instead of falling back to counting every report.
cp -r "$e2e_root" "$tmp_root/e2e-badpom"
printf '<project/>\n' >"$tmp_root/e2e-badpom/pom.xml"
badpom_rc=0
badpom_out=$(bash "$tmp_root/e2e-badpom/scripts/check-test-baseline.sh" 2>&1) || badpom_rc=$?
expect_ne "e2e: a pom without the plugin blocks fails closed" "0" "$badpom_rc"
case "$badpom_out" in
  *"拒绝裁决"*) pass "e2e: the fail-closed reason is printed" ;;
  *) die_case "e2e: expected a refusal-to-judge message in: $badpom_out" ;;
esac

# A CRLF pom (core.autocrlf=true checkouts) must parse identically.
mk_crlf() { tr -d '\r' <"$1" | sed -e 's/$/\r/' >"$2"; }
mk_crlf "$e2e_root/pom.xml" "$tmp_root/pom-crlf.xml"
cp "$tmp_root/pom-crlf.xml" "$e2e_root/pom.xml"
crlf_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || crlf_rc=$?
expect_eq "e2e: a CRLF pom parses the same (autocrlf regression lock)" "0" "$crlf_rc"

# ---------------------------------------------------------------------------
# 4b) source-revision dirty detection: 5 states (F-G2 / L-10)
# ---------------------------------------------------------------------------
# 场景 1：非 Git 目录执行 --update，断言台账中包含 # source-revision=unknown
bash "$e2e_root/scripts/check-test-baseline.sh" --update >/dev/null 2>&1
case "$(cat "$e2e_root/scripts/test-baseline.txt")" in
  *"# source-revision=unknown"*) pass "revision state 1: non-git repo writes unknown source-revision" ;;
  *) die_case "revision state 1: non-git repo missing unknown source-revision in: $(cat "$e2e_root/scripts/test-baseline.txt")" ;;
esac

# 虚拟 Git 仓库夹具
git_fixture_root="$tmp_root/e2e-git"
mkdir -p "$git_fixture_root/scripts" "$git_fixture_root/target/surefire-reports" "$git_fixture_root/target/failsafe-reports"
cp "$sf_dir"/*.txt "$git_fixture_root/target/surefire-reports/"
cp "$fa_dir"/*.txt "$git_fixture_root/target/failsafe-reports/"
cp "$gate_path" "$git_fixture_root/scripts/check-test-baseline.sh"
mk_scope_pom "$git_fixture_root/pom.xml"
echo "tracked fixture content" > "$git_fixture_root/tracked-file.txt"

git -C "$git_fixture_root" init -q
git -C "$git_fixture_root" config user.name "Test Runner"
git -C "$git_fixture_root" config user.email "test@example.com"
git -C "$git_fixture_root" config core.autocrlf false
git -C "$git_fixture_root" add pom.xml scripts/ target/ tracked-file.txt
git -C "$git_fixture_root" commit -q -m "initial fixture commit"
expected_clean_hash=$(git -C "$git_fixture_root" rev-parse --short HEAD)

# 场景 2：纯净 Git 仓库执行，断言输出精确等于短哈希，不含 -dirty
bash "$git_fixture_root/scripts/check-test-baseline.sh" --update >/dev/null 2>&1
clean_rev=$(sed -n 's/^# source-revision=//p' "$git_fixture_root/scripts/test-baseline.txt" | tr -d '[:space:]')
expect_eq "revision state 2: clean git repo revision equals short hash" "$expected_clean_hash" "$clean_rev"
case "$clean_rev" in
  *-dirty*) die_case "revision state 2: clean repo revision must not contain -dirty" ;;
  *) pass "revision state 2: clean repo revision does not contain -dirty" ;;
esac

# 场景 3：工作区脏树（修改已跟踪文件）执行，断言输出末尾精确追加 -dirty
echo "dirty worktree change" >> "$git_fixture_root/tracked-file.txt"
bash "$git_fixture_root/scripts/check-test-baseline.sh" --update >/dev/null 2>&1
worktree_dirty_rev=$(sed -n 's/^# source-revision=//p' "$git_fixture_root/scripts/test-baseline.txt" | tr -d '[:space:]')
expect_eq "revision state 3: modified worktree revision appends -dirty" "${expected_clean_hash}-dirty" "$worktree_dirty_rev"

# 场景 4：暂存区脏树（git add 修改）执行，断言输出末尾精确追加 -dirty
git -C "$git_fixture_root" add "$git_fixture_root/tracked-file.txt"
bash "$git_fixture_root/scripts/check-test-baseline.sh" --update >/dev/null 2>&1
staged_dirty_rev=$(sed -n 's/^# source-revision=//p' "$git_fixture_root/scripts/test-baseline.txt" | tr -d '[:space:]')
expect_eq "revision state 4: staged modification revision appends -dirty" "${expected_clean_hash}-dirty" "$staged_dirty_rev"

# 场景 5：纯未跟踪文件对照组（新增 untracked 文件）执行，断言输出不含 -dirty（与 Git 官方规范一致）
git -C "$git_fixture_root" reset --hard HEAD >/dev/null 2>&1
touch "$git_fixture_root/untracked.txt"
bash "$git_fixture_root/scripts/check-test-baseline.sh" --update >/dev/null 2>&1
untracked_rev=$(sed -n 's/^# source-revision=//p' "$git_fixture_root/scripts/test-baseline.txt" | tr -d '[:space:]')
expect_eq "revision state 5: untracked file alone does not append -dirty" "$expected_clean_hash" "$untracked_rev"
case "$untracked_rev" in
  *-dirty*) die_case "revision state 5: untracked file must not cause -dirty tag" ;;
  *) pass "revision state 5: untracked file does not contain -dirty" ;;
esac

# ---------------------------------------------------------------------------
# 5) Sourcing the gate must not write anything into the repository.
# ---------------------------------------------------------------------------
if [ -e "$scripts_dir/tests/test-baseline.txt" ]; then
  die_case "sourcing the gate leaked its paths: a baseline appeared under scripts/tests/"
else
  pass "sourcing the gate wrote nothing into the repository"
fi
if [ ! -f "$scripts_dir/test-baseline.txt" ]; then
  die_case "the committed baseline file vanished"
else
  expect_line "the committed baseline keeps its entries" "$scripts_dir/test-baseline.txt" "surefire.tests"
  expect_line "the committed baseline keeps its entries" "$scripts_dir/test-baseline.txt" "failsafe.skipped"
  expect_line "the committed baseline keeps its reports keys" "$scripts_dir/test-baseline.txt" "surefire.reports"
  expect_line "the committed baseline keeps its reports keys" "$scripts_dir/test-baseline.txt" "failsafe.reports"
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "SELFTEST PASSED: $checks assertions"
  exit 0
fi
echo "SELFTEST FAILED: $failures of $checks assertions" >&2
exit 1
