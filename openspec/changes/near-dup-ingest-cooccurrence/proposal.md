# 近重复图真实入库的共现位移实测（near-dup-ingest-cooccurrence）

> 状态：**已立项，待 Owner 派发**。Owner 2026-10-24 授权 **S0→S1 阶梯**：先 6 图探针（18 次外呼），通过后再 48 图（144 次外呼），**累计上限 162 次外呼**。
> 本卡是储备 ② 的执行卡，直接后续口径四 `near-dup-displacement-evidence-v1`（`POSSIBLY-DISPLACED`）。
> **fix1 收卡（2026-10-25）**：Owner 裁定 A′——S1 未授权（首轮 124 次外呼后红于计数恒等式，重跑需 ~120 次将破
> 162 累计帽），收窄为 S0-only：S1 消融不跑、S1 装置拆除（骨架留 `d7abc37` 供后续卡取回）、负结论预注册于
> `docs/rag-quality/near-dup-ingest-s1-not-run-v1.json`；§5 判据 R2–R7 随 S1 封存（不改判据文本），R0/R1 已交付。

## 1. 要回答的问题（一句话）

口径四只能给「若该图真是带内近重复，**68.5%（1479/2160）** 的 `(cos,w)` 组合会把它压到实测竞争者之下」——这是**反事实重建**，不是实测。本卡要把它变成同一批真实候选上的**因果对照**：

> 当带内近重复图**真的入库**、与文本竞争者在**同一 top-k** 里竞争时，降权是否真的改变了先后？

## 2. 方法（消融，零额外外呼）

对每条查询跑**两遍**同一批候选：

- **A 生产模式**：图像路三档乘数 `0.75/0.6/0.5`；
- **B 消融模式**：三档乘数全 `1.0`（等价于「不降权」）。

两次检索返回的候选集合必须**逐条相同**（同一 KB、同一 top-k 参数、同一查询向量），唯一差异是乘数。逐候选记录 `(chunkId, sourceType, 图像路分, 文本路分, 融合分, tier, band, 生产排名, 消融排名)`。

**这正是本卡最重要的设计选择**：不需要再造一次反事实，也不需要第二次外呼——反事实的「假如不降权」在同一个进程里可以用乘数直接构造，而且构造出来的就是我们自己的生产融合代码算出的分。

## 3. 侦察结论（立项时实测，行号以 master `cdf3382` 为准）

### 3.1 一次「入库」= 3 次外呼/图

`PdfImageSliceIngestService.describeAndStore`（`:240-258`）逐图：

1. `modelProvider.vision(...)`（`:280`）—— VLM 描述，过 `VisionIngestSupport.passesQualityGate`（≥40 字 + 「（截图不清）」占比 ≤0.4）
2. 描述文本 → `toChunk` → 走既有 chunk/embed 链路（**1 次文本 embedding**）
3. `stageImageVector`（`:253`）→ `modelProvider.embedImage(...)`（**1 次视觉 embedding**，`ImageVisualVectorSupport:72`）

### 3.2 **会毁掉实验的耦合**：闸门失败连同图像向量一起丢

`:248` `if (description != null)` 把 `stageImageVector`（`:253`）包在里面。**VLM 输出没过质量闸门 ⇒ 文本 chunk 与图像向量一起丢弃**。而 `crop-12` / `scale-070` 这类退化变体，正是最可能落在近重复带、也最可能让 VLM 输出大量「（截图不清）」而被拒的那批 —— **实验会系统性地丢掉自己要观测的目标**。

⇒ 决策 **D3：本卡不改这个顺序**。闸门拒图是**要被测量的现象**，不是要修的 bug；S0 探针把 `gatePassRate` 作为第一等输出。要通过「解耦图像向量与描述」来救，属于**改生产行为**，另立卡（结论写进 `missingEvidence`）。

### 3.3 语料与能力面

- `NearDuplicateCorpus.generate()` 造 **24 base × 5 kind = 120 变体对 / 144 图**（白底合成、退化登记已修复）；**只读复用，禁改生成逻辑**。
- 定标锚 `image-threshold-calibration-v2.json` 的 `pairs[]` 里 `stratum=near-duplicate` 档共 120 对，其中 `cos ∈ [0.9887, 0.9944)` 的**带内对 7 条**（口径二 R4 已实测该数）—— 这 7 对的参与者是 S1 的**必选集**。
- 入库路径只吃 PDF：`buildSlices` 仅对 `isPdf(filename)` 生效（`:130`）。⇒ 变体必须包成**合成 PDF**（决策 D1）。
- 每文档图数帽：`DEFAULT_MAX_IMAGES=5`，**`HARD_MAX_IMAGES=20`**（`:66/:69`），配置键 `rag.retrieval.image-slice-ingest.max-images-per-document`。⇒ 48 图 = **3 份** PDF（20/20/8）。
- 四个开关：`image-slice-ingest.enabled`（总闸）、`image-slice-ingest.kb-allowlist`、`image-slice-ingest.max-images-per-document`、`image-vector-ingest.enabled`（视觉路）。
- 双集合装配已有先例：`NearDupDisplacementEvidenceIT` 同时装文本集合与图片集合，**允许复制其装配，禁改原 IT**（决策 D4）。

### 3.4 实测单价（不编汇率）

| 量 | 值 | 来源 |
| --- | --- | --- |
| VLM 描述 | **9.77 s/图**；**≈1067 token/次** | `production-gray-cost-v1.json` `elapsedMsPerImage=9774.857`；9 次 vision 回流 9600 token |
| 图像视觉 embedding | **1.63 s/次** | `image-threshold-calibration-v2.json` `elapsedMillis=469111` ÷ 288 |
| 金额 | **不给** | `docs/token-metering.md` §6 明写「按次计价换算系数不在代码里，属业务定价口径」；本卡只报次数与 token |

**成本上限（Owner 已授权）**：

| 阶段 | 图数 | 外呼 | VLM token | 墙钟估计 |
| --- | --- | --- | --- | --- |
| **S0 探针** | 6 | **18** | ≈6.4k | ≈1.2 min |
| **S1 实测** | 48 | **144** | ≈51k | ≈9–10 min |
| 合计 | — | **≤162** | ≈57k | ≈11 min |

## 4. 决策（执行者不得自行改判）

- **D1 合成 PDF**：变体包成 PDF 走**真实 ingest 路径**，不许直接注入 chunk/vector（否则「入库」是假的，共现无从谈起）。每份 ≤20 图。
- **D2 消融而非反事实**：同一批候选只换乘数，零额外外呼、因果可控。
- **D3 `src/main` 零 diff**：`stageImageVector` 留在 `description != null` 分支内（见 §3.2）。
- **D4 复用 G1 装配**：双集合装配从 `NearDupDisplacementEvidenceIT` 复制，**禁改原 IT 与既有锚**。
- **D5 语料只读**：`NearDuplicateCorpus` 不改；变体→PDF 由本卡新增 builder。

## 5. 判据（R0–R6，跑前写死、跑后禁改）

- **R0 合成 PDF 可行性（零外呼，先做）**：PDFBox 造 3 图 PDF → `PdfImageExtractor.extract(bytes, 20)` 抠出的图数与像素尺寸**与输入逐一对上**。不通过 ⇒ 停手（换方案需 Owner 决定）。
- **R1 闸门通过率（S0）**：6 图（3 base + 3 个最差变体：`crop-12`/`crop-04`/`scale-070`）逐图落 `(assetKey, kind, token, gatePass, failReason)` 与 `gatePassRate`。**`gatePassRate < 4/6` ⇒ 停 S0 上报，不进入 S1**。
- **R2 入库完整性（S1）**：实际处理图数 `N`；image 集合向量数 = `N_pass`（过闸门的图数）；描述 chunk 数 = `N_pass`；`externalCalls` **实测值** = `3 × N`（写 0 或算出来的数判假绿）。48 图则 ≤144。
- **R3 消融对照**：逐查询跑 A/B 两模式；**断言两次的候选 `chunkId` 集合逐条相同**；逐候选落 `(图像路分, 文本路分, 融合分, tier, band, rankA, rankB)`。
- **R4 带内覆盖**：S1 的 48 图**必须包含全部 7 条带内 variant↔base 对的参与者**（从定标锚筛，写死选取规则），并含 **≥6 张非带内变体**作对照。凑不齐 48 就从同一档补，**不许换语料、不许改生成逻辑**。
- **R5 归因隔离**：消融只改图像路乘数 ⇒ 逐候选**文本路分两次必须相等**（不等即红，说明消融污染了文本路）。
- **R6 结论三选一（禁造第四种）**：
  - `DISPLACED-IN-FUSION`：≥1 条查询出现「A 模式下图像候选排名劣于某文本竞争者，B 模式下翻回」；
  - `NOT-DISPLACED-IN-FUSION`：全部查询两模式排名完全一致；
  - `INSUFFICIENT-COVERAGE`：带内候选未入库 / 被闸门拒 / 覆盖不足，无法定案。
- **R7 副作用留痕**：变体描述文本与 base 描述高度相似，文本路本身也出现近重复 ⇒ 落 `textSideNearDuplicates` 计数，供 R6 解释用（**不作为判据**）。

## 6. 不做的事

1. 不改 `src/main` 任何文件（含 `stageImageVector` 的位置）。
2. 不改 `NearDuplicateCorpus`、不改既有 IT、不改六份既有锚、不改签收锚。
3. 不跑 S2（全 144 图）；不跑任何其它真外呼 IT。
4. 不动 `NEAR_DUPLICATE_FLOOR`、三档权重、阈值、融合权重。
5. 不把外呼上限当下限——**能少调用就必须少调用**。

## 7. 交付物

- `src/test/java/com/slz/crm/quality/NearDupIngestPdfBuilder.java`（变体 → 合成 PDF，含零外呼自检；fix1：S0 所需能力内联进 GateProbeIT，原类拆除、全文留 d7abc37）
- `src/test/java/com/slz/crm/quality/NearDupIngestGateProbeIT.java`（S0，6 图）
- `src/test/java/com/slz/crm/quality/NearDupIngestCooccurrenceIT.java`（S1，48 图 + 消融；fix1：拆除、全文留 d7abc37）
- `src/test/java/com/slz/crm/quality/NearDupIngestGateProbeGuardTest.java`（锚守卫，默认跑）/ `NearDupIngestCooccurrenceGuardTest.java`（fix1：拆除、全文留 d7abc37）
- `docs/rag-quality/near-dup-ingest-gate-probe-v1.json` / `.md`（S0 锚）
- `docs/rag-quality/near-dup-ingest-s1-not-run-v1.json`（fix1：S1 NOT-RUN 负结论锚；原 S1 锚 `near-dup-ingest-cooccurrence-v1.json` 必须不存在——守卫断言负空间）
- `scripts/test-baseline.txt`（仅经 `--update`）
