# Tasks — trace-i05-citation-forensics

> 当前 master=`f9a4e55`，surefire **639**。任务组 1–3、5 为 ¥0；**任务组 4 授权才外呼**。禁改 Evaluator 公式、禁覆盖 v1/v2/after-quality-loop。

## 0. 执行记录

- only 实现：`RagRealRetrievalBenchmarkIT.applyOnlyFilter`（`-Drag.benchmark.only`，rewriteSuite 后）
- 取证文件：默认 `docs/rag-quality/i05-forensics.json`（`-Drag.benchmark.trace.out`）；主报告取证跑须显式 out 到 `i05-forensics-report.json`
- 授权跑结论三选一：（任务组 4 完成后回填）

## 1. 过滤与旁路（¥0）

- [x] 1.1 `RagRealRetrievalBenchmarkIT`：`rag.benchmark.only` 逗号分隔 id；未知 id 或过滤后空套件 assert 失败。Javadoc 标任务号
- [x] 1.2 evaluateCase 采集对齐前/后答案与 citations、retrieved 与 excerpt；跑完写 trace 文件（默认 `docs/rag-quality/i05-forensics.json`，可 `-Drag.benchmark.trace.out`）
- [x] 1.3 不改 `CaseScore` / `RagQualityEvaluator.citationPrecision`

## 2. ¥0 单测

- [x] 2.1 only 过滤单测（I-05 / 空属性 / 未知 id 失败）
- [x] 2.2 旁路 JSON 字段单测（假数据含 rawAnswer、alignedAnswer、citationsBefore/After、excerpts）

## 3. CI

- [ ] 3.1 `mvn -B -ntp test` 全绿，实测改 ci.yml 三处

## 4. 授权：只跑 I-05

- [ ] 4.1 **停下**报成本：47 chunk 嵌入 + I-05 改写/嵌入/生成/判卷，远小于 54 条，仍须授权
- [ ] 4.2 （授权后）
  ```
  $env:RAG_BENCHMARK_REAL='1'
  mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.only=I-05" "-Drag.benchmark.out=docs/rag-quality/i05-forensics-report.json" "-Drag.benchmark.trace.out=docs/rag-quality/i05-forensics.json"
  ```
  禁止覆盖 v1/v2/after-quality-loop；禁止注入 rag.retrieval.* / vision-pdf
- [ ] 4.3 根据旁路文件写下三选一结论到 `docs/rag-quality/i05-forensics.md`（漏引 / KEEP 错号 / remap 到另一非黄金）。**不改对齐器**

## 5. 收尾

- [ ] 5.1 HANDOFF：取证落地；若 4 未跑则标待授权
- [ ] 5.2 `feature/trace-i05-citation-forensics`；提案随首提；`--no-ff` 合入；不 push；三未跟踪件勿动
