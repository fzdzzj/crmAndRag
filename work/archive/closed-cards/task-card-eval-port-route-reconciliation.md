# 任务卡 · eval-port-route-reconciliation（卡 29）

> 权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`。分支 `feature/eval-port-route-reconciliation`（基点 = 当前 `master` `d812ed1`）。
> 分档：**B 档**（复核对账式）。成本：**零外呼**。
> 先读：`openspec/changes/eval-port-route-reconciliation/proposal.md`（R0–R6 / D1–D3）→ `.../tasks.md`（14 条）→ 本卡。

## §0 一句话

把 72 例基准套件的检索端口拆成**装配 / 输入 / 稀疏 / 度量**四条腿逐条对账，再用**平移不变性实验**证明重排分是集合内相对量，判定「这套件的分数能不能当文本路分布用」。

## §1 写集（5 个新文件，无其他写入者）

| 文件 | 动作 |
| --- | --- |
| `src/test/java/com/slz/crm/quality/EvalPortRouteReconciliationTest.java` | 新增（逐腿证据 + 平移实验 + 写锚） |
| `src/test/java/com/slz/crm/quality/EvalPortRouteGuardTest.java` | 新增（守卫） |
| `docs/rag-quality/eval-port-route-v1.json` | 新增（LF，默认逐字节复算） |
| `docs/rag-quality/eval-port-route-v1.md` | 新增（≤40 行） |
| `openspec/changes/eval-port-route-reconciliation/{proposal,tasks}.md` | 已由主 agent 写就，原样带进分支 |

## §2 硬边界

1. **`src/main` 零 diff**；`RagQualityRegressionTest` / `RagBenchmarkPipelineFactory` / `RagBenchmarkSuite` / `SparseBenchmarkRecallService` **一字不动**（只读）。
2. **零真实外呼**：不读 key、不构造真实 provider、不跑 `RAG_BENCHMARK_REAL=1`。
3. **改不了既有基线**：`scripts/test-baseline.txt`、`docs/rag-quality/baseline-v*.json` 零字节变动；套件用例数仍 72。
4. 不新增依赖、不改 pom、不加 `@Disabled`、不删既有断言。
5. **禁**把稀疏腿的 bigram 近似写成「与生产等价」；**禁**给阈值主张或修复建议。

## §3 七个坑

1. **别把「装配是生产类」误推成「口径是生产口径」**——四条腿必须分开判，合成一个「是/不是」就是说错话。
2. **`DefaultWeightedReranker` 的构造器可见性**：先看能否直构；不可则经公共入口（`KnowledgeRetrievalServiceImpl`），**绝不复制 min-max 逻辑**自造等价物（复制了测的就是你自己）。
3. **平移实验必须同时做「单点抬高」对照**：只做整体平移，恒等函数也能通过；有对照才证明测的是归一化。
4. **浮点**：`+100` 平移后归一化可能引入 `1e-16` 级差，断言用 `Double.compare` 前先确认实现是否 `double` 直算；若确有微差，落**实测最大绝对差**并写明容差理由，**不许**直接放宽成 `1e-6` 了事。
5. **伪向量不是「噪声」**：`DeterministicEmbedding` 是确定性哈希向量，其余弦分布与真嵌入**不可比**——这一条要写进「被污染结论」清单，不是一句「近似」带过。
6. **锚 JSON 必须纯 LF**（`DefaultIndenter("  ","\n")` + 断言 `!contains("\r")`），禁手写死值。
7. **`measure()` 是 private**：你只能复用其**读法**（照它的组装方式直构），不能调用它，也不能改它。

## §4 判定口径

| verdict | 条件 |
| --- | --- |
| `PRODUCTION-PATH` | 四条腿全部为生产实现（本卡**预期不会**取到，取到即须逐条证明输入腿为何算生产） |
| `PARTIAL-OFF-PRODUCTION` | 至少一条腿是替身/近似/归一化，须**逐腿**列出偏差点与被污染结论 |
| `INCONCLUSIVE` | 缺源码行号证据、或平移实验无法执行（须写清缺哪条） |

## §5 验收（顺序不可颠倒）

1. `mvn -B -ntp test` 全绿，贴统计行（surefire 只增不减）。
2. 变异三连红 → 还原 → 绿，留日志。
3. `git diff master -- src/main` 空；`git status` 只含写集 5 项；`git diff --check` 干净；外呼 0。

## §6 回报（≤300 字，细节留本卡 §7）

提交 SHA / 改动文件数 / `mvn test` 统计行 / **四条腿各自一句结论** / **平移实验三遍得分是否逐位相等** / `verdict` / 未解决项。

**「等价 / 一致 / 不变」这类词，必须同回合附可复算数字或行号；否则不许写。**

## §7 细节留档位

执行者在本节追加：四条腿的证据表、平移实验三组输入与输出（原始/+100/×2、单点抬高对照）、浮点容差实测、任何偏离 proposal 的选择与理由。

---

## §7 执行留档（2026-10-25，执行 agent）

### 提交与门禁
- 提交 `0ffcd2c`（`feature/eval-port-route-reconciliation`，基点 `d812ed1`，6 文件 +1656；未 merge 未 push）。
- `mvn -B -ntp test`：**Tests run: 2167, Failures: 0, Errors: 0, Skipped: 0**（= 基线 2159 + 新增 8，只增不减）；`bash scripts/check-test-baseline.sh` **EXIT=0**（surefire 351 报告/2167 tests/0 skipped ≥ 台账 349/2159/0，台账零字节未动）。
- `git diff master -- src/main` = 0 字节；`git diff --check` 干净；锚 JSON/MD 纯 LF（od 实测无 `\r`；`.gitattributes` `*.json`/`*.md` eol=lf 全机强制）。
- 终态 git status 仅余 `?? work/task-card-eval-port-route-reconciliation.md`（本卡自身，work/ 惯例保持未跟踪）。

### 四腿六面证据表（= 锚 `legs[]`，行号实测于 d812ed1）
| 腿/面 | 分类 | 生产件 | 替身/近似 | 关键行号 |
| --- | --- | --- | --- | --- |
| 装配腿 | PRODUCTION | KnowledgeRetrievalServiceImpl + DefaultWeightedReranker + RrfFusion | 无 | RagBenchmarkPipelineFactory.java:50-51 / 53-55 / 65-75 |
| 输入腿 | STUB | 真模型嵌入 + Qdrant（缺位） | DeterministicEmbedding + DeterministicModelProvider + InMemoryVectorStore | RagQualityRegressionTest.java:278-283 / 593-654 / 660-700 |
| 稀疏腿 | APPROXIMATION | SparseRecallService（FULLTEXT(ngram) + 授权 JOIN + 软删） | bigram 重叠、无授权 JOIN、无软删 | SparseBenchmarkRecallService.java:21-22 / 24-31 / 33-63 |
| 度量腿 | SET-RELATIVE | DefaultWeightedReranker 本体 | 无替身；min-max 集合内归一语义 | DefaultWeightedReranker.java:24 / 36-42 / 58-74 |
| 语料面 | TEST-FIXTURE | 生产库语料（缺位） | 15 份 fixture | RagBenchmarkDataPreparer.java:154-174 |
| 归一化面 | SET-RELATIVE | normalizeScores 本体 | — | DefaultWeightedReranker.java:41-42 / 58-74 |

装配腿运行时双证：对账测试与守卫各自**独立**直构 `RagBenchmarkPipelineFactory.build(HYBRID, …)`，均断言 `instanceof KnowledgeRetrievalServiceImpl`。

### 平移不变性实验（R2 命门）
- 探针 = T-01「销售合同审批流程是怎样的」（改写服务对恒等桩返回原问句）；拆路 1 条：向量 20 + 稀疏 20 → RRF 融合 **24 候选**。
- 同一候选集三遍重跑**生产** `DefaultWeightedReranker`（public 构造器 `:24` 直构，空配置 = 默认权重 0.6/0.4）：原始 / hit.score 全体 +100 / ×2。
- **rerankScore 实测最大绝对差：+100 = 2.8310687127941492E-14；×2 = 0.0（逐位相等）**。+100 的 2.83e-14 属预测内算术噪声：hit.score+100 落在本机 double ULP≈1.42e-14（2^-52×2^6）格内，min-max 分子/分母各一次舍入；×2 是 2 的幂缩放，理想逐位不变（实测证实）。
- 浮点容差：上界 1e-12 ≈ ULP(100) 的 70 倍裕度，实测 2.83e-14 远在界内；`>1e-12` 断言红 = R2 停手改判信号（stopCondition 落锚）。未放宽到 1e-6。
- **单点抬高对照**：只抬第二名候选 hit.score +10 ⇒ 名次 **1→0** 必升 ⇒ 装置测的是 min-max 归一而非恒等函数。

### verdict 与结论清单
- **verdict = PARTIAL-OFF-PRODUCTION**（机械推导：六行中任一非 PRODUCTION ⇒ PARTIAL；非 PRODUCTION 行 = 输入腿/稀疏腿/度量腿/语料面/归一化面）。
- 不可外推（锚 `nonSupportable[]`）：recall@5/mrr 绝对值；**从本套件取「文本路真分数分布」（储备 3 原案，R2+度量腿实证关路）**；跨查询/跨语料分位；稀疏路命中/相关度绝对值；伪向量余弦分布当真嵌入分布；生产语料分布。
- 可支持（锚 `supportable[]`）：同一装配下回归位移、同一查询内候选间相对排序比较、管线排序环节回归敏感性。

### 变异三连（守卫红证；日志 /tmp/evalport-mut-m{1,2,3}.log，关键断言如下）
1. 稀疏腿标注 APPROXIMATION→PRODUCTION ⇒ `腿分类与运行时事实不符（手改生产/替身标注即红）: 稀疏腿 ==> expected: <APPROXIMATION> but was: <PRODUCTION>`，exit 1。
2. verdict→EQUIVALENT-PRODUCTION ⇒ `verdict 越界（只允许三值，禁自创第四种）`，exit 1。
3. 结论位→SHIFT-SENSITIVE ⇒ 结论位断言 + 度量腿/归一化面前提断言**双红**，exit 1（Failures: 2）。
三处均以备份 `cmp` 逐字节还原；终态 `EvalPortRoute*` 8/8 复绿（FINAL_EXIT=0）。

### 偏离/裁量说明（无违反 proposal）
1. 实验语料向量化用本测试**独立实现**的确定性词面向量（256 维 bigram 有符号哈希 + 次线性 + L2），不反射进 `RagQualityRegressionTest` 私有嵌套类；候选分值与套件实测**不要求逐位相同**（**fix1 订正**：该声明只在任务卡，锚 JSON 内没有对应字段，原文误写「锚内声明」）。平移不变性是重排器函数性质，与候选分值来源无关；稀疏腿分数与套件逐位同源（同一 `preparation.chunks()` + 同一 `SparseBenchmarkRecallService`）。
2. R2「同一查询的候选」取 T-01 单探针（24 候选，与套件 rerank 真实输入同量级）跑三遍；函数性质单候选集足以实证，不对 72 例全量放大。
3. 锚零时间戳/零随机（逐字节复算前提）；`decisionOwner=OWNER`——储备 3 的口径处置（改写/放弃/另立真跑卡）归 Owner，本卡只交证据并关路。
