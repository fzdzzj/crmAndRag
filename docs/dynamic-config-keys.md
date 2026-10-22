# DynamicConfig 键清单（检索链路相关）

> 维护约定：新增/变更 DynamicConfig 键随所属提案同步更新本清单（complete-hybrid-retrieval-and-rerank 任务 5.4 起建）。
> 口径：键名 / 类型 / 默认值 / 权限档位 / 语义与回退。动态配置实现（Lane E）未配置或类型不匹配时返回调用方给定的默认值，
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
> 注册状态（add-self-rag-reflection，2026-10-10）：命名空间白名单扩为十一个（新增
> `rag.generation`），登记 3 个生成侧 Self-RAG 反思配置键，新调用实时读取生效、非法/缺失 fail-safe 回落——
> `rag.generation.selfrag.mode`（默认 rule，非法值回落默认）/ `rag.generation.selfrag.llm.timeout-ms`（默认 3000）/
> `rag.generation.selfrag.llm.max-claims`（默认 20）。三键全定级为 COST 档（超管专写，适用成本键申请-审批流）。
> 定级状态（add-dynamic-config-key-tier-acl，2026-10-09；add-self-rag-reflection 扩为 66 键；wire-built-unfinished-infra
> 2026-10-17 摘除 ai.model.* 六死键后为 60 键；fix-citation-threshold-config 2026-10-20 补 3 个引用对齐阈值键后为 63
> 键；其间历卡增补至 67 键（明细见各卡归档规格，勿据 63 直接推算）；image-retrieval-threshold-gate 2026-10-22
> 补 1 个图像同图判别阈值键后为 68 键）：注册表 68 键全集完成三档定级，
> 各表新增「权限档位」列——OPERATIONAL 36 键 = 608 持有者可写，COST 27 键 /
> STRUCTURAL 5 键 = 超管专写；608 语义与三档全清单见文末「键级权限分层（tier ACL）」节。

## rag.retrieval.* —— 检索管线（Lane B）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.topK` | Integer | 5 | OPERATIONAL | 最终返回条数；调用方显式传入时优先生效 |
| `rag.retrieval.admin-vector.enabled` | Boolean | **false** | COST | 管理端真向量检索总开关；缺失/读取失败按关闭处理。仅超管可通过既有动态配置入口写入，实际开启仍需 owner 授权；不是金额上限 |
| `rag.retrieval.minScore` | Double | 0.20 | OPERATIONAL | 向量召回相似度下限（0~1），越界值回落默认 |
| `rag.retrieval.strictKb` | Boolean | — | OPERATIONAL | KB 空匹配兜底开关（D16，既有键） |
| `rag.retrieval.chunkSize` | Integer | — | STRUCTURAL | 入库切分尺寸（既有键） |
| `rag.retrieval.chunkOverlap` | Integer | — | STRUCTURAL | 入库切分重叠（既有键） |
| `rag.retrieval.query-rewrite.enabled` | Boolean | true | OPERATIONAL | 查询改写开关；关闭或模型失败时用原查询 |

> 管理端真向量端点另有静态配额 `platform.quota.admin-vector-user-per-minute`，默认每用户每 JVM 实例每分钟 3 次。它是固定窗口的工作量保护，不是跨实例全局限制，也不是金额或 Provider 账户预算；禁用、参数拒绝、无授权 KB 不占用额度。生产开启和真实试点仍需 owner 授权。

## rag.retrieval.fusion.* —— 双路融合（提案2 新增，任务 3.1）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.fusion.mode` | String | `rrf` | OPERATIONAL | 向量路+稀疏路融合模式：`rrf`（默认，Rank Fusion）\| `weighted`（升级前行为，稀疏路完全不参与=回退开关）。其他值一律按 rrf 处理 |
| `rag.retrieval.fusion.rrf-k` | Integer | 60 | OPERATIONAL | RRF 常数 k（score=Σ1/(k+rank)）；&lt;1 回落默认 |

## rag.retrieval.rerank.* —— 重排（提案2 新增/沿用，任务 4.1–4.2）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.rerank.mode` | String | `default` | COST | 重排器选择：`default`（向量/BM25 归一化加权）\| `llm`（LLM listwise 重排，需 ModelProvider 可用）。llm 未装配或值非法时落 default |
| `rag.retrieval.rerank.vector-weight` | Double | 0.60 | OPERATIONAL | 默认重排链向量分权重（0~1），越界回落默认 |
| `rag.retrieval.rerank.bm25-weight` | Double | 0.40 | OPERATIONAL | 默认重排链 BM25 分权重（0~1），越界回落默认 |
| `rag.retrieval.rerank.candidate-multiplier` | Integer | 4 | OPERATIONAL | 候选倍数：topK × 倍数 = 向量路/稀疏路各自召回上限 |
| `rag.retrieval.rerank.llm.timeout-ms` | Long | 3000 | COST | LLM 重排等待超时；超时即回退默认重排链 |
| `rag.retrieval.rerank.llm.max-candidates` | Integer | 20 | COST | LLM 重排精排候选上限，超出部分保持原序排在尾部 |

## rag.retrieval.image-*-route-weight —— 图文路由融合（既有键）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.image-text-route-weight` | Double | 0.70 | OPERATIONAL | 文本路权重（0~1） |
| `rag.retrieval.image-vector-route-weight` | Double | 0.30 | OPERATIONAL | 图片路权重（0~1） |

## rag.retrieval.image-similarity-threshold —— 图像路同图判别闸门（image-retrieval-threshold-gate）

> 图像路不再复用文本 `rag.retrieval.minScore`（0.20）。阈值取定标锚
> `docs/rag-quality/image-threshold-calibration-v1.json` 的 `recommendedThreshold` = 0.9944；消费方
> `KnowledgeRetrievalServiceImpl#recallImage` + `ImageSimilarityGate`，文本路 minScore 行为零变化。
>
> **锚重标定耦合（手工同步，无编译期保护）**：重跑定标产出新锚后必须同时改三处，否则三者不一致——
> `ImageSimilarityGate.DEFAULT_THRESHOLD`（0.9944）、`ImageSimilarityGate.NEAR_DUPLICATE_FLOOR`（0.9887）、
> 以及 `DynamicConfigKeyRegistry` 里该键的默认串 `"0.9944"`；本表的 0.9944/0.9887 与定标 json 同步更新。
> **近似带：段边界自动缩放、权重常量需人工复核**——三等分段边界由 `[effectiveFloor(阈值), 阈值]` 现算
> （`floor` 或阈值一变，0.9906 / 0.9925 两个边界随之移动，**没有第二处需要同步的常量**）；但三档权重
> `NEAR_DUPLICATE_WEIGHT_HIGH/MID/LOW`（0.75 / 0.6 / 0.5）是代码常量、**不随锚缩放**，锚重标定后须人工复核
> 这三档是否仍匹配新的间隔带宽度（落带分布与「当前三档是否合理」的结论见
> `docs/rag-quality/image-near-duplicate-bands-v1.md`）。
> 另注意 `ProductionGrayRealRunIT` 的「每条必召回」断言隐含依赖下限 0.9887 低于真实同图余弦，抬锚时需一并复跑。

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.image-similarity-threshold` | Double | 0.9944 | OPERATIONAL | 图像路同图判别阈值（0~1）：cosine ≥ 阈值 = 高置信同图正常召回；[近似同图下限 0.9887, 阈值) = 近似同图**召回并打 `imageSimilarityTier=NEAR_DUPLICATE`**，重排后按余弦所在 1/3 段降权（`imageSimilarityBand`：`NEAR_HIGH` ×0.75 / `NEAR_MID` ×0.6 / `NEAR_LOW` ×0.5，段边界由 `[0.9887, 阈值]` 三等分推导、随锚自动缩放；band 缺失回落 0.5）；&lt; 0.9887 = 异图过滤。越界（&lt;0 或 &gt;1）回落 0.9944；不在 per-KB 12 键白名单内（per-KB 覆盖不生效） |

## rag.retrieval.vision-pdf.* —— 图像 PDF 视觉转写试点（add-vision-pdf-ingest-pilot）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.vision-pdf.enabled` | Boolean | **false** | COST | 总开关；关/未配/转写器未装配 = 仅文本层（与升级前一致） |
| `rag.retrieval.vision-pdf.min-text-chars` | Integer | 80 | COST | normalize 后文本短于此值才尝试 VLM；范围 1~2000 |
| `rag.retrieval.vision-pdf.max-pages` | Integer | 3 | COST | 单文档最多视觉转写页数；范围 1~20；超出保留文本层 |

> 失败回退：渲染失败 / vision 抛错 / 转写 blank 或 &lt;40 字 / 「（截图不清）」÷汉字 &gt;0.4 → 保留该页文本层，不使整篇 ingest 抛视觉异常。真 VLM 外呼须另授权。

## rag.retrieval.image-slice-ingest.* —— PDF 内嵌图片切片摄取（add-kb-image-slice-retrieval）

> 方案 B 文本降维闭环：PDF 内嵌图片对象（PDResources/XObject，非整页渲染）→ 每图 VLM 描述 → 描述文本作为单切片入现有
> chunk/embed 链路，命中后 `SourceReference.sourceType=IMAGE` 回显。**默认关 = 默认行为零变化**（零提取、零外呼、零落盘）。
> 三键均属 COST 档（拨动开关即产生真实 VLM 外呼费用），不进 per-KB 12 键覆盖白名单（与 `vision-pdf.*` 同处置）。

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.image-slice-ingest.enabled` | Boolean | **false** | COST | 总开关；关/未配/未装配存储 = 完全不摄取（与升级前一致） |
| `rag.retrieval.image-slice-ingest.kb-allowlist` | String | 空 | COST | KB 灰度白名单（逗号分隔 KB id）；空 = 总开关开启时全部知识库生效；命中才摄取 |
| `rag.retrieval.image-slice-ingest.max-images-per-document` | Integer | 5 | COST | 单文档最多处理的内嵌图片张数；范围 1~20；超出不送 VLM、不落盘 |

> 失败回退：图片解码失败 / vision 抛错 / 描述 blank 或 &lt;40 字 / 落盘失败 → 跳过该图，文本切片照常入库，绝不使整篇 ingest 抛错。
> 真 VLM 外呼真跑不在本卡（单测/IT 全 mock），生产灰度真跑由 Owner 另行授权。

## rag.retrieval.image-vector-ingest.* —— 真视觉向量摄取（multimodal-shared-space）

> 方案 A 真视觉闭环：同一张 PDF 内嵌图片在描述切片之外，直接由文本-图像联合 embedding（`platform.ai.model.image-embedding-model`,
> 默认 `multimodal-embedding-v1`）编码成视觉向量，写入独立集合 `knowledge.qdrant.image-collection`（维度 `image-dimensions`，
> 与文本维度解耦）。与方案 B 的 `image-slice-ingest.*` **并行**：文本降维描述切片保留为文本召回路，本键只控真视觉向量路。
> **默认关 = 默认行为逐字节一致**（零视觉 embedding 外呼、零 image 集合向量落盘；装配期仍 ensure 一次空集合建制，但零向量写入、零外呼）。
> 属 COST 档（拨动开关即产生真实视觉 embedding 外呼费用），不进 per-KB 12 键覆盖白名单。

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.image-vector-ingest.enabled` | Boolean | **false** | COST | 真视觉向量摄取总开关；关/未配 = 完全不生成视觉向量（视觉路退化关闭），描述切片不受影响 |

> 失败回退：视觉 embedding 抛错 / 返回空向量 / 写 image 集合失败 → 只丢该图（或该批）视觉向量并 WARN，描述切片与文本检索照常。
> 查询侧：`AiChatImageUnderstandingService` 的图片向量同样走真视觉 embedding，检索路由改查 image 集合；image 库未装配时退回文本集合旧行为。
> **运行期灰度授权路径**（multimodal-gray-and-eval-threshold，纯文档）：真视觉路嵌在 `image-slice-ingest.enabled` + KB 白名单门控之内，
> 打开顺序 / 灰度 KB 白名单 / 生产图帽 5 / 回滚 / 观察指标 / 成本基线引用见 `docs/multimodal-gray-rollout.md`；**默认值不变、零代码改动**。
> 真实语料判别阈值见 `docs/rag-quality/image-threshold-calibration-v1.json`；embedding token 不可得口径见 `docs/token-metering.md`。

## rag.retrieval.citation.* —— 引用对齐阈值（fix-citation-threshold-config）

> 三阈值原为 `CitationAligner` 编译期常量（KEEP_MIN=0.12 / REMAP_MIN=0.22 / TIE_MARGIN=0.08），本卡升级为动态配置：
> 默认值仍等于原常量，未改配置时对齐行为逐字节等价；生产消费点 `CitationThresholdsResolver` 在每次流式收尾时实时读取
> （`AiChatStreamLifecycle` 经 `ObjectProvider<DynamicConfigService>` 注入），缺失/类型不符/越界逐键回落默认常量。
> 写入护栏与本类构造校验同界：三键均只收 [0,1] 内有限数。

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.retrieval.citation.keepMin` | Double | 0.12 | OPERATIONAL | 引用编号可保留（KEEP）的最低支撑分（0~1）；低于此值原编号不再保留 |
| `rag.retrieval.citation.remapMin` | Double | 0.22 | OPERATIONAL | 重映射到最佳片段所需的最低支撑分（0~1）；低于此值该引用直接删除（DROP） |
| `rag.retrieval.citation.tieMargin` | Double | 0.08 | OPERATIONAL | 平局带宽：原编号与最佳片段得分差小于该值时保留原编号，避免误伤（0~1） |

## rag.context.* —— 上下文组装：邻居增强与压缩（提案3 新增，add-context-compression-and-enrichment）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.context.neighbors` | Integer | 1 | OPERATIONAL | 邻居增强开关：1（默认，取命中块紧邻前/后各一片）\| 0 或负值（关闭，输出与关闭邻居增强一致；消费点按 `>=1` 为开判定）。邻居只进上下文、不进 SourceReference |
| `rag.context.token-budget` | Integer | 4096 | COST | 上下文 token 预算（TokenEstimator 估算口径）；超预算触发压缩，未超预算原文逐字保留。&lt;1 回落默认 |
| `rag.context.compressor.mode` | String | `rule` | COST | 压缩器选择：`rule`（确定性规则压缩，默认）\| `llm`（LLM 要点化压缩，需 ModelProvider 可用）。llm 未装配或值非法时落规则链 |
| `rag.context.compressor.llm.timeout-ms` | Long | 3000 | COST | LLM 压缩等待超时；超时/失败/空输出/编号不完整/仍超预算均回退规则压缩链 |

> 计量口径：LLM 压缩调用 token 挂 `TokenUsageRecorder`（type=SUMMARY，`TokenUsageType` 为冻结契约无压缩枚举值，语义最近者为摘要旁路）。

## rag.chunking.* —— 切分策略（提案4 + add-paragraph-chunking）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.chunking.strategy` | String | `fixed` | STRUCTURAL | 切分策略：`fixed`（升级前 320/40 滑窗，现行为回退）\| `semantic`（标题/段落/转折词边界，段长受 max-chunk-size 约束）\| `paragraph`（add-paragraph-chunking：同窗 320/40，优先在 `\n\n`/`\n` 段落界收刀，避免图注横切；**默认仍为 fixed**，生产要生效须显式设为 paragraph）。其他值一律按 fixed 处理。策略只影响新摄取/重建的切片；页锚点（D15）任何策略都按页附加 |
| `rag.chunking.max-chunk-size` | Integer | 480 | STRUCTURAL | semantic 策略单块字符上限；超上限段落按句界二次切分。&lt;1 回落默认 |

## rag.context.parent-expand —— 双粒度父块展开（提案4 新增，upgrade-semantic-chunking-and-index）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.context.parent-expand` | String | `on` | OPERATIONAL | 父块展开开关：`on`（命中挂父块的子块时上下文放父块全文，引用/锚点仍指子块）\| `off`（回退邻居增强模式）。未挂父块的命中（fixed 切分、单片逻辑段、评测占位 id、快照行缺失）逐块回退邻居拼装——fixed 数据下 on 与 off 输出一致 |

> 重建入库（reingest）触发不走动态配置，是运维 runner 环境变量：`RAG_REINGEST_TRIGGER=all|<documentId,...>` + `RAG_REINGEST_OPERATOR_ID=<用户主键>`（提案4 任务 4.3/4.4；全量重嵌入有真实 API 成本，执行前需用户确认）。

## rag.intent.* —— 意图/类目（D17 既有键）

| 键 | 类型 | 权限档位 | 语义 |
|---|---|---|---|
| `rag.intent.categories` | List&lt;String&gt; | OPERATIONAL | 意图类目清单；检索侧以 `RetrievalQuery.intentCategory` 承接（提案2 任务 2.1 起两路过滤生效） |
| `rag.intent.keywords` | List&lt;String&gt; | OPERATIONAL | 类目关键词 |
| `rag.intent.filterEnabled` | Boolean | OPERATIONAL | 意图过滤开关 |

## rag.query.* —— 查询侧增强（提案5 新增，enhance-query-transformation，默认全关）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.query.multi-query.enabled` | Boolean | false | COST | 多查询变体开关（方案07 增强）。开启后对主查询一次 LLM 生成 N 变体，N+1 路并行召回 → RRF 融合（`RrfFusion.fuseAll`）→ 单一重排。关闭/LLM 失败/空输出回退单查询（现行为）；`fusion.mode=weighted` 时本增强不参与 |
| `rag.query.multi-query.variants` | Integer | 3 | COST | 变体数量，钳位 1~5（防 runaway 成本）；越界回落默认 |
| `rag.query.hyde.enabled` | Boolean | false | COST | HyDE 假设答案开关（方案15）。开启后生成假设答案→嵌入→纯向量召回路，与原查询路 RRF 融合。假设答案只用于检索向量，绝不进入生成上下文/SourceReference；关闭/失败/超时回退原查询路 |
| `rag.query.hyde.timeout-ms` | Long | 3000 | COST | HyDE 生成等待超时；超时跳过 HyDE 路 |
| `rag.query.derived-questions.enabled` | Boolean | false | COST | 衍生问题入库旁路开关（方案06）。开启后入库/重建成功后异步每块生成反向问题→嵌入→向量记录关联原块 chunkId（命中即回原块，`VectorRecord.text` 保持原块原文）。关闭=无衍生向量（回退现行为） |
| `rag.query.derived-questions.max-per-chunk` | Integer | 2 | COST | 每块反向问题上限，钳位 1~5 |

> 衍生问题旁路线程池不走 DynamicConfig，是 Spring 配置：`platform.async.derived-questions.queue-capacity`（默认 64）/`platform.async.derived-questions.await-termination-ms`（默认 10000）；队列饱和丢弃（discard-log）=该文档退化为无衍生向量，不阻塞入库主链。

## platform.resilience.* —— 依赖熔断治理（wire-circuit-dynamic-config / wire-circuit-per-dependency-override）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `platform.resilience.failure-threshold` | Integer | 5 | OPERATIONAL | 依赖熔断连续失败阈值：连续失败达到此次数后熔断器进入 OPEN 状态。范围 1~1000；非法/越界/缺失回落 5 |
| `platform.resilience.open-duration-ms` | Long | 30000 | OPERATIONAL | 依赖熔断开闸保持时长（毫秒）：开闸达到该时长后进入 HALF_OPEN 单探测状态。范围 0~86400000；非法/越界/缺失回落 30000 |
| `platform.resilience.failure-threshold.model-chat` | Integer | 5 | OPERATIONAL | 依赖 model-chat 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.model-chat` | Long | 30000 | OPERATIONAL | 依赖 model-chat 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.model-embed` | Integer | 5 | OPERATIONAL | 依赖 model-embed 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.model-embed` | Long | 30000 | OPERATIONAL | 依赖 model-embed 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.model-vision` | Integer | 5 | OPERATIONAL | 依赖 model-vision 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.model-vision` | Long | 30000 | OPERATIONAL | 依赖 model-vision 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.vector-qdrant` | Integer | 5 | OPERATIONAL | 依赖 vector-qdrant 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.vector-qdrant` | Long | 30000 | OPERATIONAL | 依赖 vector-qdrant 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |
| `platform.resilience.failure-threshold.storage-minio` | Integer | 5 | OPERATIONAL | 依赖 storage-minio 的熔断阈值覆盖。范围 1~1000；未配置/非法/越界时逐级回落全局键与默认 5 |
| `platform.resilience.open-duration-ms.storage-minio` | Long | 30000 | OPERATIONAL | 依赖 storage-minio 的熔断开闸时长（毫秒）覆盖。范围 0~86400000；未配置/非法/越界时逐级回落全局键与默认 30000 |

> 实时生效与回退：每次调用按依赖名实时读取——覆盖键 → 全局键 → 默认值，逐级回落（覆盖键越界回全局，全局也越界回默认 5/30000；拼错依赖名自然回落全局）。已写入 openUntilNanos 的在飞 OPEN 窗口不被追溯调整；键缺失、删除或写入非法值时 fail-safe 回落，绝不抛出配置异常打断业务调用。maxAttempts/backoff 不暴露（零自动重试）；走既有通用管理员动态配置权限。

## rag.ingest.* —— 摄取恢复重放（wire-ingestion-recovery-replay）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.ingest.replay-enabled` | Boolean | **false** | COST | 摄取恢复自动重放总开关：默认关闭（费用红线：重放=真实嵌入调用，必须显式开启）。开启时定时扫描 PENDING 状态文档以原上传者身份自动重放；关闭、缺失或读取异常时空转跳过 |
| `rag.ingest.replay-batch-size` | Integer | 5 | COST | 摄取恢复自动重放单批最大处理文档数，范围 1~50；非法、越界或缺失时 fail-safe 回落默认值 5 |

> 调度与身份：由 IngestionReplayScheduler 固定 tick 调度（默认 60s），以 uploaded_file.userId 加载真实用户构造 UserContext 调用既有 reingest 入口，走既有 canWrite 授权与平台治理审计；上传者不存在、离职、冻结或无权限时转 FAILED 终态并记录审计，熔断仍开快速拒绝时保持 PENDING 下轮再试。

## rag.generation.* —— 生成侧 Self-RAG 反思（add-self-rag-reflection）

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| `rag.generation.selfrag.mode` | String | `rule` | COST | Self-RAG 反思模式：`off`（关闭反思）\| `rule`（确定性规则反思，默认）\| `llm`（LLM 支持度自评——默认关闭，开启会产生模型调用费用，COST 档）。非法值回落默认 `rule` |
| `rag.generation.selfrag.llm.timeout-ms` | Long | 3000 | COST | Self-RAG LLM 自评等待超时（毫秒）：超时/失败/空输出/解析失败均回退规则反思链。消费点 <1 回落默认 3000。仅在 `selfrag.mode=llm` 时生效（COST 档） |
| `rag.generation.selfrag.llm.max-claims` | Integer | 20 | COST | Self-RAG LLM 自评单次断言/引用上限，防 prompt 膨胀。范围 1~100（默认 20） |

> 计量与回退：LLM 支持度自评调用 token 挂 `TokenUsageRecorder`（type=SUMMARY，复用语义最近枚举）。LLM 自评失败（超时/模型不可用/空输出/解析异常）无缝回退规则反思链，保障回答生成不中断。COST 档超管专写，支持通过成本键申请-审批流（`/platform/config/cost-requests`）按规范流程申请开启与调整。

## per-KB 覆盖（add-per-kb-retrieval-strategy-override）
> 知识库级检索策略覆盖：对单个知识库覆盖检索参数。恰 12 键白名单（v1 封闭集，扩充需 owner 拍板并同步本清单）；三层合并 = 覆盖值 > 全局动态配置 > 注册表默认；仅当授权收敛后 kbScope 恰为单库时应用覆盖（多库/全库/kbId 不可解析一律走全局）。覆盖值非法/越界 = WARN 审计 + 回落全局，打不断检索。写端点复用 KNOWLEDGE_ADMIN_MANAGE(900)：GET /knowledge/strategies（清单含来源 override/global/default）、PUT /knowledge/strategies/{key}（单键覆盖）、DELETE /knowledge/strategies/{key}（软删回落全局）、POST /knowledge/strategies/{key}/rollback（按版本回滚）。热失效：写后逐键失效 + 有界全量刷新兜底。
> 本表「权限档位」指该键作为全局动态配置键的档位；per-KB 覆盖写端点走 900（不走 608），白名单恰 12 键 ⊆ OPERATIONAL。

| 键 | 类型 | 默认值 | 权限档位 | 语义与回退 |
|---|---|---|---|---|
| rag.retrieval.topK | Integer | 5 | OPERATIONAL | 单库覆盖返回候选片段数（1~100，越界回落全局） |
| rag.retrieval.minScore | Double | 0.20 | OPERATIONAL | 单库检索相关度阈值（0~1，越界回落全局） |
| rag.retrieval.fusion.mode | String | rrf | OPERATIONAL | 单库融合模式（rrf/weighted） |
| rag.retrieval.fusion.rrf-k | Integer | 60 | OPERATIONAL | 单库 RRF 常数 k |
| rag.retrieval.rerank.vector-weight | Double | 0.60 | OPERATIONAL | 单库重排向量权重（0~1） |
| rag.retrieval.rerank.bm25-weight | Double | 0.40 | OPERATIONAL | 单库重排 BM25 权重（0~1） |
| rag.retrieval.rerank.candidate-multiplier | Integer | 4 | OPERATIONAL | 单库候选倍数 |
| rag.retrieval.image-text-route-weight | Double | 0.70 | OPERATIONAL | 单库图文路由文本路权重（0~1） |
| rag.retrieval.image-vector-route-weight | Double | 0.30 | OPERATIONAL | 单库图文路由图片路权重（0~1） |
| rag.context.neighbors | Integer | 1 | OPERATIONAL | 单库邻居增强开关（0/1） |
| rag.context.parent-expand | String | on | OPERATIONAL | 单库父块展开开关（on/off） |
| rag.retrieval.query-rewrite.enabled | Boolean | true | OPERATIONAL | 单库查询改写开关 |

> 成本类（rerank.mode=llm、compressor.mode=llm、multi-query.*、hyde.*、derived-questions.*、vision-pdf.*、image-slice-ingest.*、replay-*、selfrag.*）与结构类（chunking.*、chunkSize/chunkOverlap）永远不得进入 per-KB 覆盖——封闭集白名单外键写入被拒。

## 键级权限分层（tier ACL）（add-dynamic-config-key-tier-acl）

> 配置中心 7 端点（list / detail / history / update / rollback / delete / cache-refresh）挂方法级
> `@RequirePermission(PLATFORM_DYNAMIC_CONFIG_MANAGE)`（608「平台动态配置管理」，V30 `__dynamic_config_permission_seed`
> 注册并授权全部业务角色，超管经拦截器直通）。服务层写路径 `DynamicConfigAccessGuards.requireKeyWriteAccess`
> 按键档位闸门：OPERATIONAL 档键对 608 持有者放开，COST / STRUCTURAL 档键仍超管专写（FORBIDDEN 96005）；
> 读端点与 cache/refresh 由 608 方法级注解承接鉴权。定级封闭表 = `ConfigKeyTierPolicy`（68 键与注册表一一对应，
> census 防呆测试 `ConfigKeyTierPolicyTest` 锁死全集），改档需 owner 拍板并同步本节与各表「权限档位」列。

- **OPERATIONAL（运营调参档，36 键，608 持有者可写）**：检索参数与融合/重排权重、引用对齐阈值、图文路由、图像同图判别阈值、邻居与父块、意图类目、提示词与采样参数、业务功能开关、熔断治理参数等——热调无边际成本、可随时回落。
- **COST（成本开关档，27 键，超管专写）**：LLM 重排/压缩/Self-RAG自评、查询增强（多查询/HyDE/衍生问题）、VLM 视觉转写与 PDF 内嵌图片描述、摄取恢复重放、管理端真向量、限流配额、token 预算等费用红线族——开关拨动直接产生真实模型调用费用。
- **STRUCTURAL（变更管理档，5 键，超管专写）**：切分策略与分块尺寸、数据范围安全语义等——热改会造成新旧数据切片/嵌入标准不一致，属变更管理而非运行时开关。
- **摘除记录（wire-built-unfinished-infra，2026-10-17）**：`ai.model.provider/chatModel/visionModel/embeddingModel/temperature/maxTokens` 六键全仓零消费，从注册表与三档封闭集同步摘除；模型选择只走静态绑定 `platform.ai.model.*`（`ModelProviderProperties`），「ai.model」命名空间保留白名单。

**COST 27 键全清单**：`business.rateLimit.perMinute`、`business.quota.maxTokensPerSession`、`rag.retrieval.admin-vector.enabled`、`rag.retrieval.vision-pdf.enabled`、`rag.retrieval.vision-pdf.min-text-chars`、`rag.retrieval.vision-pdf.max-pages`、`rag.retrieval.image-slice-ingest.enabled`、`rag.retrieval.image-slice-ingest.kb-allowlist`、`rag.retrieval.image-slice-ingest.max-images-per-document`、`rag.retrieval.image-vector-ingest.enabled`、`rag.retrieval.rerank.mode`、`rag.retrieval.rerank.llm.timeout-ms`、`rag.retrieval.rerank.llm.max-candidates`、`rag.context.token-budget`、`rag.context.compressor.mode`、`rag.context.compressor.llm.timeout-ms`、`rag.query.multi-query.enabled`、`rag.query.multi-query.variants`、`rag.query.hyde.enabled`、`rag.query.hyde.timeout-ms`、`rag.query.derived-questions.enabled`、`rag.query.derived-questions.max-per-chunk`、`rag.ingest.replay-enabled`、`rag.ingest.replay-batch-size`、`rag.generation.selfrag.mode`、`rag.generation.selfrag.llm.timeout-ms`、`rag.generation.selfrag.llm.max-claims`。

**STRUCTURAL 5 键全清单**：`rag.retrieval.chunkSize`、`rag.retrieval.chunkOverlap`、`rag.chunking.strategy`、`rag.chunking.max-chunk-size`、`business.dataScope.enabled`。

**OPERATIONAL 36 键全清单**：`ai.prompt.system`、`rag.retrieval.topK`、`rag.retrieval.minScore`、`rag.retrieval.citation.keepMin`、`rag.retrieval.citation.remapMin`、`rag.retrieval.citation.tieMargin`、`rag.retrieval.strictKb`、`rag.retrieval.query-rewrite.enabled`、`rag.retrieval.fusion.mode`、`rag.retrieval.fusion.rrf-k`、`rag.retrieval.rerank.vector-weight`、`rag.retrieval.rerank.bm25-weight`、`rag.retrieval.rerank.candidate-multiplier`、`rag.retrieval.image-text-route-weight`、`rag.retrieval.image-vector-route-weight`、`rag.retrieval.image-similarity-threshold`、`rag.context.neighbors`、`rag.context.parent-expand`、`rag.intent.filterEnabled`、`rag.intent.categories`、`rag.intent.keywords`、`business.assist.reminderEnabled`、`business.feature.aiAssistantEnabled`、`business.assistant.imageCacheMaxEntries`、`platform.resilience.failure-threshold`、`platform.resilience.open-duration-ms`、`platform.resilience.failure-threshold.model-chat`、`platform.resilience.open-duration-ms.model-chat`、`platform.resilience.failure-threshold.model-embed`、`platform.resilience.open-duration-ms.model-embed`、`platform.resilience.failure-threshold.model-vision`、`platform.resilience.open-duration-ms.model-vision`、`platform.resilience.failure-threshold.vector-qdrant`、`platform.resilience.open-duration-ms.vector-qdrant`、`platform.resilience.failure-threshold.storage-minio`、`platform.resilience.open-duration-ms.storage-minio`。

**新增键定级维护约定**：新键注册进 `DynamicConfigKeyRegistry` 时必须同步在 `ConfigKeyTierPolicy` 显式登记档位——未登记键 census 防呆测试即红（解析顺序：sensitive=true 防御性映射 COST → 显式登记表 → 默认 OPERATIONAL，但封闭集校验要求登记齐全）。档位升降（尤其 OPERATIONAL ↔ COST/STRUCTURAL）需 owner 拍板，同步改 `ConfigKeyTierPolicy`、`ConfigKeyTierPolicyTest` 与本清单各表「权限档位」列及本节。

### 成本键申请-审批流（add-cost-key-approval-workflow，卡 P-ae）

为解决运营侧热调 COST 档键（费用红线族，超管专写）零申请留痕、零审批链的问题，系统化落地申请-审批流：

1. **端点与权限**：5 个 REST 端点（`/platform/config/cost-requests` 系列）全部挂载方法级 `@RequirePermission(PLATFORM_DYNAMIC_CONFIG_MANAGE(608))`：
   - `POST /platform/config/cost-requests`：提交申请（608 持有者；值预校验同写路径但未通过审批前不写入）；
   - `GET /platform/config/cost-requests`：清单分页查询（608 持有者；超管看全部、普通业务用户只看本人申请单，实时角色判定）；
   - `POST /platform/config/cost-requests/{id}/withdraw`：撤回申请（仅申请人本人且处于 PENDING 态）；
   - `POST /platform/config/cost-requests/{id}/approve`：审批通过（服务层超管闸 `roleId=1`，非超管 96005）；
   - `POST /platform/config/cost-requests/{id}/reject`：驳回申请（服务层超管闸 `roleId=1`，非超管 96005；需记录驳回理由）。
2. **四态封闭状态机**：
   - 转移路径：`PENDING → APPROVED | REJECTED | WITHDRAWN`；
   - 终态不可逆：一旦进入 `APPROVED`、`REJECTED` 或 `WITHDRAWN`，不可再次转移；
   - 无过期（EXPIRED）态：审批通过即写入生效。
3. **COST 白名单封闭**：
   - 严格引用 `ConfigKeyTierPolicy.costKeys()`（25 键封闭集），零新增白名单常量；
   - 白名单外键（OPERATIONAL 34 / STRUCTURAL 7 / 未注册未知键）提交申请一律拒绝（96007 语义）。
4. **一键一单在途约束**：
   - 同一 `config_key` 在存在 `PENDING` 态申请单时，禁止提交新申请单，避免同键并发审批歧义；终态完结后可再次申请。
5. **通过即写入原子语义与审计锚**：
   - 审批通过时，以**审批人（超管）身份**直接调用既有 `DynamicConfigAdminService.updateValue` 写路径（过写路径键级 ACL、值校验、乐观锁版本+1、history 留痕与审计事件、缓存热失效）；
   - 若写路径发生异常，申请单**保持 PENDING 状态**，绝不落 APPROVED 半状态，异常向上抛出审批人可见可重试；
   - 写入成功后回填 `applied_config_version`（配置写入后的新版本号），与 `dynamic_config_history` 形成审计闭环关联。
