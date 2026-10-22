# handoff · multimodal-gray-and-eval-threshold（多模态灰度授权 + 异图阈值定标 + embedding 计量口径）

> 档位：A 档（异图定标真跑 + embedding 计量；生产开关登记为纯文档）
> 分支：feature/multimodal-gray-and-eval-threshold（永久保留）｜M1 merge --no-ff `7ffcba5` → 基线收口 `3334c08`

## §1 目标与背景
多模态真共享空间系列三个收尾项硬合一张卡：生产开关运行期灰度路径登记 / 异图硬阈值定标 / embedding 计量诚实化。
Owner 拍板：生产开关只登记运行期灰度路径不改代码；三卡硬合一张 A 档卡。

## §2 实现面（16 文件 +1982/−10）
- **任务组 1（纯文档）**：`docs/multimodal-gray-rollout.md` 登记运行期灰度路径（打开 enabled=true + kb-allowlist 灰度 +
  max-images-per-document=5，含顺序/回滚/指标/成本基线引用）。实读纠偏：真视觉路嵌在 image-slice-ingest.enabled+kb-allowlist 门控内，无独立 vector 白名单键。
- **任务组 2（定标真跑）**：`ImageThresholdCalibrationFixture`(163)/`ImageThresholdCalibrator`(292)/`ImageThresholdCalibrationIT`(267)/
  `ImageThresholdCalibrationTest`(170) + `image-threshold-calibration-v1.json`(90 对)。真实语料同图下限 1.0、异图上限 0.9887、
  判别阈值 **0.9944**（real-pdf 区间中点非 hardcode），结论「0.90 在真实语料偏松」。判据「不可分必红」防线。
- **任务组 3（计量口径）**：`ModelProviderImpl` +9（纯 Javadoc 留痕）/ `TokenUsageRecorderImpl` +46（`ai.token.record.calls` +
  `ai.token.usage.unavailable` 两指标 + `usageAvailable`）。零伪造 token、零解冻冻结契约。口径 `docs/token-metering.md`。

## §3 零迁移 / 零权限 / 零契约论证
- src/main 仅 2 文件（ModelProviderImpl +9 Javadoc / TokenUsageRecorderImpl +46 指标），`-- src/main/resources/db` 空。
- 零 RequirePermission/PermissionOperates/@RestController/@Mapping/OpenEndpointRegistry/CONTROLLER_REGISTRY 命中。
- TokenUsageRecord/ModelCallResult 零改动（零契约解冻，比预期更安全）；开关默认 false 未动（PdfImageSliceIngestService 不在 diff）。

## §4 真跑论证（A 档判据）
`ImageThresholdCalibrationIT` 两条 assumeTrue（RAG_VISION_EMBED_REAL=1 + key）→ 无 key 零外呼；90 对（programmatic 45 +
real-pdf 45，各 9 同图/9 同类/27 跨类）、36 次调用，真实语料 = 3 份公有领域 PDF（NIST SP800-101r1/183、DOE）；
阈值 0.9943679700394117 = real-pdf 区间中点；不可分必红（Calibrator 不可分时 threshold=null + IT:115 assertTrue(separable)）。

## §5 偏差处置与治理留痕
- **D1（计量落 platform/token 偏差）**：派发写集列 contract/model，实际落 platform/token（TokenUsageRecorderImpl）——
  **合理偏差**：判据是 token 三列纯函数、落库侧一处即可推导，且零契约解冻（根本没碰冻结契约）。复核方确认合理。
- **D2（生产开关实读纠偏）**：真视觉路无独立 vector 白名单键，嵌在 image-slice-ingest.enabled+kb-allowlist 门控内——proposal 假设纠正。
- **D3（0.9944 是否进检索硬闸门 = 行为变更另卡）**：定标产出阈值，但「进入检索硬闸门」属 src/main 行为变更，另卡授权。
- **D4（近重复图无法用单阈值区分）**：真实语料里「视觉近似同图」版式图（间隔可低至 +0.011），单阈值受限，已如实标注。

## §6 停步声明
未 push 前停步；push 由主 Agent 在 Owner 授权 merge 后执行（`0e1c367..3334c08`）。未 reset/rebase/amend/删分支。