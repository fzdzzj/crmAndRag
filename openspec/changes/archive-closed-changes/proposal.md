# 提案 — archive-closed-changes（归档三张已闭合卡）

## 为什么

P-ag（resolve-dual-track-and-ledger）定夺的轨制：`openspec/changes/` 仅留在途卡，完工归 `archive/`。实测（2026-10-10）changes/ 下滞留 3 个**已闭合**目录：

| 目录 | 卡 | 闭合证据 |
|---|---|---|
| resolve-dual-track-and-ledger | P-ag | CI 第 29 轮（Run 38024881097）6/6 绿 |
| add-self-rag-reflection | P-ah | CI 第 30/31 轮绿，D3 真跑闭合 |
| expand-rag-benchmark-mismatch | P-ai | CI 第 32/33 轮绿，证伪结案闭合 |

## 做什么

`git mv openspec/changes/<dir> openspec/changes/archive/<dir>` ×3，一笔中文提交。零代码改动、零 DDL、零新依赖、不碰 src/frontend/pom/scripts。

## 已核实的前置事实（指导侧 2026-10-10 实测）

1. 主树（detach @ 05a0003）`git status --short -- openspec/changes/` 干净，三目录无未提交物。
2. `git grep "openspec/changes/(三目录名)"` 在 tracked 全量唯一命中 = P-ai HANDOFF.md 自引用自身路径（随目录一起移动，无害）。执行方须在权威树复测同口径。

## 边界

- 本卡自身目录（archive-closed-changes）完工后亦应归档，但**不能在本卡内归档自己**（在途）；留待后续收尾动作，在 HANDOFF 注明。
- archive/ 下现存 54 目录，执行方须实测确认无同名撞目录。

## 验收口径

- 三目录在 archive/ 下原样存在，`git log --follow` 可追溯；changes/ 仅剩本卡目录。
- surefire 计数不变（纯文档移动），`bash scripts/merge-gate.sh` 全 PASS。
- 不合并、不 push（owner 显式授权后另行执行）。
