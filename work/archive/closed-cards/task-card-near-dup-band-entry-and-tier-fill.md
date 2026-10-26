# 任务卡 · near-dup-band-entry-and-tier-fill（卡 32 · A 档 · 两腿一卡）

## §1 目标

- **腿 A（花钱，≤36）**：用**几何编辑**（裁切边距 2/4/6%）把样本真正撞进降权档 `[0.982, 0.9944)`，再看降权搬不搬名次。卡 31 用质量型变形全落带外（`cos ≥ 0.997653`），而 S0 探针实测 `crop-04` 的 `cos = 0.9934` **在带内**——这是全仓唯一有实测证据的入口。
- **腿 B（零外呼）**：把「档位被什么填满」盘成一张可复算的表（396 对变体×第三图，含 14 对带内），并给 0.90 议题列**选项与代价**（不给推荐值）。

## §2 背景（三条必须记住的教训）

1. **卡 26 的死法**：48 图一批跑完、**124 次外呼**之后才发现「过闸 41 ≠ embed 36」。已归因 `COUNTER-SUPPRESSED`（测试侧缓存抑制计数）⇒ **分母必须是实测子块行数**。
2. **卡 31 的白花**：30 次外呼，6 个质量型变体全落 `SAME_IMAGE` 档 ⇒ 0 次降权，原问题没碰到。别再试质量型。
3. **板规 26（卡 31 立的）**：`downWeightedCount == 0` 时「无位移」的结论是**恒真**的，不能写 `NO-RANK-CHANGE`。本卡把这条写进判据本体（第 8 条）。

## §3 写集（**只许写这 6 个文件**，无其他写入者）

| 路径 | 动作 |
| --- | --- |
| `src/test/java/com/slz/crm/quality/NearDupS1CropCorpus.java` | 新增：2 base × 3 裁切边距 |
| `src/test/java/com/slz/crm/quality/NearDupBandEntryIT.java` | 新增：腿 A 真跑（opt-in + 写锚） |
| `src/test/java/com/slz/crm/quality/NearDupTierFillAnalysisTest.java` | 新增：腿 B 零外呼盘点 |
| `src/test/java/com/slz/crm/quality/NearDupBandEntryGuardTest.java` | 新增：守卫 |
| `docs/rag-quality/near-dup-band-entry-v1.json` | 新增（写模式生成） |
| `docs/rag-quality/near-dup-band-entry-v1.md` | 新增（≤40 行） |

## §4 禁改面（越界即判死）

- `src/main/**` 一行不改（B 跑用 `mockStatic(CALLS_REAL_METHODS)`）。
- **既有锚（`docs/rag-quality/*.json` 全量）零字节变动**：尤其 `near-dup-ingest-s1-v1.json`（卡 31）、`near-dup-ingest-s1-not-run-v1.json`（卡 26）、`near-dup-ingest-gate-probe-v1.json`。
- **既有测试不得修改**：`NearDuplicateCorpus`（变体清单/顺序被 6 份锚引用）、`NearDupS1Corpus`（卡 31 守卫重建语料比对）、`NearDupIngestGateProbe*`、`NearDupIngestS1*`。
- 不改 `pom.xml`、不改 CI YAML、不新增依赖。

## §5 成本铁律

- 腿 A：目标 **30**（6 变体 × 3 = 18 入库；6 查询 × 2 = 12），硬帽 **36**。
- 查询向量**只嵌一次、A/B 复用**（各嵌一遍 = 24 次 ⇒ 总 42，直接超帽）。
- 回改边距参数重跑 = 挑样本，判死；追加调用须新授权新卡。

## §6 验收（摘要）

1. 逐图 R2 等式；分母 = 实测子块行数；`rowIndex == null`。
2. `verdict` 与 `downWeightedCount` 联动三分支（判据本体，不许事后解释）。
3. 腿 B 表每行可指回源锚字段；**腿 B 不出现推荐阈值**（守卫 grep 断言）。
4. 锚 LF、默认复跑逐字节一致（剔除 `runtime`）、`callLedger.total ≤ 36`。
5. 既有锚与禁改文件零字节变动。

## §7 停手条件

- R2 等式不成立 ⇒ **立即 abort**，判 `INCONCLUSIVE` + 落缺口与已花调用。
- 裁切后短边 <64px 且换更小边距仍不足 ⇒ 停手上报。
- 6 个变体全部落带外 ⇒ 判 `INCONCLUSIVE`（`missingEvidence = NO-IN-BAND-CANDIDATE`），**不许**转质量变形再花一轮。
- `mockStatic` 不可用 ⇒ 停手上报，不得改 `src/main` 加开关。

## §8 回报（≤300 字）

提交 SHA / 文件数 / 外呼三分项与总数 / 6 变体 `bandSide`+`cos` / `downWeightedCount` / `verdict` / 名次有无变化（附 `gap`）/ 腿 B 表的带内对数 / 未解决项 / 待决策项。

**「有位移 / 无位移」必须同回合附 `downWeightedCount`；「进带 / 没进带」必须附 `bandSide` 逐条；否则不许写。**

## §9 细节留档位

执行者在此节追加：语料参数与 base 选择实测、逐图对账表、逐查询候选与分数、腿 B 表的源字段路径、abort/异常记录、任何偏离 proposal 的选择与理由。

### 卡 32 执行留档（2026-10-26，feature/near-dup-band-entry-and-tier-fill）

**语料与参数实测**

- base 与卡 31 完全同序同口径：`nist-sp800-101r1-p1-b1`（490×225）+ `nist-sp800-101r1-p16-b2`（923×453）；裁切边距 0.02/0.04/0.06 写死（`NearDupS1CropCorpus.java:57-63`），梯子（步进 0.01 下探）未触发，6 变体 param 全为 m0.02/m0.04/m0.06。
- 裁切后短边最小 207px（p1-b1@crop-06：490-2×29=432、225-2×14=207），远高于 64px 下限。

**腿 A 逐变体落点（锚 bandSides）**

| 变体 | bandSide | cos（3 查询恒等） | tier / band |
| --- | --- | --- | --- |
| p1-b1@crop-02 | IN-BAND | 0.993354 | NEAR_DUPLICATE / NEAR_HIGH |
| p1-b1@crop-04 | IN-BAND | 0.993401 | NEAR_DUPLICATE / NEAR_HIGH（复现 S0 探针 0.9934） |
| p1-b1@crop-06 | IN-BAND | 0.990735 | NEAR_DUPLICATE / NEAR_MID |
| p16-b2@crop-02 | ABOVE | 0.994625 | SAME_IMAGE（带外照记） |
| p16-b2@crop-04 | IN-BAND | 0.988904 | NEAR_DUPLICATE / NEAR_LOW |
| p16-b2@crop-06 | BELOW | 未召回（cos null） | 低于 floor 被闸（带外照记） |

- 边距单调性成立（同 base 边距越大 cos 越低）；p1-b1 三档全进带、p16-b2 跨越整条带（ABOVE→IN-BAND→BELOW），扫描设计达到预期。
- **downWeightedCount=12**（4 带内变体 × 各自 base 3 查询）；名次面 anyImageRankChange=false、6 查询 chunkIdSetEqual 全 true ⇒ **verdict=NO-RANK-CHANGE（dwc>0 分支）**，missingEvidence=null；分数面 maxImageScoreDelta=0.075（=0.30×(1−0.75)）、imageDeltaSum=0.4836。
- 窗口算术 12 条全录：4 条 gap ≤ window（flip 可能位，全在 p1-b1@crop-02：gap 0.000943/0.005159/0.017033 vs window 0.075）但未翻转——其紧邻下位竞争者同为被降权候选（同步下沉），静态 gap（B 跑口径）不反映该协同；其余 8 条 gap > window 或无下位竞争者。
- 外呼台账：vision 6 + embedImage 12 + embed 12 = **total 30**（目标 30、硬帽 36，恰不超）；授权口径 Owner 2026-10-26 授权（含更强变形）；运行时视觉模型 qwen3-vl-plus（runtime 块留档，replay 剔除）。

**腿 B 源字段路径（锚 legB）**

- tierFill：`image-variant-cross-matrix-v1.json#vectors`（144×1024 大端 base64）复算 own-base 120 对 + third-image 2760 对；档位 `ImageSimilarityGate.tierOf/bandOf(DEFAULT_THRESHOLD)` 现算；totals：现行带内 22 对、≥0.9887 恰 14 对。
- thresholdOptions：ownBase 侧 `near-dup-exchange-rate-v1.json#baseline`（0.9887 行）与 `#grid.items[floor=0.9]`（0.90 行）；third-image 侧向量复算（13→93，新增 80/2760）；代价两行指回 grid.items[floor=0.9].R 与 .extraSuppressedSameDoc。
- **口径澄清（偏离登记，非判据修订）**：proposal §2/R4 括注「396 对变体×第三图」实测核对为源锚 **M2 偏差对账对数**（near-duplicate 120 + same-class 100 + cross-class 176 = 396，见 `image-variant-cross-matrix-v1.md:23`），变体×第三图全域实为 **2760 对**（`variantVsThirdImage.count`）；「14 对带内」按源锚口径为 cos ≥ 0.9887（当时 floor）恰 14 对，已复核一致。本卡按 R4 语料域（编辑类型 × 变体×第三图）如实取 2760 并在锚 `pairCountClarification` 注明。

**过程记录（含一次零成本失败）**

- 首跑尝试（`%TEMP%/near-dup-band-run.log`）：`-Dtest=NoSuchTestAnywhere` 触发 surefire「No tests matching pattern」在测试执行前失败——**零外呼、锚未写**（构建止于 surefire 阶段）；补 `-Dsurefire.failIfNoSpecifiedTests=false` 后单次真跑成功（`near-dup-band-run2.log`），未动用修复预算的第二次真跑。
- 装置复用说明：`NearDupBandEntryIT` 只读复用卡 31 `NearDupIngestS1IT` 的冻结嵌套类（TrackingProvider / StubEmbeddingProvider / CapturingInMemoryStore / RecordingTokenRecorder / StoredRecord / FrozenIngest / Material / SourceRef / PassEvidence）与 `resolveApiKey`，`NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*` 零字节改动。
- 验证链实测：靶向 surefire（TierFill+Guard）8/8 绿（真跑前 4+4、真跑后锚对账全量 4+4）；真跑 1/1 绿；默认复跑（opt-in 无 write）1/1 绿 + 逐字节一致 + 零外呼；锚纯 LF（226264 字节无 CR）；md 24 行。

**未解决项**：无。**待决策项**（Owner）：NEAR_DUPLICATE_FLOOR 的 0.90 议题（腿 B 只给选项与代价：own +43/120、third +80/2760、多压 6 张同文档图、R 膝盖点 113/120=0.941667），变更须另卡。
