# 任务清单 · multimodal-gray-and-eval-threshold

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 生产开关运行期灰度路径登记（纯文档，零代码）
- [x] 1.1 登记「打开 `rag.retrieval.image-vector-ingest.enabled` = 超管运行时写 true + 可选 kb-allowlist 灰度 + max-images-per-document 按生产帽（默认 5）」，附 production-gray-cost-v1 成本基线引用；不改代码、不改默认值
      补勾依据（2026-10-22）：`docs/multimodal-gray-rollout.md`（MULTIMODAL-GRAY-ROLLOUT-1.0，四键真实门控关系 / 打开顺序 / 回滚 / 观察指标 / 成本基线引用）+ `docs/dynamic-config-keys.md` §image-vector-ingest 指针 + `docs/active-handoff.md` §3 看板文本；实读 `PdfImageSliceIngestService` 确认真视觉路嵌在 `image-slice-ingest.enabled + kb-allowlist` 门控内（**无独立 vector kb-allowlist 键**）、`resolveMaxImages()` 默认 5 / 硬上限 20；本卡组零 `src/main` 改动、`image-vector-ingest.enabled` 默认值仍为 false（`DynamicConfigKeyRegistry` L341-353 未动）

## 2. 异图硬阈值定标（A 档真跑）
- [x] 2.1 造「多图同类/跨类」定标语料（同类变体应判同、跨类应判异、真实版式图类边界），复用 `ImageCrossModalEvaluator` 余弦判据框架
      补勾依据（2026-10-22）：`src/test/java/com/slz/crm/quality/ImageThresholdCalibrationFixture.java`（3 图型族 × 3 实例 = 9 张确定性图）+ `ImageThresholdCalibrator.java`（三层档位 same-image / same-class / cross-class，纯函数零外呼，复用 `ImageCrossModalEvaluator#cosine`）+ 真实语料经 `RealPdfAssets.extract`（生产 `PdfImageExtractor`，各 3 张，SHA-256 去重）
- [x] 2.2 真跑 `RAG_VISION_EMBED_REAL=1`，用真实语料分布定出「同图下限 / 异图上限」判别阈值（替代程序化语料 0.90），落锚 `docs/rag-quality/image-threshold-calibration-v1.json`；无 key 整类 Assume 跳过
      补勾依据（2026-10-22 真跑 EXIT=0，21.67 s / 36 次视觉 embedding 调用）：真实语料 9 图 / 45 对 → 同图下限 **0.9999999999999998**、异图上限 **0.9887359400788237**（独立复现 production-gray 的 0.9887）、可分 true、判别阈值 **0.9943679700394117**；程序化对照块阈值 0.9889（不采信为锚）；锚 `image-threshold-calibration-v1.json`（19 461 B）+ 说明 `image-threshold-calibration-v1.md`；程序化套件 `ImageCrossModalSuite` 0.90 **未动**；无 key/未 opt-in 由 `requireRealRun` 两条 Assume 跳过（默认 CI 零外呼）

## 3. embedding 计量口径诚实化（B 档 src/main 小改）
- [x] 3.1 把「token 恒 0」的 embedding 计量改为显式「调用次数计量 + token 不可得声明」口径（不伪造 token）；若触碰 `TokenUsageRecord`/`ModelCallResult` 走受控解冻
      补勾依据（2026-10-22）：`TokenUsageRecorderImpl` 新增 `ai.token.record.calls`（每次上报 +1，与 token 是否可得无关）+ `ai.token.usage.unavailable`（三列 token 全空时 +1）+ `usageAvailable()` 判据；`ModelProviderImpl#embed` 补 usage 口径 Javadoc；**未解冻冻结契约**（`contracts-frozen.md` §71「TokenUsageRecord 9 字段不改签名」原样，三列 NOT NULL DEFAULT 0 由 V5 迁移既定、零迁移）；口径文档 `docs/token-metering.md`
- [x] 3.2 单测锁「embedding 调用次数记入、token 不可得明示」语义
      补勾依据（2026-10-22 `mvn -B -ntp test -Dtest=TokenUsageRecorderImplTest` 4 绿）：`shouldCountEmbeddingCallAndDeclareTokenUnavailable`（calls=1 / unavailable=1 / 落库三列仍为 0）+ `shouldNotDeclareUnavailableWhenUsagePresent`（calls=1 / unavailable 计数器不存在）

## 4. 测试与验证
- [x] 4.1 `mvn -B -ntp clean verify` BUILD SUCCESS + 基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10
      补勾依据（2026-10-22）：见 `work/verify-multimodal-gray.log`；基线 `--update` 后 `check-test-baseline.sh` 校验 EXIT=0；`merge-gate.sh` 10/10 PASS

## 5. 收口
- [x] 5.1 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）
      补勾依据（2026-10-22）：看板 `docs/active-handoff.md` §3 三条长期储备已改为本卡收口态；分支 `feature/multimodal-gray-and-eval-threshold` 未 merge、未 push（A 档待独立复核）
