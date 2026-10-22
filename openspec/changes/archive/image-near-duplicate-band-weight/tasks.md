# 任务清单 · image-near-duplicate-band-weight

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 闸门分段
- [x] 1.1 `ImageSimilarityGate` 增三档权重常量 `NEAR_DUPLICATE_WEIGHT_HIGH = 0.75D` / `_MID = 0.6D` / `_LOW = 0.5D`；旧 `NEAR_DUPLICATE_DOWN_WEIGHT` 处理（保留为 LOW 别名并注明，或全仓替换后删除）
      补勾依据（2026-10-22 `efedceb`）：常量落 `ImageSimilarityGate:36-43`；旧 `NEAR_DUPLICATE_DOWN_WEIGHT` 保留为 `NEAR_DUPLICATE_WEIGHT_LOW` 的别名并在 javadoc 注明「= 最低档，新代码用三档」（`:45-46`），非空壳。
- [x] 1.2 段边界由 `[effectiveFloor(threshold), threshold]` 按 1/3、2/3 推导（零新增常量、零新增配置键），`gate(...)` 在三档 tier 之上补打 `imageSimilarityBand`（`NEAR_HIGH` / `NEAR_MID` / `NEAR_LOW`）
      补勾依据（2026-10-22 `efedceb`）：`lowBandUpperBound`（`:87-90`）/`highBandLowerBound`（`:98-101`）由入参现算，`bandOf`（`:133-146`）；`gate` 对 `NEAR_DUPLICATE` 补打 band（`:186-188`）；`DynamicConfigKeyRegistry` 与 `docs/dynamic-config-keys.md` 键表**零新增键**（diff 可证）。
- [x] 1.3 `downWeightNearDuplicates(...)` 按 band 取权重（缺 band / 未知值 → 0.5），仍在 rerank 之后调用；`gate` 仍不改分
      补勾依据（2026-10-22 `efedceb`）：`weightOfBand`（`:155-165`）未知/缺失回落 LOW；降权 `:205-224` 只乘 `rerankScore`、`gate` 不改分；主链调用点仍在 rerank 之后（`KnowledgeRetrievalServiceImpl` 未在 diff 内）。
- [x] 1.4 `NEAR_DUPLICATE_FLOOR`、`tierOf` 三档语义、文本路 minScore 行为均保持不变
      补勾依据（2026-10-22 `efedceb`）：`NEAR_DUPLICATE_FLOOR`、`tierOf`（`:110-121`）逐字未变；`KnowledgeRetrievalServiceImpl`/`RetrievalConfigResolver` 零 diff；`imageRouteUsesIndependentThresholdWhileTextKeepsMinScore` 仍绿。

## 2. 测试
- [x] 2.1 边界：`floor`、`floor+Δ/3`、`floor+2Δ/3`、`threshold` 各取 `±1e-6`，断言落档正确（含等于边界值时的归属）
      补勾依据（2026-10-22 `c3f2dda`）：`bandBoundariesAtDefaultThreshold` 覆盖 b1=0.9906 / b2=0.9925 两侧 ±1e-6 与等于边界归上一档。
- [x] 2.2 单调与不放松：`w_low == 0.5`、`w_low < w_mid < w_high < 1.0`、同图档权重 == 1.0（用具体分数断言，不用公式推导）
      补勾依据（2026-10-22 `c3f2dda`）：`bandWeightsAreStrictlyMonotonicAndLowestNeverRelaxes` 用字面量断言 0.5/0.6/0.75 与 SAME 不降权。
- [x] 2.3 缩放性：阈值改为 0.9995（`floor` 仍 0.9887）后段边界随 Δ 移动，断言新高/中/低档命中点
      补勾依据（2026-10-22 `c3f2dda`）：`bandBoundariesScaleWithConfiguredThreshold` + `configuredThresholdScalesBandAlongMainChain`（配置驱动到主链）。
- [x] 2.4 兼容：band 缺失的候选回落 0.5；`imageSimilarityTier` 仍为 `NEAR_DUPLICATE`（既有断言不破）
      补勾依据（2026-10-22 `c3f2dda`）：`missingOrUnknownBandFallsBackToLowestWeight`、`gateTagsNearDuplicateBands`；既有 tier 断言全绿（旧 9 例未改）。
- [x] 2.5 顺序：降权后 `sources` 内容保持、重排顺序不被二次打乱（沿用既有 `0.18 → 0.09` 类断言风格）
      补勾依据（2026-10-22 `c3f2dda`）：`downWeightByBandKeepsSourcesAndOrder`；另有 `downWeightIsIdempotent` 钉住重复调用不叠加。

## 3. 文档与台账
- [x] 3.1 `docs/dynamic-config-keys.md` 该键节：三档语义补「间隔带三等分 → 0.75/0.6/0.5」，锚耦合注记补「段边界自动缩放、权重常量需人工复核」
      补勾依据（2026-10-22 `81930c7`）：键表行补三档语义与 band 缺失回落；锚耦合小节补「段边界由 floor/阈值现算、无第二处同步常量；三档权重是代码常量、锚重标定后须人工复核」。
- [x] 3.2 新 `docs/rag-quality/image-near-duplicate-bands-v1.md`：从 `image-threshold-calibration-v1.json` 的 90 对真实余弦统计落带分布（含各带样本数、分位），给出「三档是否合理」的结论与如需调整的具体建议
      补勾依据（2026-10-22 `81930c7` + 主 Agent 独立复算）：文档 118 行；主 Agent 直接读 json 重算得 `same=18 / high=0 / mid=0 / low=1 / cross=71`、`max_cross=0.9778706`，与文档 §2/§4 逐格一致；结论「90 对上行为变化为零、0.75/0.6 属未校准缺省值、需生产灰度采样」已写入 §6/§7。
- [x] 3.3 `scripts/test-baseline.txt` 只经 `--update` 写入
      补勾依据（2026-10-22）：执行端 `--update` 写 `source-revision=81930c7`；M1 主 Agent 在干净合并树上再 `--update` 写 `source-revision=323e667`（surefire 322/2012/0 + failsafe 42/143/12）。

## 4. 验证与收口
- [x] 4.1 `mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿
      补勾依据（2026-10-22 执行端 + M1）：verify BUILD SUCCESS（surefire 2012、failsafe 143/12 skip、Docker 真跑；首轮因 Docker Desktop 中途停止超时红，重启后全绿）、`check-test-baseline.sh` EXIT=0、merge-gate 十项全 PASS；主 Agent 在合并树上 `--update` EXIT=0，并由 **CI Run 39802029858（#167）回归基线阈值校验 success** 复核。
- [x] 4.2 停步未 merge 未 push，回报分支名 / commit 列表 / 门禁输出尾行 / 未解决项（B 档：门禁绿 + 主 Agent 审 diff 后由 Owner 授权 merge）
      补勾依据（2026-10-22）：分支 `feature/image-near-duplicate-band-weight` 自 `0c09ad3` 检出、5 笔、停步未 merge/push；主 Agent B 档审 diff（含独立复算落带）后 Owner 授权 → merge --no-ff **`323e667`**（tree `c627d4d1…` 与分支 tip 逐字节相等，连续第十七卡走树等价快车道）→ 基线收口 `75e35dd` → **CI #167 6/6 全绿** → M2 归档 + 勾选 + 看板 + logbook §152。
