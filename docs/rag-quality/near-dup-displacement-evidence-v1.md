# 近重复位移证据 v1（near-dup-missing-evidence · G1 真跑 + G3 收口）

> **性质：存在性/量级证据，不是分布。** 7 条真实混合查询（3 份登记真实 PDF 的 7 个生产切片），落锚
> docs/rag-quality/near-dup-displacement-evidence-v1.json（sha256:5444719a9ff67ee696cb01533eb624821ba09530a5b87069a9c8fb62b0c9ad9b）。
> 真跑 opt-in：`RAG_NEAR_DUP_DISPLACEMENT_REAL=1` + `DASHSCOPE_API_KEY`（visionModel=qwen3-vl-plus、text-embedding-v3、multimodal-embedding-v1）；
> 写锚 `-Dnear.dup.displacement.write=true`；默认复跑（opt-in 无 write）从锚内冻结向量本地重放生产检索链，零外呼，序列化与磁盘锚**逐字节一致**（复跑已验证）。
> 逐条判据 D1–D5 / M1–M4 原文落锚 `criteria`；守卫 `NearDupDisplacementEvidenceGuardTest`（SHA 实算 + verdict 越界 + externalCalls=0 + W_max 自洽 + 判别函数禁 route）。

## 0. 观测面（上一卡缺口的补法）

上一卡 `ProductionGrayRealRunIT` 的视觉路与文本路是两个独立检索服务（视觉路的文本集合为空），从未有一次调用同时产出 TEXT 与 IMAGE 候选——共现观测位点不存在。本卡把**同一个**生产 `KnowledgeRetrievalServiceImpl` 同时装上文本集合与图片集合，每条查询**一次** `retrieve(queryText=VLM 描述, imageVector=落盘 PNG 重编码)`（两参同给，禁止两次独立 retrieve 拼凑），读 `sources()` 逐条落 `(sourceType, relevanceScore, chunkId)`。

- 语料基座 = 既有 3 份登记真实 PDF（NIST SP 800-101r1 85 页 / SP 800-183 30 页 / DOE 42 页），不下载新资产；生产切片链出 7 个图片切片（MAX_IMAGES_PER_DOCUMENT=3 成本帽 + 描述质量闸门，9 张提取 7 张入切片）。
- 文本集合 = 图片切片 VLM 描述块（生产 `createVectorRecord` 同形，`sourceType=IMAGE`）+ PDF 正文逐页文本块 36 块（PDFBox 测试侧抽取，成本帽 12 块/文档 → `sourceType=pdf`，即非 IMAGE 竞争者的来源）。
- 融合 = 生产口径 0.7/0.3 分数制同切片累加，降权在融合前；图像路 floor=0.9887、`relevanceScore` 即融合后分。
- **模态判别只认 `sourceType`**（`route` 取值域是 `vector|keyword|hybrid|ocr`，全仓 3 处赋值点均非模态：`SourceReferenceFactory` 两处硬编码 `"hybrid"`、`BusinessRetrievalService` 一处 `"vector"`；守卫读 IT 源码断言判别函数体不引用 `route`）。
- externalCalls=**77**（vision 9 + embedImage 14 + 文本 embed 54），`TrackingProvider` 真调用点计数，非公式推导。

## 1. 实测（D1 共现 + D2 分差 + D3 窗口判定）

**D1 共现：6/7 查询共现**（一次调用同时返回 ≥1 条 `sourceType=IMAGE` 与 ≥1 条非 IMAGE 来源）。`cooccurrenceQueryCount=6 / queryCount=7`。唯一不共现的是 queryIndex=1（其 top-5 全为 IMAGE 来源，文本竞争者未进 top-5）。

**D2 分差**（图像候选分 − 比它低的最大竞争者分；逐查询，末位记 null）：

| queryIndex | 资产/页 | 共现 | downwardGap | 备注 |
| --- | --- | --- | --- | --- |
| 0 | nist-101r1 p1 | ✓ | 0.24 | 两张 NIST 封面互为带内对（定标锚 cross-class 0.988736 同源），被降权方融合分 0.76，仍在窗外 |
| 1 | nist-101r1 p16 | ✗ | 0.0138 | 本查询无文本竞争者（top-5 全 IMAGE）；gap 为 IMAGE↔IMAGE 邻接 |
| 2 | nist-101r1 p18 | ✓ | **0.0094** | 全轮最小；IMAGE:9416 融合分 0.1810 vs pdf 竞争者 0.1716 |
| 3 | nist-183 p1 | ✓ | 0.24 | 同 queryIndex=0 的另一侧 |
| 4 | nist-183 p14 | ✓ | **0.0098** | |
| 5 | nist-183 p16 | ✓ | **0.0457** | |
| 6 | doe p1 | ✓ | **0.0319** | |

- 逐查询口径（tasks.md 1.4「逐查询算 downwardGap」）与共现查询子集口径给出**同一个** `minDownwardGap = 0.00941087658500056`（全轮最小恰来自共现查询 2），两读法结论一致。
- **D3 窗口判定**：`W_max = 0.30 × (1 − 0.5) × 1.0 = 0.15`。`minDownwardGap = 0.0094 ≤ 0.15` ⇒ **`verdict = POSSIBLY-DISPLACED`**，窗口内共现查询 4 条（queryIndex 2/4/5/6，gap 0.0094/0.0098/0.0457/0.0319），锚 `withinWindow` 逐条列出（另含 queryIndex 1 的非共现 IMAGE↔IMAGE 条目，已在表内标注）。
- 实测含义：跨路位移在本语料上**实际可辨认地逼近发生**——4 条共现查询的图像候选与下方竞争者的融合分差全部落在降权窗口内，其中最小 0.0094，比窗口小一个数量级。两张 NIST 封面（queryIndex 0/3）是现成的带内降权实例：变体查询召回对方切片，gate 降权后融合分 0.76，与己方 1.0 分差 0.24，恰在窗外——降权本身在工作，但没有把任何候选压到竞争者之下。

## 2. 反事实重建（D4，单独字段，禁与实测并表）

把定标锚 `near-duplicate` 档 120 个 cos 代入 `0.30 × cos × w`（三档 w=0.75/0.6/0.5），与同查询实测非 IMAGE 竞争者最大分比较：

| 档位 w | 位移对数（共 2160 组合） |
| --- | --- |
| 0.75 | 365 |
| 0.6 | 502 |
| 0.5 | 612 |
| 合计 | **1479 / 2160（68.5%）** |

`assumption`（锚内原文）：**「竞争者分差跨查询可迁移」是假设，不作因果结论；本节是反事实重建（D4），不是实测（D2/D3）**。即：若图像候选真是带内近重复（cos 取锚内 120 档值）并被降权，其在 68.5% 的 (cos, w) 组合下会跌到本语料实测竞争者之下——位移窗口不是纸面上界，是共现查询里真实逼近的量级。

## 3. G3 收口：上一卡三项 missingEvidence 逐条三态

| # | missingEvidence（fusion 锚原文） | 状态 | 依据 |
| --- | --- | --- | --- |
| ① | 真实查询的文本路 rerank 分数分布——判定融合分差 ≤ bound 的竞争者是否存在 | **已补（存在性/量级口径）** | 本锚 `queries[].sources[].relevanceScore`（融合后分实测）+ `minDownwardGap=0.0094` + `verdict=POSSIBLY-DISPLACED`；sha256 5444719a…ad9b。边界：7 条查询的存在性证据，**不是**分布——分布须另立卡（72 例基准套件检索端口是否生产路未验） |
| ② | 真实查询的图像候选与文本候选共现观测（A2 共现面） | **已补** | 同锚 `cooccurrenceQueryCount=6/queryCount=7`、`withinWindow` 4 条共现查询落窗口；共现观测位点从「不存在」变为「已实测」 |
| ③ | 变体↔第三图视觉余弦——R4 竞争者以 base 对为代理，未经验证 | **已补（代理判为低估）** | docs/rag-quality/image-variant-cross-matrix-v1.json（sha256 3adde4ec…319b）：144 图单编码全矩阵，`maxAbsDeviation=0.0`（对账 PASS），`proxyVerdict=PROXY-UNDERSTATES`——存在 14 对带内变体↔第三图对（max=0.9973），base 对代理**低估**带内竞争者，R4 的进入带样本数偏少 |

三项均「已补」，无「仍有界」、无「仍缺」。第 2 条查询-相关性标注（签收页 §4 第 2 条）不在本卡范围，维持原状。

## 4. 边界（D5，禁外推）

- `queryCount=7` 条量级 = **存在性/量级**证据：它证明「共现存在、分差可进入窗口、代理低估」，**不证明**「生产上位移不可见」或其反面；把 7 条当分布是越权解读。
- 反事实重建依赖「竞争者分差跨查询可迁移」假设（见 §2），不得当因果结论引用。
- 本卡零生产代码、零常量改动：`0.9944 / 0.9887 / 0.75|0.6|0.5 / 0.70 / 0.30` 逐字未动，改档属新卡（签收页 §6）。
