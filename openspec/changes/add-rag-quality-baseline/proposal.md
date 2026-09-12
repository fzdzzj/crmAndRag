# 提案：RAG 检索质量基线（评估先行）

> 变更 ID：`add-rag-quality-baseline` ｜ 能力域：`rag-quality` ｜ 序列：检索链路优化 第 1/5 个（最先，无前置依赖）

## Why

rag-kb `08-评估与迭代.md` 路径 B 铁律：**先有度量再优化，别凭感觉调参**；`00-知识地图.md` §0：检索质量是整条链路的能力上限。后续 4 个提案（混合检索补全、上下文压缩、语义切分、查询侧增强）全部需要"基线不回退"的机器验收锚点——没有本提案，它们无法判断是否真变好。

现状三缺口（已核代码）：

1. **黄金集仅 6 条合成用例**：`RagBenchmarkSuite.standard()`（src/main/java/com/slz/crm/quality/RagBenchmarkSuite.java）的 `expectedChunkIds` 是占位符（如 `sales-flow-1`），注释明言"需与入库向量库的 chunkId 对齐"，但**无数据准备步骤**实现该对齐。
2. **无"词法精确型"用例**：现有 TEXT/TABLE/IMAGE/EDGE 四类，缺"型号/编号/专有名词精确命中"类——这是纯向量检索的已知短板（rag-kb 方案 16 的解决对象），也是提案 2 补全混合检索后的对照刚需。
3. **真检索基线不存在**：quality 模块仅有 fake 模式单测（`RagQualityEvaluatorTest`）；`RagQualityEvaluator` 的设计意图（"CI/生产用真 KnowledgeRetrievalPort + 已入库向量"）没有 runner 落地，`ModelProviderImplDashScopeIT` 因外网请求被排除出 failsafe 白名单，无任何落盘的基线数字。

`RagQualityEvaluator` 本身已具备口径：recall@k / precision@k / MRR / hitRate / citationPrecision / 答案要点覆盖 + TTFT/延迟/token/失败率 + D16 边界语义（零命中诚实=满分）。本提案**不造新轮子，只补数据、runner 和门禁**。

## What Changes

### 1. 基准集扩充与版本化（`com.slz.crm.quality`）
- `RagBenchmarkCase.Category` 新增 `LEXICAL`（词法精确型：型号、编号、专名，向量易漏召）。
- 套件 6 → **≥16 条**，五类全覆盖；LEXICAL ≥4 条（含至少 1 条"向量相似度低但文本精确包含目标词"的构造）。
- 套件带版本常量（`SUITE_VERSION`），进 `RagQualityReport.Report`，保证跨变更可比较。

### 2. 评测语料与数据准备（可复现）
- 固定评测文档集：`src/test/resources/rag-quality/fixtures/`（合同流程/回款计划/区域销售表/架构图描述 + 词法型文档：含型号、编号、专名）。
- 数据准备 runner：把 fixtures 幂等入库到指定评测 KB → 输出 chunkId 映射 → 黄金集 `expectedChunkIds` 与真实 chunkId **对齐或显式报错**（拒绝占位 id 静默计空集）。

### 3. 真检索基准 runner（环境变量门控）
- 新增 failsafe IT：真实向量库 + 真实模型（嵌入/改写）跑完整套件，输出含全部指标的 JSON 报告。
- **门控**：`RAG_BENCHMARK_REAL=1` + `DASHSCOPE_API_KEY` 才启用；未启用按 skip 处理（不失败）——沿用"本地无 Docker 不炸"原则，避免默认 CI 发真实外网请求。

### 4. 基线数字落盘
- 跑一轮真检索基准，报告落盘 `docs/rag-quality/baseline-v1.json`（含 suite 版本、指标、时间戳），作为提案 2–5 的"不回退/提升"对照锚点。

## Impact

- **规范**：`specs/rag-quality/spec.md`（ADDED Requirements，全新能力域）。
- **代码**：`com.slz.crm.quality`（Category 扩展、套件扩充、版本）；`src/test/java/com/slz/crm/quality/`（IT、数据准备 runner）；`src/test/resources/rag-quality/fixtures/`。
- **CI**：surefire 基线 467 只增不减；真检索 runner 不进默认 CI 路径（env 门控）；`ci.yml` 注释里补充 runner 说明（不改门禁逻辑）。
- **不改**：`platform/contract`、检索行为本身、DB schema。

## 风险

- 真检索指标受模型波动影响 → 报告带 suite 版本与时间戳，对比时以"不回退阈值"而非绝对值判定。
- fixtures 入库幂等性依赖存储清理 → runner 先按 documentId 清理再入库，幂等断言进单测。

## Non-Goals

- 不优化任何检索行为（那是提案 2–5 的事）。
- 不引入 17 方案中任何一项技术（本提案 = 方案 01 基线的度量设施）。
- 不做生成质量人工评估（答案要点覆盖沿用现有机器口径）。
