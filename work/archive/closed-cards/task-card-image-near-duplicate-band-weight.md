# 任务卡 · image-near-duplicate-band-weight（近似同图降权分段系数）

## 目标（一句话）
把「近似同图统一 ×0.5 降权」改成**按余弦分段的三档系数**（间隔带三等分、自动随锚缩放），档位边界不新增需要手工同步的常量。

## 现状锚点（已实测，勿再猜）
- `ImageSimilarityGate`：`NEAR_DUPLICATE_FLOOR = 0.9887D`、`NEAR_DUPLICATE_DOWN_WEIGHT = 0.5D`、`TIER_METADATA_KEY = "imageSimilarityTier"`、`tierOf(cosine, threshold)` 三档、`gate(...)` 只打标不改分、`downWeightNearDuplicates(...)` 在 **rerank 之后**对 `NEAR_DUPLICATE` 候选乘 `NEAR_DUPLICATE_DOWN_WEIGHT`。
- 锚：`docs/rag-quality/image-threshold-calibration-v1.json`（同图下限 0.9999999999999998 / 异图上限 0.9887359400788237 / 判别阈值 0.9943679700394117）。
- 触发理由：`production-gray-real-run` 真跑记录「真实语料同图/异图间隔可低至 +0.011」，单一 0.5 把「几乎相同」和「勉强过下限」压成同一档，二值化损失排序信息。

## 硬约束（违反即退回）
1. **不放松、不越界**：最低档权重必须 = 现状 `0.5`（不得更高便于召回，也不得低于 0.5 变成误杀）；任意档权重必须 `0.5 ≤ w < 1.0`，且 `w_low < w_mid < w_high` 严格单调；同图档不加权不降权。
2. **零新增同步点**：段边界由 `[floor, threshold]` 区间按 1/3、2/3 等分推导（`floor = effectiveFloor(threshold) = min(0.9887, threshold)`），因此锚重标定时段边界**自动缩放**，不新增常量、不新增配置键（`docs/dynamic-config-keys.md` 键表零新增）。
3. **metadata 兼容**：`imageSimilarityTier` 仍保留 `NEAR_DUPLICATE`（既有消费方与测试不破），新增 `imageSimilarityBand` ∈ `NEAR_HIGH` / `NEAR_MID` / `NEAR_LOW`；`downWeightNearDuplicates` 按 band 取权重，**band 缺失时回落 0.5**（兼容旧候选/直接构造的候选）。
4. 降权仍在 **rerank 之后**；`gate` 仍不改分；文本路 `minScore` 语义零变化；零迁移、零权限、零契约、零新依赖。
5. **严禁在观察树（`D:\code\crmAndRag`）产生任何文件**（含空文件）。

## 交付面（写集）
- `src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java`：三档权重常量（`NEAR_DUPLICATE_WEIGHT_HIGH/MID/LOW`，LOW = 0.5）+ 段边界推导 + band 打标 + 按 band 降权 + javadoc 更新（旧 `NEAR_DUPLICATE_DOWN_WEIGHT` 保留为 LOW 别名并注明，或全仓同步替换，二选一在回报里说明）。
- 测试：`ImageSimilarityThresholdGateTest` 扩充（边界 ±1e-6、单调性、LOW==旧 0.5、band 缺失回落、缩放性：阈值换 0.9995 后边界随之移动）。
- 文档：`docs/dynamic-config-keys.md` 该键节语义与锚耦合注记补「分段系数」+ 新 `docs/rag-quality/image-near-duplicate-bands-v1.md`（用 90 对真实余弦统计落带分布，给出"当前三档是否合理"的结论）。
- 台账：`scripts/test-baseline.txt` 只经 `--update`。

## 验收（自证）
`mvn -B -ntp clean verify` BUILD SUCCESS；`bash scripts/check-test-baseline.sh --update` + 校验 `EXIT=0`；`bash scripts/merge-gate.sh` 全绿。

## 停步条件
跑绿即停步：**不 merge、不 push**，回报分支名 / commit 列表 / 门禁输出尾行 / 未解决项。
