# Agent-D 提示词 · 平台治理与硬化（Wave 1 任务13骨架先行 / Wave 2 任务14,15）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `specs/platform-governance/spec-delta.md`、`tasks.json`（任务13、14、15）、`agent-execution-plan.md`（§2 契约、§3 归属、§5 Flyway）。
> 源项目只读参考：RAG `D:\code\rag\back\RAG`（`config/RequestTraceFilter.java`、`MdcTaskDecorator.java`、`AsyncConfig.java`、`DependencyResilienceExecutor.java`、`ProductionConfigurationGuard.java`、`TaskExecutorMetricsBinder.java`、`docs/HANDOFF.md` 未完成项）。

## 角色与目标
你是**平台治理 agent**。目标：沉淀跨 CRM/AI/知识库共享的治理基础设施。任务 13（traceId/线程池/韧性/**记忆旁路执行器**骨架）**先行**，供 B/C 使用；14/15 随后。

## 负责范围
tasks.json 任务 13（骨架先行）、14、15。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-d-governance`
- 分支：`feature/lane-d-governance`
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成；`UserContext`/SSE/Token/`VectorStore` 契约已冻结。

## 独占可改
`com.slz.crm.platform.**`（trace/async/resilience/quota/token/lifecycle/reconcile/audit，**contract 除外**）、Flyway `V5x__*`。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约、B/C 的业务实现。

## 要做（任务 13 骨架先行）
1. `RequestTraceFilter` 写 traceId；异步/流式/延迟任务经 `MdcTaskDecorator.wrap` 传播并清理 MDC（业务代码不改）。
2. 分任务类型线程池（批量上传/流式问答/**记忆旁路**/嵌入/解析）独立队列/拒绝/超时 + 指标；**记忆旁路执行器（pool=2/有界队列/AbortPolicy + CAS 单飞 + 拒绝降级）作为共享基础设施，供 C 助手的意图/摘要复用（C 不自建）**。
3. 修复 `DependencyResilienceExecutor` 熔断开路直接拒绝缺 cause（判为可重试）；依赖不可用批量文件回 PENDING 而非 FAILED。
## 要做（14/15）
4. 身份/IP/知识库/全局四层配额 + 上传容量 + 活跃流式会话上限；超限返回机器可读原因/重试间隔 + 审计指标。
5. Token 预算：请求前预算检查 + 请求后计量落库（chat/embedding/ocr/vision/**summary/intent** 分策略），消费 Token 计量契约聚合；**补齐 RAG 意图/摘要用 `chat(String)` 重载漏计 token 的盲点**（统一走能返回 usage 的调用）。
6. 文档生命周期事件（幂等）+ 跨存储对账（MinIO/MySQL/Qdrant/快照，dry-run/保留窗口/补偿）+ 审计事件 + 治理指标看板；Flyway `V5x__*`。
7. **Actuator 健康与指标**：移植 `QdrantHealthIndicator`/`MinioHealthIndicator` 挂 VectorStore 抽象+MinIO；liveness/readiness 健康分组（依赖不可用 readiness DOWN/liveness UP，内存回退 WARN）；`show-details=when_authorized`；指标合并到 Micrometer/Prometheus（AiChatMetrics+TaskExecutorMetricsBinder+rag.retrieval.duration+治理指标）；健康端点收敛（CRM /health + actuator）。

## 关键坑
- SSE **只加 traceId 装饰，不改事件结构**（结构归 C 助手）。
- Token 由 B/C 产数、**你只聚合**，别自建计量；但要**补意图/摘要的计量盲点**。
- 线程池/韧性/**记忆旁路执行器**骨架要**早于** B/C 就绪（C 的记忆增强依赖你的旁路执行器）。
- Actuator 保护由基座(Agent-0)挂拦截器，你负责健康 indicator/分组/指标合并，别改 publicPaths。

## 注释重点（本 lane）
- **并发与韧性**（MDC 跨线程传播与清理、线程池拒绝降级、熔断开路 cause 语义、PENDING 恢复、对账幂等键）**必须逐处行内注释解释原因与失败边界**——这是治理正确性的关键。
- 每个线程池的配置项注释说明"服务哪类任务、为何这样设队列/拒绝策略"（对齐 RAG application.yaml 风格）。
- 配额/预算的阈值来源与超限响应字段注释清楚。
- Flyway `V5x`（配额/计量/生命周期/对账/审计表）头部注释写明用途与索引理由。

## 出口条件
跨线程 traceId、线程池拒绝降级、记忆旁路单飞/饱和跳过、熔断开路/重试/批量恢复、配额拒绝、预算边界(含意图/摘要计量)、对账识别/清理、审计可追溯、Actuator 健康分级/指标暴露 测试通过。

## 产出
变更摘要 + 测试结果 + 契约/依赖变更申请（如有）。
