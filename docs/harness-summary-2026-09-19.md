# Harness 实践评审总结（crmAndRag · 2026-09-19）

> 由 `/better-harness` 生成的可读版摘要。完整交互式报告：
> `.qoder/better-harness/2026-09-19/121010-crmandrag/report.canvas.tsx`（机器事实：同目录 `findings.json` / `canvas.json`）。

## 评审方式与证据边界

- 三条独立证据 lane 并行产出候选：Session（qoder 会话事实）、Project Harness（仓库五维检查）、Agent Customize（配置资产基线），lead 逐条亲验后定级。
- **session-limited 评审**：2026-08-20..09-19 窗口内 0 个可分析会话（采集源根仅 1/5 存在），所有行为侧结论仅以项目内静态与文档证据为限；本轮零命令执行，任何"绿/红"均未实测。
- 关键断言（V27 版本表、.env 键存在、四套基线数字、%X{ 缺失、.gitignore 行内注释等）均经 lead 用文件/行号复核。

## 五维评分（Loop Effectiveness，满分 100）

| 维度 | 分数 | 一句话结论 |
|---|---|---|
| 任务理解 | 55 | 路由目标真实存在，但权威上下文本身含多处已证实的过期事实 |
| 可控执行 | 45 | 环境入口声明性；标准 verify 命令默认可达付费外发，防线只有散文规则 |
| 改动验证 | 52 | 分层测试与 fail-closed 闸门是真实机制，但当前变更集的迁移门禁失明 |
| 可靠交付 | 48 | 真实验收边界不可观察；本地合入无任何机械 backstop，全靠 agent 自报 |
| 经验沉淀 | 40 | 有界评审完成，但会话证据通道不可用，重复流程/学习机会无法裁决 |

## 保留的 9 项发现

| # | 级别 | 发现 | 关键证据 |
|---|---|---|---|
| 1 | **High** | 标准验收命令 `mvn verify` 默认可达付费外发模型调用 | `ModelProviderImplDashScopeIT:91` 仅在 key 缺失时跳过，仓库根 `.env` 存在 `DASHSCOPE_API_KEY` 键名；同仓 `RagRealRetrievalBenchmarkIT:134` 已有 `RAG_BENCHMARK_REAL=1` opt-in 先例 |
| 2 | Medium | 迁移链门禁对新增 V27 失明：版本表未同步 + 无 Docker 跳过 + CI 下限遮蔽 | `FlywayMigrationIT:38-39` EXPECTED_VERSIONS 止于 26，而 `V27__knowledge_admin_permission_seed.sql` 已在迁移目录；`ci.yml:66` 自陈 13 为"无 Docker 下限" |
| 3 | Medium | 三段门禁与真实合入路径不同路，合入接受完全依赖 agent 自报 | 本 clone 无 remote、无 hooks；30 天内 40 次本地 `--no-ff` 合入；HANDOFF §6.2 自述发生过"报完成但未提交" |
| 4 | Medium | traceId 进 MDC/审计表/响应头但从不进日志行，与冻结 spec 断言相反 | 全 `src/` 无 `%X{`，无 logback 配置；`spec-delta.md:20` 却把"日志携带 traceId"写成已实现 |
| 5 | Medium | 入口文档四套互相矛盾的测试基线，被授权"必读"的 runbook §6 已过期 | ci.yml=657/13 vs HANDOFF=649 vs git-workflow=473 vs runbook=467（且写成等式）；"工作树干净"vs 实测 23 改+约 300 未跟踪 |
| 6 | Medium | 根 AGENTS.md 与现状多处不符：号段表落空、冻结上界过期、前端无路由 | `AGENTS.md:47` V4x/V5x/V6x 号段无任何对应文件；`:89` "V1..V26 禁改"而 V27 已存在；零次提及 frontend/pnpm/openspec/HANDOFF |
| 7 | Low | `.gitignore` 行内 `#` 注释不生效，前端工具资产与 382KB 临时件实际未被忽略 | 末行 pattern 串整体失效；`git status` 实测 `?? frontend/.claude/`、`openapi.yaml.tmp/.bak` |
| 8 | Low | 无 Docker 机器上 `mvn verify` 以硬报错收尾，本地第二层反馈信号失真 | `AbstractMySqlIT:22-31` 静态块直接起容器，无 `assumeTrue` 守卫（同仓 FlywayMigrationIT:54 有先例） |
| 9 | Low | 本工作区 30 天窗口采集不到任何会话，行为侧与学习回路评审被阻断 | evidence-bundle session lane eligible=0、omitted 全 0，warningCodes 含 disabled-source-root |

## 三个最小优先动作（Operationalize 轨道）

1. **堵外发口子**（#1）：给 DashScope IT 加 `RAG_BENCHMARK_REAL` 式显式 opt-in 门控 + AGENTS.md verify 行就近告警。
2. **修迁移门禁同轮契约**（#2）：V27 登记进 EXPECTED_VERSIONS，并把"新增迁移必须同轮更新版本表"写进 `openspec/git-workflow.md §4`。
3. **同步权威文档**（#5/#6）：以 `ci.yml` 为唯一数字真相源做引用式改写；修订 AGENTS.md 号段/冻结上界并补前端与 openspec 路由。

每项的可执行修复提示词（含验证步骤与边界约束）在 `findings.json` 各条 `aiFixPrompt` 中，可直接用于 `/better-harness fix` 流程。

## 未保留的候选（对账台账）

- `frontend/` 下 AGENT/AGENTS/CLAUDE 三份指南 canonical 不明：正文未打开，仅 hash/行数差异 = 相似度线索，无观察到的后果 → 暂缓。
- CI `frontend-quality` job 当前会因 `package.json` 未跟踪而失败：在途提案的暂态，非持续缺陷 → 暂缓。
- tracked 文档无应用启动命令：runbook §2 有部分建库/启动路由，无观察后果 → 仅计入"可控执行"维度摘要。

## 打开 Canvas 报告的说明

若插件视图提示"文件不存在或无法访问"：多为渲染期间 staging 目录被替换导致的旧句柄失效——关闭该"插件视图"标签页，从文件树重新双击打开 `report.canvas.tsx`（或直接点视图里的"重试"）。三个产物文件均已验证存在且可读。
