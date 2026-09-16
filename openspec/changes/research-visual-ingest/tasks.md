# Tasks — research-visual-ingest

> **先决**：`fix-multicondition-recall` 已合入 master 后再开本分支。禁止并行。
> 当前基线以 T-14 合入后的 master 为准。本单 ¥0，零外呼。

## 0. 执行记录

- 本仓：`DocumentService` / `DocumentPage` / `ChunkHeaderText` / `FixedChunkingStrategy` / `SourceReference`
- 工作区：`sources.md` / `02-文档解析与切分.md` / `_vlm_transcribe.py` / `_extract/render.py`
- gap-map：`docs/ingest-gap-map.md`
- 是否误外呼：**无**

## 1. 只读事实

- [x] 1.1 读本仓 `DocumentService`（supports / parsePdf / parseExcel / 表头投影）、`DocumentPage`、`ChunkHeaderText`
- [x] 1.2 读 `C:\Users\fzdzzj\Desktop\rag\rag-kb\sources.md`、`02-文档解析与切分.md`、`d:\code\crmAndRag\_vlm_transcribe.py`；render.py 若存在则读渲染参数
- [x] 1.3 不修改学习工作区任何文件

## 2. 对照文档

- [x] 2.1 写 `docs/ingest-gap-map.md`，章节按 proposal「What Changes」1–5
- [x] 2.2 差距表至少 8 行；「本仓现状」列带文件路径；推荐下一切口只能 1 个
- [x] 2.3 文内声明：未调用 DashScope、未拷贝工作区语料到本仓

## 3. 收尾

- [ ] 3.1 HANDOFF 增加摄取调研入口（指向 gap-map；实现未立项）
- [ ] 3.2 git：`feature/research-visual-ingest`；提案三件套 + gap-map + HANDOFF；`--no-ff` 合入；不 push；三个未跟踪件仍不提交

