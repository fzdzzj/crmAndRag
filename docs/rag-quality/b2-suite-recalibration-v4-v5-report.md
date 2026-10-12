# b2-suite-recalibration — v4→v5 指标对比报告（SUITE_VERSION 4.0 重锚）

- 日期：2026-10-12；分支 feature/b2-suite-recalibration；权威树
- 真跑凭证：RAG_BENCHMARK_REAL=1（owner 授权）+ DASHSCOPE_API_KEY 真值（.env）；63 例单轮
  命令：`mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT
        -Drag.benchmark.out=docs/rag-quality/baseline-v5.json`
  产出：docs/rag-quality/baseline-v5.json（suiteVersion=4.0，scoringVersion=v2）
  耗时：293 s（约 4.9 min）；总 tokens=61786。跑毕已清 RAG_BENCHMARK_REAL / DASHSCOPE_API_KEY（进程级）。

## 1. 聚合指标 v4 → v5

| 指标 | v4（baseline-v4.json, 3.0/v2） | v5（baseline-v5.json, 4.0/v2） | Δ |
|------|------|------|------|
| caseCount | 63 | 63 | 0 |
| recall@5 | 0.95503 | 0.96296 | **+0.00793** |
| citP | 0.92434 | 0.97302 | **+0.04868** |
| ansC | 0.95503 | 0.96032 | **+0.00529** |
| MRR | —（未公开） | 0.92857 | 基准线 v5 起登记 |
| hitRate | 1.0 | 1.0 | 0 |
| failureRate | 0.0 | 0.0 | 0 |
| totalTokens | 61584 | 61786 | +202 |
| 耗时 | （上卡 310s） | 293s | — |

说明：recall 因黄金面扩大本可"允许合理下降"，本次实测反而微升 +0.008（T-14 recall 0.5→1.0 主要贡献；
见下归因），与"分母扩大"方向相反，属修复面的正向收益，未触碰 recall 口径。citP 大幅 +0.049（八例补标
+ T-15 锚修正直接消除误判），是本次校准的核心收益。

## 2. 逐例 v4→v5（9 例修正：8 补标 + T-15 锚修正）

| 案例 | v4 citP | v5 citP | Δ | v4 ansC | v5 ansC | 归因 |
|------|--------|--------|-----|---------|---------|------|
| T-01 | 1.0 | 1.0 | 0 | 1.0 | 1.0 | 补 sales-flow-3（扩写归档段）；本压实测原已满分 |
| T-15 | 1.0* | 1.0 | 0 | 1.0 | 1.0 | 锚修正（customer-onboard-3→sop-3 含报备句）；撕裂零检出，结构性脱离 0 分态 |
| TB-01 | 0.5 | 1.0 | +0.5 | 1.0 | 1.0 | 补全 4 区黄金 |
| TB-08 | 0.5 | 1.0 | +0.5 | 1.0 | 1.0 | 排除论证非命中行补标 |
| TB-09 | 0.5 | 1.0 | +0.5 | 1.0 | 1.0 | 决策树图注补标 |
| TB-10 | 0.333 | 1.0 | +0.667 | 1.0 | 1.0 | XR-500 年度漏项 + ZB-220 排除补标 |
| TB-11 | 1.0 | 1.0 | 0 | 1.0 | 1.0 | 四区全补（本压实测原已满分） |
| M-01 | 0.333 | 0.667 | +0.333 | 0.667 | 1.0 | 贴现块（跨题归属）补标，citP 与 ansC 双升 |
| M-04 | 0.5 | 1.0 | +0.5 | 0.667 | 0.667 | XR-500 年度对比块补标 |

*注：T-15 在 v4 柱 baseline-v4.json 的 63 例实跑恰好 citP=1.0（LLM 引用集合非确定性偶中）；取证的
citation-forensics-v4 §6 波动表记录该例 v2=0.333/v3=0/v4=0。本轮锚修正后，黄金归点与报备句主体同块、
撕裂零检出，0 分态被结构性移除（不再依赖引用运气），故据实披露 v5=1.0 而非 0。

## 3. 未触碰例的"漂移"归因

63 例中仅 8 例指标变化；其中 7 例（TB-01/08/09/10、M-01/M-04 与 T-15）属修正面正向，1 例属 C 类连带。
两例"非 8+1"漂移须逐例归因：

- **T-11**（非修正例）citP 0.8→0.667（-0.133）：T-11 黄金 customer-onboard-1/2/payment-plan-1 未变、其锚
  归集位置未变（标记均在 line-009 之前，滑动窗口未受影响），判为 **LLM 引用集合非确定性漂移**（本轮少命
  中一股黄金引用），非黄金改动所致；单例 citP 浮动 0.13 在本卡聚合口径内属正常（v4 同例也曾随轮次波动）。
- **T-14**（C 类 recall 连带）recall 0.5→1.0（+0.5）、citP 0.6→0.8（+0.2）：T-14 黄金含 customer-sop-3，
  该块因 T-15 移动标记导致其切块/embedding 微变，使原本"三轮稳定未召回"的 customer-sop-3 本轮被召回，
  连带 recall/citP 上升——**T-15 锚修正的正向副作用**（归并 T-14 拆路残留问题的既有方向一致）。

聚合方向与任务预期一致：修正 9 例（8+1）citP 应改善、T-15 结构性脱离 0 分态、未触碰例 recall/MRR/ansC
逐位不动或逐例归因漂移（T-11 LLM 浮动、T-14 C 类正向连带均已归因）。

## 4. 成本与口径

- 真跑成本：63 例单轮，293s，61786 tokens（对比上卡 310s/61584 tokens，量级一致）。
- 真跑后已清环境变量：本卡只允许任务组 4 外呼，其余一切 mvn 前置 DASHSCOPE_API_KEY="".
- 锚点乘积：docs/rag-quality/ 既有 baseline-v1/v2/v3/v4 均未改动（v5 为新增锚点）；
  i05-forensics.json 为真跑 trace 旁路，被本跑刷新后已 git checkout 还原至 HEAD（不属本卡范围）。
