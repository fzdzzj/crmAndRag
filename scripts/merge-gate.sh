#!/usr/bin/env bash
# 合并前本地聚合门禁（harness-gates 组 5.2）
#
# 为什么需要它：本仓 `git remote -v` 为空、master 无 upstream —— .github/workflows/ci.yml 里声明的
# push/PR 触发在本仓**不会执行**，所以"任一阶段失败即阻止合入"今天是声明不是机制。真实的验收边界
# 是"执行者手跑一遍再 --no-ff 合入"，本脚本就是把 openspec/git-workflow.md §3 那段散文收敛成一条命令：
#     bash scripts/merge-gate.sh
# 跑不过就不许合。风格照 scripts/fail-fast-gate.sh：自带断言、每个子门禁有具名失败出口，不靠 grep 拼凑。
#
# 用法：
#   bash scripts/merge-gate.sh                # 默认序列（下面 1/2/4/5/6 步）
#   bash scripts/merge-gate.sh --with-verify  # 追加第 3 步 failsafe 集成测试
#   bash scripts/merge-gate.sh --help
#
# 子门禁（失败出口一律带方括号 id，便于从日志里直接认出是哪一步红）：
#   [unit]        mvn -B -ntp test                    surefire 单元 + 契约 + H2 上下文冒烟，无 Docker 无外网
#   [spotbugs]    mvn -B -ntp spotbugs:check          High 级缺陷扫描；判定依据 pom.xml 的 threshold + excludeFilterFile
#   [pmd]         mvn -B -ntp pmd:check               声明规则集（src/main/resources/pmd-rules.xml，24 条）的代码异味扫描；
#                 条数读者 = pom 的 <maxAllowedViolations>。**单独调用、不挂 [it]**：pmd 虽绑在 verify 阶段，
#                 而默认序列根本不跑 verify（--with-verify 才有 [it]），挂在 verify 上等于不设门禁。
#   [it]          mvn -B -ntp verify                  （--with-verify 才跑）failsafe IT；本机无 Docker 时按 §6.2 只跳不证
#   [baseline]    bash scripts/check-test-baseline.sh 回归基线裁决（阈值唯一读者 scripts/test-baseline.txt）
#   [hook]        生效 hooks 目录内 pre-commit 存在、可追踪到 frontend/.githooks/pre-commit、且被转发目标确实在位
#   [bijection]   bash scripts/tests/spotbugs-exclude-staleness-check.sh
#                 SpotBugs 台账双射：<Match> 元素数 == 未过滤 High 数且一一对应，抓"登记了却不再命中"的过期豁免
#   [pmd-baseline]
#                 bash scripts/tests/pmd-baseline-check.sh
#                 PMD 条数基线的过期/漂移判别：登记值 > 实测值 → 红（要求下调，[pmd] 自己看不见这一项）；
#                 实测值 > 登记值 → 红；pom 的 <maxAllowedViolations> 与台账不等 → 红。必须在 [pmd] 之后，
#                 因为它读的就是 [pmd] 刚产出的 target/pmd.xml。
#   [frontend-unit]
#                 pnpm -C frontend test    前端 Vitest 单元/组件轨。之所以要出现在这里：提交期 pre-commit
#                 只跑 lint+type-check（见 frontend/AGENTS.md），而本仓 CI 无触发通道 —— 单元轨否则没有任何
#                 会自己执行的地方。node_modules 缺失时不判红（装依赖是联网动作、需单独授权），
#                 但会在尾部显式声明"本结论未覆盖前端单元轨"，不静默跳过。
#
# 语义边界（必读，别把本脚本读成 CI）：
#   - 只读：不写业务数据、不改数据库、不下发迁移、不访问外网、不 push。构建产物照常落 target/。
#   - 不增减用例：本脚本自身不产生测试，[baseline] 的判定完全委托 check-test-baseline.sh，不在这里复制阈值。
#   - [it] 默认不跑：mvn verify 在有 Docker 的机器上会经 Testcontainers 拉镜像（网络与耗时都由机器状态决定），
#     把它设成默认会让"只读、不访问外网"的承诺失真。但要注意：不跑 [it] 时 target/failsafe-reports 可能是上一轮
#     留下的，[baseline] 仍会照它裁决 —— 报告缺失时 [baseline] 直接判红（不允许静默通过），届时请用 --with-verify。
#
# 自测：bash scripts/tests/merge-gate-selftest.sh（覆盖"任一子门禁失败→聚合非零且指名"与"全过→零退出"）。
set -uo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
cd "$repo_root" || exit 2

WITH_VERIFY=0
for arg in "$@"; do
  case "$arg" in
  --with-verify) WITH_VERIFY=1 ;;
  -h | --help)
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
    exit 0
    ;;
  *)
    echo "::error::未知参数 '$arg'（可选 --with-verify | --help）" >&2
    exit 2
    ;;
  esac
done

# 子门禁命令的可覆盖入口 —— 唯一目的是让 scripts/tests/merge-gate-selftest.sh 能在不依赖 Docker、
# 不跑十分钟 Maven 的前提下验证聚合逻辑（哪一步红、退出码、有没有指名）。默认值就是上面注释里写的真命令，
# 正常调用不需要设任何变量；口径同 scripts/check-test-baseline.sh 的 BASELINE_* 测试钩子。
cmd_unit=${MERGE_GATE_CMD_UNIT:-mvn -B -ntp test}
cmd_spotbugs=${MERGE_GATE_CMD_SPOTBUGS:-mvn -B -ntp spotbugs:check}
cmd_pmd=${MERGE_GATE_CMD_PMD:-mvn -B -ntp pmd:check}
cmd_it=${MERGE_GATE_CMD_IT:-mvn -B -ntp verify}
cmd_baseline=${MERGE_GATE_CMD_BASELINE:-bash scripts/check-test-baseline.sh}
cmd_bijection=${MERGE_GATE_CMD_BIJECTION:-bash scripts/tests/spotbugs-exclude-staleness-check.sh}
cmd_pmd_baseline=${MERGE_GATE_CMD_PMD_BASELINE:-bash scripts/tests/pmd-baseline-check.sh}
# [hook] 的生效目录默认由 git 自己解析（不许硬编码绝对路径）；该覆盖同样只服务于自测。
hooks_dir=${MERGE_GATE_HOOKS_DIR:-$(git rev-parse --git-path hooks)}
cmd_frontend=${MERGE_GATE_CMD_FRONTEND:-pnpm -C frontend test}
# 前端依赖是否就位用这个路径判断；覆盖只服务于自测（本机没有 node_modules 时不判红，见下面的降级）。
frontend_modules=${MERGE_GATE_FRONTEND_MODULES:-$repo_root/frontend/node_modules}
frontend_not_covered=0

echo "== merge-gate：HEAD=$(git rev-parse --short HEAD 2>/dev/null || echo unknown) 分支=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown) =="
echo "   仓根=$repo_root with_verify=$WITH_VERIFY"

failed=()

# run_gate <id> <中文说明> <命令>  —— 命令按 shell 片段执行（覆盖入口本就是命令，非用户输入）。
run_gate() {
  local id=$1 label=$2 cmd=$3 code
  echo "── [$id] $label"
  echo "   \$ $cmd"
  eval "$cmd"
  code=$?
  if [ "$code" -eq 0 ]; then
    echo "   PASS [$id]"
  else
    echo "   FAIL [$id]：$label —— 退出码 $code" >&2
    failed+=("$id")
  fi
  return 0
}

run_gate unit "surefire 单元与契约门禁" "$cmd_unit"
run_gate spotbugs "SpotBugs High 门禁" "$cmd_spotbugs"
# [pmd] 显式单点调用 pmd:check：pmd 在 pom 里绑的是 verify 阶段，默认序列不跑 verify，
# 指望 [it] 顺带等于没有这道门禁（harness-gates 已确认的坑，spec R4「绑在默认不跑的阶段上的检查」）。
run_gate pmd "PMD 声明规则集门禁（条数读者 = pom 的 maxAllowedViolations）" "$cmd_pmd"
if [ "$WITH_VERIFY" -eq 1 ]; then
  run_gate it "failsafe 集成测试门禁" "$cmd_it"
else
  echo "── [it] failsafe 集成测试：未执行（默认跳过，加 --with-verify 才跑）"
fi
run_gate baseline "回归基线裁决（阈值读者 scripts/test-baseline.txt）" "$cmd_baseline"

# [frontend-unit]：spec R3 —— 被跟踪的前端单元用例必须落在某个会自动执行的检查上。
# 提交期 pre-commit 只跑 lint+type-check，CI 在本仓又无触发通道，所以只有这里能承接它。
# 依赖缺失时不判红（pnpm install 是联网动作、需单独授权），但必须显式声明未覆盖，不许静默跳过。
if [ -d "$frontend_modules" ]; then
  run_gate frontend-unit "前端 Vitest 单元轨（无浏览器/无后端）" "$cmd_frontend"
  # vitest 会让 unplugin-vue-router 重写 tracked 的生成物；实测正文不变、只差行尾 CR。
  if [ -n "$(git status --porcelain -- frontend/typed-router.d.ts)" ]; then
    echo "   NOTE [frontend-unit]：vitest 改写了 frontend/typed-router.d.ts 的行尾（正文未变），"
    echo "        属生成物噪声，不要提交：git checkout -- frontend/typed-router.d.ts" >&2
  fi
else
  frontend_not_covered=1
  echo "── [frontend-unit] 未执行：$frontend_modules 不存在（安装依赖属联网动作，本门禁不代跑）" >&2
fi

# [hook]：P1 交给本包的判别式 —— 提交期那道"唯一会自己变红"的门禁是否在位。
# 三项都要：文件存在、内容能追踪到被转发目标、目标本体没被删。
# 只查 `git config --get core.hooksPath` 或只看文件是否存在都不算（spec R2）。
hook_problem=""
if [ ! -f "$hooks_dir/pre-commit" ]; then
  hook_problem="生效 hooks 目录（$hooks_dir）内没有 pre-commit —— 前端提交检查从不执行"
elif ! LC_ALL=C grep -q 'frontend/[.]githooks/pre-commit' "$hooks_dir/pre-commit"; then
  hook_problem="$hooks_dir/pre-commit 存在，但内容追踪不到 frontend/.githooks/pre-commit（不是本仓安装器装的转发器）"
elif [ ! -f "$repo_root/frontend/.githooks/pre-commit" ]; then
  hook_problem="转发器在位，但被转发目标 frontend/.githooks/pre-commit 不存在（转发器会静默跳过，等于没有门禁）"
fi
if [ -z "$hook_problem" ]; then
  echo "── [hook] 提交期 pre-commit 转发器在位：$hooks_dir/pre-commit"
  echo "   PASS [hook]"
else
  echo "   FAIL [hook]：$hook_problem" >&2
  failed+=("hook")
fi

run_gate bijection "SpotBugs 基线双射校验（过期豁免）" "$cmd_bijection"
# [pmd-baseline] 紧跟在 [pmd] 之后跑过才有意义（它读的就是 [pmd] 产出的 target/pmd.xml）；
# 与 [bijection] 同族：都抓"台账留着已失效的冗余"，那类过期 pmd:check 自己永远不会报。
run_gate pmd-baseline "PMD 条数基线过期/漂移校验（台账 scripts/tests/pmd-violation-baseline.txt）" "$cmd_pmd_baseline"

if [ "${#failed[@]}" -eq 0 ]; then
  echo "== merge-gate 通过：所有已执行的子门禁绿。以上输出可作为合入证据（含各步实测数字）。=="
  if [ "$frontend_not_covered" -eq 1 ]; then
    echo "   ⚠ 本证据**未覆盖前端 Vitest 单元轨**（依赖未安装）。合入前端改动请先获授权执行" >&2
    echo "     pnpm install，再重跑 bash scripts/merge-gate.sh。" >&2
  fi
  exit 0
fi

echo "::error::merge-gate 失败，未通过的子门禁：${failed[*]}" >&2
echo "        逐项排查命令：unit→mvn -B -ntp test｜spotbugs→mvn -B -ntp spotbugs:check｜pmd→mvn -B -ntp pmd:check｜it→mvn -B -ntp verify｜" >&2
echo "        baseline→bash scripts/check-test-baseline.sh｜hook→见 frontend/AGENTS.md Git Hook Policy｜" >&2
echo "        frontend-unit→pnpm -C frontend test｜" >&2
echo "        bijection→bash scripts/tests/spotbugs-exclude-staleness-check.sh｜" >&2
echo "        pmd-baseline→bash scripts/tests/pmd-baseline-check.sh" >&2
exit 1
