# Tasks — fix-i05-caption-chunk

> master 以当前 HEAD 为准（含 `00b906e` 取证）。surefire **644**。禁改 CitationAligner / FixedChunkingStrategy 的 320/40 / Evaluator。任务组 4 外呼须授权。

## 0. 执行记录

- fixture=sla-arch-diagram.md key=sla-arch；前缀去重 12（待 surefire 实测）；I-05 重跑 citP 待授权

## 1. 语料拆出（¥0）

- [x] 1.1 新增 `sla-arch-diagram.md`：图注原文 + `【GOLD:sla-arch-diagram】`，措辞与现 `sla-terms.md` 该段一致
- [x] 1.2 从 `sla-terms.md` 删除该段，其它 GOLD 不动
- [x] 1.3 `RagBenchmarkDataPreparer.FIXTURES` 登记；Javadoc 标「fix-i05-caption-chunk 任务 1.3」

## 2. ¥0 单测

- [x] 2.1 黄金块含接入层/台账与预警引擎，不含何建军/赔偿当月服务费
- [x] 2.2 文档前缀去重 11→12；54 条仍齐；`SUITE_VERSION` 仍 2.0

## 3. CI

- [ ] 3.1 `mvn -B -ntp test` 全绿，实测改 ci.yml 三处

## 4. 授权：仅 I-05 取证重跑

- [ ] 4.1 **停下**报成本（同 I-05 取证：全量 fixtures 嵌入 + 1 条生成判卷）
- [ ] 4.2 （授权后）
  ```
  $env:RAG_BENCHMARK_REAL='1'
  mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.only=I-05" "-Drag.benchmark.out=docs/rag-quality/i05-after-caption-chunk-report.json" "-Drag.benchmark.trace.out=docs/rag-quality/i05-after-caption-chunk.json"
  ```
  禁止覆盖 v1/v2/after-quality-loop/i05-forensics.json
- [ ] 4.3 结论写入 `docs/rag-quality/i05-after-caption-chunk.md`：citP 目标 1.0；仍 0 则停，不改对齐器

## 5. 收尾

- [ ] 5.1 HANDOFF
- [ ] 5.2 `feature/fix-i05-caption-chunk`；`--no-ff`；不 push；三未跟踪件勿动
