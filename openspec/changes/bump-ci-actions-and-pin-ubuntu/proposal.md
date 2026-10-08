# 提案：CI 依赖卫生——actions 版本升级与 runner 钉版（bump-ci-actions-and-pin-ubuntu）

## Why
- **硬时限**：`ubuntu-latest` 将于 2026-10-19 起迁移到 Ubuntu 26（GitHub runner-images issue #14748，Ubuntu 26 已 2026-09-17 GA）。当前连续 12 轮远端 CI 100% 全绿的运行环境是 ubuntu-latest（= Ubuntu 24.04）；迁移后环境突变（预装工具链、系统库版本）可能直接打断全绿记录，届时需在压力下诊断。距时限仅约两周，预防性钉版成本远低于事后抢救。
- **平台弃用**：Node 20 已于 2026-09-23 在 GitHub Actions 下线，v4 系列 action（checkout/setup-java/upload-artifact/setup-node/pnpm/action-setup）被强制跑在 Node 24；`actions/setup-java` v4 官方已停止更新并明确迁移 v5。每轮 CI 的 annotations 持续刷屏三类弃用提醒，已在执行文档 §51.6 / §53.3 两次登记为依赖卫生债务。

## What Changes
- `.github/workflows/ci.yml`（全仓唯一 workflow，6 jobs）：
  1. 6 处 `runs-on: ubuntu-latest` → `ubuntu-24.04`（钉版，环境与既往全绿完全一致）；
  2. 6 处 `actions/checkout@v4` → `v5`；5 处 `actions/setup-java@v4` → `v5`；
  3. 4 处 `actions/upload-artifact@v4`、1 处 `actions/setup-node@v4`、1 处 `pnpm/action-setup@v4` → 各自最新稳定 major（执行侧核实 GitHub Releases 后钉版登记）。
- **结构等价红线**：diff 只允许触碰 `uses:` 版本引用与 `runs-on:` 值；job/step/name/run/with 结构与命令字节级不变。

## Impact
- **受影响面**：仅远端 CI 运行环境与 action 运行时；本地开发、Java 代码、前端源码、数据库、契约零影响。
- **受控写集**：2 tracked 文件（`.github/workflows/ci.yml` + 本变更 `tasks.md`）；零 Java、零前端、零 DDL、零新依赖；surefire 基线 938 数量不变。
- **验收**：本地门禁全套全绿（结构等价证明 + YAML 语法 + 938 单测 + 四静态门禁 + 三守卫 + 136 自测）；真验收在推送后——远端 CI 6/6 全绿且三类 deprecation annotations 清零。
- **非目标**：不迁移 Ubuntu 26（未来单独卡）、不改 CI 阶段逻辑/门禁命令、不引入新工具。
