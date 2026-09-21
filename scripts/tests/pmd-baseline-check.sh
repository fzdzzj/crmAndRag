#!/usr/bin/env bash
# PMD 存量条数基线的"过期 / 只降不升"校验（wire-pmd-ruleset 组 3.3 + 3.4，拍板 Q1=A）。
#
# 要解决的问题：`mvn -B -ntp pmd:check` 只会因"实测条数 > pom 的 maxAllowedViolations"而红，
# 不会因"台账/阈值留着已修掉的冗余"而红；它也说不清"这个数是谁写的"。本脚本把两边都管住：
#   1) 登记值 > 实测值 → 非零，要求把数字下调（spec R3「基线只严不松」）；
#   2) 实测值 > 登记值 → 非零，报"新增违规超基线"；
#   3) pom 的 <maxAllowedViolations> 与台账不等 → 非零（同一口径出现两个数字即口径不再唯一）；
#   4) 台账只能由 --update 从一次真实运行写入，且**拒绝把数字改高**（想变高只能先把异味修掉）。
#
# 边界语义是实测出来的，不是猜的（2026-09-21 两次真实 `mvn -o -B -ntp pmd:check`，日志见
# work/mailbox/tasks/PMDC-P2/run-1-eq-registered.log 与 run-2-eq-plus-one.log）：
#   实测 1329 / 登记 1329 → exit 0，日志 "The build has not failed because 1329 violations are allowed"
#   实测 1329 / 登记 1328 → exit 1，日志 "PMD 7.9.0 has found 1329 violations"
# 即插件口径是"严格大于才失败"。推论（改代码的人必须知道）：修掉一条异味后 pmd:check **仍然绿**，
# 台账不会自己变红，只能靠本脚本的第 1 条判别把登记值拽下来 —— 这正是本脚本存在的理由。
#
# 用法：
#   bash scripts/tests/pmd-baseline-check.sh            # 用台账 + pom 裁决当前 target/pmd.xml
#   bash scripts/tests/pmd-baseline-check.sh --update   # 用一次真实运行的报告重写台账
#
# 报告由 `mvn -B -ntp pmd:check` 产出（target/pmd.xml）。本脚本不代跑 Maven，也**不改 pom**：
# 阈值那个数字由人按 --update 打印出的行号照抄一次（口径同 scripts/check-test-baseline.sh，
# 它也只写自己的台账）。pom 与台账一旦不等，check 模式立刻红，所以"抄错/只改一半"不会被放过。
# 为什么不让脚本代改 pom：msys 的 sed -i / awk 重写整份文件时会把 CRLF 抹成 LF（本机实测
# 528 行全部换行尾），被跟踪文件的行尾属于工作树约定，不该由校验脚本污染。
#
# 退出码：0=口径唯一且实测正好落在基线上；1=过期 / 超基线 / pom 与台账漂移 / 拒绝写入；
#         2=前置缺失（台账、报告、pom 接线，或 pmd 块内 <skip> 仍在位）。
#
# 行尾注意：台账与被读的 pom 都是**被跟踪文本**，本机 core.autocrlf=true 检出后是 CRLF，
# 末尾的 \r 会让 "1329" 变成 "1329\r" 从而永远不等。所有读取侧一律先 tr -d '\r'，且数字解析
# 只认 [0-9]+。回归锁见 scripts/tests/merge-gate-selftest.sh 场景 11。
set -uo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)

# Test-only hooks（口径同 spotbugs-exclude-staleness-check.sh 的 SPOTBUGS_* 与
# check-test-baseline.sh 的 BASELINE_*）：让自测能拿临时台账 / 临时报告 / 假 pom 证明
# 「坏输入真能变红」，而不必去改被跟踪的台账、pom 或 target/。正常调用三个变量都不存在。
ledger_file=${PMD_BASELINE_LEDGER:-"$script_dir/pmd-violation-baseline.txt"}
report_file=${PMD_REPORT:-"$repo_root/target/pmd.xml"}
pom_file=${PMD_POM_FILE:-"$repo_root/pom.xml"}

RULESET_LITERAL='<ruleset>src/main/resources/pmd-rules.xml</ruleset>'

err() { echo "::error::$*" >&2; }

# strip_xml_comments —— 按 <!-- ... --> 配对剥注释（状态机实现，不假设注释内不含 '>'）。
# 为什么这里也要：pom 的注释里就写着 "<skip>true</skip>" 这类历史说明（P1 与本包都写过），
# 不剥注释会把"已被注释掉的跳过"读成"仍然跳过"，反过来也能用一句注释骗过校验。
strip_xml_comments() {
  awk '{
        s = $0; out = ""
        while ((i = index(s, "<!--")) > 0) {
          out = out substr(s, 1, i - 1)
          s = substr(s, i + 4)
          j = index(s, "-->")
          if (j == 0) { s = ""; break }
          s = substr(s, j + 3)
        }
        print out s
      }'
}

# pmd_block <pom> —— 抽出 maven-pmd-plugin 那个 <plugin> 块（已剥注释、已去 \r）。
# 判定只盯这一块，别处将来给其它插件加 <skip> 不会误伤本校验。
pmd_block() {
  tr -d '\r' < "$1" | strip_xml_comments | awk '
    seen { print; if (index($0, "</plugin>") > 0) exit; next }
    index($0, "<artifactId>maven-pmd-plugin</artifactId>") > 0 { seen = 1; print }
  '
}

# count_in <固定串> —— 从 stdin 数出现次数（grep -o 逐次计数，不用 -c 的行数口径：
# 同一行写两遍也算两遍）。
count_in() { LC_ALL=C grep -o -F -- "$1" | wc -l | tr -d ' '; }

# read_ledger <key> -> 纯数字；读不到或非数字则失败
read_ledger() {
  local key=$1 value
  [ -f "$ledger_file" ] || return 1
  value=$(tr -d '\r' < "$ledger_file" | LC_ALL=C grep -v '^#' |
    LC_ALL=C sed -n "s/^${key}=//p" | tail -1 | tr -d '[:space:]')
  case "${value:-}" in
    '' | *[!0-9]*) return 1 ;;
  esac
  printf '%s' "$value"
}

# read_pom_reader -> 块内 <maxAllowedViolations> 的值；不是恰好一个就报错
read_pom_reader() {
  local block n value
  block=$(pmd_block "$pom_file")
  n=$(printf '%s\n' "$block" | count_in '<maxAllowedViolations>')
  if [ "${n:-0}" -ne 1 ]; then
    err "pom 的 pmd 块里 <maxAllowedViolations> 出现 ${n:-0} 次（应为 1 次）——条数读者必须唯一"
    return 1
  fi
  value=$(printf '%s\n' "$block" |
    LC_ALL=C sed -n 's:.*<maxAllowedViolations>\([0-9]*\)</maxAllowedViolations>.*:\1:p')
  case "${value:-}" in
    '' | *[!0-9]*) err "pom 的 <maxAllowedViolations> 不是纯数字，无法裁决"; return 1 ;;
  esac
  printf '%s' "$value"
}

# preconditions —— 判定链成立的最起码条件，不成立一律 exit 2，绝不静默通过
preconditions() {
  [ -f "$pom_file" ] || { err "缺少 pom.xml：$pom_file"; exit 2; }
  [ -f "$report_file" ] || { err "缺少 PMD 报告：$report_file —— 先跑一次 mvn -B -ntp pmd:check（本脚本不代跑 Maven）"; exit 2; }
  local block rulesets skips
  block=$(pmd_block "$pom_file")
  if [ -z "$block" ]; then
    err "pom 里找不到 maven-pmd-plugin 块（接线被摘掉了？）——无法裁决基线"
    exit 2
  fi
  rulesets=$(printf '%s\n' "$block" | count_in "$RULESET_LITERAL")
  if [ "${rulesets:-0}" -ne 1 ]; then
    err "pom 的 pmd 块未把 src/main/resources/pmd-rules.xml 接为 <ruleset>（命中 ${rulesets:-0} 次，应为 1 次）—— 当前报告量的不是仓库声明的那把尺子"
    exit 2
  fi
  skips=$(printf '%s\n' "$block" | count_in '<skip>')
  if [ "${skips:-0}" -ne 0 ]; then
    err "pmd 块内仍有 ${skips} 处生效的 <skip>（写在注释里的不计）—— PMD 被整体跳过时基线没有读者，拒绝裁决"
    exit 2
  fi
}

# measure_report -> "<violations> <files>"；报告里 0 个 <file> 时返回 2（根本没分析）
measure_report() {
  local violations files
  violations=$(tr -d '\r' < "$report_file" | count_in '<violation ')
  files=$(tr -d '\r' < "$report_file" | count_in '<file name=')
  case "$violations$files" in
    '' | *[!0-9]*) return 1 ;;
  esac
  [ "${files:-0}" -eq 0 ] && return 2
  printf '%s %s' "${violations:-0}" "${files:-0}"
}

# freshness_note —— 报告比源文件旧时只提醒、不判红：merge-gate 里 [pmd] 排在前面，报告必新鲜；
# 单独跑本脚本才可能踩到"拿上一轮旧报告裁决"的坑，所以把它说出来，不装作没看见。
freshness_note() {
  local newer
  newer=$(find "$repo_root/src/main/java" -name '*.java' -newer "$report_file" 2>/dev/null | head -1)
  [ -n "$newer" ] && echo "   NOTE: ${newer#"$repo_root"/} 比 ${report_file#"$repo_root"/} 更新 —— 报告可能过期，结论前先重跑 mvn -B -ntp pmd:check" >&2
  return 0
}

# write_ledger <violations> <files> —— 唯一会写文件的地方，只由真实报告驱动
write_ledger() {
  local v=$1 f=$2 revision measured_at
  revision=$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)
  measured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  cat > "$ledger_file" <<EOF
# PMD 存量条数基线：由 scripts/tests/pmd-baseline-check.sh --update 从一次真实 pmd:check 运行写入，禁止手改。
# source-revision=${revision}
# measured-at=${measured_at}
# 生成命令：mvn -B -ntp pmd:check（产出 target/pmd.xml）后 bash scripts/tests/pmd-baseline-check.sh --update
# pmd.violations = 声明尺子下的违规条数；pmd.files = 本轮被分析的源文件数（用来识破「根本没分析」）。
#
# 口径（必须连同数值一起读，否则会被拿去做错误的类比）：
#   1) 尺子 = src/main/resources/pmd-rules.xml 声明的 24 条规则（不是插件内置 quickstart）；
#      哪些条计入由 pom 该块的 failurePriority=4 决定，计入多少条算失败由同块的
#      maxAllowedViolations 决定 —— 后者是全仓唯一的条数读者，本台账是它的入库凭证与过期判据。
#   2) 只覆盖 src/main/java —— PMD 默认源目录不含 src/test/java。本数与 SpotBugs 的 12 条 High
#      基线**不是同一口径、不可类比**（那 12 条是一条条被豁免的具体缺陷，本数是一个条数上限）。
#   3) 86.4% 的条数集中在 3 条规则（OnlyOneReturn 732 / CommentSize 282 /
#      AvoidCatchingGenericException 134，共 1148/1329），所以短期内这道门禁只拦「新增第 4 种
#      异味」。按拍板 Q2 如实登记，不为好看回头调松规则集；收紧留给按规则分片的后续提案。
# 台账与 pom 的 <maxAllowedViolations> 一旦不等即红（口径不许有两个数字）。改数只允许由上面那条
# 命令写入，且只许下调不许上调 —— --update 在实测 > 登记时直接拒绝写入。
# 边界语义实测：实测 == 登记 → 绿；实测 == 登记 + 1 → 红（插件是「严格大于才失败」）。
# 因此修掉一条异味后 pmd:check 仍绿，必须靠 pmd-baseline-check.sh 把两处数字一起拽下来。
pmd.violations=${v}
pmd.files=${f}
EOF
  echo "已写入基线：${ledger_file#"$repo_root"/}（violations=${v}，files=${f}）"
}

MODE="${1:-check}"
case "$MODE" in
-h | --help)
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
  exit 0
  ;;
check | --update) ;;
*)
  err "未知参数 '$MODE'（可选：--update | --help）"
  exit 2
  ;;
esac

preconditions
freshness_note

measured=$(measure_report)
code=$?
if [ "$code" -eq 2 ]; then
  err "报告里没有任何 <file name=>：PMD 这轮一个源文件都没分析。拒绝把「没分析」读成「零违规」—— 请确认 pmd:check 真的执行过、${report_file#"$repo_root"/} 没被别处覆盖"
  exit 2
fi
if [ "$code" -ne 0 ] || [ -z "$measured" ]; then
  err "无法从报告里解析条数：${report_file#"$repo_root"/}（宁可判错，也不把解析失败当作达标）"
  exit 2
fi
read -r measured_violations measured_files <<< "$measured"

pom_reader=$(read_pom_reader) || exit 1

if [ "$MODE" = "--update" ]; then
  if [ -f "$ledger_file" ]; then
    old=$(read_ledger pmd.violations) || { err "台账缺 pmd.violations 条目：$ledger_file"; exit 2; }
    if [ "$measured_violations" -gt "$old" ]; then
      err "实测 ${measured_violations} > 登记 ${old}：只允许下调，拒绝把基线改高（改高等于用改数字让门禁变绿）。请先修掉新增异味，或按 pmd:check 报出的规则名逐条处理"
      exit 1
    fi
  else
    echo "台账不存在，按首次落基线处理：${ledger_file#"$repo_root"/}"
  fi
  write_ledger "$measured_violations" "$measured_files"
  pom_line=$(tr -d '\r' < "$pom_file" | LC_ALL=C grep -n '<maxAllowedViolations>[0-9]*</maxAllowedViolations>' | head -1 | LC_ALL=C sed -e 's/:.*<maxAllowedViolations>/ => /' -e 's#</maxAllowedViolations>.*##')
  echo "   台账已按实测写下 ${measured_violations}。pom 的条数读者必须等于这个数：${pom_line:-（pom 里没找到 <maxAllowedViolations> 数字行）}"
  if [ "$pom_reader" != "$measured_violations" ]; then
    echo "::warning::pom 当前是 ${pom_reader}，与实测 ${measured_violations} 不等 —— 请把上面那行的数字改成 ${measured_violations}，否则 check 模式与 [pmd-baseline] 会判漂移" >&2
  fi
  exit 0
fi

registered=$(read_ledger pmd.violations) || { err "台账缺 pmd.violations 条目或不可解析：$ledger_file（跑一次 --update 生成）"; exit 2; }

echo "报告：${report_file#"$repo_root"/} —— 实测 violations=${measured_violations}（files=${measured_files}）"
echo "登记：台账 pmd.violations=${registered}，pom <maxAllowedViolations>=${pom_reader}"

rc=0
if [ "$pom_reader" != "$registered" ]; then
  err "条数口径出现两个数字：pom=${pom_reader} 而台账=${registered}。唯一读者被复制成了两处，请把它们对齐到同一次真实运行的实测值"
  rc=1
fi
if [ "$measured_violations" -gt "$registered" ]; then
  err "新增违规超基线：实测 ${measured_violations} > 登记 ${registered}（多出 $((measured_violations - registered)) 条）。这些代码违反了仓库声明的 24 条规则，要改的是代码不是台账；--update 也会拒绝上调"
  rc=1
elif [ "$registered" -gt "$measured_violations" ]; then
  err "基线过期：登记 ${registered} > 实测 ${measured_violations}（有 $((registered - measured_violations)) 条异味已被修掉）。必须跑 bash scripts/tests/pmd-baseline-check.sh --update 把登记值下调（连同 pom 的 maxAllowedViolations），不许留着冗余"
  rc=1
fi
if [ "$rc" -eq 0 ]; then
  echo "RESULT=PMD_BASELINE_OK（实测 ${measured_violations} == 登记 ${registered} == pom 阈值 ${pom_reader}）"
fi
exit "$rc"
