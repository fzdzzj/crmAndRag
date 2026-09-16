# 图像 PDF 单页真 VLM 试点记录（add-vision-pdf-ingest-pilot 任务组 5）

> 授权节点执行记录｜执行日期 **2026-09-17**｜分支 `feature/vision-pdf-real-pilot`（基于 master `ccbdbc1`）
> 授权范围：**1 页无文本层 PDF ≈ 1 次 vision**（`qwen-vl-plus`，compatible-mode）。
> 未跑 54 条 RAG 基准、未跑 132 页全量转写、未设 `RAG_BENCHMARK_REAL`、未改默认开关。

## 1. 试点结论（实测，未重试）

| 观测项 | 实测值 |
|---|---|
| 是否抛错 | **否**（`process` 正常返回；无 vision 异常） |
| vision 调用次数 | **1**（成本闸门：本次试点恰好 1 次） |
| 文本层长度 | 0 字符（夹具确为无文本层图像页） |
| chunk 数与 pageNo | **chunks=1**，**pageNo=1**（页锚点不变，D15 未受影响） |
| 转写正文长度 | **112 字**（去空白后）；汉字 63；「（截图不清）」0 次 |
| 是否含图上关键词 | **6/6 命中**：服务台、架构、接入层、调度层、数据层、台账 |
| 是否通过质量闸门 | **通过**（`PdfVisionTranscriber.passesQualityGate=true`） |
| 失败原因 | 无（未触发回退链） |
| 测试结果 | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`（failsafe） |

链路验证成立：无文本层 PDF → PDFBox 渲染 144DPI PNG → `ModelProvider.vision`（`qwen-vl-plus`）→ 质量闸门 → 转写正文按原 pageNo 进入切片。回退链本次未被触发（未观测到），其行为仍由 ¥0 单测（`DocumentServiceTest` 任务 3.1–3.5）覆盖。

## 2. 执行方式

```
# 分支
git checkout master; git checkout -b feature/vision-pdf-real-pilot

# 授权试点（key 走仓库根 .env，不打印、不入库）
$env:RAG_VISION_PDF_REAL='1'
mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=VisionPdfRealPilotIT"
$env:RAG_VISION_PDF_REAL=''
```

- 测试类：`src/test/java/com/slz/crm/knowledge/document/VisionPdfRealPilotIT.java`（`*IT.java` → failsafe；surefire 仍排除，**653 基线未变**）。
- 未设 `RAG_VISION_PDF_REAL=1` 时 JUnit 假设跳过（CI 恒 skip，不外呼）。
- 装配：真实 DashScope `ModelProvider`（原生 chat/embed + compatible-mode stream/vision，装配同 `ModelProviderImplDashScopeIT`）+ mock `DynamicConfigService`（`enabled=true` / `min-text-chars=80` / `max-pages=1`）+ `PdfVisionTranscriber` 三参构造 + `DocumentService` 两参构造。
- 夹具：PDFBox 生成 1 页 A4，整页贴一张 AWT 位图（`LosslessFactory`），**不使用任何 PDF 文本算子**，图上用 `Graphics2D` 写「服务台架构 / 接入层 -> 调度层 -> 数据层 / SLA台账」。`PDFTextStripper` 抽到 0 字符（实测）。

## 3. 转写正文摘录（前 10 行，非敏感）

```markdown
# 服务台架构

接入层 -> 调度层 -> 数据层

- **接入层**
- **调度层**
- **数据层**

---
```

（摘录为输出前 10 行；关键词命中断言显示「台账」等后段内容同样被转写。）

## 4. 边界与未做事项

- 默认配置**仍为关**：`DynamicConfigKeyRegistry` 中 `rag.retrieval.vision-pdf.enabled` 默认值仍为 `"false"`；本次未改 `PdfVisionTranscriber` / `DocumentService` / `DynamicConfigKeyRegistry`，仅新增 IT 与本文档。
- **未跑** 54 条 RAG 基准（每次 `RagRealRetrievalBenchmarkIT` 真外呼量级远大于本次）。
- **未跑** 132 页全量转写。
- 未改阈值（`min-text-chars=80` / `max-pages=3` 默认值未动）、未改 Prompt、未做失败重试。
- 生产打开 `vision-pdf.enabled=true` 前仍需单独授权（每图像页 1 次 vision）。