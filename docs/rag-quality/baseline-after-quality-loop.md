# 质量闭环三单合并复测对照 v2（after-quality-loop）

> 分支：`feature/run-quality-loop-retest`（自 master `b2c44f2`）
> 跑法：纯默认矩阵，`RAG_BENCHMARK_REAL=1` + `RagRealRetrievalBenchmarkIT`，**未**注入任何 `rag.*` / `rag.benchmark.run`，**未**开 `vision-pdf.enabled`。
> 产物：`docs/rag-quality/baseline-after-quality-loop.json`（**不**覆盖 `baseline-v1.json` / `baseline-v2.json`）。
> 说明：fix-citation-alignment / add-excel-header-projection / fix-multicondition-recall 三单任务组 4 **合并为一次** 54 条真外呼，故产物名是 after-quality-loop，不是三个分文件。
> 跑时：2026-09-16T11:31:37.093951700Z；failsafe：Tests run 1 / Failures 0 / Errors 0 / Skipped 0，elapsed **197.0 s**。

## 1. 全套指标对照

| 指标 | v2 锚点 | after-quality-loop | Δ |
|---|---:|---:|---:|
| caseCount | 54 | 54 | +0 |
| recall@5 | 0.9105 | 0.9475 | +0.0370 |
| precision@5 | 0.2630 | 0.2778 | +0.0148 |
| MRR | 0.8210 | 0.9136 | +0.0926 |
| hitRate | 0.9600 | 1.0000 | +0.0400 |
| citationPrecision | 0.7843 | 0.8302 | +0.0460 |
| answerConsistency | 0.9475 | 0.9907 | +0.0432 |
| TTFT 均值 ms | 380.9074 | 427.2593 | +46.3519 |
| 总延迟均值 ms | 3665.6852 | 3439.6852 | -226.0000 |
| totalTokens | 51947 | 50744 | -1203 |
| failureRate | 0.0000 | 0.0000 | +0.0000 |
| suiteVersion | 2.0 | 2.0 | — |
| runProfile / chunking | V1 / fixed | V1 / fixed | — |
| runConfig | sparseOn=false contextOn=false matrix={} | {"sparseOn": false, "contextOn": false, "matrix": {}} | — |

## 2. 关键四条对照

| 用例 | 指标 | v2 | after-quality-loop | 备注 |
|---|---|---:|---:|---|
| I-05 | recallAtK | 1.0000 | 1.0000 | 持平 |
| I-05 | citationPrecision | 0.0000 | 0.0000 | **仍为 0（对齐目标未达，不调阈值）** |
| I-05 | answerConsistency | 1.0000 | 1.0000 | 持平 |
| TB-01 | recallAtK | 0.0000 | 1.0000 | **0→1（表头投影生效）** |
| TB-01 | citationPrecision | 0.0000 | 0.5000 | 0→0.5 |
| TB-01 | answerConsistency | 0.0000 | 1.0000 | 0→1 |
| TB-10 | recallAtK | 0.0000 | 1.0000 | **0→1（表头投影生效）** |
| TB-10 | citationPrecision | 0.0000 | 0.3333 | 0→0.333 |
| TB-10 | answerConsistency | 0.0000 | 1.0000 | 0→1 |
| T-14 | recallAtK | 0.5000 | 0.5000 | **仍 0.5（拆路未把 recall 抬到 1.0）** |
| T-14 | citationPrecision | 0.2500 | 0.2500 | 持平 0.25 |
| T-14 | answerConsistency | 0.6667 | 1.0000 | 0.667→1.0 |

同组对照（拆路回归哨兵）：

| 用例 | recall v2→new | citP v2→new |
|---|---|---|
| T-15 | 1.0000→1.0000 | 0.3333→0.0000 |
| T-16 | 1.0000→1.0000 | 0.5000→0.5000 |
| T-17 | 1.0000→1.0000 | 1.0000→1.0000 |

> T-15/T-17 **recall 未回退**（仍 1.0）。T-15 citP 0.333→0 为引用侧波动，非 recall 回退；T-14 门槛「recall≤0.5 且同组 recall 回退」的第二支未触发。

## 3. 其余 TABLE recall 是否回退

| 用例 | v2 recall | new recall | 状态 |
|---|---:|---:|---|
| TB-01 | 0.0000 | 1.0000 | UP |
| TB-02 | 1.0000 | 1.0000 | SAME |
| TB-03 | 1.0000 | 1.0000 | SAME |
| TB-04 | 1.0000 | 1.0000 | SAME |
| TB-05 | 1.0000 | 1.0000 | SAME |
| TB-06 | 1.0000 | 1.0000 | SAME |
| TB-07 | 1.0000 | 1.0000 | SAME |
| TB-08 | 1.0000 | 1.0000 | SAME |
| TB-09 | 1.0000 | 1.0000 | SAME |
| TB-10 | 0.0000 | 1.0000 | UP |
| TB-11 | 1.0000 | 1.0000 | SAME |
| TB-12 | 1.0000 | 1.0000 | SAME |
| TB-13 | 1.0000 | 1.0000 | SAME |

**结论：其余 11 条 TABLE recall 无回退**；TB-01/TB-10 由 0 升 1。

## 4. 硬门禁核对

| 门禁 | 结果 |
|---|---|
| I-05 citP 仍为 0 | **是（残留，已记下，不调阈值）** |
| TB-01 与 TB-10 仍双双为 0 | **否**（均升至 1.0） |
| T-14 recall≤0.5 且 T-15/T-17 回退 | T-14 仍 0.5；T-15/T-17 recall **未**回退 |
| 全套 citationPrecision < 0.7843 | **否**（0.8302 ≥ 0.7843） |
| suiteVersion≠2.0 或 failureRate>0 | **否**（suiteVersion=2.0，failureRate=0.0） |

## 5. 覆盖与外呼

- **是否覆盖 v1/v2**：否。`baseline-v1.json` mtime 仍 2026-09-13；`baseline-v2.json` mtime 仍 2026-09-16 13:15；新文件独立为 `baseline-after-quality-loop.json`。
- **外呼次数/token**：IT 无逐次调用日志，结构同 v2；本轮 `totalTokens=50744`（v2=51947，口径=答案生成+判卷）。估算 ≈54×(改写/嵌入/生成/判卷)+fixtures 嵌入，拆句查询可能多几次 embed，仍同量级。
- **RAG_BENCHMARK_REAL**：跑完已清空。

## 6. 摘要判断

- 全套 recall/MRR/hitRate/citP/ansC **相对 v2 全面上升**（hitRate 0.96→1.0，主要由 TB-01/TB-10 转 hit）。
- Excel 表头投影对 TB-01/TB-10 **有效**。
- 多条件拆路：**未**把 T-14 recall 从 0.5 抬到 1.0；同组 recall 未回退；ansC 改善。
- 引用对齐：全套 citP 上升，但 **I-05 citP 仍为 0**（目标未达，按契约不调阈值、不改黄金）。

