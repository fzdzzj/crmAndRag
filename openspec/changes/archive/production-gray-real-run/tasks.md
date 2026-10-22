# 任务清单 · production-gray-real-run

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 真实 PDF 资产准备
- [ ] 1.1 从公有领域政府出版物（NIST/DOE）下载 2~3 份真实 PDF，验证 `PdfImageExtractor` 可提取 ≥2 张 `PDImageXObject` 位图（最短边 ≥64px、非 stencil、非矢量），单份体积 ≤5MB
- [ ] 1.2 记录来源 URL + 授权依据（公有领域声明），入库 `src/test/resources/rag-quality/real-pdf/`

## 2. 完整链路真跑 IT
- [ ] 2.1 `ProductionGrayRealRunIT`（opt-in `RAG_VISION_EMBED_REAL=1`）：真实 PDF → 真 VLM 描述 + 真视觉 embedding + 真文本 embedding → image/文本双集合 → 真 query-by-image 检索，`CountingProvider` 真计数；无 key 整类 Assume 跳过
- [ ] 2.2 成本口径对齐 pdf-ingest-image-eval 的 CountingProvider（visionCallCalls/visionEmbeddingCalls/textEmbeddingCalls 真调用点累加）

## 3. 成本基线落盘
- [ ] 3.1 `docs/rag-quality/production-gray-cost-v1.json`（真实 token/调用次数/耗时）+ `.md` 口径说明（含「真实 PDF vs 程序化 PDF」成本差异观察）

## 4. 测试与验证
- [ ] 4.1 单测：真实 PDF 资产可提取性 / 无 key 跳过（fake 向量回函，零真外呼）
- [ ] 4.2 `mvn -B -ntp clean verify` BUILD SUCCESS + 基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10

## 5. 收口
- [ ] 5.1 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）