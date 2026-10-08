# 增量契约规范：openspec 历史卡未勾格逐格核验回补（close-openspec-task-residue）

### 契约 1：补勾必须带证据留痕（Evidence-Annotated Retick）

- **GIVEN** 历史卡 tasks.md 的某未勾格，其对应动作已被实测证明实际发生（授权、合并、停步回报、复核结论等）；
- **WHEN** 本卡对其补勾；
- **THEN** 勾选行下一行必须附 6 空格缩进留痕：`补勾依据（日期 P-ab 逐格核验）` + 可独立复跑的命令与哈希（合并节点双亲、分支在册、提交 stat、CI 轮次等）；禁止无证据补勾，禁止以代码推导或记忆冒充实测。

### 契约 2：维持未勾必须日期化定夺（Dated Non-Retick）

- **GIVEN** 授权或触发条件未发生的未勾格（opt-in 基准未跑、费用红线未授权）；
- **WHEN** 本卡完成逐格核验；
- **THEN** 该格保持未勾，并在下一行附日期化注记 `维持未勾核验（日期 P-ab 逐格核验）：理由`；已自带行内定夺的格子（如 run-baseline-ladder 6.2）视为已满足、不重复注记；禁止任何「无定夺悬空」的未勾格留存。

### 契约 3：授权门控格永不虚勾（Authorization-Gated Never Ticked）

- **GIVEN** 受费用红线约束的格子（真外呼、全量 reingest、生产开关）；
- **WHEN** owner 授权未发生；
- **THEN** 无论过去多久该格永远维持未勾；授权发生后按契约 1 流程补勾。

### 契约 4：回补授权载体（Retrofit Authorization Vehicle）

- **GIVEN** 对历史卡 tasks.md 的任何勾选状态变更；
- **THEN** 必须以 owner 拍板的专门卡为授权载体（本卡即 P-aa spec-delta 契约 4 所指「另行立项」的落实），禁止任何卡顺手代勾；本卡三件套 tracked 入库（承 intake-openspec-proposal-specs 契约 5），work/ 执行留痕不入库。

### 契约 5：复核区与停步格的回补惯例（Review-Section Retrofit Convention）

- **GIVEN** 自本卡起新建卡片的 tasks.md 复核区（复核 agent 只读，不改文件）与严格停步格（停步回报为执行终态，无法在回报前自勾）；
- **THEN** 两类格子的勾选不在本卡内完成，卡片内必须预注册注记（格式见本卡 tasks.md 3.5 行下注），其回补载体为 owner 指定的后续 master 前向提交（下一张审计卡或专门回补笔）；带预注册注记的未勾格不构成悬空，不触发再审计。

### 契约 6：断言语义冻结（Assertion Semantic Freeze）

- **GIVEN** 本卡对历史卡文件的一切编辑；
- **THEN** 只增不改：勾选框状态、留痕/注记行、随勾选语义更新的组状态注记（如 add-knowledge-admin-api 组 6 的 `(pending…)` → `(completed…)`）之外，历史行原文逐字保留；不改任何断言语义、不重写既有执行记录。
