# Agent-B-spike 提示词 · Spring AI 可行性验证（Wave 1，B 线前置闸）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `migration-subtasks.md`（B0 与 Part B 风险清单）、`design-decisions.md`（D4/D9）。
> 源项目只读参考：RAG `D:\code\rag\back\RAG`（重点 `chat/config/ChatConfig.java`、`rag/service/rag/RagStreamSessionManager.java`、`rag/common/ThinkingRequestParams.java`、`config/QdrantInitializer.java`）。

## 角色与目标
你是**技术验证 agent**。目标：判定 Spring AI 能否等价承接 RAG 现有 LangChain4j 的关键能力，产出 go/no-go 结论与降级策略。**只验证，不做业务迁移。** 你的结论决定 B-ai 的深度，并影响 C 的 SSE `thinking` 契约。

## 负责范围
migration-subtasks.md 的 **B0**（仅此）。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-b-rag`（与 B-persist/B-ai 共用，你是第一个进的）
- 分支：`feature/lane-b-rag`
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成；`ModelProvider`/`VectorStore` 契约已冻结。

## 独占可改
新建验证样例目录（如 `spike/`）+ 输出文档 `B0-spike-结论.md`。**不改** `knowledge` 业务代码、**不改** `pom.xml`（如需临时验证依赖，走"变更申请"）。

## 要验证（用最小样例）
1. **思考块流式透传**：LangChain4j 现用 `.returnThinking(true)` + `PartialThinking/PartialThinkingContext` 下发 `reasoning_content`；Spring AI DashScope 流式是否有等价思考增量？
2. **自定义思考参数**：vLLM 的 `chat_template_kwargs.enable_thinking`、openai/百炼顶层 `enable_thinking`，能否经 Spring AI `ChatOptions` 按 provider 下发？
3. **Qdrant 过滤语义**：Spring AI `QdrantVectorStore` 的 metadata filter 是否等价 LangChain4j `EmbeddingStore` filter（影响检索授权一致性）？
4. **向量维度**：现状默认值三处不一致（`ChatConfig` 2056 / `QdrantInitializer` 2560 / `application.yaml` 1024），确认实际嵌入模型维度并给出统一值。

## 注释重点（本 lane）
- 验证样例代码同样遵循中文注释规范；每个样例类/方法注释写明"验证哪个能力、判定标准、结论"。
- `B0-spike-结论.md` 每项写：结论（可行/不可行）+ 证据（代码/文档/实测）+ 若不可行的**降级方案**（如思考流式不可等价 → 降级为仅快速模式或自定义解析）。

## 出口条件
产出 `B0-spike-结论.md`（4 项各有明确结论 + 降级策略 + 统一向量维度值）。

## 产出
`B0-spike-结论.md`，并**广播给 Agent-B-ai 与 Agent-C**（思考流式结论会影响 SSE 事件契约的 `thinking` 字段）。
