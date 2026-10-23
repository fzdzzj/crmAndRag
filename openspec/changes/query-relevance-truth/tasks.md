# 任务清单 · query-relevance-truth（三合一）

> **打勾纪律**：每个 `[x]` 必须在**该行下一行**写 6 空格缩进的「补勾依据（日期 · 实测数字/命令输出）」；没有依据的勾视为未完成。
> **写入面纪律**：每组只写本组 §写入面 列出的路径；跨组写 = 整卡作废。
> **停步纪律**：某组判据不达标 → 只停该组（回退该组写入面），其余组继续；禁止「一组红就整卡放弃」。
> **本卡禁外呼**：任何一组都不许设 `RAG_*_REAL`、不许读 `.env` 真键、不许访问网络。

## 0. G0 · 立项与共用侦察（零外呼）

- [x] 0.1 记录两棵树同一提交、权威树在 `master`、`git status` 干净
      补勾依据（2026-10-24 · 权威树 `git rev-parse --abbrev-ref HEAD`=master、`git worktree list` 实测两树同指 c789e23（linked worktree 同仓）；`git status --porcelain` 仅含本卡新增 `openspec/changes/query-relevance-truth/` 与 `work/task-card-query-relevance-truth.md`）
- [x] 0.2 记录链路事实（源码行号 + 字面值）：`KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases` 的调用顺序、`downWeightNearDuplicates` 在 `fuseRoutes` **之前**、`RetrievalConfigResolver` 的文本/图像融合权重默认值
      补勾依据（2026-10-24 · 本轮读源码实测：`KnowledgeRetrievalServiceImpl.java:246-283` 主链顺序 = 文本多路召回 :257-259 → 文本重排 :262 → 图像召回+gate+重排+`downWeightNearDuplicates` :265-276（降权调用在 :267）→ `fuseRoutes` :277-278 → 按 `rerankScore` 降序取 top-k :278-281 ⇒ 降权在融合之前；`recallImage` :415-434（`gate` :426-428、`floor=effectiveFloor` :422）；`RetrievalConfigResolver.java:18-19` 默认 `DEFAULT_TEXT_ROUTE_WEIGHT=0.70` / `DEFAULT_IMAGE_ROUTE_WEIGHT=0.30`，:174-193 两 resolver，`fuseRoutes` :253-269（:262-263 解析权重），`mergeRoute` :271-288（:280 首插乘 routeWeight、:285 同键累加））
- [x] 0.3 记录闸门常量：`DEFAULT_THRESHOLD=0.9944` / `NEAR_DUPLICATE_FLOOR=0.9887` / 权重 `0.75|0.6|0.5`，并记 `git rev-parse HEAD`
      补勾依据（2026-10-24 · `ImageSimilarityGate.java:31` `DEFAULT_THRESHOLD=0.9944D`、:34 `NEAR_DUPLICATE_FLOOR=0.9887D`、:37/:40/:43 `NEAR_DUPLICATE_WEIGHT_HIGH/MID/LOW=0.75D/0.6D/0.5D`；开工 HEAD=`c789e23c64e03b679244e1a05b95322e654917f0`）
- [x] 0.4 复算校准锚 SHA-256 = `82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65`；确认 540 对覆盖全部 276 个 base 两两组合（same-class 100 + cross-class 176）
      补勾依据（2026-10-24 · `sha256sum` 实测 = 82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65 与登记逐字相等；pairs 540 对分层实测 same-image 144 + near-duplicate 120 + same-class 100 + cross-class 176，base-pair 合计 276 = C(24,2)，24 个 base 键实测去重）
- [x] 0.5 记录上一卡锚关键字段：`verdict=EXCHANGE-RATE`、`freeTier=null`、`firstImprovingFloor=0.988`、`bestValueFloor=0.982`、基线 `70/2/1/273`
      补勾依据（2026-10-24 · JSON 实测展开：`verdict=EXCHANGE-RATE`、`freeTier=null`、`exchangeSummary.firstImprovingFloor=0.988`、`bestValueFloor=0.982`、`baseline.floor=0.9887 / retainedNearDup=70 / keptSame=2 / keptCross=1 / dropped=273`、`baseline.R=0.583333`）

## 1. G1 · 融合级代价边界（零外呼）

### 1.1 分析器

- [x] 1.1.1 新增 `NearDupFusionImpactAnalyzer`：从源码/常量读入 `textWeight`、`imageWeight`、三档 `w`，算 `bound(w) = imageWeight × (1 − w)`（R3）
      补勾依据（2026-10-24 · 分析器 `bounds(0.30)` 三档 = NEAR_HIGH 0.75→0.075 / NEAR_MID 0.6→0.12 / NEAR_LOW 0.5→0.15，DecisionTest 逐档常量断言绿；imageWeight 从 RetrievalConfigResolver 源码正则实测 0.30（R1 不沿用注释））
- [x] 1.1.2 落 R2 路由内单调性：对 `[0.9887, 0.9944]` 5000 点等距 + 三档边界点，断言 `gatedScore = rerankScore × w(tier)` 的排序与 `cosine` 排序**同序**（并列允许）
      补勾依据（2026-10-24 · 整数插值 5000 点 + lowBandUpperBound/highBandLowerBound 去重后 5002 点，inversions=0、maxDrop=0.0、strictlyIncreasing=true、verdict=MONOTONIC；推论 1 成立）
- [x] 1.1.3 落 R4 gap 分布：从 24×24 完整矩阵算「同文档候选 vs 跨文档候选」余弦 gap 的分位 + 最大值，换算融合分 gap 上界 `imageWeight × Δcosine`
      补勾依据（2026-10-24 · 基线档带内候选 7 个、共现竞争者观测 2 条（同文档 1：doe-p5-b9↔p5-b10 gap 0.001327；跨文档 1：nist-101r1-p1-b1↔183-p1-b1 gap 0.004665），三档 w 下位移均数学可能；maxFusedGap 0.000398/0.001399 入锚 gapDistribution，p50/p90/p99 分位 nearest-rank）
- [x] 1.1.4 落 R5 三值判定：`VISIBLE-BOUNDED` / `INVISIBLE-IN-ROUTE-ONLY` / `UNDECIDABLE-BY-DATA`，并把「仍需什么数据」写成锚字段 `missingEvidence`
      补勾依据（2026-10-24 · verdict=INVISIBLE-IN-ROUTE-ONLY（R2 路由内 0 反序 + 跨路缺文本路分数不可定案）；missingEvidence 三项：文本路 rerank 分数分布 / 真实查询共现 / 变体↔第三图余弦）
- [x] 1.1.5 每条推论必须带**源码行号**（R1），若实测权重与注释不符按实测值重算并在锚内记 `weightSource`
      补勾依据（2026-10-24 · 源码行实测：权重 0.70@L18 / 0.30@L19、fuseRoutes 语义注释@L233 声明@L234、mergeRoute 乘权@L280 累加@L285、downWeight 调用@L267 < fuse 调用@L278（downWeightBeforeFuse=true）；守卫行号回访测试绿）
- [x] 1.2.1 `NearDupFusionImpactDecisionTest` 为**唯一写入入口**：默认逐字节校验，`-Dnear.dup.fusion.write=true` 才写 `docs/rag-quality/near-dup-fusion-impact-v1.json`
      补勾依据（2026-10-24 · 写模式生成锚后默认模式复跑逐字节一致（serialize 钉死 LF：`.replace("\r\n","\n")`+末尾 \n）；clean checkout 语义由行尾钉死保证）
- [x] 1.2.2 锚内必带：`criteria`（R1–R5 原文）/ `sourceAnchors`（校准锚与上一卡锚路径 + SHA-256 实算）/ `productionConstants`（逐字抄现值）/ `weights`（0.7 / 0.3 及出处行号）/ `bounds`（逐档）/ `gapDistribution` / `verdict` + `missingEvidence` / `externalCalls=0` / `decisionOwner="OWNER"`
      补勾依据（2026-10-24 · 锚实测含全部字段：corpusAnchorSha256=82d24204…（与登记逐字一致）、exchangeRateAnchorSha256=62e43dfb…、productionConstants 五常量、externalCalls=0、decisionOwner=OWNER）
- [x] 1.2.3 `NearDupFusionImpactGuardTest`：源锚 SHA 漂移即红、`externalCalls != 0` 即红、`verdict` 越界即红、`bounds` 与常量不自洽即红（改了融合权重或三档权重，守卫必须红）
      补勾依据（2026-10-24 · 守卫 9 测试全绿：SHA 实算/外呼/三选一+单调性一致/R2 现场复算/bounds 对源码正则与 ImageSimilarityGate/R1 行号回访/R4 自洽/criteria 逐字/常量回显）
- [x] 1.2.4 守卫自证：手工把锚里 `bounds` 的一个数改掉 → 守卫红；改回绿（留痕）
      补勾依据（2026-10-24 · 实测：bound 0.075→0.08 后 boundsMatchProductionConstants 红（"高档位移上界与公式不自洽" expected 0.075 was 0.08）；从备份还原后 9/9 绿）
- [x] 1.3.1 `docs/rag-quality/near-dup-fusion-impact-v1.md`：链路图（带行号）+ 两条推论 + 上界表 + **「必要非充分」声明**（不得写成召回损失）
      补勾依据（2026-10-24 · md 六节：链路图带行号 / R2 单调表 + R3 上界表 / R4 分布表 + 必要非充分声明 / R1–R5 照判 / R5 仍需数据 / 复算命令；数字全部来自锚）
- [x] 1.3.2 `scripts/rag-quality/near-dup-fusion-impact-report.sh`：写模式复算 + `tee target/near-dup-fusion-impact-report.txt` + 打印 verdict 与上界三行；退出码 0=复算成功（含 NO-GO 类结论），非 0=复算失败
      补勾依据（2026-10-24 · 脚本镜像上一卡形状：`>$LOG 2>&1` 落盘 + grep `[QRT1]` 表格 + grep 锚 verdict/externalCalls/三行 bound；退出码 0/非 0 语义同上卡）
- [x] 1.3.3 脚本实跑一次留痕
      补勾依据（2026-10-24 · `bash scripts/rag-quality/near-dup-fusion-impact-report.sh` 退出码 0，verdict=INVISIBLE-IN-ROUTE-ONLY externalCalls=0 bound 三行 0.075/0.12/0.15；重写后锚与手工写模式 cmp 字节一致）
- [x] 1.4.1 逐条给出 R1–R5 判定与依据
      补勾依据（2026-10-24 · md §4 表：R1 满足（源码行实测）/ R2 满足（5002 点 0 反序）/ R3 满足（常量断言）/ R4 满足（分布+必要非充分）/ R5 满足（INVISIBLE-IN-ROUTE-ONLY + missingEvidence））
- [x] 1.4.2 硬边界自证：`ImageSimilarityGate` / `KnowledgeRetrievalServiceImpl` / `RetrievalConfigResolver` / 上一卡两锚 + 脚本 零 diff
      补勾依据（2026-10-24 · `git status --porcelain` 仅本卡白名单新文件（14 项全 untracked），`git diff --stat -- <受保护文件集>` 空输出（生产三源文件 + 上一卡两锚与脚本 + openapi 零 diff））

## 2. G2 · 图像相关性真值用例集（零外呼）

### 2.1 用例集

- [ ] 2.1.1 新增 `ImageNearDuplicateTruthSuite`：24 base × 5 kind = **120 正例**，`expectedChunkIds` 由 `baseKey → goldenId` 唯一生成（T1），禁手写字面量 id
- [ ] 2.1.2 新增两类负例：跨文档近似（`base_i` 变体 vs `base_j`，i≠j，不同 `docKey`）与同文档不同图（hard negative，同 `docKey` 不同 base），truth 同样由 `NearDuplicateCorpus` 键导出
- [ ] 2.1.3 排序重建：图像路得分一律从校准锚的**逐对余弦**取（正例 = 同一 base 的变体对；负例 = 跨 base 对），**不做任何 embedding**（T5）

### 2.2 评测接入

- [ ] 2.2.1 把用例喂给既有 `RagQualityEvaluator`（零改动，T2），产出 `recallAtK`
- [ ] 2.2.2 派生 `contaminationAtK`（负例 top-k 污染率）：只读 evaluator 的 top-k 结果，**不新增评测口径**，文档写明它是派生量
- [ ] 2.2.3 逐 floor 表 ≥8 档：`0.9887` / `0.985` / `0.982` / `0.98` / `0.9785` / `0.975` / `0.95` / `0.90`（T3）

### 2.3 交叉校验与锚

- [ ] 2.3.1 T4 交叉校验：`recallAtK`（本次正例集）必须与上一卡锚同 floor 的 `R(f)` 相等；不等即停该组并上报差异，禁「取其一」
- [ ] 2.3.2 `ImageNearDuplicateTruthEvaluationTest` 为唯一写入入口（`-Dnear.dup.truth.write=true`），写 `docs/rag-quality/image-near-dup-truth-v1.json`；默认逐字节校验
- [ ] 2.3.3 锚内必带：用例数（正/负 A/负 B 逐项）、逐 floor 表、`externalCalls=0`、`scopeLimits`（明写「只到图像路内检索级，不含文本路与融合后排序」）
- [ ] 2.3.4 `docs/rag-quality/image-near-dup-truth-v1.md`：真值来源（provenance）+ 与既有 `expectedChunkIds` 机制的关系 + 三处适用范围声明

### 2.4 G2 照判

- [ ] 2.4.1 逐条给出 T1–T5 判定与依据
- [ ] 2.4.2 硬边界自证：`RagQualityEvaluator` / `RagBenchmarkCase` / `ImageCrossModalSuite` / `PdfIngestImageFixture` 零 diff

## 3. G3 · 档位决策请求（一页可签收）

- [ ] 3.1 写 `docs/rag-quality/near-dup-floor-decision-request-v1.md`：三口径（pair / 融合 / 检索）+ 「仍缺什么」+ 三候选（0.982 / 0.98 / 0.90 / 维持 0.9887）+ 签收栏
- [ ] 3.2 `NearDupFloorDecisionRequestGuardTest`：实算三份锚 SHA-256 与文档里的逐字一致（D1）；文档里每个逐档数字能在锚里找到同值字段（D2）
- [ ] 3.3 措辞检查（D3）：禁「已决定/已采用/建议上线」，只允许「候选 + 代价 + 待签收」
- [ ] 3.4 覆盖性（D4）：三口径 + 「缺什么」齐；若某组停步，该项标 `MISSING` 并写明原因
- [ ] 3.5 若 G1 或 G2 停步：把无法核对的数字删掉而非编造，并在文档顶部写「本页缺项 = X（原因）」

## 4. G4 · 全量门禁与照判回报

- [ ] 4.1 `mvn -B -ntp clean verify` BUILD SUCCESS（计数只增不减）
- [ ] 4.2 `bash scripts/check-test-baseline.sh` EXIT=0（**只在全量 `clean verify` 之后跑**）
- [ ] 4.3 `bash scripts/check-openapi-consistency.sh` EXIT=0
- [ ] 4.4 三份分组判定分别出表 + 一份总判定；停步项写清「停了哪组 / 其余组状态 / 已回退路径」
- [ ] 4.5 交付前自检：生产常量零 diff、融合权重零 diff、评测口径零 diff、零迁移、零新依赖、零外呼
- [ ] 4.6 回报 ≤300 字：产出路径 / 行数 / 校验结果 / 未解决项 / 待决策项

## 5. 铁律（违反任一条 = 整卡作废）

1. 不改生产常量与融合权重（`0.9944` / `0.9887` / `0.75|0.6|0.5` / `0.7` / `0.3`）。
2. 不改生产链路：`ImageSimilarityGate`、`KnowledgeRetrievalServiceImpl`、`RetrievalConfigResolver`。
3. 不改既有评测口径：`RagQualityEvaluator`、`RagBenchmarkCase`、`ImageCrossModalSuite`、`PdfIngestImageFixture`。
4. 不改契约（`frontend/openapi.yaml` 零 diff）、不加迁移、不加依赖。
5. 上一卡两锚（`near-dup-mechanism-decision-v1` / `near-dup-exchange-rate-v1`）**只读**。
6. 零外呼：不设 `RAG_*_REAL`、不读 `.env` 真键、不访问网络。
7. 真值只许来自语料 provenance；**禁止编造标注**、禁止用「看起来合理」的数字填表。
8. 分组回退只回退本组写入面。
