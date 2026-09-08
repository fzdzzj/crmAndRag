# Agent-B-persist 提示词 · 知识库建表 + 移植 + 授权（Wave 1，B 线，先于 B-ai）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`（注释规范+纪律+冻结契约+Flyway 号段），再读 `specs/knowledge-rag/spec-delta.md`、`specs/platform-fusion/spec-delta.md`、`tasks.json`（任务 7、8）、`design-decisions.md`（D2/D3/D8/D11）、`db-table-coordination.md`、`agent-execution-plan.md`（§2 契约、§3 归属、§6 B 拆分）、`migration-subtasks.md`（A0–A7，按"新建+移植"理解）。
> 源项目只读参考：RAG `D:\code\rag\back\RAG`（`rag/entity/*`、`rag/repository/*`、`rag/service/DocumentService`、`auth/**`、`KnowledgeBaseAuthorizationService`、`RagRetrievalAccessFilter`）。

## 角色与目标
你是 **知识库能力 agent（建表+移植持久化+授权）**。目标：把 RAG 的**知识库/文档/检索/存储能力**移植为 `com.slz.crm.knowledge` 模块（7 表新建 + LangChain4j→Spring AI），授权缝合到 CRM userId，**移除匿名态与 RAG 独立对话层**。★这不是"全量迁移一个在跑的 RAG"，对话层丢弃、对话能力归 C 助手。

## 负责范围
tasks.json 任务 7、8；migration-subtasks.md A0–A7（按"新建+移植"而非"机械迁 JPA"理解）。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-b-knowledge`（与 B-spike/B-ai 共用，B-spike 先进）
- 分支：`feature/lane-b-knowledge`；禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成、契约冻结；**B-spike 出 Spring AI go/no-go 结论**（决定模型层深度/降级）。

## 独占可改
`com.slz.crm.knowledge.**`（entity/mapper/document/embedding/storage/auth）、Flyway `V3x`、`docker-compose`。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约、`server.ai.**`（C 领地）、`com.slz.crm.platform.**`（D 领地）。

## 要做（任务 7：建表 + 移植）
1. `com.mark.knowledge.*` **知识库能力**重打包为 `com.slz.crm.knowledge.*`；Jackson 3(`tools.jackson`)→2、`spring-boot-starter-webmvc`→`web`；控制器改返回 CRM `Result`。**不移植**独立对话层/匿名/Security。
2. **7 表用 MyBatis-Plus 新建**（`@TableName` 单数 / `@TableId(AUTO)` / `@TableField` / `@EnumValue` / `@TableLogic`），借鉴 RAG schema（实体全扁平无关联）；表名单数、审计列 `create_time/update_time`、软删除 `is_deleted`(Boolean)、`deleted_by` username→userId、`user_id` String(100) `user:<id>`；编写 `V3__knowledge.sql`。
3. 移植文档处理链（多格式解析/PDF/Excel/OCR/视觉抽取/分块/批量上传状态机/分片上传/向量快照）：LangChain4j→Spring AI `ChatModel/EmbeddingModel`，统一 `ModelProvider`（dashscope 默认）。
4. 引入平台 `VectorStore` 抽象：`QdrantVectorStore`(默认)+`InMemoryVectorStore`(dev/test 回退)；接入 Qdrant(QdrantInitializer+健康+集合/维度)、MinIO(健康+内存/文件回退)；提供 `docker-compose`(qdrant+minio+mysql)。

## 要做（任务 8：授权 + 移除对话层）
5. 知识库授权入参改消费 CRM `UserContext` 稳定 userId(`user:<id>`)；停用 RAG 独立登录/`SecurityConfig` 过滤链/静态账号(`app.users`)。
6. **移除匿名态 + 独立对话层**：删 `AnonymousRagChatService`、`chat_conversation`/`chat_message`、`RagChatPipeline`/`RagStreamSessionManager` 独立入口与独立会话控制器；公开知识库=所有已登录用户可见。
7. `KnowledgeBaseAuthorizationService`/`RagRetrievalAccessFilter`/`ResourceAccessPolicy` 保留判定逻辑、替换身份来源；检索授权过滤改按需查询（消除 `UploadedFileRepository` 6 个 `findAllBy*` 全表扫描）。
8. 第 10 实体 `TeacherAccountEntity` 按 D3/D11 **丢弃**（→ `sys_user`）。
9. 补测试：成员/负责人/管理员/越权访问矩阵 + userId 映射 + 匿名链路已移除。

## 关键坑
- **对话层不归你**：`chat_*`/`RagChatPipeline`/流式会话/记忆/思考 是 C 的领地，你只丢不移植。
- **B-spike 结论先行**：Spring AI 若不能等价思考流式/视觉，模型层按降级策略做，别硬写。
- 表约定（C1–C4）务必落实；`deleted_by` 存 username 与 D3/D8 冲突，必须改 userId。
- 全表扫描消除是授权过滤的硬要求（按需 documentId 集合/知识库范围查询）。

## 注释重点（本 lane）
- **移植既有代码保留原注释语义**（RAG 文档/检索注释密集处不得丢信息）。
- 授权判定、userId 映射、全表扫描消除处行内注释说明原因。
- Flyway `V3x` 头部注释写明每张表用途、字段含义、归属域、约定（单数名/审计列/软删除/userId）。
- VectorStore 抽象与内存回退注释说明"生产 MUST Qdrant、内存仅 dev/test"。

## 出口条件
7 表建好 + 文档移植可解析入库 + VectorStore/Qdrant/MinIO 接通 + 授权 userId 化 + 匿名/对话层移除 + 越权测试绿。

## 产出
变更摘要 + 测试结果 + 契约/依赖变更申请（如有）。
