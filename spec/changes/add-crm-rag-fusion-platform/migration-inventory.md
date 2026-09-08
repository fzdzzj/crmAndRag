# 迁移清单与接入分析（CRM 基座 + RAG 知识库能力 → crmAndRag）

> **★架构重定位（D11）**：以 CRM 为基座的**单体**，AI 助手**吸收** RAG 对话能力，知识库能力**移植**为 `com.slz.crm.knowledge`。RAG 资产按**四类处置**：✅ 移植(知识库) · 🔵 吸收进助手(对话能力) · 🟢 提升为平台(治理) · ⛔ 丢弃(独立对话层/匿名/死代码)。
> 本文件回应两份交接要求（CRM 迁移清单 / RAG 可复用·差异·顺序·回归）。表取舍见 `db-table-coordination.md`（保留 7 表、丢 3 表）；行为见 `assistant-decision-tree.md`。

---

## 一、技术栈差异总览（适配基线）

| 维度 | CRM（基座） | RAG（能力来源） | 处置 |
|------|------------|------------|----------|
| Spring Boot | 3.5.5 | 4.0.2 | 🔧 统一 3.5.x，知识库能力回填（D1） |
| Java | 21 | 21 | ✅ 一致 |
| 持久化 | MyBatis-Plus 3.5.6 + auto-table | Spring Data JPA（ddl-auto） | ⛔ 统一 MyBatis-Plus；知识库 **7 表新建**（借鉴 schema，非机械迁 JPA）+ Flyway（D2） |
| 认证 | JWT 拦截器 + `@RequirePermission` | Spring Security + JwtAuthFilter | ⛔ 统一 CRM 身份源，**移除 Spring Security**（D3） |
| AI 栈 | spring-ai-alibaba（dashscope） | LangChain4j（vllm/openai） | 🔧 统一 Spring AI（dashscope 默认）+ 可插拔 Provider（D4） |
| 对话层 | 助手 `ai_session/ai_message` + SSE | `chat_*` + RagChatPipeline + 匿名 | 🔵 对话能力**吸收进助手**；⛔ RAG 独立对话层丢弃（D11） |
| 向量库 | 无 | Qdrant（无本地回退） | 🔧 引入 + VectorStore 抽象 + 内存回退（D7/D9） |
| 对象存储 | 本地 `slz.file.path` | MinIO（+内存回退） | 🔧 引入 MinIO；CRM 附件本期并存（D7/C8） |
| JSON | Jackson 2 | Jackson 2 + Jackson 3（`tools.jackson`） | 🔧 回 Jackson 2 |
| JWT 库 | jjwt 0.12.5 | jjwt 0.11.5 | 🔧 归一 0.12.5 |
| Result | `com.slz.crm.common.result.Result` | `auth.common.Result` | ⛔ 统一到 CRM Result |
| 包根 | `com.slz.crm` | `com.mark.knowledge` | 🔧 重打包 `com.slz.crm.knowledge` |

---

## 二、CRM 侧清单（基座，业务原样搬入）

### 2.1 可直接复用（✅）
- 业务域全量：客户/联系人/商机/合同/订单/回款/发票/审批/协助/报表/统计/项目文件。
- AI 助手运行时（306 测试通过）：`AiChatServiceImpl`、`ai/AiChatStreamLifecycle`、`ai/AiStreamRegistry`、`ai/AiChatStreamHeartbeat`、`ai/AiChatSseEventWriter`、`ai/AiChatMetrics`、`controller/AiChatController`、`AiActionController`、`PendingActionServiceImpl`、`AiMessageServiceImpl`、`AiSessionServiceImpl`；Mapper `AiMessage/AiSession/AiPendingAction/AiToolCallLog`；`properties/AiProperties`；评测 `ai-eval/cases.json`。
- 通用件：`common/result/Result`、`common/exiception/*`、`common/untils/*`、`common/enumeration/*`、`server/handler/GlobalExceptionHandler`。

### 2.2 必须适配（🔧）
- `DataScopeLevel` 新增 `DEPT`/`DEPT_AND_CHILD`（D6）；`PermissionOperates` 补 `_DEPT`/`_DEPT_AND_SUB`。
- `DataScopeServiceImpl` + `aspect/QueryWrapperAspect` + `constant/ResourceTypeConstant`：新增"下属集合解析 + 每表归属字段过滤"（真实机制，**非 MyDataPermissionHandler——不存在**）；`RoleAO` 加 `deptId`（现无）；**删死代码 `DataScopeResolver(Impl)`**；两个 `addDataScopeCondition` 重载的 `switch` 都要加 case（否则 default→SELF 静默降级）。
- `SysDeptEntity` 加 `leaderId`；`UserEntity` 复用 `deptId`（可选 `managerId`）。
- `application.yml`：并入知识库配置命名空间；生产 `auto-table.mode=none`（改走 Flyway）；**Actuator 挂 JWT 拦截器保护**（移除 Security 后）。
- AI 工具注册表：新增只读工具 `queryKnowledgeBase`（调用知识库检索能力）。

### 2.3 CRM 数据表（MySQL，节选）
- 业务：`customer_company`、`customer_contact`、`sales_opportunity`、`contract`、`contract_order_item`、`invoice_info`、`payment_record`、`business_activity`、`contact_task`、`sales_stage_approval`、`project_file`。
- 组织/权限：`sys_user`(真名)、`sys_role`(真名)、`permissions`、`role_permissions`、`sys_dept`、`company_dept`、`company_group`、`tage_role_binding`、`tage_resource_binding`、`data_share`、`user_handover`。
- AI：`ai_session`、`ai_message`、`ai_pending_action`、`ai_tool_call_log`；（新增 V4x）`ai_conversation_memory`（记忆加工品）、`ai_insight`。
- 数据权限受控表：`MANAGED_TABLES`（**10** 张，含 `customer_contact`）；`TABLE_PERMISSIONS` 配三元组的 **9** 张；每表归属字段见 `TABLE_USER_FIELDS`。

### 2.4 CRM 配置项 / API（保留，节选）
- 配置：`spring.ai.dashscope.*`、`crm.ai.*`（max-history-rounds/pending-expire/rate-limit/llm-timeout/max-fix-rounds/fallback-model/heartbeat/sse-timeout/thread-pool）、`slz.jwt.*`、`slz.file.path`、`auto-table.*`、`mybatis-plus.*`、`management.*`。
- API：AI `POST /ai/chat/stream`(SSE)、`/ai/action/**`；业务 `/customer/**`、`/contract/**`、`/sales-opportunity/**`、`/payment/**`、`/report/**`；组织 `/sys-dept/**`、`/role/**`、`/user/**`。

---

## 三、RAG 侧清单（四类处置）

### 3.1 ✅ 移植为知识库模块（`com.slz.crm.knowledge`，LangChain4j→Spring AI）
- 授权：`KnowledgeBaseAuthorizationService`、`RagRetrievalAccessFilter`、`ResourceAccessPolicy`（判定逻辑保留，身份换 CRM userId）。
- 文档处理：`rag/service/Impl/*`（多格式解析、PDF/Excel/OCR/视觉抽取、批量/分片上传、向量快照）。
- 检索/嵌入：`RagRetrievalService`、`HybridImageTextRetrievalService`、`EmbeddingService`、`ImageEmbeddingService`、`Bm25Scorer`。
- 存储：`storage/*`（`MinioFileStorageServiceImpl`、`InMemoryFileStorageServiceImpl`、`StorageKeySupport`）；`QdrantInitializer`。

### 3.2 🔵 吸收进助手（Lane C，`server.ai.*`；移植逻辑，不移植独立入口）
- `RagChatPipeline`（问答主干：改写/检索编排/prompt 组装/连接重试）、`RagStreamSessionManager`（流式/接管/`shouldAbort`/事件下发）。
- `RagContextAssembler`（prompt 装配 + 短问题规则改写）、`RagMemoryOrchestrator` + `ConversationMemoryService`（记忆 9 措施）。
- `ImageConversationCacheService` + `ImageUnderstandingService`（图片理解/缓存，按 D13 解耦改造）。
- `ChatConfig` 思考参数（`.returnThinking`/`enable_thinking`）+ `ThinkTagStripper`（按 B-spike 结论迁 Spring AI）。

### 3.3 🟢 提升为平台治理（`com.slz.crm.platform`）
- `RequestTraceFilter`、`RequestTraceKey`、`MdcTaskDecorator`、`AsyncConfig`、`DependencyResilienceExecutor`、`ProductionConfigurationGuard`、`TaskExecutorMetricsBinder`、`config/storage/*HealthIndicator`、`AiCallLogHelper`、`StreamErrorClassifier`。

### 3.4 ⛔ 丢弃（不迁）
- 独立对话层：`AnonymousRagChatService`、`AuthenticatedRagChatService`、`UnifiedRagService`、独立会话控制器、`chat_conversation`/`chat_message` 表。
- 独立认证：`SecurityConfig`、`JwtAuthFilter`、静态账号 `app.users`、`teacher_account` 表。
- 死代码：`QueryIntentClassifier`（技术栈类目、无调用点 → 新建 CRM 域意图/类目替代，D17）。

### 3.5 RAG 数据表处置
- **保留 7 表新建**：`knowledge_base`、`knowledge_base_member`、`uploaded_file`、`document_vector_chunk`、`chunk_upload_session`、`batch_task`、`batch_file_result`。
- **丢弃 3 表**：`chat_conversation`、`chat_message`（→ `ai_session`/`ai_message`）、`teacher_account`（→ `sys_user`）。

### 3.6 RAG API 处置
- **保留（适配）**：文档 `/api/documents/**`（上传/列表/删除/向量详情/批量/分片/重建）→ 改返回 CRM `Result` + CRM 登录态。
- **丢弃**：问答/会话 `/api/rag/**`（普通问答、流式问答、会话历史）→ 对话统一走助手 `POST /ai/chat/stream`；认证 `/api/auth/**` → 收敛到 CRM 登录。

---

## 四、必须适配的 RAG 差异点（🔧）
- 身份：`UserIdentityVerifier`/`SecurityUser`/`JwtAuthFilter` → 消费 CRM `UserContext` 稳定 userId（`user:<id>`）。
- 授权主体：`KnowledgeBaseMemberEntity` owner/member 由 `teacher:<id>`/`username` → CRM `user:<id>`。
- 检索授权过滤：消除 `findAllByDeletedFalseOrderByUploadTimeDesc()` 等全表扫描（`UploadedFileRepository` 6 个 `findAllBy*`）→ 按 documentId 集合/知识库范围查询。
- Jackson 3(`tools.jackson`)→2；`spring-boot-starter-webmvc`→`web`；**移除 Spring Security**（非降级到 6）。
- 持久化：7 表用 MyBatis-Plus 新建；移植仓储逻辑（派生查询→`LambdaQueryWrapper`、`@Modifying`→`@Update` 保留状态条件）；移除 Hibernate/`ddl-auto`。
- AI 栈：`dev.langchain4j.*`（chat/embedding/vision）→ Spring AI `ChatModel`/`EmbeddingModel`（默认 dashscope）。
- 向量库：`langchain4j-qdrant` EmbeddingStore → 平台 `VectorStore` 抽象（Qdrant 默认 + 内存回退）。

---

## 五、需重写的边界（融合"缝合线"）
1. **身份缝合**：CRM `UserContext`(ThreadLocal) → 知识库授权入参(stable userId)；异步/流式经 `MdcTaskDecorator` + 上下文快照传播。
2. **Result 缝合**：知识库控制器返回 CRM `Result`；SSE 错误沿用 `StreamErrorClassifier` + CRM 脱敏。
3. **SSE 缝合（统一到助手）**：事件名冻结为 `start/meta/sources/thinking/delta/references/done/cancelled/stopped/error` + `ping`(心跳)；**修正**原 CRM `text/done/stopped/title` 与 RAG `start/sources/delta/complete/cancelled/error` 两套；超时/心跳/取消接管统一口径（RAG 独立 SSE 已丢弃，能力并入助手）。
4. **数据权限缝合**：CRM `DataScopeServiceImpl`（经 `QueryWrapperAspect`，含部门维度）为业务可见性唯一口径；知识库可见性 = KB 授权 ∩（可选）数据范围；AI 工具查询同受两者约束。
5. **Token 计量缝合**：`ai_message.tokenCount` + 知识库问答 + **意图/摘要（补 RAG `chat(String)` 漏计盲点）** → 平台统一计量 + 预算执行器。
6. **建表缝合**：`auto-table`(CRM)/`ddl-auto`(RAG) → 统一 Flyway；**V1 仅 CRM 表**，知识库 7 表在 V3x 新建。
7. **ModelProvider 缝合**：`spring.ai.dashscope` 与 RAG `llm.*` → 平台 `ModelProvider` 抽象（默认 dashscope，可切；**须返回带 usage 的响应**）。
8. **VectorStore 缝合**：`langchain4j-qdrant` → 平台 `VectorStore`（Qdrant + 内存回退）。
9. **记忆缝合**：`ai_message` 唯一真相源，`recentMessages` 内存投影，`summary/facts/intent` → `ai_conversation_memory`（D14）。

---

## 六、数据库迁移方案（Flyway 号段见 plan §5）
1. **基座阶段（Agent-0）**引入 Flyway：`V1__baseline.sql` = **仅 CRM 现有表**（真实名 `sys_user/sys_role/sys_dept`… 36 张）。
2. 增量：`V2x__org_data_scope`(A：`sys_dept.leader_id` + DEPT 权限种子) · `V3__knowledge`(B：知识库 7 表新建 + 授权 userId + `uploaded_file.knowledge_base` 回填) · `V4x`(C：`ai_conversation_memory` + `ai_insight`) · `V5x__governance`(D：配额/Token/生命周期/对账/审计) · `V6x__dynamic_config`(E：配置项 + 版本历史)。
3. 生产 profile：`auto-table.mode=none`、无 JPA/Hibernate、Flyway 开启；提供备份/演练/回退脚本。
4. 若要保留 RAG 存量数据：另附 `ALTER TABLE ... RENAME`（表名单数化/审计列/`deleted`→`is_deleted`/`deleted_by`→userId）迁移脚本（并入 V3x）；否则全新建表无存量迁移。

---

## 七、建议顺序（对齐 tasks.json 18 任务 / 6 lane）
1. **基座（Agent-0，任务 1-4）**：Boot 3.5.x 骨架 → 并入 CRM 全量 → 统一 Result/配置/profile → **移除 Security + Actuator 保护** → Flyway V1 → 冻结契约。
2. **A 数据权限（5-6）**：DataScopeLevel 扩展 + 部门树/负责人 + RoleAO.deptId + 两 switch + 删死代码 + 测试（纯 CRM 内，先做）。
3. **B 知识库能力（7-9）**：重打包 `com.slz.crm.knowledge` + 7 表新建 + 文档/检索/嵌入/视觉移植 Spring AI + VectorStore 抽象 + Qdrant/MinIO + 授权 userId + 移除匿名/对话层 + 按页分块(档B) + SourceReference 锚点 + 意图 CRM 化。
4. **C 助手增强（10-12）**：吸收对话能力（思考/记忆持久化/图文解耦/接管）+ KB 手动开关 + 空匹配修复 + queryKnowledgeBase + 来源高亮/payload + 限流/references/洞察/评测/Token。
5. **D 治理（13-15）+ E 动态配置（16）**：MDC/线程池(含记忆旁路)/韧性/配额/Token 预算(补盲点)/生命周期/对账/审计/Actuator 健康指标；DynamicConfig（含意图类目/strict-KB）。
6. **集成（Integrator，17-18）**：质量基准 + 检索性能 + 契约/CI 门禁 + 串行合并 + 灰度/回退。

---

## 八、风险与回归测试点
### 8.1 回归重点（缝合线）
- **认证/授权**：CRM 登录态访问知识库；成员/负责人/管理员/越权矩阵；userId 映射；匿名已移除。
- **数据权限**：本人/本部门/本部门及以下/全部 四级；负责人跨子部门；AI 工具同口径；两 switch 均生效。
- **助手对话**：思考透传/剥离、记忆持久化(重启恢复)、图文解耦、接管 shouldAbort、KB 开/关、**空匹配修复(KB OFF 不吐兜底)**、来源高亮/citations、SSE 超时/心跳。
- **文档与检索**：多格式解析/OCR/视觉、**按页分块 pageNo**、向量化、混合检索命中、授权过滤(无全表扫描)、Qdrant 恢复语义。
- **配置与启动**：生产配置保护、Qdrant/MinIO 健康、Actuator 保护、Flyway 迁移/回滚、线程池指标。
- **成本**：Token 计量落库（含意图/摘要）、预算检查、超限告警。

### 8.2 测试基线（★拆分，非原样重建）
- CRM：`mvn -B -ntp "-Djacoco.skip=true" test`（原 **306** 全量重建，JaCoCo 80%）。
- RAG 原 **491**（485 通过 + 6 Docker 跳过）= **知识库能力测试子集**（Lane B 重建）+ **对话层测试**（随 `chat_*`/`RagChatPipeline` 丢弃，其能力在 Lane C 以助手测试重建）——**不是 491 原样重建**。
- 融合后：统一回归门禁（契约 + 数据权限 + 助手对话/检索冒烟 + 迁移演练）。

### 8.3 硬性协作规则（沿用两源项目）
- 禁止直接提交 master；禁止推送远程（push 需用户显式授权）；只在独立分支/worktree 开发。
- 每阶段完成先出可审查变更摘要，确认后再提交；不顺手重构；库结构变更写明影响；敏感信息不入库；中文 Javadoc 注释风格。
