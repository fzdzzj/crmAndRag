# 任务清单 · image-near-duplicate-band-weight

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 闸门分段
- [ ] 1.1 `ImageSimilarityGate` 增三档权重常量 `NEAR_DUPLICATE_WEIGHT_HIGH = 0.75D` / `_MID = 0.6D` / `_LOW = 0.5D`；旧 `NEAR_DUPLICATE_DOWN_WEIGHT` 处理（保留为 LOW 别名并注明，或全仓替换后删除）
- [ ] 1.2 段边界由 `[effectiveFloor(threshold), threshold]` 按 1/3、2/3 推导（零新增常量、零新增配置键），`gate(...)` 在三档 tier 之上补打 `imageSimilarityBand`（`NEAR_HIGH` / `NEAR_MID` / `NEAR_LOW`）
- [ ] 1.3 `downWeightNearDuplicates(...)` 按 band 取权重（缺 band / 未知值 → 0.5），仍在 rerank 之后调用；`gate` 仍不改分
- [ ] 1.4 `NEAR_DUPLICATE_FLOOR`、`tierOf` 三档语义、文本路 minScore 行为均保持不变

## 2. 测试
- [ ] 2.1 边界：`floor`、`floor+Δ/3`、`floor+2Δ/3`、`threshold` 各取 `±1e-6`，断言落档正确（含等于边界值时的归属）
- [ ] 2.2 单调与不放松：`w_low == 0.5`、`w_low < w_mid < w_high < 1.0`、同图档权重 == 1.0（用具体分数断言，不用公式推导）
- [ ] 2.3 缩放性：阈值改为 0.9995（`floor` 仍 0.9887）后段边界随 Δ 移动，断言新高/中/低档命中点
- [ ] 2.4 兼容：band 缺失的候选回落 0.5；`imageSimilarityTier` 仍为 `NEAR_DUPLICATE`（既有断言不破）
- [ ] 2.5 顺序：降权后 `sources` 内容保持、重排顺序不被二次打乱（沿用既有 `0.18 → 0.09` 类断言风格）

## 3. 文档与台账
- [ ] 3.1 `docs/dynamic-config-keys.md` 该键节：三档语义补「间隔带三等分 → 0.75/0.6/0.5」，锚耦合注记补「段边界自动缩放、权重常量需人工复核」
- [ ] 3.2 新 `docs/rag-quality/image-near-duplicate-bands-v1.md`：从 `image-threshold-calibration-v1.json` 的 90 对真实余弦统计落带分布（含各带样本数、分位），给出「三档是否合理」的结论与如需调整的具体建议
- [ ] 3.3 `scripts/test-baseline.txt` 只经 `--update` 写入

## 4. 验证与收口
- [ ] 4.1 `mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿
- [ ] 4.2 停步未 merge 未 push，回报分支名 / commit 列表 / 门禁输出尾行 / 未解决项（B 档：门禁绿 + 主 Agent 审 diff 后由 Owner 授权 merge）
