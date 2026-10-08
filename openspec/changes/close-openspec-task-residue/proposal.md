# 提案：openspec 历史卡未勾格逐格核验回补（close-openspec-task-residue）

## 为什么

P-aa（close-candidate-pool-residue）的 spec-delta 契约 4 明确：openspec/changes/ 下其他变更目录 tasks.md 的历史未勾项「不代勾、不改动（需 owner 逐格核验定夺，另行立项）」。2026-10-08 owner 拍板立项本卡。主 agent 在 master@191d56f 实测全量盘点：8 个 tasks.md 共 19 个未勾格，其中 16 格的动作已实际发生却未回勾（试点类 2：add-knowledge-admin-api 6.1/6.2，owner 指令「A」授权后由卡 P-j 执行合入；停步回报类 3：wire-circuit-dynamic-config 5.2、optimize-knowledge-base-write-auth-batching 5.3、close-candidate-pool-residue 3.4；复核区只读惯例类 11：P-aa 4.1-4.5 与 intake 4.1-4.6，复核结论均已落盘执行文档 §66.3/§65），另有 2 格属授权/触发未发生（optimize-project-file-list-auth-reuse 6.1 opt-in 基准未跑已明列；archive/upgrade-semantic-chunking-and-index 4.4 存量迁移受费用红线约束）与 1 格自带行内定夺（archive/run-baseline-ladder 6.2）。台账要求诚实：已发生的不悬空、未发生的不虚勾、每格有日期化定夺。

## 做什么

- 16 格补勾：按 owner 既定格式在勾选行下一行加 6 空格留痕——「补勾依据（2026-10-08 P-ab 逐格核验）」+ 可独立复跑的命令与哈希（合并节点双亲、分支在册、提交 stat、CI 轮次与执行文档章节锚点）；add-knowledge-admin-api 组 6 状态注记随勾选语义更新。
- 2 格维持未勾 + 日期化核验注记（「维持未勾核验（2026-10-08 P-ab 逐格核验）：…」）：授权/触发未发生的格子永不虚勾。
- 1 格不动：run-baseline-ladder 6.2 已自带行内定夺「触发条件不成立，本条不执行」，不重复注记。
- 本卡三件套（proposal.md / tasks.md / specs/*/spec-delta.md）tracked 入库（承 intake-openspec-proposal-specs 契约 5）；work/ 执行留痕不入库。

## 不做什么

- 不改任何历史记录文本与断言语义（只增勾选与留痕/注记行；组状态注记随勾选语义更新）。
- 不虚勾任何授权门控格（费用红线：真外呼、全量 reingest 未授权永远未勾）。
- 不碰 `src/`、`frontend/`、`scripts/test-baseline.txt` 与 work/ 既有留痕；不改测试基线。
- 不 push（推送归 owner）。
- 红测试豁免（纯文档零代码，P-z/P-aa 先例），最强不变量 = surefire 1019 逐字不变。

## 影响

openspec tasks.md 台账从「19 格悬空」到「0 格无定夺悬空」；P-aa spec-delta 契约 4 的「另行立项」条件由本卡满足并闭环；自本卡起新卡复核区与停步格的回补机制由本卡 spec-delta 契约 5 定夺（预注册注记的未勾格不构成悬空）。
