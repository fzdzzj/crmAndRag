# near-dup-ingest-s1-v1 · S1 窗内共现消融锚（卡 31）

方法：真跑 30 外呼（Owner 2026-10-25 报批 36）。`NearDupS1Corpus` 本地构造 2 base（nist-sp800-101r1 生成序前 2 个短边 ≥64px：p1-b1 490×225 / p16-b2 923×453）× 3 变体（jpeg-95 / jpeg-85 / scale-95），逐张变体真走「合成单图 PDF → 生产提取器 → VLM 描述 → 质量闸门 → 落盘 → 生产 chunkEmbedAndWrite（子块行 embed）→ 视觉向量 flush」；6 查询（2 base × 3 形态：title / first-sentence / caption-keywords，文本取自该页 PDFBox 提取，零文本外呼）向量只嵌一次，A 跑（生产降权）与 B 跑（mockStatic 恒等替换）复用同批向量。

## 结论

- **verdict = NO-RANK-CHANGE（构造失效面）**：6 变体全部落带外（bandSide=ABOVE，tier=SAME_IMAGE，cos 0.997653–0.999956 均 ≥ 阈值 0.9944）⇒ 0 次降权事件（downWeightedCount=0，windowCheck.entries=0 与之一致）、名次面与集合面均无变化。D3 预注册路径：如实判 NO-RANK-CHANGE + constructionFailure 落锚，不追加调用换参数。
- 逐变体 cos（对各自 base 的 3 查询恒等，embedImage 同输入确定性）：p1-b1 jpeg-95 0.999956 / jpeg-85 0.999633 / scale-95 0.997653；p16-b2 jpeg-95 0.999501 / jpeg-85 0.999283 / scale-95 0.999794。最接近带界的是 p1-b1@scale-95（0.997653 > 0.9944）：三档变形强度均不足以把同源图推出同图档。
- **窗口算术**：本跑无被降权候选，无 gap 可记（windowCheck.entries 恰等于 downWeightedCount=0）。「撞上了会怎样」在本构造参数域内未被触及——该问题仍开放，需更强变形（预计另卡）。
- 不可外推：结论只对本构造语料（2 base × 3 变体 × 6 查询、TOP_K=5）与本装配成立，不可外推为「生产无位移」。

## 对账与成本

- 逐图 R2 等式 6/6 全过：提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片=1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数=1（分母为实测子块行数，卡 27 COUNTER-SUPPRESSED 口径；rowIndex 恒 null）。
- callLedger：vision 6 + embedImage 12（摄取 6 + 查询 6）+ embed 12（子块行 6 + 查询文本 6）= **total 30**（目标 30、硬帽 36，实测计数由 TrackingProvider 真调用点累加并断言与派生口径逐项相等）。
- 默认复跑（opt-in 无 write）：语料重建与冻结面逐字段一致 → 两集合重建 → A/B 重放 → 剔除 runtime 后与磁盘锚**逐字节一致**，零外呼；守卫 `NearDupIngestS1GuardTest` 5/5（台账等式、逐变体七链自洽、verdict 与逐候选表重算、bandSide 六条、三值可达路径、上游锚 SHA 实算）。
- 边界：src/main 零 diff；既有 8 锚零字节变动；NearDuplicateCorpus / NearDupIngestGateProbeIT / NearDupIngestGateProbeGuardTest 未触碰。

JSON 为唯一数字源（`near-dup-ingest-s1-v1.json`），本文件不承载独立数字。
