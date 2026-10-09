# 提案：harness 评审残留治理收口（close-harness-review-residue，卡 P-af）

## 背景与动机

2026-09-30 harness 实践评审（docs/harness-summary-2026-09-30.md）保留 6 项发现。2026-10-09 立项前逐项实测处置状态：

| # | 发现 | 现状（2026-10-09 主 agent 实测） |
|---|---|---|
| 1 | work/ 草稿层吞没变更边界 | 半消解：.gitignore `work/*` 白名单框架已立（git 噪声已收敛），但物理层从评审时 471 项膨胀至递归 1796 文件（顶层 16 目录 + 551 文件），生产类 `.java.bak` 副本 ×2 仍在，且 `.trae/` `.workbuddy/` `.trae-tmp/` 三个 IDE/agent 资产目录未被忽略（主树 untracked 噪声 5 项中占 2 项） |
| 2 | ci.yml 头注口径过时 | 仍开：头注自述「截至 2026-09-29 共两次且均 failure、CI 首绿尚未发生」（L4-L14），实测已常态全绿（最近锚点 Run 37944875404 @ b47593c，6/6，2026-10-09）；且头注引用的 `docs/main-agent-execution.md` §18 已随 §70.4 改名为 `main-agent-logbook.md`，引用悬空 |
| 3 | 主树落后 70 提交 + 同名文件冲突 | 已消解（§70.4：detach 至最新 master + 日志改名 main-agent-logbook.md） |
| 4 | 跨工作树同步纪律无承载 | 已消解（owner 已更新 AGENTS.md「工作树拓扑与同步纪律」节） |
| 5 | .qoder/worktrees 快照不可见堆积 | 仍开：14 个 `agent-general-purpose-*` 目录各带一份与根 AGENTS.md 逐字节漂移的副本，不在 `git worktree list`，对 git 与资产盘点双重不可见 |
| 6 | openspec/ 与 spec/ 双轨权威归属未决 | owner 拍板项，本卡不碰（继续挂账） |

owner 2026-10-09 拍板：按方案 A 立项工程治理卡，一次收口 #1 残留 / #2 / #5 三项。

## 变更内容

1. **.gitignore 增段（#1 残留）**：既有 `# ---- IDE ----` 段 `.qoder/` 之后追加 `.trae/`、`.trae-tmp/`、`.workbuddy/` 三行；`work/*` 白名单框架逐字零改动；
2. **主树 work/ 物理治理（#1，untracked 面，归档不删）**：三分处置——白名单三类与被已合入卡引用的证据原位保留 → 未引用废料归档移动至 `work/_archive-P-af/`（移动可逆，manifest 全表留证）→ 删除类（`.java.bak` ×2、`.qoder/worktrees` ×14）单列授权清单，本卡一律不删，停步请求 owner 授权；
3. **ci.yml 头注口径修正（#2，comment-only）**：重写 L4-L14 注释叙事为实测口径（Run 锚点以执行时 `gh run list` 实测为准；历史两次 failure 保留为史实；「合并前真实验收边界 = 本地 `bash scripts/merge-gate.sh`」表述保留；execution.md §18 引用改指 logbook.md §18）；YAML 结构（name/on/jobs/steps）逐字节零改动；
4. **AGENTS.md L67 一行口径修正**：主协作树角色行对齐执行文档双轨现实——主树承载 `docs/main-agent-logbook.md`（实时执行日志，未跟踪）与 `work/`；`docs/main-agent-execution.md` 收敛为 master 上的方法论手册；其余文字逐字保留；
5. **评审摘要入库（立项内补充，owner 审阅可裁剪）**：`docs/harness-summary-2026-09-30.md` 随卡入 master——本卡收口的发现清单自身仍仅存于主树 untracked 区（主树近期两度 detach/收拾，有丢失风险），入库时同步更新其 untracked 自述措辞，其余内容逐字保留。

## 不做什么

- **不删除任何文件**（零删除铁律：`.java.bak` ×2 与 `.qoder/worktrees` ×14 仅落证清单请求授权，删除为授权后另行动作）；
- **不动 openspec/ 与 spec/ 双轨归属**（发现 #6，owner 拍板项挂账）；
- **不动 work/ 白名单框架与 `work/_stale-openspec-drafts/`**（§70.4 attic 原位保留）；
- **不改 ci.yml 任何 YAML 结构**（name/on/jobs/steps 逐字节不动，仅头注注释行）；
- **不把 docs/main-agent-logbook.md 入 .gitignore 或入库**（实时日志维持未跟踪惯例，归属 owner 后续拍板）；
- **零 src/、零 frontend/、零 pom.xml、零 scripts/、零 db/migration、零测试文件改动**（不适用红测试先行，以现状实测清单替代前置取证）；
- **不执行 git push**（owner 授权后主 agent 执行）。

## 影响面

- 写集 7 tracked 文件：`.gitignore` / `.github/workflows/ci.yml` / `AGENTS.md` / 本卡三件套（proposal.md、tasks.md、specs/harness-debt/spec-delta.md）/ `docs/harness-summary-2026-09-30.md`；另有主树 work/ 内部 untracked 物理移动（不入 git）；
- 基线锚：master@b47593c（P-ae 收口笔后，CI 第 27 轮全绿 Run 37944875404）；
- 测试基线：surefire 1075（0 失败 0 跳过）/ failsafe 27 类 98 例 6 跳——**零变化为本卡硬验收**（test-baseline.txt 不 `--update`）；
- 无新权限号、无新依赖、无 DDL、无 API/OpenAPI 变化、无真实外呼。
