#!/usr/bin/env bash
# Self-test for scripts/agent-helper.sh（任务卡 P-c / L-8）。
#
# 锁住的是工具化后 5 条机器可判纪律的正反向行为（红绿双向证明）：
#   - check-line-endings: 纯 LF / 纯 CRLF 双向、混合换行指名到 文件:行号、空文件 vacuous 通过；
#   - check-write-set: 夹具仓里 base 之后已提交的改动全在声明写集 -> WRITE_SET_OK；
#     混入未声明文件 -> rc 1 且逐个 ILLEGAL_FILE；
#   - check-dirty: 已跟踪文件改动 -> DIRTY rc 1；纯新增未跟踪 -> CLEAN rc 0（防 git status 误报）；
#   - eval-pipefail: 对照组先证明默认管道会把 false|true 吞成 0，经助手必须穿透为 1，
#     且真实退出码（exit 7）原样透传；成功管道保持 0；
#   - 自持检：两个新脚本自身必须纯 LF（check-line-endings lf 打在自家文件上）。
#
# 夹具 = mktemp -d 下的独立 git 仓（local config 关 autocrlf / hooks，避免宿主配置渗入），
# 无 Docker、无外网、无 Maven。
# check-env 只断言机器无关的输出行（bash / OS），其退出码依赖 JAVA_HOME 是否配置，刻意不断言。
#
# 跑：bash scripts/tests/agent-helper-selftest.sh    （退出 0 = 全部断言成立）
set -uo pipefail

selftest_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
helper="$selftest_dir/../agent-helper.sh"

checks=0
failures=0
pass() { checks=$((checks + 1)); echo "ok - $*"; }
die() { checks=$((checks + 1)); failures=$((failures + 1)); echo "NOT OK - $*" >&2; }

expect_rc() { # <label> <expected> <actual>
  if [ "$2" -eq "$3" ]; then
    pass "$1"
  else
    die "$1: expected rc=$2 but got rc=$3"
  fi
}

expect_contains() { # <label> <needle> <haystack>
  case "$3" in
    *"$2"*) pass "$1" ;;
    *) die "$1: output missing [$2]" ;;
  esac
}

if [ ! -f "$helper" ]; then
  echo "FATAL: helper not found at $helper" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 夹具：临时目录 + 独立 git 仓（老 git 无 init -b，回退普通 init）
# ---------------------------------------------------------------------------
fixture=$(mktemp -d) || { echo "FATAL: mktemp -d failed" >&2; exit 1; }
trap 'rm -rf "$fixture"' EXIT

fxrepo="$fixture/repo"
nohooks="$fixture/no-hooks"
mkdir -p "$fxrepo" "$nohooks"
if ! git -C "$fxrepo" init -q -b master 2>/dev/null; then
  git -C "$fxrepo" init -q
fi
git -C "$fxrepo" config user.name "agent-helper-selftest"
git -C "$fxrepo" config user.email "selftest@invalid"
git -C "$fxrepo" config core.autocrlf false
git -C "$fxrepo" config commit.gpgsign false
git -C "$fxrepo" config core.hooksPath "$nohooks"

# 换行夹具：纯 LF / 纯 CRLF / 混合（第 2 行是 CRLF，其余 LF）/ 空文件
printf 'alpha\nbeta\n' > "$fixture/pure-lf.txt"
printf 'alpha\r\nbeta\r\n' > "$fixture/pure-crlf.txt"
printf 'alpha\nbeta\r\ngamma\n' > "$fixture/mixed.txt"
: > "$fixture/empty.txt"

echo "# scenario 1: check-line-endings 正反向"
out=$(bash "$helper" check-line-endings lf "$fixture/pure-lf.txt"); rc=$?
expect_rc "pure LF passes lf mode" 0 "$rc"
expect_contains "pure LF lf mode prints OK" "LINE_ENDINGS_OK" "$out"

out=$(bash "$helper" check-line-endings crlf "$fixture/pure-lf.txt"); rc=$?
expect_rc "pure LF fails crlf mode" 1 "$rc"
expect_contains "pure LF crlf mode names line 1" "pure-lf.txt:1:" "$out"

out=$(bash "$helper" check-line-endings crlf "$fixture/pure-crlf.txt"); rc=$?
expect_rc "pure CRLF passes crlf mode" 0 "$rc"
expect_contains "pure CRLF crlf mode prints OK" "LINE_ENDINGS_OK" "$out"

out=$(bash "$helper" check-line-endings lf "$fixture/pure-crlf.txt"); rc=$?
expect_rc "pure CRLF fails lf mode" 1 "$rc"
expect_contains "pure CRLF lf mode names line 1" "pure-crlf.txt:1:" "$out"

out=$(bash "$helper" check-line-endings lf "$fixture/mixed.txt"); rc=$?
expect_rc "mixed file fails lf mode" 1 "$rc"
expect_contains "mixed lf mode names the CR line (2)" "mixed.txt:2:" "$out"

out=$(bash "$helper" check-line-endings crlf "$fixture/mixed.txt"); rc=$?
expect_rc "mixed file fails crlf mode" 1 "$rc"
expect_contains "mixed crlf mode names the lone-LF line (1)" "mixed.txt:1:" "$out"

out=$(bash "$helper" check-line-endings lf "$fixture/empty.txt"); rc=$?
expect_rc "empty file passes lf mode" 0 "$rc"
out=$(bash "$helper" check-line-endings crlf "$fixture/empty.txt"); rc=$?
expect_rc "empty file passes crlf mode" 0 "$rc"

echo "# scenario 2: check-write-set 正反向（夹具仓）"
printf 'one v1\n' > "$fxrepo/allowed-one.txt"
printf 'two v1\n' > "$fxrepo/allowed-two.txt"
git -C "$fxrepo" add -A
git -C "$fxrepo" commit -q -m "base"
base_rev=$(git -C "$fxrepo" rev-parse HEAD)

printf 'one v2\n' > "$fxrepo/allowed-one.txt"
git -C "$fxrepo" add -A
git -C "$fxrepo" commit -q -m "touch allowed file"

out=$(cd "$fxrepo" && bash "$helper" check-write-set "$base_rev" allowed-one.txt allowed-two.txt); rc=$?
expect_rc "in-set committed change passes" 0 "$rc"
expect_contains "in-set run prints WRITE_SET_OK" "WRITE_SET_OK: 1 files" "$out"

printf 'rogue\n' > "$fxrepo/rogue-one.txt"
printf 'rogue too\n' > "$fxrepo/rogue-two.txt"
git -C "$fxrepo" add -A
git -C "$fxrepo" commit -q -m "rogue additions"

out=$(cd "$fxrepo" && bash "$helper" check-write-set "$base_rev" allowed-one.txt allowed-two.txt); rc=$?
expect_rc "rogue files fail the write-set check" 1 "$rc"
expect_contains "rogue file one is named" "ILLEGAL_FILE: rogue-one.txt" "$out"
expect_contains "rogue file two is named" "ILLEGAL_FILE: rogue-two.txt" "$out"

echo "# scenario 3: check-dirty 正反向（夹具仓）"
out=$(cd "$fxrepo" && bash "$helper" check-dirty); rc=$?
expect_rc "committed-only tree is CLEAN" 0 "$rc"
expect_contains "clean tree prints STATUS: CLEAN" "STATUS: CLEAN" "$out"

printf 'appended\n' >> "$fxrepo/allowed-one.txt"
out=$(cd "$fxrepo" && bash "$helper" check-dirty); rc=$?
expect_rc "tracked modification is DIRTY" 1 "$rc"
expect_contains "dirty tree prints STATUS: DIRTY" "STATUS: DIRTY" "$out"

git -C "$fxrepo" checkout -q -- allowed-one.txt
printf 'untracked only\n' > "$fxrepo/untracked-note.txt"
out=$(cd "$fxrepo" && bash "$helper" check-dirty); rc=$?
expect_rc "untracked-only tree stays CLEAN (no false dirty)" 0 "$rc"
expect_contains "untracked-only prints STATUS: CLEAN" "STATUS: CLEAN" "$out"
rm -f "$fxrepo/untracked-note.txt"

echo "# scenario 4: eval-pipefail 退出码穿透"
out=$(bash -c 'false | true'); rc=$?
expect_rc "control: plain pipe swallows false into 0" 0 "$rc"

out=$(bash "$helper" eval-pipefail 'false | true'); rc=$?
expect_rc "eval-pipefail surfaces false|true as 1" 1 "$rc"

out=$(bash "$helper" eval-pipefail 'bash -c "exit 7" | cat'); rc=$?
expect_rc "eval-pipefail passes the real exit code (7) through" 7 "$rc"

out=$(bash "$helper" eval-pipefail 'printf ok | cat'); rc=$?
expect_rc "successful pipeline stays 0" 0 "$rc"

out=$(bash "$helper" eval-pipefail 'true | false'); rc=$?
expect_rc "rightmost failure also surfaces" 1 "$rc"

echo "# scenario 5: 自持检与用法守卫"
out=$(bash "$helper" check-line-endings lf "$helper"); rc=$?
expect_rc "agent-helper.sh itself is pure LF" 0 "$rc"
out=$(bash "$helper" check-line-endings lf "$selftest_dir/agent-helper-selftest.sh"); rc=$?
expect_rc "agent-helper-selftest.sh itself is pure LF" 0 "$rc"

out=$(bash "$helper" check-env)
expect_contains "check-env prints bash version line" "- bash:" "$out"
expect_contains "check-env prints OS line" "- os:" "$out"

out=$(bash "$helper" definitely-not-a-subcommand 2>/dev/null); rc=$?
expect_rc "unknown subcommand exits 2" 2 "$rc"

echo
if [ "$failures" -eq 0 ]; then
  echo "SELFTEST PASSED: $checks assertions"
  exit 0
fi
echo "SELFTEST FAILED: $failures of $checks assertions" >&2
exit 1
