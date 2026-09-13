# HANDOFF — CRM+RAG 融合平台交接文档

> 给接手本项目的人/agent。目标：读完能**理解**这个项目是什么、为什么长这样、现在什么状态、坑在哪、下一步做什么。
> 配套深度文档在 `spec/changes/add-crm-rag-fusion-platform/`（见 §4 文档地图）。本文是"理解入口"，不是替代品。

---

## 0. 30 秒概览

- **是什么**：一个 Java/Spring Boot **单体**，把原 CRM 系统与原 RAG 系统融合成一个产品。
- **架构定位（关键，D11）**：**不是**"两个对等系统拼接"，而是 **CRM 为基座** + **AI 助手吸收 RAG 的对话能力** + **知识库能力移植为 `com.slz.crm.knowledge` 模块**。原 RAG 的独立对话层（`chat_*` 表 / `RagChatPipeline` / 匿名问答）**已丢弃**，对话统一走 CRM 的 `ai_session`/`ai_message`。
- **当前状态**：`master` = 完整融合产品 + **检索链路优化 5 提案已全部落地**（混合检索/上下文压缩/语义切分/查询增强，见 §2.5 与 `openspec/`）；**surefire 590 绿**、真库迁移链 V1..V23 已通；**未 push**（推送需显式授权）。
- **怎么建的**：OpenSpec 规范驱动 + **多 agent 并行**：Wave 0 基座串行（契约冻结）→ Wave 1 四 lane 并行（A 数据权限/B 知识库/C 助手/D 治理）→ Wave 2 E 动态配置 → Wave 3 串行集成。理解这个波次结构对理解代码归属很重要（见 §1/§2）。

---

## 1. 心智模型：代码怎么分层

```
com.slz.crm
├── server/            # CRM 业务 + AI 助手
│   ├── ai/            #   助手：SSE 流、记忆、思考流、图文、接管、检索编排（吸收自 RAG）
│   ├── service/...    #   CRM 业务服务 + 数据权限（DataScopeServiceImpl + QueryWrapperAspect AOP）
│   └── mapper/        #   MyBatis-Plus mapper（CRM 表 + 知识库 7 表 + ai 表）
├── knowledge/         # 知识库能力（移植自 RAG）：document 解析/入库、vector(Qdrant/InMemory)、retrieval 检索管线、auth 授权、storage(MinIO/内存)、embedding
└── platform/          # 平台层
    ├── contract/      #   ★冻结契约（跨模块接口边界，见下）
    ├── config/        #   E 动态配置中心（DynamicConfigService 实现 + 超管管理 + 审计缝）
    ├── async/ trace/ resilience/ quota/ token/ audit/ health/ security/   # D 治理：线程池/MDC/韧性/配额/token计量/审计/健康/内容安全
```

### 1.1 冻结契约（`platform/contract/`）——最重要的边界
基座（Wave 0）冻结了 **17 个接口/record**（`UserContext`、`ModelProvider`、`CrmVectorStore`、`DynamicConfigService`、`SseEventName`、`SourceReference`、`TokenUsageRecorder`、`DataScope`…），后续修正轮又加了 `CrmVectorStoreHealth`、`BypassTaskExecutor`、`ModelCallOptions` 等（**权威清单见 `contracts-frozen.md`**）。
**为什么重要**：这些是当初多 lane 并行时的"接口合同"——各 lane 只实现/消费、不改签名。现在虽已合并成单仓，**改它们仍=契约变更**：要同步所有消费方 + `SseContractTest`（SSE 事件/payload）+ 前端。

### 1.2 五道跨模块 seam（接线点，改一边要想到另一边）
| seam | 定义方 | 实现方 | 消费方 |
|---|---|---|---|
| `KnowledgeRetrievalPort` | C(server.ai.port) | B(KnowledgeRetrievalServiceImpl) | C 助手检索编排 |
| `DynamicConfigService` | 契约 | E(platform.config) | B/C/D 各读取点 |
| `BypassTaskExecutor`(memoryBypassExecutor) | 契约 | D(platform.async) | C 记忆旁路 |
| `CrmVectorStoreHealth` | 契约 | B(Qdrant/InMemory) | D VectorStoreHealthIndicator |
| `DynamicConfigAuditRecorder` | E 审计缝 | D→E 桥(GovernanceDynamicConfigAuditRecorder) | E 配置变更审计 |

### 1.3 数据（Flyway，前向迁移、无自动 down）
`V1`(CRM 36 表基线) → `V3`(knowledge 7 表) → `V4`/`V4_1`(ai 记忆/聊天图片) → `V5`(governance) → `V6`(dynamic config；`sensitive` 保留字已就地修复——曾阻断真库迁移链) → `V21`(org 数据权限) → `V22`(chunk FULLTEXT ngram——稀疏召回路) → `V23`(parent_chunk_id——双粒度索引)。**MyBatis-Plus 是唯一持久化**（JPA/Hibernate 已移除）。回退靠备份，不靠 down 迁移。

### 1.4 认证与暴露面
- **Spring Security 已移除**，认证统一到 **CRM JWT 拦截器**；`UserContext`(userId/roleId/deptId/dataScope) 经 `UserContextHolder` 传递，跨域引用用 `userIdRef()`=`user:<id>`。
- **Actuator 由 `ActuatorProtectionFilter` 保护**（移除 Security 后的替代）：仅探针放行，其余需超管。readiness 组 = `readinessState,db,vectorStore,minio`。

---

## 2. 来龙去脉：为什么是现在这样（关键决策）

- **D11 架构重定位**：早期方案是"融合两个对等系统"，后改为"CRM 基座 + 助手吸收 RAG + 知识库移植"。这决定了：RAG 对话层丢弃、知识库是"被调用的能力"而非独立服务、助手范围扩大（思考/记忆/图文/接管）。**读任何代码前先记住这个定位**，否则会把 knowledge/ 误当独立服务。
- **技术栈收敛**：LangChain4j→**Spring AI**(spring-ai-alibaba/dashscope)；JPA→**MyBatis-Plus**；Spring Security→**CRM JWT**。
- **契约修正轮 1/2/3**（集成期补的接口缺口，见 `contracts-frozen.md` §13）：①补 `TITLE` 事件/`ModelProviderImpl`/`CrmVectorStoreHealth`/`BypassTaskExecutor`；②`ModelCallOptions`（中立模型调用选项，修 thinking 参数经 Provider 丢失）；③`ModelCallOptions` 承载工具回调（修工具调用绕过 ModelProvider）。**这三轮是"并行开发后接口对不齐"的产物**——接手改模型/工具链路时先看这里。
- **集成期查出的 4 类装配缺陷**（各 lane 单测全 mock、抓不到；靠新增的 `ApplicationContextSmokeTest`(H2 全上下文) 查出）：E 的 mapper 包未被 `@MapperScan` 覆盖 / `minioClient` @Bean 无条件构建(无凭据即炸) / D 两个类双构造器缺 `@Autowired` / readiness 组硬编 `minio`(in-memory 下无该贡献者)。**教训：多模块合并后必须有一个不依赖外部 infra 的全上下文冒烟测试。**

### 2.5 检索链路优化（2026-09，五提案已全部落地并归档）

以 rag 学习工作区的 17 方案差距分析立项，OpenSpec 六案归档于 `openspec/changes/archive/`（提案/任务/验收三件套）；`openspec/project.md` 是 17 方案处置总表，`openspec/git-workflow.md` 是 git 操作契约（分支/提交/CI 基线/成本闸门）：

| 能力 | 现状（动态配置键见 `docs/dynamic-config-keys.md`） |
|---|---|
| 混合检索 | 向量路 + **MySQL FULLTEXT ngram 稀疏路**（V22）+ **RRF 融合**（`fusion.mode`，默认 rrf；`weighted` = 升级前 0.7/0.3 图文融合） |
| 上下文组装 | `ContextBuilder` 邻居增强（`context.neighbors`，默认 1）+ rule 压缩（超 `context.token-budget` 才触发，默认 4096） |
| 切分与索引 | `chunking.strategy`：`fixed`（默认，与升级前等价）/`semantic`；V23 `parent_chunk_id` 父子块，`context.parent-expand` 父块展开 |
| 查询增强 | 多查询/HyDE/衍生问题三开关**默认全关**（fixtures 归因"词汇失配"不成立，未翻默认） |
| 质量评估 | `com.slz.crm.quality`：五类 18 条基准集 + 真检索 runner（`RAG_BENCHMARK_REAL` 门控）+ 四份基线 JSON |

基线阶梯结论（`docs/rag-quality/ladder-report.md`）：四跑质量锚点全稳（recall@k 0.9444 / MRR 0.8472 / citationPrecision 0.8056 无失效级回退）；chunking 跑 MRR 小降 0.028 但 citationPrecision +0.083（已记录、未翻默认）；fixtures 量级指标饱和，量化"提升"主张需更大数据量兑现。

---

## 3. 当前 git 状态与未决事项

- 工作树干净（仅两个未跟踪的 rag 工作区暂存件 `_rag优化交接.md`/`_vlm_transcribe.py`，属另一工作区，勿提交勿删除）；**未 push**（硬约束：推送需你显式授权）。
- **surefire 602 全绿（本地亲验）**；真库迁移链 V1..V25 已在本机 Docker 完整应用（FlywayMigrationIT 3 绿）。
- **WriteChainRegressionIT 双红灯已修复（V24 补 approval_attachment.uploader_id + 种子修正）**；**全量实体↔表列漂移审计（P1）已完成（audit-entity-table-drift 合入）**：新增 `SchemaDriftAuditIT` 永久门禁（真 MySQL CRITICAL 非空即 fail）根修 `V25__invoice_info_add_remark.sql` 补 `invoice_info.remark` 列，审计报告见 `docs/schema-drift-audit.md`（当前 CRITICAL=0 / WARN=5 / INFO=2，均只记录待授权）。
- **RAG 真检索基线已跑**：四份 JSON + `docs/rag-quality/ladder-report.md`。跑法铁律：`RAG_BENCHMARK_REAL=1` 且只 `-Dit.test=RagRealRetrievalBenchmarkIT` 过滤——`.env` 常备 key，全量 `mvn verify` 会连带其他 DashScope IT 真外发。
- CI：`.github/workflows/ci.yml` = surefire + failsafe 双口径回归门禁（基线数字以文件为准）+ 报告归档。
- **待授权遗留**：生产库全量 reingest（真实嵌入成本 × chunk 总量，`KnowledgeReingestRunner` 已实现）、多查询/HyDE 生产语料重评。

---

## 4. 文档地图（去哪找什么）

`spec/changes/add-crm-rag-fusion-platform/`：
| 文件 | 内容 |
|---|---|
| `proposal.md` | 为什么做、架构重定位(D11)、范围 |
| `design-decisions.md` | D1–D17 决策及理由（读代码前的"为什么"） |
| `contracts-frozen.md` | ★冻结契约权威清单 + 修正轮1/2/3 + 谁实现谁消费 |
| `tasks.json` | 18 任务分解（6 阶段） |
| `agent-execution-plan.md` | 多 agent 波次/并行 lane/文件归属/冲突规避 |
| `prompts/` | 各 lane/集成 的 agent 提示词（含 `_common-rules.md`） |
| `release-runbook.md` | 发布前检查/环境变量/生产强约束/灰度开关/4层回退/运维/权限矩阵 |
| `assistant-decision-tree.md` | 助手情况处理树 + SSE payload 示例 |
| `migration-inventory.md` / `migration-subtasks.md` / `db-table-coordination.md` | RAG 资产取舍、迁移子任务、表协调 |

`openspec/`（检索链路优化，已完成）：
- `project.md` —— 17 方案处置总表 + 硬约束（**改检索链路前必读**）
- `git-workflow.md` —— 分支/提交/合并/CI 基线/成本闸门契约
- `changes/archive/` —— 六案：add-rag-quality-baseline（评估基线）/ complete-hybrid-retrieval-and-rerank（混合检索+重排）/ add-context-compression-and-enrichment（压缩+邻居）/ upgrade-semantic-chunking-and-index（语义切分+双粒度）/ enhance-query-transformation（查询增强，默认关）/ run-baseline-ladder（基线阶梯五回）

代码入口：`src/main/java/com/slz/crm/{server,knowledge,platform}`；测试：`src/test/java/com/slz/crm/{unit,integration,contract,quality}`。

---

## 5. 接手后的第一步 / 常见任务怎么做

1. **先跑** `mvn test`（应 590 绿）+ 看 `git status`（工作树应干净，已知未跟踪件见 §3）。
2. **理解助手**：读 `server/ai/` + `assistant-decision-tree.md` + SSE 契约（`SseEventName` + `SseContractTest`）。改事件/payload = 契约变更，同步前端 + 测试。
3. **理解知识库**：`knowledge/` + `contracts-frozen.md §2`；检索管线 = 授权过滤→查询改写→向量/稀疏双路召回→RRF 融合→rerank→ContextBuilder 邻居增强+预算压缩（`KnowledgeRetrievalServiceImpl`；开关矩阵见 §2.5 与 `docs/dynamic-config-keys.md`）。
4. **改契约**：`contracts-frozen.md` 是权威；改 `platform/contract` 要同步所有消费方 + `SseContractTest`。
5. **发布/回退**：`release-runbook.md`（灰度开关、4 层回退、权限矩阵）。
6. **质量**：`com.slz.crm.quality`(RagQualityEvaluator) 是 RAG 评估 harness（五类 18 条基准集，recall/MRR/citationPrecision + JSON 报告）；真检索基准 `RagRealRetrievalBenchmarkIT` 走 InMemory+DashScope（跑法铁律见 §3），既有四跑基线在 `docs/rag-quality/`。

---

## 6. 坑与教训（接手必读）

1. **多模块合并后必须有 H2 全上下文冒烟测试**（`ApplicationContextSmokeTest`）——单测全 mock 抓不到装配缺陷（本项目查出 4 类）。
2. **子 agent/同事报"完成"要亲验** commit hash + `git status` 干净——曾有 lane 报完成但成果未提交。
3. **Flyway 前向、无 down**；回退靠备份。改表=加新迁移，不改旧迁移。
4. **SSE 是前后端唯一对接面**：12 事件名 + payload 字段冻结 + 断线续传(`id=<generationId>:<seq>`、重放≠重执行)；改它=改契约。
5. **数据权限靠 AOP**（`QueryWrapperAspect` + `DataScopeServiceImpl` 两重载），不是手写 where；超集不变量（同维度内就大不就小）。
6. **DashScope key 曾明文暴露 → 已建议轮换**；密钥只走环境变量/`.env`(gitignore)，不进仓库。
7. **V6 保留字教训**：列名 `sensitive` 撞 MySQL 8.0.36 保留字，曾让真库迁移链全灭（V1..V23 从未完整应用于真库、仅探针容器绿）——新迁移合入前至少真库跑一次 `FlywayMigrationIT`。
8. **基准真跑铁律**：`.env` 常备 DASHSCOPE key，全量 `mvn verify` 会连带真外发——跑基准只 `-Dit.test=RagRealRetrievalBenchmarkIT` 过滤。

---

## 7. 与 rag 学习工作区（`c:\Users\fzdzzj\Desktop\rag`）的关系

那是**另一个独立工作区**：一个"关于 RAG 的学习知识库"(rag-kb) + 它的抽取 pipeline，正在做 pipeline/RAG 优化（有自己的交接：`优化交接.md`，含 17 优化方案适用性映射）。
- **互参已兑现**：本项目检索链路优化正是以那边的 17 方案适用性分析立项（`openspec/project.md` 处置总表）；那边 B4（检索评估）借鉴了本项目 `RagQualityEvaluator` 的黄金集+指标+JSON 报告设计。
- **两者代码独立**，不要混淆仓库；根目录 `_rag优化交接.md`、`_vlm_transcribe.py` 是那边的暂存件，勿提交勿删除。
