# HANDOFF — CRM+RAG 融合平台交接文档

> 给接手本项目的人/agent。目标：读完能**理解**这个项目是什么、为什么长这样、现在什么状态、坑在哪、下一步做什么。
> 配套深度文档在 `spec/changes/add-crm-rag-fusion-platform/`（见 §4 文档地图）。本文是"理解入口"，不是替代品。

---

## 0. 30 秒概览

- **是什么**：一个 Java/Spring Boot **单体**，把原 CRM 系统与原 RAG 系统融合成一个产品。
- **架构定位（关键，D11）**：**不是**"两个对等系统拼接"，而是 **CRM 为基座** + **AI 助手吸收 RAG 的对话能力** + **知识库能力移植为 `com.slz.crm.knowledge` 模块**。原 RAG 的独立对话层（`chat_*` 表 / `RagChatPipeline` / 匿名问答）**已丢弃**，对话统一走 CRM 的 `ai_session`/`ai_message`。
- **当前状态**：`master` = 完整融合产品 + **检索链路优化 5 提案已全部落地**（混合检索/上下文压缩/语义切分/查询增强，见 §2.5 与 `openspec/`）；**测试基线阈值只存放在 `scripts/test-baseline.txt`，裁决入口是 `bash scripts/check-test-baseline.sh`（本地与 CI 同一条命令；本文不复制数字，阈值禁止手改，历史阶梯见 `git log -p -- scripts/test-baseline.txt`）**；真库迁移链在本机 Docker 完整应用（该实测记录快照截至 2026-09-16 覆盖到 V26，V27 合入后未复验）；**schema 漂移 7 项遗留已全部定夺豁免**（Known/NEW 二分机制上线，见 §3）；**RAG 基准集已扩容 18→54 条（SUITE_VERSION 2.0）且 v2 锚点已跑**（见 §3）；**Java 静态分析四道门禁（checkstyle / spotbugs / spotless / pmd）已全部生效**——spotbugs 与 pmd 的 `<skip>` 均已删除，各带"pom 单一读者 + 入库台账 + 过期防呆脚本 + 能变红的自测"，口径与四个陷阱见 §3 末条与 `docs/migration-runbook.md` §6.7（**本文与 `ci.yml` 一律不复制条数**）；**未 push**（推送需显式授权）。
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
`V1`(CRM 36 表基线) → `V3`(knowledge 7 表) → `V4`/`V4_1`(ai 记忆/聊天图片) → `V5`(governance) → `V6`(dynamic config；`sensitive` 保留字已就地修复——曾阻断真库迁移链) → `V21`(org 数据权限) → `V22`(chunk FULLTEXT ngram——稀疏召回路) → `V23`(parent_chunk_id——双粒度索引) → `V24__approval_attachment_add_uploader`(补 `uploader_id`，修 WriteChainRegressionIT 双红) → `V25__invoice_info_add_remark`(补 `invoice_info.remark`，schema 漂移审计根修) → `V26__permission_seed`(权限种子：501-504 报表 + 800-807 AI，apply-permission-matrix) → `V27__knowledge_admin_permission_seed`(知识管理 900 权限码种子) → `V28__add_source_to_customer_company`(客户来源列，TASK-14/15 图表聚合)。**取号与清单以 `ls src/main/resources/db/migration` 实测为准**（本行快照截至 2026-09-22，新增脚本须同轮登记 `FlywayMigrationIT` 的 `EXPECTED_VERSIONS`）。**MyBatis-Plus 是唯一持久化**（JPA/Hibernate 已移除）。回退靠备份，不靠 down 迁移。

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
| 质量评估 | `com.slz.crm.quality`：五类 **54 条**基准集（SUITE_VERSION 2.0，单条权重 5.6%→1.9%；v1 的 18 条不可直接比较）+ 真检索 runner（`RAG_BENCHMARK_REAL` 门控）+ 既有四份基线 JSON |

基线阶梯结论（`docs/rag-quality/ladder-report.md`）：四跑质量锚点全稳（recall@k 0.9444 / MRR 0.8472 / citationPrecision 0.8056 无失效级回退）；chunking 跑 MRR 小降 0.028 但 citationPrecision +0.083（已记录、未翻默认）；fixtures 量级指标饱和，量化"提升"主张需更大数据量兑现。

---

## 3. 当前 git 状态与未决事项

- **图像 PDF 视觉转写试点已落地（add-vision-pdf-ingest-pilot，默认关）**：`PdfVisionTranscriber` + `DocumentService.parsePdf` 可选接入；三键 `rag.retrieval.vision-pdf.*`（enabled=false / min-text-chars=80 / max-pages=3）；失败回退文本层；pageNo 不变。**真 VLM 1 页试点已授权跑完（2026-09-17）**：`VisionPdfRealPilotIT`（failsafe，`RAG_VISION_PDF_REAL=1` 门控）**1 次 vision** 全绿——无文本层 PDF（文本层 0 字符）→ 渲染 → `qwen-vl-plus` 转写 **112 字**、关键词 **6/6** 命中、**闸门通过**、chunks=1 / pageNo=1；未跑 54 条、未跑 132 页全量；记录见 `docs/ingest-vision-pdf-pilot.md`。生产打开 `enabled=true` 仍需单独授权。
- **I-05 图注黄金块切分对齐（fix-i05-caption-chunk，已合入）**：`sla-arch-diagram.md` 独立 fixture（key=`sla-arch`），从 `sla-terms.md` 删图注段；FIXTURES 11→12；`RagBenchmarkDataPreparerTest` 词面断言（含接入层/台账与预警引擎，不含何建军/赔偿当月服务费）；`SUITE_VERSION` 仍 2.0；禁改 CitationAligner/FixedChunkingStrategy 320/40/Evaluator。本轮新增 1 个词面断言用例（surefire 计数口径见 `scripts/test-baseline.txt`，本文不复制数字）。**I-05 取证重跑已授权完成（2026-09-16）**：citP **1.0**（was 0.0）；黄金 `sla-arch-0` rank1 被 `[1]` KEEP；见 `docs/rag-quality/i05-after-caption-chunk.md`；未覆盖 v1/v2/after-quality-loop/i05-forensics.json。
- **性能与并发基线（measure-perf-baseline，已合入）**：只测不改热路径。`docs/perf-baseline.md` 盘点 AiChatMetrics / 平台与助手线程池拒绝策略 / actuator 保护；`PerfBaselineSmokeTest` 4 条 ¥0 微基准（FixedChunking 10KB/100KB、CitationAligner×1e4、ConstraintQuerySplitter×1e4），本机 ns 表入文档**不**入 ci 阈值。**未**改 `FixedChunkingStrategy` / 线程池大小 / SSE 超时。**`add-paragraph-chunking` 已完成并合入**（`ccbdbc1`，段落感知切分；tasks 已全勾），**切分默认策略仍为 `fixed`**（新策略走开关，不翻默认）。
- **工作树实测（口径 = `git status --porcelain`，2026-09-22 亲验快照：3 个未跟踪条目 —— `.trae/`、`openspec/changes/tighten-pmd-residual-325/`、`work/`；另有一批 `src/main/java` 的 PMD 收窄改动由**并发 lane 在途**，随时可能变）**：`frontend/` **已全部纳入 git 跟踪**（`git ls-files frontend | wc -l` = 262，且对它无未提交改动）——旧版"`frontend/` 整片是**未跟踪**的在途前端提案工作区（非遗留垃圾）"的说法**已不成立**；`work/` 是本地临时目录（构建/门禁日志），`openspec/changes/tighten-pmd-residual-325/` 是 PMD 存量收尾的在途规格。rag 学习工作区的三个暂存件 `_rag优化交接.md` / `_vlm_transcribe.py` / `_技术深化交接.md` 已于 `61c0f92` 一并入库（旧版"勿提交勿删除"的告诫随之失效）。**未 push**（硬约束：推送需你显式授权）。
- **surefire 全绿以 `scripts/test-baseline.txt` + `bash scripts/check-test-baseline.sh` 的裁决为准**（本机的历次亲验记录只作过程证据，见上文各提案条目）；failsafe 在无 Docker 机器上是"下限口径"，分类见 `docs/migration-runbook.md` §6.2。
- **WriteChainRegressionIT 双红灯已修复（V24 补 approval_attachment.uploader_id + 种子修正）**；**全量实体↔表列漂移审计（P1）已完成（audit-entity-table-drift 合入）**：新增 `SchemaDriftAuditIT` 永久门禁（真 MySQL CRITICAL 非空即 fail）根修 `V25__invoice_info_add_remark.sql` 补 `invoice_info.remark` 列，审计报告见 `docs/schema-drift-audit.md`。
- **schema 漂移 7 项遗留已全部定夺豁免（drift-disposition 合入，零行为变更）**：新增 `KnownDriftRegistry`（W1-W5 类型不亲和 + I1 生成列 + I2 预留表，2026-09-14 拍板"登记豁免、不动表结构"），审计 KNOWN/NEW 二分上线（KNOWN=7 / NEW=0 / CRITICAL=0，IT 真库实测）；单测防呆 `unmatchedKnownDrifts` 保证登记项必须仍产出真实漂移，新漂移走 NEW 登记流程。
- **权限读取缺口已闭合（close-permission-read-gap 合入）**：`GET /permission/list` 与 `GET /permission/getByRole` 已加 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`（复用 606，读写同权，未新增 608）；`PermissionControllerIT` 两个 `@Disabled` 已移除并新增 1 正向用例（本地 Docker 实测 3 绿）；surefire 用例数本轮不变。AGENTS.md「未闭合的授权缺口」章节已改写为闭合记录。
- **全量端点权限矩阵：审计上线 → 映射已落地（audit-permission-matrix → apply-permission-matrix，均已合入 master `fad493b`）**：`PermissionCoverageAuditIT` 永久门禁（纯 JVM 静态扫描，无 Docker 依赖，本地与 CI 均真跑）——每个端点强制三选一（方法级注解 / INTENTIONAL_OPEN 登记 / PENDING_DECISION 登记），写语义裸奔端点直接红；新增 controller 必须同步登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`。首轮审计（2026-09-13）27 controller × 208 端点 = SECURED 146 / OPEN 5 / PENDING 57 / CRITICAL 0；2026-09-18 `add-knowledge-admin-api` 补登记 `KnowledgeAdminController` 后为 28 × 215 = 153/5/57。**用户 2026-09-14 拍板（D1 方案A / D2 复用 501-503 / D3 纳入）后由 apply-permission-matrix 落地并 `--no-ff` 合入 `fad493b`**：AI 三 controller 34 端点挂新增 800 段常量 800-807、报表 6 端点复用并激活 501/502、`DELETE /user` + `POST /user/find` 挂 602/603、15 条零注解端点（自服务 4 / 模板 2 / 下拉自查 2 / DynamicConfig 7）转 INTENTIONAL_OPEN、`V26__permission_seed.sql` 种 501-504 + 800-807 并给全部业务角色授权（roleId 0/1/2 不授、超管直通）；冻结/离职状态检查已前置修复绕过（`PermissionsInterceptor#authorize` + 反向 IT `PermissionsInterceptorStatusIT` 3 例）。**落地后门禁实测（2026-09-22）**：28 controller × 215 端点 = **SECURED 195 / INTENTIONAL_OPEN 20 / PENDING_DECISION 0 / CRITICAL 0 / WARN 0，PENDING_DECISION 已清零**；拍板记录、三档对照与"期望值 186/22/0 为何是错的"口径勘误见 `docs/permission-matrix-audit.md` §6。
- **RAG 基准集扩容 18→54 条（expand-rag-benchmark 合入，¥0 部分）**：fixtures 6→11（新增客户 SOP/价格政策/区域政策/SLA/维保周期表）；`RagBenchmarkSuite` 五类 TEXT 17 / TABLE 13 / IMAGE 6 / LEXICAL 14 / EDGE 4，`SUITE_VERSION` 升 2.0（v1 18 条不可直接比较，单条权重 5.6%→1.9%）；新增 ngram 召回闸门覆盖 L-01~L-14（含 InnoDB FTS 批量插入相关度全 0 的坑：ingest 后须 `OPTIMIZE TABLE` 刷盘）；本轮新增 1 例，ci.yml 三处同步上调。**v2 锚点已跑（用户授权真外呼，2026-09-16）**：`docs/rag-quality/baseline-v2.json` + `docs/rag-quality/baseline-v2-anchor.md`（54 条，纯默认矩阵 V1，failureRate 0.0）；关键指标 recall@5 0.9105 / MRR 0.8210 / citationPrecision 0.7843 / hitRate 0.96 / answerConsistency 0.9475 / totalTokens 51947 / meanTotalLatency 3665.7ms；与 v1（18 条）不可数值直比，TABLE 聚合与数值区间（TB-01/TB-10 零召回）为新增难度面弱项。
- **RAG 真检索基线已跑**：四份 JSON + `docs/rag-quality/ladder-report.md`。跑法铁律：`RAG_BENCHMARK_REAL=1` 且只 `-Dit.test=RagRealRetrievalBenchmarkIT` 过滤——`.env` 常备 key，全量 `mvn verify` 会连带其他 DashScope IT 真外发。
- **测试与可观测性卫生修复（test-hygiene 合入，零行为变更）**：① 9 个 Caffeine cache 开 `recordStats()`（CacheConfig 8 + RequestQuotaService 2，命中率指标可见）；② AI 定时任务按 `crm.ai.scheduled-enabled` 门控（生产默认开，测试 profile 关闭，H2 冒烟不再刷 `ai_pending_action` Table not found）；③ surefire `@{argLine}` 合并挂 byte-buddy-agent 1.17.7 消 Mockito 动态加载警告；④ AsyncContextDecoratorConfig 的 @Bean 工厂方法 static 化消 Spring 6.2 BPP 警告。**本轮 surefire 用例数不变、全绿**，四类警告日志全消。
- CI：`.github/workflows/ci.yml` = surefire + failsafe 双口径回归门禁 + 报告归档；其回归基线步骤不含硬编码阈值，只调用 `bash scripts/check-test-baseline.sh` 读 `scripts/test-baseline.txt` 裁决（该文件禁止手改，只允许 `--update` 从一次真实运行写入）。
- **Java 静态分析四道门禁已全部生效（`operationalize-harness-gates` 组 3 + `wire-pmd-ruleset`，2026-09-21）**：`pom.xml` 的 checkstyle / spotbugs / spotless / pmd 四个插件块**均无 `<skip>`**（复测 `grep -c "<skip>" pom.xml` = 0）。PMD 尤其从"仓库那把尺子从未被加载"变成真门禁：`<rulesets>` 显式加载 `src/main/resources/pmd-rules.xml`，条数阈值唯一读者是同块 `<maxAllowedViolations>`，存量基线在 `scripts/tests/pmd-violation-baseline.txt`、过期防呆是 `scripts/tests/pmd-baseline-check.sh`（与 SpotBugs 侧同构：只允许 `--update` 从一次真实运行写入、只许下调）。**本文与 `ci.yml` 都不复制条数/阈值**，要看现值就读 pom 与台账；启用状态、豁免历史与复测命令在 `docs/migration-runbook.md` §6.7。**一条必须知道的口径**：`pmd:check` 只在实测**严格大于**登记值时才红，所以它永远不会因代码变好而红——"该下调了"只有 `[pmd-baseline]` 会报，而它挂在人跑的 `bash scripts/merge-gate.sh` 上（无自动触发通道，见本文件"哪些门禁会自己变红"）。**`wire-pmd-ruleset` 的 Q4 已拍板闭合**（2026-09-21：用 `codestyle/EmptyControlStatement` 补回覆盖面，24→25 条尺子）；**"按规则分片收紧存量"的后续提案 `tighten-pmd-violations` 已执行**（分片 C 已闭合于 2026-09-22：`AvoidCatchingGenericException` 134 处 catch 通用异常归零 CATCH=0，全仓 459→325，台账与 pom 同步落 325；处置 = Q6 拍板的混合双轨——叶子层能确定抛出源者收窄为具体异常、顶层兜底用 `@SuppressWarnings("PMD.AvoidCatchingGenericException")`+中文理由，处置表见该提案 tasks.md 3.1-T，两个新坑与读数坑见 `docs/migration-runbook.md` §6.8）。
- **PMD 剩余存量第二轮收紧全案收口（tighten-pmd-residual-325，F-1..F-5 全部落地，PMD 归零）**：上一轮 `tighten-pmd-violations` 把 1329 收到 325 后，本轮按 owner 拍板 Q7（"全部拆完"，2026-09-22）分五批把 325 收到底，**实测终值 0**（读数一律以 `pom.xml` 的 `<maxAllowedViolations>` 与 `scripts/tests/pmd-violation-baseline.txt` 为准，本节只记过程阶梯）：分片 D `9eb7bfa`（死参/死变量/小异味 + 空 catch 逐例）**325→306** → 分片 E `8656919`（FieldNamingConventions 39 条改名，pojo 零命中故零豁免）**306→267** → F-1 `161802f`/`63812c3`/`85078d7`/`dc2fe3f`/`fa9dee1`/`1c3fe53`/`2bc09d0` 七批（方法级复杂度拆方法）**267→188** → F-2 `e579c81`/`7d54dc5`/`23516b0`/`6824964`/`9a99ca2`/`adced5a`/`bd31372`/`c0eff2d`/`d142258`/`5a9cc94`/`e66398e`/`438531d`/`dc5527b`/`a144bf4` 十四批（类级拆 helper/协作类，仅高风险面外沿）**188→83** → F-3 `ec551aa`/`6ad6259`/`f66a913`/`c3417da` 四批（高风险类：SSE 生命周期 / 数据权限超集不变量 / 附件越权 / ModelProvider，**配套反向用例**并以 `SseContractTest` 10/10 为硬门禁）**83→37** → F-4 `4acd8cd`（`AssistRequestServiceImpl` 1940 行 / 83 方法拆为门面 + 20 个同包协作类，同步删掉分片 D 任务 4.5 的 `PMD.TooManyMethods` 类级豁免，口径自洽）**37→0**，F-5 为纯文档/台账收尾批。**surefire 724→748**——本提案"计数锁死"的唯一例外是 F-3 起的**只增**配套反向用例（F-4 的 `AssistRequestSplitEquivalenceTest` 两段式 12 例：先在原实现跑绿作基准、拆分后原样复跑仍 12 绿）。**豁免终盘 118 条**（不含 `//NOPMD`，实测 0 处）：`AvoidCatchingGenericException` 107 / `OnlyOneReturn` 7 / `UnusedFormalParameter` 3 / `EmptyCatchBlock` 1，落在 115 个 `@SuppressWarnings` 注解点、71 个文件；按来源分 = 第一轮存量 83（分片 C 的 catch 全会处置）+ 本轮新登记 13 + 随拆分类/方法**原样迁移** 22，逐条位置、理由摘要与登记批次（含 `git blame` + `git log -S` 双向取证）见该提案 tasks.md 6.6 留痕。**门禁零值盲区已修（本提案唯一越界项）**：`scripts/tests/pmd-baseline-check.sh` 的旧哨兵把"报告里 0 个 `<file name=>`"一律读成"根本没分析"→ exit 2，而 PMD 的 XMLRenderer **只为有违规的文件**输出 `<file>`，于是基线一旦降到 0 就永远登记不进去；现判据为"`violations==0 && files==0` 时报告 mtime 不得早于 `src/main/java` 下最新 `.java`"，`files>=1` 路径行为一字未改，零值双向回归锁见 `scripts/tests/merge-gate-selftest.sh` 场景 11h/11i。**F-5 收尾复验 merge-gate 八门禁全绿**（unit / spotbugs / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline），同日按 6.6 授权把台账 `source-revision` 由 `69e41f3` 经真实 `--update` 刷新为 `53cac0d`（口径不变、数值仍 0）。附带修正：本节"工作树实测"快照里"`openspec/changes/tighten-pmd-residual-325/` 未跟踪"一条**已失效**（`proposal.md`/`tasks.md` 已随 `81064eb`/`9eb7bfa` 入库，`git status --porcelain` 下现已无未跟踪的 `openspec/` 目录）。
- **待授权遗留**：生产库全量 reingest（真实嵌入成本 × chunk 总量，`KnowledgeReingestRunner` 已实现）、多查询/HyDE 生产语料重评。（基准集 v2 锚点已于 2026-09-16 授权跑完，见上条与 `baseline-v2-anchor.md`。）
- **生成后引用编号对齐（fix-citation-alignment，已合入）**：`CitationAligner` 零外呼 KEEP/REMAP/DROP；生产 `AiChatStreamLifecycle` 落库前对齐、评测 `RagRealRetrievalBenchmarkIT` 共用同一实现对齐后再抽 citations。阈值 KEEP_MIN=0.12 / REMAP_MIN=0.22 / TIE_MARGIN=0.08；计分子句 CJK 二字覆盖率。本轮新增 `CitationAlignerTest` 6 例（ci.yml 已同步上调）。**after-quality-loop 已跑**（2026-09-16 授权，三单合并一次默认矩阵；产物 `docs/rag-quality/baseline-after-quality-loop.json` + `.md`，**未**覆盖 v1/v2；全套 recall@5 **0.9475** / MRR **0.9136** / hitRate **1.0** / citP **0.8302** / failureRate **0** / suiteVersion **2.0**；I-05 citP **仍 0**——对齐目标未达，不调阈值）。
- **Excel 多列表头投影（add-excel-header-projection，已合入）**：`DocumentService.parseExcel` 在首行非空格≥2 且每格≤32 时把列名投影为「列名：值」进数据行，表头行不入库；单列/超长首行保持原行为。¥0 单测 + 语料黄金行列名断言已绿（本轮新增 3 例，ci.yml 已同步上调）。**after-quality-loop 已覆盖本单复测**（非单独 after-excel-header 文件）：TB-01/TB-10 recall **0→1.0**，其余 11 条 TABLE recall 无回退。生产已入库 xlsx 需另授权 reingest，本单不触发。
- **多条件查询零 LLM 拆路召回（fix-multicondition-recall，已合入）**：`ConstraintQuerySplitter` 确定性拆「A后B/且/并且/同时」为原查询+左右路；`KnowledgeRetrievalServiceImpl.retrieve` 每路 embed+recallTextRoute，>1 路用本地 `new RrfFusion().fuseAll`（V1 六参 this.rrfFusion==null 也可融）。**禁止改构造器**；**不打开** multi-query 默认。本轮新增 `ConstraintQuerySplitterTest` 6 例（ci.yml 已同步上调）。**任务组4 已并入 after-quality-loop 复测**：T-14 recall **仍 0.5**（目标 1.0 未达）；T-15/T-17 recall 未回退；三单合并一次跑，产物 after-quality-loop，不是三个分文件。
- **视觉摄取适用性调研（research-visual-ingest，已合入）**：对照文档 `docs/ingest-gap-map.md`（本仓 PDFBox 文本层现状 × 学习工作区 PNG+VLM 做法 × 差距表 × 成本粗估）。**试点已落地**（`add-vision-pdf-ingest-pilot`，默认 `vision-pdf.enabled=false`）：`PdfVisionTranscriber` + parsePdf 可选接入，失败回退文本层，pageNo 不变。**任务组 5 真 VLM 1 页试点已授权跑完（2026-09-17，1 次 vision，闸门通过，见 `docs/ingest-vision-pdf-pilot.md`）**；生产打开开关仍待授权。质量闭环三单 54 条 after-quality-loop 已跑完。本调研/试点合入路径 ¥0、零外呼；`_vlm_transcribe.py` 等仍未跟踪不提交。


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

`openspec/`（检索链路优化 + 治理提案）：
- `project.md` —— 17 方案处置总表 + 硬约束（**改检索链路前必读**）
- `git-workflow.md` —— 分支/提交/合并/CI 基线/成本闸门契约
- `changes/` —— 在途提案；**当前实际目录以 `ls openspec/changes/` 为准**（本节只记“现在还剩谁”，不再复制会过期的全量清单）：add-frontend-workspace（前端工作区纯拷贝引入；**真在途**——tasks.md 组 8 的 git 收尾未落，无 `--no-ff` 合并提交）｜ add-knowledge-admin-api（知识库管理 API 7 端点 + 900 权限码 + V27 种子，已合入；组 6 真摄取/真检索试点**停下等授权**）｜ add-vision-pdf-ingest-pilot（图像 PDF 视觉转写试点，`vision-pdf.enabled=false` 默认关；生产打开待授权）
- `changes/archive/` —— 已闭合提案统一移入此处（以 `ls openspec/changes/archive/` 为准；归档判定与引用同步口径见 `openspec/git-workflow.md` §8）

性能基线：`docs/perf-baseline.md`（measure-perf-baseline）。

摄取：`docs/ingest-gap-map.md`（调研）+ `docs/ingest-vision-pdf-pilot.md`（1 页真 VLM 试点记录，1 次 vision）+ `openspec/changes/add-vision-pdf-ingest-pilot/`（试点实现，默认关；生产打开待授权）。

代码入口：`src/main/java/com/slz/crm/{server,knowledge,platform}`；测试：`src/test/java/com/slz/crm/{unit,integration,contract,quality}`。

---

## 5. 接手后的第一步 / 常见任务怎么做

1. **先看工作树、再跑一条聚合门禁**：`git status --porcelain`（**本文不替你记工作树状态**——本仓常有并发 lane 在途，写死的"干净/不干净"结论放着就会过期）。然后跑 **`bash scripts/merge-gate.sh`**：一条命令依次裁决 `scripts/merge-gate.sh` 头部清单里的全部子门禁（surefire / spotbugs / pmd / 回归基线 / 前端单元轨 / 提交期钩子在位 / 两份台账的双射与过期），输出本身就是合入证据；要连 failsafe 一起拿新鲜结论加 `--with-verify`（**本机 Docker 实测在线**，会真起 `mysql:8.0.36`；有没有 Docker 自己 `docker info` 一条命令验，别照抄任何文档，包括本文）。单独跑 `mvn test` + `bash scripts/check-test-baseline.sh` 仍有效，但它只是聚合门禁里的两格。
2. **理解助手**：读 `server/ai/` + `assistant-decision-tree.md` + SSE 契约（`SseEventName` + `SseContractTest`）。改事件/payload = 契约变更，同步前端 + 测试。
3. **理解知识库**：`knowledge/` + `contracts-frozen.md §2`；检索管线 = 授权过滤→查询改写→向量/稀疏双路召回→RRF 融合→rerank→ContextBuilder 邻居增强+预算压缩（`KnowledgeRetrievalServiceImpl`；开关矩阵见 §2.5 与 `docs/dynamic-config-keys.md`）。
4. **改契约**：`contracts-frozen.md` 是权威；改 `platform/contract` 要同步所有消费方 + `SseContractTest`。
5. **发布/回退**：`release-runbook.md`（灰度开关、4 层回退、权限矩阵）。
6. **质量**：`com.slz.crm.quality`(RagQualityEvaluator) 是 RAG 评估 harness（五类 **54 条**基准集 SUITE_VERSION 2.0，recall/MRR/citationPrecision + JSON 报告）；真检索基准 `RagRealRetrievalBenchmarkIT` 走 InMemory+DashScope（跑法铁律见 §3），既有基线在 `docs/rag-quality/`：v2 锚点（`baseline-v2.json`）+ **after-quality-loop**（质量闭环三单合并复测，`baseline-after-quality-loop.json`，recall@5 0.9475 / MRR 0.9136 / hitRate 1.0 / citP 0.8302 / failureRate 0；I-05 citP 仍 0，T-14 recall 仍 0.5；I-05 引用取证已落地见 `docs/rag-quality/i05-forensics.md`，结论 **KEEP 错号**——对齐前后均为 `[1]`→非黄金 `sla-3`，黄金 `sla-2` 在 rank3，raw==aligned，未改对齐器；根因对症 **fix-i05-caption-chunk** 已把图注拆为独立 fixture，黄金块词面 ¥0 断言绿，I-05 真外呼重跑已完成 citP=1.0，见 `docs/rag-quality/i05-after-caption-chunk.md`）。

---

## 6. 坑与教训（接手必读）

1. **多模块合并后必须有 H2 全上下文冒烟测试**（`ApplicationContextSmokeTest`）——单测全 mock 抓不到装配缺陷（本项目查出 4 类）。
2. **子 agent/同事报"完成"要亲验** commit hash + `git status` 干净——曾有 lane 报完成但成果未提交。
3. **Flyway 前向、无 down**；回退靠备份。改表=加新迁移，不改旧迁移。
4. **SSE 是前后端唯一对接面**：12 事件名 + payload 字段冻结 + 断线续传(`id=<generationId>:<seq>`、重放≠重执行)；改它=改契约。
5. **数据权限靠 AOP**（`QueryWrapperAspect` + `DataScopeServiceImpl` 两重载），不是手写 where；超集不变量（同维度内就大不就小）。
6. **DashScope key 曾明文暴露 → 已建议轮换**；密钥只走环境变量/`.env`(gitignore)，不进仓库。
7. **V6 保留字教训**：列名 `sensitive` 撞 MySQL 8.0.36 保留字，曾让真库迁移链全灭（V1..V23 从未完整应用于真库、仅探针容器绿）——新迁移合入前至少真库跑一次 `FlywayMigrationIT`。
8. **基准真跑铁律**：真外发 IT 已改为显式 opt-in（`RAG_BENCHMARK_REAL=1` 门控，见 `docs/migration-runbook.md` §6.3），但 `.env` 常备 key 且同一开关也解锁 `ModelProviderImplDashScopeIT` 的真机用例——跑基准仍只 `-Dit.test=RagRealRetrievalBenchmarkIT` 白名单过滤，别用 `-Dit.test=!XxxIT`（见 runbook §6.4）。
9. **别假定本机有没有 Docker，跑一条 `docker info` 只要 1 秒**。2026-09-21 一条门禁记录把"没跑 `[it]`"记成"本机无 Docker"，实际原因是默认序列不含 verify（要加 `--with-verify`）——本机 Docker 一直在线（Server 29.6.2，`git-workflow.md` 与本文件早已写明"在线时会经 Testcontainers 拉镜像"）。**环境前提也要实测，猜的错前提会顺着文档链往下传**：它后来还被写进派发简报，让下一个 agent 以为 failsafe 拿不到新鲜证据。
10. **台账类判别必须在"被 git 检出后的形态"下验证，且脚本别用 `sed -i` 改被跟踪文件**。本仓 `core.autocrlf=true`：`.tsv`/`.xml`/`.txt` 台账一入库，Windows checkout 就变 CRLF，行尾的 `\r` 会让 `comm`/字符串比较把同一行判成两行——`spotbugs-exclude-staleness-check.sh` 曾因此在交付后当场变红（12 对里只配上末行 1 对）。修法在读入侧 `tr -d '\r'`（对 Linux runner 同样成立），不是把数据改去迁就平台。同一坑的镜像面：msys 的 `sed -i`/整文件重写会把 pom 的 528 行 CRLF 抹成 LF，**`git diff` 看不出来、只有字节数看得出**，所以 `pmd-baseline-check.sh` 设计成只写自己那份台账、不代改 pom（漂移由"两个数字不等即红"兜住）。既有 `.gitattributes` 只覆盖 `*.sh` 与 `.githooks/*`，台账类刻意留在 CRLF 世界靠读入侧归一。
11. **在 Git Bash（MINGW64）里 `mvn` 是坏的：启动器把 unix 路径直接喂给 Windows 版 java**（2026-09-22 实测）。症状是每条 mvn 步骤都报 `错误: 找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`，于是 `bash scripts/merge-gate.sh` 的 `[unit]`/`[spotbugs]`/`[pmd]`/`[it]` **整片假红**——看着像代码坏了，其实一行代码都没问题，`[baseline]`/`[frontend-unit]`/`[bijection]` 反而照常 PASS，别被这个混合结果骗。根因两件叠一起：① `mvn` 脚本的平台分支里 `mingw` 只做 `cd && pwd`、**不做 Windows 路径转换**（源码里就留着 `TODO classpath?`），`exec` 行因此是 `-classpath /d/develop/.../plexus-classworlds-*.jar`；② 本机 `JAVA_HOME` 未设、`java` 落到 `C:\Program Files (x86)\Common Files\Oracle\Java\javapath\java`（Windows java，解析不了 `/d/...`，会当成 `<当前盘>:\d\develop\...`）。**判别一条命令**：`bash -x $(which mvn) -v 2>&1 | tail -3` 看 exec 行的 `-classpath` 是 `/d/...` 还是 `D:/...`。**修法**（已验证）：用一个 shim 把 Windows 风格路径直调 classworlds Launcher，`JAVA_BIN` 指 `D:/develop1/jdk21/bin/java.exe`（pom `java.version=21`，本机默认 `java` 只有 1.8），再 `PATH=<shim 目录>:$PATH bash scripts/merge-gate.sh --with-verify`；shim 本体放仓外临时目录，别入库。附带提醒：`mvn` 的**跨模块**行为依赖 `-Dmaven.multiModuleProjectDirectory`，shim 里要照抄脚本的"向上找 `.mvn`"逻辑，否则多模块解析会偏。

---

## 7. 与 rag 学习工作区（`c:\Users\fzdzzj\Desktop\rag`）的关系

那是**另一个独立工作区**：一个"关于 RAG 的学习知识库"(rag-kb) + 它的抽取 pipeline，正在做 pipeline/RAG 优化（有自己的交接：`优化交接.md`，含 17 优化方案适用性映射）。
- **互参已兑现**：本项目检索链路优化正是以那边的 17 方案适用性分析立项（`openspec/project.md` 处置总表）；那边 B4（检索评估）借鉴了本项目 `RagQualityEvaluator` 的黄金集+指标+JSON 报告设计。
- **两者代码独立**，不要混淆仓库；根目录 `_rag优化交接.md`、`_vlm_transcribe.py` 是那边的暂存件，勿提交勿删除。



## add-knowledge-admin-api 执行记录（2026-09-18）

- 权限码：KNOWLEDGE_ADMIN_MANAGE(900L) —— 读写同权单码（复用 606 模式）。
- 7 端点全部挂 @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)：
  GET /knowledge/bases
  GET /knowledge/files
  POST /knowledge/files
  GET /knowledge/files/{id}
  DELETE /knowledge/files/{id}
  POST /knowledge/files/{id}/reingest
  POST /knowledge/retrieval/test （默认稀疏零外呼）
- 新增 controller 已登记 PermissionCoverageScanner.CONTROLLER_REGISTRY。
- surefire 本轮新增 KnowledgeAdminServiceTest 4 个用例（实测绿），当时（2026-09-18 历史记录）上调的是 `.github/workflows/ci.yml` 里的字面量；阈值现由 `scripts/test-baseline.txt` 持有，当前值以该文件与其 `git log -p` 为准，本条不作验收口径。
- V27__knowledge_admin_permission_seed.sql 已种植 + 业务角色授权。
- docs/permission-matrix-audit.md 已追加行。
- 禁止事项遵守：未改 V1..V26；未跑 54 条基准；未默认真 embedding；单测 mock；IT 如需 Docker 记 skip。
- 分支 feature/add-knowledge-admin-api；--no-ff merge；未 push。
- 任务组 5 真摄取：停下等授权，未执行。
