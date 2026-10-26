# 任务卡 · near-dup-rerank-decomposition（卡 35 · B 档 · 两腿一卡，首选零外呼）

## §1 目标

**腿 A（首选零外呼）**：卡 34 停在「`r` 为什么是 0」未证。侦察发现 `corpusMaterial` 已冻结真实文本与向量，`Bm25Scorer` 又是纯本地的 ⇒ 可以**本地**把 `r = wv·normVec + wb·normBm25` 拆开，验证「4 条零位移候选在两条分支上是否都取最小」。成立 ⇒ `PROVEN`；不成立 ⇒ `REFUTED`；对不上 ⇒ `UNDETERMINED` + 更窄的埋点方案（**不执行**）。

**腿 B（零外呼）**：0.90 决策页——维持 `0.9887` vs 降到 `0.90` 的 lexicographic 对照，**不给推荐值**（阈值是 Owner 的）。

## §2 背景（三条必须记住的教训）

1. **板规 27**：花钱的调用放最后。本卡开局就想清楚「本地能拆到什么程度」，而不是先跑一次真跑看看。
2. **D2 口径同源**：必须调生产 `Bm25Scorer` / `DefaultWeightedReranker`，**不许自己重写 min-max**——自己写一套再宣称量到了，是换尺子。
3. **D3 退化分桶**：卡 34 实测 `degenerate = 3 / nonDegenerate = 3`，退化查询里 `r = 0` 不算「末位」证据。

## §3 写集（**只许写这 4 个文件**，无其他写入者）

| 路径 | 内容 |
| --- | --- |
| `src/test/java/com/slz/crm/quality/NearDupRerankDecompositionTest.java` | 本地复算 + 写锚（写模式） |
| `src/test/java/com/slz/crm/quality/NearDupRerankDecompositionGuardTest.java` | 守卫（复算/分桶/三值/无推荐值/上游 SHA/md 行数） |
| `docs/rag-quality/near-dup-rerank-decomposition-v1.json` | 写模式生成（纯 LF） |
| `docs/rag-quality/near-dup-rerank-decomposition-v1.md` | ≤40 行 |

## §4 禁改面（越界即判死）

- `src/main/**`、`pom.xml`、`.github/**` 零 diff；不新增依赖。
- 既有锚（卡 32/33/34 三张）零字节变动；11 个禁改 `NearDup*` 类只读。
- **不许跑检索、不设 `*_REAL`**：本地复算只读锚内材料 + 调生产纯函数。

## §5 成本铁律

零外呼优先。若判 `UNDETERMINED`，埋点方案只写不跑；预算另请授权。

## §6 验收（摘要）

1. `r` 复算与锚反算逐条一致（容差 1e-9）；`r = 0 ⟺ 两分支同时取最小`。
2. 4 条零位移事件全落非退化桶且两分支均为最小（`PROVEN` 的充分条件）或给出反例（`REFUTED`）。
3. `verdict` 三值之一 + 三值的可达路径写清；`UNDETERMINED` 时 `instrumentedPlan.executed == false`。
4. 腿 B 两栏对照齐备，锚与 md 无推荐值（双面 grep）。
5. 锚 LF、默认复跑逐字节一致、上游 SHA 实算、md ≤40 行。

## §7 停手条件

- 本地复算需要跑检索 ⇒ 停手（那不是本地复算）。
- 复算口径与生产不同源（自己写 min-max）⇒ 判不合格。
- 复算对不上 ⇒ 判 `UNDETERMINED` 并记 `proposal §8`（具体失败步骤 + 样本），**不许**改 R3 迁就。

## §8 回报（≤300 字）

提交 SHA / 文件数 / 两个权重实测值 / `r` 复算一致条数 / 4 条零位移的两分支取值与桶 / `verdict` + 理由 / `instrumentedPlan.executed` / 腿 B 两栏的核心数字 / 未解决项 / 待决策项。

## §9 细节留档位

执行者在此节追加：复算算法与调用点、逐查询候选表、4 条零位移的两分支数值、退化桶判据、`verdict` 推演、腿 B 表格来源字段路径、任何偏离 proposal 的选择与理由。

### 卡 35 执行留档（2026-10-26，feature/near-dup-rerank-decomposition）

- **权重实测**：`wv = 0.6`、`wb = 0.4`（两条受控探针直接调生产 `rerank` 反解；负对照 0.9/0.1 同步还原 0.9/0.1，证明读的是 `resolveRatio` 而非硬编码）。
- **复算**：12/12 与卡 32 反算一致（`maxAbsDiff = 5.55e-16`，容差 1e-9）；候选集 30 行全重建（图像路 15 + 路外 15，`routeListSize = 3/3/3/2/2/2`）。
- **4 条零位移**：`normVec = 0.0`、`normBm25 = 0.0`，桶全为 `DEGENERATE-NOT-EVIDENCE`（bm25 分支在该查询退化为全 0）。
- **结构发现**：`r = 0` 要求 `wb·normBm25 = 0`；bm25 在 6/6 查询退化——q0/q2 全 1.0（同 route 候选 `text` 逐字节相同、长度归一化后同分），q1/q3/q4/q5 全 0.0（零命中）。故 `r = 0` 只可能出现在 q1/q3/q4/q5，实测 4 条全在那里；q0/q2 的最小 `r` 恰为 **0.400000 = wb**（两侧预言都对上）。
- **verdict = UNDETERMINED**：不是缺证据，而是 R3 的「4 条零位移全在**非退化**查询」不成立（bm25 分支 6/6 退化）。执行者**未改判据迁就数据**，符合提示词红线。
- **腿 B**：`70/120 (58.33%) ↔ 113/120 (94.17%)`、`13/2760 (0.47%) ↔ 93/2760 (3.37%)`、`2/100 ↔ 8/100`；无推荐值。
- **复核后 fix1（主 agent 代笔，2026-10-26）**：M1 台账（tasks 14 条补勾 + 本节 + 卡面入库）；M2 `runtime.externalCalls=0` + `excludedFromReplay` + 依据说明；M3 `violations` 改为累加计数；M4 新增**不依赖 rerank 探针**的独立路径（只用 `cos` 断端值映射，`checked=6 / endpointViolations=0`）；M5 `isVecMin/isBm25Min` → `normVecAtZero/normBm25AtZero`（语义澄清）；M6 「全命中」归因改为「同 route 候选 `text` 逐字节相同 ⇒ 长度归一化同分，非 BM25 饱和的普遍规律」；M7 `numericHygiene.snapRule` 入锚。
