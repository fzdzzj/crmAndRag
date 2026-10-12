#!/usr/bin/env bash
# 机械核对脚本（非门禁）：复核强度分级政策里 C 档替代复核 agent 的出表工具。
#
# 恒 exit 0 —— 只报告、不判定（D4 边界：不接 CI、不接 merge-gate.sh）。
# 输出四类机械事实：
#   1. 两树 git 状态与 HEAD 位置        —— 权威树（当前 repo）与可选的协作/观察树
#   2. 分支 vs master diff 文件清单      —— 指定分支相对 master 改动过哪些文件
#   3. 指定卡 tasks.md 未勾项计数        —— 数 `- [ ]` 行（未勾），顺带报勾选总数
#   4. 基线文件 vs master 漂移检查       —— 基线文件在当前 HEAD 与 master 间是否有差异
#
# 用法：
#   bash scripts/tests/review-mechanical-check.sh
#       # 默认：当前 repo 视为权威树，master-ref=master，无第二棵树，无指定卡
#   bash scripts/tests/review-mechanical-check.sh --branch feature/xxx --tasks openspec/changes/xxx/tasks.md
#   bash scripts/tests/review-mechanical-check.sh --master-tree ../crmAndRag --tasks openspec/changes/xxx/tasks.md
set -uo pipefail

# ---- 参数 ----
BRANCH=""            # 待核对分支（默认当前检出分支）
MASTER_REF="master"  # 作为 diff 基准的引用
MASTER_TREE=""       # 可选的第二棵树（协作/观察树）路径
TASKS=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --branch)      BRANCH="${2:-}";      shift 2 ;;
    --master-ref)  MASTER_REF="${2:-}";  shift 2 ;;
    --master-tree) MASTER_TREE="${2:-}"; shift 2 ;;
    --tasks)       TASKS="${2:-}";       shift 2 ;;
    *)             echo "未知参数: $1" >&2; shift ;;   # 只报告，未知参数不阻断
  esac
done

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)

section() { echo; echo "===== $1 ====="; }

# g() <args> -- 机构化调用 git：2>/dev/null 防污染，非零返回时输出占位符。
g() { git -C "$repo_root" "$@" 2>/dev/null || true; }

section "1/4 两树 git 状态与 HEAD 位置"
{
  echo "权威树: $repo_root"
  echo "  分支: $(g rev-parse --abbrev-ref HEAD)"
  echo "  HEAD: $(g log -1 --format='%h %s')"
  if g status --porcelain | grep -qv '^??'; then
    echo "  工作树: 有已跟踪改动"
  else
    echo "  工作树: 干净(或有未跟踪)"
  fi
  if [ -n "$MASTER_TREE" ]; then
    echo "协作树: $MASTER_TREE"
    echo "  分支: $(git -C "$MASTER_TREE" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
    echo "  HEAD: $(git -C "$MASTER_TREE" log -1 --format='%h %s' 2>/dev/null || true)"
  else
    echo "协作树: (未指定 --master-tree)"
  fi
}

section "2/4 分支 vs ${MASTER_REF} diff 文件清单"
target="${BRANCH:-$(g rev-parse --abbrev-ref HEAD)}"
{
  echo "基准: ${MASTER_REF}  分支: ${target}"
  diff_list=$(g diff --name-status "$MASTER_REF"..."$target")
  if [ -n "$diff_list" ]; then
    printf '%s\n' "$diff_list"
  else
    echo "(相对 ${MASTER_REF} 无 diff 或基准不存在)"
  fi
  diff_count=$(g diff --name-only "$MASTER_REF"..."$target" | grep -c . || true)
  echo "文件数: ${diff_count}"
}

section "3/4 指定卡 tasks.md 未勾项计数"
if [ -n "$TASKS" ] && [ -f "$repo_root/$TASKS" ]; then
  unchecked=$(grep -c '^- \[ \]' "$repo_root/$TASKS" 2>/dev/null || true)
  checked=$(grep -c '^- \[x\]' "$repo_root/$TASKS" 2>/dev/null || true)
  echo "tasks: $repo_root/$TASKS"
  echo "  未勾(- [ ]): ${unchecked}"
  echo "  已勾(- [x]): ${checked}"
  echo "  汇总: 已勾=${checked} 未勾=${unchecked}"
else
  echo "未指定 --tasks 或文件不存在: ${TASKS:-<空>}"
fi

section "4/4 基线文件 vs ${MASTER_REF} 漂移检查"
base_files=(
  "scripts/test-baseline.txt"
  "scripts/tests/pmd-violation-baseline.txt"
  "scripts/tests/spotbugs-high-baseline.tsv"
  "src/main/resources/spotbugs-exclude.xml"
)
{
  echo "口径: 仅报告——以下基线文件若在 ${MASTER_REF} 与当前 HEAD 间有 diff 即列出（是否该更新由人判定）"
  for f in "${base_files[@]}"; do
    if g cat-file -e "$MASTER_REF:$f" && g cat-file -e "HEAD:$f"; then
      if g diff --quiet "$MASTER_REF" HEAD -- "$f"; then
        echo "  [一致] $f"
      else
        echo "  [漂移] $f"
      fi
    else
      echo "  [单边缺失或不可比] $f"
    fi
  done
}

echo
echo "机械核对完成（恒 exit 0，仅报告不判定）。"
exit 0