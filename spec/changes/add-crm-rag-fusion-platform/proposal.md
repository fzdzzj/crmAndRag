# 提案：CRM 单体 + AI 助手知识库能力（crmAndRag）

> 变更 ID：`add-crm-rag-fusion-platform`
> 目标仓库：`d:\code\crmAndRag`（当前仅有提案文档、无代码；分支 `spec/add-crm-rag-fusion-platform`）
> **架构定位（重定位，先读）**：本项目不是"融合两个对等系统"，而是**以 CRM 为基座构建一个（看起来从零的）CRM 单体，其 AI 助手具备知识库(RAG)能力**。RAG 的独立对话层丢弃、对话能力被助手吸收、知识库能力移植为 `com.slz.crm.knowledge` 模块。
> 状态：设计已锁定（D1–D17 + C1–C10），进入实施；不改业务模块、不自动提交 master、不 push。

## Why

新平台 `crmAndRag` 的目标是**一个 CRM 单体**：完整业务域 + 一个 AI 助手，助手既能调用 CRM 业务工具，又能检索知识库(RAG)作答。为此需要把两套已成熟但技术栈分叉的后端，按"CRM 为基座、RAG 为助手能力"的方式收敛：

- **CRM（基座，`com.slz.crm`，Boot 3.5.5 / Java 21 / MyBatis-Plus / JWT 拦截器 / spring-ai-alibaba）**：完整客户关系管理业务域（客户、联系人、商机、合同、订单、回款、发票、审批、协助、报表、统计），一套角色数据权限（`DataScopeLevel`=NONE/SELF/TAGE/ALL），一个生产级 AI 助手（SSE 流式、17+ 工具、草稿→确认两级执行状态机、心跳/超时/接管、token 回写、评测集）。
- **RAG（能力来源，`com.mark.knowledge`，Boot 4.0.2 / JPA / Spring Security / LangChain4j / Qdrant / MinIO）**：完整知识库能力（多格式解析、OCR/视觉抽取、分块、向量化、BM25+向量混合检索、图文双路检索、会话记忆、图片缓存、成员授权与检索授权过滤），以及一套后端治理（traceId/MDC、分任务线程池、依赖韧性、生产配置保护、可观测性）。

**关键洞察（决定范围）**：整合的真实工作量集中在 **AI/知识库模块 + 横切治理**，**业务模块原样搬入**——
- 业务模块已用 CRM `Result`/JWT/MyBatis-Plus，无需改。
- traceId/MDC 靠 Filter + logback + `MdcTaskDecorator`，**不改业务代码**。
- 部门数据权限靠**已有**的 `QueryWrapperAspect` 切面 + `DataScopeServiceImpl`，业务 service 不改。
- 主战场只有两处：**助手吸收 RAG 对话能力** + **新建知识库能力模块**。

**背景（需在本次收敛的未完成提案）**：
- CRM：`add-ai-assistant`（安全/配额/预算/语义缓存）、`enhance-ai-assistant`（限流落地、引用跳转、主动洞察、评测机制）。
- RAG：`add-backend-governance-hardening`（配额、依赖韧性、Token 预算、版本化迁移、生命周期事件、跨存储对账、内容安全、审计门禁）、`update-backend-optimization-roadmap`（质量基准、检索性能、核心服务拆分、验证门禁）。
- CRM 数据权限**缺部门/上下级维度**：`DataScopeLevel` 只有 NONE/SELF/TAGE/ALL；真实落点是 `QueryWrapperAspect`(AOP)→`DataScopeServiceImpl.addDataScopeCondition`+`ResourceTypeConstant.getUserFieldsByTableName`（**`MyDataPermissionHandler` 不存在**，`DataScopeResolver(Impl)` 是无调用方的**死代码**）；`SysDeptEntity` 有 `parentId`、`UserEntity` 有 `deptId`，但**无"本部门/本部门及以下(上司看下属)"范围、无负责人字段、`RoleAO` 无 `deptId`**。

**当前状态**：`crmAndRag` 仅有提案文档、无代码；CRM 与 RAG 各自独立可用、回归通过（CRM **306** 测试；RAG **491**：485 通过 + 6 Docker 集成跳过），但无法协同——助手只能查结构化业务数据，知识库只能独立问答，二者不共享身份/权限/治理/成本口径。

**期望状态**：一个以 CRM 为基座的单体后端——
- 单一身份与权限主体：CRM 用户/角色/数据权限为唯一认证源，知识库授权绑 CRM 稳定 userId。
- 一个 AI 助手：既调 CRM 业务工具（自动），又按前端手动开关 `useKnowledgeBase` 检索知识库；具备思考模式、会话记忆、图文混合问答、流式接管。
- 部门上下级数据权限（上司看下属）贯通业务查询、AI 工具查询、知识库可见范围。
- 平台级治理（traceId/MDC、线程池、依赖韧性、配额、Token 预算、版本化迁移、可观测性、内容安全、审计）统一沉淀，两源项目未完成提案在此收敛为一条路线图。

## What Changes

### 组 1：融合基座（platform-fusion）
- 以 CRM 为基座统一到 **Spring Boot 3.5.x / Java 21**；RAG 迁入代码回填该基线（Jackson 2、`spring-boot-starter-web`）。
- 包边界：业务与助手基座 `com.slz.crm.*`；知识库 `com.slz.crm.knowledge.*`（由 `com.mark.knowledge.*` 重打包）；平台治理 `com.slz.crm.platform.*`。
- 认证统一：以 CRM JWT + `UserContext` 为唯一身份源；**移除 RAG 的 Spring Security 过滤链**，知识库路由由 CRM 拦截器统一鉴权。
- 持久化统一 MyBatis-Plus + Flyway：知识库表**新建**（借鉴 RAG schema，非机械迁移 JPA）；生产禁 `auto-table`/`ddl-auto` 改表。
- 统一 `Result`/异常/错误码（以 CRM `com.slz.crm.common.result.Result` + `GlobalExceptionHandler` 为准）。
- **Actuator 保护**（移除 Security 后不留裸奔）：`/actuator/**` 挂 CRM JWT+权限拦截器，仅放行 `health/liveness`、`health/readiness` 探针；敏感端点需超管；`show-details=when_authorized`。
- 统一配置：合并 `application.yml`，敏感项外置，dev/test/prod 分离，生产配置保护 fail-fast。

### 组 2：部门架构与上下级数据权限（org-data-scope）
- `DataScopeLevel` 新增 `DEPT`（本部门）与 `DEPT_AND_CHILD`（本部门及以下/含下属），保留 `fromCode` 兼容。
- `SysDeptEntity` 新增 `leaderId`（负责人）；`RoleAO` 新增 `deptId`（`PermissionsInterceptor` 填充）——现 `RoleAO` 无 deptId 无法解析 DEPT。
- 基于 `sys_dept.parentId` 做 N 级子树递归（带成环防御）；下属集合 = `deptId ∈ 子树部门` 的用户；按每表归属字段（`ResourceTypeConstant.TABLE_USER_FIELDS`）过滤。
- 接入 `DataScopeServiceImpl.addDataScopeCondition`（经 `QueryWrapperAspect`）；**两个重载的 `default→SELF` switch 都要加 case**（否则静默降级）；为受控表补 `_DEPT/_DEPT_AND_SUB` 权限项（`PermissionOperates`）。
- **删除死代码 `DataScopeResolver/DataScopeResolverImpl`**（无调用方、逻辑重复、表清单陈旧缺 project_file）。
- 数据权限贯通 AI 工具查询与知识库可见范围（同一 userId + 数据范围口径）。

### 组 3：知识库能力（knowledge-rag）
- **保留 7 表新建**（CRM 约定：单数名/create_time/is_deleted/user:<id>/MyBatis-Plus）：`knowledge_base`、`knowledge_base_member`、`uploaded_file`、`document_vector_chunk`、`chunk_upload_session`、`batch_task`、`batch_file_result`。
- **移植**文档解析/分块/OCR/视觉抽取/嵌入/BM25+向量混合检索/图文双路检索/MinIO 存储/批量上传状态机——从 LangChain4j 迁到 **Spring AI**（`ChatModel`/`EmbeddingModel`/`VectorStore`）。
- **VectorStore 抽象 + 内存回退**：默认 Qdrant，dev/test 可切内存实现；`docker-compose` 提供真 Qdrant+MinIO+MySQL（净新增）。
- 授权绑 CRM 稳定 userId；检索授权过滤（`RagRetrievalAccessFilter` 语义）改按需查询消除全表扫描。
- **来源高亮（档 B，页级）**：PDF/视觉**按页分块**并打 `pageNo`（当前实现把全页 merge 成一个 `mergedText` 再整体分块，页边界丢失，需重构）；`SourceReference` 补 `chunkIndex/pageNo/chunkId`；bbox 像素级不在本期。
- **意图/类目 CRM 化**：丢弃死代码 `QueryIntentClassifier`（技术栈类目、无调用点）；**新建** CRM 域类目机制，接进检索 metadata 过滤，类目+关键词由 `DynamicConfig` 可配。
- **移除匿名态 + 独立对话层**：删 `AnonymousRagChatService`、`chat_conversation/chat_message`、`RagChatPipeline`/`RagStreamSessionManager` 独立入口与独立会话控制器；"公开"知识库语义收敛为"所有已登录用户可见"。

### 组 4：AI 助手增强（ai-assistant）
- 保留 CRM 助手既有能力（SSE 流式、17+ 工具、草稿状态机、心跳/超时/接管、token 回写、评测集）。
- **吸收 RAG 对话能力**（详见 `assistant-decision-tree.md`）：
  - **KB 手动开关 `useKnowledgeBase`**（非 LLM 自主）：ON=每轮强制检索并注入；OFF=纯助手（闲聊+工具+图片理解）。业务工具仍由 LLM 自动调用。
  - **思考模式**：SSE `thinking` 事件透传（前端折叠）+ 写记忆/历史前**恒定剥离** think + prompt 预算闸门。
  - **会话记忆**（9 套措施）：`ai_message` 为唯一真相源，`recentMessages` 改其内存投影；`summary/facts/intent` **持久化到新表 `ai_conversation_memory`**（解决纯内存重启丢+多实例不共享），归属改 userId；意图/摘要 LLM 加工走异步旁路（单飞+拒绝降级，不阻塞主答）。
  - **图文混合 + 图片解耦**：图片理解文本恒注入 prompt（闲聊/工具也能看图）；图片向量**仅 KB ON 懒生成**；取消无差别"最近图回退"，改 `imageRef` 显式引用；缓存分层 L1(hash)/L2(hash+问题)，加每会话上限+LRU。
  - **流式接管**：`shouldAbort` 检查点（嵌入前/检索后/重排后/LLM首包/每 delta）；接管=当前会话活跃生成被顶替。
  - **空匹配兜底修复**：KB OFF **绝不**返回"未检索到"；KB ON 零命中改"注入未命中标记+诚实生成"（strict-KB 硬兜底可配）。
- **只读工具 `queryKnowledgeBase`**：接入 `AiToolRegistry`，受 CRM 数据权限 + 知识库授权双重约束。
- **来源引用与高亮**：`sources` SSE 事件（检索后、答案前）+ 答案内联 `[n]` + `payload.citations`；`ai_message.payload` 契约（9 种 msgType，见决策树 §6）。
- 中和 `enhance-ai-assistant`/`add-ai-assistant`：限流落地（每用户滑窗）、`references` 结构化事件、主动洞察 `ai_insight`、评测执行机制、提示注入/越权防护、并发配额。
- **统一 Token 计量**：`ai_message.tokenCount` 接平台计量；**补齐 RAG 意图/摘要 LLM 调用的计量盲点**（当前用 `chat(String)` 重载拿不到 usage）。

### 组 5：平台治理与硬化（platform-governance）
- 中和 RAG 两个治理提案，提升为平台级：traceId/MDC 跨线程传播、分任务类型线程池（含记忆旁路执行器）、依赖韧性执行器（修复熔断开路无 cause、依赖不可用回 PENDING）、四层请求配额、AI Token 预算、Flyway 版本化迁移、文档生命周期事件与跨存储对账、内容安全分级、审计与治理指标、RAG 质量基准与检索性能优化、验证门禁。
- **Actuator 可观测性**：移植 `QdrantHealthIndicator`/`MinioHealthIndicator`（挂 VectorStore 抽象+MinIO），健康分组 liveness/readiness；合并指标（`AiChatMetrics` + `TaskExecutorMetricsBinder` + `rag.retrieval.duration` + 治理指标）到 Micrometer/Prometheus；健康端点收敛。

### 组 6：超级管理员动态配置（dynamic-config）
- AI 与业务参数运行期可调：系统提示词、模型 Provider/名称/温度/最大 token、限流与配额阈值、检索 topK/阈值/分块参数、**意图类目与关键词**、功能开关。DB 存储 + 命名空间 + 热生效 + 类型/范围/枚举校验护栏 + 版本历史/回滚 + 审计（复用组 5 审计流）。仅超管（roleId=1）可写。

## 关键取舍决策（D1–D17，评审已确认）

详细对比见 `design-decisions.md`；行为细节见 `assistant-decision-tree.md`。

| # | 决策点 | 确认结果 |
|---|--------|----------|
| D1 | Spring Boot 基线 | ✅ A 统一 3.5.x，回填 RAG |
| D2 | 持久化 | ✅ B 归一 MyBatis-Plus + Flyway（范围收窄到知识库模块） |
| D3 | 认证统一 | ✅ A CRM JWT 唯一源，移除 Spring Security，授权绑 userId |
| D4 | AI 栈 | ✅ A Spring AI 统一（dashscope 默认）+ 可插拔 ModelProvider |
| D5 | 模块结构 | ✅ A 单模块 + 三包边界 |
| D6 | 上司下属建模 | ✅ A+B 部门树 + `sys_dept.leaderId` + `RoleAO.deptId`（删死代码 DataScopeResolver） |
| D7 | 向量库/对象存储 | ✅ A 引入 Qdrant + MinIO |
| D8 | RAG 未登录态 | ✅ B 移除匿名，全部需登录 |
| D9 | 向量库本地回退 | ✅ A+B compose 真 Qdrant + VectorStore 抽象内存回退 |
| D10 | 动态配置 | ✅ B 超管运行期动态配置（含提示词） |
| **D11** | 架构重定位 | ✅ RAG=助手能力；丢弃独立对话层，助手吸收对话能力，知识库能力移植 |
| **D12** | KB 触发方式 | ✅ 前端手动 `useKnowledgeBase`（非 LLM 自主）；业务工具 LLM 自动 |
| **D13** | 图片与 KB 解耦 | ✅ 理解恒注入、向量仅 KB ON 懒生成；取消最近图回退改 imageRef；缓存分层+上限 |
| **D14** | 会话记忆持久化 | ✅ `ai_message` 真相源 + 投影；`summary/facts/intent` 落 `ai_conversation_memory` 表；userId 归属；补 Token 计量盲点 |
| **D15** | 来源高亮 | ✅ 档 B 页级：按页分块打 pageNo + SourceReference 补锚点 + 内联 `[n]`/citations |
| **D16** | 空匹配兜底 | ✅ KB OFF 绝不触发；KB ON 零命中软标记+诚实生成；strict-KB 可配 |
| **D17** | 意图/类目 | ✅ 丢死代码 QueryIntentClassifier；新建 CRM 域类目接检索过滤，DynamicConfig 可配 |

## RAG 资产取舍

| 类别 | 处置 | 说明 |
|---|---|---|
| 知识库/文档/解析/OCR/视觉/分块/嵌入/检索/图文双路/MinIO/批量上传 | ✅ **移植** | 进 `com.slz.crm.knowledge`，LangChain4j→Spring AI |
| 7 张知识库表 | ✅ **保留(新建)** | CRM 约定重建，非机械迁 JPA |
| 会话记忆(摘要/事实/意图)/图片缓存/思考模式/流式接管 | ✅ **吸收进助手** | 记忆持久化，图片解耦，缓存分层 |
| 治理(traceId/MDC/线程池/韧性/配额/健康指标) | ✅ **提升为平台级** | 进 `com.slz.crm.platform` |
| `chat_conversation`/`chat_message` | ❌ **丢弃** | 助手用 `ai_session`/`ai_message` |
| `RagChatPipeline`/`RagStreamSessionManager` 独立入口、独立会话控制器 | ❌ **丢弃** | 对话逻辑并入助手管线 |
| `AnonymousRagChatService`/匿名问答/独立登录/`teacher_account` | ❌ **丢弃** | 统一 CRM 登录态与 `sys_user` |
| `QueryIntentClassifier`（技术栈类目） | ❌ **丢弃(死代码)** | 新建 CRM 域意图/类目 |

## Impact

### 受影响的规范（`specs/`）
- `platform-fusion` — 基座/技术栈/包边界/认证统一(移除 Security)/持久化/配置/**Actuator 保护**/表协调。
- `org-data-scope` — 部门树与上下级数据权限（真实机制 + 删死代码）。
- `knowledge-rag` — 知识库/文档/向量/检索/**来源高亮档 B**/**意图类目 CRM 化**/授权/存储；移除匿名与独立对话层。
- `ai-assistant` — 助手吸收 RAG 对话能力（思考/记忆/图文/接管）、KB 手动开关、图片解耦、记忆持久化、空匹配修复、限流/引用/洞察/评测、`queryKnowledgeBase` 工具、Token 计量、payload 契约。
- `platform-governance` — traceId/MDC、线程池、韧性、配额、Token 预算、迁移、生命周期/对账、内容安全、审计、**Actuator 健康与指标**、质量基准。
- `dynamic-config` — 超管动态配置（含意图类目）。

### 受影响的代码（融合后布局）
- `com.slz.crm.common` / `com.slz.crm.server.*` — 业务与助手基座（业务原样；助手增强）。
- `com.slz.crm.server.ai.*` + `AiChatServiceImpl` — 吸收 RAG 对话能力（记忆/图片/思考/接管/KB 开关/工具/引用）。
- `com.slz.crm.pojo.entity`（`SysDeptEntity`+leaderId / `UserEntity` / `RoleAO`+deptId）+ `DataScopeLevel`/`PermissionOperates` + `DataScopeServiceImpl`/`QueryWrapperAspect`/`ResourceTypeConstant` — 部门数据权限（删 `DataScopeResolver`）。
- `com.slz.crm.knowledge.*`（重打包，JPA→MyBatis-Plus、LangChain4j→Spring AI）— 知识库能力（保留 7 表 + 移植服务，按页分块）。
- `com.slz.crm.platform.*` — 治理（trace/async/resilience/quota/token/migration/observability/audit）+ 动态配置 + ModelProvider/VectorStore 抽象 + `ai_conversation_memory`。

### 用户影响
- 上司（部门负责人/上级部门用户）可见下属及下级部门数据；普通用户仍限本人+共享。
- 助手可开/关知识库增强；答案带来源引用且前端可跳页高亮；超配额/越权/高风险内容明确拒绝。
- 匿名能力移除，原 RAG 公开接口收紧为登录态。

### API 变更
- 不主动破坏 CRM 既有响应；统一 `Result` 保持字段兼容。
- 助手请求契约增 `useKnowledgeBase`/`thinking`/`imageRef`；SSE 事件对齐（`start/sources/thinking/delta/references/done` + 心跳）；新增部门数据范围、知识库检索工具、配额/审计/对账/动态配置管理端接口。

### 需要迁移
- [ ] 数据库：CRM `sys_dept.leader_id`、DEPT 权限项、`ai_conversation_memory`、`ai_insight`、动态配置表、治理表（配额/Token/生命周期/对账/审计）；知识库 7 表新建；统一 Flyway 版本化（生产禁 `auto-table`/`ddl-auto`）。
- [ ] 依赖：合并 `pom.xml`（Boot 3.5.x BOM、Spring AI(spring-ai-alibaba/dashscope)、Qdrant VectorStore、MinIO、统一 MyBatis-Plus（不引 JPA/Hibernate）、jjwt 归一 0.12.5、springdoc、actuator、micrometer-prometheus、testcontainers）。
- [ ] 配置：合并 `application.yml`/`application.yaml`，敏感项外置，profile 分离，Actuator 暴露与保护。
- [ ] 文档：部署（Qdrant/MinIO/compose）、运维手册、告警阈值、回退步骤、权限矩阵。

## 时间线（Lane 收敛）

工作量集中在 AI/知识库 + 治理；按 6 lane 推进（详见 `agent-execution-plan.md`、`tasks.json`）：
1. **基座(Agent-0)**：技术栈统一、包边界、认证统一(移除 Security)、MyBatis+Flyway、Result/配置、Actuator 保护、契约冻结、compose。
2. **A 数据权限**：DataScopeLevel 扩展、部门树/负责人、RoleAO.deptId、两 switch、删死代码、贯通测试（纯 CRM 内，风险低）。
3. **B 知识库能力**：7 表新建、文档/检索/嵌入/视觉移植到 Spring AI、VectorStore 抽象+回退、按页分块(档 B)、SourceReference 锚点、CRM 意图类目、授权绑 userId、移除匿名/对话层。
4. **C 助手增强**：吸收对话能力（思考/记忆/图文/接管）、KB 手动开关、图片解耦、记忆持久化、空匹配修复、限流/references/citations/洞察/评测、`queryKnowledgeBase`、Token 计量、payload 契约。
5. **D 治理**：MDC/线程池(含记忆旁路)/韧性/配额/Token 预算(补盲点)/生命周期/对账/审计/Actuator 健康与指标/质量基准。
6. **E 动态配置 + 集成**：DynamicConfig（含意图类目）；串行合并、CI 门禁、回归基线（CRM 306 / RAG 491 重建）、发布检查。

## 风险

- **Boot 版本回填**：RAG 依赖 Boot 4（Jackson 3、starter 命名、Security 7）；回填 3.5.x 需机械适配。缓解：最小可编译骨架 + 关键链路冒烟（D1 已缩小到知识库模块）。
- **LangChain4j→Spring AI**：RAG 的 `.returnThinking(true)` 思考块透传 + `chat_template_kwargs/enable_thinking` + `StreamingChatResponseHandler/PartialThinking` 是最高风险点。缓解：B-spike 先验证 Spring AI 等价能力，出 go/no-go 与降级策略。
- **按页分块重构（档 B）**：当前 PDF 把全页 merge 成一个 `mergedText` 再整体分块，页边界丢失；改按页分块影响入库与既有分块语义。缓解：新平台新建、无存量包袱；补分块/高亮回归。
- **移除 Security 后 Actuator 裸奔**：必须重挂 CRM 拦截器保护，仅放行探针。缓解：基座阶段固化 `publicPaths` + 敏感端点超管校验。
- **认证统一回归面**：知识库授权从 Security 上下文改 CRM `UserContext`。缓解：保留授权判定点、仅换身份来源、补越权测试。
- **数据权限扩展影响既有查询**：新增 DEPT/DEPT_AND_CHILD 可能改变可见范围；两 switch `default→SELF` 漏改会静默降级。缓解：默认不授予、按角色灰度、补四级数据范围测试。
- **记忆持久化并发**：`ai_conversation_memory` 每轮高频写。缓解：乐观锁 version + 助手每会话 `AiStreamRegistry` 锁串行化。
- **新增基础设施**：Qdrant+MinIO 部署运维成本。缓解：compose + 健康检查 + 内存/文件回退（dev/test）。
- **范围过大**：严格按 lane 推进，每 lane 可审查/可回归/可回退（遵循"阶段审核后提交"硬规则）。
