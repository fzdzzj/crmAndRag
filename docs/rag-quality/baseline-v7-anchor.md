# baseline-v7 落锚报告（v6.0 主套件首次真跑）

> 卡：`openspec/changes/anchor-v6-baseline-v7`（A 档真跑）。日期 2026-10-22。
> 唯一产物：`docs/rag-quality/baseline-v7.json`（`suiteVersion=6.0` / `caseCount=72`）。

## 1. 为什么需要这一锚

`RagBenchmarkSuite.SUITE_VERSION` 自 expand-rag-eval-blind-spots 升到 **6.0**（72 例：TEXT 21 / TABLE 13 /
IMAGE 6 / LEXICAL 15 / EDGE 4 / MISMATCH 9 / TEMPORAL 4）以来**从未真跑落锚**。历史锚 baseline-v1~v6 中最近
的 v6 锚是 `SUITE_VERSION=5.0` / 63 例的产物，因此 72 例套件此前"无可比基线"。本卡补这一缺口。

## 2. 真跑口径（非 mock）

| 项 | 取值 |
| --- | --- |
| runner | `RagRealRetrievalBenchmarkIT`（failsafe，`-Dit.test=RagRealRetrievalBenchmarkIT` 白名单） |
| opt-in | `RAG_BENCHMARK_REAL=1`（沿用既有键，**未新开开关**）+ `DASHSCOPE_API_KEY` |
| profile | `V1`（`matrix={}`、`runChunking=fixed`）——与 v6 锚同 profile，便于同口径阅读 |
| 真嵌入 | `EmbeddingService` → DashScope，语料 chunks=57 / goldens=53 真入库 |
| 真改写 | 检索管线真改写（`RagBenchmarkPipelineFactory` 装配） |
| 真生成 | 真模型作答（72 例逐例），`totalTokens=72347` |
| 真判卷 | `RagQualityEvaluator` 规则制判卷（recall/MRR/citationPrecision/ansC） |
| 耗时 | 341.2 s 单测时长，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` |
| 落盘 | 合并写（`writeBaselineReport`）：只替换自有顶层键，`scoringVersion=v2` 紧邻 `suiteVersion` 登记 |

## 3. v7 关键指标（实测）

| 指标 | v7（6.0 / 72 例） |
| --- | --- |
| meanRecallAtK | **0.9537** |
| mrr | **0.9014** |
| hitRate | **0.9853** |
| meanPrecisionAtK | 0.2944 |
| meanCitationPrecision | 0.9220 |
| meanAnswerConsistency | 0.9144 |
| failureRate | **0.0** |
| meanTtftMs / meanTotalLatencyMs | 408.3 / 4549.6 |
| totalTokens | 72347 |

## 4. 与 v6 不可比（跨版本声明）

| 锚 | suiteVersion | caseCount | 是否可比 |
| --- | --- | --- | --- |
| baseline-v6 | 5.0 | 63 | — |
| baseline-v7 | 6.0 | 72 | **与 v6 不可比** |

v6.0 新增多轮组 5 + 时间限定组 4 + D16 判分收紧，用例集、判分口径、聚合分母三处同时变，**任何与 v6 的数值
对比都不成立**（不折算、不跨锚比较）。`validateAgainstV7Anchor` 以 `suiteVersion` 与 `caseCount` 双闸硬拒跨版
本对账：拿 `baseline-v6.json` 当 v7 锚会直接红。

## 5. 锁测与回落

- 离线锁：`RagBenchmarkV7AnchorValidationTest`（纯 JVM 零外发，11 例）——在场按 `scoringVersion=v2` 对账 citP /
  recall / ansC（下限 = 记录值 × 95%），缺席/非 v7 产物整体静默跳过，写侧只让 v7 产物带 `scoringVersion`。
- 无 key 默认零外呼：本 IT 的 `assumeTrue(RAG_BENCHMARK_REAL=1)` + key 两道 Assume 未改动，日常 `mvn test` /
  CI 阶段 1 恒跳过。
- 后续重跑（覆盖本锚）：
  ```bash
  RAG_BENCHMARK_REAL=1 mvn -B -ntp test-compile failsafe:integration-test failsafe:verify \
    -Dit.test=RagRealRetrievalBenchmarkIT \
    -Drag.benchmark.out=docs/rag-quality/baseline-v7.json \
    -Drag.benchmark.trace.out=target/rag-quality/v7-forensics.json
  ```
  注意：`-Drag.benchmark.trace.out` 必须指到 `target/`，否则默认的 `docs/rag-quality/i05-forensics.json` 会被重写。
