# 任务清单 · image-retrieval-threshold-gate

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 独立图像阈值键
- [x] 1.1 `RetrievalConfigResolver` 增 `resolveImageSimilarityThreshold(singleKbId)`，读 `rag.retrieval.image-similarity-threshold`（Double，默认 0.9944，越界拒写）；`DynamicConfigKeyRegistry` 登记
      补勾依据（2026-10-22 `c0709c8`）：`RetrievalConfigResolver` +31（读键、默认 0.9944、越界回落、不入 per-KB 12 键白名单）；`DynamicConfigKeyRegistry` +19（DOUBLE / 默认串 `"0.9944"` / 范围 0.0~1.0）；`ConfigKeyTierPolicy` 记 OPERATIONAL 36 / 总 68（复用 608，零新权限），`ImageSimilarityThresholdKeyTest` 4 绿。

## 2. recallImage 用独立阈值 + 三档分档
- [x] 2.1 `retrieveFromKnowledgeBases` 改传 `resolveImageSimilarityThreshold` 给 `recallImage`，不再复用文本 minScore
      补勾依据（2026-10-22 `c0709c8`）：主链调用点改传独立阈值；`ImageSimilarityThresholdGateTest` 两路 minScore 断言（文本路 0.20 原样 / 图像路 0.9887）证解耦。
- [x] 2.2 `recallImage` 三档分档：高置信同图（≥阈值）正常召回 / 近似同图（lower ≤ 分数 < 阈值）召回但降权标记 / 异图（< lower）过滤；近重复图降权不误杀
      补勾依据（2026-10-22 `c0709c8`）：`ImageSimilarityGate`（134 行）`gate()` 三档 + `imageSimilarityTier` 标记，向量库 `minScore = effectiveFloor(0.9887)`；`downWeightNearDuplicates` ×0.5 **在 rerank 之后**施加（0.18→0.09 仍在 sources 断言）/ 阈值档不降权 / 异图剔除断言齐备。

## 3. 测试
- [x] 3.1 单测：三档边界（阈值/近似下限/异图下限）、近重复图降权、文本路 minScore 零变化（行为零回归）
      补勾依据（2026-10-22 `c0709c8`）：`ImageSimilarityThresholdGateTest` 9 绿（含 0.9944 / 0.9887 / 异图三边界与「重排后降权」顺序）。
- [x] 3.2 既有检索测试零回归（`KnowledgeRetrievalServiceImpl` 相关 + `ImageCrossModal` 相关）
      补勾依据（2026-10-22 `c0709c8` + M1 实跑）：夹具仅阈值数值上调（`ImageSpaceRoutingTest` 0.95→0.9995、`KnowledgeRetrievalServiceImplTest` 0.9→0.9995），M1 树净全量实跑 surefire 1991 / failsafe 134 零失败。

## 4. 验证与收口
- [x] 4.1 `mvn -B -ntp clean verify` BUILD SUCCESS + 基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10
      补勾依据（2026-10-22 M1）：`--no-ff` 合并笔 `e824aa6` 的 tree `dd5cecd…` 与分支 tip `^{tree}` 逐字节相等 → 走树等价快车道（免本地 clean verify / merge-gate 复跑，执行端已在等价树跑过 BUILD SUCCESS + 10/10）；主 Agent 树净单次 `bash scripts/check-test-baseline.sh --update` 写 `source-revision=e824aa6`（surefire 320/1991/0 + failsafe 41/134/12），校验模式 `EXIT=0`，基线收口 `db1970e`。
- [x] 4.2 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）
      补勾依据（2026-10-22 M2）：A 档独立复核四判据全过无 ACK → Owner 授权 merge+push（M1 合并笔 `e824aa6`、基线收口 `db1970e` 已推 origin/master）；本卡 openspec 卡 `git mv` 入 `openspec/changes/archive/image-retrieval-threshold-gate/`、任务卡复制入 `work/archive/closed-cards/`（SHA256 `37CBCCB8…` 一致，观察树副本已删）+ 看板 / logbook §150 / 台账更新；分支 `feature/image-retrieval-threshold-gate` 永久保留。
