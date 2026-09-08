# Agent-B-ai 提示词 · RAG 模型/检索/流式迁移（Wave 1，B 线，后于 B-persist）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `migration-subtasks.md`（B1–B10 + 风险清单）、`B0-spike-结论.md`（B-spike 产出）、`specs/knowledge-rag/spec-delta.md`、`tasks.json`（任务7 AI部分、任务9）、`design-decisions.md`（D4/D7/D9）。
> 源项目只读参考：RAG `D:\code\rag\back\RAG`（`chat/config/ChatConfig.java`、`rag/service/rag/RagChatPipeline.java`、`RagStreamSessionManager.java`、`RagRetrievalService.java`、`EmbeddingService.java`、`ImageUnderstandingService.java`）。

## 角色与目标
你是 **RAG AI 栈 agent**。目标：把 RAG 从 LangChain4j 迁到 Spring AI（dashscope 默认），接通检索、图文双路召回与流式问答。这是**关键路径**上最重的一环。

## 负责范围
tasks.json 任务 7（AI 栈+VectorStore 部分）、任务 9（文档/检索/流式冒烟）；migration-subtasks.md B1–B10。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-b-rag`
- 分支：`feature/lane-b-rag`（在 B-persist 合入后接续）
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成；**B-spike 结论已出**；**B-persist 已合入**（持久化先、AI 后，避免同文件并发改）。

## 独占可改
`com.slz.crm.knowledge.**` 的 ai/chat/embedding/vector/retrieval/stream 服务、`ChatConfig` 重写。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约（若 spike 要求改 SSE `thinking` 字段，走契约变更申请）、B-persist 已定的 entity/mapper。

## 要做
1. 按 B0 结论重写 `ChatConfig` 5 个 bean 为 Spring AI（chatModel/embeddingModel/streamingChatModel/vectorStore/memorySummaryModel），接入 `ModelProvider` 契约（dashscope 默认/openai/vllm）。
2. 同步 chat：`RagMemoryOrchestrator` 摘要/意图 → `ChatModel.call(Prompt)`，内部恒禁思考。
3. **流式 chat（最重）**：`RagChatPipeline`/`RagStreamSessionManager` 从 `StreamingChatResponseHandler`/`PartialResponse` 改为 `Flux<ChatResponse>`；`wrapWithConnectionRetry`→`retryWhen`；同会话取消/接管对齐 CRM `AiStreamRegistry` 接管锁与 `stopped` 事件；统一 SSE 事件契约。
4. 思考模式：按 B0 结论透传或降级；`ThinkTagStripper`（写记忆/历史前剥离）保留；`prompt-max-chars` 预算闸门保留。
5. Embedding：`EmbeddingService`/`ImageEmbeddingService`→Spring AI `EmbeddingModel`；`TextSegment`→`Document`；维度按 spike 统一值。
6. VectorStore：`EmbeddingStore`/`QdrantEmbeddingStore`→`VectorStore`/`QdrantVectorStore`；实现 `InMemoryVectorStore` 回退（dev/test）；`RagRetrievalService`/`RagRetrievalAccessFilter` 过滤语义迁移；`Bm25Scorer` 保留。
7. Vision/OCR：`ImageUnderstandingService` 多模态→Spring AI DashScope `Media`；保留图文双路召回加权融合（文本 0.7/图片 0.3）。
8. `TokenUsage`→`Usage`，对接 Token 计量契约；移除 LangChain4j 依赖（走 pom 申请）。

## 关键坑
- **Flux 背压 vs 回调 handler** 的取消/接管/重试语义差异最大，是最高风险点。
- `MockWebServer` 思考参数序列化测试需改为 Spring AI 等价断言。
- 向量维度用 spike 统一值，勿沿用不一致默认。

## 注释重点（本 lane）
- **流式/并发**（Flux 订阅取消、接管锁、retryWhen 重试退避、SSE 事件下发、思考块剥离）**必须逐处行内注释解释原因与边界**——这是最难懂、最易回归的地方。
- `ChatConfig` 重写保留原注释语义（provider 差异、思考参数形态、bean 级禁思考动机），并补 Spring AI 对应说明。
- 图文加权融合、BM25、维度统一等非直觉逻辑注释清楚。
- 每处 LangChain4j→Spring AI 的类型替换（见 migration-subtasks B8 映射表）注释对应关系，便于回溯。

## 出口条件
登录态下 上传→解析→嵌入→检索→流式问答（快速/思考两模式）冒烟通过；RAG 回归基线（原 491 测试：485 通过 + 6 Docker 集成跳过）重建通过。

## 产出
变更摘要 + 测试结果 + 依赖/契约变更申请。
