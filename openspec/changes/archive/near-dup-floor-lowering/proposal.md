# 近重复下限下探 0.9887 → 0.982（near-dup-floor-lowering）

> 状态：**已立项，待执行**。Owner 已于 2026-10-24 在 `docs/rag-quality/near-dup-floor-decision-request-v1.md` §6 口径上签收「下探 0.982」。
> 本卡是签收页末句「改档属新卡」所指的那张卡。**只执行已签收的这一档**：不重新定价、不改三档权重、不动 0.9944。

## 1. 为什么是 0.982（签收依据，不重算）

签收页 §1/§3 两口径逐档同值，取 0.982 行：

| 口径 | R / recallAtK | S_same / 同文档竞争率 | ΔR | ΔS_same | 多压掉的同文档图数 | ΔR/ΔS_same |
| --- | --- | --- | --- | --- | --- | --- |
| 0.9887（现状） | 0.583333 | 0.02 | — | — | — | — |
| **0.982（签收档）** | **0.666667** | **0.03** | **0.083333** | **0.01** | **1** | **8.333333** |

三条支撑事实：

1. **跨文档代价恒为零**：cross-class 余弦「最高 0.988736、次高 0.73」存在量级断层 ⇒ 带下沿移到 0.982 不新增任何跨文档污染，`C_cross` / `contaminationAtK` 全档恒 `1/176`（签收页 §1/§3 逐行可验）。唯一代价轴是「更多同文档图进降权带」。
2. **效率最高点**：0.982 是 8 个候选档里 `ΔR/ΔS_same` 最大的一档（8.333），且只多压 1 张同文档图。在「降权窗口已证实现实可能位移（`minDownwardGap=0.00941087658500056` ≪ `W_max=0.15`）」前提下，首步应最小化新增降权面。
3. **0.90 不属于本卡**：0.90 多压 6 张同文档图（降权面翻 4 倍），须在观测到真实位移后再议。

## 2. 侦察结论（立项时逐条实测；行号以 master `242dd4d` 为准）

### 2.1 档位耦合：三等分随 floor 缩放（**本卡最大风险**）

- `ImageSimilarityGate.java:34` `NEAR_DUPLICATE_FLOOR = 0.9887D`
- `:77-79` `effectiveFloor(threshold) = min(NEAR_DUPLICATE_FLOOR, threshold)`
- `:87-89` / `:98-100` 两内边界 = `floor + Δ/3` 与 `floor + 2Δ/3`，**Δ 与 floor 都取自 `effectiveFloor`**
- 默认阈值 0.9944 下当前边界：`0.9887 | 0.9906 | 0.9925 | 0.9944`（Δ=0.0057，每段 0.0019）

⇒ 只改常量不动公式：Δ→0.0124，内边界滑到 `0.986133…` / `0.990267…`，**已带内候选被无声改档且降权变轻**（`cos=0.9910` 由 `NEAR_MID×0.6` 变 `NEAR_HIGH×0.75`）。这是签收范围外的未测量行为变更，且破坏「越远离阈值 ⇒ 降权越重」的单调语义。

### 2.2 证据侧在生产常量上有活引用

- `NearDupFusionImpactAnalyzer.java:43` `BASELINE_FLOOR = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR`、`:257` `effectiveFloor`
- `NearDuplicateBandAnalyzer.java:44` `PRODUCTION_FLOOR = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR`、`:330` `labelOfProduction` 走 `effectiveFloor`
- 决策/守卫侧比对生产常量：`NearDupFusionImpactDecisionTest:339`、`NearDuplicateExchangeRateDecisionTest:206`、`NearDupFusionImpactGuardTest:290,295`、`NearDuplicateMechanismDecisionGuardTest:163,168`、`NearDuplicateExchangeRateDecisionGuardTest:204,209`

⇒ 常量一动这些守卫即红；若图省事直接重生成锚，六份证据会被静默改写 —— 而这六份正是本次签收的依据。

### 2.3 `NearDuplicateBandAnalyzer` 存在**双公式漂移**（本卡必须收口）

该类自持一套与闸门同形的通用式 `lowBandUpperBound(floor, threshold)` / `highBandLowerBound(floor, threshold)`（`:389`/`:400`），生产视图 `labelOfProduction`（`:330`）用 `effectiveFloor` 当 floor 走这套通用式。一旦 admissionFloor(0.982) ≠ 带基准(0.9887)，通用式给出 `0.986133…`、闸门给出 `0.9906` ⇒ 两者分叉。既有 `NearDuplicateBandAnalyzerTest#gateAlignmentSweep`（0.98–1.00 扫 2000 点与闸门逐点比对）会红，**这正是它该有的作用**。

### 2.4 会被新档「翻转预期」的既有用例（不改必红，且改法有讲究）

- `ImageSimilarityThresholdGateTest:197` 以 `0.985` 作「异图」样本断言被过滤 —— 0.985 现在**准入**。
- 同文件 `:346` `candidate("cross", 0.9886D)` 期望被 `gate` 丢弃 —— 0.9886 现在**准入且降权**。
- 同文件 `:213` DisplayName「图像路按 0.9887 检索」（minScore 断言）。
- `NearDuplicateBandAnalyzerTest:39,44` 取 `NEAR_DUPLICATE_FLOOR` 并断言 `== 0.9887`。
- `ImageNearDuplicateCalibrationAnchorTest:180` 断言 `productionView.floor == 0.9887`（**字面量，值不变**）但消息写着「必须仍取闸门常量」——改档后该说法失真。
- `NearDupFusionImpactDecisionTest:160` / `NearDuplicateExchangeRateDecisionTest:139` 的判据自述串写着 `NEAR_DUPLICATE_FLOOR=0.9887`。

### 2.5 面向运维的配置说明也说谎（生产文件）

- `DynamicConfigKeyRegistry.java:545` 的键说明写死「`[近似同图下限 0.9887, 阈值)`」「`< 0.9887 = 异图过滤`」——这是**用户可见**文案。
- `KnowledgeRetrievalServiceImpl.java:408` javadoc 写「`effectiveFloor(double)`（0.9887）」。
- `docs/dynamic-config-keys.md:94-95 / :101 / :105` 同口径（含「段边界由 `[0.9887, 阈值]` 三等分推导」）。

### 2.6 无配置/迁移面

`NEAR_DUPLICATE_FLOOR` 是编译期常量，全仓只在 `ImageSimilarityGate` 与其测试链被引用；`rag.retrieval.image-similarity-threshold` 是**阈值**键。⇒ 零迁移、零依赖、零 `openapi.yaml` 变更、零新配置键。

## 3. 架构决策（执行者不得自行改判；要改先停手上报）

### D1 · 拆「生产准入下限」与「带内基准」两个常量

```java
/** 生产准入下限（Owner 2026-10-24 签收「下探 0.982」，见 near-dup-floor-signoff-v1）。 */
public static final double NEAR_DUPLICATE_FLOOR = 0.982D;

/** 三档细分带的内边界基准 = 定标锚 v2 异图上限 0.9887359400788237 的 4 位小数。冻结：
 *  生产准入下限变动时内边界不得跟着动，否则已在带内的候选被无声改档。 */
public static final double BAND_REFERENCE_FLOOR = 0.9887D;
```

- `effectiveFloor(threshold) = min(NEAR_DUPLICATE_FLOOR, threshold)` —— **公式不变**。
- 新增 `bandReferenceFloor(threshold) = min(BAND_REFERENCE_FLOOR, threshold)`；`lowBandUpperBound` / `highBandLowerBound` 改用它推导。
- `bandOf` 的**判空下沿仍用 `effectiveFloor`**、两内边界用 `bandReferenceFloor` ⇒ `bandOf` 方法体本身**零改动**，只换两个被调方。
- 不引入第三个常量。证据侧各自持冻结字面量，**禁止**让证据侧引用 `NEAR_DUPLICATE_FLOOR`。

### D2 · 变更对 `[0.9887, 0.9944)` 纯加性

- 两内边界在默认阈值下**逐字保持** `0.9906 / 0.9925`（容差 1e-12）。
- 新准入区 `[0.982, 0.9887)` 全落 `NEAR_LOW`（0.5，最重）。
- 分档：`0.982…0.9906 → LOW/0.5`；`0.9906…0.9925 → MID/0.6`；`0.9925…0.9944 → HIGH/0.75`；`≥0.9944 → 无带`。
- 三档权重 0.75/0.6/0.5 **逐字不动**；`W_max = 0.30×(1−0.5)×1.0 = 0.15` 不变。

### D3 · 证据锚零改动（本卡验收硬线）

- **`docs/rag-quality/` 下既有文件一律不动**：六份主证据锚（`image-threshold-calibration-v2` / `near-dup-fusion-impact-v1` / `near-dup-exchange-rate-v1` / `image-near-dup-truth-v1` / `near-dup-displacement-evidence-v1` / `image-variant-cross-matrix-v1`）SHA 必须逐份等于 tasks.md §0 表；`image-near-duplicate-bands-v1.md` / `-v2.md`（无 `.json` 伴生）亦零改动。
- 语义：这些锚记录的 `floor=0.9887` 是**「测于该档」的历史事实**，不是生产现值。生产值改了就改锚 = 用新结论覆盖旧证据。
- 守卫比对口径由「== 生产常量」改为「== 冻结基准 0.9887」，注释写明理由。
- `NearDuplicateBandAnalyzer` 的**生产视图改为直接委托 `ImageSimilarityGate.bandOf`**（消掉双公式），锚视图（floor = 实测异图上限）保留自推三等分语义不变。

### D4 · 签收落成可校验的决议锚

新增 `docs/rag-quality/near-dup-floor-signoff-v1.json` + `.md`：签收档位、日期、六份证据 SHA、四格代价、被拒档位（0.9887 / 0.98 / 0.90）与理由、D1/D2/D3 三项冻结决策。新增守卫 `NearDupFloorSignoffGuardTest` 实算校验，并把「生产下限 < 证据档」这个**故意的不相等**写成显式断言（带解释注释），防止后来者「顺手对齐」。

## 4. 判据（E1–E7，跑前写死、跑后禁改）

- **E1 生产口径**：`NEAR_DUPLICATE_FLOOR == 0.982`；`effectiveFloor(0.9944) == 0.982`；`lowBandUpperBound(0.9944) == 0.9906`；`highBandLowerBound(0.9944) == 0.9925`（容差 1e-12）。
- **E2 分档与权重**：对 `{0.978, 0.9819, 0.982, 0.985, 0.988, 0.988699, 0.9887, 0.9906, 0.9918, 0.9925, 0.9935, 0.9943, 0.9944}` 逐点给 `(tier, band, weight)`；新准入区全 `NEAR_LOW/0.5`、`0.9819` 仍 `CROSS_IMAGE`、阈值点无带。
- **E3 旧档零回归**：`cos ≥ 0.9887` 逐点 `bandOf` 必须等于**字面量冻结**的改前期望表（禁活代码自证）：`0.9887→LOW, 0.989→LOW, 0.9905→LOW, 0.9906→MID, 0.9918→MID, 0.9925→HIGH, 0.9935→HIGH, 0.9943→HIGH, 0.9944→null`。
- **E4 证据锚零改动**：六份主锚 SHA-256 等于 tasks.md §0 表；`git status --porcelain docs/rag-quality/` 只允许出现两个新签收文件与签收页 `§6` 改动。
- **E5 窗口与权重不变**：三档权重逐字不变；`W_max == 0.15`，断言注释写明「被放缩的是图像路 rerank 后分，值域 [0,1]」。
- **E6 签收页落地**：§6 勾选「下探 0.982」+ 签名行；`git diff` 自证**只有一个 hunk 且位于 §6**（§1–§5 逐字未动）。
- **E7 生产文案与实现同口径**：`DynamicConfigKeyRegistry` 该键说明、`KnowledgeRetrievalServiceImpl` 该处 javadoc、`docs/dynamic-config-keys.md` 该键行，三处都不再出现「下限 0.9887 / `< 0.9887` 过滤 / 三等分自 0.9887」的旧说法，且与 E1/E2 数值一致。

**判据不可达即停手上报**，禁止调判据、换样本、放宽容差。

## 5. 不做的事

1. 不动 `DEFAULT_THRESHOLD`、三档权重、融合权重 0.70/0.30、`minScore`、top-k、重排器、融合顺序。
2. 不重新生成任何证据锚；不改语料生成器；不跑任何真外呼 IT。
3. 不把下限做成运行期配置；不加迁移、不加依赖、不改 `openapi.yaml`。
4. 不处理 0.90 档；不做阶梯式自动下探；不加「按 base 对自动定价」之类的新逻辑。
5. 不改 `docs/permission-matrix-audit.md`（历史快照）。
6. **不删断言来换绿**：任何因档位变化而失效的断言，只准「换成合法样本 + 同步文案」，不准删除或放宽。

## 6. 交付物

- 生产：`ImageSimilarityGate.java`（一常量改值 + 一常量新增 + 两边界方法换基准 + javadoc 四处口径校正）、`KnowledgeRetrievalServiceImpl.java:408` javadoc、`DynamicConfigKeyRegistry.java:545` 键说明。
- 测试/证据侧：`NearDupFusionImpactAnalyzer` / `NearDuplicateBandAnalyzer`（生产视图改委托闸门）/ `ImageSimilarityThresholdGateTest` / `NearDuplicateBandAnalyzerTest` / `ImageNearDuplicateCalibrationAnchorTest` 消息 / 五处守卫比对口径 / 两个决策测试的自述串。
- 新守卫 `NearDupFloorSignoffGuardTest`（≥8 条，含实算 SHA）。
- 新锚 `near-dup-floor-signoff-v1.json`（LF）/ `.md`。
- 文档：`docs/dynamic-config-keys.md`、`ImageSimilarityGate` 与 `KnowledgeRetrievalServiceImpl` javadoc、签收页 §6。
- `scripts/test-baseline.txt`（仅经 `--update`）。
