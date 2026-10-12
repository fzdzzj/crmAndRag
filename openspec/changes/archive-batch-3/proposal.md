# 变更提案：archive-batch-3 —— 已闭合卡第三批归档

复核档位（按 docs/review-intensity-policy.md D1 自 declare）：**C 档**（纯 openspec 归档批，无产品代码 / 迁移 / 权限 / 真跑）

## 一、现状与问题

`openspec/changes/` 在途区滞留 7 个已闭合卡目录（合并笔齐备、CI 全绿、复核终局），违反「完工归 `archive/`」纪律，干扰新卡检索：

1. `aichat-prompt-template-integration`（合并笔 f695a6c，CI 43 全绿）
2. `b2-suite-recalibration`（合并笔 3c798b7，CI 44 全绿）
3. `add-prompt-template-nesting`（合并笔 3c59120，CI 45 全绿）
4. `add-prompt-template-slots`（合并笔 f5dfddb，CI 46 全绿）
5. `calibrate-review-intensity`（合并笔 bf79e52，CI 47）
6. `archive-batch-2`（上批归档卡自身，闭合后未自归档）
7. `prompt-template-enhance`（早期闭合卡，历次待办池在列）

## 二、方案

**建议 D1 · 纯移动归档**：`git mv` 七目录 → `openspec/changes/archive/`，零内容改动。git mv 保 rename 追踪；提交用常规暂存（禁 `git commit -- <pathspec>` 姿态，历史坑：pathspec 提交会把 rename 拆成 delete+add）。

**建议 D2 · 本卡自身留待下批**：`archive-batch-3` 卡目录留 in-flight 区，随下批归档（对齐 archive-batch-2 先例——归档卡自身不同批自归）。

**建议 D3 · C 档全流程**：执行子 agent 实施后，指导侧按新政策 C 档核验——单遍逐格 + `scripts/tests/review-mechanical-check.sh` 出表（新政策首次应用于归档批）。

**建议 D4 · 门禁下界**：`mvn -B -ntp test` 全绿且 surefire 零增（零 Java 构造即证）+ `bash scripts/check-test-baseline.sh` 只读不红。

## 三、影响面与边界

- 改动面：仅 `openspec/changes/` 下 7 目录路径移动 + 本卡目录两件（proposal/tasks）；无 Java、无迁移、无契约、无权限。
- 边界：不动 `archive/` 既有 58 目录；不改任何归档卡内容文件（纯移动）；AGENTS.md 零改动；零外呼。
- 顺序纪律：排在 calibrate-review-intensity 闭合之后（已满足）。
