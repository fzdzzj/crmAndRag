# 提案 · production-gray-real-run（真共享空间生产灰度真跑）

## 背景与动机

多模态真共享空间系列四张前序卡已闭环：multimodal-shared-space（真视觉向量空间）、image-cross-modal-eval（真视觉 vs
文本对照）、pdf-ingest-image-eval（PDF 摄取链真跑）、anchor-v6-baseline-v7（主套件 72 例真跑）。但**所有真跑语料都是程序化
确定性图/程序化 PDF**（`pdfBytes()` 现场生成），从未用**真实生产 PDF 资产**真跑过一次完整「摄取 → 视觉向量 → 检索」链路。

本卡是生产落地终环：用真实 PDF 资产真跑完整链路，度量**真实成本基线**（VLM 描述 + 视觉 embedding + 文本 embedding 的真实
token/调用次数与耗时），为「是否把 `rag.retrieval.image-vector-ingest.enabled` 生产开关真正打开」提供成本依据。

## 现状锚点（三源实读，2026-10-22）

- **四前序卡已闭环**：链路正确性（ImageVectorSpaceIT）、真跑质量（image/pdf cross-modal eval 判别间隔）、评测基线
  （baseline-v7/image-baseline-v1/pdf-ingest-image-baseline-v1）全部就绪。
- **成本口径已成熟**：`CountingProvider`（真调用点计数，非公式推导）+ `RAG_VISION_EMBED_REAL` / `RAG_BENCHMARK_REAL`
  opt-in + key 走 `.env`（`DASHSCOPE_API_KEY`）。
- **关键缺口**：`src/test/resources` 零真实 PDF；项目纪律「零二进制资产入库」+ 程序化生成。真实 PDF 资产是绝对前置。
- **opt-in 纪律**（runbook §6.3）：真外发 IT 一律显式 opt-in，`-Dit.test` 白名单铁律，`RAG_BENCHMARK_REAL` 会同时打多个真机 IT。

## Owner 拍板（2026-10-22）

1. **真实 PDF 来源 = 公有领域政府出版物**（NIST/DOE，零版权风险）。
2. **入库 `src/test/resources`**（随 repo 分发）。
3. **严格自由授权**（CC0/公有领域，可确认合法入仓）。
4. **完整链路真跑**（摄取 → 双集合 → 检索）+ **落盘成本基线 JSON**。

## 方案骨架

1. **真实 PDF 资产准备**（执行端任务）：从公有领域政府出版物（NIST/DOE）选 2~3 份**含可提取内嵌图**的真实 PDF，验证
   `PdfImageExtractor` 可提取 ≥2 张 `PDImageXObject` 位图（最短边 ≥64px 非 stencil 非矢量），单份体积 ≤5MB（控制 repo 负担），
   记录来源 URL + 授权依据（公有领域声明），入库 `src/test/resources/rag-quality/real-pdf/`。
2. **完整链路真跑 IT**（新 `ProductionGrayRealRunIT`，opt-in `RAG_VISION_EMBED_REAL=1`）：真实 PDF 资产 → 真 VLM 描述 +
   真视觉 embedding + 真文本 embedding → image/文本双集合 → 真 query-by-image 检索，`CountingProvider` 真计数。
3. **成本基线落盘**：`docs/rag-quality/production-gray-cost-v1.json`（真实 token/调用次数/耗时，对齐 CountingProvider 口径），
   附 `.md` 口径说明（含「真实 PDF vs 程序化 PDF」的成本差异观察）。
4. **无 key 零外呼**：opt-in Assume 跳过（默认 CI/verify 零外呼）；真实 PDF 资产不参与默认真跑（仅 opt-in 真跑读取）。

## 边界与不改动面

- **不动** 生产摄取/检索链路（`src/main` 零 diff）；**不改**生产开关默认值（`image-vector-ingest.enabled` 仍默认 false）。
- **不碰** 既有 baseline（v1~v7/image/pdf 锚）；**零迁移 / 零权限 / 零契约改动**。
- **唯一入库新资产**：真实 PDF（公有领域）+ 成本基线 JSON/MD。

## 档位判定

**A 档（真跑）**：真实 PDF 全链路真跑（真 VLM + 真视觉/文本 embedding + 真检索），`RAG_VISION_EMBED_REAL` opt-in。真跑判据触发 A 档。

## 验收判据

1. 真实 PDF 资产入库（公有领域、含 ≥2 张可提取内嵌图、≤5MB、授权可溯源）。
2. 完整链路真跑跑通（真实 PDF → 摄取 → 双集合 → 检索），非 mock 假绿。
3. 成本基线 JSON 落盘（真实 token/调用/耗时，CountingProvider 口径）。
4. 无 key 零外呼；独立复核四判据全过。