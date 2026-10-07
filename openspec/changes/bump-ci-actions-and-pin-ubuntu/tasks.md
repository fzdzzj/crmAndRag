# tasks：bump-ci-actions-and-pin-ubuntu（卡 P-r）

> 写集红线：仅 `.github/workflows/ci.yml` 与本文件为 tracked 写集；零 Java、零前端源码、零 DDL、零新依赖、不动 `scripts/test-baseline.txt`。全部数字自跑实测，禁止照抄卡面预期值。

## 1. 阶段一：开工准备
- [x] 1.1 从 `master@c647081` 检出 `feature/bump-ci-actions-and-pin-ubuntu`，登记工作树纯净（仅受保护未跟踪项）
- [x] 1.2 通读 `work/task-card-ci-actions-pin-ubuntu.md` 与本三件套，确认写集与结构红线

## 2. 阶段二：版本核实
- [x] 2.1 WebFetch 核实 `actions/upload-artifact` / `actions/setup-node` / `pnpm/action-setup` 的 GitHub Releases 最新稳定 major，登记目标版本与依据
- [x] 2.2 汇总目标清单（含卡面已确认的 checkout@v5 ×6 / setup-java@v5 ×5 / ubuntu-24.04 ×6）留痕于本节

## 3. 阶段三：实施
- [x] 3.1 修改 `.github/workflows/ci.yml`（仅 `uses:` / `runs-on:` 行）
- [x] 3.2 结构等价证明：`git diff -U0` 变更行过滤后，不含 `uses:` / `runs-on:` 的行为零（贴实录）
- [x] 3.3 YAML 语法校验通过（`yaml.safe_load` 或登记的等效手段）
- [x] 3.4 六 job 名单与各 job step 数改前后对照一致

## 4. 阶段四：门禁验证
- [x] 4.1 全量 `mvn -B -ntp test`：938/0/0/0（数量不变）
- [x] 4.2 四静态门禁（pmd / spotbugs / checkstyle / spotless）0 违规
- [x] 4.3 `check-test-baseline.sh` 通过（**未** `--update`）
- [x] 4.4 三守卫（`check-line-endings lf` / `check-write-set c647081` / `check-dirty`）+ 136 条门禁自测全绿

## 5. 阶段五：提交与合并
- [x] 5.1 本文件勾选与执行留痕（版本核实结果、结构等价实录指针）
- [x] 5.2 2 笔提交（chore(ci) actions 升级与 runner 钉版 / docs(openspec) tasks 留痕）→ 切回 master 真 `--no-ff` 合并（禁 push，分支保留）→ 严格停步回报

## 0. 执行记录（子 agent 填写）
- 分支与基线：`feature/bump-ci-actions-and-pin-ubuntu` @ `master@c6470811d190498d8d7c8cbd93771d0b21d4e02a`
- 版本核实登记：
  - `runs-on: ubuntu-24.04` ×6：从 `ubuntu-latest` 迁移钉版，规避 2026-10-19 GitHub 迁移 Ubuntu 26 带来的运行环境漂移。
  - `actions/checkout@v5` ×6：主 agent 亲核锁定 v5（Node 24 运行时，且保持 PR 检出默认行为）。
  - `actions/setup-java@v5` ×5：主 agent 亲核锁定 v5（v5.5.0，Node 24 运行时，官方标注 migrate to v5，消除刷屏弃用警告）。
  - `actions/upload-artifact@v7` ×4：GitHub Releases 查实最新稳定 major 为 v7（最新发布 v7.0.1 于 2026-04-10，tag `v7` 指向 `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`），默认保持 archive 模式，参数兼容。
  - `actions/setup-node@v7` ×1：GitHub Releases 查实最新稳定 major 为 v7（最新发布 v7.0.0 于 2026-07-14，tag `v7` 指向 `820762786026740c76f36085b0efc47a31fe5020`），运行时切至 Node 24，参数兼容。
  - `pnpm/action-setup@v6` ×1：GitHub Releases 查实最新稳定 major 为 v6（最新发布 v6.1.0 于 2026-09-05，tag `v6` 指向 `f520eceda224fe1a4aed5a2a27a194379a409996`），支持 pnpm v10/v11/v12，参数兼容。
- 结构等价实录位置：
  - diff filter 实录：`git diff -U0 .github/workflows/ci.yml | grep -E '^[+-]' | grep -v -E '^\+\+\+|^\-\-\-' | grep -v -E 'uses:|runs-on:'` 过滤输出为空，违规行数 0。
  - 改动行数统计：23 insertions, 23 deletions，总计 46 处变更行，全部命中 `uses:` / `runs-on:`。
  - YAML 校验：`python -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml', encoding='utf-8')); print('YAML_OK')"` 输出 `YAML_OK`。
  - 六 job 对照：build-and-gate (7/7), openapi-consistency-gate (4/4), static-analysis-gate (3/3), fail-fast-gate (4/4), frontend-quality (10/10), thread-pool-metrics-gate (6/6)，全部步数一致。
- 门禁实测数字：
  - `mvn -B -ntp test`：Tests run: 938, Failures: 0, Errors: 0, Skipped: 0（耗时 03:37 min）。
  - 四静态门禁（checkstyle / spotbugs / spotless / pmd）：Checkstyle 0 error, Spotless 872 files clean, SpotBugs 0 bug/error, PMD 0 violations（耗时 52.559 s）。
  - `check-test-baseline.sh`：surefire reports=165 tests=938 skipped=0；failsafe reports=24 tests=83 skipped=6；未带 `--update`。
  - 三守卫：`check-line-endings lf` 两文件通过；`check-write-set c647081` 严格 2 文件；`check-dirty` CLEAN。
  - 136 条门禁自测：`check-test-baseline-selftest.sh` 101/101 + `agent-helper-selftest.sh` 35/35 = 136/136 全绿。
- git 拓扑：
  - c647081 (master 基线)
  - 0712010 (chore(ci): 升级 actions 版本并钉住 ubuntu-24.04 runner（卡 P-r 阶段3）)
  - docs(openspec): 登记 P-r 任务留痕并完成 tasks 勾选（卡 P-r 阶段5）
  - Merge branch 'feature/bump-ci-actions-and-pin-ubuntu'（卡 P-r）
- 未跑项与假设：
  - 未跑项：failsafe IT（`mvn verify`，需 Docker）未重复跑；远端 CI（推送后触发）。
  - 假设：GitHub Actions runner 正常拉取各升级后 action 标签。
