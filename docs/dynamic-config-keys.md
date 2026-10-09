# DynamicConfig 键清单（检索链路相关）

> 维护约定：新增/变更 DynamicConfig 键随所属提案同步更新本清单（complete-hybrid-retrieval-and-rerank 任务 5.4 起建）。
> 口径：键名 / 类型 / 默认值 / 语义与回退。动态配置实现（Lane E）未配置或类型不匹配时返回调用方给定的默认值，
> 因此下表"默认值"即缺省回退行为，缺配置不影响检索可用性。
> 单一真相源（TASK-18）：`rag.retrieval.topK`=5、`rag.retrieval.minScore`=0.20 两行的默认值与
> `RetrievalDefaults` 常量、`DynamicConfigKeyRegistry` 展示默认、检索服务运行默认及基准 TOP_K=5 由
> `RetrievalParamTruthSourceTest`（CI 阶段 1）强制一致，本表任一侧单独改动即红。
>
> 注册状态（register-rag-retrieval-dynamic-keys，2026-09-25）：下列 11 键已登记进
> `DynamicConfigKeyRegistry`（`rag.retrieval` 命名空间），超管可经既有动态配置管理入口写入、热生效；
> 登记未改任何默认值或语义，本表默认值即消费点代码缺省——
> `rag.retrieval.query-rewrite.enabled`、`rag.retrieval.fusion.mode`、`rag.retrieval.fusion.rrf-k`、
> `rag.retrieval.rerank.mode`、`rag.retrieval.rerank.vector-weight`、`rag.retrieval.rerank.bm25-weight`、
> `rag.retrieval.rerank.candidate-multiplier`、`rag.retrieval.rerank.llm.timeout-ms`、
> `rag.retrieval.rerank.llm.max-candidates`、`rag.retrieval.image-text-route-weight`、
> `rag.retrieval.image-vector-route-weight`。
> 注册状态（register-rag-context-query-dynamic-keys，2026-09-25）：命名空间白名单扩为八个（新增
> `rag.context` / `rag.chunking` / `rag.query`），下列 13 键已同样登记进 `DynamicConfigKeyRegistry`，
> 超管可经既有动态配置管理入口写入、热生效；登记未改任何默认值或语义，本表默认值即消费点代码缺省——
> `rag.context.neighbors`、`rag.context.token-budget`、`rag.context.compressor.mode`、
> `rag.context.compressor.llm.timeout-ms`、`rag.context.parent-expand`、`rag.chunking.strategy`、
> `rag.chunking.max-chunk-size`、`rag.query.multi-query.enabled`、`rag.query.multi-query.variants`、
> `rag.query.hyde.enabled`、`rag.query.hyde.timeout-ms`、`rag.query.derived-questions.enabled`、
> `rag.query.derived-questions.max-per-chunk`。
> HyDE / 多查询 / 衍生问题 / LLM 压缩默认关，开启会产生模型调用费用（登记描述已写明，是否开启由 owner 拍板）；
> 切分策略只影响新摄取/重建，不自动重嵌。
> 注册状态（wire-circuit-dynamic-config，2026-10-08）：命名空间白名单扩为九个（新增
> `platform.resilience`），登记 2 个依赖熔断治理全局键，新调用实时读取生效、在飞 OPEN 不追溯、非法/缺失 fail-safe 回落——
> `platform.resilience.failure-threshold`（默认 5）与 `platform.resilience.open-duration-ms`（默认 30000）。
> 注册状态（wire-circuit-per-dependency-override，2026-10-08）：在 `platform.resilience` 命名空间下追加 10 个
> 按依赖名覆盖键（五依赖 × 2 参数，默认值=全局默认 5/30000 展示），逐级回落覆盖键 → 全局键 → 默认值。
> 注册状态（wire-ingestion-recovery-replay，2026-10-08）：命名空间白名单扩为十个（新增
> `rag.ingest`），登记 2 个摄取恢复重放配置键，新调用实时读取生效、非法/缺失 fail-safe 回落——
> `rag.ingest.replay-enabled`（默认 false，费用红线：重放=真实嵌入调用，必须显式开启）与
> `rag.ingest.replay-batch-size`（默认 5，范围 1~50）。

## rag.retrieval.* —— 检索管线（Lane B）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.retrieval.topK` | Integer | 5 | 最终返回条数；调用方显式传入时优先生效 |
| `rag.retrieval.admin-vector.enabled` | Boolean | **false** | 管理端真向量检索总开关；缺失/读取失败按关闭处理。仅超管可通过既有动态配置入口写入，实际开启仍需 owner 授权；不是金额上限 |
| `rag.retrieval.minScore` | Double | 0.20 | 向量召回相似度下限（0~1），越界值回落默认 |
| `rag.retrieval.strictKb` | Boolean | — | KB 空匹配兜底开关（D16，既有键） |
| `rag.retrieval.chunkSize` | Integer | — | 入库切分尺寸（既有键） |
| `rag.retrieval.chunkOverlap` | Integer | — | 入库切分重叠（既有键） |
| `rag.retrieval.query-rewrite.enabled` | Boolean | true | 查询改写开关；关闭或模型失败时用原查询 |

> 管理端真向量端点另有静态配额 `platform.quota.admin-vector-user-per-minute`，默认每用户每 JVM 实例每分钟 3 次。它是固定窗口的工作量保护，不是跨实例全局限制，也不是金额或 Provider 账户预算；禁用、参数拒绝、无授权 KB 不占用额度。生产开启和真实试点仍需 owner 授权。

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
| `rag.context.neighbors` | Integer | 1 | 邻居增强开关：1（默认，取命中块紧邻前/后各一片）\| 0 或负值（关闭，输出与关闭邻居增强一致；消费点按 `>=1` 为开判定）。邻居只进上下文、不进 SourceReference |
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

## platform.resilience.* —— 依赖熔断治理（wire-circuit-dynamic-config / wire-circuit-per-dependency-override）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `platform.resilience.failure-threshold` | Integer | 5 | 依赖熔断连续失败阈值：连续失败达到此次数后熔断器进入 OPEN 状态。范围 1~1000；非法/越界/缺失回落 5 |
| `platform.resilience.open-duration-ms` | Long | 30000 | 依赖熔断开闸保持时长（毫秒）：开闸达到该时长后进入 HALF_OPEN 单探测状态。范围 0~86400000；非法/越界/缺失回落 30000 |
| `platform.resilience.failure-threshold.model-chat` | Integer | 5 | 依赖 model-chat 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.model-chat` | Long | 30000 | 依赖 model-chat 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.model-embed` | Integer | 5 | 依赖 model-embed 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.model-embed` | Long | 30000 | 依赖 model-embed 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.model-vision` | Integer | 5 | 依赖 model-vision 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.model-vision` | Long | 30000 | 依赖 model-vision 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.vector-qdrant` | Integer | 5 | 依赖 vector-qdrant 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.vector-qdrant` | Long | 30000 | 依赖 vector-qdrant 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.storage-minio` | Integer | 5 | 依赖 storage-minio 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.storage-minio` | Long | 30000 | 依赖 storage-minio 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |

> 实时生效与回退：每次调用按依赖名实时读取——覆盖键 → 全局键 → 默认值，逐级回落（覆盖键越界回全局，全局也越界回默认 5/30000；拼错依赖名自然回落全局）。已写入 openUntilNanos 的在飞 OPEN 窗口不被追溯调整；键缺失、删除或写入非法值时 fail-safe 回落，绝不抛出配置异常打断业务调用。maxAttempts/backoff 不暴露（零自动重试）；走既有通用管理员动态配置权限。

## rag.ingest.* —— 摄取恢复重放（wire-ingestion-recovery-replay）

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| `rag.ingest.replay-enabled` | Boolean | **false** | 摄取恢复自动重放总开关：默认关闭（费用红线：重放=真实嵌入调用，必须显式开启）。开启时定时扫描 PENDING 状态文档以原上传者身份自动重放；关闭、缺失或读取异常时空转跳过 |
| `rag.ingest.replay-batch-size` | Integer | 5 | 摄取恢复自动重放单批最大处理文档数，范围 1~50；非法、越界或缺失时 fail-safe 回落默认值 5 |

> 调度与身份：由 IngestionReplayScheduler 固定 tick 调度（默认 60s），以 uploaded_file.userId 加载真实用户构造 UserContext 调用既有 reingest 入口，走既有 canWrite 授权与平台治理审计；上传者不存在、离职、冻结或无权限时转 FAILED 终态并记录审计，熔断仍开快速拒绝时保持 PENDING 下轮再试。


## per-KB 覆盖（add-per-kb-retrieval-strategy-override）
> 知识库级检索策略覆盖：对单个知识库覆盖检索参数。恰 12 键白名单（v1 封闭集，扩充需 owner 拍板并同步本清单）；三层合并 = 覆盖值 > 全局动态配置 > 注册表默认；仅当授权收敛后 kbScope 恰为单库时应用覆盖（多库/全库/kbId 不可解析一律走全局）。覆盖值非法/越界 = WARN 审计 + 回落全局，打不断检索。写端点复用 KNOWLEDGE_ADMIN_MANAGE(900)：GET /knowledge/strategies（清单含来源 override/global/default）、PUT /knowledge/strategies/{key}（单键覆盖）、DELETE /knowledge/strategies/{key}（软删回落全局）、POST /knowledge/strategies/{key}/rollback（按版本回滚）。热失效：写后逐键失效 + 有界全量刷新兜底。

| 键 | 类型 | 默认值 | 语义与回退 |
|---|---|---|---|
| rag.retrieval.topK | Integer | 5 | 单库覆盖返回候选片段数（1~100，越界回落全局） |
| rag.retrieval.minScore | Double | 0.20 | 单库检索相关度阈值（0~1，越界回落全局） |
| rag.retrieval.fusion.mode | String | rrf | 单库融合模式（rrf/weighted） |
| rag.retrieval.fusion.rrf-k | Integer | 60 | 单库 RRF 常数 k |
| rag.retrieval.rerank.vector-weight | Double | 0.60 | 单库重排向量权重（0~1） |
| rag.retrieval.rerank.bm25-weight | Double | 0.40 | 单库重排 BM25 权重（0~1） |
| rag.retrieval.rerank.candidate-multiplier | Integer | 4 | 单库候选倍数 |
| rag.retrieval.image-text-route-weight | Double | 0.70 | 单库图文路由文本路权重（0~1） |
| rag.retrieval.image-vector-route-weight | Double | 0.30 | 单库图文路由图片路权重（0~1） |
| rag.context.neighbors | Integer | 1 | 单库邻居增强开关（0/1） |
| rag.context.parent-expand | String | on | 单库父块展开开关（on/off） |
| rag.retrieval.query-rewrite.enabled | Boolean | true | 单库查询改写开关 |

> 成本类（rerank.mode=llm、compressor.mode=llm、multi-query.*、hyde.*、derived-questions.*、vision-pdf.*、replay-*）与结构类（chunking.*、chunkSize/chunkOverlap）永远不得进入 per-KB 覆盖——封闭集白名单外键写入被拒。
