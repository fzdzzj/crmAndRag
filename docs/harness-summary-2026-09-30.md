# Harness 实践评审总结（crmAndRag · 2026-09-30）

> 由 `/better-harness` 生成的可读版摘要。完整交互式报告：
> `C:\Users\fzdzzj\AppData\Local\Temp\bh-run-20260930\report.html`（自包含 HTML；机器事实：同目录 `findings.json`，渲染已通过 `--validate` 全部检查）。
> 本文档为持久留档；temp 目录若被清理，以本文件为准。

## 评审方式与证据边界

- 三条独立证据 lane 并行产出候选：Session（codex 会话事实）、Project Harness（仓库五维检查）、Agent Customize（配置资产基线），lead 逐条亲验后定级；支持轨道选定 **Operationalize（1→60）**。
- **provider=codex**（当前会话为 ZCode，不在 CLI 支持列表，owner 拍板改用 codex）；node v22.19.0 低于 CLI 声明范围（>=22.20.0），owner 授权作为显式偏差继续。窗口 2026-08-31..09-30，normal 深度。
- **会话面首次非零**：32/32 会话全量进入分析（上轮 09-21 为 0，边界声明后的采集修复已发生）；151 个 Task Episode，但候选仅 emit 7 个（预算截断，约 2.3% 抽样）——行为侧结论全部低置信。
- git 历史窗口实测仅 16 天（90/180 窗口同源），热点表述均加时限。
- Memory / user-home 未授权读取（0 计数不得读作"零条"）。
- lead 关键断言均经有界直读复核：ci.yml 头注、`git remote -v` / `git ls-remote`、work/ 未跟踪计数、AGENTS.md 工作树段（两路独立实读）、`.qoder/worktrees` 计数。
- 评审 lane 本身对项目零改动；文末附录为同会话先行的项目状态实测。

## 五维评分（Loop Effectiveness，满分 100）

| 维度 | 分数 | 一句话结论 |
|---|---|---|
| 任务理解 | 55 | AGENTS.md 路由质量高，但 work/ 草稿层吞没变更边界、双轨权威自认未决、检出落后权威 master 70 提交 |
| 可控执行 | 68 | 环境入口齐备、脚本可发现可调用；零 agent 层资产属口径内现状；权限边界 Unobserved |
| 改动验证 | 68 | 三层验证轨与只许下调的阈值台账声明完备；会话侧验证以浅检查为主，76 个 Episode 闭环 0（低置信） |
| 可靠交付 | 55 | 真实验收边界=本地 merge-gate 一条命令（三文件交叉一致），但后端合入全链无机械触发 |
| 经验沉淀 | 45 | 有界评审完成；工作树同步纪律两 Episode 重复而无持久承载；无 ledger，纵向验证 Unobserved |

## 保留的 6 项发现

| # | 级别 | 发现 | 关键证据 |
|---|---|---|---|
| 1 | **Medium** | work/ 草稿层把变更边界吞成 critical 假信号 | 479 个未跟踪变更文件中约 470 个在 work/（复核 471，约 7.9 万行），src/main 与 frontend/src 真实变更为 0，diffSeverity=critical 全由草稿造成；含生产类 `.java.bak` 副本；ignoredCount=0，无任何 ignore/治理约定 |
| 2 | **Medium** | 合入门禁仍是程序性边界：后端合入没有会自己变红的触发通道 | ci.yml/AGENTS.md/merge-gate.sh 三文件交叉记载唯一验收边界=本地 `bash scripts/merge-gate.sh`；hooks 仅覆盖前端 pre-commit 转发器；ci.yml 头注自述"remote 为空、触发不执行"，与实测矛盾（`git remote -v` 显示 origin 已配置、`git ls-remote` 显示 master 已推送 0b70f2a） |
| 3 | Low | 本工作区检出落后权威 master 70 提交（0 领先） | `git rev-list` 实测；master==远端；untracked 的 `docs/main-agent-execution.md` 与 master 已入库同名文件冲突，会挡住切分支/合并 |
| 4 | Low | 跨工作树同步纪律靠用户口头补课，两轮独立会话重复且无承载 | 130 个候选 Episode 中 2 个不同上下文组同型请求（缺上游"修正轮"→指示 merge 同步）；AGENTS.md 无工作树拓扑/纪律段（两路独立实读）；项目 skills=0 |
| 5 | Low | 工作区内 14 个未注册快照目录各带一份已漂移的 AGENTS.md | `.qoder/worktrees/agent-general-purpose-*` ×14，与现行根 AGENTS.md 逐字节比较全 DIFFER，均不在 `git worktree list`；对 git 与资产盘点双重不可见 |
| 6 | Low | 规格双轨权威归属自认未决，最热检索链路每次改动双目录辨权 | AGENTS.md 第 64 行"待 owner 确认"；openspec/changes 与 spec/changes 并存；检索链为近 16 天头号热点（KnowledgeRetrievalServiceImpl 18 提交/churn 2441） |

每项的可执行修复提示词（含前置授权与验证步骤）在 `findings.json` 各条 `aiFixPrompt`，可直接用于 `/better-harness fix` 流程。

## 三个最小优先动作（Operationalize 轨道）

1. **立 work/ 治理约定**（#1）：`.gitignore` 增段或 AGENTS.md 治理段二选一，让 core 变更面恢复可判别；删除类动作单列授权。
2. **触发口径对齐 + 同步检出**（#2/#3）：ci.yml 头注改为实测远端口径；处理 untracked 副本后 fast-forward 到 master，消除双重复旧。
3. **沉淀工作树纪律**（#4）：AGENTS.md 增"工作树拓扑与同步纪律"节（含判别命令），下轮同类会话不再口头补课。

## 未保留的候选（对账台账）

- **验证闭环 0/151 reviewed-relevant check、E3 交付声明不可观察**：同一 collector 观察面盲区族（changes.files=0 投影失效、git 操作型变更不可投影），属证据边界非项目缺陷 → 计入"改动验证"维度摘要，不立案。
- **portfolio 截断（2.3% 抽样）、历史窗口 16 天**：校准项 → 全局降权、热点加时限。
- **frontend/AGENTS.md 未入盘点 ownerRoutes（封套 rules=1 vs 实存 2）**：盘点口径线索；文件存在且被根 AGENTS.md 与 ci.yml 双向路由确证 → 覆盖行按实读计 2，暂缓。
- **AGENTS.md 时点叙事漂移风险（84-110 行闭合段等）**：无确认过期条目（唯一确认实例 ci.yml 已入 #2）→ 暂缓。
- **上轮（09-21）已报告、本轮未复现**：提交钩子 core.hooksPath 归属冲突（本轮未复验安装状态，Unobserved）；静态分析被 pom skip（pmd 台账已归零、基线体系在位）；前端无 test 脚本（本轮实测 package.json 已有 `test: vitest run`）→ 视为状态变化，不重复立案。

## 附录：同会话项目状态实测（2026-09-30）

- `mvn -B -ntp test`：**750/750 全绿**（0 失败 0 跳过，1 分 55 秒），与 test-baseline 台账一致；PMD 台账维持 0 条。
- 前端门禁：`lint:check` 0 errors / 22 条风格 warning；`type-check:check`（vue-tsc 双配置）通过。
- Docker 本机未启动 → failsafe/IT（19 报告，Testcontainers）本地不可跑。
- 分支拓扑：4 个 worktree（本目录 feature/add-knowledge-admin-api、权威 master 在另一检出目录、2 个 detached A/B 树）；master 与远端一致（0b70f2a，已推送）。
- 在途规格：add-knowledge-admin-api 勾 17/19（余 6.1/6.2 为"停下报授权"的真实 embedding 成本项）；add-vision-pdf-ingest-pilot 16/16 已勾、待结案归档。

## 打开报告的说明

`report.html` 为自包含文件，浏览器直接打开即可。temp 目录有被系统清理的可能：本文件（`docs/harness-summary-2026-09-30.md`，已入库）与文中引用的发现内容即持久记录；需要机器可读版时可从本文件重建或重跑 `/better-harness`。
