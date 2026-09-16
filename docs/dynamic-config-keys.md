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

## rag.retrieval.vision-pdf.* —— 图像 PDF 视觉转写试点（add-vision-pdf-ingest-pilot）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.retrieval.vision-pdf.enabled` | Boolean | **false** | 总开关；关/未配/转写器未装配 = 仅文本层（与升级前一致） |
| `rag.retrieval.vision-pdf.min-text-chars` | Integer | 80 | normalize 后文本短于此值才尝试 VLM；范围 1~2000 |
| `rag.retrieval.vision-pdf.max-pages` | Integer | 3 | 单文档最多视觉转写页数；范围 1~20；超出保留文本层 |

> 失败回退：渲染失败 / vision 抛错 / 转写 blank 或 &lt;40 字 / 「（截图不清）」÷汉字 &gt;0.4 → 保留该页文本层，不使整篇 ingest 抛视觉异常。真 VLM 外呼须另授权。

## rag.context.* —— 上下文组装：邻居增强与压缩（提案3 新增，add-context-compression-and-enrichment）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.context.neighbors` | Integer | 1 | 邻居增强开关：1（默认，取命中块紧邻前/后各一片）\| 0（关闭，输出与升级前逐字一致）。&lt;0 按 1 处理。邻居只进上下文、不进 SourceReference |
| `rag.context.token-budget` | Integer | 4096 | 上下文 token 预算（TokenEstimator 估算口径）；超预算触发压缩，未超预算原文逐字保留。&lt;1 回落默认 |
| `rag.context.compressor.mode` | String | `rule` | 压缩器选择：`rule`（确定性规则压缩，默认）\| `llm`（LLM 要点化压缩，需 ModelProvider 可用）。llm 未装配或值非法时落规则链 |
| `rag.context.compressor.llm.timeout-ms` | Long | 3000 | LLM 压缩等待超时；超时/失败/空输出/编号不完整/仍超预算均回退规则压缩链 |

> 计量口径：LLM 压缩调用 token 挂 `TokenUsageRecorder`（type=SUMMARY，`TokenUsageType` 为冻结契约无压缩枚举值，语义最近者为摘要旁路）。

## rag.chunking.* —— 切分策略（提案4 + add-paragraph-chunking）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.chunking.strategy` | String | `fixed` | 切分策略：`fixed`（升级前 320/40 滑窗，现行为回退）\| `semantic`（标题/段落/转折词边界，段长受 max-chunk-size 约束）\| `paragraph`（add-paragraph-chunking：同窗 320/40，优先在 `\n\n`/`\n` 段落界收刀，避免图注横切；**默认仍为 fixed**，生产要生效须显式设为 paragraph）。其他值一律按 fixed 处理。策略只影响新摄取/重建的切片；页锚点（D15）任何策略都按页附加 |
| `rag.chunking.max-chunk-size` | Integer | 480 | semantic 策略单块字符上限；超上限段落按句界二次切分。&lt;1 回落默认 |

## rag.context.parent-expand —— 双粒度父块展开（提案4 新增，upgrade-semantic-chunking-and-index）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.context.parent-expand` | String | `on` | 父块展开开关：`on`（命中挂父块的子块时上下文放父块全文，引用/锚点仍指子块）\| `off`（回退邻居增强模式）。未挂父块的命中（fixed 切分、单片逻辑段、评测占位 id、快照行缺失）逐块回退邻居拼装——fixed 数据下 on 与 off 输出一致 |

> 重建入库（reingest）触发不走动态配置，是运维 runner 环境变量：`RAG_REINGEST_TRIGGER=all|<documentId,...>` + `RAG_REINGEST_OPERATOR_ID=<用户主键>`（提案4 任务 4.3/4.4；全量重嵌入有真实 API 成本，执行前需用户确认）。

## rag.intent.* —— 意图/类目（D17 既有键）

| 键 | 类型 | 语义 |
|---|---|---|
| `rag.intent.categories` | List&lt;String&gt; | 意图类目清单；检索侧以 `RetrievalQuery.intentCategory` 承接（提案2 任务 2.1 起两路过滤生效） |
| `rag.intent.keywords` | List&lt;String&gt; | 类目关键词 |
| `rag.intent.filterEnabled` | Boolean | 意图过滤开关 |

## rag.query.* —— 查询侧增强（提案5 新增，enhance-query-transformation，默认全关）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.query.multi-query.enabled` | Boolean | false | 多查询变体开关（方案07 增强）。开启后对主查询一次 LLM 生成 N 变体，N+1 路并行召回 → RRF 融合（`RrfFusion.fuseAll`）→ 单一重排。关闭/LLM 失败/空输出回退单查询（现行为）；`fusion.mode=weighted` 时本增强不参与 |
| `rag.query.multi-query.variants` | Integer | 3 | 变体数量，钳位 1~5（防 runaway 成本）；越界回落默认 |
| `rag.query.hyde.enabled` | Boolean | false | HyDE 假设答案开关（方案15）。开启后生成假设答案→嵌入→纯向量召回路，与原查询路 RRF 融合。假设答案只用于检索向量，绝不进入生成上下文/SourceReference；关闭/失败/超时回退原查询路 |
| `rag.query.hyde.timeout-ms` | Long | 3000 | HyDE 生成等待超时；超时跳过 HyDE 路 |
| `rag.query.derived-questions.enabled` | Boolean | false | 衍生问题入库旁路开关（方案06）。开启后入库/重建成功后异步每块生成反向问题→嵌入→向量记录关联原块 chunkId（命中即回原块，`VectorRecord.text` 保持原块原文）。关闭=无衍生向量（回退现行为） |
| `rag.query.derived-questions.max-per-chunk` | Integer | 2 | 每块反向问题上限，钳位 1~5 |

> 衍生问题旁路线程池不走 DynamicConfig，是 Spring 配置：`platform.async.derived-questions.queue-capacity`（默认 64）/`platform.async.derived-questions.await-termination-ms`（默认 10000）；队列饱和丢弃（discard-log）=该文档退化为无衍生向量，不阻塞入库主链。
