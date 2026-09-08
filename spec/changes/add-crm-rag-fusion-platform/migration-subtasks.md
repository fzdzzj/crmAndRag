# D2 & D4 细化迁移子任务

> 本文件把工作量最大的两项拆到可执行粒度，对应 `tasks.json` 的**任务 4（持久化归一）**与**任务 7（RAG AI 栈迁移）**。
> 规模图例：S=小(≤0.5天) · M=中(0.5–2天) · L=大(2–5天) · XL=特大(>5天，需再拆)
> 全部基于对 RAG 源码的实际盘点（`ChatConfig`、9 个 Repository、9 个 Entity、LangChain4j 触点）。

---

# Part A — D2：RAG JPA → MyBatis-Plus 迁移

## A0. 前置：持久化语义差异清单（S）
迁移前必须明确的 JPA↔MyBatis-Plus 语义差异，作为 A1–A3 的判据：

| JPA 行为 | MyBatis-Plus 对应 | 迁移注意 |
|----------|-------------------|----------|
| `save(e)`＝按 id upsert | `insert(e)` / `updateById(e)` 分离 | 服务层需按“id 是否存在”分流 |
| 脏检查 + 自动 flush | 无，需显式 update | 修改实体后必须显式调用更新 |
| 派生查询 `findByX` | `LambdaQueryWrapper` | 逐个方法改写 |
| `@Query` JPQL | XML / `@Select` 原生 SQL | JPQL 实体名→表名/列名 |
| `@Modifying @Query` | `@Update` | 保留状态条件（乐观更新） |
| `deleteByX` 返回 `long` | `delete` 返回 `int` | 需捕获删除计数 |
| `@Enumerated(STRING)` | `@EnumValue`/`IEnum`/TypeHandler | 枚举存字符串保持一致 |
| `@OneToMany` 等关联 | 无（本项目**实体全扁平**，无关联） | ✅ 无关联拆解成本 |

## A1. 实体注解迁移（9 个实体）（M）
注解映射：`@Entity`+`@Table(name=...)` → `@TableName(...)`；`@Id`+`@GeneratedValue(IDENTITY)` → `@TableId(type=IdType.AUTO)`；`@Column` → `@TableField`；`@Enumerated(STRING)` → `@EnumValue` 或 `IEnum`；`@Table(indexes/uniqueConstraints)` → 移到 Flyway DDL（A4）。

| 实体 | 表名 | 关键点 |
|------|------|--------|
| `BatchFileResultEntity` | batch_file_results | 索引 |
| `BatchTaskEntity` | batch_tasks | `@Enumerated` 状态机 `BatchTaskStatus` |
| `ChatConversationEntity` | chat_conversations | `username`→`userId`（A5） |
| `ChatMessageEntity` | chat_messages | `username`→`userId`（A5） |
| `ChunkUploadSessionEntity` | chunk_upload_sessions | 索引 |
| `DocumentVectorChunkEntity` | document_vector_chunks | 向量分块元数据 |
| `KnowledgeBaseEntity` | knowledge_bases | `@Enumerated` 可见性 + 唯一约束 |
| `KnowledgeBaseMemberEntity` | knowledge_base_members | `@Enumerated` 角色；`userId` 为 String（stable userId，对齐 D3） |
| `UploadedFileEntity` | uploaded_files | `knowledge_base` 归属字段、软删除标志 |

- [ ] 逐实体替换注解，保留中文 Javadoc 注释风格
- [ ] 枚举字段确认存储形态（STRING）与 MyBatis-Plus 处理一致
- [ ] `@Table` 内索引/唯一约束登记到 A4 的 DDL 清单

## A2. Repository → Mapper（9 个，逐方法）（L）
| 仓储 | 代表方法 | 迁移方式 |
|------|----------|----------|
| `BatchTaskRepository` | `findByTaskId` / `findAllByStatusInAndUpdatedTimeBefore` / `existsByStatusIn` / `@Modifying @Query updateStatusIfMatch(int)` | Wrapper + `@Update`（保留状态条件乐观更新，用于崩溃恢复扫描） |
| `ChatConversationRepository` | `findByUsernameAndConversationId` / `findByUsernameOrderByUpdatedTimeDesc` / `deleteByUsernameAndConversationId(long)` / `deleteByConversationId(long)` / 2× `@Query` | Wrapper + XML；`username`→`userId`；delete 计数用 `int` 承接 |
| `ChatMessageRepository` | `findByUsernameAndConversationIdOrderByMessageIndexAsc` / `deleteByX(long)` | Wrapper（按 messageIndex 升序）+ delete 计数 |
| `BatchFileResultRepository` | `findByTaskId` / `findByDocumentId` / `findByTaskIdAndDocumentId` | Wrapper |
| `ChunkUploadSessionRepository` | `@Query findByUploadSessionId` / `@Modifying @Query markFailed(int)` | Wrapper + `@Update` |
| `KnowledgeBaseMemberRepository` | `existsByKnowledgeBaseIdAndUserId` / `existsByKnowledgeBaseIdAndUserIdAndMemberRole` | Wrapper（`selectCount>0`）；`userId` String |
| `KnowledgeBaseRepository` | 派生查询（按 owner/name/可见性） | Wrapper |
| `DocumentVectorChunkRepository` | `deleteByDocumentId(void)` | `@Delete`/Wrapper |
| `UploadedFileRepository` | `findAllByDeletedFalseOrderByUploadTimeDesc`（**待优化**）/ 按 knowledgeBase、documentId 查询 | 改为**按需查询**（documentId 集合/知识库范围），消除全表扫描（对齐 knowledge-rag 规范） |

- [ ] 每个方法改写并保留原语义与返回基数
- [ ] `@Modifying` 的 `clearAutomatically/flushAutomatically` 语义确认（原生 SQL 更新无一级缓存问题）
- [ ] `deleteByX` 返回计数从 `long`→`int` 的调用方适配

## A3. 服务层调用适配（L）
- [ ] `save()` → 按 id 存在性分流 `insert`/`updateById`（重点：`BatchUpload*`、`ChatHistory*`、`UploadedFilePersistenceServiceImpl`）
- [ ] `findById/getAll/findAll` → `selectById/selectList`；`saveAll` → 批量 insert
- [ ] 移除对 JPA 脏检查的隐式依赖（修改后显式更新）
- [ ] `@Transactional` 保留；跨域（CRM↔知识库）通过服务编排，不用分布式事务

## A4. Flyway 基线：RAG 表 DDL 固化（M）
- [ ] 从 Hibernate 生成物导出 RAG 9 张表 DDL，并入 `V1__baseline.sql`
- [ ] `@Table` 的索引/唯一约束显式写入 DDL（如 knowledge_bases 名称唯一）
- [ ] 字符集/排序规则/时区与 CRM 表对齐（utf8mb4、Asia/Shanghai）

## A5. username → userId 协同（与 D3）（M）
- [ ] `chat_conversations.username` / `chat_messages.username` → `user_id`（`V3` 脚本含映射）
- [ ] 会话查询/删除全部按 CRM 稳定 userId
- [ ] `KnowledgeBaseMemberEntity.userId`（String）确认为 `user:<id>` 形态

## A6. 移除 JPA/Hibernate（S）
- [ ] pom 移除 `spring-boot-starter-data-jpa`、Hibernate
- [ ] 删除 9 个 `*Repository` 接口，替换为 `*Mapper`
- [ ] 关闭 `spring.jpa.hibernate.ddl-auto`（配置项整体移除）

## A7. 回归（M）
- [ ] 每仓储查询语义单测（含排序、计数、exists）
- [ ] 批量任务状态机：`updateStatusIfMatch`/`markFailed`/恢复扫描 `findAllByStatusInAndUpdatedTimeBefore`
- [ ] 会话历史读写/删除；知识库成员授权判定；上传文件归属与软删除查询

---

# Part B — D4：RAG LangChain4j → Spring AI 迁移

## B0. 前置技术验证 Spike（**最高风险，必须先做**）（M）
在投入大规模迁移前，用最小样例验证 Spring AI（spring-ai-alibaba）能否等价承接 LangChain4j 的关键能力：

- [ ] **思考块流式透传**：`ChatConfig` 现用 `.returnThinking(true)` + `PartialThinking/PartialThinkingContext`（`RagStreamSessionManager`）下发 `reasoning_content`。验证 Spring AI DashScope 流式是否支持思考增量；若不可等价 → 记录降级方案（仅快速模式，思考块剥离）。
- [ ] **自定义思考参数**：vLLM 走 `chat_template_kwargs.enable_thinking`、openai/百炼走顶层 `enable_thinking`（`ThinkingRequestParams`）。验证 Spring AI `ChatOptions`/DashScope options 能否按 provider 下发。
- [ ] **Qdrant 过滤语义**：Spring AI `QdrantVectorStore` 的 metadata filter 是否等价 LangChain4j `EmbeddingStore` filter（影响检索授权过滤一致性）。
- [ ] **向量维度默认值统一**：现状不一致——`ChatConfig` 默认 2056、`QdrantInitializer` 默认 2560、`application.yaml` 为 1024；迁移时统一为单一来源（按实际嵌入模型维度）。
- [ ] 产出：`B0-spike-结论.md`（可行性 + 风险 + 降级策略），作为 B1–B10 的前提。

## B1. ModelProvider 抽象 + ChatConfig 重写（L）
- [ ] 定义平台模型装配：`ChatModel`/`StreamingChatModel`/`EmbeddingModel`/`VisionModel`/`VectorStore`
- [ ] Provider：`dashscope`（默认，spring-ai-alibaba）| `openai` 兼容 | `vllm`（经 openai 兼容）
- [ ] 重写 `ChatConfig` 的 5 个 bean：`chatModel`/`embeddingModel`/`streamingChatModel`/`embeddingStore`/`memorySummaryModel`
- [ ] 统一 `llm.*` 配置命名空间；模型/温度/Provider 可被 `dynamic-config`（D10）运行期覆盖

## B2. 同步 Chat 迁移（M）
- [ ] `RagMemoryOrchestrator` 的摘要/意图 `chatModel.chat(prompt)` → Spring AI `ChatModel.call(Prompt)`
- [ ] 内部调用恒定禁思考（对齐原 Bean 级默认）

## B3. 流式 Chat 迁移（**大头**）（XL）
- [ ] `RagChatPipeline`：`StreamingChatModel` + `StreamingChatResponseHandler` + `ChatRequest` → Spring AI `StreamingChatModel.stream(Prompt)` 返回 `Flux<ChatResponse>`
- [ ] `RagStreamSessionManager`：`PartialResponse`/`StreamingHandle`/`ChatResponse` → 订阅 `Flux` + SSE 写出
- [ ] `wrapWithConnectionRetry`（连接重试）→ 在 `Flux` 上用 `retryWhen` 重写，保留原重试/退避语义
- [ ] 同会话取消/接管 → `Flux` 订阅取消，对齐 CRM `AiStreamRegistry` 接管锁与 `stopped` 事件
- [ ] 统一 SSE 事件（message/stopped/references/error）与超时/心跳口径

## B4. 思考模式透传（L，依赖 B0 结论）
- [ ] `returnThinking`/`PartialThinking(Context)` → Spring AI DashScope 思考增量（或 B0 降级方案）
- [ ] `ThinkTagStripper`（写记忆/历史前剥离思考块）保留
- [ ] `ThinkingRequestParams`（provider 形态）→ Spring AI `ChatOptions` 自定义参数
- [ ] `prompt-max-chars` 提示词预算闸门保留（防上游网关断连）

## B5. Embedding 迁移（M）
- [ ] `EmbeddingService`/`ImageEmbeddingServiceImpl`：`EmbeddingModel.embed(TextSegment)` → Spring AI `EmbeddingModel.embed(Document/String)`
- [ ] `TextSegment` → Spring AI `Document`；维度一致性校验（配合 B0）

## B6. VectorStore 迁移 + 抽象（与 D9）（L）
- [ ] `EmbeddingStore<TextSegment>`（`QdrantEmbeddingStore`）→ Spring AI `VectorStore`（`QdrantVectorStore`）
- [ ] `EmbeddingSearchRequest/Result`、`EmbeddingMatch` → Spring AI `SearchRequest`/`SearchResult<Document>` + 相似度
- [ ] `RagRetrievalService`/`RagRetrievalAccessFilter` 过滤语义迁移（元数据过滤 + 授权后置过滤，按需查询）
- [ ] `DocumentAdminService` 直接 Qdrant point 访问（`QdrantPoint`）→ 保留 REST 或改 VectorStore API
- [ ] 引入 `VectorStore` 抽象 + `InMemoryVectorStore` 回退（D9，dev/test）
- [ ] `Bm25Scorer` 混合检索保留（纯 Java，无 LangChain4j 依赖）

## B7. Vision/OCR 迁移（M）
- [ ] `ImageUnderstandingService`：多模态图片消息 → Spring AI DashScope 多模态（`Media`）
- [ ] OCR/vision prompt 与开关配置保留（`rag.pdf-ocr.*`/`llm.vision-*`）

## B8. 数据类型映射表（S，随 B2–B7 落地）
| LangChain4j | Spring AI |
|-------------|-----------|
| `ChatModel` / `StreamingChatModel` | `ChatModel` / `StreamingChatModel`（Flux） |
| `EmbeddingModel` | `EmbeddingModel` |
| `EmbeddingStore<TextSegment>` | `VectorStore` |
| `TextSegment` | `Document` |
| `EmbeddingMatch` / `EmbeddingSearchResult` | `SearchResult<Document>` / 相似度 |
| `AiMessage`/`SystemMessage`/`UserMessage` | `AssistantMessage`/`SystemMessage`/`UserMessage` |
| `TokenUsage` | `Usage`（`ChatResponse.getMetadata()`） |
| `StreamingChatResponseHandler`/`PartialThinking` | `Flux<ChatResponse>` + 思考增量（见 B0/B4） |

## B9. TokenUsage → Usage（对接统一计量）（S）
- [ ] `RagStreamSessionManager` 的 `TokenUsage` → Spring AI `Usage`
- [ ] 对接 `ai-assistant` 统一 Token 计量与 `platform-governance` 预算/告警

## B10. 移除 LangChain4j + 回归（L）
- [ ] pom 移除 `langchain4j`、`langchain4j-open-ai`、`langchain4j-qdrant`
- [ ] 全量编译；重建原 456 测试（`MockWebServer` 思考参数序列化实测改为 Spring AI 等价断言）
- [ ] 冒烟全链路：上传→解析→嵌入→检索→流式问答（快速/思考两模式）

## B 风险清单（按严重度）
1. **思考块流式透传**（B0/B4）：Spring AI 若无等价增量 → 降级为快速模式或自定义解析。
2. **provider 自定义参数**（B0/B4）：`chat_template_kwargs` 需经 Spring AI 自定义 options 下发。
3. **Flux 背压 vs 回调式 handler**（B3）：取消/接管/重试语义需在响应式流上重写。
4. **Qdrant 过滤语义差异**（B0/B6）：影响检索授权一致性。
5. **向量维度默认值不一致**（B0）：2056/2560/1024 必须统一。
6. **MockWebServer 测试重写**（B10）：思考参数序列化断言需改造。

---

## 建议执行顺序（A、B 均在阶段 3，可并行推进）
- **A 线**：A0 → A1 → A2 → A3 → A4 → A5 → A6 → A7
- **B 线**：**B0（spike 先行，出结论再决定 B4 深度）** → B1 → (B2 / B5 / B6 并行) → B3 → B4 → B7 → B8/B9 → B10
- **门禁**：A7 与 B10 各自回归通过后，再做 A+B 联合冒烟（登录态下上传→检索→带数据权限与知识库授权的流式问答）。
