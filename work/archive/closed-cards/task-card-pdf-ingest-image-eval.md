# 任务卡 · pdf-ingest-image-eval（PDF 摄取链纳入图文评测）

- 档位：**A 档**（真跑 opt-in 落锚；零生产代码/零迁移/零权限/零新依赖）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/pdf-ingest-image-eval`（从 master 检出）
- 目标：桥接「真 PDF 解析 → 真视觉 embedding → image collection」与「同/异图余弦质量判据」，落锚 PDF 摄取链独立基线

## Owner 两项拍板（2026-10-21）
1. 真跑范围 = 真 PDF 解析 + 真视觉 embedding（RAG_VISION_EMBED_REAL=1）全链真跑
2. 评测框架 = 复用 ImageCrossModal 框架（ImageCrossModalEvaluator + Suite 口径）

## 关键锚点（已实读，勿重复侦察）
- `PdfImageExtractor` / `PdfImageSliceIngestService#buildSlices`（imageVectorEnabled 开真视觉）/ `ImageVisualVectorSupport#embedAndStage`（真 embedImage 暂存）/ `ImageVectorFlushSupport#flush`
- 上一卡 `ImageCrossModalSuite`（IMAGE-SUITE-1.0）+ `ImageCrossModalEvaluator`（余弦/阈值/判别间隔）+ `ImageCrossModalBenchmarkIT`（opt-in 真跑）
- `ImageVectorSpaceIT`（Testcontainers 真库 + mock 视觉，3 例：入库命中/删除双清/重建）——链路已覆盖，本卡补真跑质量
- `VisionEmbeddingRealIT`（同图>0.9 异图<0.9 判据）

## 任务（写集：src/test quality 模块 + docs/rag-quality/，无其他写入者）
1. 真 PDF fixture：确定性 PDF 含结构图（同图变体）+ 异图 + 纯白易负例（复用/强化 ImageVectorSpaceIT.pdfWithImages 为三型）
2. `PdfIngestImageBenchmarkIT`：真 PDF → buildSlices（真 embedImage opt-in）→ image collection → query-by-image
   → 复用 ImageCrossModalEvaluator 余弦判据 → Report 落盘 pdf-ingest-image-baseline-v1.json；无 key Assume 跳过
3. 对照文本降维（VLM 描述文本切片走文本 collection），两路指标并列
4. 单测：PDF fixture/三型判分/无 key 跳过（fake 向量）

## 边界（禁改面）
- 禁改 src/main（摄取/检索生产链路零 diff）；禁碰 baseline-v1~v6 / image-baseline-v1 / RagBenchmarkSuite / SUITE_VERSION
- 零迁移 / 零新权限 / 零新依赖 / 零契约改动

## 验证
`mvn -B -ntp clean verify`（Docker 已启动，Testcontainers 真库）+ 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10
+ 真跑 RAG_VISION_EMBED_REAL=1 时 pdf-ingest-image-baseline-v1.json 成功落盘

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字，细节留文件。