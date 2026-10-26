# near-dup-band-entry-v1 · 带内入口几何扫描 + 档位填充盘点锚（卡 32，两腿一卡）

方法：腿 A 真跑 30 外呼（Owner 2026-10-26 授权（含更强变形），硬帽 36）。`NearDupS1CropCorpus` 只读复用卡 31 同序 base（nist-sp800-101r1 生成序前 2 个短边 ≥64px：p1-b1 490×225 / p16-b2 923×453），每 base 3 个**纯几何**裁切变体（边距 2%/4%/6%，D1：卡 31 已证质量型全落带外），逐张真走「合成单图 PDF → 生产提取器 → VLM 描述 → 质量闸门 → 落盘 → 生产 chunkEmbedAndWrite → 视觉向量 flush」；6 查询（2 base × 3 形态，复用卡 31 冻结形态规则）向量只嵌一次，A 跑（生产降权）与 B 跑（mockStatic 恒等替换）复用。腿 B 零外呼，纯冻结锚复算。

## 腿 A 结论

- **几何入口成立**：6 变体 4 个落带内（bandSide=IN-BAND）——p1-b1@crop-02 cos 0.993354（NEAR_HIGH）/ crop-04 cos 0.993401（NEAR_HIGH，复现 S0 探针 0.9934）/ crop-06 cos 0.990735（NEAR_MID）；p16-b2@crop-04 cos 0.988904（NEAR_LOW）。带外 2 个照记：p16-b2@crop-02 cos 0.994625（SAME_IMAGE，ABOVE）、p16-b2@crop-06 未被图像路召回（BELOW）。边距单调性成立：同 base 边距越大 cos 越低。
- **verdict = NO-RANK-CHANGE（downWeightedCount=12 > 0 分支，板规 26 联动合规）**：12 次降权事件（4 带内变体 × 各自 base 的 3 查询）后名次面（anyImageRankChange=false）与集合面（6 查询 chunkIdSetEqual 全 true）均无变化；分数面 maxImageScoreDelta=0.075（=0.30×(1−0.75)），imageDeltaSum=0.4836。
- **窗口算术（12 条全录）**：4 条 gap ≤ window（flip 可能位，集中在 p1-b1@crop-02：gap 0.000943–0.017033 vs window 0.075）但未翻转——其紧邻下位竞争者同为被降权候选（B 跑 fusedB 口径的静态 gap 不反映同步下沉）；其余 8 条 gap > window（含 1 条无下位竞争者）。
- 不可外推：结论只对本构造语料（2 base × 3 裁切变体 × 6 查询、TOP_K=5）与本装配成立，不可外推为「生产无位移」。

## 腿 B 结论（零外呼）

- 档位填充表（编辑类型 × cos(min/median/max) × 档位 × 带内对数，own-base 120 对 + third-image 2760 对，5 编辑类型×两侧全落锚 legB.tierFill）：现行生产带 [0.982,0.9944) 内变体×第三图 22 对；cos ≥ 0.9887 恰 14 对（与源锚 md M3 一致）。proposal R4 括注「396 对」经核对为源锚 M2 偏差对账对数（120+100+176），变体×第三图全域实为 2760 对，已在锚 pairCountClarification 如实注明。
- 0.90 议题只列选项与代价（锚 legB.thresholdOptions）：维持 0.9887 → own-base 保留 70/120、third-image 带内 13/2760、同文档被压 2/100；降到 0.90 → own-base 113/120、third-image 93/2760、同文档 8/100（+6 张）。新增进带：own-base +43/120、third-image +80/2760。已记录代价：R 膝盖点 113/120=0.941667、多压 6 张同文档图（均逐字段指回 near-dup-exchange-rate-v1）。**不给推荐阈值、不改任何常量**；NEAR_DUPLICATE_FLOOR 变更属另卡须 Owner 拍板。

## 对账与成本

- 逐图 R2 等式 6/6 全过（提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片=1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数=1；rowIndex 恒 null；分母=实测子块行数，卡 27 口径）。
- callLedger：vision 6 + embedImage 12（摄取 6 + 查询 6）+ embed 12（子块行 6 + 查询文本 6）= **total 30**（目标 30、硬帽 36；TrackingProvider 真调用点累加并断言与派生口径逐项相等）。
- 默认复跑（opt-in 无 write）：语料重建逐字段一致 → 两集合重建 → A/B 重放 → 腿 B 静态算子重算 → 剔除 runtime 后与磁盘锚**逐字节一致**，零外呼；守卫 `NearDupBandEntryGuardTest`（台账等式、R3 三分支联动重算、窗口算术公式、bandSide 六条、腿 B 逐数字复算 + 独立向量计数抽查、无推荐阈值 grep、上游锚 SHA、runtime 白名单）。
- 边界：src/main 零 diff；既有锚零字节变动；NearDuplicateCorpus / NearDupS1Corpus / NearDupIngestGateProbe* / NearDupIngestS1* 未修改（装置嵌套类只读复用）。运行时视觉模型 qwen3-vl-plus（生产默认 qwen-vl-plus 在本 key 返回 model_not_found，灰卡同款覆盖，runtime 块留档）。

JSON 为唯一数字源（`near-dup-band-entry-v1.json`），本文件不承载独立数字。
