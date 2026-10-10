# 提案：图像 PDF 检测 + 单页 VLM 转写试点（默认关）

> 变更 ID：`add-vision-pdf-ingest-pilot` ｜ 能力域：`document-ingest` ｜ 序列：方向 B 第一张实现
> 来源：`docs/ingest-gap-map.md` §5 唯一推荐切口。三次 54 条复测仍挂起，本单禁止顺手跑。

## Why

本仓 `parsePdf` 只用 PDFBox `PDFTextStripper`。学习工作区已验证企业课件/扫描页文本层近空、内容在图里；默认路径会得到空壳 chunk 或整篇 `文档解析结果为空`。

产品里视觉调用已经有冻结出口：`ModelProvider.vision` + `TokenUsageType.VISION` + `ai.model.visionModel`（聊天图片理解在用）。摄取侧还没接。本单把这条路接到 PDF 页，**默认关**，失败回退文本层。

## What Changes

### 1. 抽出 `PdfVisionTranscriber`（knowledge.document）
Javadoc 标「add-vision-pdf-ingest-pilot 任务 1.1」。职责：

1. **检测**：该页 `normalize` 后文本长度 `< rag.retrieval.vision-pdf.min-text-chars`（默认 80，与 `MIN_TEXT_LENGTH` 同量级）才尝试视觉。
2. **渲染**：PDFBox `PDFRenderer.renderImageWithDPI(pageIndex, 144)`（0-based）；宽边 >1600 则等比缩小；`ImageIO` 写 PNG。不新增 Maven 依赖（pdfbox 3.0.3 已含 rendering）。
3. **转写**：`ModelProvider.vision`，Prompt 纪律对齐 `_vlm_transcribe.py`：只转写可见内容、不清标「（截图不清）」、不猜测补全、不要寒暄。模型走配置默认 vision（`qwen-vl-plus`），`thinking=false`。
4. **质量闸门**（任一失败 → 空 Optional，调用方保留文本层）：
   - 抛错 / 返回 blank；
   - 转写去空白后长度 < 40；
   - 「（截图不清）」出现次数 / 汉字数 > 0.4。
5. **计量**：成功或失败尝试都尽量 `TokenUsageRecorder` + `TokenUsageType.VISION`（无 recorder / 无 UserContext 则跳过，不挡摄取）。
6. **页数帽**：单文档最多转写 `rag.retrieval.vision-pdf.max-pages` 页（默认 3，范围 1~20）。超出的图像页保留文本层。

### 2. 接入 `DocumentService.parsePdf`
每页先抽文本层；开关开且 transcriber 给出非空转写则用转写替换该页 `DocumentPage.text`；**pageNo 不变**。开关关 / transcriber 未注入 / 闸门失败 → 与现在逐字一致。

构造：保留无参、单参 `ObjectProvider<DynamicConfigService>`（单测/评测）。Spring 用 `@Autowired(required=false)` 注入 `PdfVisionTranscriber`（或 `ObjectProvider`），避免改现有 `new DocumentService()` / `new DocumentService(provider)` 签名含义。

### 3. 动态配置（现有命名空间，不扩 NAMESPACES）
注册到 `rag.retrieval`（`DynamicConfigKeyRegistry` 只允许五个官方 ns）：

| 键 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `rag.retrieval.vision-pdf.enabled` | Boolean | **false** | 总开关 |
| `rag.retrieval.vision-pdf.min-text-chars` | Integer | 80 | 低于此长度视为图像页；范围 1~2000 |
| `rag.retrieval.vision-pdf.max-pages` | Integer | 3 | 单文档最多 VLM 页；范围 1~20 |

同步 `docs/dynamic-config-keys.md`。

### 4. ¥0 单测（mock ModelProvider，禁止真外呼）
- 默认关：无文本 PDF 仍走旧行为（空页跳过；整篇空则 `文档解析结果为空`）。
- 开 + mock 转写：无文本页变成转写正文，`pageNo` 保持。
- 开 + vision 抛错 / 低质量「（截图不清）」刷屏：回退文本层，process 不因 VLM 失败而炸。
- 已有 `semanticStrategyPreservesPageNoAnchorsOnPdf`（富文本 PDF）在开关开时仍绿——不得对够长文本层走 VLM。
- 无参 `DocumentService` 永不调 vision。

### 5. 真 VLM（授权节点，非合入前置）
任务组 1–3 全绿即可合入。真模型试点另授权：人造 1 页图像 PDF 或工作区单页 PNG 封装 PDF，`enabled=true`，断言转写非空且闸门过。未授权禁止设 API key 跑。

## Impact

- **新增**：`PdfVisionTranscriber` + 单测；三枚 `rag.retrieval.vision-pdf.*` 键。
- **修改**：`DocumentService.parsePdf`；`DynamicConfigKeyRegistry`；`docs/dynamic-config-keys.md`；HANDOFF；surefire 基线随实测上调。
- **不改**：扩展名白名单（仍无 pptx）；Excel 投影；检索；54 条用例；Flyway；`ModelProvider` 签名；`_vlm_transcribe.py`（保持未跟踪）。

## 风险

- **误伤文本 PDF**：阈值 80 可能把短页当图像。对策：富文本页单测必须不调 vision；复测/试点若误伤再调阈值，不先放宽到「每页都 VLM」。
- **费用**：默认关；开了之后每图像页一次 vision。max-pages=3 封顶。生产打开必须先授权。
- **PDFRenderer 字体/色彩**：渲染失败算闸门失败，回退文本层。
- **整篇图像且开关关**：行为与现在相同（可能整篇空）。这是故意的，避免默认产生账单。

## Non-Goals

- 不做 PPTX/docx/OCR 引擎。
- 不默认打开、不全量转写学习工作区 132 页。
- 不跑 after-citation / after-excel-header / after-multicondition。
- 不新增 Maven 依赖、不新增 controller。

## 失败场景

1. 无参/单参构造测红，或富文本 PDF 页锚点测在开关开时变红。
2. 默认关时仍调用 `ModelProvider.vision`。
3. VLM 失败导致整篇 ingest 抛错（必须回退）。
4. 为调通而把 enabled 默认改 true。
