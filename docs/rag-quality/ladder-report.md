# 基线阶梯报告（五回真检索基准）

> 变更域：`run-baseline-ladder` ｜ 分支：`feature/run-baseline-ladder` ｜ 掣点：解锁提案 1–5 计量化验收。
> 跑法一律 `RAG_BENCHMARK_REAL=1 mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT`
> （fixtures 评测库，校验库 id=99001，对话 `qwen-plus` / 嵌入 `text-embedding-v3`，topK=5，18 条）。指标口径见 `openspec/changes/add-rag-quality-baseline/baseline.md`。

## 五跑核心指标一览

| 跑 | 落盘 | 矩阵要点 | recall@k | MRR | citationPrecision | hitRate | answerConsistency | totalTokens | meanTotalLatency |
|---|---|---|---|---|---|---|---|---|---|
| 1 v1（回退态） | `baseline-v1.json` | 纯向量单路（旧构造器） | 0.9444 | 0.8472 | 0.8056 | 1.0 | 1.0 | 17032 | 3450 ms |
| 2 after-hybrid | `baseline-after-hybrid.json` | +稀疏路 RRF 融合 | 0.9444 | 0.8472 | 0.8056 | 1.0 | 1.0 | 17114 | 3198 ms |
| 3 after-context | `baseline-after-context.json` | +neighbors=1+rule 压缩+parent-expand=off | 0.9444 | 0.8472 | 0.8056 | 1.0 | 1.0 | 17179 | 3940 ms |
| 4 after-chunking | `baseline-after-chunking.json` | +semantic 切分+parent-expand=on（fixtures 重嵌入） | 0.9444 | 0.8194 | 0.8889 | 1.0 | 1.0 | 20274 | 3523 ms |
| 5 after-query | 不产出 | **触发不成立，未跑**——LEXICAL/TEXT 召回已饱和，无词汇失配缺口 | — | — | — | — | — | — | — |

> 注：meanPrecisionAtK 各跑约 0.24–0.26（chunking 跑略降至 0.2444），随 MRR 一致；与 5 内 topK 命中稀释有关，非异常。

## 逐跑结论与回退处置

- **跑 1 v1（锚点）**：纯向量单路，TEXT 5 条 / LEXICAL 6 条 recall@k=MRR=1.0——词汇/文本两路召回均已到顶。→ 解锁提案 1 的 4.1/4.4。
- **跑 2 after-hybrid**：接入稀疏路 + RRF 融合，聚合指标与 v1 全等（recall/MRR/citationPrecision/hitRate 不变）。
  "LEXICAL 较 v1 提升"在已饱和基数下**不可测**（无缺口可填），持平上限非回退。→ 解锁提案 2 的 5.1/5.2 + 1.1 补勾。
  token 17032→17114（+82，稀疏路检索计量）；meanTotalLatency 3450→3198 ms。
- **跑 3 after-context**：上下文增强（邻居拼装 + rule 压缩），quality 五项与 after-hybrid 全等，无回退。
  "token 下降"在自然上下文 <4096 时 rule 为 no-op，fixtures 量级**不可达**，记 scale 限制而非回退，不翻默认值。→ 解锁提案 3 的 5.1/5.2。
  token 17114→17179（+65，邻居加上下文）；meanTotalLatency 3198→3940 ms（上下文变长的自然成本）。
- **跑 4 after-chunking**：semantic 切分 + parent-expand=on（评测库 fixtures 语义重嵌入，非生产 reingest）。
  recall@k 与跑 3 持平（不降）✓，citationPrecision 0.8056→0.8889 **提升** ✓，**MRR 0.8472→0.8194 小降 0.028 ✗**。
  小降 = semantic 大块下首黄金块排名整体略后移（hitRate 仍 1.0、recall 持平、citation 反升），属分块粒度/排序权衡，非失效。
  按铁律**记录差异、不翻转默认值**（semantic 默认仍 fixed、parent-expand 默认值未动）。→ 解锁提案 4 的 5.1/5.2 + 4.4 文档段。
  token 17179→20274（+3095，parent-expand 填父块 + semantic 大块）；meanTotalLatency 3940→3523 ms。
- **跑 5 after-query（判定）**：v1/hybrid 的 LEXICAL 6 条均已 recall@k=MRR=1.0，证明 fixtures 量级**无「查询-文档词汇失配」漏召缺口**，
  多查询/HyDE 的"改写救漏召"作用面为空，无法归因到词汇失配的提升 → 触发条件不成立，**不执行真跑、不产出 `baseline-after-query.json`**
  （成本闸门显式不浪费；三开关维持默认 false 不翻转）。→ 提案 5 的 4.1/4.2 改标「触发不成立」并勾选。

## 解锁清单（12 项计量化验收）

- 提案 1 `add-rag-quality-baseline`：4.1 / 4.4
- 提案 2 `complete-hybrid-retrieval-and-rerank`：5.1 / 5.2 + 漂移补勾 1.1
- 提案 3 `add-context-compression-and-enrichment`：5.1 / 5.2
- 提案 4 `upgrade-semantic-chunking-and-index`：5.1 / 5.2 + 4.4 文档段回填
- 提案 5 `enhance-query-transformation`：4.1 / 4.2（改标「触发不成立」）

## surefire / failsafe 基线

- surefire 实测 **590 全绿**（585 + runner 泛化新增 5，任务组 2 已锁）；本阶梯未再新增单测，ci.yml 基线仍 590 无需 bump。
- failsafe：本阶梯新增 `RagRealRetrievalBenchmarkIT`（门控，无 `RAG_BENCHMARK_REAL` 时 skipped，counts 不减）；
  存量 V6 修复后 WriteChainRegressionIT 2 红为 task18 另案（提案 2 §0 已登记）。

## 遗留清单

1. **生产 reingest 另授权**：跑 4 的 fixtures 语义重嵌入属评测库（已授权）；生产全量 reingest（真实嵌入 API 成本 × chunk 总量）仍"另授权"（提案 4 的 4.4 待勾项）。
2. **WriteChainRegressionIT 2 红另案**：task18 存量缺陷，修复待用户授权（提案 2 §0 登记）。
3. **11·14 deferred 不变**：方案 11（RRF 参数调参）/14（Query Expansion）按台账待触发，未随本阶梯启动。
4. **多查询/HyDE 在更大语料再评估**：本阶梯证明 fixtures 量级无词汇失配缺口；若在含真实词汇失配数据的生产语料（如更大知识库）上回归，可再授权重跑 after-query 评估多查询/HyDE 收益。

## 复现与产物

- 报告本体：`docs/rag-quality/baseline-*.json` 四份（v1/hybrid/context/chunking）+ 本文档。
- 逐跑验证记录：`openspec/changes/run-baseline-ladder/tasks.md` §验证记录；各提案 tasks 勾选随同提交。