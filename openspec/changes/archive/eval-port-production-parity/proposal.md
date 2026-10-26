# 提案 · eval-port-production-parity（评测端口误差条 + 真通道）

## §1 问题

`docs/rag-quality/eval-port-route-v1.json` 已定案 `verdict=PARTIAL-OFF-PRODUCTION`：72 例套件实测 **6 条腿**——`装配腿` 是生产类直构（`classification=PRODUCTION`，0 偏差点），其余 **5 条腿共 9 个偏差点**（`输入腿` 3 / `稀疏腿` 3 / `度量腿` 1 / `语料面` 1 / `归一化面` 1），**套件分数不可跨卡比较**（同一锚 `nonSupportable[]` 已列死被污染的结论）。后果是具体的：

- 任何「调阈值 / 调融合权重 / 换 rerank」的判断都只能靠**真跑卡**（花钱 + 慢 + 不可逆预算）；
- 近重复线六张卡全程绕开它，只用 pair 级 `cos`（见卡 32–36 各锚）——这不是偏好，是因为检索分不可信；
- 于是「有争议的改动」永远被推到长队尾，而「零成本的判断」永远做不了。

本卡**不**替换 CI 端口（理由见 D6），而是做三件能立刻兑现的事：**给既有套件的分数安上误差条**、**把「报什么」改成可比口径**、**把真嵌入变成一条带预算的通道**。换不来「生产分数分布」，但换来「这套分数值不值得信、错多少、要花多少钱才真」。

## §2 五条偏差腿的现状与处置（`装配腿` 本就是生产类直构，不在处置范围）

| 腿 | 现状（`eval-port-route-v1` 实测） | 本卡处置 |
| --- | --- | --- |
| 稀疏腿 | `SparseBenchmarkRecallService` 覆写 `recall`、`super(null)` 不触 DB，字符 bigram 重叠近似 | **量化差异**：同批 fixture 在真 MySQL 8.0.36 上跑生产同一条 `DocumentVectorChunkMapper#fulltextSearch` SQL（Testcontainers，装置见 `ChunkNgramRecallGateIT`），产出 bigram 近似 vs 真 ngram 的差异表；**不替换** CI 端口（D6） |
| 度量腿 | 只报 min-max **集合内归一后**的分数（`translationExperiment` 已证平移/缩放不变） | **改口径**：本卡锚内同时报 ① 归一化前融合分 ② 名次面 ③ 相邻 margin，三项并列，禁单报归一化分 |
| 归一化面 | 两次 `normalizeScores` 均为集合内归一 | **口径说明**：写清「集合内相对量」的适用边界与换算不可能性；不改生产实现 |
| 输入腿 | `DeterministicEmbedding` 确定性哈希伪向量（只承载 1/2-gram 词面） | **开真通道**：真嵌入重放（显式 opt-in，预算 ¥5 封顶，默认不跑）；跑一次即给出「伪向量 vs 真嵌入」的差 |
| 语料面 | 测试侧固定 fixture（15 份文档） | **本卡不碰**，锚内显式声明「该腿仍 off-production，禁外推」 |

交付后的处境：既有回归分的**误差可量化**（差异表 + 三口径），真嵌入**有价格**（¥5 台账），**不可得结论被写死**（`residualDeviation`）。不可消除的偏差只剩两条：语料面（本卡不碰）与默认路径的伪向量（真跑须 opt-in + 预算）。

## §3 判据（R0–R8）

- **R0 边界**：`src/main` **零 diff**、`pom.xml` / `.github/**` 零改、不改任何生产常量、既有 45 张 `docs/rag-quality/*.json` **零字节变动**。
- **R1 稀疏腿必须真（限本卡独立测试类，见 D6）**：同一批 fixture 切片入真库（真 Flyway 迁移 + 真 FULLTEXT ngram 索引），用**生产同一条** `fulltextSearch` SQL 取候选；并把「bigram 近似 vs 真 ngram」的差异**量化**（top-k 集合重叠、名次位移、分数分布差异），**禁写「等价 / 一致 / 无差异」类措辞**（措辞禁令与卡 29 R3 同源）。
- **R2 度量口径三项并列**：每个用例必须同时落 `rawFusedScore`（归一化前）、`rank`（同集合名次）、`margin`（与相邻候选的差）；锚内**不得**只给归一化分。归一化分可以留，但必须标注「集合内相对量」。
- **R3 默认零外呼**：默认 `mvn test` 下真嵌入路径**必须不执行**（跳过或直接不跑），CI 因此**不花钱**；只有显式 opt-in（`RAG_BENCHMARK_REAL=1` 或本卡新增开关，二选一且写进 tasks）才走真调用。
- **R4 预算 ¥5 封顶**：真跑前必须打印预算推导（计划调用次数 = 72 查询 + fixture 全量切片数；每条文本 token 估算；单价取该 provider 实价），并在**第一次调用之前**做硬检查：任一项超帽 ⇒ 抛错终止、不重试、不「先跑几条看看」。调用次数、实际 token、耗时全部入锚。
- **R5 残余偏差声明**：锚内必须有 `residualDeviation`，逐条列「仍 off-production 的腿 + 为什么不影响本卡结论 + 哪些结论仍不可得（引用 `eval-port-route-v1#nonSupportable`）」。
- **R6 差异必须可复认证**：锚由生成器按写模式 `-Deval.port.parity.write=true` 重写；默认复跑逐字节一致（剔除顶层 `runtime`）；纯 LF、单尾换行。
- **R7 不许外推**：本卡产出的任何数字，**不得**写成「生产语义召回质量」或「生产分数分布」；只能写「在真 ngram + 真嵌入、语料仍为 fixture 的条件下」。
- **R8 停手**：任一腿无法在 ¥5 内做成真的（例如 provider 拒绝、dim 不匹配、真库 ngram 对中英混排不召回），**记 `UNDETERMINED` 并写清原因**，不许用近似顶替再声称「已对齐」。

## §4 交付物

- 稀疏腿对照测试：`src/test/java/com/slz/crm/quality/EvalPortParitySparseTest.java`（真 ngram 装置 + 近似 vs 真的差异表，纯本地/容器，零 LLM 外呼）
- 真嵌入重放测试：`src/test/java/com/slz/crm/quality/EvalPortParityRealEmbeddingReplayTest.java`（opt-in，硬帽 + 计数，默认不执行）
- 守卫：`src/test/java/com/slz/crm/quality/EvalPortParityGuardTest.java`
- 锚：`docs/rag-quality/eval-port-parity-v1.json` + `.md`（≤40 行）

## §5 成本

- **LLM 外呼**：默认 0 次；opt-in 真跑上界 = 「72 查询 + fixture 全量切片」次嵌入调用，**¥5 封顶**（R4 硬帽）。
- **0 迁移**、0 契约变更、0 权限变更、0 新依赖（Testcontainers / MySQL 已有）。
- 容器：需要本地 Docker（卡 31 实测在线；CI 的 `fail-fast-gate` 亦跑 Testcontainers 系列）。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1 不改生产**：对齐靠测试侧装置 + 生产同一条 SQL，**不**为了测试而给 `src/main` 加开关。
- **D2 「对齐」= 换成真实现，不是校准近似**：bigram 加权重标定不算对齐，只算「更准的近似」，本卡不认。
- **D3 度量腿改的是「报什么」，不是「算什么」**：生产 `normalizeScores` 不动，只是把归一化前值与名次面一并留下。
- **D4 预算不可挪用**：¥5 是 Owner 给的上界，本卡不得为「多跑几档」而放宽；不够就停手并报需要多少。
- **D5 语料腿留白**：本卡明确**不**把 fixture 换成生产语料；那是另一张卡，且会被 R7 约束。
- **D6 不替换 CI 端口**：`mvn test` 的契约是**无 Docker**（`AGENTS.md` 明写），把真 MySQL / 真 ngram 塞进默认端口会让无 Docker 环境直接红。所以真装置只进**独立测试类**（Docker 存在才跑），CI 端口一字不动，其误差由本卡的差异表给出。**谁想改默认端口，是另一张卡，且要先改契约。**

## §7 与既有锚的关系

- 只读输入：`eval-port-route-v1`（本卡的偏差清单来源，`legs[].deviationPoints` / `nonSupportable[]`）、`near-dup-*` 各锚（不动）。
- 本卡不重算任何既有锚，只用它们声明过的口径做对照。
- `RagQualityRegressionTest` 的基线门禁不变（本卡不改它的判据，只新增独立测试类）。

## §8 判据修订记

- （预置）若稀疏腿真 ngram 在 fixture 上表现显著差于 bigram 近似（例如 LEXICAL 用例 top-5 命中下降），**不改判据迁就数据**：如实记差异，并停手上报——那说明生产稀疏路本身对这批语料不适用，属于产品级发现，优于本卡结论。
