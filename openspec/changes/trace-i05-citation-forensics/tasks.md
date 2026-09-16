# Tasks — trace-i05-citation-forensics

> 当前 master=`f9a4e55`，surefire **639→644**。任务组 1–3、5 为 ¥0；**任务组 4 已授权并完成**。禁改 Evaluator 公式、禁覆盖 v1/v2/after-quality-loop。

## 0. 执行记录

- only 实现：`RagRealRetrievalBenchmarkIT.applyOnlyFilter`（`-Drag.benchmark.only`，rewriteSuite 后）
- 取证文件：`docs/rag-quality/i05-forensics.json`（旁路）+ `i05-forensics-report.json`（单条报告，非锚点）+ `i05-forensics.md`（结论）
- 授权跑结论三选一：**KEEP 错号**（对齐前后均为 `[1]`→`sla-3` 非黄金；黄金 `sla-2` 在 rank 3；raw==aligned）

## 1. 过滤与旁路（¥0）

- [x] 1.1 `RagRealRetrievalBenchmarkIT`：`rag.benchmark.only` 逗号分隔 id；未知 id 或过滤后空套件 assert 失败。Javadoc 标任务号
- [x] 1.2 evaluateCase 采集对齐前/后答案与 citations、retrieved 与 excerpt；跑完写 trace 文件（默认 `docs/rag-quality/i05-forensics.json`，可 `-Drag.benchmark.trace.out`）
- [x] 1.3 不改 `CaseScore` / `RagQualityEvaluator.citationPrecision`

## 2. ¥0 单测

- [x] 2.1 only 过滤单测（I-05 / 空属性 / 未知 id 失败）
- [x] 2.2 旁路 JSON 字段单测（假数据含 rawAnswer、alignedAnswer、citationsBefore/After、excerpts）

## 3. CI

- [x] 3.1 `mvn -B -ntp test` 全绿，实测改 ci.yml 三处（639→644）

## 4. 授权：只跑 I-05

- [x] 4.1 成本：45 chunk 嵌入 + I-05 单条生成/判卷（已授权）
- [x] 4.2 真外呼 only=I-05，out→i05-forensics-report.json，trace→i05-forensics.json；未覆盖 v1/v2/after-quality-loop
- [x] 4.3 结论 **KEEP 错号** 写入 `docs/rag-quality/i05-forensics.md`。**未改对齐器**

## 5. 收尾

- [x] 5.1 HANDOFF：取证落地 + KEEP 错号结论
- [x] 5.2 `feature/trace-i05-citation-forensics`；提案随首提；`--no-ff` 合入；不 push；三未跟踪件勿动
