# DynamicConfig 键清单（检索链路相关）

> 维护约定：新增/变更 DynamicConfig 键随所属提案同步更新本清单（complete-hybrid-retrieval-and-rerank 任务 5.4 起建）。
> 口径：键名 / 类型 / 默认值 / 语义与回退。动态配置实现（Lane E）未配置或类型不匹配时返回调用方给定的默认值，
> 因此下表"默认值"即缺省回退行为，缺配置不影响检索可用性。

## rag.retrieval.* —— 检索管线（Lane B）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.retrieval.topK` | Integer | 5 | 最终返回条数；调用方显式传入时优先生效 |
| `rag.retrieval.minScore` | Double | 0.20 | 向量召回相似度下限（0~1），越界值回落默认 |
| `rag.retrieval.strictKb` | Boolean | — | KB 空匹配兜底开关（D16，既有键） |
| `rag.retrieval.chunkSize` | Integer | — | 入库切分尺寸（既有键） |
| `rag.retrieval.chunkOverlap` | Integer | — | 入库切分重叠（既有键） |
| `rag.retrieval.query-rewrite.enabled` | Boolean | true | 查询改写开关；关闭或模型失败时用原查询 |

## rag.retrieval.fusion.* —— 双路融合（提案2 新增，任务 3.1）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.retrieval.fusion.mode` | String | `rrf` | 向量路+稀疏路融合模式：`rrf`（默认，Rank Fusion）\| `weighted`（升级前行为，稀疏路完全不参与=回退开关）。其他值一律按 rrf 处理 |
| `rag.retrieval.fusion.rrf-k` | Integer | 60 | RRF 常数 k（score=Σ1/(k+rank)）；&lt;1 回落默认 |

## rag.retrieval.rerank.* —— 重排（提案2 新增/沿用，任务 4.1–4.2）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.retrieval.rerank.mode` | String | `default` | 重排器选择：`default`（向量/BM25 归一化加权）\| `llm`（LLM listwise 重排，需 ModelProvider 可用）。llm 未装配或值非法时落 default |
| `rag.retrieval.rerank.vector-weight` | Double | 0.60 | 默认重排链向量分权重（0~1），越界回落默认 |
| `rag.retrieval.rerank.bm25-weight` | Double | 0.40 | 默认重排链 BM25 分权重（0~1），越界回落默认 |
| `rag.retrieval.rerank.candidate-multiplier` | Integer | 4 | 候选倍数：topK × 倍数 = 向量路/稀疏路各自召回上限 |
| `rag.retrieval.rerank.llm.timeout-ms` | Long | 3000 | LLM 重排等待超时；超时即回退默认重排链 |
| `rag.retrieval.rerank.llm.max-candidates` | Integer | 20 | LLM 重排精排候选上限，超出部分保持原序排在尾部 |

## rag.retrieval.image-*-route-weight —— 图文路由融合（既有键）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.retrieval.image-text-route-weight` | Double | 0.70 | 文本路权重（0~1） |
| `rag.retrieval.image-vector-route-weight` | Double | 0.30 | 图片路权重（0~1） |

## rag.intent.* —— 意图/类目（D17 既有键）

| 键 | 类型 | 语义 |
|---|---|---|
| `rag.intent.categories` | List&lt;String&gt; | 意图类目清单；检索侧以 `RetrievalQuery.intentCategory` 承接（提案2 任务 2.1 起两路过滤生效） |
| `rag.intent.keywords` | List&lt;String&gt; | 类目关键词 |
| `rag.intent.filterEnabled` | Boolean | 意图过滤开关 |
