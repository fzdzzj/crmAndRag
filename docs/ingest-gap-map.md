# 视觉摄取差距对照（ingest-gap-map）

> 变更：`openspec/changes/research-visual-ingest`  
> 性质：**只读调研**。不实现解析器、不改 `src/main`、不调用 DashScope。  
> 先决：`fix-multicondition-recall` 已合入 master（merge `ca6298d`）。  
> 日期：2026-09-16

---

## 1. 本仓现状（代码实证）

证据路径均相对仓库根；下列断言均可被 grep / 读文件复核。

### 1.1 支持的扩展名

`DocumentService.SUPPORTED_EXTENSIONS` = `pdf` / `txt` / `md` / `markdown` / `xlsx` / `xls`（`src/main/java/com/slz/crm/knowledge/document/DocumentService.java`）。

- `supports(filename)` 仅按扩展名判断；**无** `pptx` / `docx` / 图片扩展名。
- 未命中扩展名 → `parse` 抛 `IllegalStateException("不支持的文件类型: …")`。

### 1.2 PDF：仅 PDFBox 文本层

`parsePdf`（同文件）：

1. `Loader.loadPDF(bytes)` 打开文档；
2. 逐页 `PDFTextStripper.setStartPage/setEndPage` + `getText`；
3. 每页产出 `DocumentPage(page, null, text)`，`pageNo` 从 1 起，`rowIndex=null`。

**没有**：

- 文本层长度阈值 /「图像型 PDF」分流；
- `PDFRenderer` / 位图渲染；
- OCR / VLM / 版面分析；
- 空页过滤（空文本仍进 `pages`；真正跳过发生在 `process` 里 `normalize` 后为空则 `continue`）。

若整篇归一化后无任何非空块 → `process` 抛 `IllegalStateException("文档解析结果为空: " + filename)`。  
这是当前唯一的「空文档」硬闸门；**没有**「空页占比 / （截图不清）比例 / 每页字符下限」等质量闸门。

### 1.3 Excel：EasyExcel + 表头投影（已合入）

`parseExcel`（同文件，`add-excel-header-projection`）：

- EasyExcel 同步读**首个工作表**，`headRowNumber(0)`；
- 启发式 `looksLikeHeaderRow`：非空格 ≥ 2（`HEADER_MIN_NON_EMPTY`）且每格长度 ≤ 32（`HEADER_CELL_MAX_LEN`）；
- 命中则 `projectRow`：`列名：值`、tab 拼接，**表头行本身不入库**；
- 未命中则 `normalizeRow`（升级前行为：仅单元格值 tab 拼接）；
- 锚点：`DocumentPage(1, rowIndex, text)` —— Excel 固定 `pageNo=1`，行号 1 起。

### 1.4 页模型与切分

- `DocumentPage(pageNo, rowIndex, text)`（`DocumentPage.java`）：Excel 一行视作一个页级锚点。
- 切分**不跨页**（`DocumentService` 类注释 + `process` 循环）：先按页/行，再对单页文本切块 —— 满足 D15 页级引用语义。
- 策略：`rag.chunking.strategy` = `fixed`（默认）| `semantic`；
  - fixed：`FixedChunkingStrategy`，`CHUNK_SIZE=320` / `CHUNK_OVERLAP=40`（冻结等价参数）；
  - semantic：`SemanticChunkingStrategy`，段长上限 `rag.chunking.max-chunk-size`（默认 480）。
- 空页归一化后跳过；关键词提取门槛 `MIN_TEXT_LENGTH=80`（仅影响 keywords，不挡入库）。

### 1.5 块头与 D15 锚点

- `ChunkHeaderText.wrap`：嵌入文本前缀 `【文件名 | 类目 | 第n页|第n行】`；**只进嵌入**，DB `chunk_text` / 向量原文 / `SourceReference.excerpt` 保持无前缀（展示分离）。
- 行锚点优先于页锚点（`rowIndex != null` → 「第n行」）。
- 契约：`platform/contract/SourceReference` 明确 D15 要求 `chunkIndex/pageNo/chunkId`；PDF 用 `pageNo`，Excel 用 `rowIndex`。
- 入库侧：`DocumentIngestionService` 调 `ChunkHeaderText.wrap` 后再嵌入。

### 1.6 与「图里有字」相关的既有信号

- 基准集 SUITE_VERSION 2.0 含 **IMAGE 6 条**（HANDOFF §3），说明产品侧已承认「图文/截图型」难度面存在；但摄取链路仍只吃文本层。
- 知识模块内 **无** `pptx` / `OCR` / `PDFRenderer` / `vlm` / `vision` 实现（对本仓 `knowledge` 树检索为空）。

---

## 2. 学习工作区现状（只读，未修改、未拷贝进仓）

出处（本机路径，勿提交）：

| 材料 | 路径 |
|------|------|
| 原始材料与提取质量 | `C:\Users\fzdzzj\Desktop\rag\rag-kb\sources.md` §一～§四 |
| 解析工程现实 / 表格 / OCR·Vision | `C:\Users\fzdzzj\Desktop\rag\rag-kb\02-文档解析与切分.md` |
| VLM 转写脚本 | `d:\code\crmAndRag\_vlm_transcribe.py`（未跟踪，勿提交） |
| PDF→PNG 渲染 | `C:\Users\fzdzzj\Desktop\rag\_extract\render.py`（已存在） |

### 2.1 课件几乎无可用文本层（sources.md §一）

黄佳 RAG 训练营课件 PDF 1–5：**文本层差～极差**（例如《索引优化技术》15 页仅约 0.7k 字符），实质内容在**页面图像**（图解、架构图、代码截图、对比表）。  
处理方式：**先渲染 PNG，再视觉精读/转写**，而不是依赖文本层。  
同目录论文 PDF 文本层优，说明「按文档类型分流」是工作区已验证做法。

规模锚点（sources.md §三）：5 套课件逐页 PNG **共 132 张**（`_extract/pages/`）；另有 PPTX 整页图 16 张（`_extract/pptx_media/`）。

### 2.2 渲染参数（render.py）

- 引擎：`pypdfium2`；
- `SCALE = 2.0`（约 144 DPI）；
- 宽度上限 1600px（超宽按比例缩小）；
- 输出 `_extract/pages/<课件名>/pNN.png`。

### 2.3 VLM 转写纪律（`_vlm_transcribe.py`）

- 默认模型：`qwen-vl-max`；端点 DashScope compatible-mode；
- Key **只读环境变量** `DASHSCOPE_API_KEY`，不落盘；
- Prompt 纪律：逐字提取、代码围栏、架构图文字+箭头、表格转 Markdown、无法确认标「（截图不清）」、**禁止猜测补全**、不编造数字/模型名/文献；
- 工程属性：幂等（已有 md 跳过）、指数退避重试、`--dry-run` / `--deck` / `--limit` / `--force`；
- 产物带机器转写头注释，供草稿精读参考，引用前需对照原图。

### 2.4 解析选型口径（02-文档解析与切分.md / [W20]）

- 通用 PDF 库 = 文本流提取；表格/复杂版面 → `pdfplumber` / `unstructured`；
- **扫描件 → OCR**；
- **高价值、小批量、复杂文档 → Vision 多模态**；Vision 按 token 单价通常比普通 Embedding **高几十到上百倍**，不适合海量普通文档；
- 表格原则：整块保留转 Markdown，不按行截断；列名/编号必须在解析阶段保住（否则检索补不回来）。

### 2.5 已知局限（sources.md §四）

图中极小注释可能误读；代码截图转写不保证可运行；不确定处保留「（截图不清）」标记，禁止「补全成看起来合理的值」。

---

## 3. 差距表

| # | 能力 | 工作区做法 | 本仓现状 | 适用性 |
|---|------|------------|----------|--------|
| 1 | 文本层 PDF vs 图像型 PDF 检测 | 人工/报告看每页字符量；规划 A3：CJK 比/字符每页阈值自动分流（`_rag优化交接.md` A3） | 无检测；一律 `PDFTextStripper`；空页仅在切分前跳过，整篇空才失败 | **需改写**（可移植「短文本→视觉」阈值思想；阈值与指标需本仓标定） |
| 2 | PDF 渲染为 PNG | `render.py`：pypdfium2，scale=2.0，宽 cap 1600 | 无渲染路径 | **需改写**（Java 可用 PDFBox `PDFRenderer`，或外包本地渲染步骤；参数可对齐） |
| 3 | VLM 转写 | `_vlm_transcribe.py`：qwen-vl-max + 禁止猜测 prompt + 幂等/重试/成本可控开关 | 无 VLM；禁止在本调研单外呼 | **需改写 + 需授权**（prompt 纪律可直接移植；生产需默认关、配额、审计） |
| 4 | PPTX | 文本层几乎空 → 导出整页图再视觉读；另有 pptx 深度提取脚本 | `SUPPORTED_EXTENSIONS` **不含** pptx；上传即「不支持」 | **需改写**（独立格式管道；非最小切口） |
| 5 | 扫描件 OCR vs VLM | [W20]：扫描件走 OCR；高价值复杂版面才 Vision | 无 OCR、无 VLM；扫描 PDF 文本层空 → 空壳/整篇失败 | **需改写**（OCR 与 VLM 是两条能力；课件图解更贴近 VLM） |
| 6 | 表格（xlsx vs PDF 内表） | 表格整块 Markdown；列名必须保住 | **xlsx 已投影**列名到数据行；PDF 内表只靠文本层线性抽取，无结构还原 | xlsx：**可直接沿用**；PDF 表：**需改写**（pdfplumber 类或 VLM 转 md 表） |
| 7 | 抽取质量闸门 | 文本层质量报告；转写保留「（截图不清）」；规划空页/过短分流 | 仅「全文档无块 → 抛异常」；无空页占比、过短页、不清标记比例闸门 | **需改写**（闸门失败应回退文本层或标记失败，避免静默空壳） |
| 8 | 与 D15 锚点（pageNo）对齐 | PNG 文件名 `pNN` ↔ 页码；转写按页落盘 | PDF 已有 1-based `pageNo` 贯穿 chunk / SourceReference / ChunkHeader | **可直接移植对齐方式**（视觉页必须写入同一 `pageNo`，禁止另造锚点语义） |
| 9 | 纯文本/Markdown | 工作区 kb 以 md 为主存储 | 本仓 `txt/md/markdown` 整文件作单页 `pageNo=1` | **可直接沿用** |
| 10 | 成本与外呼闸门 | dry-run 列文件；试点 `--limit`；全量前看规模 | 仓库已有 DashScope 成本闸门文化（基准/reingest 需授权） | **可直接移植流程**（实现提案必须先报预估再跑试点） |

---

## 4. 成本粗估（未真跑、未调模型）

口径：学习工作区课件量级 **132 页 PNG**（sources.md §三），模型假设与脚本一致：`qwen-vl-max`。

公开价目（调研时第三方/国际站汇总，**非本仓实测账单**；实现前须以百炼控制台当期价为准）：

- 量级参考：输入约 **$0.8 / 百万 tokens**，输出约 **$3.2 / 百万 tokens**（qwen-vl-max 常见挂牌；另有 Batch 半价等档，以官网为准）。
- 单页输入：提示词数百 token + **图像 token**（分辨率相关；官方单图上限可到万级 token 量级；render 在 ~144DPI/宽≤1600 时，保守按每页输入 **2k～8k tokens** 估）。
- 单页输出：结构化 Markdown，课件页常见 **0.5k～2k tokens**。

**132 页全量粗算（数量级，非报价）**：

| 假设 | 输入合计 | 输出合计 | 费用数量级（按上表挂牌） |
|------|----------|----------|--------------------------|
| 偏瘦（2k in / 0.5k out） | ~0.26M | ~0.07M | 约 **$1 内** |
| 中位（4k in / 1k out） | ~0.53M | ~0.13M | 约 **数美元** |
| 偏肥（8k in / 2k out） | ~1.1M | ~0.26M | 约 **$1～$2+ 输入侧主导，合计可到十余人民币～数十人民币量级** |

说明与约束：

1. 图像 token 计价与实际分辨率/模型版本强相关，上表只给**数量级**；密集代码截图页会顶向上沿。
2. [W20] 定性：Vision 单价常比 Embedding **高几十到上百倍** → 只适合**可控批量**，不适合无闸门海量摄取。
3. **下一张实现提案必须先报预估（页数 × 抽样实测 token × 单价）再申请试点授权**；本调研 **零外呼**，未验证真实账单。
4. 另计：渲染 CPU、存储 PNG、失败重试、人工抽检「（截图不清）」的工程成本。

---

## 5. 建议的下一张实现提案切口（恰好 1 个）

**推荐切口（原文，唯一）：**

> **图像 PDF 检测 + 单页 VLM 转写试点，默认关，质量闸门失败回退文本层。**

### 为什么是这一个

1. **问题最尖锐**：本仓 PDF 只抽文本层；工作区已证明企业课件/扫描式页面文本层近空、内容在图里——上线此类语料会得到空壳 chunk 或整篇失败，比再调检索权重更「符合常理」的杠杆。
2. **锚点可复用**：现有 `pageNo` / D15 / `ChunkHeaderText` 无需改语义；视觉页写入同一页码即可对齐引用。
3. **可默认关闭**：符合本仓动态配置与成本闸门文化；失败回退文本层，避免「一开就伤生产」。
4. **最小可验证**：检测阈值 + 单页试点（对齐脚本 `--limit`）即可用 IMAGE/人造图像 PDF fixture 做 ¥0 单测路径；真 VLM 必须另授权。

### 为什么不是 PPTX-first

- 本仓扩展名白名单尚无 pptx；要先做格式接入 + 整页图导出，链路比「PDF 已能打开、只差视觉分支」更长。
- 工作区主痛点页量在 **PDF 课件 132 页**；PPTX 是附加 16 页，应作为后续格式扩展。

### 为什么不是先上 OCR 引擎

- OCR 擅长「扫描纯文字页」；课件核心是**架构图 / 代码截图 / 复杂版面**，工作区已用 VLM 验证路径。
- [W20] 将 OCR 与 Vision 分列：扫描件 OCR，高价值复杂文档 Vision。本切口对准后者；OCR 可作为另一提案服务「纯扫描合同」类。

### 明确不在本切口

- 不改生产默认行为（开关默认关）；
- 不引入 PPTX/docx；
- 不在未授权时全量转写；
- 不替换已合入的 Excel 表头投影。

---

## 6. 调研执行声明

- **未调用 DashScope**（未跑 `_vlm_transcribe.py` 非 dry-run，未对任何模型发请求）。
- **未**把 `C:\Users\fzdzzj\Desktop\rag` 拷进本仓。
- **未**修改 / 提交 `_vlm_transcribe.py`、`_rag优化交接.md`、`_技术深化交接.md`。
- **未**修改任何 Java / pom / fixtures / baseline JSON。
- 本仓现状结论均来自当轮阅读 `DocumentService` / `DocumentPage` / `ChunkHeaderText` / `FixedChunkingStrategy` / `SourceReference`；工作区结论来自上表只读路径。

---

## 附录：执行记录（tasks §0）

| 项 | 值 |
|----|----|
| 读过的本仓路径 | `DocumentService.java`、`DocumentPage.java`、`ChunkHeaderText.java`、`FixedChunkingStrategy.java`、`DocumentIngestionService.java`（ChunkHeader 调用）、`SourceReference.java` |
| 读过的工作区路径 | `sources.md`、`02-文档解析与切分.md`、`_vlm_transcribe.py`、`_extract/render.py` |
| gap-map 落盘 | `docs/ingest-gap-map.md` |
| 是否误外呼 | **无** |
