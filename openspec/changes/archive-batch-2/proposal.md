# proposal — archive-batch-2（归档批次收尾：5 卡目录 + MINOR 订正）

## Why

openspec/changes/ 逐卡轨制要求完工卡归档（changes/ 仅留在途卡）。当前 5 张已闭合卡目录滞留：trace-citation-redundancy、harden-gold-marker-tearing、fix-citation-grading-overlap、add-prompt-template-admin、archive-closed-changes（自身收尾，当时在途不可自归档）。另 fix-citation-grading-overlap 两轮复核留下 7 条 MINOR 文档精度疵，需随批次订正。

## What Changes

1. **归档**：上述 5 目录 `git mv` 入 `openspec/changes/archive/`（57→62，纯移动零内容变更）；本卡自身目录留待后续批次（在途卡不可自归档）。
2. **MINOR 订正（fix-citation-grading-overlap/tasks.md，4+1 处）**：
   - :3 顶部红线「旧锚点 17 文件」→「20 文件」（提案期口径，目录已演进为 20）；
   - :10 任务 1.4 同款 17→20；
   - :32 §0 红测留痕「`MVN_EXIT=1`」→「BUILD FAILURE（COMPILATION ERROR）」，注明实际日志无 MVN_EXIT 字面标记（轮 1 复核 MINOR）；
   - :48 §0 同款订正（组 4 红测留痕）；
   - §0 尾部补两行披露：work/ 域历史日志层 3 条（_green-run1.log 实为 span 校准前红跑、_green-run2.log spotless 925 系临时探针在场时点、改 IT 文件的第二轮 spotless:apply 归一只动本卡文件）——work/ 未跟踪域按原样保留，以本披露留痕。
3. 无代码变更、无 DDL、无依赖变更；surefire 基线不动（188/1124/0 + 28/101/6）。

## Impact

- 写集：5 目录纯移动（git mv 记 rename）+ tasks.md 5 处订正 + 本卡三件套。
- 门禁预期：纯移动+文档笔，merge-gate 应全绿；check-write-set 语义下 git mv 计入写集（先例 archive-closed-changes 13 files +83 同构）。
