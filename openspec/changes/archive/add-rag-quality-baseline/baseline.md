# 基线记录 — add-rag-quality-baseline

> 用途：提案 2–5（混合检索补全、上下文压缩、语义切分、查询侧增强）做"不回退/提升"判定时的对照锚点。
> 报告本体：`docs/rag-quality/baseline-v1.json`（由 `RagRealRetrievalBenchmarkIT` 门控运行后落盘）。

## 状态

- [x] **基线数字已首跑**（run-baseline-ladder 第一跑，授权执行，≈¥0.1）：`docs/rag-quality/baseline-v1.json` 已落盘，
  metrics 摘要见下文"基线数字"节。本文档作为提案 2–5"不回退/提升"判定的对照锚点。

## 运行环境与口径

| 项 | 值 |
|---|---|
| 基准集版本 | `RagBenchmarkSuite.SUITE_VERSION = 1.0`（18 条：TEXT 5 / TABLE 3 / IMAGE 2 / LEXICAL 6 / EDGE 2） |
| 对话模型 | `qwen-plus`（`ModelProviderProperties` 默认，答案生成与判卷） |
| 嵌入模型 | `text-embedding-v3`（入库与查询同模型，保证维度与语义空间一致） |
| 向量库 | `InMemoryVectorStore`（评测环境；检索算法与生产同源：双路召回 + BM25 融合重排） |
| topK / 截断 k | 5 |
| 评测知识库 id | 99001（语料幂等入库，chunkId 形如 `<语料key>-<chunkIndex>`） |
| 授权 | 评测桩固定放行评测库；生产授权语义不在基准考察范围 |

指标口径（与 `RagQualityReport.Metrics` 字段一一对应）：

- recall@k / precision@k / MRR / hitRate / citationPrecision / answerConsistency：`RagQualityEvaluator` 定义的口径；
  边界用例（期望片段为空）按 D16 语义——不召回、不伪造引用记满分。
- token：**只计答案生成 + 判卷两段**；检索侧查询改写与嵌入的 usage 被 `EmbeddingService` /
  `RetrievalQueryRewriteService` 消费后不回传，无法回收（改这两处属行为变更，不在本提案范围）。
- TTFT：流式答案的首 chunk 时延；总延迟 = 检索 + 生成 + 判卷全程。
- failureRate：任一阶段抛异常的用例占比；基线要求 ≤ 0.25。

## 复现命令

```bash
# 需授权后执行：真实外发 DashScope 请求
RAG_BENCHMARK_REAL=1 mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT
# 报告输出：docs/rag-quality/baseline-v1.json（含 suiteVersion / generatedAt / metrics / cases）
```

不设 `RAG_BENCHMARK_REAL` 或无 key 时该 IT 按假设跳过，`mvn verify` 不受影响（failsafe 计 skipped，总数不减）。

## 基线数字（首跑已回填，generatedAt = 2026-09-12T15:46:47Z）

```
metrics:
  caseCount 18
  meanRecallAtK 0.9444
  meanPrecisionAtK 0.2556
  mrr 0.8472
  hitRate 1.0
  meanCitationPrecision 0.8056
  meanAnswerConsistency 1.0
  meanTtftMs 366.9 ms
  meanTotalLatencyMs 3450.3 ms
  totalTokens 17032
  failureRate 0.0
```

分类明细（首跑即发现关键信号，供 run5 触发判定）：**TEXT 5 条 recall@k=1.0 / MRR=1.0，LEXICAL 6 条 recall@k=1.0 / MRR=1.0**。
在纯向量单路基线下 LEXICAL 与 TEXT 均已饱和到满格，**无词汇失配缺口可被稀疏路/查询侧提升**（详见 run-baseline-ladder 验证记录）。
