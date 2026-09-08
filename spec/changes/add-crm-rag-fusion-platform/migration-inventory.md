# 迁移清单与接入分析（CRM AI + RAG → crmAndRag）

> **★架构重定位（D11）**：以 CRM 为基座的单体，AI 助手吸收 RAG 对话能力，知识库能力移植为 `com.slz.crm.knowledge`。下文"RAG 迁入"均指**知识库能力移植**；RAG 独立对话层（chat_*/RagChatPipeline/匿名/独立登录）**丢弃不迁**。表取舍见 `db-table-coordination.md`（保留 7 表、丢 3 表）。
> 本文件同时回应两份交接要求：
> - CRM：迁移文件/依赖/数据表/配置/API 清单、需重写的边界、认证权限适配、SSE 适配、DB 迁移、回归方案。
> - RAG：可复用模块清单、需适配差异点、迁移顺序、风险与回归点。
> 分类图例：✅ 可直接复用 · 🔧 必须适配 · ⛔ 不能照搬（需重写/替换）

---

## 一、技术栈差异总览（适配基线）

| 维度 | CRM（基座） | RAG（迁入） | 融合处置 |
|------|------------|------------|----------|
| Spring Boot | 3.5.5 | 4.0.2 | 🔧 统一到 3.5.x（D1） |
| Java | 21 | 21 | ✅ 一致 |
| 持久化 | MyBatis-Plus 3.5.6 + auto-table | Spring Data JPA（ddl-auto:update） | ⛔ 归一 MyBatis-Plus（RAG JPA 迁移）+ Flyway（D2） |
| 认证 | JWT 拦截器 + `@RequirePermission` | Spring Security + JwtAuthFilter | ⛔ 统一到 CRM 身份源（D3） |
| AI 栈 | spring-ai-alibaba（dashscope） | LangChain4j（vllm/openai） | 🔧 统一 Spring AI（dashscope 默认）+ 可插拔 Provider；RAG LangChain4j 迁移（D4） |
| 向量库 | 无 | Qdrant（无本地回退） | 🔧 引入 + VectorStore 抽象 + 内存回退（D7/D9） |
| 对象存储 | 本地 `slz.file.path` | MinIO（+内存回退） | 🔧 引入 MinIO，CRM 附件评估迁移（D7） |
| JSON | Jackson 2 | Jackson 2 + Jackson 3（`tools.jackson`） | 🔧 回 Jackson 2 |
| JWT 库 | jjwt 0.12.5 | jjwt 0.11.5 | 🔧 归一 0.12.5 |
| Result | `com.slz.crm.common.result.Result` | `com.mark.knowledge.auth.common.Result` | ⛔ 统一到 CRM Result |
| 包根 | `com.slz.crm` | `com.mark.knowledge` | 🔧 重打包 `com.slz.crm.knowledge` |

---

## 二、CRM 侧清单

### 2.1 可直接复用（✅）
- 业务域全量：客户/联系人/商机/合同/订单/回款/发票/审批/协助/报表/数据统计/项目文件。
- AI 助手运行时（交接文档确认已优化、306 测试通过）：
  - `service/impl/AiChatServiceImpl.java`、`ai/AiChatStreamLifecycle.java`、`ai/AiStreamRegistry.java`
  - `ai/AiChatStreamHeartbeat.java`、`ai/AiChatSseEventWriter.java`、`ai/AiChatMetrics.java`
  - `controller/AiChatController.java`、`controller/AiActionController.java`
  - `service/impl/PendingActionServiceImpl.java`、`service/impl/AiMessageServiceImpl.java`、`service/impl/AiSessionServiceImpl.java`
  - Mapper：`AiMessageMapper`/`AiSessionMapper`/`AiPendingActionMapper`/`AiToolCallLogMapper`
  - 配置：`properties/AiProperties.java`；评测：`src/test/resources/ai-eval/cases.json` + `ai/eval/*`
- 通用件：`common/result/Result`、`common/exiception/*`、`common/untils/*`、`common/enumeration/*`、`server/handler/GlobalExceptionHandler`。

### 2.2 必须适配（🔧）
- `common/enumeration/DataScopeLevel`：新增 `DEPT`、`DEPT_AND_CHILD`（D6）。
- `common/enumeration/PermissionOperates`：为受控表补 `_DEPT` / `_DEPT_AND_SUB` 权限项。
- `service/impl/DataScopeServiceImpl` + `aspect/QueryWrapperAspect` + `constant/ResourceTypeConstant`：新增“下属用户集合解析 + 每表归属字段过滤”（真实机制，**非 MyDataPermissionHandler——该类不存在**）；`pojo/ao/RoleAO` 加 `deptId`（现无）；**删除死代码 `DataScopeResolver`/`DataScopeResolverImpl`**。
- `pojo/entity/SysDeptEntity`：新增 `leaderId`（部门负责人）；`pojo/entity/UserEntity`：复用 `deptId`（可选 `managerId`）。
- `application.yml`：合并 RAG 配置命名空间；`auto-table.mode` 生产改 `none`（改走 Flyway）。
- AI 工具注册表：新增只读工具 `queryKnowledgeBase`（调用迁入的 RAG 检索）。

### 2.3 不能照搬 / 需替换（⛔）
- CRM 独立 JWT 签发与 RAG 独立 JWT 签发**二选一**：统一为 CRM `slz.jwt`（D3）。
- `spring.ai.dashscope` 与 RAG `llm.*` 的密钥/超时配置**不可分散**：统一到平台 LLM 配置命名空间（D4）。

### 2.4 CRM 数据表（MySQL，节选）
- 业务：`customer_company`、`customer_contact`、`sales_opportunity`、`contract`、`contract_order_item`、`invoice_info`、`payment_record`、`business_activity`、`contact_task`、`sales_stage_approval`、`project_file`。
- 权限/组织：`user`、`role`、`permissions`、`role_permissions`、`sys_dept`、`company_dept`、`company_group`、`tage_role_binding`、`tage_resource_binding`、`data_share`、`user_handover`。
- AI：`ai_session`、`ai_message`、`ai_pending_action`、`ai_tool_call_log`；（新增）`ai_insight`。
- 数据权限受控表：`ResourceTypeConstant.MANAGED_TABLES`（**10** 张，含 `customer_contact`）；`DataScopeServiceImpl.TABLE_PERMISSIONS` 配权限三元组的 **9** 张（`customer_company`、`sales_opportunity`、`contract_order_item`、`contract`、`business_activity`、`contact_task`、`sales_stage_approval`、`payment_record`、`project_file`）；每表归属字段见 `TABLE_USER_FIELDS`。

### 2.5 CRM 配置项（需并入统一配置）
- `spring.ai.dashscope.*`、`spring.ai.chat.options.*`
- `crm.ai.*`（max-history-rounds / pending-expire-minutes / rate-limit-per-minute / entity-query-page-size / llm-timeout-seconds / max-fix-rounds / fallback-model / static-fallback-message / heartbeat-* / sse-timeout-seconds / thread-pool.{chat,title,audit}）
- `slz.jwt.*`、`slz.attach-token.*`、`slz.file.path`、`slz.birthday.reminder.*`、`slz.database-field-map`
- `auto-table.*`、`mybatis-plus.*`、`permission.sync.*`、`data.init.*`、`management.*`

### 2.6 CRM API（保留，节选）
- AI：`POST /ai/chat/stream`（SSE）、`/ai/action/**`（确认/取消）、会话与消息查询。
- 业务：`/customer/**`、`/contract/**`、`/sales-opportunity/**`、`/payment/**`、`/invoice/**`、`/report/**`、`/data-statistics/**`。
- 组织/权限：`/sys-dept/**`、`/company-dept/**`、`/company-group/**`、`/role/**`、`/permission/**`、`/user/**`、`/user-handover/**`。

---

## 三、RAG 侧清单

### 3.1 可直接复用（✅，重打包后）
- 知识库授权：`rag/service/KnowledgeBaseAuthorizationService`、`rag/service/rag/RagRetrievalAccessFilter`、`rag/service/ResourceAccessPolicy`。
- 检索/问答：`rag/service/rag/*`（`RagChatPipeline`、`RagRetrievalService`、`HybridImageTextRetrievalService`、`RagContextAssembler`、`RagMemoryOrchestrator`、`RagStreamSessionManager`、`UnifiedRagService`、`AuthenticatedRagChatService`/`AnonymousRagChatService`）。
- 文档处理：`rag/service/Impl/*`（多格式解析、PDF/Excel/OCR/视觉抽取、批量/分片上传、向量快照）。
- 嵌入/评分：`rag/service/EmbeddingService`、`ImageEmbeddingService`、`Bm25Scorer`、`QueryIntentClassifier`。
- 存储：`storage/*`（`MinioFileStorageServiceImpl`、`InMemoryFileStorageServiceImpl`、`StorageKeySupport`）。
- 治理基础设施（提升为 `com.slz.crm.platform.*`）：`config/RequestTraceFilter`、`RequestTraceKey`、`MdcTaskDecorator`、`AsyncConfig`、`DependencyResilienceExecutor`、`ProductionConfigurationGuard`、`TaskExecutorMetricsBinder`、`RequestLoggingInterceptor`、`QdrantInitializer`、`config/storage/*HealthIndicator`。
- AI 日志：`rag/common/AiCallLogHelper`、`AiLogFields`、`AiLogProperties`、`StreamErrorClassifier`、`ThinkTagStripper`。

### 3.2 必须适配（🔧）
- 身份：`auth/service/UserIdentityVerifier`、`auth/models/SecurityUser`/`UserInfo`、`auth/filter/JwtAuthFilter` → 改为消费 CRM `UserContext` 稳定 userId（`user:<id>`）（D3）。
- 授权主体：`KnowledgeBaseMemberEntity` 的 owner/member 从 `teacher:<id>`/`config:<username>` → CRM `user:<id>`；存量映射迁移。
- 检索授权过滤：`EmbeddingAuthorizationFilter`/`RagRetrievalAccessFilter` 的全表查询 `findAllByDeletedFalseOrderByUploadTimeDesc()` → 按 documentId 集合或知识库范围查询（HANDOFF 3.4）。
- Jackson：`tools.jackson`（Jackson 3）→ Jackson 2（D1）。
- Starter：`spring-boot-starter-webmvc` → `spring-boot-starter-web`；Security 7 → 6（D1/D3）。
- 配置：`llm.*`/`qdrant.*`/`rag.*`/`storage.minio.*`/`app.async.*`/`app.dependency.*` 并入统一配置；`app.jwt.*`/`app.users` 移除（统一 CRM 身份）。
- `Result`：`auth/common/Result` → CRM `Result`（⛔ 替换）。
- 持久化：`rag/repository/*`（JPA Repository）→ MyBatis-Plus Mapper；JPA 实体注解（`@Entity`/`@Table`/`@Column`）→ MyBatis-Plus（`@TableName`/`@TableId`/`@TableField`）；移除 Hibernate/`ddl-auto`（D2）。
- AI 栈：`EmbeddingService`/`ImageEmbeddingService`/`RagChatPipeline`/vision 等 LangChain4j 调用（`dev.langchain4j.*`）→ Spring AI `ChatModel`/`EmbeddingModel`（默认 dashscope）（D4）。
- 向量库：`langchain4j-qdrant` EmbeddingStore → 平台 `VectorStore` 抽象（`QdrantVectorStore` 默认 + `InMemoryVectorStore` 回退）（D9）。
- 匿名态：移除 `AnonymousRagChatService` 及匿名分流，问答统一走已认证路径（D8）。

### 3.3 不能照搬 / 需替换（⛔）
- RAG 独立登录/静态管理员账号（`app.users` admin）与独立 JWT：移除，统一 CRM 认证（D3）。
- RAG `SecurityConfig` 过滤链：精简为放行 + CRM 拦截器统一鉴权，或移除。
- `ddl-auto: update`：生产禁用，改 Flyway（D2 / 治理）。

### 3.4 RAG 数据表（JPA 实体 → MySQL）
- 知识库：`knowledge_base`、`knowledge_base_member`（含 role/visibility）。
- 文档/向量：`uploaded_file`（含 `knowledge_base` 字段）、`document_vector_chunk`、`chunk_upload_session`。
- 批量任务：`batch_task`、`batch_file_result`。
- 会话：`chat_conversation`、`chat_message`。
- 认证：`teacher_account`（融合后由 CRM `user` 取代，需映射/停用）。
- 治理新增（本提案）：配额、Token 计量、生命周期事件、跨存储对账账本、审计事件。

### 3.5 RAG API（保留，节选）
- 文档：`/api/documents/**`（上传/列表/删除/向量详情/批量/分片/重建）。
- 问答：`/api/rag/**`（普通问答、流式问答 SSE、会话历史）。
- 认证：`/api/auth/**`（融合后收敛到 CRM 登录）。

---

## 四、需重写的边界（融合“缝合线”）

1. **身份缝合**：CRM `UserContext`（ThreadLocal）→ RAG 授权入参（stable userId）。异步/流式线程需通过 `MdcTaskDecorator` + 安全上下文/用户上下文快照传播。
2. **Result 缝合**：RAG 控制器返回 CRM `Result`；SSE 错误事件沿用 RAG `StreamErrorClassifier` + CRM 脱敏策略。
3. **SSE 缝合**：CRM 助手 SSE（`AiChatSseEventWriter` + 心跳 + 接管）与 RAG 流式问答 SSE（`RagStreamSessionManager` + `rag.stream-timeout-ms`）**统一心跳/超时/取消/指标口径**，共用线程池治理与 traceId。
4. **数据权限缝合**：CRM `DataScopeServiceImpl`（经 `QueryWrapperAspect` 注入，含部门维度）作为业务数据可见性唯一口径；RAG 知识库可见性 = 知识库授权 ∩（可选）数据范围。AI 工具查询同时受两者约束。
5. **Token 计量缝合**：CRM `AiMessage.tokenCount` + RAG `AiUsageInfo` → 平台统一计量表与预算执行器。
6. **建表缝合**：`auto-table`（CRM）与 `ddl-auto`（RAG）→ 统一 Flyway 基线 + 版本化迁移（RAG 原由 Hibernate 自动建表，现需将表 DDL 显式写入基线脚本）。
7. **模型 Provider 缝合**：CRM `spring.ai.dashscope` 与 RAG `llm.*`（vllm/openai）→ 平台统一 `ModelProvider` 抽象（默认 dashscope，可切换），chat/embedding/vision 统一入口。
8. **向量库缝合**：RAG `langchain4j-qdrant` → 平台 `VectorStore` 抽象（Qdrant 默认 + 内存回退）。

---

## 五、SSE / 流式接口适配方案

- **保留** CRM 助手 SSE 协议（`message`/`stopped`/`references`/`error` 事件、生成中接管、部分回答保存、心跳线程池）。
- **保留** RAG 流式问答（思考模式透传开关、prompt 字符预算闸门、记忆旁路有界队列 + 拒绝降级）。
- **统一**：
  - 超时：`crm.ai.sse-timeout-seconds` 与 `rag.stream-timeout-ms` 收敛为平台级默认 + 各自覆盖。
  - 心跳：统一心跳调度池（复用 CRM `AiChatStreamHeartbeat` 可配置池）。
  - 取消/接管：统一“同会话新请求取消旧流”的语义（CRM 接管锁 + RAG `RagStreamSessionManager`）。
  - traceId：SSE 异步线程通过 `MdcTaskDecorator.wrap` 传播 traceId（治理阶段）。
  - 指标：首字延迟、总耗时、活跃流、取消/失败统一注册到 Micrometer。

---

## 六、数据库迁移方案

1. 引入 **Flyway**（治理阶段），以“CRM 现有表 + RAG 现有表”生成基线脚本 `V1__baseline.sql`。
2. 增量脚本：
   - `V2__org_data_scope.sql`：`sys_dept.leaderId`；（可选）`user.managerId`；DEPT/DEPT_AND_SUB 权限项种子。
   - `V3__kb_authorization_userid.sql`：知识库成员 owner/member 从 username/teacher → CRM userId 映射；`uploaded_file.knowledge_base` 归属回填策略。
   - `V4__ai_insight.sql`：主动洞察表。
   - `V5__governance.sql`：配额、Token 计量、生命周期事件、对账账本、审计事件表。
   - `V6__dynamic_config.sql`：动态配置项表 + 版本历史表（超管运行期配置，含提示词）。
3. 生产 profile：`auto-table.mode=none`、移除 JPA/Hibernate（无 ddl-auto）、Flyway 开启；提供备份/演练/回退脚本。

---

## 七、建议迁移顺序（分阶段，与 tasks.json 对齐）

1. **基座**：建空项目骨架（Boot 3.5.x）→ 并入 CRM 全量（可编译可跑）→ 建立统一 `Result`/异常/配置/profile → 引入 Flyway 基线。
2. **部门数据权限**：`DataScopeLevel` 扩展 + 部门树/负责人 + `DataScopeServiceImpl`/`QueryWrapperAspect`（删除死代码 `DataScopeResolver`）+ 测试（纯 CRM 内，风险低，先做）。
3. **RAG 迁入**：重打包 `com.slz.crm.knowledge.*` → 引 Qdrant/MinIO → 身份缝合（CRM userId）→ 检索授权过滤优化 → 文档/检索/流式问答冒烟。
4. **AI 助手统一**：`queryKnowledgeBase` 工具 + 限流落地 + references + 洞察 + Token 计量统一。
5. **平台治理**：MDC/线程池/韧性/配额/预算/生命周期/对账/内容安全/审计/可观测性。
6. **质量与门禁**：RAG 质量基准 + 检索性能优化 + 契约/性能测试 + CI 门禁 + 灰度/回退。

---

## 八、风险与回归测试点

### 8.1 回归重点（融合缝合线）
- **认证/授权**：CRM 登录态下访问知识库；匿名/成员/负责人/管理员/越权矩阵；知识库成员 userId 映射正确性。
- **数据权限**：本人 / 本部门 / 本部门及以下 / 全部 四级可见性；负责人跨子部门可见；AI 工具查询同口径。
- **流式问答**：安全上下文 + traceId 跨线程、拒绝降级、连接重试、思考透传、生成中接管、部分回答保存、SSE 超时/心跳。
- **文档与检索**：多格式解析/OCR/视觉、向量化、混合检索命中、检索授权过滤（按需查询，无全表扫描）、Qdrant 写入恢复语义。
- **配置与启动**：生产配置保护、Qdrant/MinIO 健康检查、Flyway 迁移/回滚、线程池指标。
- **成本**：Token 计量落库、预算检查、超限告警（chat/embedding/ocr/vision 分策略）。

### 8.2 源项目测试基线（迁移后须重建）
- CRM：`mvn -B -ntp "-Djacoco.skip=true" test`（原 306 通过，JaCoCo 80%）。
- RAG：`mvnw test`（surefire 实测 491：485 通过 + 6 个 Docker 集成测试跳过）。
- 融合后：建立统一回归门禁（契约测试 + 数据权限测试 + 流式/检索冒烟 + 迁移演练）。

### 8.3 硬性协作规则（沿用两源项目）
- 禁止直接提交 main/master；禁止推送远程；只在独立分支开发。
- 每阶段完成前先展示可审查变更摘要，用户确认后再提交。
- 不顺手重构；数据库结构变更必须写明影响；敏感信息不入库；注释遵循项目中文 Javadoc 风格。
