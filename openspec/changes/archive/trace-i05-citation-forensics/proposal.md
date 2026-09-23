# 提案：I-05 引用取证（落盘答案原文与对齐前后编号）

> 变更 ID：`trace-i05-citation-forensics` ｜ 能力域：`rag-quality` ｜ 序列：质量闭环补证，不改对齐器
> 来源：after-quality-loop 中 I-05 仍 citP=0、ansC=1、黄金 rank 3。没有答案原文无法判断是漏引还是 KEEP 错号。禁止再猜阈值。

## Why

`CaseScore` JSON 只有分数，没有答案、`[n]`、retrieved id。对齐器假设是「标了错号」；同样符合数据的是「根本没标号」（漏引，对齐器按设计不补）。再改 CitationAligner 或黄金块都是盲打。

本单只取证：单条过滤 + 旁路 JSON。不改 citationPrecision 公式、不改 SUITE_VERSION、不覆盖 v1/v2/after-quality-loop。

## What Changes

### 1. `-Drag.benchmark.only=I-05`（可逗号多 id）
`RagRealRetrievalBenchmarkIT` 在 rewriteSuite 之后过滤。未设置 = 全套。过滤后 `caseCount` 会变成 1，**这份报告不是可比锚点**，禁止写进默认 `baseline-v1.json`。

### 2. 取证旁路文件
`evaluateCase` 记下（不进 CaseScore）：

- 用例 id / 问题 / 黄金 id
- retrievedChunkIds（排名序）
- 每条 source 的 1-based n、chunkId、excerpt（可截断 400 字）
- 对齐前答案全文、对齐后答案全文
- 对齐前 citations、对齐后 citations
- 该条 citationPrecision / recall / ansC（从评分回填或现场算）

跑完写 `docs/rag-quality/i05-forensics.json`（可用 `-Drag.benchmark.trace.out` 覆盖）。主报告仍按 `-Drag.benchmark.out`；取证跑必须显式 out 到 `docs/rag-quality/i05-forensics-report.json`，避免当基线。

### 3. ¥0 单测
- only 过滤：`I-05` 留下 1 条；空属性不滤；未知 id 得到空套件要显式失败（避免默默跑 0 条还报绿）。
- 旁路 JSON：用假答案序列化出对齐前后字段，不断言模型。

不改 `RagQualityEvaluator` / `CaseScore` 字段。

### 4. 真外呼取证（授权节点）
仅 I-05：仍会 **fixtures 全量嵌入**（InMemory 每次重建），但生成+判卷只有 1 条。比 54 条便宜一个数量级，仍须授权。

看旁路文件判定三选一（写进 forensics 结论段，不改代码）：

| 现象 | 含义 | 下一步（另案） |
|---|---|---|
| 对齐前后都无 `[n]` | 漏引 | 补引另案，不是 remap |
| 有 `[n]` 且对齐前后相同、指向非黄金 | 支撑分 KEEP 住了错号，或金块 excerpt 词面不够 | 取证后再决定是否动对齐器 |
| 对齐前错、对齐后仍错但变了 | remap 到了另一非黄金 | 记目标块与得分 |

## Non-Goals

- 不改 CitationAligner 阈值、不补漏引、不改 I-05 黄金。
- 不跑 54 条、不打开 vision-pdf、不打开 multi-query。
- 不把 1 条报告当新锚点。

## 失败场景

1. 未设 `only` 却覆盖了 v2 / after-quality-loop。
2. 未知 id 过滤成 0 条仍 BUILD SUCCESS。
3. 取证 JSON 没有对齐前答案或 excerpts。
