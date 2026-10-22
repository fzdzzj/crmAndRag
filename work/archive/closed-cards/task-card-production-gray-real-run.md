# 任务卡 · production-gray-real-run（真共享空间生产灰度真跑）

- 档位：**A 档**（真实 PDF 全链路真跑 opt-in 落锚；零 src/main / 零迁移 / 零权限 / 零新依赖）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/production-gray-real-run`（从 master 检出）
- 目标：用真实生产 PDF 资产真跑「摄取 → 视觉/文本双集合 → 检索」完整链路，落盘真实成本基线

## Owner 拍板（2026-10-22）
1. 真实 PDF 来源 = 公有领域政府出版物（NIST/DOE，零版权风险）
2. 入库 src/test/resources（随 repo 分发）
3. 严格自由授权（CC0/公有领域）
4. 完整链路真跑 + 落盘成本基线 JSON

## 关键锚点（已实读，勿重复侦察）
- `PdfImageExtractor`（只提取 PDImageXObject 位图，最短边 ≥64px 非 stencil 非矢量）
- `PdfIngestImageBenchmarkIT` 的 `CountingProvider`（真调用点计数，visionCall/visionEmbedding/textEmbedding 三计数）
- `RAG_VISION_EMBED_REAL` / `RAG_BENCHMARK_REAL` opt-in + `.env` DASHSCOPE_API_KEY
- runbook §6.3：真外发 IT 显式 opt-in、`-Dit.test` 白名单铁律、RAG_BENCHMARK_REAL 会同时打多个真机 IT
- src/test/resources 零真实 PDF（需新增）

## 任务（写集：src/test/resources/rag-quality/real-pdf/ + src/test quality 模块 + docs/rag-quality/，无其他写入者）
1. 下载 2~3 份公有领域真实 PDF（NIST/DOE），验证 PdfImageExtractor 可提取 ≥2 张内嵌位图、单份 ≤5MB、授权可溯源，入库
2. `ProductionGrayRealRunIT`（opt-in RAG_VISION_EMBED_REAL=1）：真实 PDF → 真 VLM + 真视觉/文本 embed → 双集合 → 真检索，
   CountingProvider 真计数；无 key Assume 跳过
3. 成本基线落盘 production-gray-cost-v1.json + 口径说明（真实 vs 程序化 PDF 成本差异）
4. 单测：资产可提取性 / 无 key 跳过（fake 向量）

## 边界（禁改面）
- 禁改 src/main（生产链路零 diff）、禁改生产开关默认值（image-vector-ingest.enabled 仍 false）
- 禁碰既有 baseline（v1~v7/image/pdf 锚）、零迁移/零权限/零契约改动

## 验证
`mvn -B -ntp clean verify` + 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10；真跑后成本基线 JSON 成功落盘

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字（真实 PDF 来源/数量/可提取图数 + 成本基线关键指标 + 校验结果），细节留文件。