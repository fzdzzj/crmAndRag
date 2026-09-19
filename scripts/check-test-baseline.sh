#!/usr/bin/env bash
# 回归基线门禁：surefire / failsafe 双口径。
#
# 为什么要脚本化：原先两个阈值是手抄在 ci.yml 里的字面量，注释里的推导链
# （639 → 644 → 645 → 649 → …）一旦和常量对不上就没人发现，而且旧实现只对
# `Tests run:` 求和，从不看 Failures / Errors / Skipped —— 整类被跳过的用例
# 照样按"跑过了"计入基线，门禁会静默放行。
#
# 用法：
#   bash scripts/check-test-baseline.sh            # 用已入库的基线裁决当前 target/ 报告
#   bash scripts/check-test-baseline.sh --update   # 用当前报告重写基线（跑完 mvn clean verify 之后）
#
# 基线数字只允许由 --update 从一次真实运行写入，不要手改。
# 历史阶梯见 `git log -p -- scripts/test-baseline.txt` 与 `git log -p -- .github/workflows/ci.yml`。
set -uo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
baseline_file="$script_dir/test-baseline.txt"
reports_root="$repo_root/target"

MODE="check"
if [ "${1:-}" = "--update" ]; then
  MODE="update"
fi

# measure <report-dir> -> "<reports> <tests> <failures> <errors> <skipped>"
measure() {
  local dir="$1"
  if [ ! -d "$dir" ]; then
    return 1
  fi
  local files
  files=$(find "$dir" -maxdepth 1 -name '*.txt' -type f | wc -l | tr -d ' ')
  if [ -z "$files" ] || [ "$files" -eq 0 ]; then
    return 1
  fi
  # 每个 .txt 只取第一条 Tests run 行，避免同一文件多行时重复计数
  find "$dir" -maxdepth 1 -name '*.txt' -type f -print0 \
    | xargs -0 awk -F'[:,]' '
        /^Tests run:/ && !seen[FILENAME]++ {
          tests += $2; failures += $4; errors += $6; skipped += $8
        }
        END { printf "%d %d %d %d", tests, failures, errors, skipped }
      '
}

read_baseline() {
  local key="$1"
  if [ ! -f "$baseline_file" ]; then
    return 1
  fi
  local value
  value=$(sed -n "s/^${key}=//p" "$baseline_file" | tail -1 | tr -d '[:space:]')
  if [ -z "$value" ]; then
    return 1
  fi
  printf '%s' "$value"
}

write_baseline() {
  local revision measured_at
  revision=$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo "unknown")
  measured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  {
    echo "# 回归基线：由 scripts/check-test-baseline.sh --update 从一次真实运行写入，禁止手改。"
    echo "# source-revision=${revision}"
    echo "# measured-at=${measured_at}"
    echo "# reports=报告文件数，tests=Tests run 合计，skipped=Skipped 合计（上限，不是下限）"
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

rc=0

if [ "$MODE" = "update" ]; then
  sf=$(measure "$reports_root/surefire-reports") || { echo "::error::surefire 报告不可测，拒绝写入基线" >&2; exit 1; }
  fa=$(measure "$reports_root/failsafe-reports") || { echo "::error::failsafe 报告不可测，拒绝写入基线" >&2; exit 1; }
  read -r sf_reports sf_tests sf_failures sf_errors sf_skipped <<< "$sf"
  read -r fa_reports fa_tests fa_failures fa_errors fa_skipped <<< "$fa"
  if [ "$sf_failures" -ne 0 ] || [ "$sf_errors" -ne 0 ] || [ "$fa_failures" -ne 0 ] || [ "$fa_errors" -ne 0 ]; then
    echo "::error::当前运行含失败/错误用例（surefire F/E=${sf_failures}/${sf_errors}, failsafe F/E=${fa_failures}/${fa_errors}），拒绝把它们写进基线" >&2
    exit 1
  fi
  write_baseline
  exit 0
fi

for suite in surefire failsafe; do
  dir="$reports_root/${suite}-reports"
  label="${suite} 测试"
  measured=$(measure "$dir")
  if [ -z "$measured" ]; then
    fail "${label} 无法度量：${dir} 缺失或没有 .txt 报告（该类测试根本没执行，不允许静默通过）"
    continue
  fi
  read -r reports tests failures errors skipped <<< "$measured"

  base_tests=$(read_baseline "${suite}.tests") || base_tests=""
  base_skipped=$(read_baseline "${suite}.skipped") || base_skipped=""
  if [ -z "$base_tests" ] || [ -z "$base_skipped" ]; then
    fail "基线文件缺 ${suite} 条目：${baseline_file}（跑一次 --update 生成）"
    continue
  fi

  echo "${label}：报告=${reports}，Tests run=${tests}，Failures=${failures}，Errors=${errors}，Skipped=${skipped}｜基线 tests>=${base_tests} skipped<=${base_skipped}"

  case "$tests$base_tests" in
    *[!0-9]*) fail "${label} 求和含非数字，无法裁决" ;;
  esac
  [ "$failures" -gt 0 ] && fail "${label} 有 ${failures} 条 Failures"
  [ "$errors" -gt 0 ] && fail "${label} 有 ${errors} 条 Errors"
  [ "$tests" -lt "$base_tests" ] && fail "${label} 数 ${tests} < 基线 ${base_tests}，疑似丢测试"
  [ "$skipped" -gt "$base_skipped" ] && fail "${label} 跳过 ${skipped} > 基线 ${base_skipped}，有用例被悄悄跳过"
done

if [ "$rc" -eq 0 ]; then
  echo "回归基线门禁通过（基线文件：$baseline_file）"
fi
exit "$rc"
