# 关键取舍决策（评审已确认 2026-09-08）

> 本文件展开 `proposal.md` 中的 D1–D17 决策。D1–D7 初版对比，D8–D10 评审新增，D11–D17 为「架构重定位」评审新增（RAG 从对等系统收敛为助手能力）。
> 每项已标注✅确认结果；保留对比表以备后续回顾。
>
> **★架构重定位（贯穿全局，先读）**：本项目不是「融合两个对等系统」，而是**以 CRM 为基座构建一个 CRM 单体，其 AI 助手具备知识库(RAG)能力**。RAG 的**独立对话层**（`chat_conversation`/`chat_message`、`RagChatPipeline`/`RagStreamSessionManager` 独立入口、匿名问答、独立登录）**丢弃不迁**；其**对话能力**（思考/记忆/图文/流式接管）由 CRM 助手**吸收**；**知识库/文档/检索/嵌入/视觉**能力**移植**为 `com.slz.crm.knowledge`。整合工作量集中在 **AI/知识库模块 + 横切治理**，业务模块原样搬入。**D2/D4 的迁移范围据此收窄到知识库模块**（不再全量迁移一个在跑的 RAG 后端）。行为细节见 `assistant-decision-tree.md`。

---

## D1. Spring Boot 基线：统一 3.5.x（回填 RAG） vs 升级 4.0.2（改造 CRM）

**背景**：CRM 基座为 Spring Boot **3.5.5**；RAG 为 Spring Boot **4.0.2**。二者不可直接共存于同一构建。

| 维度 | A. 统一 3.5.x（推荐） | B. 升级 4.0.2 |
|------|----------------------|---------------|
| 改造对象 | RAG（较小、较新，回填成本可控） | CRM（业务域庞大、依赖多） |
| Jackson | RAG 从 Jackson 3（`tools.jackson`）回到 Jackson 2 | CRM 需迁移到 Jackson 3 |
| Starter 命名 | RAG `spring-boot-starter-webmvc` → `spring-boot-starter-web` | CRM 需适配 Boot 4 命名/自动配置变化 |
| Security | RAG Security 7 → 6（配合 D3 认证统一，实际会弱化 RAG Security） | CRM 需引入并适配 Security 7 |
| MyBatis-Plus | 3.5.6 在 Boot 3.5.x 稳定 | 需验证 MyBatis-Plus 3.5.6 与 Boot 4 兼容性（风险高） |
| spring-ai-alibaba | 1.0.0.4 基于 Boot 3 | 需验证 Boot 4 兼容性（风险高） |
| LangChain4j | 1.12.2 支持 Boot 3 | 原生支持 Boot 4 |

**推荐 A**：以 CRM 基座为准，RAG 回填到 3.5.x。理由：CRM 是基座且业务/依赖体量最大，动 CRM 风险最高；RAG 回填主要是 Jackson/starter/Security 的机械适配，且 D3 会统一认证，RAG 对 Security 7 的依赖被削弱。
**影响**：需要一次“最小可编译骨架”验证 LangChain4j + Qdrant + MinIO + JPA 在 Boot 3.5.x 下全部可用。
**回退**：若关键依赖无法在 3.5.x 运行，再评估 B（但需重估 CRM 全量回归成本）。

---

## D2. 持久化：MyBatis-Plus 与 JPA 共存 vs 全量归一

**背景**：CRM 用 MyBatis-Plus（+ `com.tangzc` auto-table），RAG 用 Spring Data JPA（Hibernate，`ddl-auto: update`）。

| 维度 | A. 共存（推荐） | B. 归一到 MyBatis-Plus | C. 归一到 JPA |
|------|----------------|------------------------|---------------|
| 迁移量 | 最小（各域沿用） | 大（RAG 全部仓储重写为 Mapper） | 极大（CRM 全部 Mapper 重写为 JPA） |
| 风险 | 中（事务/建表边界需明确） | 高 | 极高 |
| 一致性 | 需统一迁移工具（Flyway）与命名规范 | 单一心智模型 | 单一心智模型 |

**✅ 已确认 B（归一到 MyBatis-Plus）**：CRM 域沿用 MyBatis-Plus；RAG 域由 Spring Data JPA 迁移为 MyBatis-Plus——`repository`（JPA Repository）改写为 MyBatis-Plus Mapper，JPA 实体注解（`@Entity`/`@Table`/`@Column`）改为 MyBatis-Plus（`@TableName`/`@TableId`/`@TableField`），移除 Hibernate/`ddl-auto`。表结构统一由 Flyway 管理，生产禁用 `auto-table` 改表。
**代价与约束**：
- RAG 仓储层需全量改写（约 9 个 Repository + 派生查询方法），工作量显著大于共存方案，但换取单一持久化心智模型与统一迁移。
- 复杂派生查询（如 `findAllByDeletedFalseOrderByUploadTimeDesc`）改为 Wrapper/自定义 SQL，正好配合“检索授权过滤按需查询”优化。
- 迁移期以域为单位灰度，保证每步可回归；表归属边界见 `migration-inventory.md`。

---

## D3. 认证统一：以 CRM JWT+拦截器为唯一源 vs 以 Spring Security 为唯一源

**背景**：CRM 用 `JWTInterceptor` + `PermissionsInterceptor` + `@RequirePermission` + `PermissionOperates`；RAG 用 Spring Security 过滤链 + `JwtAuthFilter` + 静态账号（`app.users`）/`teacher:<id>` 账号 + `UserIdentityVerifier`（稳定 userId）。

| 维度 | A. 以 CRM 为唯一源（推荐） | B. 以 Spring Security 为唯一源 |
|------|---------------------------|-------------------------------|
| 与“CRM 为基座”一致性 | 高 | 低（需改造 CRM 全量鉴权） |
| RAG 改造点 | 授权判定保留，身份来源改为 CRM `UserContext`/稳定 userId | CRM 需接入 Security 过滤链与方法级鉴权 |
| 数据权限协同 | 天然复用 CRM `DataScopeServiceImpl`（含 D6 部门维度） | 需把数据权限桥接进 Security |
| 风险 | 中（RAG 匿名/登录分流需重接） | 高 |

**推荐 A**：
- 单一 JWT 签发方（CRM `slz.jwt`），单一 `UserContext`。
- RAG 的 `UserIdentityVerifier`/`KnowledgeBaseAuthorizationService`/`RagRetrievalAccessFilter` **保留授权判定逻辑**，但身份主体改为 CRM 稳定 userId（`user:<id>`），停用 RAG 独立登录与静态账号。
- RAG 的 `SecurityConfig` 精简为“放行 + 由 CRM 拦截器统一鉴权”，或彻底移除，改由 CRM 拦截器覆盖知识库路由。
**影响**：知识库成员标识从 `teacher:<id>`/`config:<username>` 迁移到 CRM `user:<id>`；需要存量映射（见治理阶段的数据迁移）。

---

## D4. AI 栈：dashscope 与 LangChain4j 并存 vs 归一

**背景**：CRM AI 助手用 spring-ai-alibaba（dashscope，`qwen-plus`，工具调用）；RAG 用 LangChain4j（vllm/openai 兼容，chat/embedding/vision/ocr，向量检索）。

| 维度 | A. 并存，各司其职（推荐） | B. 归一到 LangChain4j | C. 归一到 spring-ai |
|------|--------------------------|----------------------|--------------------|
| CRM 工具调用助手 | 沿用 spring-ai（成熟） | 需重写工具注册/调用 | 沿用 |
| RAG 向量/embedding/检索 | 沿用 LangChain4j（成熟） | 沿用 | 需重写检索/嵌入栈 |
| 统一成本口径 | 通过治理层统一 Token 计量 | 单一栈 | 单一栈 |
| 迁移量/风险 | 最小 | 大 | 大 |

**✅ 已确认 A（dashscope 为默认 + 可适配不同模型）**：AI 模型层统一到 **Spring AI（spring-ai-alibaba）**，默认 Provider 为 dashscope（qwen 系列）。引入平台级 `ModelProvider` 抽象，支持按配置切换 dashscope / openai 兼容 / vllm 等，满足“适配不同模型”。
**协同点**：把 RAG 检索封装为 CRM 助手只读工具 `queryKnowledgeBase`；Token 计量/预算/超时/降级/日志脱敏统一走 `platform-governance`。
**代价与迁移**：RAG 原基于 LangChain4j 的 chat/embedding/vision 调用需迁移到 Spring AI 的 `ChatModel`/`EmbeddingModel`/`VectorStore` 抽象，LangChain4j 依赖逐步移除。为控制风险采用分阶段：先统一配置 + Provider 抽象（dashscope 默认），再逐服务把 LangChain4j 调用替换为 Spring AI；向量库经 `VectorStore` 抽象（见 D9）。

---

## D5. 模块结构：单模块+包边界 vs Maven 多模块

**背景**：目标仓为空，可自由决定结构。

| 维度 | A. 单模块 + 严格包边界（推荐起步） | B. Maven 多模块 |
|------|-----------------------------------|-----------------|
| 起步速度 | 快 | 慢（需搭父 POM、模块依赖） |
| 边界约束 | 靠包与评审（`com.slz.crm` / `.knowledge` / `.platform`） | 靠编译期模块依赖（更强） |
| 演进 | 可后续拆分为多模块 | 一步到位 |

**推荐 A→B 演进**：起步用单模块 + 三包边界（`com.slz.crm.*` 业务与助手基座、`com.slz.crm.knowledge.*` 知识库、`com.slz.crm.platform.*` 平台治理）；当边界稳定、团队规模扩大后，再拆为 `crm-common / crm-core / crm-knowledge / crm-platform / crm-app` 多模块。
**影响**：包重命名（`com.mark.knowledge.*` → `com.slz.crm.knowledge.*`）需在基座阶段一次完成，避免后续反复。

---

## D6. 上司查看下属建模：部门树推导 vs 负责人字段 vs 直属上级字段

**背景**：`SysDeptEntity`（`sys_dept`）已有 `id` + `parentId`（部门树）；`UserEntity` 已有 `deptId` + `roleId`；**无**部门负责人字段、**无**用户直属上级字段。`DataScopeLevel` 现为 `NONE/SELF/TAGE/ALL`。

| 维度 | A. 部门树 + deptId 推导 | B. 新增部门负责人字段（`sys_dept.leaderId`） | C. 新增用户直属上级（`user.managerId`） |
|------|------------------------|---------------------------------------------|----------------------------------------|
| “上司查看下属”语义 | 上级部门用户可见下级部门全部数据 | 部门负责人可见本部门（及子部门）数据 | 经理可见其直接/间接下属数据 |
| 复用现有结构 | 高（已有 parentId + deptId） | 中（加一列） | 中（加一列 + 递归） |
| 灵活性 | 部门维度 | 部门 + 明确负责人 | 人的汇报线（可跨部门） |
| 复杂度 | 低 | 低 | 中（递归下属解析） |

**推荐 A+B**：
- 以**部门树**为基础：新增 `DataScopeLevel.DEPT`（本部门）与 `DEPT_AND_CHILD`（本部门及所有子部门）。
- 新增 `sys_dept.leaderId`（部门负责人），把“上司”显式化为部门负责人；负责人默认获得 `DEPT_AND_CHILD` 范围。
- 下属集合 = `deptId ∈ {负责人所在部门 ∪ 其所有子部门}` 的用户；数据过滤按记录归属人（如 `owner_id`/`creator_id`）∈ 下属集合。
- C（`user.managerId`）作为**可选增强**留待后续（当出现跨部门汇报线需求时）。
**影响**：需为受控表补 `_DEPT`/`_DEPT_AND_SUB` 权限项；默认不授予，按角色灰度；补数据范围测试（本人/本部门/本部门及以下/全部）。

---

## D7. 向量库与对象存储：引入 Qdrant + MinIO vs 暂缓

**背景**：RAG 依赖 Qdrant（向量检索）与 MinIO（对象存储，S3 兼容，含内存回退）。CRM 无此类依赖。

| 维度 | A. 引入 Qdrant + MinIO（推荐） | B. 暂缓 RAG 存储/向量能力 |
|------|-------------------------------|---------------------------|
| 知识库能力完整性 | 完整（向量检索 + 文档存储） | 残缺（无法做真正的 RAG） |
| 部署/运维成本 | 增加两个中间件 | 无 |
| 本地开发 | 提供 docker-compose + 健康检查 + 内存/文件回退 | — |

**推荐 A**：引入 Qdrant + MinIO 作为平台基础设施，提供 `docker-compose`、健康检查（复用 RAG `QdrantHealthIndicator`/`MinioHealthIndicator`）、本地回退（MinIO 内存实现）。
**影响**：部署文档、生产配置保护（endpoint/密钥外置）、可观测性（依赖健康与指标）需纳入 `platform-governance`。

---

## D8. RAG 未登录态：移除匿名 vs 保留匿名

**背景**：RAG 原有 `AnonymousRagChatService`（匿名问答）与 `AuthenticatedRagChatService`（登录问答）双链路，公开知识库可匿名访问。

**✅ 已确认 B（移除匿名态）**：融合后 RAG 不再保留未登录态，所有知识库/文档/问答接口均需 CRM 登录态。
- 删除 `AnonymousRagChatService` 匿名链路与相关分流；`UnifiedRagService` 只走已认证路径。
- “公开”知识库（`KnowledgeBaseVisibility.PUBLIC`）语义收敛为“所有**已登录**用户可见”，而非匿名可见。
- 简化认证：无需为匿名场景单独设计限流/配额/授权分支，全部纳入统一身份与配额体系。
**影响**：若未来需要对外匿名问答，须重新评估（不在本期范围）。

---

## D9. 向量库本地回退：Qdrant 无内存回退的补齐方案

**背景（回应 #9）**：RAG 的 `QdrantInitializer` 仅通过 REST 检查/创建集合，失败时“应用继续启动”，但**运行期检索仍需真实 Qdrant**——不同于 MinIO 有 `InMemoryFileStorageServiceImpl` 回退，Qdrant **没有本地回退**。

**✅ 已确认 A+B（组合方案）**：
- **A 本地真 Qdrant**：提供 `docker-compose`（qdrant + minio + mysql），开发/测试一键起真实依赖；配合 `QdrantInitializer` 自动建集合。（净新增交付：RAG 源仓无 docker-compose，不是迁移项）
- **B VectorStore 抽象 + 内存回退**：引入平台级 `VectorStore` 抽象（对齐 Spring AI `VectorStore`），两种实现：
  - `QdrantVectorStore`（默认/生产）
  - `InMemoryVectorStore`（dev/test 回退，基于内存向量 + 余弦相似度，可选文件持久化到本地，重启可加载）
  - 通过配置 `rag.vector-store.provider=qdrant|in-memory` 切换；内存实现仅用于本地/测试，生产 MUST 用 Qdrant。
**取舍**：内存回退便于无 Qdrant 环境下跑通链路与单测，但不具备生产级 ANN 性能与持久化语义，严禁用于生产。

---

## D10. 动态配置：超管运行期可调 vs 仅静态配置

**背景（回应 #11）**：CRM/RAG 的 AI 与业务参数（提示词、模型、温度、限流、检索/分块参数等）目前都写死在 `application.yml`，调整需改配置并重启。

**✅ 已确认 B（超管动态配置）**：新增 `dynamic-config` 能力域——
- DB 存储 + 命名空间（`ai.prompt.*`/`ai.model.*`/`rag.retrieval.*`/`business.*`），动态值覆盖静态默认。
- 仅超级管理员（roleId=1）可写；热生效（缓存 + 有界刷新）；类型/范围/枚举校验护栏；版本历史与回滚；全程审计。
- 优先纳入：系统提示词、模型 Provider/名称、温度/最大 token、限流与配额阈值、检索 topK/阈值/分块参数、功能开关。
**边界**：动态配置不替代启动期生产配置保护——密钥/凭据等敏感项仍走环境变量注入（静态），动态配置聚焦“运行期可调的策略参数”。

---

## D11. 架构重定位：RAG 作为助手能力（丢弃独立对话层）

**背景**：初版把 CRM 与 RAG 当对等系统「融合」，导致要塞两套聊天子系统、全量重写能跑的 RAG。评审重定位：这是**一个 CRM 产品、一个助手**，RAG 是助手的知识库能力。

**✅ 已确认**：
- **丢弃**：RAG 独立对话层（`chat_conversation`/`chat_message` 表、`RagChatPipeline`/`RagStreamSessionManager` 独立入口与 SSE、独立会话控制器、`AnonymousRagChatService`、独立登录/`teacher_account`）。
- **吸收进助手**：思考模式、会话记忆(摘要/事实/意图)、图文混合、流式接管(shouldAbort)、图片缓存、连接重试/降级 → 并入 CRM 助手管线（`server.ai.*` + `AiChatServiceImpl`）。
- **移植为知识库模块**：文档解析/OCR/视觉/分块/嵌入/混合检索/图文双路/MinIO/批量上传 + 7 张知识库表 → `com.slz.crm.knowledge`。

**影响**：D2(JPA→MyBatis)、D4(LangChain4j→Spring AI) 范围收窄到知识库模块；助手与知识库共用一套 SSE/记忆/Token 口径，不再有「两套聊天」。

---

## D12. 知识库触发方式：前端手动开关 vs LLM 自主工具

**背景**：RAG 现状是前端 `useKnowledgeBase` 开关 + 每轮强制检索，模型不参与「要不要查」。

**✅ 已确认（手动开关）**：助手请求契约带 `useKnowledgeBase`——ON=每轮强制检索并注入上下文；OFF=纯助手（闲聊+业务工具+图片理解）。**业务工具仍由 LLM 自动调用**（两套触发并存、互不干扰）。

**影响**：确定性/可控性强；空匹配兜底须按 D16 修复，避免 KB OFF 误吐「未检索到」。

---

## D13. 图片与知识库解耦

**背景**：RAG 里图片必然走检索；「无图追问自动回退最近图」会把旧图塞进无关闲聊；「同图换问题」复用旧问题的聚焦/向量导致焦点错位。

**✅ 已确认**：
- **理解文本恒注入**：有图/`imageRef` → OCR+摘要+实体+问题聚焦注入 prompt，**与 KB 开关无关**。
- **图片向量仅 KB ON 懒生成+缓存**：KB OFF 不 embed、不缓存向量。
- **取消无差别最近图回退**，改 `imageRef` 显式引用；**缓存分层** L1(OCR/摘要/实体，按 hash) / L2(问题聚焦+向量，按 hash+问题)；每会话图片缓存加**上限+LRU** 叠加 TTL。

**影响**：`imageRef` 指向的图/理解需可恢复（推荐把 L1 理解按 hash 持久化，免重跑 vision）。

---

## D14. 会话记忆持久化

**背景**：RAG 记忆纯内存（`ConcurrentHashMap`，TTL 1800s）——重启丢、多实例不共享；`recentMessages` 与 CRM 持久 `ai_message` 是同批对话两份副本；意图/摘要用 `chat(String)` 重载，token 未计量。

**✅ 已确认**：
- `ai_message` 为**唯一真相源**，`recentMessages` 改其**内存投影**（`restoreMemoryIfAbsent` 回灌，不双写）。
- `summary/facts/intent` **持久化到新表 `ai_conversation_memory`**（1:1 ai_session，乐观锁 version），归属改 `userId`；内存 map 退化为缓存。
- 意图/摘要仍走**异步旁路**（专用执行器 pool=2/队列=64/AbortPolicy + CAS 单飞 + 拒绝降级）→ 归 platform-governance。
- **补 Token 计量盲点**：意图/摘要改用能返回 usage 的重载，纳入统一计量。

**影响**：新增 `ai_conversation_memory`（Flyway V4x）；content 存剥离 think 正文（避免回灌膨胀）。

---

## D15. 来源引用与高亮（档 B 页级）

**背景**：RAG `SourceReference` 只有 filename/documentId/excerpt/score，无定位锚点；`pageNo` 只在日志、未进片段元数据；PDF 把全页 merge 成一个 `mergedText` 再整体分块，页边界丢失。

**✅ 已确认（档 B 页级）**：
- 入库**按页分块**并打 `pageNo`（PDF text/OCR 都改；`extraMetadata`→`document_vector_chunk.extra_metadata_json`）。
- `SourceReference` 补 `chunkIndex/pageNo/chunkId`（+ Excel `rowIndex`）。
- 流式 `sources` 事件（检索后、答案前）+ 答案内联 `[n]` + `payload.citations`（实际引用编号集，供只高亮承重来源 + 引用精度评测）。
- 前端点 `[n]` → 跳 `pageNo` 页 + `chunk_text` 段内匹配高亮；Excel 用 `rowIndex`。

**边界**：bbox 像素级高亮不在本期（OCR/vision 只返回纯文本、无坐标）。

---

## D16. 空匹配兜底修复

**背景（★坑）**：RAG `if (matches.isEmpty() && !hasUsableImageContext)` 未判 `useKnowledgeBase`，KB OFF 时 `retrievalResult` 恒 empty → 无图恒吐「未检索到」、不进正常生成。

**✅ 已确认**：
- **必修**：兜底 guard 加 `useKnowledgeBase &&` → **KB OFF 绝不触发**，直接正常生成（闲聊+工具+图片）。
- **建议**：KB ON 零命中也不硬 return canned，改「注入未命中标记 + 诚实约束、仍进正常生成」；`strict-KB` 硬兜底由 DynamicConfig 可选保留。

**影响**：ai-assistant 增 MODIFIED 需求 + 负向场景（KB OFF 不得返回「未检索到」）。

---

## D17. 意图/类目 CRM 化

**背景**：RAG `QueryIntentClassifier`（技术栈类目 Java/Python/Vue…）**是死代码**（无调用点，仅单测引用），与「财务处知识库助手」系统提示词类目对不上；文档侧 `category/direction/techStack` 元数据存了但检索未用。

**✅ 已确认**：丢弃死代码 `QueryIntentClassifier`；**新建** CRM 域意图/类目机制，接进检索 metadata 过滤（复用已存的 `category/direction` 元数据位，换成 CRM 类目）；类目+关键词由 `DynamicConfig` 可配；无配置则跳过过滤（保留 `intent-filter-enabled` 开关）。

**影响**：文档入库按 CRM 类目打 metadata 标签，过滤才生效。

---

## 决策汇总表（已确认）

| # | 决策 | 确认结果 |
|---|------|----------|
| D1 | Boot 基线 | ✅ A 统一 3.5.x，回填 RAG |
| D2 | 持久化 | ✅ B 归一 MyBatis-Plus + Flyway（RAG JPA 迁移） |
| D3 | 认证 | ✅ A CRM JWT 唯一源，RAG 授权绑 userId |
| D4 | AI 栈 | ✅ A dashscope 默认 + Spring AI 统一 + 可插拔 Provider |
| D5 | 模块 | ✅ A 单模块 + 包边界 |
| D6 | 上下级 | ✅ A+B 部门树 + `sys_dept.leaderId` |
| D7 | 向量/存储 | ✅ A 引入 Qdrant + MinIO |
| D8 | RAG 匿名态 | ✅ B 移除匿名，全部需登录 |
| D9 | 向量库回退 | ✅ A+B compose 真 Qdrant + VectorStore 内存回退 |
| D10 | 动态配置 | ✅ B 超管运行期动态配置（含提示词） |
| D11 | 架构重定位 | ✅ RAG=助手能力；丢弃独立对话层，助手吸收对话能力，知识库能力移植 |
| D12 | KB 触发 | ✅ 前端手动 `useKnowledgeBase`；业务工具 LLM 自动 |
| D13 | 图片解耦 | ✅ 理解恒注入、向量仅 KB ON 懒生成；取消最近图回退改 imageRef；缓存分层+上限 |
| D14 | 记忆持久化 | ✅ ai_message 真相源+投影；summary/facts/intent 落 ai_conversation_memory；userId；补 Token 盲点 |
| D15 | 来源高亮 | ✅ 档 B 页级：按页分块打 pageNo + SourceReference 补锚点 + 内联 [n]/citations |
| D16 | 空匹配兜底 | ✅ KB OFF 绝不触发；KB ON 零命中软标记+诚实生成；strict-KB 可配 |
| D17 | 意图/类目 | ✅ 丢死代码 QueryIntentClassifier；新建 CRM 域类目接检索过滤，DynamicConfig 可配 |
