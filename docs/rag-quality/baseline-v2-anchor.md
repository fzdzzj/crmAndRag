# 基准集 v2 锚点（54 条真检索基准）

> 变更域：`expand-rag-benchmark` 任务组 4（授权节点，用户已授权真外呼）。
> 产出：`docs/rag-quality/baseline-v2.json`（`SUITE_VERSION=2.0`，54 条）。
> 跑法：`$env:RAG_BENCHMARK_REAL='1'; mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT -Drag.benchmark.out=docs/rag-quality/baseline-v2.json`
> （纯默认矩阵：不注入任何 `rag.*`/`-Drag.benchmark.run`，run=V1 profile，matrix={}，sparseOn=false，contextOn=false，chunking=fixed；key 从仓库根 `.env` 解析）。

## 1. 口径声明（与 v1 不可直接比较）

- **版本机制**：`RagBenchmarkSuite.SUITE_VERSION` 版本化——v1.0=18 条（历史锚 `baseline-v1.json`），v2.0=54 条（新锚 `baseline-v2.json`）。增删用例/改黄金片段必须升版本；跨版本报告不可直接比较（Report 携带版本字段供核对）。
- **五类配比**：TEXT 17 / TABLE 13 / IMAGE 6 / LEXICAL 14 / EDGE 4（合计 54，用户确认口径）。
- **难度面新增**（v1 无）：同义改写、跨 chunk 关联、多条件组合、多列交叉、数值区间、聚合比较、图注问答、近邻主题误导。v2 的 TABLE/EDGE 难度面在 v1 中不存在或极弱。
- **结论**：v2 指标**不可与 v1 数值直比**；两者比较仅作参考性观察（规模 18 vs 54、难度面不同、单条权重 5.6%→1.9%）。

## 2. 全套指标（2026-09-16，54 条，topK=5）

| 指标 | v2 实测 | v1（18 条，参考） |
|---|---|---|
| caseCount | 54 | 18 |
| recall@5 | **0.9105** | 0.9444 |
| precision@5 | 0.2630 | 0.2556 |
| MRR | **0.8210** | 0.8472 |
| hitRate | 0.96（48/54） | 1.0 |
| 引用率 citationPrecision | **0.7843** | 0.8056 |
| 拒答/一致性 answerConsistency | 0.9475 | 1.0 |
| TTFT（均值） | 380.9 ms | 366.9 ms |
| 总延迟（均值） | 3665.7 ms | 3450.3 ms |
| token（生成+判卷口径） | 51947 | 17032 |
| failureRate | 0.0 | 0.0 |

> token 口径沿用 IT 定义：只计"答案生成 + 判卷"两段（改写/嵌入的 usage 被 `EmbeddingService`/`RetrievalQueryRewriteService` 吞掉无法回收）。v2 token 放大主因 = 用例数 ×3 + 新增难度面判卷要点增多。
> 外呼次数：IT 无逐次调用日志，按结构估算 ≈ 54 条 ×（改写/嵌入/生成/判卷 每条约 3–4 次）+ 47 chunk 入库嵌入 ≈ 250 次 DashScope 调用（一次跑，未重试）。

## 3. 异常用例清单（非失败，均 success=true，显式记录不静默）

| 用例 | 类 | 现象 | 初步判断 |
|---|---|---|---|
| TB-01 | TABLE | recall=0，citP=0，ansC=0（"第三季度各区域销售额"聚合查询） | 聚合查询在 47 chunk 语料中黄金块未进 top-5，属新增难度面弱项 |
| TB-10 | TABLE | recall=0，citP=0，ansC=0（"计划工时超过 2 人时的维保项"数值区间） | 数值区间跨两表（maint-xr500-q / maint-kq9000-m），黄金块未召回 |
| T-14 | TEXT | recall=0.5，citP=0.25，ansC=0.67（多条件组合） | 部分召回，判卷只覆盖 2/3 要点 |
| I-04 | IMAGE | recall=1，citP=1，ansC=0.5（折扣审批决策树图注） | 黄金块命中、引用正确，但判卷要点只覆盖一半 |
| I-05 | IMAGE | recall=1，citP=0，ansC=1（服务台架构图注） | 黄金块命中但答案引用编号全错（引用他块），一致性满分 |
| E-01/E-03 | EDGE | hit=false 但 recall=1（近义闲聊，KB OFF 聊天路径） | 设计内：不检索直接对话，覆盖=1.0，v1 E-01 同语义 |
| E-02/E-04 | EDGE | recall=0，hit=false，citP=1，ansC=1（近邻主题误导/期望空） | 设计内：零命中诚实拒答满分（D16） |

处置：以上均为指标结果而非报错/黄金对齐失败，failureRate=0，无重跑；下一次语料/难度面迭代时优先观测 TABLE 聚合与数值区间两弱项。

## 4. 与 v1 的定性对照（仅参考，不数值直比）

- recall@5 0.9444→0.9105、MRR 0.8472→0.8210 的回落主要来自 v2 新增的 TABLE 聚合/数值区间（TB-01/TB-10 零召回）与 TEXT 多条件（T-14 部分召回）——这些难度面在 v1 18 条中不存在或极弱，属"测得更严"而非回退。
- EDGE 四条 hit=false 全部命中"拒答/闲聊"设计语义（v1 仅 2 条同型），hitRate 0.96 由此构成。
- 引用率 0.8056→0.7843、一致性 1.0→0.9475 的小幅下降集中在 IMAGE 图注问答（I-04/I-05）与多条件 TEXT——图注语料为文本路召回，属新难度面。
- 时延/TTFT 与 v1 同量级（总延迟 +6%，TTFT +4%），token 放大与用例数×3 及判卷要点增多相符。

## 5. 复现与产物

- 报告本体：`docs/rag-quality/baseline-v2.json`（含 runProfile=V1 / runChunking=fixed / runConfig 矩阵快照，凭报告可复现）。
- 跑时现场：`chunks=47 goldens=44`，全部黄金占位 id 对齐成功（对齐失败会显式报错，本轮无）。
- 门禁断言：`suiteVersion=2.0` 与 `RagBenchmarkSuite.SUITE_VERSION` 一致；failureRate 0.0 ≤ 0.25；hitRate 0.96 > 0。
