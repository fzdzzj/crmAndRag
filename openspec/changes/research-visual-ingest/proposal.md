# 提案：视觉摄取适用性调研（不做生产代码）

> 变更 ID：`research-visual-ingest` ｜ 能力域：`document-ingest` ｜ 序列：方向 B，调研先行
> 来源：交接「评测语料全是手写 md/txt/xlsx，真实知识库 80% 是 PDF/PPT/扫描件」；工作区已有 `_vlm_transcribe.py` 与 `C:\Users\fzdzzj\Desktop\rag`。
> **本单禁止改 `src/main`、禁止 DashScope 外呼、禁止把学习工作区文件提交进本仓。** 必须等 `fix-multicondition-recall` 合入后再执行（禁止并行）。

## Why

当前 `DocumentService` 只吃 pdf/txt/md/xlsx：PDF 走 PDFBox 文本层。学习工作区 `rag-kb/sources.md` 已验证课件 PDF 文本层「极差/差」、真正内容在图里，必须渲染 PNG + 视觉转写。产品侧若继续只抽文本层，上线企业课件/扫描件会得到空壳 chunk——这比再调检索权重更「符合常理」的杠杆。

调研目标：把学习工作区的做法 **映射** 到本仓，标出能落地 / 不能照搬 / 成本闸门，给下一张**实现提案**当靶，而不是这张就写解析器。

## What Changes

只新增一份对照文档（建议路径 `docs/ingest-gap-map.md`），结构固定：

1. **本仓现状**（必须 grep/读代码，禁止凭记忆）：`DocumentService` 支持扩展名、`parsePdf`/`parseExcel`、切分策略、锚点 pageNo/rowIndex、表头投影刚合入的行为。
2. **学习工作区现状**（只读，不修改、不提交）：
   - `C:\Users\fzdzzj\Desktop\rag\rag-kb\sources.md` §一～§四
   - `C:\Users\fzdzzj\Desktop\rag\rag-kb\02-文档解析与切分.md`（解析工程现实、表格、OCR）
   - `d:\code\crmAndRag\_vlm_transcribe.py`（Qwen-VL、幂等、禁止猜测）
   - 如存在：`C:\Users\fzdzzj\Desktop\rag\_extract\render.py` 的渲染参数
3. **差距表**（每行：能力 / 工作区做法 / 本仓现状 / 适用性：可直接移植 / 需改写 / 不适用 / 需授权）：至少覆盖
   - 文本层 PDF vs 图像型 PDF 检测（文本层过短则走视觉）
   - PDF 渲染 PNG
   - VLM 转写（模型、prompt 纪律、重试、成本）
   - PPTX
   - 扫描件 OCR vs VLM
   - 表格（xlsx 已投影 vs PDF 内表格）
   - 抽取质量闸门（空页、过短、（截图不清）比例）
   - 与 D15 锚点（pageNo）如何对齐
4. **成本粗估**（不要真跑）：按工作区 132 页课件量级，说明若用 qwen-vl-max 全量转写的数量级；明确「下一张实现提案必须先报预估再跑试点」。
5. **建议的下一张实现提案切口**（只推荐 1 个最小切口，不要列 8 个都做）：例如「图像 PDF 检测 + 单页 VLM 转写试点，默认关，质量闸门失败回退文本层」。给出为什么不是 PPTX-first、为什么不是先上 OCR 引擎。

## Impact

- **新增**：`docs/ingest-gap-map.md`（中文，带代码路径与工作区路径）。
- **修改**：HANDOFF 加一行「摄取调研已出，实现另案」。
- **不改**：任何 Java、pom、fixtures、baseline JSON、`_vlm_transcribe.py`（保持未跟踪）。

## Non-Goals

- 不实现 VLM/OCR/PPTX 解析。
- 不调用 DashScope（连 dry-run 调模型都不要）。
- 不把 `C:\Users\fzdzzj\Desktop\rag` 拷进本仓。
- 不与 T-14 抢同一分支。

## 验收

文档每条「本仓现状」能被 grep 到对应文件；每条「工作区现状」有 `sources.md` / `02-` / `_vlm_transcribe.py` 的出处；有且仅有一个推荐下一切口；文末写明未跑任何外呼。
