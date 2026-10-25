# proposal · near-dup-ablation-redesign（把「卡 26 没跑成的消融」改成零外呼可复跑）

> 卡 28。**S0 零外呼 = 本卡承诺范围**；**S1 需外呼 = 只预注册，不执行**。零迁移 / 零契约 / 零权限。
> 只做测量与判定，不做修复；不产出新的阈值建议。

## §1 问题陈述（为什么必须有这张卡）

卡 26 的 S1 消融在 **124 次外呼后红停**（过闸 41 vs 文本 embed 实计 36），Owner 裁定 A′：S1 不授权、装置拆除、负结论预注册（`near-dup-ingest-s1-not-run-v1.json`）。卡 27 把地基清了三件：七链逐链不丢（相邻差全 0）、五种静默丢弃语义已钉成断言、**分母必须用实测子块行数**。

但消融要回答的那道题还在原地：

> **把三档降权（`×0.75/×0.6/×0.5`）换成不降权，真实检索顺序变不变？**

它此前跑不动有三个相互独立的原因，本卡逐一拆：

| 障碍 | 现状 | 本卡处置 |
| --- | --- | --- |
| **装置没有鉴别力证明** | 卡 26 花完 124 次外呼、看到计数不符之后才发现装置不可信 | **S0 先证明装置能测出差异**（正对照必翻转），**零外呼** |
| **没有「只差降权」的对照位** | 降权在服务内联调用，无开关、无注入点 | `mockStatic` **只替换这一个调用**（`src/main` 零 diff） |
| **分母口径** | 曾用「过闸图数 × 3」推导 | 改用**实测子块行数**；推导值一律作废 |

## §2 侦察已得事实（行号实测于 `1884e31`，执行者不必重查）

- **主链** `KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases` `:246-283`（`private`）：`:257-259` 文本多路召回 → `:262` 重排 → `:265-276` 图像路 = `downWeightNearDuplicates(reranker.rerank(recallImage(...)))` → `:278-281` `fuseRoutes` 后按分降序取 topK。**降权就一行，且只有一行** ⇒ A/B 的唯一差异点天然存在，只需把它变成可替换。
- **`ImageSimilarityGate.downWeightNearDuplicates(List)`** `:233`：`public static`、纯函数、保序、**幂等**（已降权者带 `BAND_WEIGHT_METADATA_KEY`，`:240-241` 防二次降权）；三档权重常量自 `:50` 起（`NEAR_DUPLICATE_WEIGHT_HIGH = 0.75D`）。
- **`RetrievalConfigResolver`**：`final class` 且**包可见**（`:16`）；`fuseRoutes(text, image, singleKbId)` 亦**包可见**（`:253`）；默认权重 `0.70 / 0.30`（`:18-19`）；融合 = 分数制、同键累加（`:271-288`）。
- **`RetrievalCandidate` / `VectorSearchHit` 是 `public record`** ⇒ 正对照可纯 JVM 直构，不需要 Spring。
- **Mockito 5.17.0**（`pom.xml:50` 注释实测自依赖树），Mockito 5 默认 inline mock maker ⇒ `mockStatic` **零新依赖**可用；仓内已有先例 `KnowledgeAdminServiceTest`。
- **现成的零外呼语料**：`docs/rag-quality/near-dup-displacement-evidence-v1.json`（735 KB 冻结向量；7 条真实查询、7 个图像切片、36 个 PDF 文本块）。该 IT 的**默认复跑就是「从冻结向量本地重放生产检索链，零外呼，与磁盘锚逐字节一致」**（`externalCalls=77` 是历史写锚值，复跑不花）。本卡直接复用它，**不下载新资产、不新增语料**。
- **该语料上唯一被降权的候选**=两张 NIST 封面（带内对，`cos=0.988736`），其与下方竞争者融合分差 **0.24**，远在窗口外 ⇒ **预判 A/B 无位移**。这是预判，不是结论；且正因如此，**必须先有正对照证明装置不哑**。
- **窗口上界**（`near-dup-fusion-impact-v1`，常量断言）= `0.30 × (1 − w)`：HIGH **0.075** / MID **0.12** / LOW **0.15**。

## §3 判据（R0–R6，执行与复核均以此为准）

- **R0 零外呼 + `src/main` 零 diff**：S0 全程只用冻结向量与桩；**不得**读 `DASHSCOPE_API_KEY`、**不得**构造真实 provider、**不得**跑任何 `*REAL*` opt-in。B 跑只经 `mockStatic` 替换 `downWeightNearDuplicates` **一个方法**，其余走真实实现（`CALLS_REAL_METHODS`）。锚内显式声明该保证由「未构造真实 provider」结构成立。
- **R1 正对照必翻转（本卡命门·鉴别力）**：纯 JVM 直构三候选——`NEAR_DUPLICATE`+`NEAR_HIGH`（`w=0.75`）的图像候选、一条文本候选、一条非近似图像候选；调参使 **A（降权）时该图像候选排后、B（不降权）时排前**。断言两次顺序**相反**、且翻转原因可归到 `0.225s` vs `0.3s`。**此条不成立 ⇒ 装置无鉴别力 ⇒ 后续任何「无位移」都无效，verdict 直接 `INCONCLUSIVE`。**
- **R2 负对照不变**：同一次对照里，非 `NEAR_DUPLICATE` 档的候选两跑融合分**逐条相等**（证明装置不是把一切都改了）。
- **R3 隔离（消融只动降权）**：B 跑断言 `downWeightNearDuplicates` **恰被调用 1 次**且返回恒等（分数逐条不变）；A 跑返回带 `BAND_WEIGHT_METADATA_KEY` 标记；两跑中**所有 `sourceType != IMAGE` 的来源融合分逐条相等** ⇒ 文本路未被污染。
- **R4 真语料 A/B**：对冻结语料 7 条查询逐条落 `chunkIdSetEqual` 与逐候选 `(sourceType, tier, band, fusedA, fusedB, rankA, rankB)`；另落 `imageCandidateCount`、`downWeightedCount`、`maxImageScoreDelta`、`maxTextScoreDelta`。
- **R5 分母（禁推导值）**：落 `childRows`（**实测**：图片切片数 + 文本块数，可逐条列 `chunkId`）与 `embedInvocations`（按**未命中**计）；**禁**写成 `3×N`、`过闸图数×常数`、`融合分 ÷ w` 反解等任何推导量。两数不等必须在锚内写明原因。
- **R6 verdict 三选一（只许这三个字符串）**：`RANK-CHANGE`（有任何 IMAGE 候选分数或名次变化，或集合不等）/ `NO-RANK-CHANGE`（`maxImageScoreDelta == 0` 且集合相等）/ `INCONCLUSIVE`（附缺哪条证据）。**`NO-RANK-CHANGE` 必须附窗口算术**（逐条被降权候选的 `gap` vs `0.30×(1−w)`）并**显式声明不可外推为「生产无位移」**；**禁**夹带修复建议或阈值主张。

## §4 交付物

- 新增正对照 surefire 测试：`src/test/java/com/slz/crm/knowledge/retrieval/NearDupAblationPositiveControlTest.java`（同包 ⇒ 可调包可见的 `fuseRoutes`）。
- **纯增**一个 `@Test` 到既有 `src/test/java/com/slz/crm/quality/NearDupDisplacementEvidenceIT.java`（复用其冻结向量装配）。允许把「重放装配 + 逐查询循环」抽成私有 helper 做**纯搬运**；**不许改语义、不许改既有断言与写锚路径**，抽取后默认复跑必须仍与磁盘锚**逐字节一致**。
- 新增守卫 surefire 测试：`src/test/java/com/slz/crm/quality/NearDupAblationGuardTest.java`。
- 结论锚：`docs/rag-quality/near-dup-ablation-v1.json`（**LF**，写模式 `-Dnear.dup.ablation.write=true`，默认逐字节复算）+ `.md`（≤40 行）。

## §5 成本

**0 次真实外呼**（S0）；0 迁移、0 契约、0 权限、0 新依赖；surefire 期望 ≤30 s，failsafe +1 用例。

## §6 S1 预注册（**本卡不执行**，仅供 Owner 后续决策）

- **语料改为「非退化近重复」**：不再用卡 26 的 48 图退化变体集（已知丢因：短边 <64px、描述质量闸门）。改为对**真实切片**做原生分辨率重编码 / 轻度 JPEG 重压 / 整图微缩（短边仍 ≥64px），**每 base 3 个变体**，入库后**断言实测过闸数 == 预期数**，任何静默丢弃即红。
- **成本算式（需 Owner 单列授权后重算，不许拿上界当下限）**：变体 6 × (vision 1 + image embed 1 + 文本 embed 1) = 18，查询 6 × (文本 embed 1 + image embed 1) = 12 ⇒ **≤30，报批 36 封顶**。
- **触发条件**：S0 判 `NO-RANK-CHANGE` 且窗口算术显示「真实语料无窗内共现」⇒ 才值得花钱构造窗内语料。S0 判 `RANK-CHANGE` 则 S1 不必跑（已答）。

## §7 裁决假设（执行者可推翻，但必须停手上报）

- **D1** 用 `mockStatic(CALLS_REAL_METHODS)` 做 B 跑，而**不是**改 `src/main` 加开关：静态 mock 只作用于本 JVM 的该次调用，生产零改动。
- **D2** 若静态 mock 在本仓实测不可用（报错/不生效），**停手上报**，改用「`src/main` 抽一个包可见布尔重载」的最小 seam 方案（须附等价性单测），不得自行降级成代数反解。
- **D3** 复用既有冻结语料，**不下载新资产、不复用卡 26 的 48 图选集**。
- **D4** 本卡不产出阈值主张；`R=94.17%`/`0.90` 议题仍归 Owner。

## §8 判据修订记（fix1，2026-10-25，主 agent 代笔）

- **修订对象**：R6 的 verdict 判定面。原文「有任何 IMAGE 候选**分数或**名次变化 ⇒ `RANK-CHANGE`」。
- **缺陷**：降权只要发生（`w<1`），融合分必变 ⇒ 该 verdict 对任何带近重复的输入都成立，**判定面没有鉴别力**——它分不出「降权把结果重排了」与「只是等比缩了分」。卡 27 的教训是「没测到」写成「测到没有」，本例是「必然为真」写成「判定结果」，同一种病。
- **修订后**：判定面 = **名次面 + 集合面**；`RANK-CHANGE`（有名次变化或集合不等）/ `NO-RANK-CHANGE`（两者均无变化）/ `INCONCLUSIVE`（附缺哪条证据）。分数面位移改单列锚内 `scoreFace{changed, maxImageScoreDelta, imageDeltaSum, imageCandidateCount, downWeightedCount}`，只作数据面。
- **`NO-RANK-CHANGE` 附加义务**：必附 `windowCheck`（逐条被降权候选 `gap` vs `0.30×(1−w)`）+ `nonExtrapolationNote`；守卫逐条校验。
- **另修**：`denominators.childRowChunkIds` 条目加存储面前缀（`IMAGE:` / `TEXT:`）——图片切片行与其描述文本行共用同一 `chunkId`，不加前缀则 50 无法从清单里数出来。
- **测量值零变动**：`childRows=50`、`embedInvocations=11`、`maxImageScoreDelta=0.06`、`imageDeltaSum=0.12`、`downWeightedCount=2`、逐候选 `fusedA/fusedB/rankA/rankB` 全部原样；只改**推导源**并重生成锚（成品不手改）。
- **执行方式**：主 agent 代笔（Owner 2026-10-25 授权「代笔 fix1 + M1 + M2 全链」）。原执行端与复核均按原 R6 忠实执行，缺陷在判据文本，不在实现。
