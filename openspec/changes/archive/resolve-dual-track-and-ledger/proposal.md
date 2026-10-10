# 提案：openspec/spec 双轨归属定夺与台账清账（resolve-dual-track-and-ledger，卡 P-ag）

## 为什么

- harness 评审（2026-09-30）6 项发现中仅剩 **#6（规格双轨权威归属自认未决）** 未闭合：AGENTS.md 写「与 spec/changes/ 的分工待 owner 确认」，最热检索链路每次改动双目录辨权。owner 2026-10-10 拍板「先拍板再清账（两项连做）」，双轨归属按推荐方案定夺（见任务 1.1），本卡即落地载体。
- **预注册勾格欠账 27 格**：P-ab（close-openspec-task-residue）7 格（3.4/3.5/4.1-4.5）、P-ac（add-per-kb-retrieval-strategy-override）7 格、P-ad（add-dynamic-config-key-tier-acl）6 格、P-ae（add-cost-key-approval-workflow）7 格——四卡均已合入 master 且 CI 全绿，仅差按 P-ab spec-delta 契约 5 的回补（「回补载体为 owner 指定的后续 master 前向提交（下一张审计卡或专门回补笔）」，本卡即该审计卡）。
- **归档堆积**：openspec/changes/ 下 26 个变更目录全部已合入、无悬空格（授权门控格带 P-ab 日期化注记者视为无悬空），零归档；changes/ 已无真正在途的活卡。

## 双轨归属定夺（owner 2026-10-10 拍板，推荐方案）

- **spec/changes/ = 平台总纲与冻结契约轨**：`add-crm-rag-fusion-platform`（proposal / contracts-frozen / db-table-coordination / design-decisions）为长期活文档，契约改动走受控解冻；其余 13 个目录（性能度量/修复类历史卡）为历史规格资产，**只读保留、不迁移、不再新加**。
- **openspec/changes/ = 逐卡变更唯一新卡轨**：新卡三件套一律在此立项，完工归 archive/。
- **检索链路辨权规则**：改契约先查 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（受控解冻）；功能/修复改动经 openspec 卡走。
- 依据（主 agent 2026-10-10 实测）：spec/changes 被 66 个文件引用、openspec/changes 被 54 个——合并单轨成本高且 contracts-frozen 受控解冻机制仍在活跃使用（P-ad `3fa9a33` 刚用过），故双轨分工合法化、零迁移。

## 做什么

1. **双轨归属落地**：AGENTS.md「在途变更规格」段「与 spec/changes/ 的分工待 owner 确认」改为上述定论；openspec 侧规则文件（project.md 或 git-workflow.md，以实测哪个承载归档/流程惯例为准）补双轨辨权一节。
2. **预注册勾格回补 27 格**：按 P-ab 契约 1 带证据留痕（勾选行下一行 6 空格 + 「补勾依据（2026-10-10 P-ag 逐格核验）」+ 可独立复跑的证据：合并节点双亲、分支在册、CI 轮次、执行文档/logbook 章节锚点）；断言语义冻结（P-ab 契约 6：只增不改）。
3. **归档整理 26 目录**：openspec/changes/ 现存 26 个变更目录全部 git（或 openspec CLI 既有惯例）移入 archive/；同步更新全仓文字引用（主 agent 实测 13 个文件引用待归档目录，其中 7 个为待归档目录自身内部交叉引用）；归档后 changes/ 仅剩本卡。

## 不做什么

- **不迁移 spec/changes 任何存量**（66 处引用成本，13 个历史卡只读保留）。
- **不动 optimize-project-file-list-auth-reuse 6.1**（授权门控格，P-ab 契约 3 永不虚勾；确认其日期化注记在位即可，不重复注记）。
- 不碰 `src/`、`frontend/`、`scripts/test-baseline.txt`、`pom.xml`、`db/migration`；零测试文件改动（surefire 1075 / failsafe 27 类 98 例 6 跳零变化为硬验收，台账不 `--update`）。
- 不 push（推送归 owner）；不删除任何文件（归档 = git mv，非删除）。
- 红测试豁免（纯文档零代码，P-z/P-aa/P-ab 先例）。

## 影响

- harness 评审 6 项发现全部归零，评审线收官。
- openspec 台账从「27 格预注册未回补 + 26 目录未归档」到「0 格悬空 + changes/ 只留在途卡」；双轨辨权有明文规则，后续会话不再口头补课。
- 写集：AGENTS.md、openspec 规则文件、4 张历史卡 tasks.md、本卡三件套、26 目录移动（~78 文件路径变更 + 内容引用同步 13 文件）——写集口径 = 修改型与移动型分开列账（check-write-set 锚 fd4ad62）。
