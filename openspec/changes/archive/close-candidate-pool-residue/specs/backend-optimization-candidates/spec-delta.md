# 增量契约规范：候选池待取证方向收口（close-candidate-pool-residue）

### 契约 1：待取证方向必须日期化证据定夺（Evidence-Dated Disposition）

- **GIVEN** 候选池 `docs/backend-optimization-candidates.md` §4 的任一「待取证方向」；
- **WHEN** 主 agent 完成取证；
- **THEN** 该方向被改写为带日期、带实测证据（命令与基线节点 hash）的定夺结论——清零关闭 / 已落地注记 / 维持阻塞并注明前置条件；不得停留在无证据的待办措辞，也不得未取证先下结论。

### 契约 2：零代码零台账（Zero Code, Zero Baseline Change）

- **THEN** `src/` 与 `frontend/` diff 为空；测试基线台账零变动（surefire 1019 逐字不变）；`work/` 保持 untracked；其他既有 untracked 项零触碰。

### 契约 3：本卡三件套随卡入库（Full Triple Intake）

- **THEN** proposal.md、tasks.md、specs/*/spec-delta.md 三件套 tracked 入库（承 intake-openspec-proposal-specs 契约 5）；`work/` 执行留痕不入库。

### 契约 4：范围外未勾项不代勾（No Unauthorized Retick）

- **GIVEN** openspec/changes/ 下其他变更目录 tasks.md 的历史未勾项（复核区未勾、授权门控项、可选未跑项）；
- **THEN** 本卡不代勾、不改动（需 owner 逐格核验定夺，另行立项）；本卡 tasks.md 勾选仅反映本卡执行实况。
