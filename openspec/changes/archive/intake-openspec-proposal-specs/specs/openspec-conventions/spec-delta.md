# 增量契约规范：openspec 物料入库口径（intake-openspec-proposal-specs）

### 契约 1：三件套完整入库（Full Triple Intake）

- **GIVEN** 任一已合入 master 的变更目录 `openspec/changes/<change>/`；
- **THEN** proposal.md、tasks.md、specs/*/spec-delta.md 三件套全部 tracked；本卡合入后 `openspec/changes/` 下零 untracked spec 文件。

### 契约 2：历史物料零改动入库（Zero-Modification Intake）

- **GIVEN** 28 个 untracked 历史文件（14 目录 × proposal.md + specs/*/spec-delta.md）；
- **WHEN** 入库；
- **THEN** 内容与入库前工作树逐字节一致（add 前 sha256 留证，复核复算），无任何内容修改。

### 契约 3：候选池重写归档（Candidates Rewrite-to-Archive）

- **GIVEN** `docs/backend-optimization-candidates.md`；
- **THEN** 重写后保留首版 §1 证据等级与共通验收方法论原文；§2 闭环归档表附落地变更 id 与 2026-10-08 重盘实测证据；§3 现行挂账含 hooks 接受现状关闭、96008 维持预留、NCSS 149/150 约束、replay-enabled 费用红线、三套自测 raw 归档习惯、三件套口径；§4 后续方向标注「待取证，非实施授权」。

### 契约 4：零代码零台账（Zero Code, Zero Baseline Change）

- **THEN** `src/` 与 `frontend/` diff 为空；测试基线台账零变动（surefire 1019 逐字不变）；`work/` 保持 untracked；其他既有 untracked 项零触碰。

### 契约 5：后续卡口径（Forward Convention）

- **GIVEN** 本卡合入后的新变更卡；
- **THEN** openspec 三件套随卡全量入库（回到 P-v 及更早先例），`work/` 执行留痕不入库。
