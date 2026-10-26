# tasks · eval-port-production-parity（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（命令 + 实测值 + 可 grep 的符号/字段名）。三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 / 决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束

- **默认零外呼**：默认 `mvn test` 不得发起任何真实模型调用（R3）；真跑必须显式 opt-in。
- **预算 ¥5 封顶**：真跑前先打印推导、再硬检查、超帽即停（R4/D4）；**不得**用「先跑几条看看」试预算。
- `src/main` / `pom.xml` / `.github/**` **零 diff**；既有 45 张 `docs/rag-quality/*.json` **零字节变动**；不改任何生产常量。
- 锚写模式 `-Deval.port.parity.write=true`；新增测试类优先放 `src/test/java/com/slz/crm/quality/`。

## A · 稀疏腿对齐（R1，零 LLM 外呼）

- [x] 1. 把 fixture 切片灌入真 MySQL 8.0.36（Testcontainers）并建出真 FULLTEXT ngram 索引：真 Flyway 迁移 + 真插入，**不得**改 `src/main`
      补勾依据：`mvn -B -ntp test "-Dtest=EvalPortParitySparseTest" "-Deval.port.parity.write=true"` → `Tests run: 3, Failures: 0`；`EvalPortParitySparseTest#migrate` + `#seedCorpus` + `#optimizeFullTextIndex`；真实装卡 57 切片（`slugCount=57`）；锚 `runtime.mysqlImage=mysql:8.0.36`、`runtime.migration=classpath:db/migration`（V22 `ft_chunk_text ... WITH PARSER ngram`）
- [x] 2. 用**生产同一条** `DocumentVectorChunkMapper#fulltextSearch` SQL 取候选（同授权 JOIN、同 `is_deleted` 过滤），保留 bench 命名空间映射
      补勾依据：`EvalPortParitySparseTest#productionSparseRecallService` 直构真实 `SparseRecallService`（同一 Mapper 代理，无 Spring 上下文）；锚 `sparseDifferential.productionSql=DocumentVectorChunkMapper#fulltextSearch`、`sparseDifferential.namespaceMapping`（`benchdoc-<key>` + `chunk_index` → `<key>-<chunkIndex>`）；72 查询全部映射回 `preparation.chunkIds()`
- [x] 3. 同批候选做「bigram 近似 vs 真 ngram」差异表：top-k 集合重叠数、名次位移、分数分布（min/p50/max），逐查询落锚；**禁「等价/一致/无差异」措辞**（守卫断言）
      补勾依据：锚 `sparseDifferential.rows[72].{overlap,maxRankShift,realScore,bigramScore}`；实测合计 `totalRealHits=341 / totalBigramHits=331 / totalOverlap=247`（`EvalPortParitySparseTest#sparseLegRealNgramDifferentialTable` 打印）；守卫 `EvalPortParityGuardTest#r1WordingBanAndSparseDifferential` 逐行重算交集并扫禁用词表

## B · 度量口径三项并列（R2）

- [x] 4. 每个用例同时落 `rawFusedScore`（归一化前）+ `rank`（同集合名次）+ `margin`（与相邻候选差）；归一化分若保留必须标注「集合内相对量」
      补勾依据：锚 `metricTriplets.cases[70].candidates[].{rawFusedScore,rank,margin,vecNorm,zScore,portScore}` + `tailPortScore`；`metricTriplets.rawDefinition/rankDefinition/marginDefinition`；2 条 EDGE 用例落 `nonApplicable`（72 = 70 + 2）
- [x] 5. 断言：锚内不存在「只有归一化分、无 raw/rank/margin」的用例行
      补勾依据：守卫 `EvalPortParityGuardTest#r2TripletsCompleteAndIdentity`（三字段 `isMissingNode` 逐个判红 + `rank` 连续 + `margin` 与下一名/tail 重算）；同测试断言归一化恒等式 `portScore == wv·vecNorm + (zScore - wv)` 与 `vecNorm == (raw-rawMin)/(rawMax-rawMin)` ≤1e-12（实测 `identityMaxAbsDiff` 最大 2.78e-17）

## C · 真嵌入重放（R3/R4，opt-in）

- [x] 6. 真嵌入通道：生产 `ModelProvider#embed` 包一层计数 + 硬帽；**默认路径必须不调用**（断言默认跑 `externalCalls=0`）
      补勾依据：`EvalPortParityRealEmbeddingReplayTest#CountingProvider`（超计划/超 ¥5 即抛）；默认跑 `mvn -B -ntp test "-Dtest=EvalPortParity*Test"` → `Tests run: 12, Skipped: 1`（重放 opt-in 用例被 `assumeTrue` 跳过）；锚 `defaultExternalCalls=0`、守卫 `#defaultZeroExternalCalls`（桩 stream/vision 即抛 + `ModelProviderProperties` 无 key）
- [x] 7. 预算推导打印：计划调用次数（72 查询 + fixture 全量切片数）+ token 估算 + 单价来源；**第一次调用前**硬检查，超帽抛错终止
      补勾依据：控制台 `[eval-port-parity] 真嵌入预算推导：queryEmbedCalls=92 ... fixtureSliceCalls=57 plannedCalls=149 estimatedTokens=18034 estimateBasis=1 token / 字符（保守上界：查询文本 + 切片文本 + 每切片 block 头 +64 字符） price=0.5 元/百万tokens（来源：https://help.aliyun.com/zh/model-studio/text-embedding-v3）estimatedCostCny=0.009017 capCny=5.0 ⇒ PASS`；锚 `budget.plan.hardCheck=PASS`、`budget.plan.hardCheckTiming`、`budget.capCny=5.0`、`budget.priceSource`。旧打印口径只算切片、低于实测（16792 < 16968），fix1 已统一为保守上界（查询文本 + 切片文本 + 每切片 block 头），锚 18034 ≥ 实测 16968
- [x] 8. 真跑一次（本卡**唯一**花钱动作），把调用次数 / 实际 token / 耗时 / 三个口径的对照结果入锚；**跑完即停**，不追加档位
      补勾依据：`RAG_BENCHMARK_REAL=1 mvn -B -ntp test "-Dtest=EvalPortParityRealEmbeddingReplayTest" "-Deval.port.parity.write=true"` → `[eval-port-parity] 真嵌入重放完成（一次）：calls=149 tokens≈16968 elapsedMs=28944 cost≈0.008484 元 / cap=5.0 元`；锚 `binary.executed=true`、`binary.ledger.{actualCalls=149,actualTokens=16968,elapsedMs=28944,costCny=0.008484}`、`binary.realSuiteMetrics.recallAt5=0.93519/mrr=0.89931` vs `binary.pseudoSuiteMetrics.recallAt5=0.84375/mrr=0.78843`；实测调用数 == 计划数，未追加

## D · 锚与守卫（R5/R6/R7）

- [x] 9. 锚 `docs/rag-quality/eval-port-parity-v1.json`（纯 LF）+ `.md`（≤40 行）；`residualDeviation` 逐条列仍 off-production 的腿与被排除结论（引 `eval-port-route-v1#nonSupportable`）
      补勾依据：锚文件（写模式重建）；md 实测 16 行（守卫 `#r6FormatAndR7Extrapolation` 断 ≤40）；`residualDeviation[5]`：语料腿/输入腿/稀疏腿/度量腿/归一化面，每条 `ref` 含 `eval-port-route-v1#nonSupportable`，语料腿 `status=STILL-OFF-PRODUCTION`
- [x] 10. 守卫：R1 措辞禁令、R2 三项齐备、R3 默零外呼、R4 预算字段自洽、R5 残差声明非空、md ≤40 行、`externalCalls` 与台账一致
      补勾依据：`mvn -B -ntp test "-Dtest=EvalPortParityGuardTest"` → `Tests run: 7, Failures: 0`；逐条：`#defaultZeroExternalCalls`、`#r1WordingBanAndSparseDifferential`、`#r2TripletsCompleteAndIdentity`、`#r4BudgetSelfConsistent`、`#r5ResidualDeviation`、`#d6CiPortAndLegs`、`#r6FormatAndR7Extrapolation`
- [x] 11. 边界：`git diff master --stat -- src/main pom.xml .github` 空；既有 45 张锚零 `M`；`git diff --check` 干净
      补勾依据：三条命令实测——`git diff master --stat -- src/main pom.xml .github` 无输出；`git status --porcelain -- docs/rag-quality` 只有 `?? docs/rag-quality/eval-port-parity-v1.json` 与 `?? ...md`；`git diff --check` 无输出；`git status --porcelain --untracked-files=no` 无输出
- [x] 12. 外推禁令：锚与 md 内不得出现「生产分数分布 / 生产召回质量 / 可跨卡比较」类断言（守卫词表断言）
      补勾依据：守卫 `BANNED_EXTRAPOLATION` + `#r6FormatAndR7Extrapolation` 对锚 JSON 与 md 双向扫；`verdictPortScope` 点名「仍未量化：语料腿」
- [x] 13. 若某腿在 ¥5 内做不成真（provider 拒绝 / dim 不匹配 / 真 ngram 对中英混排不召回）⇒ 记 `UNDETERMINED` 并写 `proposal §8`，不许近似顶替（R8）
      补勾依据：本轮未触发——真 ngram 命中 341（>0，非零召回）；真嵌入 `calls=149` 全部成功、成本 ¥0.008484 ≪ ¥5；`proposal §8` 无需追加停手记录

## 收尾实测（R6 逐字节）

- 写模式重建后默认复跑：`mvn -B -ntp test "-Dtest=EvalPortParity*Test"` → `Tests run: 12, Failures: 0, Skipped: 1`、`BUILD SUCCESS`；两锚 raw sha256 前后一致（`json=B511A2438BC6E1C7E632B6893600F6661E102F230855D13915F18E4ECFB6FFAA`、`md=0026EE137F164C39932E4AAF28FFE156B211EB7E6CE3C876E166FD41A97483CA`，`Get-FileHash -Algorithm SHA256` 实测）。
