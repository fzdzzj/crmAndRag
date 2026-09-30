#!/usr/bin/env bash
# 回归基线门禁：surefire / failsafe 双口径。
#
# 为什么要脚本化：原先两个阈值是手抄在 ci.yml 里的字面量，注释里的推导链
# （639 → 644 → 645 → 649 → …）一旦和常量对不上就没人发现，而且旧实现只对
# `Tests run:` 求和，从不看 Failures / Errors / Skipped —— 整类被跳过的用例
# 照样按"跑过了"计入基线，门禁会静默放行。
#
# 默认口径（修「残留报告抬高基线计数」缺口，2026-09-28）：
#   基线衡量的对象是 pom 默认 includes 定义的默认运行（surefire：**/*Test.java、**/*Tests.java
#   且排除 **/*IT.java；failsafe：**/*IT.java、**/*IntegrationTest.java —— 词干读自 pom，不在此处复制）。
#   目录里不在该口径内的报告（典型：opt-in 基准，如
#   KB_SCOPE_AUTH_MEASURE=1 mvn -Dtest=...Benchmark test 留下的 *Benchmark 报告）是「口径外残留」：
#     · check：只按默认口径计数，且残留存在即判红并逐份列名 —— 否则残留会顶替真实丢测试
#       （旧表现：台账 153/845，target 多一份 opt-in 报告即 154/846，Tests run 下界照过 → 静默绿）。
#     · --update：残留存在即拒绝写入 —— 台账只能来自一次干净的默认运行。
#   为什么不选「先清 target/*-reports 再跑」：默认序列不跑 [it] 时 failsafe 报告本就沿用上一轮
#   （见 merge-gate.sh 语义边界），清目录会连这条既有语义一起清；按 mtime 判「本轮」同样分不开
#   「上一轮的合法 failsafe 报告」与「残留」，且两者都保护不到直接调用本脚本的路径（CI 阶段3/手工）。
#   残留处置不需要 clean 整个 target/：删掉列出的 .txt（连同同名 .xml）即可。
#
# 用法：
#   bash scripts/check-test-baseline.sh            # 用已入库的基线裁决当前 target/ 报告（默认口径）
#   bash scripts/check-test-baseline.sh --update   # 用当前报告重写基线（跑完 mvn clean verify 之后）
#
# 基线数字只允许由 --update 从一次真实运行写入，不要手改。
# 历史阶梯见 `git log -p -- scripts/test-baseline.txt` 与 `git log -p -- .github/workflows/ci.yml`。
set -uo pipefail

# ${BASH_SOURCE[0]} 而非 $0：本脚本被 source（自测的 BASELINE_LIB_ONLY=1 用法）时 $0 是调用者，
# 会把 repo_root 解析到调用者目录，口径函数就会去读错的 pom。
script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
baseline_file="$script_dir/test-baseline.txt"
reports_root="$repo_root/target"
# 默认口径的真相源：pom 的 surefire/failsafe 插件块（由 load_scope_patterns 读取）。
baseline_pom="$repo_root/pom.xml"
# 上述四组词干由 load_scope_patterns 填充；先初始化，避免 set -u 下间接展开报错。
SUREFIRE_INCLUDES="" SUREFIRE_EXCLUDES="" FAILSAFE_INCLUDES="" FAILSAFE_EXCLUDES=""
# 单趟分区（partition_reports）与条数统计（count_nul_entries）的结果变量，同样先初始化。
SCOPE_FOREIGN="" SCOPE_IN_COUNT=0 NUL_COUNT=0

MODE="check"
if [ "${1:-}" = "--update" ]; then
  MODE="update"
fi

# aggregate_tuples -- sum "tests failures errors skipped" tuples coming in one per line.
#
# Why this exists (E1): xargs splits the file list into several awk processes as soon as the
# argument list exceeds the OS batch limit, and every one of those processes prints its own
# END tuple. Without this second pass the caller sees a multi-line (or, with a newline-less
# printf, a glued-together) tuple and `read` parses garbage like "Skipped=047 0 0 0".
# Aggregating here keeps measure() a single-line contract no matter how many batches ran.
aggregate_tuples() {
  awk '{tests += $1; failures += $2; errors += $3; skipped += $4}
       END {printf "%d %d %d %d", tests, failures, errors, skipped}'
}

# 每个 .txt 只取第一条 Tests run 行，避免同一文件多行时重复计数。
# 独立成变量（而不是在两条生产者路径里各写一份）：默认口径过滤与不过滤必须共用同一份求和程序，
# 否则将来只改一条路径，口径就分叉了。
TESTS_RUN_AWK='
  /^Tests run:/ && !seen[FILENAME]++ {
    tests += $2; failures += $4; errors += $6; skipped += $8
  }
  END { printf "%d %d %d %d\n", tests, failures, errors, skipped }
'

# sum_report_files —— stdin 收 NUL 分隔的报告路径，stdout 输出 "tests failures errors skipped" 一行。
sum_report_files() {
  # Test-only hook: BASELINE_XARGS_LIMIT=<n> forces "xargs -0 -n <n>" so a small fixture
  # directory really is split across several awk batches. Unset = stock batching behaviour.
  local xargs_opts=""
  if [ -n "${BASELINE_XARGS_LIMIT:-}" ]; then
    case "$BASELINE_XARGS_LIMIT" in
      *[!0-9]*) echo "BASELINE_XARGS_LIMIT must be a positive integer" >&2; return 1 ;;
    esac
    xargs_opts="-n $BASELINE_XARGS_LIMIT"
  fi
  # shellcheck disable=SC2086
  xargs -0 $xargs_opts awk -F'[:,]' "$TESTS_RUN_AWK" | aggregate_tuples
}

# ---- 默认口径：pom 的 surefire/failsafe includes/excludes 是"哪些报告属于默认运行"的唯一真源 ----
# 只读 pom、不猜：<include>/<exclude> 形如 **/*Foo.java，这里归一为后缀词干 Foo；报告类简名命中任一
# include 词干、且不命中任一 exclude 词干 = 默认口径内。形态不认识（将来有人写出别的 glob 写法）一律
# 拒绝裁决而不是猜 —— 错误的过滤比不过滤更危险。注：<include> 元素须为单行形态，跨行写法会被判为
# 不可判定（与 scripts/tests/pmd-baseline-check.sh 对 <ruleset> 的单行要求同口径）。

err() { echo "::error::$*" >&2; }

# strip_xml_comments —— 剥 <!-- ... --> 注释，in_comment 状态在行间保持（跨行状态机，F-1b）。
# 旧实现逐行配对：开启行把行内剩余部分丢弃，但续行不带 <!--，被整行原样输出 —— 注释续行里写的
# <include>**/*Benchmark.java</include> 会被读成真配置，口径被 fail-open 污染（opt-in 基准残留被
# 静默当成口径内计数）。现在注释开启后，后续行哪怕整行都不带 <!-- 也整行当作注释体丢弃，直到 -->。
# 保留既有性质：不假设注释体内不含 '>'（只找 --> 收口）；单行内多段注释仍逐段剥净；输入行与
# 输出行一一对应（整行都在注释内的行输出空行）。scripts/tests/pmd-baseline-check.sh 里那份
# 逐行版同名副本不在本案写集，不许越界去修（已另行记录）。
strip_xml_comments() {
  awk '{
        if (in_comment) {
          j = index($0, "-->")
          if (j == 0) { print ""; next }
          s = substr($0, j + 3)
          in_comment = 0
          out = ""
        } else {
          s = $0; out = ""
        }
        while ((i = index(s, "<!--")) > 0) {
          out = out substr(s, 1, i - 1)
          s = substr(s, i + 4)
          j = index(s, "-->")
          if (j == 0) { in_comment = 1; s = ""; break }
          s = substr(s, j + 3)
        }
        print out s
      }'
}

# plugin_block <artifactId> —— 抽出 pom 里该插件块（已剥注释、已去 \r）。找不到输出空。
plugin_block() {
  tr -d '\r' <"$baseline_pom" 2>/dev/null | strip_xml_comments | awk -v id="$1" '
    seen { print; if (index($0, "</plugin>") > 0) exit; next }
    index($0, "<artifactId>" id "</artifactId>") > 0 {
      seen = 1; print
      if (index($0, "</plugin>") > 0) exit
    }
  '
}

# pattern_stem <pattern> —— 只接受 **/*<stem>.java 形态，输出词干；其余形态返回 1（宁红不猜）。
pattern_stem() {
  local p=$1 stem
  case "$p" in
    '**/*'*.java) stem="${p#'**/*'}"; stem="${stem%.java}" ;;
    *) return 1 ;;
  esac
  case "$stem" in
    '' | *['*?/']*) return 1 ;;
  esac
  printf '%s' "$stem"
}

# plugin_stems <block> <include|exclude> —— 该块内全部词干，每行一个；允许零条，模式形态不认识返回 1。
# 纯内建提取（F-1a）：等价于旧实现 `LC_ALL=C grep -o "<kind>[^<]*</kind>" | sed 剥标签`，但不再每次
# 派生 printf|grep|sed 管线（load_scope_patterns 一趟要调 4 次，那次是 clean 态仅次于按文件派生的 fork 大头）。
# 逐行扫描每个 <kind>…</kind>：内容不含 '<' 才算数（与 grep 的 [^<]* 同义，含 '<' 时跳过这个开标签、
# 从其后重扫，后面的开标签仍有机会命中）；空元素与旧实现一样跳过；开了没闭的行不会有任何匹配。
plugin_stems() {
  local block=$1 kind=$2 line rest after content stem
  local open="<${kind}>" close="</${kind}>"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    rest=$line
    while [ -n "$rest" ]; do
      after="${rest#*"$open"}"
      if [ "$after" = "$rest" ]; then break; fi
      content="${after%%"$close"*}"
      if [ "$content" = "$after" ]; then break; fi
      case "$content" in
        *'<'*) rest=$after; continue ;;
        '') rest="${after#*"$close"}"; continue ;;
      esac
      stem=$(pattern_stem "$content") || return 1
      printf '%s\n' "$stem"
      rest="${after#*"$close"}"
    done
  done <<<"$block"
}

# load_scope_patterns —— 读 pom 填四组词干；失败原因打到 stderr，调用方必须判红退出（不许退回不过滤）。
load_scope_patterns() {
  local sf fa
  sf=$(plugin_block maven-surefire-plugin)
  fa=$(plugin_block maven-failsafe-plugin)
  if [ -z "$sf" ]; then
    err "pom 里找不到 maven-surefire-plugin 块（$baseline_pom）——默认口径不可判定，拒绝裁决"
    return 1
  fi
  if [ -z "$fa" ]; then
    err "pom 里找不到 maven-failsafe-plugin 块（$baseline_pom）——默认口径不可判定，拒绝裁决"
    return 1
  fi
  SUREFIRE_INCLUDES=$(plugin_stems "$sf" include) || { err "surefire 的 <include> 有不认识的形态（只支持 **/*Xxx.java）——拒绝裁决"; return 1; }
  SUREFIRE_EXCLUDES=$(plugin_stems "$sf" exclude) || { err "surefire 的 <exclude> 有不认识的形态（只支持 **/*Xxx.java）——拒绝裁决"; return 1; }
  FAILSAFE_INCLUDES=$(plugin_stems "$fa" include) || { err "failsafe 的 <include> 有不认识的形态（只支持 **/*Xxx.java）——拒绝裁决"; return 1; }
  FAILSAFE_EXCLUDES=$(plugin_stems "$fa" exclude) || { err "failsafe 的 <exclude> 有不认识的形态（只支持 **/*Xxx.java）——拒绝裁决"; return 1; }
  if [ -z "$SUREFIRE_INCLUDES" ] || [ -z "$FAILSAFE_INCLUDES" ]; then
    err "pom 的 surefire/failsafe <includes> 为空 —— 默认口径不可判定，拒绝裁决"
    return 1
  fi
  return 0
}

# ---- 单趟分区（F-1a）：词干每 suite 只展开一次，目录只走一趟，计数与求和复用同一份列表 ----
# 旧实现的代价：report_in_scope 对每份报告做 1-2 次 $(stems_of …) 命令替换（每次一个子 shell
# fork），且每个 suite 走 3 趟（out_of_scope_reports 1 趟 + measure 内数数/求和各 1 趟）——
# 153 份报告的 clean 态实测 1m14s（sys 时间占大头，瓶颈是进程派生不是计算）。现在词干从四组
# 全局变量直接展开进局部变量，文件循环体内零命令替换、零 fork。

# partition_reports <dir> <suite> <in-nul-file> —— 一趟遍历同时产出：
#   · 口径内 .txt 路径（NUL 分隔）写入 <in-nul-file>（调用前先截断）；
#   · 口径外基名（含 .txt，换行分隔，已带结尾换行）写入全局 SCOPE_FOREIGN（每次调用先清空）；
#   · 口径内份数写入全局 SCOPE_IN_COUNT（每次调用先清零）。
# 词干取自 load_scope_patterns 填好的四组全局变量（每 suite 一次展开，不在文件循环里）；
# 分类判定与 report_in_scope 同一副代码（name_matches_stems）。目录缺失返回 1；suite 不认识返回 1。
partition_reports() {
  local dir=$1 suite=$2 out_file=$3
  local inc_stems="" exc_stems=""
  case "$suite" in
    surefire) inc_stems=$SUREFIRE_INCLUDES exc_stems=$SUREFIRE_EXCLUDES ;;
    failsafe) inc_stems=$FAILSAFE_INCLUDES exc_stems=$FAILSAFE_EXCLUDES ;;
    *) return 1 ;;
  esac
  SCOPE_FOREIGN=""
  SCOPE_IN_COUNT=0
  if [ ! -d "$dir" ]; then
    return 1
  fi
  : >"$out_file" || return 1
  local f base simple
  while IFS= read -r -d '' f; do
    base="${f##*/}"
    simple="${base%.txt}"
    simple="${simple##*.}"
    if name_matches_stems "$simple" "$inc_stems" && ! name_matches_stems "$simple" "$exc_stems"; then
      printf '%s\0' "$f" >>"$out_file"
      SCOPE_IN_COUNT=$((SCOPE_IN_COUNT + 1))
    else
      SCOPE_FOREIGN+="$base"$'\n'
    fi
  done < <(find "$dir" -maxdepth 1 -name '*.txt' -type f -print0)
  return 0
}

# count_nul_entries <nul-分隔文件> —— 条数写入全局 NUL_COUNT。纯内建循环，不为计数多走一趟。
count_nul_entries() {
  local f n=0
  while IFS= read -r -d '' f; do
    n=$((n + 1))
  done <"$1"
  NUL_COUNT=$n
}

# measure_list <nul-列表文件> <份数> —— 对 partition_reports / find 已产出的同一份列表求和，
# 输出 "<reports> <tests> <failures> <errors> <skipped>"。计数直接复用分区趟里数好的份数
# （不再为"数个数"对目录再走一趟）；数字校验与 measure 的既有契约一致（不是恰好四个整数即失败）。
measure_list() {
  local list_file=$1 count=$2 sums
  case "$count" in
    '' | *[!0-9]*) return 1 ;;
  esac
  if [ "$count" -eq 0 ]; then
    return 1
  fi
  sums=$(sum_report_files <"$list_file") || return 1
  # measure() must end up with exactly four integers; anything else means the reports could not
  # be read and must not be silently reported as "0 tests, 0 failures".
  # shellcheck disable=SC2086
  set -- $sums
  if [ "$#" -ne 4 ]; then
    return 1
  fi
  local field
  for field in "$@"; do
    case "$field" in
      '' | *[!0-9]*) return 1 ;;
    esac
  done
  printf '%s %s %s %s %s' "$count" "$1" "$2" "$3" "$4"
}

# name_matches_stems <类简名> <词干（多行）> —— 命中任一词干后缀即 0。
name_matches_stems() {
  local name=$1 stem
  while IFS= read -r stem; do
    [ -n "$stem" ] || continue
    case "$name" in *"$stem") return 0 ;; esac
  done <<<"$2"
  return 1
}

# report_in_scope <suite> <报告基名（去 .txt）> —— 0 = 默认口径内。
# 词干直接从四组全局变量展开（不再对每个名字做 $(stems_of …) 子 shell 派生 —— F-1a 根因之一）。
report_in_scope() {
  local suite=$1 simple=$2 inc_stems="" exc_stems=""
  case "$suite" in
    surefire) inc_stems=$SUREFIRE_INCLUDES exc_stems=$SUREFIRE_EXCLUDES ;;
    failsafe) inc_stems=$FAILSAFE_INCLUDES exc_stems=$FAILSAFE_EXCLUDES ;;
    *) return 1 ;;
  esac
  name_matches_stems "$simple" "$inc_stems" || return 1
  if name_matches_stems "$simple" "$exc_stems"; then
    return 1
  fi
  return 0
}

# out_of_scope_reports <dir> <suite> —— 口径外 .txt 的基名，每行一个（只用于列名，不参与计数）。
# 生产路径（check/--update 两态）已改走 partition_reports：口径外名单与口径内列表出自同一趟遍历，
# 本包装现在只服务于低层自测（scripts/tests/check-test-baseline-selftest.sh）的断言。
out_of_scope_reports() {
  local dir=$1 suite=$2 tmp
  [ -d "$dir" ] || return 0
  tmp=$(mktemp) || return 1
  partition_reports "$dir" "$suite" "$tmp"
  rm -f "$tmp"
  printf '%s' "$SCOPE_FOREIGN"
  return 0
}

# measure <report-dir> [suite] -> "<reports> <tests> <failures> <errors> <skipped>"
# suite 给定时先单趟分区（口径内列表 + 口径外名单 + 份数一次拿全），再对同一份列表求和；
# 省略时统计目录内全部 .txt —— 后者只留给聚合函数的低层自测（scripts/tests/check-test-baseline-selftest.sh
# 的夹具），门禁的 check/--update 两条路径都必须传 suite，不许退回"不过滤"。
measure() {
  local dir="$1" suite="${2:-}"
  if [ ! -d "$dir" ]; then
    return 1
  fi
  local list_file sums files out=""
  if ! list_file=$(mktemp); then
    return 1
  fi
  local ok=1
  if [ -n "$suite" ]; then
    if partition_reports "$dir" "$suite" "$list_file"; then
      files=$SCOPE_IN_COUNT
    else
      ok=0
    fi
  else
    if find "$dir" -maxdepth 1 -name '*.txt' -type f -print0 >"$list_file"; then
      count_nul_entries "$list_file"
      files=$NUL_COUNT
    else
      ok=0
    fi
  fi
  if [ "$ok" -eq 1 ] && [ "$files" -eq 0 ]; then
    ok=0
  fi
  if [ "$ok" -eq 1 ]; then
    sums=$(sum_report_files <"$list_file")
    # shellcheck disable=SC2086
    set -- $sums
    if [ "$#" -eq 4 ]; then
      local field good=1
      for field in "$@"; do
        case "$field" in
          '' | *[!0-9]*) good=0 ;;
        esac
      done
      if [ "$good" -eq 1 ]; then
        out="$files $1 $2 $3 $4"
      fi
    fi
  fi
  rm -f "$list_file"
  if [ -n "$out" ]; then
    printf '%s' "$out"
    return 0
  fi
  return 1
}

read_baseline() {
  local key="$1" line value=""
  if [ ! -f "$baseline_file" ]; then
    return 1
  fi
  # 纯内建读法（F-1a）：旧实现每键 sed|tail|tr 三个 fork，一趟 check 调 6 次 ≈ 18 个派生。
  # 语义不变：取该键的最后一次命中、剔除值里全部空白、读不到非空值即失败。
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      "$key="*) value="${line#*=}"
        value="${value//[[:space:]]/}" ;;
    esac
  done <"$baseline_file"
  if [ -z "$value" ]; then
    return 1
  fi
  printf '%s' "$value"
}

get_source_revision() {
  local target_root="${1:-$repo_root}"
  local rev
  if rev=$(git -C "$target_root" rev-parse --short HEAD 2>/dev/null); then
    if ! git -C "$target_root" diff-index --quiet HEAD -- 2>/dev/null; then
      rev="${rev}-dirty"
    fi
  else
    rev="unknown"
  fi
  printf '%s' "$rev"
}

write_baseline() {
  local revision measured_at
  revision=$(get_source_revision)
  measured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  {
    echo "# 回归基线：由 scripts/check-test-baseline.sh --update 从一次真实运行写入，禁止手改。"
    echo "# source-revision=${revision}"
    echo "# measured-at=${measured_at}"
    echo "# reports=默认口径（pom 默认 includes）内的报告文件数，tests=Tests run 合计，skipped=Skipped 合计（上限，不是下限）"
    echo "surefire.reports=${sf_reports}"
    echo "surefire.tests=${sf_tests}"
    echo "surefire.skipped=${sf_skipped}"
    echo "failsafe.reports=${fa_reports}"
    echo "failsafe.tests=${fa_tests}"
    echo "failsafe.skipped=${fa_skipped}"
  } > "$baseline_file"
  echo "已写入基线：${baseline_file}"
  cat "$baseline_file"
}

fail() {
  echo "::error::$*" >&2
  rc=1
}

# Self-test hook: `BASELINE_LIB_ONLY=1 . scripts/check-test-baseline.sh` loads the functions above
# without running the gate below (used by scripts/tests/check-test-baseline-selftest.sh).
# When executed normally the variable is unset, and a bare `return` outside a function is simply
# ignored by bash, so the gate always runs for a real invocation.
if [ "${BASELINE_LIB_ONLY:-}" = "1" ]; then
  return 0 2>/dev/null
fi

rc=0

# 默认口径是"哪些报告属于默认运行"的唯一真源：读不到就拒绝裁决（判红），不许退回"不过滤"——
# 那正是残留报告能抬高计数、顶替丢测试的老缺口。
if ! load_scope_patterns; then
  echo "::error::默认口径（pom 的 surefire/failsafe includes）不可判定，拒绝裁决" >&2
  exit 1
fi

if [ "$MODE" = "update" ]; then
  # 口径外残留（opt-in 基准等）存在时拒绝写入：台账必须来自一次干净的默认运行。
  # 每个 suite 一趟单遍分区：口径外名单与口径内列表（供 measure_list）出自同一趟遍历（F-1a）；
  # 两个 suite 的残留检查先于任何度量；拒写名单按 suite 分组一次列全，处置提示只给一句。
  sf_list_file=$(mktemp) || { echo "::error::无法创建分区列表临时文件，拒绝写入基线" >&2; exit 1; }
  fa_list_file=$(mktemp) || { rm -f "$sf_list_file"; echo "::error::无法创建分区列表临时文件，拒绝写入基线" >&2; exit 1; }
  sf_count=0 fa_count=0 sf_foreign="" fa_foreign=""
  if partition_reports "$reports_root/surefire-reports" surefire "$sf_list_file"; then
    sf_count=$SCOPE_IN_COUNT sf_foreign=$SCOPE_FOREIGN
  fi
  if partition_reports "$reports_root/failsafe-reports" failsafe "$fa_list_file"; then
    fa_count=$SCOPE_IN_COUNT fa_foreign=$SCOPE_FOREIGN
  fi
  if [ -n "$sf_foreign" ] || [ -n "$fa_foreign" ]; then
    # 名单按 suite 分组一次列全（旧实现 if/elif 只挑第一个非空 suite，两个 suite 同时有残留时
    # 用户删完第一份还得再跑一轮才能看到另一份）；空 suite 不打分组标题。判定语义不变：
    # 任一 suite 有残留即拒绝写入，台账一个字节不动。
    {
      echo "::error::拒绝写入基线：以下目录存在不属于 pom 默认 includes 的报告（口径外残留，如 opt-in 基准）："
      if [ -n "$sf_foreign" ]; then
        echo "    surefire-reports："
        printf '%s' "$sf_foreign" | sed 's/^/        /'
      fi
      if [ -n "$fa_foreign" ]; then
        echo "    failsafe-reports："
        printf '%s' "$fa_foreign" | sed 's/^/        /'
      fi
      echo "        处置：删除上述 .txt（连同同名 .xml）后重跑本脚本；或 mvn -B -ntp clean verify 重跑默认序列后再 --update。"
    } >&2
    rm -f "$sf_list_file" "$fa_list_file"
    exit 1
  fi
  sf=$(measure_list "$sf_list_file" "$sf_count") || { rm -f "$sf_list_file" "$fa_list_file"; echo "::error::surefire 报告不可测，拒绝写入基线" >&2; exit 1; }
  fa=$(measure_list "$fa_list_file" "$fa_count") || { rm -f "$sf_list_file" "$fa_list_file"; echo "::error::failsafe 报告不可测，拒绝写入基线" >&2; exit 1; }
  rm -f "$sf_list_file" "$fa_list_file"
  read -r sf_reports sf_tests sf_failures sf_errors sf_skipped <<< "$sf"
  read -r fa_reports fa_tests fa_failures fa_errors fa_skipped <<< "$fa"
  if [ "$sf_failures" -ne 0 ] || [ "$sf_errors" -ne 0 ] || [ "$fa_failures" -ne 0 ] || [ "$fa_errors" -ne 0 ]; then
    echo "::error::当前运行含失败/错误用例（surefire F/E=${sf_failures}/${sf_errors}, failsafe F/E=${fa_failures}/${fa_errors}），拒绝把它们写进基线" >&2
    exit 1
  fi
  write_baseline
  exit 0
fi

# 单趟分区共用一份列表文件：partition_reports 每次调用先截断，两个 suite 顺序复用（F-1a）。
check_list_file=$(mktemp) || { echo "::error::无法创建分区列表临时文件，拒绝裁决" >&2; exit 1; }

for suite in surefire failsafe; do
  dir="$reports_root/${suite}-reports"
  label="${suite} 测试"

  measured=""
  if partition_reports "$dir" "$suite" "$check_list_file"; then
    # 同一趟分区的口径外名单：存在即判红并逐份列名（计数已按默认口径剔除它们）。
    if [ -n "$SCOPE_FOREIGN" ]; then
      fail "${label} 口径外残留：${dir} 下有 $(printf '%s' "$SCOPE_FOREIGN" | wc -l | tr -d ' ') 份不属于 pom 默认 includes 的报告（本次计数已按默认口径剔除它们，但残留会使 target/ 不能代表默认运行，必须处理）"
      printf '%s' "$SCOPE_FOREIGN" | sed 's/^/        /' >&2
      echo "        处置：删除上述 .txt（连同同名 .xml）后重跑本门禁；不必 clean 整个 target/（只有要重跑默认序列时才 mvn -B -ntp clean verify）。" >&2
    fi
    measured=$(measure_list "$check_list_file" "$SCOPE_IN_COUNT")
  fi
  if [ -z "$measured" ]; then
    fail "${label} 默认口径下无法度量：${dir} 缺失或没有属于 pom 默认 includes 的 .txt 报告（该类测试根本没执行，不允许静默通过）"
    continue
  fi
  read -r reports tests failures errors skipped <<< "$measured"

  base_reports=$(read_baseline "${suite}.reports") || base_reports=""
  base_tests=$(read_baseline "${suite}.tests") || base_tests=""
  base_skipped=$(read_baseline "${suite}.skipped") || base_skipped=""
  if [ -z "$base_reports" ] || [ -z "$base_tests" ] || [ -z "$base_skipped" ]; then
    fail "基线文件缺 ${suite} 条目：${baseline_file}（跑一次 --update 生成）"
    continue
  fi

  echo "${label}：报告=${reports}（默认口径），Tests run=${tests}，Failures=${failures}，Errors=${errors}，Skipped=${skipped}｜基线 reports>=${base_reports} tests>=${base_tests} skipped<=${base_skipped}"

  case "$reports$tests$base_reports$base_tests" in
    *[!0-9]*) fail "${label} 求和含非数字，无法裁决" ;;
  esac
  [ "$failures" -gt 0 ] && fail "${label} 有 ${failures} 条 Failures"
  [ "$errors" -gt 0 ] && fail "${label} 有 ${errors} 条 Errors"
  [ "$reports" -lt "$base_reports" ] && fail "${label} 报告数 ${reports} < 基线 ${base_reports}，疑似丢测试类（类被删除/改名，或未被默认 includes 覆盖）"
  [ "$tests" -lt "$base_tests" ] && fail "${label} 数 ${tests} < 基线 ${base_tests}，疑似丢测试"
  [ "$skipped" -gt "$base_skipped" ] && fail "${label} 跳过 ${skipped} > 基线 ${base_skipped}，有用例被悄悄跳过"
done

rm -f "$check_list_file"

if [ "$rc" -eq 0 ]; then
  echo "回归基线门禁通过（基线文件：$baseline_file）"
fi
exit "$rc"
