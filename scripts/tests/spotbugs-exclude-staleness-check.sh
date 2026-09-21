#!/usr/bin/env bash
# SpotBugs High 存量基线的"过期豁免"双射校验（harness-gates 组 5.5，由组 3 的一次性校验器耐久化而来）。
#
# 要解决的问题：`mvn -B -ntp spotbugs:check` 只会因"出现不在台账里的新 High"而红，
# 不会因"台账里某条豁免对应的缺陷已被修掉"而红。后者就是过期豁免——它继续挂在台账里，
# 顺带吞掉将来同类的真缺陷，而构建全绿，没人知道。本脚本专抓这一项，
# 判别式与 src/main/resources/spotbugs-exclude.xml 头部注释写的复验法一致：
#   <Match> 条数必须等于未过滤 High 条数，且「类 + bug 型 + 成员」一一对应。
#
# 真值来源三选一：
#   （默认）                读被跟踪的快照 scripts/tests/spotbugs-high-baseline.tsv（最近一次未过滤真实运行）
#   --xml <文件>            读一份当场生成的未过滤 spotbugs XML（判定逻辑与默认模式逐字相同）
#   --update-snapshot <文件> 用那份 XML 重写快照 —— 唯一会写文件的模式，且只写该快照
#
# 用法：
#   bash scripts/tests/spotbugs-exclude-staleness-check.sh
#   bash scripts/tests/spotbugs-exclude-staleness-check.sh --xml /path/to/unfiltered-spotbugsXml.xml
#   bash scripts/tests/spotbugs-exclude-staleness-check.sh --update-snapshot /path/to/unfiltered-spotbugsXml.xml
#
# 取未过滤报告的手法与 spotbugs-exclude.xml 头部注释一致：临时注释掉 pom 的 <excludeFilterFile>，
# 跑 `mvn -B -ntp compile spotbugs:spotbugs`，把 target/spotbugsXml.xml 另存，再把 pom 原样放回。
#
# 退出码：0=双射成立；1=双射不成立（MISS/STALE/COARSE/计数不等）；2=前置条件缺失（文件或 pom 接线）。
#
# 计数口径注意：spotbugs-exclude.xml 的**头部注释里也出现 `<Match>` 字样**，因此本脚本先剥 XML 注释、
# 再按 XML 元素切分计数，不用 `grep -c '<Match>'` 那种行数口径。
set -uo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
# Test-only hooks（口径同 scripts/check-test-baseline.sh 的 BASELINE_* 钩子）：让自测能拿临时台账
# 证明"坏输入真能变红"，而不必去改被跟踪的 src/main/resources/spotbugs-exclude.xml。
# 正常调用三个变量都不存在，取的就是真实路径。
exclude_file=${SPOTBUGS_EXCLUDE_FILE:-"$repo_root/src/main/resources/spotbugs-exclude.xml"}
pom_file=${SPOTBUGS_POM_FILE:-"$repo_root/pom.xml"}
snapshot_file=${SPOTBUGS_HIGH_SNAPSHOT:-"$script_dir/spotbugs-high-baseline.tsv"}

err() { echo "::error::$*" >&2; }

[ -f "$exclude_file" ] || { err "缺少豁免台账：$exclude_file"; exit 2; }
[ -f "$pom_file" ] || { err "缺少 pom.xml：$pom_file"; exit 2; }

# 前置条件之一：pom 确实把台账接给了插件。接线一旦被摘掉，存量 High 会全体变红（表现为"老缺陷突然全变新缺陷"），
# 而双射本身失去意义 —— 所以单独判、单独指名，不混进双射结论里。
pom_wiring=$(LC_ALL=C grep -c '<excludeFilterFile>[[:space:]]*src/main/resources/spotbugs-exclude\.xml[[:space:]]*</excludeFilterFile>' "$pom_file" || true)
if [ "${pom_wiring:-0}" -ne 1 ]; then
  err "pom.xml 未把 src/main/resources/spotbugs-exclude.xml 接为 <excludeFilterFile>（命中 ${pom_wiring:-0} 次，应为 1 次）——台账当前不生效"
  exit 2
fi

# strip_xml_comments —— 按 <!-- ... --> 配对剥注释（状态机实现，不假设注释内不含 '>'）。
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

# parse_exclude_matches —— 从台账解析「类 <TAB> bug型 <TAB> 成员种类 <TAB> 成员名 <TAB> 是否含Or」。
# 以 </Match> 为记录分隔符，每条记录只取第一个 "<Match" 之后的内容，块与块之间的注释天然不参与。
parse_exclude_matches() {
  strip_xml_comments | LC_ALL=C gawk '
    function attr(s, name,   q, v) {
      if (!match(s, "[[:space:]]" name "[[:space:]]*=[[:space:]]*[\047\042]")) return ""
      q = substr(s, RSTART + RLENGTH - 1, 1)
      v = substr(s, RSTART + RLENGTH)
      if (!match(v, q)) return ""
      return substr(v, 1, RSTART - 1)
    }
    BEGIN { RS = "</Match>" }
    {
      p = index($0, "<Match")
      if (p == 0) next
      body = substr($0, p)
      cls = ""; bug = ""; field = ""; method = ""
      if (match(body, /<Class[[:space:]][^>]*>/))  cls    = attr(substr(body, RSTART, RLENGTH), "name")
      if (match(body, /<Bug[[:space:]][^>]*>/))   bug    = attr(substr(body, RSTART, RLENGTH), "pattern")
      if (match(body, /<Field[[:space:]][^>]*>/)) field  = attr(substr(body, RSTART, RLENGTH), "name")
      if (match(body, /<Method[[:space:]][^>]*>/)) method = attr(substr(body, RSTART, RLENGTH), "name")
      kind = "none"; member = ""
      if (field != "")       { kind = "field";  member = field }
      else if (method != "") { kind = "method"; member = method }
      printf "%s\t%s\t%s\t%s\t%s\n", cls, bug, kind, member, (body ~ /<Or>/ ? "or" : "-")
    }'
}

# parse_spotbugs_findings —— 从未过滤 spotbugs XML 解析「优先级 <TAB> 类 <TAB> bug型 <TAB> 成员种类 <TAB> 成员名」。
# 成员取 primary='true' 的 Field，没有则取 primary='true' 的 Method，与台账的 Field/Method 粒度对齐。
# 属性引号两种写法都支持：spotbugs 产出单引号，被跟踪的 XML 惯例是双引号。
parse_spotbugs_findings() {
  LC_ALL=C gawk '
    function attr(s, name,   q, v) {
      if (!match(s, "[[:space:]]" name "[[:space:]]*=[[:space:]]*[\047\042]")) return ""
      q = substr(s, RSTART + RLENGTH - 1, 1)
      v = substr(s, RSTART + RLENGTH)
      if (!match(v, q)) return ""
      return substr(v, 1, RSTART - 1)
    }
    function primary_name(block, tag,   rest, seg, found) {
      rest = block; found = ""
      while (found == "" && match(rest, "<" tag "[[:space:]][^>]*>")) {
        seg = substr(rest, RSTART, RLENGTH)
        rest = substr(rest, RSTART + RLENGTH)
        if (attr(seg, "primary") == "true") found = attr(seg, "name")
      }
      return found
    }
    BEGIN { RS = "</BugInstance>" }
    /<BugInstance/ {
      block = substr($0, index($0, "<BugInstance"))
      cls = ""
      if (match(block, /<Class[[:space:]][^>]*>/)) cls = attr(substr(block, RSTART, RLENGTH), "classname")
      m = primary_name(block, "Field")
      kind = "field"
      if (m == "") { m = primary_name(block, "Method"); kind = "method" }
      if (m == "") { kind = "none" }
      printf "%s\t%s\t%s\t%s\t%s\n", attr(block, "priority"), cls, attr(block, "type"), kind, m
    }'
}

# truth_rows <file> —— 输出排序后的「类 <TAB> bug型 <TAB> 成员种类 <TAB> 成员名」，列序与台账解析一致。
# TRUTH_KIND=xml 时输入是 spotbugs XML（只取 priority=1 即 High），=tsv 时输入是本脚本产出的快照。
# 一律先 tr -d '\r'：台账与快照都是被跟踪文本，在 core.autocrlf=true 的检出下会带 CRLF，
# 而 \r 会挂在第 4 列尾巴上，使 comm 判为不同行 —— 表现为 MISS/STALE 成对出现、
# 只有文件末行（无尾换行）能配上。这是平台差异不是缺陷差异，必须在读入侧归一。
TRUTH_KIND=""
truth_rows() {
  case "$TRUTH_KIND" in
  xml)
    tr -d '\r' < "$1" | parse_spotbugs_findings | awk -F'\t' '$1 == "1"' | cut -f2-
    ;;
  tsv)
    tr -d '\r' < "$1" | LC_ALL=C grep -v '^#' | LC_ALL=C grep .
    ;;
  esac | LC_ALL=C sort
}

MODE="${1:-check}"
case "$MODE" in
check)
  TRUTH_KIND="tsv"
  truth_file="$snapshot_file"
  ;;
--xml)
  TRUTH_KIND="xml"
  truth_file="${2:-}"
  [ -f "$truth_file" ] || { err "--xml 指向的文件不存在：$truth_file"; exit 2; }
  ;;
--update-snapshot)
  src="${2:-}"
  [ -f "$src" ] || { err "--update-snapshot 需要一次真实运行的未过滤 spotbugs XML，路径不存在：$src"; exit 2; }
  TRUTH_KIND="xml"
  n=$(truth_rows "$src" | wc -l | tr -d ' ')
  if [ "$n" -eq 0 ]; then
    err "该 XML 里没解析出任何 priority=1（High）缺陷，拒绝把空清单写成基线（会把双射永久变成'台账必须为空'）"
    exit 2
  fi
  {
    echo "# SpotBugs 未过滤 High 存量快照：由 scripts/tests/spotbugs-exclude-staleness-check.sh --update-snapshot"
    echo "# 从一次真实运行写入，禁止手改（口径同 scripts/test-baseline.txt）。"
    echo "# 列：类 <TAB> bug 型 <TAB> 成员种类 <TAB> 成员名；成员取 primary='true' 的 Field，无则取 Method。"
    echo "# source-revision=$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "# measured-at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    truth_rows "$src"
  } > "$snapshot_file"
  echo "已写入快照：${snapshot_file#"$repo_root"/}（$n 条 High）"
  exit 0
  ;;
-h | --help)
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
  exit 0
  ;;
*)
  err "未知参数 '$MODE'（可选：--xml <文件> | --update-snapshot <文件> | --help）"
  exit 2
  ;;
esac

[ -f "$truth_file" ] || { err "缺少真值文件：$truth_file（跑一次 --update-snapshot 生成）"; exit 2; }

truth=$(truth_rows "$truth_file")
ledger=$(tr -d '\r' < "$exclude_file" | parse_exclude_matches)
[ -n "$truth" ] || { err "真值解析结果为空：$truth_file —— 宁可判错，也不把'没解析出来'读成'没有缺陷'"; exit 2; }
[ -n "$ledger" ] || { err "台账解析结果为空：$exclude_file"; exit 2; }

high_count=$(printf '%s\n' "$truth" | wc -l | tr -d ' ')
match_count=$(printf '%s\n' "$ledger" | wc -l | tr -d ' ')

# 粒度违规：缺类 / 缺型 / 一条写多型 / 缺成员 / 用了 <Or> 或通配 —— 这类豁免会顺带吞掉同类新缺陷。
coarse=$(printf '%s\n' "$ledger" | awk -F'\t' '$1 == "" || $2 == "" || $3 == "none" || $4 == "" || $5 == "or" || $2 ~ /,/ || $1 ~ /\*/')
coarse_count=$(printf '%s' "$coarse" | grep -c . || true)

covered=$(printf '%s\n' "$ledger" | cut -f1-4 | LC_ALL=C sort)
stale=$(LC_ALL=C comm -23 <(printf '%s\n' "$covered") <(printf '%s\n' "$truth"))
missed=$(LC_ALL=C comm -13 <(printf '%s\n' "$covered") <(printf '%s\n' "$truth"))
stale_count=$(printf '%s' "$stale" | grep -c . || true)
missed_count=$(printf '%s' "$missed" | grep -c . || true)

echo "真值来源：${truth_file#"$repo_root"/}"
echo "未过滤 High=$high_count  <Match> 元素=$match_count（按 XML 元素计，已剥注释）"
if [ "$missed_count" -gt 0 ]; then
  echo "MISS —— 缺陷未被任何豁免覆盖（新 High，或台账被误删）："
  printf '%s\n' "$missed" | sed 's/^/  /'
fi
if [ "$stale_count" -gt 0 ]; then
  echo "STALE —— 豁免登记了却不再命中（缺陷已修，必须删除对应 <Match>，不许留着）："
  printf '%s\n' "$stale" | sed 's/^/  /'
fi
if [ "$coarse_count" -gt 0 ]; then
  echo "COARSE —— 粒度不合规（三段不齐 / 含 <Or> / 含通配 / 一条写多型）："
  printf '%s\n' "$coarse" | sed 's/^/  /'
fi

if [ "$high_count" -eq "$match_count" ] && [ "$missed_count" -eq 0 ] && [ "$stale_count" -eq 0 ] && [ "$coarse_count" -eq 0 ]; then
  echo "RESULT=BIJECTION_OK（$match_count 条豁免与 $high_count 条 High 一一对应）"
  exit 0
fi
err "SpotBugs 基线双射校验失败：High=$high_count，<Match>=$match_count，MISS=$missed_count，STALE=$stale_count，COARSE=$coarse_count"
echo "RESULT=MISMATCH" >&2
exit 1
