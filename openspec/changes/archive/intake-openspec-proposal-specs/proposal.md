# 提案：openspec 提案物料口径统一入库与候选池重写归档（intake-openspec-proposal-specs）

## 为什么

2026-10-08 候选池重盘实测：14 个已合入变更目录的 proposal.md 与 specs/*/spec-delta.md 共 28 个文件 untracked（仅 tasks.md 入库），与 P-v（wire-circuit-half-open，F-4 定夺）及更早卡的全量入库口径漂移；候选池 docs/backend-optimization-candidates.md（untracked）7 项候选已全部闭环但内容停留在 2026-09-26 首版。owner 拍板（AskUserQuestion，2026-10-08）：口径统一入库 + 候选池重写归档一并随卡入库。

## 做什么

1. 28 个历史文件纯 git add 入库（内容零改动，add 前 sha256 留证）。
2. docs/backend-optimization-candidates.md 按重盘结论重写：保留首版 §1 方法论原文、7 项闭环归档表（附变更 id 与实测证据）、现行挂账（hooks 接受现状关闭 / 96008 维持预留 / NCSS 约束 / replay-enabled 费用红线 / 三套自测 raw 归档习惯 / 三件套口径）、后续待取证方向。
3. 本卡三件套随卡入库（P-v 先例）。

## 不做什么

- 零 Java 代码、零 frontend、零迁移、零依赖（src/ 与 frontend/ diff 必须为空）。
- 不改任何已入库文件内容（28 个文件纯 add；F-6 挂账经亲读核实 P-v 契约 6 已是「未到期 OPEN」措辞，无需修订）。
- work/ 执行留痕不入库（惯例不变）。
- 不动测试基线台账（surefire 1019 不变）。

## 影响

纯文档治理：本卡合入后 openspec/changes/ 下零 untracked spec 文件；候选池从「待验证清单」转为「闭环归档 + 现行挂账」；后续卡三件套口径回到全量入库。
