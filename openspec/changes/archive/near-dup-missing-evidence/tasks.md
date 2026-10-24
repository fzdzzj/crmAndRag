# 任务清单 · near-dup-missing-evidence（三组）

> **打勾纪律**：每个 `[x]` 必须在**该行下一行**写 6 空格缩进的「补勾依据（日期 · 实测数字/命令输出）」；没有依据的勾视为未完成。
> **写入面纪律**：每组只写本组 §写入面 列出的路径；跨组写 = 整卡作废。
> **停步纪律**：某组判据不达标 → 只停该组（回退该组写入面），其余组继续；禁止「一组红就整卡放弃」。
> **真跑纪律**：未设 opt-in 的跳过 ≠ 通过；跳过即该组未完成。

## 0. G0 · 立项与共用侦察（零外呼）

- [x] 0.1 记录两棵树同一提交、权威树在 `master`、`git status` 干净
      2026-10-24 实测：两树 HEAD 同为 5519c43bd61c886eb2b129a7498e796d20e43d8d；权威树 rev-parse --abbrev-ref HEAD=master，status 仅本卡两未跟踪文件（任务卡+本目录）
- [x] 0.2 复述并**实测复核**三条侦察结论（不得照抄 proposal）：`SourceReference` 十字段含 `route`+`relevanceScore`；`relevanceScore` 由 `KnowledgeRetrievalServiceImpl:333` 传入 `candidate.rerankScore()`；`SourceReferenceFactory` 图分支以 `sourceType=IMAGE` + `chunkId=IMAGE:{assetId}` 判别
      2026-10-24 实测：SourceReference.java record 十字段（26 route / 34 relevanceScore）；KnowledgeRetrievalServiceImpl.java:333 map(candidate -> toSourceReference(candidate.hit(), candidate.rerankScore()))；SourceReferenceFactory.from 以 imageAssetId 判空分支，imageReference 落 SOURCE_TYPE_IMAGE + IMAGE_CHUNK_PREFIX+assetId
- [x] 0.3 实测确认 `route` 取值域不含 `IMAGE`（读 Javadoc + 全仓 grep `route` 的赋值点），并在锚内落 `routeIsNotModality=true`
      2026-10-24 实测：SourceReference.java:14 Javadoc=vector|keyword|hybrid|ocr；全仓 new SourceReference( 恰 3 处（Factory:55/71 硬编码 "hybrid"、BusinessRetrievalService:349 "vector"）；锚 productionConstants.routeIsNotModality=true + routeDomain 落定，守卫断言 routeDomain 不含 IMAGE
- [x] 0.4 复算上游四锚 SHA-256 并记录：`image-threshold-calibration-v2.json`（`82d24204…`）、`near-dup-fusion-impact-v1.json`、`near-dup-exchange-rate-v1.json`、`image-near-dup-truth-v1.json`
      2026-10-24 实算：82d24204fba80296…bdc65 / 43c54ba633464888…03f4 / 62e43dfb46d79be9…2e36d / d84838c2c2ed31e0…d9a0，全量 64 位落两份新锚 sourceAnchors，守卫实算比对通过
- [x] 0.5 记录闸门常量与融合权重字面值：`DEFAULT_THRESHOLD=0.9944` / `NEAR_DUPLICATE_FLOOR=0.9887` / `0.75|0.6|0.5` / `0.70` / `0.30`，并记 `git rev-parse HEAD`
      2026-10-24 实测：ImageSimilarityGate:31 =0.9944D、:34 =0.9887D；RetrievalConfigResolver:18/19 =0.70/0.30；w 三档 0.75|0.6|0.5（fusion 锚 bounds+productionConstants 同值）；baselineHead=5519c43bd61c886eb2b129a7498e796d20e43d8d 落两锚
- [x] 0.6 记录 Owner 外呼授权与原话范围（`proposal.md` §8 v1.1）；确认分支从 `master` 检出、**未**获 push 授权
      2026-10-24：授权=两项真跑外呼（G1 混合查询 / G2 144 次视觉 embedding）+ 权威树检出 feature/near-dup-missing-evidence；分支已检出（checkout -b 自 master@5519c43），全程未 push

## 1. G1 · 真实混合查询的双路共现与分差（真跑 + 外呼）

### §写入面
`src/test/java/com/slz/crm/quality/NearDupDisplacementEvidenceIT.java`、`src/test/java/com/slz/crm/quality/NearDupDisplacementEvidenceGuardTest.java`、`docs/rag-quality/near-dup-displacement-evidence-v1.json`、`docs/rag-quality/near-dup-displacement-evidence-v1.md`

- [x] 1.1 新 IT 在同一条查询上**同时**给 `text` 与 `imageVector`，发起**一次** `retrieve`（禁止两次独立调用拼凑），读 `sources()` 逐条落 `(sourceType, relevanceScore, chunkId)`（D1）
      2026-10-24 实测：NearDupDisplacementEvidenceIT.runMixedQueries 一次 retrieve(queryText=VLM 描述, imageVector=PNG 重编码)，7 查询×5 sources 全量落锚 queries[].sources（sourceType/chunkId/relevanceScore/pageNo）
- [x] 1.2 语料基座复用既有 3 份真实 PDF（NIST SP 800-101r1 / SP 800-183 / DOE），**不下载任何新资产**；记为 `corpusAssets` 落锚
      2026-10-24 实测：corpusAssets=3（85/30/42 页，sha256 同登记表）；守卫 corpusAssetsMatchRegisteredFiles 实算资产 SHA 一致
- [x] 1.3 落 `cooccurrenceQueryCount / totalQueries` 与 `queryCount`；若 0 次共现 → `verdict=NO-COOCCURRENCE` 并停 G1（D1/D5）
      2026-10-24 实测：queryCount=7、cooccurrenceQueryCount=6（非零，未触发停步）；守卫重算 cooccurrence 与查询级逐条口径一致
- [x] 1.4 逐查询算 `downwardGap`（图像候选分 − 比它低的最大竞争者分），末位记 `null` + `imageCandidateIsLast`，落 `minDownwardGap`（D2）
      2026-10-24 实测：7 条逐查询 gap（0.24/0.0138/0.0094/0.24/0.0098/0.0457/0.0319），minDownwardGap=0.00941087658500056（逐查询与共现子集两口径同值）；imageCandidateIsLast 语义=图像候选存在但全末位
- [x] 1.5 按 `W_max=0.15` 判定 `NOT-DISPLACED-IN-CORPUS` / `POSSIBLY-DISPLACED`，`POSSIBLY` 必须逐条列出 `(query, gap)`（D3）
      2026-10-24 实测：minDownwardGap 0.0094 ≤ 0.15 ⇒ verdict=POSSIBLY-DISPLACED；withinWindow 5 条逐条落锚（4 条共现 + 1 条非共现 IMAGE↔IMAGE，md §1 披露）
- [x] 1.6 反事实重建：把定标锚 `near-duplicate` 档的 `cos` 代入 `0.30×cos×w` 三档，与同查询实测竞争者分比，落 `counterfactualDisplacingPairs` + `assumption` 字段（D4）
      2026-10-24 实测：comparisons=2160（6 共现查询×120 cos×3 档）、displacingPairs=1479（0.75 档 365 / 0.6 档 502 / 0.5 档 612）；assumption 写死「竞争者分差跨查询可迁移」是假设、不是实测
- [x] 1.7 锚内必带：`criteria`（D1–D5 原文）/ `sourceAnchors`（四锚路径 + 实算 SHA）/ `productionConstants` / `queryCount` / `externalCalls`（实测值，**不许写 0**）/ `verdict` / `assumption` / `corpusAssets` / `decisionOwner="OWNER"`
      2026-10-24 实测：externalCalls=77（vision 9 + embedImage 14 + embed 54，TrackingProvider 真调用点计数）；其余字段全在位，守卫 8 测试逐项校验
- [x] 1.8 守卫 `NearDupDisplacementEvidenceGuardTest`：源锚 SHA 漂移即红、`verdict` 越界即红、`externalCalls=0` 即红（真跑不可能 0）、`W_max` 与 `0.30×(1−0.5)` 不自洽即红、判别函数**引用了 `route`** 即红、`queryCount` 缺失即红
      2026-10-24 实测：8 测试全绿（四锚 SHA 实算 / verdict 三值+互锁 / externalCalls>0+W_max 精确 0.15 / isImageSource 源码方法体禁 route 断言 / queryCount 与逐条口径重算）
- [x] 1.9 守卫自证：手工把锚里 `minDownwardGap` 改小到窗口内 → 守卫红；还原 → 绿（留痕）
      2026-10-24 实测：0.00941087658500056→0.05 ⇒ 红（minDownwardGap 与查询级 gap 重算不符 expected 0.0094… was 0.05）→ 还原后 sha256 前 16 位 5444719a9ff67ee6 与原锚一致 ⇒ 绿
- [x] 1.10 md 分两节：**实测（D2/D3）** 与 **反事实重建（D4）**，不得并表；正文写死「7 条量级查询 = 存在性证据，禁外推」
      2026-10-24：near-dup-displacement-evidence-v1.md §1 实测 / §2 反事实分节；§0 头部与 §4 边界写死存在性/量级口径；锚 scopeNote 同句
- [x] 1.11 硬边界自证：`git diff --stat -- src/main/java` 空输出；四份上游锚 `git status` 无 M 项
      2026-10-24 实测：diff --stat -- src/main/java 空输出；git status 上游四锚与迁移/pom/openapi.yaml 无 M（G4 交付前复证见 4.5）

## 2. G2 · 变体↔第三图全矩阵（真跑 + 外呼 144 次）

### §写入面
`src/test/java/com/slz/crm/quality/ImageVariantCrossMatrixIT.java`、`src/test/java/com/slz/crm/quality/ImageVariantCrossMatrixGuardTest.java`、`docs/rag-quality/image-variant-cross-matrix-v1.json`、`docs/rag-quality/image-variant-cross-matrix-v1.md`

- [x] 2.1 复用 `NearDuplicateCorpus.generate()` 取得 24 base + 120 variant（**确定性**，不得新增语料、不得改 corpus 生成逻辑）
      2026-10-24 实测：corpus 24/120/144、skips=0、逐文档 8/4/12，与 v2 定标锚 corpus 断言一致；生成逻辑零改动（git status NearDuplicateCorpus.java 无 M）
- [x] 2.2 每图**单次**编码（144 次外呼），落 `144×144` 对称矩阵；对角落**实测**自余弦到 `selfCosines`（M1）
      2026-10-24 实测：externalCalls=144（embedImageCalls=144=图数，断言每图单编）；矩阵对称守卫逐元素精确通过；selfCosines 实测 [0.9999999999999998, 1.0000000000000002]
- [x] 2.3 偏差对账：与 v2 定标锚 `near-duplicate` 120 对 + `same-class` 100 对 + `cross-class` 176 对逐对比较，落 `maxAbsDeviation` 与超阈值对数；`>1e-3` ⇒ 停 G2（M2）
      2026-10-24 实测：396 对逐对 maxAbsDeviation=0.0、超阈 0 对、verdict=PASS——单编码与定标卡 A 向量逐位一致（编码确定性实证），未触发停步
- [x] 2.4 落 `variantVsThirdImage`（120×23 子矩阵，排除自身 base）分位 `p50/p90/p99/max`，并回答「是否存在 `cos ≥ 0.9887`」（M3）
      2026-10-24 实测：2760 值，p50=0.22159741771509944 / p90=0.5024351822324381 / p99=0.9809376960840968 / max=0.9972924771028003；存在带内对（14 对）⇒ hasCosAtOrAboveFloor=true
- [x] 2.5 给 `proxyVerdict` 三选一（`PROXY-SUFFICIENT` / `PROXY-UNDERSTATES` / `PROXY-OVERSTATES`），`SUFFICIENT` 必须附 `maxVariantVsThird` 与到 floor 的距离（M3）
      2026-10-24 实测：proxyVerdict=PROXY-UNDERSTATES（存在带内对 ⇒ base 对代理低估）；UNDERSTATES 分支 distanceToFloor=null（SUFFICIENT 分支的距离字段留空不适用，守卫互锁校验）
- [x] 2.6 md 内逐条回填两处代理缺口：`image-near-dup-truth-v1.json` 的 `deviations` 第一条、融合锚 R4 的 gap 分布（M4）
      2026-10-24：image-variant-cross-matrix-v1.md §M4 逐条回填——deviations[0] 的「不存在变体↔异 base 对」已由 2760 对实测补上（14 对带内，低估方向）；R4 竞争者观测面 2 条 → 至少 2+14 条，定性 necessaryNotSufficient 不变
- [x] 2.7 守卫 `ImageVariantCrossMatrixGuardTest`：矩阵对称性、对角元素与 `selfCosines` 一致、`proxyVerdict` 三选一、`maxAbsDeviation` 与「是否停组」自洽、外呼次数 = 144 或如实更大（不许小于图数）
      2026-10-24 实测：4 测试全绿（对称逐元素精确 / 对角=selfCosines / 396 对对账重算同值+PASS 互锁 / proxyVerdict 互锁+外呼=图数+四锚 SHA+decisionOwner）
- [x] 2.8 守卫自证：手工把 `maxVariantVsThird` 改到 ≥0.9887 而 `proxyVerdict` 仍写 `SUFFICIENT` → 守卫红；还原 → 绿
      2026-10-24 实测：max→0.99+proxyVerdict→SUFFICIENT ⇒ 红（maxVariantVsThird 重算不符 expected 0.99729… was 0.99）→ 还原 sha256 前 16 位 3adde4ec96ed8b88 ⇒ 绿；另做 IT 级变异（p50→0.3 ⇒ replay 逐字节红）→ 还原绿

## 3. G3 · 证据收口（零外呼）

### §写入面
`docs/rag-quality/near-dup-displacement-evidence-v1.md`（与 G1 同文件，G3 负责其「三项收口」章节）、`docs/rag-quality/near-dup-floor-decision-request-v1.md`（**仅 §4**）、`src/test/java/com/slz/crm/quality/NearDupFloorDecisionRequestGuardTest.java`

- [x] 3.1 新页内逐条对应三项 `missingEvidence`，每项三态之一：`已补（锚字段 + SHA）` / `仍有界（写边界）` / `仍缺（原因 + 所需数据）`（R1）
      2026-10-24：near-dup-displacement-evidence-v1.md §3 三态表——①已补（minDownwardGap/verdict，sha 5444719a…）②已补（cooccurrenceQueryCount=6/7）③已补（PROXY-UNDERSTATES，sha 3adde4ec…）；无仍有界、无仍缺
- [x] 3.2 更新签收页 §4 三条，指向新锚 SHA；**§1–§3 / §5 逐字不动**（R2）
      2026-10-24：§4 第 1/3/4 条改写为已补并引用两新锚（sha256 全量 64 位），第 2 条（查询-相关性标注）原样保留
- [x] 3.3 自证：`git diff -- docs/rag-quality/near-dup-floor-decision-request-v1.md` 只出现 §4 行段，§1–§3/§5 零变动（R2）
      2026-10-24 实测：git diff 单 hunk @@ -53,12 +53,12 @@（4 insertions/4 deletions），仅 §4 行段
- [x] 3.4 扩展 `NearDupFloorDecisionRequestGuardTest`：新锚 SHA 实算比对 + §4 引用的锚文件必须存在 + 原有三口径逐格断言**未放松**（R3）
      2026-10-24：D1 引用计数 4→6（新锚 SHA 经既有实算循环校验）+ 新测试 evidenceAnchorsReferencedAndMeasured（存在+externalCalls>0+OWNER+被 §4 引用）；D2/D3/D4 既有断言逐行未动（diff 仅 D1 计数行与方法插入）；变异自证=删 §4 引用行 ⇒ 红（2 失败）→ 还原绿
- [x] 3.5 若 G1 或 G2 停步：该项写 `仍缺 + 原因`，**禁止**删掉整节或留空
      2026-10-24：G1/G2 均未停步（G1 POSSIBLY-DISPLACED、G2 对账 PASS），本条无适用对象；新页三节全实

## 4. G4 · 全量门禁与照判回报

- [x] 4.1 `mvn -B -ntp clean verify` BUILD SUCCESS（计数只增不减）
      2026-10-24 实测：BUILD SUCCESS EXIT=0；surefire 2122/0 失败/0 错误（343 报告，2109→2122 只增）、failsafe 147 测试/15 跳过（46 报告，145→147 只增，新增 2 个 opt-in IT 默认跳过）
- [x] 4.2 `bash scripts/check-test-baseline.sh` EXIT=0（**只在全量 `clean verify` 之后跑**）
      2026-10-24 实测：--update 写入 343/2122/0 + 46/147/15（真实运行写入），复跑 check EXIT=0；source-revision 先 -dirty 后按纪律（先 commit 再 --update）重写为干净提交号，小提交跟进
- [x] 4.3 `bash scripts/check-openapi-consistency.sh` EXIT=0
      2026-10-24 实测：EXIT=0，前后端契约一致（frontend/openapi.yaml 与后端 swagger 导出逐字相同）
- [x] 4.4 三组判定分别出表 + 一份总判定；停步项写清「停了哪组 / 其余组状态 / 已回退路径」
      2026-10-24：G1 verdict=POSSIBLY-DISPLACED（displacement-evidence md §1-2）；G2 对账 PASS + proxyVerdict=PROXY-UNDERSTATES（cross-matrix md M1-M4）；G3 三项全部已补（displacement md §3）；无停步组，无回退路径
- [x] 4.5 交付前自检：`src/main` 零 diff、零迁移、零新依赖、四份上游锚零 diff、`frontend/openapi.yaml` 零 diff
      2026-10-24 实测：git diff --stat master -- src/main/java db/migration pom.xml frontend/openapi.yaml 空输出；四份上游锚与三个受保护测试类 git status 无 M
- [x] 4.6 回报 ≤300 字：产出路径 / 行数 / 校验结果 / 未解决项 / 待决策项
      2026-10-24：回报见执行子 agent 最终消息（六项：路径行数/门禁/实测值/停步/未解决/待决策）

## 5. 铁律（违反任一条 = 整卡作废）

1. 不改生产代码：`src/main/java` 全域零 diff。
2. 不改网关常量与融合权重：`0.9944` / `0.9887` / `0.75|0.6|0.5` / `0.70` / `0.30`。
3. 不改既有评测口径与既有 IT：`RagQualityEvaluator`、`RagBenchmarkCase`、`ImageNearDuplicateCalibrationIT`、`ProductionGrayRealRunIT`、`NearDuplicateCorpus` 零 diff。
4. 不改契约（`frontend/openapi.yaml` 零 diff）、不加迁移、不加依赖。
5. 上游四锚（`image-threshold-calibration-v2` / `near-dup-fusion-impact-v1` / `near-dup-exchange-rate-v1` / `image-near-dup-truth-v1`）**只读**。
6. **模态判别只许用 `sourceType`，禁用 `route`**（`route` 是 `vector|keyword|hybrid|ocr`）。
7. 真跑必须真 opt-in；跳过 ≠ 通过，跳过即该组未完成。
8. 判据冻结：跑后不许调窗口 / 调阈值 / 换分母 / 换语料凑结论。
9. 数字零编造：缺数据写缺，不许用「看起来合理」的值填表。
10. 分组回退只回退本组写入面；G3 不因前组停步而放弃。
