# 近重复消融重设计 v1（near-dup-ablation-redesign · 卡 28 · S0 零外呼）

> **回答的题**：把三档近重复降权（×0.75/×0.6/×0.5）换成不降权，冻结真实语料上的检索顺序变不变？
> 锚：docs/rag-quality/near-dup-ablation-v1.json（sha256:78b249ff11a19b9bdc373c3819e204d79884c7d09f3d9274a02a2829fb57d3eb，纯 LF）。
> 写锚 `-Dnear.dup.ablation.write=true`；默认复跑（无 opt-in 门，CI/verify 真跑）逐字节一致、零外呼。
> 装置：A 跑 = 生产降权（`mockStatic(CALLS_REAL_METHODS)` 下对 `downWeightNearDuplicates` 做「捕获入参→`callRealMethod`→捕获出参」旁路观测，行为=生产）；B 跑 = 同框架只把该方法替换为恒等（逐查询 verify 恰 1 次）；两跑各自从冻结锚 `near-dup-displacement-evidence-v1.json` 的 corpusMaterial 全新装配（7 查询 × TOP_K=5，3 份登记真实 PDF）。守卫 `NearDupAblationGuardTest`：verdict 三选一 + 逐候选表/查询集重算 + `imageDeltaSum` 逐行和（对任一 fused 值手改敏感）+ 分母跨锚重算 + 正对照结论位 + externalCalls==0。

## 1. 装置鉴别力（R1/R2，surefire `NearDupAblationPositiveControlTest`）

直构三候选（NEAR_HIGH 图像 s=1.0 / 文本 s=0.5 / SAME_IMAGE 图像 s=0.98）：B（不降权）该图像候选融合分 0.3 排前（rank 2）、A（降权）(1.0×0.75)×0.3=0.225 排后（rank 3），两次顺序相反；翻转归因 = 降权损失 0.075 > B 跑领先 0.006（0.225s vs 0.3s，逐字节断言）。负对照：文本与 SAME_IMAGE 候选两跑融合分逐字节相等。窗口 0.30×(1−w)=0.075/0.12/0.15 与融合影响锚 bounds 一致（1e-12）。

## 2. 真语料 A/B 实测（R3/R4/R5）

- **分母（实测，禁推导）**：childRows=50（图片切片行 7 + 文本块行 43，逐条 chunkId 落锚）；embedInvocations=11（A 跑重放链真实到达 provider 的 embed 请求实计，按未命中计；B 跑实测同值断言相等；两数口径不同——库行面 vs 单轮查询嵌入面，非推导）。
- **降权事件**：downWeightedCount=2，恰为两张 NIST 封面互查事件（q0: IMAGE:9415、q3: IMAGE:9400，均 NEAR_DUPLICATE+NEAR_LOW，w=0.5 取自 A 跑出参 BAND_WEIGHT_METADATA_KEY 实测标记）。
- **分数**：被降权候选 fusedA=0.76 / fusedB=0.82，maxImageScoreDelta=0.060（imageDeltaSum=0.12 恰为两事件之和）；其余全部 IMAGE 候选与全部非 IMAGE 候选两跑融合分逐字节相等（maxTextScoreDelta=0.0，R3 隔离：消融只动降权一行）。
- **名次与集合**：anyImageRankChange=false、全部 chunkIdSetEqual=true（imageCandidateCount=23）。

## 3. verdict（R6 机械推导）= RANK-CHANGE

任一 IMAGE 候选融合分变化即 RANK-CHANGE（分数面触发）；名次面与集合面实测零变化。窗口算术（逐降权事件，锚 windowCheck 逐条）：gap=0.3856 vs window=0.30×(1−0.5)=0.15，gap 全部 > window ⇒ 降权损失不足以跨越下位竞争者，名次不变是窗口算术保证的必然而非巧合。**不可外推为「生产无位移」**：结论只对本冻结语料（7 查询/TOP_K=5/本装配）成立；S1 取舍按 proposal §6 预注册语义由 Owner 裁定，本锚不夹带修复建议或阈值主张。
