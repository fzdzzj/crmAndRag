#!/usr/bin/env bash
# agent-helper.sh — 方法学纪律工具化助手（任务卡 P-c / L-8）。
#
# 把主/子 agent 多轮协作沉淀的 9 条纪律中可机器判定的部分固化为子命令，
# 消除换行混淆、管道退出码被吞、脏树误报、写集越界四类历史摩擦
# （取证见 docs/main-agent-execution.md §12/§15/§17.5/§18/§26/§27）：
#
#   1. check-line-endings <crlf|lf> <file...>   纯 CRLF / 纯 LF 双向校验，违规指名文件与行号
#   2. check-write-set <base-rev> <allowed...>  自 base-rev 起的已提交改动必须全部落在声明写集内
#   3. check-dirty                              diff-index 判脏树，未跟踪文件不算脏（防误报）
#   4. check-env                                bash / OS / git 仓库 + JAVA_HOME 指向 JDK 21
#   5. eval-pipefail "<cmd>"                    子壳内 set -o pipefail 执行管道命令，退出码如实穿透
#
# 其余纪律属流程约束，由任务卡与回报格式负责，本脚本在注释里只指路：
#   6. 特性分支 + 真 --no-ff 合并 + 保留分支 + 未授权严禁 git push；
#   7. scripts/test-baseline.txt 只许 check-test-baseline.sh --update 从一次真实运行写入；
#   8. 守卫必须红绿双向证明，探针清理后确认工作树恢复（见配套 selftest 的正反向断言）；
#   9. 动态数字必须带日期落款或给出自查命令，禁止硬编码易过期的静态计数。
#
# 本脚本自身必须保持纯 LF（.gitattributes: *.sh text eol=lf）。
# 自测套件：bash scripts/tests/agent-helper-selftest.sh
set -uo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: agent-helper.sh <subcommand> [args]

Subcommands:
  check-line-endings <crlf|lf> <file...>   every line CRLF (crlf) / no CR at all (lf); rc 1 on violation
  check-write-set <base-rev> <allowed...>  committed files changed since base-rev must all be listed
                                           (repo-root-relative); rogue ones printed as ILLEGAL_FILE
  check-dirty                              tracked changes -> STATUS: DIRTY rc 1; untracked ignored -> CLEAN
  check-env                                print bash/OS, require a git work tree, require JAVA_HOME -> JDK 21
  eval-pipefail "<command>"                run <command> in a pipefail subshell, pass the exit code through
EOF
}

check_line_endings() {
  if [ $# -lt 1 ]; then
    echo "ERROR: check-line-endings <crlf|lf> <file...>" >&2
    return 2
  fi
  local mode="$1"
  shift
  case "$mode" in
    crlf|lf) ;;
    *)
      echo "ERROR: mode must be 'crlf' or 'lf', got: $mode" >&2
      return 2
      ;;
  esac
  if [ $# -lt 1 ]; then
    echo "ERROR: check-line-endings needs at least one file" >&2
    return 2
  fi
  local rc=0 f last_byte
  for f in "$@"; do
    if [ ! -f "$f" ]; then
      echo "LINE_ENDING_VIOLATION: $f:0: not a regular file"
      rc=1
      continue
    fi
    if [ "$mode" = "lf" ]; then
      # BINMODE=1: Git Bash 的 gawk 默认按文本模式读文件会静默吞掉 \r（Linux 上为普通变量，无副作用）
      awk -v file="$f" '
        BEGIN { BINMODE = 1 }
        { if (index($0, "\r") > 0) { printf "LINE_ENDING_VIOLATION: %s:%d: found CR (file must be pure LF)\n", file, NR; bad = 1; exit 1 } }
        END { if (bad) exit 1; printf "LINE_ENDINGS_OK: %s\n", file; exit 0 }
      ' "$f" || rc=1
    else
      last_byte=$(tail -c 1 "$f" | od -An -tx1 | tr -d " \t\n\r")
      if [ -n "$last_byte" ] && [ "$last_byte" != "0a" ]; then
        echo "LINE_ENDING_VIOLATION: $f:$(awk 'END { print NR }' "$f"): file does not end with LF (last byte 0x$last_byte); pure CRLF requires a final CRLF"
        rc=1
        continue
      fi
      awk -v file="$f" '
        BEGIN { BINMODE = 1 }
        { n = length($0); if (n == 0 || substr($0, n, 1) != "\r") { printf "LINE_ENDING_VIOLATION: %s:%d: lone LF (line not terminated by CRLF)\n", file, NR; bad = 1; exit 1 } }
        END { if (bad) exit 1; printf "LINE_ENDINGS_OK: %s\n", file; exit 0 }
      ' "$f" || rc=1
    fi
  done
  return "$rc"
}

check_write_set() {
  if [ $# -lt 1 ]; then
    echo "ERROR: check-write-set <base-rev> <allowed_file...>" >&2
    return 2
  fi
  local base_rev="$1"
  shift
  if ! git rev-parse --verify --quiet "$base_rev" >/dev/null 2>&1; then
    echo "ERROR: base revision not resolvable: $base_rev" >&2
    return 2
  fi
  local changed
  if ! changed=$(git diff --name-only "${base_rev}...HEAD" 2>/dev/null); then
    if ! changed=$(git diff --name-only "${base_rev}..HEAD" 2>/dev/null); then
      echo "ERROR: git diff against $base_rev failed" >&2
      return 2
    fi
  fi
  local rc=0 total=0 f a in_set
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    total=$((total + 1))
    in_set=0
    for a in "$@"; do
      if [ "$f" = "$a" ]; then
        in_set=1
        break
      fi
    done
    if [ "$in_set" -eq 0 ]; then
      echo "ILLEGAL_FILE: $f"
      rc=1
    fi
  done <<EOF
$changed
EOF
  if [ "$rc" -eq 0 ]; then
    echo "WRITE_SET_OK: $total files"
  fi
  return "$rc"
}

check_dirty() {
  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "ERROR: not inside a git work tree" >&2
    return 2
  fi
  if git diff-index --quiet HEAD -- 2>/dev/null; then
    echo "STATUS: CLEAN"
    return 0
  fi
  echo "STATUS: DIRTY"
  return 1
}

check_env() {
  local rc=0 inside
  echo "ENV CHECK"
  echo "- bash: $BASH_VERSION"
  echo "- os: $(uname -s) / OSTYPE=$OSTYPE"
  inside=$(git rev-parse --is-inside-work-tree 2>/dev/null)
  if [ "$inside" = "true" ]; then
    echo "- git repo: yes ($(git rev-parse --show-toplevel 2>/dev/null))"
  else
    echo "- git repo: NO (run inside a git work tree)"
    rc=1
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    local java_bin=""
    if [ -x "$JAVA_HOME/bin/java" ]; then
      java_bin="$JAVA_HOME/bin/java"
    elif [ -x "$JAVA_HOME/bin/java.exe" ]; then
      java_bin="$JAVA_HOME/bin/java.exe"
    fi
    if [ -z "$java_bin" ]; then
      echo "- JAVA_HOME: $JAVA_HOME -> bin/java not found (expected JDK 21)"
      rc=1
    else
      local ver major
      ver=$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)
      major="${ver%%.*}"
      if [ "$major" = "1" ]; then
        major="${ver#*.}"
        major="${major%%.*}"
      fi
      if [ "$major" = "21" ]; then
        echo "- JAVA_HOME: $JAVA_HOME -> java $ver [OK]"
      else
        echo "- JAVA_HOME: $JAVA_HOME -> java ${ver:-unknown} but expected JDK 21"
        rc=1
      fi
    fi
  else
    echo "- JAVA_HOME: not set (mvn gates need JDK 21)"
    rc=1
  fi
  if [ "$rc" -eq 0 ]; then
    echo "ENV CHECK: PASS"
  else
    echo "ENV CHECK: FAIL"
  fi
  return "$rc"
}

eval_pipefail() {
  if [ $# -lt 1 ]; then
    echo "ERROR: eval-pipefail \"<command>\" (one quoted pipeline)" >&2
    return 2
  fi
  local cmd="$1"
  ( set -o pipefail; eval "$cmd" )
}

main() {
  if [ $# -lt 1 ]; then
    usage
    return 2
  fi
  local cmd="$1"
  shift
  case "$cmd" in
    check-line-endings) check_line_endings "$@" ;;
    check-write-set) check_write_set "$@" ;;
    check-dirty) check_dirty "$@" ;;
    check-env) check_env "$@" ;;
    eval-pipefail) eval_pipefail "$@" ;;
    help|-h|--help) usage ;;
    *)
      echo "ERROR: unknown subcommand: $cmd" >&2
      usage
      return 2
      ;;
  esac
}

main "$@"
