# Tasks — add-vision-pdf-ingest-pilot

> 执行契约见 `openspec/git-workflow.md`。当前基线：master=`f146a56`，surefire **634**。
> 硬约束：任务组 1–4、6 全程 ¥0；**任务组 5 授权节点**。禁改构造器对外语义、禁把开关默认改 true、禁新增 Maven 依赖、禁跑 54 条真基准、禁提交 `_vlm_transcribe.py`。

## 0. 执行记录（执行 agent 填写）

- 阈值：min-text-chars=80 / max-pages=3 / enabled 默认 **false**（未改）
- surefire 实测：2026-09-16 `mvn -B -ntp test`，Tests run **639**（=634+5），Failures=0 Errors=0；`DASHSCOPE_API_KEY=''`
- 误外呼：**无**（未设 API key，未跑任务组 5，未设 RAG_BENCHMARK_REAL=1）
- 任务组 5：停等授权（1 页图像 PDF ≈1 次 vision）

## 1. 转写器（¥0）

- [x] 1.1 新增 `src/main/java/com/slz/crm/knowledge/document/PdfVisionTranscriber.java`：检测 / 渲染 144DPI 宽边≤1600 / `ModelProvider.vision` / 质量闸门 / max-pages / VISION 计量。Javadoc 标任务号
- [x] 1.2 Prompt 纪律：只转写可见内容、不清写「（截图不清）」、不猜测、不寒暄（对齐 `_vlm_transcribe.py`，不要把脚本提交进仓）

## 2. 接入解析 + 配置（¥0）

- [x] 2.1 `DocumentService.parsePdf`：开关开且转写成功则替换该页文本，pageNo 不变；失败保留文本层。无参/单参构造不注入 transcriber = 旧行为
- [x] 2.2 注册 `rag.retrieval.vision-pdf.enabled`（false）/ `min-text-chars`（80）/ `max-pages`（3）到 `DynamicConfigKeyRegistry`（命名空间 `rag.retrieval`，不扩 NAMESPACES）
- [x] 2.3 更新 `docs/dynamic-config-keys.md`

## 3. ¥0 单测

- [x] 3.1 默认关：无文本 PDF 不调 vision；整篇空仍抛「文档解析结果为空」
- [x] 3.2 开 + mock：无文本页得到转写，pageNo=1
- [x] 3.3 开 + 抛错 / 低质量转写：回退，不炸
- [x] 3.4 `semanticStrategyPreservesPageNoAnchorsOnPdf` 在 enabled=true 时仍绿（富文本不走 VLM）
- [x] 3.5 无参 `new DocumentService()` 不调 vision

## 4. ¥0 回归与 CI

- [x] 4.1 `mvn -B -ntp test` 全绿；读本次合计改 ci.yml 三处
- [x] 4.2 Docker 可选 skip

## 5. 真 VLM 试点（授权节点，非合入前置）

- [ ] 5.1 **停下**报：1 页图像 PDF 约 1 次 vision，成本远小于 54 条基准，仍须授权
- [ ] 5.2 （授权后）enabled=true 跑 1 页试点，记录转写长度与是否过闸门；失败回退则记下原因。禁止 132 页全量

## 6. 收尾

- [x] 6.1 HANDOFF：视觉 PDF 试点已落地、默认关；真 VLM 未授权则标明；三次 after-* 仍待授权
- [x] 6.2 git：`checkout -b feature/add-vision-pdf-ingest-pilot`；提案三件套随首个提交；亲验全绿 + status 干净（三个未跟踪件勿提交勿删除）后 `--no-ff` 合入 master；**不 push**
