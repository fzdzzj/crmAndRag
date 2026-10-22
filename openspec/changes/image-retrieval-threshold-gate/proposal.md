# 提案 · image-retrieval-threshold-gate（异图阈值进检索硬闸门 + 近重复图分档）

## 背景与动机

上一卡 `multimodal-gray-and-eval-threshold` 产出了定标锚：真实语料**同图下限 1.0、异图上限 0.9887、判别阈值 0.9944**
（程序化语料 0.90 在真实语料偏松），但「进检索硬闸门」被明确标注为**属 src/main 行为变更、另卡授权**。同时定标诚实指出
「近重复图（视觉近似同图，间隔可低至 +0.011）无法用单阈值区分」。

本卡把这两个收尾合一：把定标锚落到检索侧真实闸门 + 用「分档」解决近重复图单阈值误杀。

## 现状锚点（三源实读，2026-10-22）

- **图像路错误复用文本阈值**：`retrieveFromKnowledgeBases`（`KnowledgeRetrievalServiceImpl` L253）解析文本 `minScore`
  （`RetrievalDefaults.MIN_SCORE = 0.20`），L267-268 把**同一个 minScore 传给 `recallImage`**——图像视觉路调用
  `recallIn` → `space.search(new VectorSearchRequest(..., minScore, ...))` 时用文本阈值 0.20 过滤，**与定标锚 0.9944
  差 5 倍**，导致图像路召回过宽（大量近重复/近似图混入）。
- **无独立图像阈值键**：`RetrievalConfigResolver.resolveMinScore` 只有一个 `rag.retrieval.minScore`（文本），无
  image 专属键。
- **定标锚已产出**：`docs/rag-quality/image-threshold-calibration-v1.json`（90 对，同图下限 1.0 / 异图上限 0.9887 /
  阈值 0.9944）。

## Owner 拍板（2026-10-22）

1. **独立图像阈值键** `rag.retrieval.image-similarity-threshold`（默认 = 定标锚 0.9944），`recallImage` 用独立阈值不再复用文本 minScore。
2. **三档分档语义**：高置信同图（≥阈值）正常召回 / 近似同图（阈值↔下限）召回但降权标记 / 异图（<下限）过滤。

## 方案骨架

1. **独立图像阈值动态键**：`RetrievalConfigResolver` 增 `resolveImageSimilarityThreshold(singleKbId)`，读
   `rag.retrieval.image-similarity-threshold`（Double，默认 0.9944，越界拒写）；`DynamicConfigKeyRegistry` 登记。
2. **`recallImage` 用独立阈值**：`retrieveFromKnowledgeBases` L267-268 改传 `resolveImageSimilarityThreshold`，不再复用文本 minScore。
3. **三档分档**：新增「近似同图下限」第二键（或从阈值推导区间），`recallImage` 召回后按余弦分数分三档：
   - 高置信同图（≥ threshold）→ 正常候选；
   - 近似同图（lower ≤ 分数 < threshold）→ 召回但叠加降权标记（不误杀近重复图）；
   - 异图（< lower）→ 过滤。
4. **近重复图降权语义**：近似同图档的候选降权（rerankScore 打折或标记），使「真同图」优先于「近似同图」，但近似同图仍可召回（不因 +0.011 边界差被误杀）。

## 边界与不改动面

- **不改**文本检索 minScore（`rag.retrieval.minScore` 仍 0.20，文本路行为零变化）。
- **不改** `ImageCrossModalSuite` 0.90 / 定标锚 0.9944 本身（只做消费方引用）。
- **零迁移 / 零新权限**；图像阈值/分档是检索行为变更（`src/main` knowledge/retrieval），新建动态键走受控登记（非冻结契约）。

## 档位判定

**A 档**：`src/main` 检索行为变更（图像路阈值 + 分档），新建动态配置键 + 权限面（COST/OPERATIONAL 档）。检索行为变更触发 A 档独立复核。

## 验收判据

1. 独立键 `rag.retrieval.image-similarity-threshold` 登记（默认 0.9944），`recallImage` 用独立阈值不再复用文本 minScore。
2. 三档分档落地：高置信正常召回 / 近似降权标记 / 异图过滤；近重复图不被单阈值误杀。
3. 文本路行为零变化（minScore 仍 0.20）；单测锁三档边界 + 检索行为。
4. 独立复核四判据全过。