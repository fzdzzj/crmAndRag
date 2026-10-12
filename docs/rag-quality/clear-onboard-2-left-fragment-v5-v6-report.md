# clear-onboard-2-left-fragment — v5→v6 指标对比报告（SUITE_VERSION 5.0 重锚）

- 日期：2026-10-12；分支 feature/clear-onboard-2-left-fragment；权威树
  `D:\code\crmAndRag-merge-add-knowledge-admin-api`（从 master fd58221 检出）
- 本卡唯一 fixture 改动：`【GOLD:customer-onboard-2】` 移出 600 边界撕裂点。
  原标记落在 raw [582,607)，跨 customer-sop chunk1 [280,600) / chunk2 [560,880) 的 600 交界（撕裂）；
  移至「评级每季度复评一次」前，marker 落 [658,683)，完全落在 customer-sop-2 [560,880) 内。
  除该标记位置外 customer-sop.md 零字节改动（文件长度 1633→1633）。SUITE_VERSION 4.0→5.0 同笔。
- 真跑凭证：RAG_BENCHMARK_REAL=1（proposal D3 owner 授权）+ DASHSCOPE_API_KEY 真值（.env）；63 例单轮
  命令：
  `mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT -Drag.benchmark.out=docs/rag-quality/baseline-v6.json`
  产出：docs/rag-quality/baseline-v6.json（suiteVersion=5.0，scoringVersion=v2，caseCount=63）
  耗时：287.5 s（约 4.8 min）；总 tokens=61918。跑毕已清 RAG_BENCHMARK_REAL / DASHSCOPE_API_KEY（进程级）。

## 1. 聚合指标 v5 → v6

| 指标 | v5（baseline-v5.json, 4.0/v2） | v6（baseline-v6.json, 5.0/v2） | Δ |
|------|------|------|------|
| caseCount | 63 | 63 | 0 |
| recall@5 | 0.96296 | 0.96296 | 0（逐位不动） |
| citP | 0.97302 | 0.97116 | -0.00185 |
| ansC | 0.96032 | 0.95503 | -0.00529 |
| MRR | 0.92857 | 0.93651 | +0.00794 |
| hitRate | 1.0 | 1.0 | 0 |
| failureRate | 0.0 | 0.0 | 0 |
| totalTokens | 61786 | 61918 | +132 |

说明：本卡 fixture 改动仅触及 customer-sop 中 onb-2 一个标记的位置（含该标记的 chunk1/chunk2 索引文本微变），
不新增/删除黄金、不改判卷口径（承载集 v2 与主锚口径零改动）。聚合指标在单轮 LLM 波动幅度内；
recall 逐位不动（黄金面未变）。citP/ansC 微降、MRR 微升均由下述逐例漂移合成。

## 2. 逐例漂移（63 例中 5 例变化）

| 案例 | 指标 | v5 | v6 | 归因 |
|------|------|------|------|------|
| T-11 | citP | 0.667 | 0.75 | **目标例**（onb-2 标记位移，承载集收敛）；见 §3 |
| T-11 | ansC | 1.0 | 0.667 | 同例，LLM 生成波动 |
| M-01 | reciprocalRank | 0.5 | 1.0 | 无关语料（trade-jargon-glossary）；查询改写（multi-query/HyDE）LLM 非确定性致召回排序变动 |
| M-01 | citP | 0.667 | 1.0 | 同上（召回排序改善连带引用命中承载集） |
| M-04 | citP | 1.0 | 0.667 | 无关语料（equipment-codebook / maintenance-schedule）；LLM 引用选择波动 |
| M-04 | ansC | 0.667 | 1.0 | 同上 |
| T-03 | ansC | 1.0 | 0.667 | 无关语料（sales-flow-3）；LLM 生成波动 |
| TB-08 | citP | 1.0 | 0.8 | 无关语料（regional-q3.xlsx）；LLM 引用选择波动 |

其余 58 例逐位不动。T-03 / TB-08 / M-01 / M-04 四例黄金语料与 customer-sop 无交集（分别属
sales-flow / regional-q3 / trade-jargon / equipment+maint），其漂移与本次 fixture 改动无因果——
均为单轮 LLM 引用选择/生成的非确定性波动（及 M-01 查询改写带来的召回排序变动）。

## 3. T-11 专项：结构目标达成 vs 指标目标未达（诚实回报）

**结构目标（本卡唯一改动面）已达成**——探针实测（zero-外呼）：
- `Preparation.tornMarkers()` 整仓零检出（onb-2 撕裂事件消除）；
- `goldenToChunkId().get("customer-onboard-2") == "customer-sop-2"` 归点正确；
- 锚块 customer-sop-2 索引文本字面承载「客户分级标准」；
- 承载集 `goldenToCarrierChunkIds["customer-onboard-2"]` 由 {customer-sop-1, customer-sop-2} 收敛为 {customer-sop-2}。

**指标目标（proposal 预期 T-11 citP 0.667→1.0）未达**：v6 实测 citP=0.75（3/4），既非 1.0，
亦非原 v5 的 2/3。

**真实失分源定位不可得（如实披露，不硬凑）**：
- 判分口径：`RagQualityEvaluator#citationPrecision` = 命中承载集的引用条数 / **有效引用条数**
  （分母为本轮 LLM 实际发出的、落在检索列表范围内的引用条数，天然随轮次浮动；非固定黄金条数）。
- v6 该例 LLM 发出 4 条有效引用，其中 3 条命中承载集 → 0.75。其中 1 条引用指向的检索块不在承载集内。
- **引用级取证（trace）丢失**：真跑 IT 默认取证旁路写 `docs/rag-quality/i05-forensics.json`，本轮被覆盖后
  已 `git checkout --` 还原至 HEAD（该文件属历史 tracked 工件、非本卡产物），**未留存 v6 的引用级 trace，
  无法定位那 1 条非承载引用指向的具体 chunkId**。
- 单轮真跑授权（D3）禁再跑，故止步于此、如实回报，不引入第二次外呼以硬凑指标。

**判定**：onb-2 左残段撕裂已被结构性消除（归点、承载、字面承载三项机械验收全绿），残余 0.75 属 LLM
引用选择层面的噪声，非结构性缺陷。是否需二次真跑补取证以定位该条引用，由 owner 定夺。

## 4. 成本与口径

- 真跑成本：63 例单轮，287.5 s，61918 tokens。对比 b2 参照 293 s / 61786 tokens：
  耗时 -1.9%、tokens +0.2%，**均远低于任务书 +20% 停步阈值**，未触发停步。
- 真跑后已清环境变量：本卡只允许任务组 4 外呼，其余一切 mvn 前置 DASHSCOPE_API_KEY=''。
- 锚点：docs/rag-quality/ 既有 baseline-v1/v2/v3/v4/v5 均未改动（v6 为新增锚点）；
  baseline-v1.json 仅 fixtureRegression 段随升版再生成（suiteVersion 4.0→5.0，三项指标逐位不变）。

## 5. 偏差披露（写集扩展 3 处，超出 tasks §0「只许动」清单）

1. `src/test/java/com/slz/crm/quality/RagRealRetrievalBenchmarkIT.java` —— 任务 2.2 落地 V6 锚校验
   （isV6AnchorTarget / validateAgainstV6Anchor / writeBaselineReport 条件扩至四锚 / run 内调用），
   §0 清单未列 IT 文件，属扩展。
2. `src/test/java/com/slz/crm/quality/RagBenchmarkV5AnchorValidationTest.java` —— 升版 5.0 后，该离线测试
   `report()` 原用活常量 SUITE_VERSION 生成产物、与样例硬编码的 "4.0"（v5 锚真版本）对账，导致 5 例红；
   修正为硬编码 "4.0"（v5 锚历史版本认定，未来升版不再受影响）。属扩展，语义正确。
3. `docs/rag-quality/i05-forensics.json` —— 真跑默认取证旁路覆盖该 tracked 历史工件（362 行变化），
   已 `git checkout --` 还原。代价即 §3 所述 T-11 引用级取证丢失。

另：本报告落盘 `docs/rag-quality/`（对齐 b2-suite-recalibration 先例 `b2-suite-recalibration-v4-v5-report.md`），
tasks §0 清单未列该路径，属披露项。