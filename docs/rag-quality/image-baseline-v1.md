# image-baseline-v1 · 图文跨模态召回质量基线（IMAGE-SUITE-1.0）

> 落锚物：`docs/rag-quality/image-baseline-v1.json`（本文件名固定，勿改；改动一律新增 `image-baseline-v2.json`）
> 变更卡：`openspec/changes/image-cross-modal-eval/`（A 档·真跑落锚）
> 生成：`ImageCrossModalBenchmarkIT`（`RAG_VISION_EMBED_REAL=1` + `DASHSCOPE_API_KEY` 显式 opt-in 真跑）

## 1. 一句话结论（2026-10-21 实测）

同一批图文语料上，**真视觉 query-by-image 的判别间隔是纯文本降维路的 2.6 倍**（0.505 vs 0.194），
**异图误召率 0% vs 60%**；两路同图召回都是 100%。即：`multimodal-shared-space` 引入的真视觉路
在"同图能召回"之外，还额外拿到了文本降维路拿不到的**视觉判别力**。

| 指标（5 条用例 IM-01~IM-05） | 真视觉 `vision-query-by-image` | 文本降维 `text-caption-baseline` | 差值 |
| --- | --- | --- | --- |
| 同图召回率（余弦 ≥ 0.90） | **1.00** | 1.00 | 0.00 |
| 异图误召率（余弦 ≥ 0.90） | **0.00** | 0.60 | **−0.60** |
| 同图余弦均值 | 1.000 | 1.000 | 0.000 |
| 异图余弦均值 | **0.495** | 0.806 | −0.311 |
| 判别间隔均值（同图 − 异图） | **0.505** | 0.194 | **+0.311** |
| 逐条判据通过率 | **1.00** | 0.40 | +0.60 |
| 检索层同图召回率（topK=5） | 1.00 | 1.00 | 0.00 |

`Report`（独立版本号 `suiteVersion=IMAGE-SUITE-1.0`）判卷：`recall@5=1.00`、`MRR=1.00`、`hitRate=1.00`、
`meanPrecision@5=0.20`（唯一黄金在 5 条候选里 → 1/5 上限）、`citationPrecision=0`（纯检索判分，不供答案引用，同
`customer-baseline` 先例）。逐条余弦明细见 JSON 的 `vision.cases[*]` / `textBaseline.cases[*]`：

| 用例 | 查询图 | 异图 | 真视觉 同/异 | 文本路 同/异 |
| --- | --- | --- | --- | --- |
| IM-01 | flow-blue | bars-blue | 1.000 / 0.547 | 1.000 / 0.81（误召） |
| IM-02 | bars-blue | flow-blue | 1.000 / 0.547 | 1.000 / 0.81（误召） |
| IM-03 | flow-orange | bars-blue | 1.000 / 0.527 | 1.000 / 0.80（误召） |
| IM-04 | table-scan | cards-green | 1.000 / 0.671 | 1.000 / ~0.80（误召） |
| IM-05 | cards-green | blank-white | 1.000 / 0.184 | 1.000 / 低（易负例） |

## 2. 判据与口径（Owner 2026-10-21 三项拍板）

1. **形态 = 独立套件**：`ImageCrossModalSuite`（`IMAGE-SUITE-1.0`，独立编号 IM-01~IM-05），不并入
   `RagBenchmarkSuite` 72 例、不改 `SUITE_VERSION="6.0"`、不改既有 `Category.IMAGE` 6 例语义。历史基线
   baseline-v1~v6 因此零影响（本卡只新增本文件与 `image-baseline-v1.json`）。
2. **判据 = 同/异图余弦判别**：逐条 `pass = 同图余弦 ≥ 0.90 ∧ 异图余弦 < 0.90 ∧ 同图余弦 ≥ 异图余弦`
   （阈值 0.90 对齐 `VisionEmbeddingRealIT`）。聚合出「同图召回率 / 异图误召率 / 判别间隔」三项主指标。
3. **真跑落锚 = 授权真跑 + 独立基线**：真视觉 embedding（`multimodal-embedding-v1`）+ 真文本 embedding 外呼；
   opt-in 键沿用 `RAG_VISION_EMBED_REAL`（**不新开键**），无 key 或未 opt-in 时整类 `Assume` 跳过 → 默认 CI/本地
   `mvn verify` 恒零外呼。

**两条路同型**：同一个 `ImageCrossModalEvaluator.evaluate` 同时喂真视觉向量与"描述文本 → 文本向量"，因此上表可直接相减；
差值口径写在 JSON 的 `comparison` 段（正 = 真视觉更好）。

**语义边界（必读，别把本基线读成"视觉一定更好"）**：
- 异图口径固定为「**描述文本同骨架、视觉跨图型**」（流程图 ↔ 柱状图 / 表格 ↔ 卡片看板 / 卡片 ↔ 纯白），在跑之前固定、不按结果调参。
- **仅配色不同、图型相同**的两张图（蓝/橙流程图）真视觉余弦约 **0.98**——跨模态 embedding 主要编码结构而非配色。
  这类负例不是本套件的异图（要求视觉 embedding 分颜色是超出其能力面的期待），但也说明「同图变体」若只是换色会被判为同图。
- 文本降维路的"描述文本"是**确定性文本替身**（VLM 描述文本的等价口径），非当次真调 VLM 生成；见 §4。

## 3. 复现

```bash
# 默认（零外呼）：IT 整类 skip，surefire 侧 8 条单测恒跑（判分阈值边界/套件构造/无 key 跳过/fake 向量端到端）
mvn -B -ntp clean verify

# 真跑落锚（授权后）：写 docs/rag-quality/image-baseline-v1.json
RAG_VISION_EMBED_REAL=1 mvn -B -ntp test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=ImageCrossModalBenchmarkIT
```

真跑外呼规模：视觉 embedding 11 次（6 图语料 + 5 条查询图）、显式文本 embedding 11 次，另有生产检索链内部的
查询文本 embed（每用例 1 次，两路各 1）。端点**不回流 token usage**，故 JSON 里 `*TotalTokens` 恒 0，成本以
`visionEmbeddingCalls` / `textEmbeddingCalls` 留痕。

## 4. 未解决项 / 已知边界（供复核判据与后续卡）

1. **VLM 描述文本是确定性替身**：本卡未真调 `ModelProvider#vision` 生成图片描述（那会把 A 档外呼面从 embedding 扩到 VLM 生成，
   且引入生成不确定性）。真 VLM 描述文本 → 文本集合的对照可作为后续卡的增强项；当前替身只影响"文本降维路上限"的严格程度，
   不影响两条路判分口径的同型性。
2. **语料是程序化确定性图像，不含 PDF 摄取链**：本卡评测的是「视觉向量召回质量」，语料经 `ImageVectorFlushSupport` 同口径
   payload 直接入 image 集合，未经过 PDF 解析 → 视觉向量落库整段（该段由 `ImageVectorSpaceIT`（Testcontainers）与
   `multimodal-shared-space` 的真跑验证覆盖）。要把"PDF 内嵌图 → 视觉召回"纳入评测，需要 Docker 真库 + 真 PDF 资产，属后续卡。
3. **检索层异图出现率 0.80（两路同值）**：image 集合只有 6 条记录，topK=5 必然带出异图——故"误召"的唯一有效口径是余弦判别
   （主指标），检索层出现率只作留痕，不作判据。
