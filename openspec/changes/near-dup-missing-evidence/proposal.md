# 近重复位移证据补齐（near-dup-missing-evidence）

> **档位：A 档**（真跑 + 外呼 + 决策页改动）。零生产代码、零迁移、零依赖、零契约字段。
> **来源**：Owner 选储备 ②「补齐三处缺失证据」。三项全部出自 `docs/rag-quality/near-dup-fusion-impact-v1.json` 的 `missingEvidence` 字段（上一卡 `query-relevance-truth` G1 的产物）。

## 1. 目标：把「未测」变成「已测 / 有界 / 仍缺且有原因」

上一卡结论 `verdict=INVISIBLE-IN-ROUTE-ONLY` 之所以只能停在这个状态，是因为三项数据缺失：

| # | 缺什么 | 为什么卡住结论 |
|---|---|---|
| ① | 文本路 rerank 分数分布（真实查询） | 跨路位移是否**实际发生**无法定案，只能给上界 `0.3×(1−w)` |
| ② | 真实查询共现观测 | 「图像候选与文本竞争者是否真的同屏、分差多大」无数据 |
| ③ | 变体↔第三图视觉余弦 | R4 与 G2 的竞争者分数以 **base 对为代理**，代理是否成立未验 |

本卡不追求「消掉全部不确定性」，而是追求**把每个不确定性换成一条可复算的实测或一条写明理由的缺口**。

## 2. 侦察已定论的事实（执行端不得重推，直接引用）

### 2.1 观测面已经存在，不需要改生产代码

| 事实 | 位置 | 对本卡的作用 |
|---|---|---|
| `SourceReference` 十字段含 `route` 与 `relevanceScore` | `src/main/java/com/slz/crm/platform/contract/SourceReference.java:14,26` | 融合结果**自带**分与路由，无需碰 `RetrievalCandidate` |
| `relevanceScore` = `candidate.rerankScore()` | `KnowledgeRetrievalServiceImpl.java:333` `.map(c -> toSourceReference(c.hit(), c.rerankScore()))` | 它就是**融合后分**（0.7/0.3 分数制累加之后） |
| 图分支判别位是 `sourceType` | `SourceReferenceFactory.java:19-22`（`IMAGE_CHUNK_PREFIX`）、`:69-79`（`imageReference`） | 图/文判别唯一可靠依据 |
| `route` 取值是 `vector\|keyword\|hybrid\|ocr` | `SourceReference.java:14` Javadoc | **陷阱**：`route` **不是**图文路由，**禁止**用它判模态 |
| 融合是分数制、降权在融合前 | `RetrievalConfigResolver.java:18-19,280,285`；`KnowledgeRetrievalServiceImpl.java:267,278` | 位移上界 `bound(w)=0.30×(1−w)` ∈ {0.075, 0.12, 0.15} |

### 2.2 共现从未被观测过（② 的真实缺口）

`ProductionGrayRealRunIT.java:309-320` 的写法是：

```java
List<String> retrieved      = retrieve(visionRetrieval, row.description(), queryVector, IMAGE_CATEGORY); // 图路单独一次
List<String> textRetrieved  = retrieve(textRetrieval,  row.description(), null, null);                    // 文本路单独一次
```

两次**独立** `retrieve` ⇒ 从来没有一次调用同时产出 TEXT 与 IMAGE 候选。所以 ② 不是「数据不够」，是「观测位点不存在」。

### 2.3 ③ 几乎零成本

`ImageNearDuplicateCalibrationIT.java:192`：

```java
assertEquals(corpus.imageCount() * 2, calls, "外呼次数必须等于图数 × 2");
```

即该 IT 对**每一张**图（24 base + 120 variant = **144**）都取了 A/B 双编码，`encodings`（图键 → `Encoded(first, second)`）里**已经在内存中持有全部 144 个向量**。全 144×144 余弦矩阵是**纯本地算术**，不增加任何外呼——上一卡只是没把它记下来。

本卡新 IT 用**单次编码**（144 次外呼，比原定标跑的一半）即可，代价是要做 2.2 节的偏差对账。

### 2.4 成本旋钮

| 项 | 现成口径 | 本卡用量 |
|---|---|---|
| 真跑 opt-in | `RAG_BENCHMARK_REAL=1` / `NearDuplicateCorpus` 系 IT 用 `OPT_IN_ENV`（`ImageNearDuplicateCalibrationIT:257-262`） | G1 用前者风格、G2 用后者 |
| 真实语料 | NIST SP 800-101r1（8 图）/ NIST SP 800-183（4 图）/ DOE（107 图），`MAX_IMAGES_PER_DOCUMENT` 是**成本帽**不是能力上限（`ProductionGrayRealRunIT` 实测 9 图→入库 7） | G1 复用同一套 3 份 PDF 资产 |
| 过滤跑禁忌 | `RagRealRetrievalBenchmarkIT:113-116,565`：`-Drag.benchmark.only` 过滤跑**禁止**当锚点 | G1 若借该 IT 必须全量 |

## 3. 判据（预注册，跑后不得改）

### G1 · 真实混合查询的双路共现与分差（真跑 + 外呼）

在真实 PDF 语料的真实知识库上，用**同一条查询同时携带文本与图像向量**发起**一次**检索调用，逐条记录 `(sourceType, relevanceScore, chunkId)`。

- **D1 共现定义**：一次调用同时返回 ≥1 条 `sourceType=IMAGE` 与 ≥1 条非 IMAGE 来源，才算一次「共现」。`cooccurrenceQueryCount / totalQueries` 必须落锚。**若整轮 0 次共现** ⇒ 判 `NO-COOCCURRENCE`，**停 G1 并如实上报**（不许退化成两次独立 retrieve 去凑数，那就是把 ② 的原缺口伪装成已补）。
- **D2 `downwardGap`**：对每个共现查询，`downwardGap = 图像候选的 relevanceScore − 比它低的竞争者中最大的 relevanceScore`；图像候选若已是末位，记 `null` 并单列 `imageCandidateIsLast`。取全轮 `minDownwardGap`。
- **D3 位移窗口判据**：`W_max = 0.30 × (1 − 0.5) × 1.0 = 0.15`（`w` 最小档 0.5、`cos` 最大 1.0 的极端值）。`minDownwardGap > W_max` ⇒ `verdict=NOT-DISPLACED-IN-CORPUS`；否则 `verdict=POSSIBLY-DISPLACED` 并逐条列出落入窗口的 `(query, gap)`。
- **D4 反事实重建（必须标注为重建）**：把定标锚 540 对里 `near-duplicate` 档的 `cos` 代入 `0.30 × cos × w`（三档 `w`）得降权后的图像融合分，与**同查询实测**竞争者分比较，记 `counterfactualDisplacingPairs`。锚内必须带 `assumption` 字段写死：「竞争者分差跨查询可迁移」是**假设**，不作因果结论。
- **D5 语料规模上限写进锚**：本轮真实查询数（预期 7 条量级）必须作为 `queryCount` 落锚，并写死「本卡结论只到**存在性 / 量级**，**禁止**外推为『生产上位移不可见』」。

### G2 · 变体↔第三图全矩阵（真跑 + 外呼 144 次）

- **M1 矩阵**：144×144 对称余弦（24 base + 120 variant），**每图单次编码**（144 次外呼）；对角实测自余弦落 `selfCosines`，不得硬写 1.0 而不记录实测值。
- **M2 偏差对账（锋利判据）**：与 v2 定标锚已登记的 `near-duplicate` 120 对 + `same-class` 100 对 + `cross-class` 176 对逐对比较，`maxAbsDeviation ≤ 1e-3` 才允许发布。**超阈值 ⇒ 停 G2 并上报**（编码非确定或模型已变），**禁止**把新矩阵当真相、也禁止「取较小偏差的那批」。
- **M3 交付结论**：`variant vs 第三图`（120 × 23 子矩阵，排除自身的 24 个 base）给分位 `p50/p90/p99/max`，并明确回答：是否存在 `cos ≥ NEAR_DUPLICATE_FLOOR=0.9887` 的点。
  - 有 ⇒ `proxyVerdict=PROXY-UNDERSTATES`（base 对代理**低估**带内竞争者，R4 与 G2 的进入带样本数偏少）。
  - 无 ⇒ `proxyVerdict=PROXY-SUFFICIENT`（须附 `maxVariantVsThird` 与到 floor 的距离）。
- **M4 回填代理缺口**：`image-near-dup-truth-v1.json` 的 `deviations`（负例以 base 对为代理）与融合锚 R4 的代理缺口，按 M3 结论逐条写「代理成立 / 低估 / 高估」。

### G3 · 证据收口（零外呼）

- **R1 新页** `docs/rag-quality/near-dup-displacement-evidence-v1.md`：逐条对应三项 `missingEvidence`，每项只允许三种状态——`已补（指向锚字段与 SHA）` / `仍有界（写清边界）` / `仍缺（写清原因与所需数据）`。
- **R2 更新签收页** `near-dup-floor-decision-request-v1.md` **§4「仍缺什么」**：三条按新结论改写并指向新锚 SHA。**§1–§3 与 §5 的任何数字、任何一行逐字不动**（三口径表是已签收材料，改它就是改签收依据）。
- **R3 守卫扩展** `NearDupFloorDecisionRequestGuardTest`：新增「新锚 SHA 实算比对」+「§4 不得引用不存在的锚文件」+ 原有三口径逐格断言**不得放松**（只准加，不准删或改宽）。

## 4. 硬边界（违反任一条 = 整卡作废）

1. **零生产代码**：`src/main/java` 全域零 diff（观测面已存在，改它说明侦察结论错了，应停下上报）。
2. **零迁移 / 零新依赖 / 零契约改动**：`src/main/resources/db/migration`、`pom.xml`、`frontend/openapi.yaml` 零 diff。
3. **上游锚只读**：`image-threshold-calibration-v2.json`、`near-dup-fusion-impact-v1.json`、`near-dup-exchange-rate-v1.json`、`image-near-dup-truth-v1.json` 四份**字节不变**（守卫实算 SHA 比对）。
4. **零编造**：任何数字必须来自本轮真跑输出或上游锚既有字段；缺数据就写缺，不许用「看起来合理」的值填表。
5. **真跑必须真 opt-in**：不许把未设 `RAG_*_REAL` 的跳过结果当绿；跳过即等于该组未完成。
6. **禁改口径凑结论**：判据在派发时已冻结，跑完发现不达标只能如实上报，不许事后调窗口/调阈值/换分母。

## 5. 停步（分组独立，不连坐）

| 触发 | 处置 |
|---|---|
| G1 全轮 0 次共现 | 停 G1，`verdict=NO-COOCCURRENCE` + 原因（单次调用产不出双路）；G2/G3 照常，G3 该条写 `仍缺` |
| G1 `src/main` 被逼得需要改动 | 立即停 G1 上报（侦察结论错），不许改生产 |
| G2 偏差对账超 1e-3 | 停 G2，报 `maxAbsDeviation` 与超阈值对数；G3 该条写 `仍缺` |
| G2 外呼配额中途失败 | 停 G2，落 `failedCalls` 实测值，不许删点重算 |
| G3 因前组停步 | G3 **不放弃**：把停步项写成 `仍缺 + 原因`，其余照写 |

## 6. 风险与取舍

| 风险 | 控制 |
|---|---|
| 用 `route` 判模态（它会返回 `vector/hybrid` 而非 IMAGE） | 卡面 §2.1 已标陷阱；守卫断言判别函数不引用 `route` |
| 7 条查询被当成「分布」 | D5 强制落 `queryCount` 并禁外推；文档写「存在性证据」 |
| 反事实重建被当成实测 | D4 强制 `assumption` 字段；md 必须把 D2（实测）与 D4（重建）**分两节**，不得并表 |
| 新矩阵与旧锚偏差被「选小的一批」掩盖 | M2 用 `maxAbsDeviation` 而非均值；超阈值停组 |
| 签收页被顺手改数字 | R2 只准改 §4；守卫断言 §1–§3/§5 表内数字仍与锚同值 |
| 真跑成本失控 | G2 单编码 144 次（而不是定标跑的 288）；G1 复用既有 3 份 PDF，不下载新资产 |

## 7. 验收

- **整卡**：`mvn -B -ntp clean verify` BUILD SUCCESS；`bash scripts/check-test-baseline.sh` EXIT=0；`bash scripts/check-openapi-consistency.sh` EXIT=0。
- **G1**：D1–D5 逐条出表；锚 `externalCalls` 实测值（不许写 0）、`queryCount`、`assumption` 在位。
- **G2**：M1–M4 逐条出表；`maxAbsDeviation` 实测；`proxyVerdict` 三选一。
- **G3**：R1–R3 逐条出表；签收页 §1–§3/§5 字节不变（`git diff` 自证只有 §4 变动）。
- 分组判定分别出表，再给总判定；停步项写清「停了哪组 / 其余组状态 / 已回退路径」。

## 8. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.1 | 2026-10-24 | **Owner 授权**（本卡派发前）：① 两项真跑外呼——G1 真实混合查询（`DASHSCOPE_API_KEY`）、G2 144 次视觉 embedding；② 在权威树从 `master` 检出分支 `feature/near-dup-missing-evidence`。授权范围**不含** `git push` / 改写历史 / 删分支 / 改库结构 / 新增依赖 / 改 `src/main`。同时确认一项**已披露的降级**：① 只能交付「7 条真实查询的分差实测（存在性证据）」，**不是**统计分布；要真分布须另立卡并先验证 72 例基准套件的检索端口是否为生产路。 |
| v1.0 | 2026-10-24 | 立项初稿。侦察定论三件事：① `SourceReference` 已自带 `relevanceScore`（= 融合后分）与 `sourceType`，观测面**无需改生产代码**；② `route` 是 `vector\|keyword\|hybrid\|ocr`，**不是**图文路由，模态判别只认 `sourceType`；③ `ImageNearDuplicateCalibrationIT:192` 每图双编码，全 144×144 矩阵零额外外呼，新 IT 单编码 144 次更省。据此把 ② 从「需造近重复库」降级为「真实混合查询一次调用观测」，把 ③ 从「需重跑定标」降级为「144 次单编码 + 偏差对账」。判据 D1–D5 / M1–M4 / R1–R3 预注册。 |
