# 变更提案 · query-relevance-truth（三合一：融合级代价边界 + 图像相关性真值用例集 + 档位决策请求）

## 0. 统一主题

上一卡把近重复的代价算到了 **pair 级**（`R` / `S_same` / `C_cross`，锚 `near-dup-exchange-rate-v1.json`，verdict=`EXCHANGE-RATE`），并如实声明 `S_same` 只是**有害量上界**（假设 A2：不测共现）。本卡只做一件事：**把这个上界换成可度量的东西**——但换成的是「融合分口径的位移」，不是「人来贴标签」。

三组各自回答一个问题：

| 组 | 问题 | 回答方式 |
|---|---|---|
| G1 | 降权在**检索排序**里到底有没有可见效果？ | 读实际融合链路 + 推出**可证明的位移上界**与**可见条件**（零外呼） |
| G2 | 「同图/近似同图/异图」在**检索口径**下是否被正确召回？ | 沿用项目既有的 `expectedChunkIds` 真值机制，补三类图像用例并接 `RagQualityEvaluator`（零外呼） |
| G3 | 现在可以拍板了吗？还缺什么？ | 一页决策请求：三档 × 三口径代价 + 缺口的准确名称 + 签收栏 |

**本卡不请求外呼、不改生产常量、不改冻结契约、零迁移。** 若 G2 发现必须真跑才能定案，本卡**只出 opt-in 跑法与成本**，不跑。

## 1. G1 · 融合级代价边界（零外呼）

### 1.1 现状锚点（本轮读源码实测）

- **闸门语义**（`knowledge/retrieval/ImageSimilarityGate.java`，242 行）：`tierOf` = `≥0.9944 → SAME_IMAGE` / `≥0.9887 → NEAR_DUPLICATE` / 其余 `CROSS_IMAGE`；`gate()` **丢弃** `CROSS_IMAGE`；`downWeightNearDuplicates()` 把 `rerankScore × weight`（`0.75/0.6/0.5` 按三等分带），并用 `imageSimilarityBandWeightApplied` 标记做幂等。
- **链路顺序**（`KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases:246-283`）：文本多路召回 → 重排 → 图像路召回（`minScore = effectiveFloor = 0.9887`）→ `gate` → 重排 → **`downWeightNearDuplicates`** → **`fuseRoutes(text, image)`** → 按 `rerankScore` 降序取 top-k。**⇒ 降权发生在融合之前，不是融合之后。**
- **融合公式**（`RetrievalConfigResolver:233`，同文件 `resolveTextRouteWeight/ImageRouteWeight`）：**文本 0.7 + 图像 0.3，同一切片两路命中则累加**（score-based，非 RRF 排名制）。
- **两个可证明的推论**（本卡要落成断言）：
  1. **图像路内部**：三等分带按 cosine 单调、权重按带单调（`0.5 < 0.6 < 0.75 < 1`），且带边界连续 ⇒ `score = rerankScore × w(tier)` 在路由内**仍按 cosine 单调** ⇒ **降权在图像路内部不改变任何两两顺序**（只改绝对分）。
  2. **跨路融合**：因为融合是**分数制**（0.7/0.3），图像候选被乘 `w` 后其融合贡献从 `0.3·s` 降到 `0.3·w·s` ⇒ **位移上界 = `0.3·s·(1−w)`，取归一化上界 `s≤1` 得 `0.3×(1−w)` = 0.15 / 0.12 / 0.075**（`w=0.5/0.6/0.75`）。**可见条件**：只有当竞争对手的融合分与它的差 **≤ 该上界** 时才可能发生位移。
- **语料侧已有的完整矩阵**：校准锚 `docs/rag-quality/image-threshold-calibration-v2.json`（SHA-256 `82d24204…`，与上一卡锚登记值逐字相等）里 **540 对**覆盖全部 **276** 个 base 两两组合（same-class 100 + cross-class 176），即 **24×24 余弦矩阵是完整的** ⇒ 图像路内的 gap 分布可**离线精确**算出。

### 1.2 预注册判据 R1–R5

- **R1 公式核对**：融合权重与累加语义必须以**源码行**为证（`RetrievalConfigResolver:233`、`resolveTextRouteWeight/ImageRouteWeight` 的默认值）；若实测权重不是 `0.7/0.3`，按实测值重算并记录偏差，**不得沿用注释**。
- **R2 路由内单调性**：对 `[0.9887, 0.9944]` 上 5000 点等距采样 + 三档边界点，断言 `rank(score_gated) == rank(cosine)`（严格：同序，允许并列）；任一处反序即红（这说明我 §1.1 的推论 1 错了，必须上报而不是改判据）。
- **R3 位移上界**：对 `w ∈ {0.75,0.6,0.5}` 逐档计算 `0.3×(1−w)`（`0.075/0.12/0.15`），并落成常量断言（融合权重改了这条会红）。
- **R4 图像路 gap 分布**：从完整 24×24 矩阵算「同文档候选 vs 跨文档候选」的余弦 gap 分布（分位 + 最大），并换算成融合分 gap 上界（`0.3×Δcosine`），给出「在多少比例的同文档候选上，位移在数学上可能」这一**必要非充分**结论；必须写明它**不是**召回率。
- **R5 结论三选一**：`VISIBLE-BOUNDED`（速度上界 > 0 且可见条件已量化）/ `INVISIBLE-IN-ROUTE-ONLY`（只能证明路由内不可见，跨路可见性缺文本路分数，无法定案）/ `UNDECIDABLE-BY-DATA`。禁造第四种，且必须写明「仍需什么数据才可定案」。

### 1.3 写入面（G1）

新增 `src/test/java/com/slz/crm/quality/{NearDupFusionImpactAnalyzer.java, NearDupFusionImpactDecisionTest.java, NearDupFusionImpactGuardTest.java}`、`docs/rag-quality/near-dup-fusion-impact-v1.json`、`docs/rag-quality/near-dup-fusion-impact-v1.md`、`scripts/rag-quality/near-dup-fusion-impact-report.sh`。
**不碰**：`ImageSimilarityGate`、`KnowledgeRetrievalServiceImpl`、`RetrievalConfigResolver`、上一卡的两份锚与脚本。

## 2. G2 · 图像相关性真值用例集（沿用既有 `expectedChunkIds` 机制，零外呼）

### 2.1 现状锚点

- **项目既有的真值机制**：`RagBenchmarkCase#expectedChunkIds()`（黄金切片 id）→ `RagQualityEvaluator` 据此算 `recallAtK/precisionAtK/RR`；图像侧已被使用：`ImageCrossModalSuite:49`「`expectedChunkIds` = 同图切片 id，供 `RagQualityEvaluator` 判 recall」、`PdfIngestImageFixture:247`「单条用例：query 与 same 同图（再次编码），`expectedChunkIds` = 该图黄金引用 id」。
- **缺口**：现有图像用例**只覆盖 `SAME_IMAGE`（同图再编码）**，完全没有覆盖**近似带**（同一张图的不同退化版本）与**两类负样本**（跨文档近似图 = 真噪声；同文档不同图 = hard negative）。也就是说：项目已有真值机制，但没有把**近重复机制自身的判别面**接进这个机制。

### 2.2 交付

1. `ImageNearDuplicateTruthSuite`（新）：三类用例，truth 全部来自**语料 provenance**（`NearDuplicateCorpus` 的 `baseKey` / `kind` / `docKey`），**不含任何人工判断**：
   - **正例**：query = 某 base 的 `kind` 变体，期望 = 该 base 的黄金切片 id（都算召回成功）；
   - **负例 A（跨文档近似）**：query = base_i 变体，期望 = 空，且候选里 top-k 出现 `base_j`（j≠i，不同文档）即计污染；
   - **负例 B（同文档不同图，hard negative）**：query = base_i 变体，期望 = 仅 base_i 的相关切片，同文档其它 base 记为**相关但不必须**（照 `RagQualityEvaluator` 口径如实分类，不篡改口径）。
2. **评测接入口**：把用例喂给既有 `RagQualityEvaluator`（**不新造指标**），输出逐 floor 的 `recallAtK`（正例）与 `contaminationAtK`（负例 top-k 污染率，新指标名必须在文档里写明它是**基于既有 top-k 结果的派生量**，不是新口径）。
3. **零外呼**：图像路得分不是重新 embedding，而是**从校准锚的逐对余弦**重建排序（同一 base 的变体对 = 正例分，跨 base 对 = 负例分）；这样跑出来的是「图像路内检索级」结论，文档必须写明**适用范围**（不覆盖文本路/融合后排序）。

### 2.3 预注册判据 T1–T5

- **T1 真值可复算**：每条用例的 `expectedChunkIds` 必须能由 `NearDuplicateCorpus` 的键唯一定出（`baseKey → goldenId`），不得出现手写字面量 id；用例数与语料（24 base × 5 kind = 120 正例）逐数相等。
- **T2 口径复用**：`RagQualityEvaluator` 的既有公式零改动（`git diff` 证明）；`contaminationAtK` 只读取其结果，不改任何既有指标。
- **T3 逐 floor 表**：至少 8 档（含 `0.9887` 基线与 `0.982/0.98/0.975/0.95/0.90`）逐档给 `recallAtK` / `contaminationAtK` / 保留数。
- **T4 结构性自检**：`recallAtK + drop` 必须与上一卡 `R(f)` 一致（同一 120 个正例、同一 floor 判定）——**两条独立路径算出同一个数**，不一致即红。
- **T5 零外呼**：不读 `.env`、不设 `RAG_*_REAL`、不访问网络；锚内 `externalCalls=0`。

### 2.4 写入面（G2）

新增 `src/test/java/com/slz/crm/quality/ImageNearDuplicateTruthSuite.java`、`ImageNearDuplicateTruthEvaluationTest.java`、`docs/rag-quality/image-near-dup-truth-v1.{json,md}`。
**不碰**：`RagQualityEvaluator`、`RagBenchmarkCase`、`ImageCrossModalSuite`、`PdfIngestImageFixture`。

## 3. G3 · 档位决策请求（一页可签收）

### 3.1 交付

`docs/rag-quality/near-dup-floor-decision-request-v1.md`：把**三份证据**摆在同一页，供 Owner 直接签收或退回：

1. **pair 级价目表**（上一卡 `near-dup-exchange-rate-v1.json`）：`R` / `S_same` / `C_cross` 逐档；
2. **融合级位移上界**（G1 锚）：`0.3×(1−w)` 逐档 + 可见条件；
3. **检索级真值结果**（G2 锚）：`recallAtK` / `contaminationAtK` 逐档；
4. **仍缺什么**：文本路分数分布（决定跨路可见性）、真实查询-相关性标注（决定 A1「同文档 ⇒ 可能相关」是否成立）；
5. **签收栏**：三候选（`0.982` / `0.98` / `0.90`）+ 「维持 `0.9887`」+ 签名与日期；并写死「改档属新卡，本卡不含任何生产常量改动」。

### 3.2 预注册判据 D1–D4

- **D1 引用可核**：文档里出现的每个锚 SHA-256 必须由守卫测试**实算比对**（防手抄）；三份锚路径必须存在且 `externalCalls=0`。
- **D2 数字可复算**：文档里每个逐档数字必须能在对应锚里找到同名/同值字段（守卫测试逐项断言），禁止手写推导值。
- **D3 无越权措辞**：不得出现「已决定/已采用/建议上线」；只允许「候选 + 代价 + 待签收」。
- **D4 覆盖性**：三口径（pair / 融合 / 检索）+ 一条「缺什么」必须齐；缺任一即红。

## 4. 全局硬边界（三组并集，违反即整卡作废）

1. **生产常量零 diff**：`ImageSimilarityGate` 全常量、`NEAR_DUPLICATE_FLOOR`、`0.9944`、`0.75|0.6|0.5`、融合权重 `0.7/0.3`。
2. **生产链路零 diff**：`KnowledgeRetrievalServiceImpl`、`RetrievalConfigResolver`、`RagQualityEvaluator`、`RagBenchmarkCase` 均零改动。
3. **契约零 diff**：`frontend/openapi.yaml`、`target/openapi/openapi.yaml`。
4. **零真外呼**：不设 `RAG_*_REAL`、不读 `.env` 真键、不访问网络（含 DashScope 与 GitHub 之外的任何外部调用）。
5. **零迁移、零新增依赖**。
6. 上一卡两份锚（`near-dup-mechanism-decision-v1` / `near-dup-exchange-rate-v1`）**只读**，不修改、不重写。
7. G2 **不得触改**任何既有评测口径与用例文件。

## 5. 组间顺序与停步裁决

- 顺序 **G1 → G2 → G3**，三组**互不依赖**（G3 依赖 G1/G2 的锚，若其中一组停步，G3 只写已交付的两口径 + 把缺的那项标 `MISSING`，仍然可签收）。
- G1 若 R2 反序（我 §1.1 推论 1 被数据推翻）：**不改判据**，如实上报 `INVISIBLE-IN-ROUTE-ONLY` 之外的第三种结论并说明，**其余组继续**。
- G2 若 T4 两条路径算出不同数：说明上一卡 `R` 或本次正例集有错，**停下**该组并把差异写进报告，禁「取其一」。
- G3 若 D1/D2 无法满足（锚缺字段）：允许**降低文档断言强度**（删掉无法核对的数字），不得改锚。

## 6. 交付物汇总

| 组 | 交付物 | 性质 |
|---|---|---|
| G1 | 融合影响分析器 + 锚 json/md + 复算脚本 + 守卫 | 解析边界（B 档） |
| G2 | 真值用例集 + 评测接入 + 锚 json/md | 检索级证据（B 档） |
| G3 | 决策请求一页 + 守卫测试 | 决策输入（C 档偏 B） |

整卡复核档位 = **B 档**（无迁移、无冻结契约、无真跑外呼、生产链路零改动）。

## 7. 非目标

- 不改 `NEAR_DUPLICATE_FLOOR`、不改融合权重、不改闸门语义（改档 = 新卡）；
- **不做人工相关性标注**（本卡的真值全部来自语料 provenance；人标的必要性与操作单在 G3 第 4 条里写明，不实施）；
- 不跑真 embedding、不改 `RagQualityEvaluator` 口径、不新增指标口径（`contaminationAtK` 是既有 top-k 结果的派生量，文档必须这样写）；
- 不评估文本路/hybrid 融合后的召回（只到「图像路内 + 融合分上界」为止）。

## 8. 风险与取舍

| 风险 | 控制 |
|---|---|
| 把「融合分上界」误读成「召回损失」 | G1 §1.2 R4 明写「必要非充分」；G3 D3 禁越权措辞 |
| `contaminationAtK` 被当成新口径 | 文档写死它是 top-k 派生量；T2 断言 evaluator 零改动 |
| 真值仍是 provenance（不是用户意图） | G3 第 4 条把「缺什么」写成准确名称；不在结论里冒充真值 |
| 三组一次性大卡 | §5 逐组停步；G3 允许 `MISSING` 收口 |

## 9. 验收（整卡 + 分组）

- **整卡**：`mvn -B -ntp clean verify` BUILD SUCCESS；`bash scripts/check-test-baseline.sh` EXIT=0；`bash scripts/check-openapi-consistency.sh` EXIT=0。
- **G1**：R1–R5 逐条出表；锚 `externalCalls=0`。
- **G2**：T1–T5 逐条出表；T4 两路一致。
- **G3**：D1–D4 逐条出表；三口径齐或明确 `MISSING`。
- 分组判定分别出表，再给总判定。

## 10. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-24 | 立项初稿：三组判据 R1–R5 / T1–T5 / D1–D4；实测链路「降权在融合前、融合是 0.7/0.3 分数制」与两条可证明推论（路由内单调不变序、跨路位移上界 `0.3×(1−w)`）登记；G2 明确沿用既有 `expectedChunkIds` 真值机制而不新造口径。 |
| v1.1 | 2026-10-24 | 交付与收口记录：B 档复核 **PASS**（G1 5/5、G2 5/5、G3 4/4；R2 由复核方独立复算 5002 点 0 反序、T4 以第三路径回算 8 档零偏差）；主 Agent 审计裁决 **PASS**（自算改动面 16 文件 +3711/−4、四锚 SHA-256 逐字一致、生产链路零 diff）。**观察项处置**：① 白名单字面歧义 → 不成立（规格先于实现、基线重锚系门禁要求）／② `t4Match` 用 `==` 而 T4 断言用 1e-9 → 非阻断**不修**（8 档实测精确相等），代之以新纪律「锚字段比较容差须与断言同容差」／③ G2 负例落地为 base 再编码 → 已由 `deviations` 与签收页 §4 第 4 条双处登记，零编造优先，合理／④ R4 gap 样本小 → 「必要非充分」已披露／⑤（本轮新增）签收页「缺项（MISSING）：无」与 §4「仍缺什么」并置易粗读为「无缺项」→ 非阻断不修，§2/§4/§5 三处已披露。**M1**：merge `5549277`（树 `f426df7b…` 与分支 tip 逐字节相等，分支 `feature/query-relevance-truth` 永久保留）+ 干净合并树 `mvn -B -ntp clean verify` BUILD SUCCESS（surefire 341/2109/0/0/0、failsafe 44/145/13）+ 基线重锚 `source-revision=5549277`（无 `-dirty`）+ 基线笔 `d92d368`。 |
