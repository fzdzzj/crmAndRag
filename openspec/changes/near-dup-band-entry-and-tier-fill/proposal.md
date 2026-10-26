# proposal · near-dup-band-entry-and-tier-fill（档位入口几何扫描 + 档位填充盘点，两腿一卡）

> 卡 32。**A 档（真跑 + 外呼）**，但**只有腿 A 花钱**（目标 30 / 封顶 36），腿 B 零外呼。
> 零迁移 / 零契约 / 零权限 / `src/main` 零 diff / 既有锚零字节变动 / **不改任何阈值常量**。

## §1 问题陈述

卡 31 花了 30 次外呼，证明了**质量型退化进不了降权档**（`jpeg-95`/`jpeg-85`/`scale-95` 的 `cos` 全在 `0.997653–0.999956`，落 `SAME_IMAGE`），于是「撞上了会怎样」这个问题**没被触及**。

但 S0 探针早就给了一条反证：**几何型**变形 `crop-04` 的 `cos = 0.9934`，**恰在 `[0.982, 0.9944)` 带内**（`near-dup-ingest-gate-probe-v1.md:38`）。也就是说：档位的真实入口是**内容平移**，不是质量损失。

⇒ 本卡两腿，一卡答两个问题：

- **腿 A（外呼，≤36）**：把入口对准几何编辑，用边距扫描去**真正撞进带内**，然后看降权搬不搬名次。这是卡 31 未完成的事，但**先验比上次强**——`0.9934` 是实测值，不是猜测。
- **腿 B（零外呼）**：把「档位到底被什么填满」这件事一次性盘清——用已冻结的 396 对变体×第三图相似度（含 14 对带内）产出「编辑类型 × `cos` 区间 × 命中档位」表，并给 0.90 议题列出**选项与代价**（**不给推荐阈值**，改常量属另卡）。

## §2 侦察已得事实（执行者不必重查）

- **带内入口已有实测证据**：`crop-04`（4% 边距裁切）对 base 的 `cos = 0.9934` ∈ `[0.982, 0.9944)`（`near-dup-ingest-gate-probe-v1.md:38`，S0 探针带内对 `p1-b1 ↔ p1-b1@crop-04`）。这是全仓**唯一**被实测证实能落进降权档的构造方式。
- **质量型已被证伪**：卡 31 三种变体全 `ABOVE`（`0.997653–0.999956`，锚 `near-dup-ingest-s1-v1.json`）。再试质量型是重复劳动。
- **腿 B 的数据源已冻结**：`image-variant-cross-matrix-v1.json` 含 `matrix` / `selfCosines` / `variantVsThirdImage`（396 对，其中 **14 对带内**）/ `vectors`，字段全在，**零外呼可复算**。
- **裁切常量现成**：`NearDuplicateCorpus` 有 `CROP_04_MARGIN = 0.04`（`:72`）与 `crop(source, margin)`（`:295`）；边距扫描只需自己算尺寸并复用提取后的 `BufferedImage`，不需要新的图像库。
- **两类不许改**（都已被冻结锚的 replay 引用）：`NearDuplicateCorpus`（变体清单与顺序被 6 份锚引用）、`NearDupS1Corpus`（卡 31 的 `near-dup-ingest-s1-v1` 守卫会重建语料逐字段比对）。**新语料必须另立类。**
- **板规 26 首次作为判据本体落地**：卡 31 的 `NO-RANK-CHANGE` 在 `downWeightedCount == 0` 时恒真，当时是事后修订；本卡把「`verdict` 必须与 `downWeightedCount` 联动」**写进 R3 本体**，不再靠修订记补。

## §3 判据（R0–R8）

- **R0 预算硬帽 + 逐图 fail-fast（沿用卡 31 装置）**：`CALL_CAP = 36` 在测试内实现，超帽即抛；每张变体入库后立即校验 R2 等式，不成立立即 abort 并落账。**批处理前先跑第一张**——第一笔钱花完就要能判断这批参数有没有希望。
- **R1 腿 A 语料（几何扫描）**：取 `nist-sp800-101r1` 语料生成序前 2 个「短边 ≥64px 且非纯色/低色数」的 base，每 base 造 **3 个裁切变体**：边距 `2%` / `4%` / `6%`（**几何编辑，不做质量重压**）。新类 `NearDupS1CropCorpus`，**不得修改** `NearDuplicateCorpus` / `NearDupS1Corpus`。裁切后短边必须仍 ≥64px（本地像素断言，不足即换更小边距而不是弃图）。边距参数**先写死并落锚**，看到分数后不得回改（回改即挑样本）。
- **R2 腿 A 入库对账（逐图等式）**：`提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片≥1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数`；图片切片 `rowIndex == null`；**分母一律用实测子块行数**，锚内禁出现推导量。
- **R3 腿 A A/B 与判定（板规 26 落地）**：查询构造规则先钉死：**2 base × 3 形态**（标题式 / 正文首句 / 图注关键词组），文本取该页已提取文本，图像分量 = base 位图；查询向量**只嵌一次、A/B 复用**（各嵌一遍 24 次 ⇒ 总 42 超帽）。A 跑 = 生产降权；B 跑 = `w=1`（`mockStatic(CALLS_REAL_METHODS)`，`src/main` 零 diff）。逐查询落 `{候选集, cos, fusedA, fusedB, rankA, rankB, downWeightedKeys, gap}`。
  **`verdict` 与 `downWeightedCount` 强制联动**：
  - `downWeightedCount == 0` ⇒ **只能** `INCONCLUSIVE`（附 `missingEvidence = NO-IN-BAND-CANDIDATE`）；
  - `downWeightedCount > 0` 且名次或集合有变化 ⇒ `RANK-CHANGE`（必附逐事件 `gap` vs `0.30×(1−w)`）；
  - `downWeightedCount > 0` 且两者均无变化 ⇒ `NO-RANK-CHANGE`（必附 `windowCheck` + 不可外推声明）。
- **R4 腿 B 档位填充盘点（零外呼，只统计不推断）**：从 `image-variant-cross-matrix-v1`（396 对 / 14 带内）、`near-dup-exchange-rate-v1`、`near-dup-ingest-gate-probe-v1` 汇总，产出表：**编辑类型 × `cos` 区间（min/median/max）× 命中档位（SAME_IMAGE / 三档 / 准入线以下）× 带内对数**；逐条给出 `cos` 与来源锚（可 grep）。**禁**在此表上做因果推断或阈值主张。
- **R5 腿 B 的 0.90 议题只给选项与代价**：列出「维持 0.9887 / 降到 0.90」两种口径下**新增多少对进带**（用 R4 的表算，标明分母）、以及已记录的代价（`R=94.17%` 膝盖点、多压 6 张同文档图）。**不给推荐阈值、不改任何常量**；`NEAR_DUPLICATE_FLOOR` 的变更属另卡，须 Owner 拍。
- **R6 锚与守卫**：锚 `docs/rag-quality/near-dup-band-entry-v1.json`（**LF**，写模式 `-Dnear.dup.band.entry.write=true`，默认逐字节复算）+ `.md`（≤40 行）；守卫 `NearDupBandEntryGuardTest`（零外呼）钉死：台账等式、R3 的联动三分支、R4 表的每个数字可复算、**腿部 B 不许出现推荐阈值**（grep 断言）。
- **R7 边界**：`src/main` 零 diff；**既有锚全量**（`docs/rag-quality/*.json`，含 `near-dup-ingest-s1-not-run-v1`、`near-dup-ingest-s1-v1`、`near-dup-ingest-gate-probe-v1`）**零字节变动**；`NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*` **不得修改**；不改 `pom.xml`、不改 CI YAML。
- **R8 成本台账**：锚内 `callLedger{vision, embedImage, embed, total, cap}`，`total ≤ 36`，`total = 三分项之和`；`authorization = "Owner 2026-10-26 授权（含更强变形）"`。

## §4 交付物

- 新语料：`src/test/java/com/slz/crm/quality/NearDupS1CropCorpus.java`（2 base × 3 裁切边距）
- 新 IT：`src/test/java/com/slz/crm/quality/NearDupBandEntryIT.java`（腿 A 真跑，opt-in + 写锚）
- 新 surefire：`src/test/java/com/slz/crm/quality/NearDupTierFillAnalysisTest.java`（腿 B 零外呼盘点）
- 新守卫：`src/test/java/com/slz/crm/quality/NearDupBandEntryGuardTest.java`
- 结论锚：`docs/rag-quality/near-dup-band-entry-v1.json` + `.md`（≤40 行）

## §5 成本

**腿 A：30 次外呼目标、36 封顶**（18 入库 + 12 查询）；**腿 B：0 次外呼**（纯冻结锚复算）。0 迁移、0 契约、0 权限、0 新依赖。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1 只用几何编辑**：卡 31 已证质量型三种全落带外，重复即浪费预算。若裁切扫描仍全落带外，如实判 `INCONCLUSIVE` 并记录 `missingEvidence`，**不许**转向质量变形再花一轮。
- **D2 边距 2/4/6% 先写死后测量**：看到分数回改参数 = 挑样本，判死；追加调用须新授权新卡。
- **D3 带外样本照记**：逐变体 `bandSide` + `cos` 全落锚，`verdict` 只用带内推导。
- **D4 腿 B 只做描述统计**：不给阈值推荐、不做因果断言；表里每个数字必须能指回源锚的字段路径。
- **D5 R2 等式不成立 ⇒ 立即 abort**，判 `INCONCLUSIVE` 并落缺口（卡 26 的死法）。

## §7 与卡 26/27/28/31 的关系

- 卡 26 建装置、124 次外呼撞上对账缺口 → 卡 27 归因 `COUNTER-SUPPRESSED` 并给出正确分母 → 卡 28 零外呼 S0 判 `NO-RANK-CHANGE` 但标注真实语料无窗内共现 → 卡 31 真跑 30 外呼，质量型变形全落带外，**原问题未触及**。
- 本卡是这条线的收口尝试：腿 A 用唯一有实测证据的入口（crop 0.9934）再撞一次；腿 B 把「档位被什么填满」从散落的探针结论升成一张可复算的表。**无论腿 A 成败，腿 B 都交付。**

## §8 判据修订记

- （M1 前如需修订在此记录；本卡已把板规 26 前置写进 R3 本体，预期无需事后修订）
