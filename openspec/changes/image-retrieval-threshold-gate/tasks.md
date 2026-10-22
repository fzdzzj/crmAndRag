# 任务清单 · image-retrieval-threshold-gate

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 独立图像阈值键
- [ ] 1.1 `RetrievalConfigResolver` 增 `resolveImageSimilarityThreshold(singleKbId)`，读 `rag.retrieval.image-similarity-threshold`（Double，默认 0.9944，越界拒写）；`DynamicConfigKeyRegistry` 登记

## 2. recallImage 用独立阈值 + 三档分档
- [ ] 2.1 `retrieveFromKnowledgeBases` 改传 `resolveImageSimilarityThreshold` 给 `recallImage`，不再复用文本 minScore
- [ ] 2.2 `recallImage` 三档分档：高置信同图（≥阈值）正常召回 / 近似同图（lower ≤ 分数 < 阈值）召回但降权标记 / 异图（< lower）过滤；近重复图降权不误杀

## 3. 测试
- [ ] 3.1 单测：三档边界（阈值/近似下限/异图下限）、近重复图降权、文本路 minScore 零变化（行为零回归）
- [ ] 3.2 既有检索测试零回归（`KnowledgeRetrievalServiceImpl` 相关 + `ImageCrossModal` 相关）

## 4. 验证与收口
- [ ] 4.1 `mvn -B -ntp clean verify` BUILD SUCCESS + 基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10
- [ ] 4.2 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）