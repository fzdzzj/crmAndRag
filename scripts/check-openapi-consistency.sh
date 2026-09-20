#!/usr/bin/env bash
# 前后端契约一致性门禁：frontend/openapi.yaml 必须是后端 springdoc 的当前导出。
#
# 为什么要脚本化：契约是手工提交的二进制式大文件，靠人记得"改完接口去刷一下前端规范"
# 一定会有人忘。真正的判定在 OpenApiContractExportTest（起完整上下文打 /v3/api-docs.yaml
# 逐字比对，属 CI 阶段 1 的 surefire 口径），本脚本只负责三件事：跑对命令、把差异打印出来、
# 提供唯一被允许的覆盖入口 --update。
#
# 用法：
#   bash scripts/check-openapi-consistency.sh            # 校验：漂移即非零退出
#   bash scripts/check-openapi-consistency.sh --update   # 用后端导出覆盖前端契约
#
# --update 之后必须到 frontend/ 跑 `pnpm gen:api` 重新生成 SDK，契约与客户端同一次提交。
# 不依赖 Docker：走 test profile 的 H2 + 内存向量库/存储（与 ApplicationContextSmokeTest 同源）。
set -uo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
committed="$repo_root/frontend/openapi.yaml"
exported="$repo_root/target/openapi/openapi.yaml"
test_class=OpenApiContractExportTest

MODE="check"
if [ "${1:-}" = "--update" ]; then
  MODE="update"
fi

normalize() {
  sed -e 's/\r$//' "$1"
}

run_export() {
  mvn -B -ntp test -Dtest="$test_class" -DfailIfNoTests=true
}

print_drift() {
  if [ ! -f "$exported" ]; then
    echo "::error::没拿到导出文件 $exported，说明测试在写盘前就失败了，看上面的 mvn 输出" >&2
    return
  fi
  echo "---- 契约差异（左=已提交的前端契约，右=后端当前导出）----"
  diff -u <(normalize "$committed") <(normalize "$exported") | head -80
  echo "---- 差异统计 ----"
  echo "已提交 $(wc -l < "$committed") 行 / 导出 $(wc -l < "$exported") 行"
}

cd "$repo_root" || exit 1

if [ "$MODE" = "update" ]; then
  echo "== 用后端导出覆盖 $committed 需要一次真实导出，先跑测试 =="
  if ! run_export; then
    echo "（预期内）契约比对失败说明存在漂移，继续执行覆盖"
  fi
  if [ ! -f "$exported" ]; then
    echo "::error::导出文件缺失，无法覆盖：$exported" >&2
    exit 1
  fi
  cp "$exported" "$committed"
  echo "== 已覆盖前端契约，复校一次 =="
  if run_export; then
    echo "契约一致。别忘了在 frontend/ 跑 pnpm gen:api 重新生成 SDK。"
    exit 0
  fi
  echo "::error::覆盖后仍不一致，导出过程不稳定，需人工排查" >&2
  print_drift
  exit 1
fi

if run_export; then
  echo "前后端契约一致：frontend/openapi.yaml 与后端 swagger 导出逐字相同。"
  exit 0
fi

echo "::error::前后端契约漂移，见下面的差异" >&2
print_drift
echo "修复：bash scripts/check-openapi-consistency.sh --update，然后 frontend/ 里 pnpm gen:api" >&2
exit 1
