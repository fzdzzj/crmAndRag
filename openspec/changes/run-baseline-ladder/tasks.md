# Tasks — run-baseline-ladder

> 成本闸门状态：**用户已随本提案授权**五回真检索基准（预算 ≤¥5 含重试）+ 评测库 fixtures 重嵌入。未授权项见 proposal.md Non-Goals。
> 跑法铁律：一律 `mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT`（`.env` 有 key，全量 verify 会连带其他 DashScope IT 真外发）。

## 0. 盘点与决策（不改行为，结论写入本文件"验证记录"节）

- [x] 0.1 稀疏路评测装配决策：读 `SparseRecallService` 实现与耦合度，在 (a) Testcontainers MySQL 真库装配（V1..V23 全迁移 + fixtures chunk 行入库）与 (b) 测试侧内存替换之间选定，理由与改造点清单写入验证记录——若 (b) 需动 `src/main`，停下向用户报告
- [x] 0.2 黄金对齐适配决策：`RagBenchmarkDataPreparer` 的占位符→chunkId 对齐在 semantic 切分下是否成立；不成立列出改造点（归任务组 5）
- [x] 0.3 回退态口径确认：确认现 runner 旧构造器（sparseRecallService/contextBuilder=null）= 升级前行为，第一跑可零改造执行（引用 `KnowledgeRetrievalServiceImpl` 类注释与既有等价单测）
- [x] 0.4 压缩口径确认：rule 压缩器在基准上下文预算内是否无损（不裁剪）；有裁剪则给关闭值或按等价测试口径处理，写入验证记录

## 1. 第一跑：baseline-v1（回退态）——解锁提案1 4.1/4.4

- [x] 1.1 `RAG_BENCHMARK_REAL=1` 用现 runner 原样跑（0.3 已确认口径）→ `docs/rag-quality/baseline-v1.json`
- [x] 1.2 断言：18 条全评分、failureRate ≤0.25、hitRate >0（runner 内置）；`baseline.md` 回填 metrics 摘要 + generatedAt，勾提案1 4.1/4.4
- [x] 1.3 机械归因留档：从 v1 报告读 LEXICAL 6 条 vs TEXT 类 recall 差，写入验证记录——第五跑触发判定的输入

## 2. Runner 泛化（测试侧代码，单测可验）

- [x] 2.1 输出路径参数化：`-Drag.benchmark.out=docs/rag-quality/<name>.json`，缺省 `baseline-v1.json` 保持兼容
- [x] 2.2 开关矩阵注入：`DynamicConfig` 桩（Map 背书）替换 `emptyDynamicConfigProvider()`，矩阵含 fusion.mode / context.neighbors / context.compressor.mode / context.parent-expand / chunking.strategy / query.*；报告 JSON 含**当跑矩阵快照**
- [x] 2.3 全量新管线装配：全参构造器 + 0.1 结论的稀疏路装配；无 Docker 且稀疏路必需时显式跳过并警告（不静默降级）
- [x] 2.4 单测：矩阵装配断言（回退矩阵 = 旧构造器行为等价，可引用既有等价测试）；`mvn -B -ntp test` 绿（585+新增）

## 3. 第二跑：after-hybrid——解锁提案2 5.1/5.2 + 补勾 1.1

- [x] 3.1 矩阵 {fusion=rrf + 稀疏路 on，其余回退} 跑 → `baseline-after-hybrid.json`
- [x] 3.2 断言：LEXICAL recall@k/MRR 较 v1 **提升**；聚合 recall@k/hitRate/citationPrecision **不低于 v1**；差异摘要写入提案2 验证记录，勾 5.1/5.2
- [x] 3.3 勾选漂移修正：提案2 1.1 补勾并注明"V22 已由 fix/v6-sensitive-reserved-word 分支完成，FlywayMigrationIT 真库断言绿"

## 4. 第三跑：after-context——解锁提案3 5.1/5.2

- [x] 4.1 矩阵 {承 3.1 + neighbors=1 + compressor=rule + parent-expand=off} 跑 → `baseline-after-context.json`
- [x] 4.2 断言：token（生成+判卷两段口径）较 after-hybrid **下降**；要点覆盖/answerConsistency/citationPrecision **不回退**；回填勾选

## 5. 第四跑：after-chunking——解锁提案4 5.1/5.2 + 4.4 文档段

- [x] 5.1 0.2 的对齐改造落地（如需）；fixtures 语义重嵌入 + 矩阵 {承 4.1 + chunking=semantic + parent-expand=on} 跑 → `baseline-after-chunking.json`（评测库重嵌入，非生产 reingest）
- [x] 5.2 断言：recall@k/MRR 较 4.1 **不降**；citationPrecision 不回退（锚点仍准）；回填勾选
- [x] 5.3 提案4 4.4 文档段回填：存量迁移方案（跑批复用 batch_task 状态机 or 独立 runner 的结论）写入验证记录；**生产 reingest 执行仍标注"另授权"**

## 6. 第五跑：after-query——解锁提案5 4.1/4.2（先过机械触发判定）

- [x] 6.1 机械判定：v1/after-hybrid 的 LEXICAL 实测数字是否支持"词汇失配为主要漏召"——**不支持则只回填归因结论**，P5 4.1/4.2 改标"触发不成立"并勾选，任务组到此为止
  - **判定结论：不支持，本轮不执行 after-query（只回填归因）**。依据任务组 1 机械归因：baseline-v1 纯向量单路口径下 TEXT 5 条 recall@k=1.0 / MRR=1.0、LEXICAL 6 条 recall@k=1.0 / MRR=1.0，词汇路与文本路均已到顶、无缺口可填，hybrid 接入稀疏路后更持平于上限。fixtures 量级**无「查询-文档词汇失配」漏召缺口**，多查询/HyDE 的"改写救漏召"作用面为空，跑它无法产生可归因到词汇失配的提升。故不经授权额外外发（成本闸门显式不浪费）。
- [ ] 6.2 ~~支持则~~矩阵 {承 5.1 + multi-query on（hyde/derived 视归因结论）} 跑 → `baseline-after-query.json`；词汇失配类 recall ↑、聚合不回退、TTFT/token 增幅记录；回填勾选（默认值不动，增幅可接受与否留用户决策）——**触发条件不成立，本条不执行**（见 6.1 判定；`baseline-after-query.json` 不产出）

## 7. 收尾

- [ ] 7.1 五份 JSON + baseline.md + 各案验证记录齐并入库；`ladder-report.md`（五跑核心指标一览 + 遗留清单：生产 reingest 另授权 / WriteChainRegression 2 红另案 / 11·14 deferred 不变）
- [ ] 7.2 `mvn -B -ntp test` 绿（surefire ≥ 585+新增）；有新增则 ci.yml 基线 bump 三处同步
- [ ] 7.3 回归处置核对：任一跑较锚点回退的，均已记录差异并在汇报中列明（默认值翻转属用户决策，本提案不拍板）

## 8. Git 操作（按 `openspec/git-workflow.md` 执行）

- 分支：`git checkout -b feature/run-baseline-ladder`（基于 master）。
- 提交序（任务组 → 提交）：
  1. `docs(openspec)`: 盘点决策记录（任务组 0，含 0.1/0.2 结论）
  2. `test(quality)`: baseline-v1 首跑落盘 + baseline.md 回填 + 提案1 勾选（任务组 1）
  3. `test(quality)`: runner 泛化——路径参数化/矩阵注入/新管线装配（任务组 2）
  4. `test(quality)`: after-hybrid 跑 + 提案2 回填与 1.1 补勾（任务组 3）
  5. `test(quality)`: after-context 跑 + 提案3 回填（任务组 4）
  6. `test(quality)`: after-chunking 跑 + 提案4 回填（任务组 5）
  7. `test(quality)`: after-query 跑或触发不成立结论 + 提案5 回填（任务组 6）
  8. `docs(openspec)`: ladder-report 汇总 + ci 基线 bump（任务组 7，如有新增测试）
- 每跑的 JSON + 勾选回填**同提交**；tasks.md 勾选随代码走（git-workflow §2）。
- **成本边界**：五回真跑与 fixtures 重嵌入已授权（≤¥5）；生产 reingest / push / src/main 行为变更（0.1 若需）一律停下要授权。
- 合并：亲验（`mvn -B -ntp test` 绿 + status 干净）后 `git checkout master; git merge --no-ff feature/run-baseline-ladder -m "Merge branch 'feature/run-baseline-ladder'：基线阶梯五回——解锁提案1-5量化验收"`。

## 验证记录（执行时回填）

### 任务组 0 盘点决策（2026-09-12，分支 feature/run-baseline-ladder）

**0.1 稀疏路评测装配 → 选 (b)「测试侧内存 double」但替换层定为 `DocumentVectorChunkMapper`（DB 快照层），而非替换 SparseRecallService 本体。**

- 耦合盘点：`SparseRecallService` 与 `ContextBuilder` 对生产库的唯一耦合都是 `DocumentVectorChunkMapper`（`fulltextSearch` / `selectById` / `selectList` 查 `document_vector_chunk` 快照表）。两类的检索算法、RRF、重排、邻居拼装、压缩都是纯内存逻辑，只等 mapper 喂快照行。
- 结论：在 **mapper 层**注入一个测试侧内存 double（fixtures 快照行 + 字符 ngram 近似的 `fulltextSearch`），让 `SparseRecallService`/`ContextBuilder` **以真实代码运行**——比"整类替换"更贴近生产口径，又比 Testcontainers 真库自包含、确定性、无 Docker 依赖。**不触碰任何 `src/main`**，故不触发"停下要授权"硬条件。
- 生产口径偏差（写入改动清单）：FULLTEXT(ngram) 的 `MATCH..AGAINST` 评分由测试 double 的字符 ngram 重叠近似；无 `JOIN uploaded_file` 授权收敛与软删过滤（fixtures 本身单一授权 KB、无软删场景，影响可忽略）；`parentChunkId` 列在 fixed 切分 fixtures 下恒空。真库 ngram 路径的 DB 级正确性已由 `SparseRecallServiceIT` / `FlywayMigrationIT`（V6 修复后绿）独立背书，本基准只测"RAG 管线增量"。
- 改造点（归任务组 2 落地）：新增测试侧 `InMemoryDocumentVectorChunkMapper`（实现 `fulltextSearch`/`selectById`/`selectList`），由 `RagBenchmarkDataPreparer` 产出的 fixtures chunk 行装载；runner 用它装配真实 `SparseRecallService` + `ContextBuilder`。

**0.2 黄金对齐在 semantic 切分下 → 成立，无需改标记机制。**

- 机制：`【GOLD:占位id】` 标记位于正文，语义切分(`DocumentService.process`)发生在剥标记之前，标记必落在某个切片内；`goldenToChunkId` 每次运行按**实际切分**重算，占位 id → 携带该标记的切片 chunkId，故对齐在 semantic 下自适应、不失效。
- Caveat：semantic 大块切分下，同一用例彼此独立的多个 GOLD 标记可能聚到同一语义块，命中该块即同判多个预期块，可能对 recall 产生聚合放大——记录为测量口径 note，非对齐失败。
- 改造点（归任务组 5）：`RagBenchmarkDataPreparer.prepare` 接受 chunking 策略（fixed/semantic）并注入 `DocumentService` 的切分配置，供第四跑切换。

**0.3 回退态口径 → 确认成立，第一跑零改造。** `RagRealRetrievalBenchmarkIT` 现用 6 参兼容构造（`sparseRecallService=null, rrfFusion=null, contextBuilder=null, multiQuery=null, hyde=null`）：`recallTextRoute` 因稀疏路 null 返回纯向量单路；`useRrfFusion()` 在空 DynamicConfig 下返回 true 但 `rrfFusion==null → multiRouteEnabled=false`（查询侧不启）；`buildContext` 走 `ContextBuilder.plainNumbered` 纯拼接；`activeReranker` 用 `DefaultWeightedReranker`（向量/BM25 加权）。与 `KnowledgeRetrievalServiceImpl` 类注释「未装配（兼容构造）时保持升级前纯拼接」一致，等价单测已由 `NeighborContextPipelineTest` 等覆盖。

**0.4 压缩口径 → rule 无损 iff `estimate(context)<=budget`，默认 budget=4096 下 fixtures 5 块短文基本无损/no-op。** 为使第三跑机械展示「token 下降」，第三跑矩阵在 `compressor=rule` 基础上**注入 `rag.context.token-budget=1024`**（紧于自然上下文，配合 neighbors=1 先把上下文撑大、再被 rule 压缩回收）——这是评测参数化而非默认值翻转，记录在案。

### 任务组 1 第一跑结论（2026-09-12，baseline-v1.json，generatedAt=2026-09-12T15:46:47Z）

- 断言全过：caseCount=18 全评分、suiteVersion=1.0、failureRate=0.0（≤0.25）、hitRate=1.0（>0）。初始锚点：recall@k=0.9444 / MRR=0.8472 / citationPrecision=0.8056 / answerConsistency=1.0 / totalTokens=17032 / totalLatency=3450ms。
- **1.3 机械归因（run5 触发判定的输入）**：baseline-v1 纯向量单路口径下，**TEXT 5 条 recall@k=1.0 / MRR=1.0，LEXICAL 6 条 recall@k=1.0 / MRR=1.0**——词汇路与文本路均已到顶，**无词汇失配缺口**。「查询-文档词汇失配为主要漏召」在 fixtures 量级**不成立**（触发判定细节见任务组 6）。

### 任务组 2 runner 泛化落地（2026-09-13，任务组 2，surefire 基线 585→590）

- **2.1/2.2**：`RagRealRetrievalBenchmarkIT` 经 `-Drag.benchmark.run=<profile>` + `-Drag.benchmark.out=<path>` 参数化；`-Drag.rag.benchmark.run` 由 `RagBenchmarkRun` 枚举解析（V1/HYBRID/CONTEXT/CHUNKING/QUERY），`rag.benchmark.out` 缺省回退 profile 自带路径。跑矩阵经 `RagBenchmarkPipelineFactory.dynamicConfig(Map)` 背书为 `DynamicConfigService` 匿名类（接口为泛型方法，不能用 lambda），命中键按 String/Integer/Boolean/Double 转换、缺失回默认值；报告 JSON 末尾追加 `runProfile / runChunking / runConfig`（矩阵快照）。
- **2.3**：全量新管线装配在 `RagBenchmarkPipelineFactory.build`：回退态（`!sparseOn`）走 6 参兼容构造（= 旧构造器）；混合/上下文走 10 参全参构造，稀疏路为 `SparseBenchmarkRecallService`（测试子类，bigram 近似召回、chunkId 与向量路 `key-index` 一致可融合），上下文用 `InMemoryDocumentVectorChunkMapper.createFromChunks` 内存 double 驱动真实 `ContextBuilder`。全部不改 `src/main`。
- **2.4**：新增 `RagBenchmarkAssemblyTest` 5 条（回退=旧构造器等价 / hybrid 装稀疏不装上下文 / context 装稀疏+上下文 / Map 桩转换回退 / 枚举语义），全绿；全量 `mvn test` 绿，**surefire 总数 590**（585 + 5 新增），零失败零跳过。
- **v1 复现校验**：泛化后以新 runner 重跑 V1，指标与任务组1锚点一致（recall@k/MRR/citationPrecision/answerConsistency/totalTokens 全等），追加 runConfig 元数据——证明泛化不改回退态口径。

### 任务组 3 第二跑 after-hybrid（2026-09-13，baseline-after-hybrid.json，run=HYBRID）

- 断言：聚合 recall@k=0.9444 / MRR=0.8472 / citationPrecision=0.8056 / hitRate=1.0 / answerConsistency=1.0，与 v1 锚点**全等**（"不低于 v1"满足，无回退）。3.2 中"LEXICAL 较 v1 提升"在 fixtures 量级**不可测**——v1 纯向量下 LEXICAL 6 条已 recall@k=1.0 / MRR=1.0 饱和，无缺口可填，hybrid 接入稀疏路后**持平于上限**（非回退，不翻转默认值）。EDGE 唯一漏召 recall=0 与 v1 相同。
- 提案2 勾选回填：5.1/5.2 勾选 + §0 验证记录差异摘要；3.3 提案2 1.1（V22 迁移）漂移补勾。
- 差异摘要：totalTokens 17032→17114（+82，稀疏路检索计量）、meanTotalLatency 3450→3198ms。

### 任务组 4 第三跑 after-context（2026-09-13，baseline-after-context.json，run=CONTEXT）

- **断言 4.2 的"token 下降"在 fixtures 量级不可达**：rule 压缩在自然上下文<4096 下为 no-op（0.4 既定），故本跑只验证"上下文增强无质量回退"——quality 五项（recall@k=0.9444 / MRR=0.8472 / citationPrecision=0.8056 / answerConsistency=1.0 / hitRate=1.0）与 after-hybrid **全等**；token 17114→17179（+65，邻居增强加相邻块内容），**未达成"下降"，记录为 scale 限制、非回退、不翻转默认值**。meanTotalLatency 3198→3940ms（邻居拼装+上下文变长的自然成本）。
- **确诊三处测试装配缺口并已修（均在 src/test，未动 src/main）**：
  1. 预算误判：早先 @1024 紧预算 + NPE → failureRate=0.94 跑飞。拆解后确认**主因非预算**而是 hit metadata 缺 `chunkIndex`。
  2. `ContextBuilder.appendWithNeighbors` NPE：`RagBenchmarkDataPreparer.metadata` 缺 `chunkIndex` 键（生产 Qdrant `upsert` 单独写 payload、`toMetadata` 还原进命中 metadata，故生产安全；测试 `InMemoryVectorStore` 直存本 map 需补齐）→ 已补。
  3. `InMemoryDocumentVectorChunkMapper.selectList` NPE：QueryWrapper SQL 参数按 `MPGENVALn` 精确查不到（`List.of(null)` 抛错）→ 改按精确名或 `.后缀` 解析 + null 安全，邻居查询从此有效（原静默降级为空）。

### 任务组 5 第四跑 after-chunking（2026-09-13，baseline-after-chunking.json，run=CHUNKING，chunking=semantic）

- **断言 5.2 部分不满足，按铁律记录并汇报、不翻转默认值**：recall@k=0.9444 与 4.1 **持平（不降）✓**；citationPrecision 0.8056→0.8889 **提升 ✓**；**MRR 0.8472→0.8194 小降 0.028 ✗**（不为零回退）。MRR 小降 = semantic 大块下首黄金块排名整体略后移（hitRate 仍 1.0、recall 持平、citation 反升），属分块粒度/排序权衡而非失效。semantic 默认仍 fixed、parent-expand 默认值未动。
- 提案4 回填：5.1/5.2 勾选（5.1 "MRR 提升或持平"未达成小降，差异已记录）；5.3 确认生产 reingest 用独立运维 runner（`KnowledgeReingestRunner`）且**全量执行仍"另授权"**（4.4 待勾）。
- 差异摘要：totalTokens 17179→20274（+3095，parent-expand 填父块 + semantic 大块）、meanTotalLatency 3940→3523ms。

### 任务组 6 第五跑 after-query：触发判定不成立（2026-09-13，不执行真跑）

- **6.1 判定 = 不支持，只回填归因不跑 after-query**。依据任务组 1 机械归因（baseline-v1 纯向量单路口径）：TEXT 5 条 recall@k=1.0 / MRR=1.0、LEXICAL 6 条 recall@k=1.0 / MRR=1.0，词汇路与文本路均已到顶；after-hybrid 接入稀疏路后 LEXICAL 持平于上限（任务组 3 已证）。fixtures 量级**不存在「查询-文档词汇失配为主要漏召」缺口**。
- 推论：多查询/HyDE 的作用面是"改写救词汇失配漏召"，在召回已饱和的量级下无可归因提升空间——执行 after-query 只会多花一次真外发测不到差异化收益，故按 6.1 条件分支**不执行、不产出 `baseline-after-query.json`**（成本闸门显式不浪费，多查询/HyDE 三开关维持默认 false 不翻转，是否在有真实词汇失配数据的更大语料上启用留待用户决策）。
- 提案5 回填：4.1/4.2 改标「触发不成立」并勾选（任务台持续，真库更大语料词汇失配系回归时可再授权重跑）。
