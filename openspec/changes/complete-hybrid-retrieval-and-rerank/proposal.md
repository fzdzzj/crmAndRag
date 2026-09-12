# 提案：补全混合检索与重排升级

> 变更 ID：`complete-hybrid-retrieval-and-rerank` ｜ 能力域：`knowledge-retrieval` ｜ 序列：第 2/5 个 ｜ 前置：`add-rag-quality-baseline`（基线对照）
> 对应 rag-kb 方案：**16 Hybrid Search（补全）+ 08 Rerank（升级）**；S11 生产级矩阵四件套之二。

## Why

**rag-kb 方案 16 的解决对象**：纯向量检索的短板是型号、代码、专有名词——语义相似度对精确词不敏感。**方案 08**：向量检索只管"像不像"，不管"是不是"，需重排精排（cross-encoder/LLM）。`06-检索后处理.md` 教多路召回用 RRF 融合。

**代码现状与 16 名不副实**（已核）：

1. `KnowledgeRetrievalServiceImpl.recall()`（L98-L113）是**纯向量单路召回**（按 knowledgeBaseId 过滤）。
2. `Bm25Scorer.score(query, candidates)`（L20-L50）只对**已召回候选**打分，且 BM25 的 df 统计也只在候选集内——**词法精确命中但向量漏召的块，永远进不了候选集**。这不是 Hybrid Search，是"rerank 内加权"（向量 0.6 + BM25 0.4，L116-L135）。
3. D17 半成品：`category` 写入向量 metadata（`DocumentIngestionService.createVectorRecord`）但 `recall()` 过滤只有 knowledgeBaseId（L105）——类目过滤没接上。

**基线证据**：`add-rag-quality-baseline` 的 LEXICAL 用例将量化此漏召（预期基线 recall 偏低），本提案验收即以它对照。

## What Changes

### 1. 语料级稀疏召回路（方案 16 补全）
- **设计决策——BM25 语料索引载体**：

| 选项 | 说明 | 取舍 |
|---|---|---|
| A（推荐） | MySQL FULLTEXT（ngram parser）索引 `document_vector_chunk.chunk_text`，`MATCH...AGAINST` 预筛 | 零新基建；DB 是切片唯一真相源；索引自动随写入维护 |
| B | 启动时内存倒排索引 + 入库增量维护 | 快但占内存、多实例不共享、重启重建 |
| C | Qdrant sparse vector | 最正统但引入双表示写入与同步复杂度 |

  采用 A：新增 Flyway **V22** 迁移（`FULLTEXT INDEX ... WITH PARSER ngram`）。若实测中文 ngram 召回质量不达标，回退 B（任务里含验证闸门）。
- 稀疏路结果同样按授权 KB 集合过滤（**授权语义不能被稀疏路绕过**）。
- 保留现有 `Bm25Scorer` 作为候选内精排组件。

### 2. 双路 RRF 融合（rag-kb 06/05）
- 向量路 + 稀疏路召回 → **RRF** 融合（`score = Σ 1/(k + rank)`，k 默认 60，DynamicConfig `rag.retrieval.fusion.rrf-k`）。
- 融合模式键 `rag.retrieval.fusion.mode = rrf | weighted`（默认 rrf；weighted = 现行为，回退开关）。
- 候选倍数、topK 流程不变。

### 3. 重排器抽象与升级（方案 08）
- 新增 `Reranker` 接口（knowledge/retrieval 内部组件，非平台契约）：`rerank(query, candidates) -> candidates`。
- 默认实现 = 现有"向量/BM25 归一化加权"（**行为等价，保证可回退**）。
- 可选实现 = LLM rerank：经 `ModelProvider` + `ModelCallOptions` 对候选做 listwise 打分；DynamicConfig `rag.retrieval.rerank.mode = default | llm`；失败/超时回退默认链。

### 4. 类目过滤收尾（D17）
- `recall()` 的 filter 增 `category`（可空 = 不过滤）；与授权过滤叠加。稀疏路同语义。

## Impact

- **规范**：`specs/knowledge-retrieval/spec.md`（ADDED Requirements，全新能力域）。
- **代码**：`knowledge/retrieval/` 新增 `SparseRecallService`（MATCH AGAINST 查询 + 授权过滤）、`RrfFusion`、`Reranker` 接口与两实现；`KnowledgeRetrievalServiceImpl` 接稀疏路+融合；`DocumentVectorChunkMapper` 增全文检索方法。
- **DB**：Flyway **V22**（FULLTEXT ngram 索引；不改已合入脚本）。
- **配置**：DynamicConfig 新键 `rag.retrieval.fusion.mode` / `fusion.rrf-k` / `rerank.mode`（+LLM rerank 参数）。
- **契约**：`KnowledgeRetrievalPort`/`SourceReference`/`CrmVectorStore` 签名不变。
- **验收**：以 `docs/rag-quality/baseline-v1.json` 对照。

## 风险

- MySQL ngram 中文召回质量不达预期 → 任务含针对性验证（LEXICAL fixtures 真库跑）；不达标切选项 B，迁移只加索引不删数据。
- RRF/权重参数敏感 → 全走 DynamicConfig 可调，默认值即现行为或保守值。
- LLM rerank 增加延迟/成本 → 默认关；启用后失败回退默认链。
- 稀疏路引入新授权绕过面 → 稀疏路单测覆盖越权用例。

## Non-Goals

- 不动图文路由融合（0.7/0.3，属现有能力）与 `SourceReference` 结构。
- 不做 cross-encoder 模型引入（LLM rerank 已覆盖"精排"语义；专用模型另立提案）。
- 不改切分/嵌入/上下文（提案 3、4 范围）。
