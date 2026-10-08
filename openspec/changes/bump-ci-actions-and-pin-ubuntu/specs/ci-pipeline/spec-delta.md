# 增量契约规范：CI 依赖卫生——actions 版本升级与 runner 钉版（bump-ci-actions-and-pin-ubuntu）

## 1. 行为契约增量规范

### 契约 1：runner 环境钉版（Pinned Runner Environment）
- **GIVEN** GitHub 官方将于 2026-10-19 把 `ubuntu-latest` 标签迁移到 Ubuntu 26；
- **WHEN** 本变更合入后，远端 CI 任意 job 调度执行；
- **THEN** 全部 6 个 job 的 `runs-on` 为 `ubuntu-24.04`——运行环境与既往连续 12 轮全绿同源，标签迁移对流水线零影响。

### 契约 2：流水线结构字节级等价（Structural Byte Equivalence）
- **GIVEN** `.github/workflows/ci.yml` 含 6 jobs 及其全部 step；
- **WHEN** 执行 `git diff -U0` 审视本变更对 ci.yml 的全部变更行；
- **THEN** 变更行仅含 `uses:` 版本引用与 `runs-on:` 值两类；job/step/name/run/with 结构与命令字节级不变，三段门禁序列（surefire → failsafe → 回归基线）与六 job 判定逻辑零改动。

### 契约 3：action 运行时脱离弃用线（Off Deprecated Runtime）
- **GIVEN** Node 20 已于 2026-09-23 在 GitHub Actions 下线、`actions/setup-java` v4 停止更新；
- **WHEN** 本变更合入后的下一轮远端 CI 执行；
- **THEN** `checkout@v5` / `setup-java@v5` / `upload-artifact` / `setup-node` / `pnpm/action-setup`（各自核实登记的最新稳定 major）不再触发 Node 20 deprecation 与 setup-java v4 弃用 annotations；CI Run 的 annotations 面板三类弃用提醒清零。
