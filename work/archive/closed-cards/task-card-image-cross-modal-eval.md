# 任务卡 · image-cross-modal-eval（图文跨模态召回质量评测）

- 档位：**A 档**（真跑 opt-in 落锚，独立复核四判据；零生产代码/零迁移/零权限/零新依赖）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/image-cross-modal-eval`（从 master 检出）
- 目标：建独立 IMAGE-SUITE 评测套件，量化「真视觉 query-by-image 召回」vs「文本降维描述召回」对照，验证真视觉路增量价值

## Owner 三项拍板（2026-10-21）
1. 评测形态 = 独立 IMAGE-SUITE 套件（对齐 customer 先例，不动 72 例主套件）
2. 真跑落锚 = 授权真跑 + 独立 image-baseline-v1.json 落锚
3. 判据 = 同/异图余弦判别（同图余弦高、异图低）

## 关键锚点（已实读，勿重复侦察）
- `RagBenchmarkCase`（Category.IMAGE 已在，但只评测文本描述切片）/ `RagBenchmarkSuite`（SUITE_VERSION="6.0" 72 例）
- `CustomerRetrievalBenchmarkIT`：独立套件先例（CUSTOMER-SUITE-2.0，独立编号 C-01~C-15，不并入主套件）
- `RagBenchmarkDataPreparer`（fixture 幂等入库 + 黄金对齐 + rewriteSuite）
- `RagQualityEvaluator`（recall/MRR/hit → Report）
- `RagRealRetrievalBenchmarkIT`（真跑落锚先例）
- `VisionEmbeddingRealIT`（同图>0.9 异图<0.9 余弦判据，opt-in 真跑）
- `KnowledgeRetrievalServiceImpl#recallImage` / `ImageVectorStore`（真视觉检索链路）

## 任务（写集：src/test quality 模块 + docs/rag-quality/，无其他写入者）
1. `ImageCrossModalSuite`（IMAGE-SUITE-1.0，独立编号 IM-01~IM-N）+ 评测 fixture（图文混排 KB + 黄金图片对）
2. `ImageCrossModalBenchmarkIT`：真视觉 embedding（opt-in）→ query-by-image → 余弦判别 → Report 落盘 image-baseline-v1.json
3. 对照 text-baseline（VLM 描述文本 → 文本 collection 召回），与真视觉并列呈现
4. 单测：套件/判分/阈值边界/无 key 跳过（fake 向量）

## 边界（禁改面）
- 禁改 `RagBenchmarkSuite` 72 例/SUITE_VERSION/Category.IMAGE 既有语义；禁碰 baseline-v1~v6；禁改生产检索/摄取链路

## 验证
`mvn -B -ntp clean verify`（opt-in IT 默认 skip 口径不变）+ 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字，细节留文件。