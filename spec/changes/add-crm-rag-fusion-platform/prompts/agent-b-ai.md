# Agent-B-ai 提示词 · 按页分块 + 高亮锚点 + 意图 CRM 化 + 检索（Wave 1，B 线，后于 B-persist）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `specs/knowledge-rag/spec-delta.md`（按页分块/高亮锚点/意图类目）、`tasks.json`（任务 9）、`design-decisions.md`（D4/D15/D17）、`assistant-decision-tree.md`（§4 高亮契约）、`B0-spike-结论.md`（B-spike 产出）、`migration-subtasks.md`（B1–B10 检索/嵌入部分）。
> 源项目只读参考：RAG `D:\code\rag\back\RAG`（`rag/service/DocumentService`(createSegment/按页)、`Impl/PdfDocumentProcessingServiceImpl`(merge→需改按页)、`Impl/VisionDocumentExtractionServiceImpl`(pageIndex)、`rag/service/rag/RagRetrievalService`、`HybridImageTextRetrievalService`、`EmbeddingService`、`Bm25Scorer`、`dto/SourceReference`、`service/QueryIntentClassifier`(死代码)）。

## 角色与目标
你是 **知识库检索/高亮/意图 agent**。目标：让检索产出**可高亮的来源**（档 B 页级），并把 RAG 的技术栈意图分类换成 **CRM 域可配类目**。这是 B 线关键路径的检索侧。

## 负责范围
tasks.json 任务 9；migration-subtasks.md B1–B10 中检索/嵌入/来源相关部分。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-b-knowledge`（同 B，接续 B-persist 之后）
- 分支：`feature/lane-b-knowledge`；禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成、契约冻结；**B-spike 结论**（Spring AI 检索/嵌入/维度）；**B-persist 已合入**（7 表 + 文档移植 + VectorStore 就绪）。

## 独占可改
`com.slz.crm.knowledge.**` 的分块/检索/来源/意图相关（`DocumentService` 分块、`RagRetrievalService`、`HybridImageTextRetrievalService`、`SourceReference`、意图/类目）。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约（尤其 `SourceReference`/SSE）、`server.ai.**`（C 领地）、B-persist 已合入的实体/mapper。

## 要做（任务 9）
1. **按页分块（档 B）**：PDF text/OCR 模式改**逐页处理**，每片段打 `pageNo`（vision 已算 `pageIndex`，透传进 `extraMetadata`→`document_vector_chunk.extra_metadata_json`）。**废弃**当前"全页 merge 成一个 `mergedText` 再整体分块"的做法（页边界丢失）。
2. **SourceReference 补锚点**：增 `chunkIndex/pageNo/chunkId`（+ Excel `rowIndex`）；检索构造来源时从片段 metadata 取全（现仅取 `filename/documentId/excerpt/score`）。这是前端跳页高亮的数据基础。
3. **意图/类目 CRM 化**：**丢弃死代码 `QueryIntentClassifier`**（技术栈类目、无调用点）；**新建** CRM 域意图/类目机制，接进检索 metadata 过滤（复用已存的 `category/direction` 元数据位）；类目+关键词由 `DynamicConfig` 可配（消费 E 的接口），`intent-filter-enabled=false` 或无配置则跳过过滤。
4. **混合检索 + 图文双路**：向量+BM25 重排；图文双路召回（**图片向量由 C 助手在 KB ON 时提供**，你只做检索侧融合，文本 0.7/图片 0.3）；授权过滤（B-persist 已建，你消费）。
5. **文档入库按 CRM 类目打 metadata 标签**（与 3 配套，过滤才生效）。
6. 补测试：按页分块 `pageNo` 正确、来源锚点齐全、类目过滤生效/跳过、检索冒烟（上传→按页分块→检索带高亮锚点）。

## 关键坑
- **图片路向量来自 C**：B 只负责检索融合，别在 B 里做聊天图片理解（那是 C 的图片解耦领地）。
- **按页分块是重构不是加一行**：`PdfDocumentProcessingServiceImpl` 现把全页 append 进 StringBuilder → 要改成保留页边界；bbox 像素级不在本期（OCR 只返回纯文本）。
- `SourceReference` 是**冻结契约**（C 消费它做高亮/citations），改字段走契约申请。
- 意图是**新建**不是改造死类；别去"修" `QueryIntentClassifier`，直接丢。

## 注释重点（本 lane）
- **按页分块的页边界/pageNo 透传**必须行内注释（为何从 merge 改按页、pageNo 从哪来）。
- 意图/类目过滤逻辑 + 可配来源（DynamicConfig）+ 跳过条件注释清楚。
- `SourceReference` 各锚点字段用途（供前端跳页/段内匹配高亮）注释。
- 混合检索/图文双路权重与去重注释说明。

## 出口条件
上传→按页分块→检索（来源带 `chunkIndex/pageNo/chunkId`）冒烟通过；类目过滤生效/跳过测试绿；知识库能力测试子集重建。合入后通知 C 接续高亮/citations。

## 产出
变更摘要 + 测试结果 + 契约/依赖变更申请（如有）。
