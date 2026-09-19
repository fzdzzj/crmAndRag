# SSE/LLM 超时预算汇总

本文档汇总了系统中所有与超时相关的配置项，包括默认值、单位、影响面及建议范围。

## 配置总览表

| 组件 | 变量名 | 默认值 | 单位 | 影响面 |
|------|--------|--------|------|--------|
| AiChatController | `crm.ai.sse-timeout-seconds` | 300 | 秒 | SSE 长连接断开阈值，建议≥180 避免网络抖动导致中断 |
| LlmReranker | `rag.retrieval.rerank.llm.timeout-ms` | 3000 | 毫秒 | LLM 重排调用超时，默认 3 秒适合快速排序场景 |
| LlmContextCompressor | `rag.context.compressor.llm.timeout-ms` | 3000 | 毫秒 | LLM 上下文压缩超时，默认 3 秒防止阻塞检索链 |
| HydeQueryExpander | `rag.query.hyde.timeout-ms` | 3000 | 毫秒 | HyDE 假设答案生成超时，默认 3 秒控制幻觉成本 |
| Platform AI Model Provider | `platform.ai.model.timeout-seconds` | 60 | 秒 | AI 模型单次调用超时阈值，超过则降级或重试 |
| CRM AI Assistant | `crm.ai.llm-timeout-seconds` | 60 | 秒 | LLM 调用超时，用于工具调用和对话生成 |
| MinIO Client | `knowledge.minio.connect-timeout-ms` | 10000 | 毫秒 | MinIO 连接建立超时，建议≥5000 应对网络延迟 |
| MinIO Client | `knowledge.minio.read-timeout-ms` | 30000 | 毫秒 | MinIO 数据读取超时，建议≥15000 处理大文件 |
| App Dependencies | `app.dependency.health-timeout-ms` | 2000 | 毫秒 | Qdrant/MinIO健康检查超时，用于 `/actuator/health` |

## 超时层级说明

### 1. SSE 长连接层（300 秒）
- **适用场景**：流式对话、长任务生成
- **风险**：过短易受网络抖动影响，过长占用连接池资源
- **调优建议**：生产环境建议≥180 秒，测试环境可降至 60 秒

### 2. LLM 快速决策层（3 秒）
- **适用组件**：LlmReranker、LlmContextCompressor、HydeQueryExpander
- **特点**：轻量级 LLM 调用，期望快速返回结果
- **风险**：超时直接走本地回退逻辑（如规则重排、原始上下文）
- **调优建议**：保持 3 秒默认值；高负载时可降至 1-2 秒

### 3. 模型调用层（60 秒）
- **适用场景**：通义千问/OpenAI 兼容 API 调用
- **风险**：复杂任务（如长文档分析）可能超时
- **调优建议**：简单问答 30 秒，复杂任务 120 秒

### 4. 基础设施层（10-30 秒）
- **适用组件**：MinIO 客户端、健康检查
- **特点**：I/O 密集型，受网络带宽影响
- **调优建议**：内网部署可降至 5 秒，跨地域需≥30 秒

## 环境变量覆盖

所有超时配置均可通过环境变量覆盖：

```bash
# SSE 超时（秒）
export SPRING_AI_SSE_TIMEOUT_SECONDS=300

# LLM 调用超时（秒）
export CRM_AI_LLM_TIMEOUT_SECONDS=60

# Rag 相关超时（毫秒）
export RAG_RETRIEVAL_RERANK_LLM_TIMEOUT_MS=3000
export RAG_CONTEXT_COMPRESSOR_LLM_TIMEOUT_MS=3000
export RAG_QUERY_HYDE_TIMEOUT_MS=3000

# MinIO 超时（毫秒）
export KNOWLEDGE_MINIO_CONNECT_TIMEOUT_MS=10000
export KNOWLEDGE_MINIO_READ_TIMEOUT_MS=30000
```

## 监控建议

1. **SSE 连接时长分布**：监控 `sse.connection.duration` 直方图，95% 分位应 < 180 秒
2. **LLM 调用成功率**：监控 `llm.call.success.rate`，超时率 > 5% 时考虑扩容
3. **基础设施响应时间**：MinIO/Qdrant 的 P99 延迟应 < 5 秒，否则调整超时阈值

## 变更历史

- **2026-09-19**：初始版本，基于 TASK-07 规格文档完成文档化
