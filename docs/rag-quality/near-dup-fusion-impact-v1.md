# 近重复融合级代价边界锚 v1（query-relevance-truth · G1）

> **本文件的每一个数字都来自 `bash scripts/rag-quality/near-dup-fusion-impact-report.sh` 的固定表格**（stdout 前缀 `[QRT1][表 N/4 …]`，落盘 `target/near-dup-fusion-impact-report.txt`），无手算数字。
> 复算产出的机器可读锚：`docs/rag-quality/near-dup-fusion-impact-v1.json`（唯一写入口 = `NearDupFusionImpactDecisionTest` 的写模式 `-Dnear.dup.fusion.write=true`，默认模式逐字节校验）。
> 上一卡两份锚（`near-dup-mechanism-decision-v1` / `near-dup-exchange-rate-v1`）**零改动**；本卡零外呼、零生产常量改动。

## 0. 一句话结论

**INVISIBLE-IN-ROUTE-ONLY：降权在图像路由内已证不可见（5002 点 0 反序），跨路可见性缺文本路分数无法定案——但位移上界 0.15/0.12/0.075 已量成常量断言，跨路「位移在数学上可能」的同文档观测基线仅 1 条（必要非充分）。**

## 1. 链路事实（R1，源码行实测，非注释）

```
KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases（主链 :246-283）
  :258  文本多路召回 recallAllTextRoutes
  :262  文本重排 reranker.rerank
  :267  ImageSimilarityGate.downWeightNearDuplicates   ← 降权（图像重排之后）
  :278  configResolver.fuseRoutes                      ← 融合（降权之后 ⇒ downWeightBeforeFuse = true）
  :279  按 rerankScore 降序取 top-k
图像路内部（recallImage :415-434）：:422 floor = effectiveFloor(threshold) → :426 gate 三档分档/丢弃
RetrievalConfigResolver：:18-19 默认权重 DEFAULT_TEXT_ROUTE_WEIGHT=0.70 / DEFAULT_IMAGE_ROUTE_WEIGHT=0.30
  :233-234 fuseRoutes（「文本 0.7 + 图片 0.3，同切片两路命中则累加」）→ :280 首插乘 routeWeight、:285 同键累加
```

**结论 1（链路）**：降权发生在融合之前，不是融合之后。
**结论 2（融合语义）**：融合是分数制（0.7/0.3 同切片累加），不是 RRF 排名制——图像候选被乘 `w` 后，其融合贡献从 `0.3·c` 掉到 `0.3·w·c`。
（per-KB 覆盖重载值域同为 [0,1]：上界公式不变、数值随覆盖变化，见锚 `weights.perKbOverrideNote`。）

## 2. 两条推论的数值落证

### 推论 1 · 路由内不改序（R2，锚 `routeInternalMonotonicity`）

对 `[0.9887, 0.9944]` 上 5000 点整数插值等距采样 + 低/高档边界点（去重后 5002 点），以生产现值语义
`score(c) = c × w(tier(c))`（同图档 ×1、近似带按 `bandOf` 三档降权）逐点算路由分：

| 采样点 | 反序处数 | 最大单步降幅 | 严格递增 | verdict |
| --- | --- | --- | --- | --- |
| 5002 | **0** | 0.0 | true | **MONOTONIC** |

**推论 1 成立**：三档带按 cosine 三等分、权重按带单调（`0.5<0.6<0.75<1`）、带边界归上一档，降权在图像路由内部**不改变任何两两顺序**（只改绝对分）。

### 推论 2 · 跨路位移上界（R3，锚 `bounds`，常量断言）

| band | w | bound(w) = 0.3×(1−w) |
| --- | --- | --- |
| NEAR_HIGH | 0.75 | **0.075** |
| NEAR_MID | 0.6 | **0.12** |
| NEAR_LOW | 0.5 | **0.15** |

**可见条件**：只有当竞争对手的融合分与该候选之差 **≤ bound(w)** 时位移才可能发生。三条上界是**常量断言**（`NearDupFusionImpactDecisionTest` 逐档断言 0.075/0.12/0.15）：融合权重或三档权重一改，守卫必红。

## 3. 基线档 gap 分布（R4，锚 `gapDistribution`）

位移主体 = near-duplicate 对中 `cosine ∈ [0.9887, 0.9944)` 的候选（**7 个**，查询=变体，被降权方=其 base 黄金切片）；竞争者 = base 两两余弦中涉及其 base 且 ≥ floor 者（**2 条观测**；代理口径：锚内无变体↔第三图对，以 base 对为代理且未经验证）。

| 分层 | 观测 | 位移可能（w=0.75/0.6/0.5） | gap p50 | gap p90 | gap p99 | 最大 gap | 最大融合分 gap |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 同文档 | 1 | 1 / 1 / 1 | 0.001327 | 0.001327 | 0.001327 | 0.001327 | 0.000398 |
| 跨文档 | 1 | 1 / 1 / 1 | 0.004665 | 0.004665 | 0.004665 | 0.004665 | 0.001399 |

两条观测明细：同文档 = `doe-renewable-energy-framework-p5-b9 ↔ p5-b10`（候选 0.990256 vs 竞争者 0.988930）；跨文档 = `nist-sp800-101r1-p1-b1 ↔ nist-sp800-183-p1-b1`（候选 0.993401 vs 竞争者 0.988736，即上一卡登记的那对跨文档带内泄漏）。

**必要非充分声明（禁读成召回损失）**：本分布是位移「数学上可能」的**必要条件**（竞争者落在降权窗口内），**非充分**——位移发生还需真实查询共现（上一卡 A2 未测）与变体真实分数（代理未验证）；基线档 7 个带内候选里仅 2 个存在 ≥floor 的共现竞争者，两条都落在三档窗口内，但**这不构成任何召回率陈述**：位移 ≠ 召回损失。

## 4. R1–R5 逐判据照判（表 4/4）

| 判据 | 实测 | 结论 |
| --- | --- | --- |
| **R1** 公式核对 | 权重 0.70/0.30 与链路顺序以源码行实测为证（§1 表）；守卫回访行号仍落在标记上 | 满足 |
| **R2** 路由内单调性 | 5002 点反序 0 处（5000 等距 + 2 边界点） | 满足（推论 1 成立） |
| **R3** 位移上界 | 0.075/0.12/0.15 逐档常量断言 | 满足 |
| **R4** gap 分布 | 同文档/跨文档分列 + 分位/最大 + 必要非充分声明 | 满足 |
| **R5** 结论三选一 | `verdict=INVISIBLE-IN-ROUTE-ONLY`；`missingEvidence` 三项；`decisionOwner="OWNER"`；`externalCalls=0` | 满足 |

## 5. R5 判定与仍需数据

**verdict = INVISIBLE-IN-ROUTE-ONLY**：路由内不可见已被 R2 证明；跨路可见性缺「文本路 rerank 分数分布」，无法定案。仍需数据（锚 `missingEvidence`）：

1. 真实查询的文本路 rerank 分数分布——判定融合分差 ≤ bound 的竞争者是否存在（跨路可见性定案的前提）；
2. 真实查询的图像候选与文本候选共现观测（A2 的共现面）；
3. 变体↔第三图视觉余弦——R4 竞争者分数目前以 base 对为代理，未经验证。

## 6. 复算命令

```bash
# 全量复算（写模式 + 落盘固定表格；退出码 0 = 复算成功，INVISIBLE-IN-ROUTE-ONLY 也是 0）
bash scripts/rag-quality/near-dup-fusion-impact-report.sh

# 只看守卫（默认测试集内跑，秒级，不重算语料）
mvn -B -ntp test -Dtest=NearDupFusionImpactGuardTest

# 只跑复算单测（不写锚，默认模式与已提交锚逐字节校验）
mvn -B -ntp test -Dtest=NearDupFusionImpactDecisionTest
```

固定表格落盘位置：`target/near-dup-fusion-impact-report.txt`。
