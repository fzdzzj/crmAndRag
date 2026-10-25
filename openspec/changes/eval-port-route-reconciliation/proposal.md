# proposal · eval-port-route-reconciliation（72 例基准套件的检索端口到底是不是「生产路」）

> 卡 29。**零外呼 / 零迁移 / 零契约 / 零权限 / `src/main` 零 diff**。只做口径判定，不改任何评测断言、不改基线、不产出新阈值。
> 本卡回答储备 3 的前置问题：**「文本路真分数分布」能不能从这个 72 例套件里取？**

## §1 问题陈述（为什么必须先判口径）

储备 3 的原话是「文本路真分数分布：口径四只给 7 条查询的**存在性证据**；要分布须先验 72 例基准套件的检索端口是否生产路（`SparseBenchmarkRecallService` 的存在使其可疑，未验）」。

这个「先验」不做，后面任何「分布」都是悬空的：**如果分数不是生产口径，算出的分布再精细也只是一条曲线的照片**。而卡 27 已经吃过一次「分母口径不对、结论全废」的教训，卡 28 又吃过一次「判定标签恒真」的教训。

## §2 侦察已得事实（行号实测于 `d812ed1`，执行者只需复核、不必重查）

- **装配面是生产类**：`RagBenchmarkPipelineFactory.build(...)` 造的是**真的** `KnowledgeRetrievalServiceImpl`（`:50` / `:65`），重排用 `new DefaultWeightedReranker(new Bm25Scorer(), providerCfg)`（`:54`），融合用 `new RrfFusion()`（`:55`），邻居用 `InMemoryDocumentVectorChunkMapper.createFromChunks(chunks)`（`:60`）。配置矩阵传**空 Map** ⇒ 全部回落生产默认键（`fusion=rrf`、`topK=5`、`minScore=0.20`、`rrf-k=60`、`rerank=default`，见 `RagQualityRegressionTest:273-275` 注释）。
- **输入面不是生产数据**：向量 = `DeterministicEmbedding::vectorFor` **伪向量**（`RagQualityRegressionTest:283`），模型 = `DeterministicModelProvider`（`:278`），存储 = `InMemoryVectorStore`（`:281`）。
- **稀疏腿是测试替身**：`SparseRecallService sparse = new SparseBenchmarkRecallService(chunks)`（`:56`）；该子类 override `recall`，用**字符 bigram 重叠**近似 DB 的 `FULLTEXT(ngram)`，且**无授权 JOIN、无软删**（`SparseBenchmarkRecallService:14-22` 自述「本类只在基准测试用」）。
- **度量面是集合内相对量**：`DefaultWeightedReranker:12` 自述「向量分与 BM25 分各自 min-max」；`:36-42` 实证两次 `normalizeScores(...)`。
- **该套件当前断言的只是**：`recall@5 ≥ 基线×0.95`、`mrr ≥ 基线×0.95`（`:56`/`:141`）、`failureRate == 0`（`:114`）、以及确定性/非空壳守卫（`:147-165`）。

**⇒ 待判定的是三条腿各自的口径，而不是一个「是/否」**：装配腿 = 生产；输入腿 = 伪向量；稀疏腿 = 近似；度量腿 = 集合内归一。**一个「是」或「不是」都会说错话。**

## §3 判据（R0–R6，执行与复核均以此为准）

- **R0 零外呼 + `src/main` 零 diff**：只跑现有 surefire 装配与纯 JVM 直构；**禁**读 key、**禁**构造真实 provider、**禁**跑任何 `*REAL*` opt-in。
- **R1 逐腿对账（四条腿 + 两个面）**：对 `装配 / 输入 / 稀疏 / 度量` 四条腿与 `语料 / 归一化` 两个面逐条落 `(腿名, 生产件, 测试替身或近似, 源码文件:行号, 受影响的可支持结论)`。**禁**用「基本等价」「大致相同」这类词；每条必须是可 grep 的类名/行号。
- **R2 平移不变性实验（本卡命门）**：把同一查询的候选向量分**整体平移**（`+100`）或**整体放大**（`×2`）后重跑**生产** `DefaultWeightedReranker`，断言 `rerankScore` **逐位不变**（min-max 的必然结果）。**此实验不成立 ⇒ 重排分含绝对信息，本卡必须改判**；成立即证明：该分数是**集合内相对量**，跨查询/跨语料比较无意义。
- **R3 稀疏腿近似不得被当成等价**：落 `bigram 重叠` vs `FULLTEXT(ngram)` 的差异声明，并明确指出**哪些结论会被它污染**（`recall@5`/`mrr` 的绝对值、稀疏命中与否）；锚内**不得**出现「稀疏路等价生产」的表述。
- **R4 verdict 三选一（只许这三个字符串）**：`PRODUCTION-PATH` / `PARTIAL-OFF-PRODUCTION`（须逐腿指出偏差点）/ `INCONCLUSIVE`（须附缺哪条证据）。**禁**自创第四种，**禁**夹带阈值主张与修复建议。
- **R5 结论清单（可支持 / 不可支持）**：逐条列出本套件的分数**能**支持什么（如「同一装配下的回归位移」「同一查询内候选间排序」）、**不能**支持什么（如「生产环境的文本路分数分布」「跨语料分位」）。每条附 R1/R2 的对应证据编号。
- **R6 只判不改**：既有 `RagQualityRegressionTest` 断言、`scripts/test-baseline.txt`、`docs/rag-quality/baseline-v*.json` **零字节变动**；本卡不生成新基线、不改套件用例数。

## §4 交付物

- 纯 JVM 复核测试（会调生产 `DefaultWeightedReranker`）：`src/test/java/com/slz/crm/quality/EvalPortRouteReconciliationTest.java`
- 结论锚：`docs/rag-quality/eval-port-route-v1.json`（**LF**，写模式 `-D` 开关，默认逐字节复算）+ `.md`（≤40 行）
- 守卫：`src/test/java/com/slz/crm/quality/EvalPortRouteGuardTest.java`（verdict 三选一 + 逐腿表与源码行号同源 + 平移不变性结论位）

## §5 成本

**0 次真实外呼**；0 迁移、0 契约、0 权限、0 新依赖；`src/main` 零 diff；surefire 期望 ≤30 s；failsafe 不新增用例。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1** 复用现有 `RagQualityRegressionTest.measure()` 的装配读法（只读、不调用其私有方法），不复制其断言。
- **D2** 平移不变性实验**直构** `DefaultWeightedReranker`（构造器可见性若不允许，改用其同包测试或经 `KnowledgeRetrievalServiceImpl` 公共入口，**不得**复制 min-max 逻辑自造等价物）。
- **D3** 本卡**不**执行储备 3 的「分布」测量；若 R4 判 `PARTIAL-OFF-PRODUCTION`，则储备 3 的处置（改写口径 / 放弃 / 另立真跑卡）由 Owner 裁定，本卡只给证据与可选口径。

## §7 与储备 3 的关系（写给下一位读者）

- 本卡若判**分数不能当分布**，则储备 3 的正确写法只剩两条：① 「**同一查询内**候选间分差的分布」（相对量之间可比），或 ② 在生产库上真跑（需数据库 + 外呼授权，另卡）。
- 本卡**不**替 Owner 做这个选择，只把「从 72 例套件直接取分布」这条路**关掉或放行**，并写清理由。
