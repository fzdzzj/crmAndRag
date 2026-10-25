# eval-port-route v1 · 检索端口口径对账结论（卡 29，零外呼）

- verdict: PARTIAL-OFF-PRODUCTION（非 PRODUCTION 腿: 输入腿=STUB、稀疏腿=APPROXIMATION、度量腿=SET-RELATIVE、语料面=TEST-FIXTURE、归一化面=SET-RELATIVE）
- 装配腿: PRODUCTION —— 生产 KnowledgeRetrievalServiceImpl/DefaultWeightedReranker/RrfFusion 直构（RagBenchmarkPipelineFactory.java:53-75）
- 输入腿: STUB —— DeterministicEmbedding 伪向量 + DeterministicModelProvider + InMemoryVectorStore（RagQualityRegressionTest.java:278-283）
- 稀疏腿: APPROXIMATION —— 字符 bigram 重叠近似 FULLTEXT(ngram)，无授权 JOIN/无软删（SparseBenchmarkRecallService.java:33-63）
- 度量腿: SET-RELATIVE —— 向量/BM25 各自 min-max 集合内归一（DefaultWeightedReranker.java:41-42,58-74）
- 平移实验: 同候选集三遍（原始/+100/×2）rerankScore 实测最大绝对差 2.8310687127941492E-14 / 0.0（逐位相等=false/true）
- 单点抬高对照: 只抬高一个候选，名次 1→0 必升 ⇒ 测的是归一化不是恒等函数
- 结论: SET-RELATIVE-INVARIANT —— 本套件分数不得当「文本路真分数分布」用（储备 3 需改口径或另立真跑卡，由 Owner 裁定）
- 被污染结论: recall@5/mrr 绝对值、跨语料分位、生产文本路分数分布、稀疏路命中绝对值、伪向量余弦分布、生产语料分布
- 细节锚: docs/rag-quality/eval-port-route-v1.json（逐字节复算；行号实测于 d812ed1）
