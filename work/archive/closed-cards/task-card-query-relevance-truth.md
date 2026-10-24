# 任务卡 · query-relevance-truth（B 档 · 三合一）

> 执行端唯一执行单。细则以 `openspec/changes/query-relevance-truth/{proposal.md,tasks.md}` 为准；本卡只写「怎么开工、写到哪、怎么停」。

## 1. 一句话目标

把上一卡 `S_same`（pair 级**有害量上界**）换成两个可度量的东西：**G1** 融合分口径的**位移上界**（可证明，不是估计）；**G2** 图像路的**检索级真值结果**（沿用项目既有 `expectedChunkIds` 机制，真值来自语料 provenance）；**G3** 把三口径证据合成**一页可签收的档位决策请求**。**不请求外呼、不改生产常量、不改生产链路。**

## 2. 开工前必须记住的三条实测事实（本轮已核对，勿再猜）

1. **降权在融合之前**：`KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases` 的顺序是「文本召回+重排 → 图像召回（`minScore=effectiveFloor=0.9887`）+ `gate` + 重排 + `downWeightNearDuplicates` → `fuseRoutes` → 按 `rerankScore` 降序取 top-k」。
2. **融合是分数制**：`RetrievalConfigResolver` 的「文本 0.7 + 图像 0.3，同切片两路命中则累加」——**不是** RRF 排名制。所以图像候选被乘 `w` 后，其融合贡献从 `0.3·s` 掉到 `0.3·w·s`。
3. **图像路内部单调**：三档带按 cosine 三等分、权重按带单调（`0.5<0.6<0.75<1`）、带边界连续 ⇒ `score=rerankScore×w(tier)` 在路由内仍按 cosine 单调 ⇒ **降权在路由内不改任何两两顺序**。这两条合起来就是本卡的 G1。

## 3. 工作树与分支

- 权威树（唯一法定）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 起点 = `master` @ `c789e23`（`evidence-to-operations` 已归档）；开工先确认 `git rev-parse --abbrev-ref HEAD` = `master` 且 `git status --porcelain` 只含本卡新增文件。
- 切分支：`git checkout -b feature/query-relevance-truth`
- 分支永久保留；`git push` / 合并由主 Agent 在 Owner 授权后执行，执行端**不得 push / 不得 merge 回 master / 不得改写历史**。

## 4. 必读（按序）

1. `openspec/changes/query-relevance-truth/proposal.md`（尤其 §1.2 R1–R5、§2.3 T1–T5、§3.2 D1–D4、§4 硬边界、§5 停步）
2. `openspec/changes/query-relevance-truth/tasks.md`（逐条打勾 + 6 空格「补勾依据」）
3. `src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java`（242 行，三档与降权语义）
4. `src/main/java/com/slz/crm/knowledge/retrieval/KnowledgeRetrievalServiceImpl.java` 240–283 行（链路顺序）与 404–434 行（图像路）
5. `src/main/java/com/slz/crm/knowledge/retrieval/RetrievalConfigResolver.java` 173–193 行（融合权重）与 233–289 行（`fuseRoutes`）
6. `src/test/java/com/slz/crm/quality/ImageCrossModalSuite.java`（§49 的 `expectedChunkIds` 真值注释）+ `PdfIngestImageFixture.java`（§247 同图用例形状）
7. `docs/rag-quality/near-dup-exchange-rate-v1.md` + `.json`（上一卡的价目表与 `R(f)`，G2 的 T4 交叉校验要用）
8. `docs/rag-quality/image-threshold-calibration-v2.json`（540 对逐对余弦，SHA-256 `82d24204…`）

## 5. 写入面（白名单，超出即整卡作废）

**G1 新增**
- `src/test/java/com/slz/crm/quality/NearDupFusionImpactAnalyzer.java`
- `src/test/java/com/slz/crm/quality/NearDupFusionImpactDecisionTest.java`
- `src/test/java/com/slz/crm/quality/NearDupFusionImpactGuardTest.java`
- `docs/rag-quality/near-dup-fusion-impact-v1.json`
- `docs/rag-quality/near-dup-fusion-impact-v1.md`
- `scripts/rag-quality/near-dup-fusion-impact-report.sh`

**G2 新增**
- `src/test/java/com/slz/crm/quality/ImageNearDuplicateTruthSuite.java`
- `src/test/java/com/slz/crm/quality/ImageNearDuplicateTruthEvaluationTest.java`
- `docs/rag-quality/image-near-dup-truth-v1.json`
- `docs/rag-quality/image-near-dup-truth-v1.md`

**G3 允许**
- 新增 `docs/rag-quality/near-dup-floor-decision-request-v1.md`
- 新增 `src/test/java/com/slz/crm/quality/NearDupFloorDecisionRequestGuardTest.java`

**全卡允许**
- `docs/active-handoff.md`（结论引用）
- `work/` 下证据与日志（不进提交的临时产物自删）

**禁改**：`ImageSimilarityGate` / `KnowledgeRetrievalServiceImpl` / `RetrievalConfigResolver` / `RagQualityEvaluator` / `RagBenchmarkCase` / `ImageCrossModalSuite` / `PdfIngestImageFixture` / `src/main/resources/db/migration/**` / `frontend/openapi.yaml` / 上一卡两份锚与脚本 / `scripts/merge-gate.sh` / `scripts/check-test-baseline.sh`。

## 6. 三组要点

### G1（解析边界）

- `bound(w) = imageWeight × (1 − w)` ⇒ `0.15 / 0.12 / 0.075`（`w=0.5/0.6/0.75`）。**��个数字必须是常量断言**，融合权重一改就红。
- R2 单调性用 5000 点 + 三档边界点数值验证，断言「同序（允许并列）」。**若出现反序，不要改判据**——如实上报「推论 1 被数据推翻」，其余组继续。
- R4 的 gap 分布换算成融合分 gap 时乘 `imageWeight`；文档必须写「**必要非充分**」，禁止写成「召回损失」。
- R5 三值判定 + `missingEvidence` 字段（缺什么数据才能定死跨路可见性）。

### G2（检索级真值）

- 正例 = 24 base × 5 kind = **120**，`expectedChunkIds` 一律由 `baseKey → goldenId` 生成，**不许出现手写字面量**。
- 负例两类：跨文档近似（不同 `docKey`）+ 同文档不同图（同 `docKey` 不同 base，hard negative）。
- 排序**不许重新 embedding**：图像路得分从校准锚的逐对余弦取；正例用同一 base 的变体对，负例用跨 base 对。
- `recallAtK` 必须用既有 `RagQualityEvaluator` 算（零改动）；`contaminationAtK` 只是 top-k 结果的**派生量**，文档要这样写。
- **T4 是硬校验**：本次正例集的 `recallAtK` 必须等于上一卡锚同 floor 的 `R(f)`；不等就**停该组**并上报差异，禁「取其一」。

### G3（决策请求）

- 三口径并列 + 「仍缺什么」+ 三候选 + 签收栏；每个数字必须能在锚里找到同值字段（守卫实算校验），**禁手写推导值**。
- 措辞只允许「候选 + 代价 + 待签收」，禁「已决定 / 已采用 / 建议上线」。
- 某组停步 → 该项标 `MISSING` 并写原因，**不要编数字补位**。

## 7. 门禁（收尾跑一次全量）

```text
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api && mvn -B -ntp clean verify'
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api && bash scripts/check-test-baseline.sh'
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api && bash scripts/check-openapi-consistency.sh'
```

- 起点基线 = `source-revision=c789e23` 之后的最新值；当前台账是 `source-revision=3e777e8`、surefire **337/2092/0**、failsafe **44/145/13**，本卡只许**只增不减**。
- 基线门禁**只在一次全量 `clean verify` 之后跑**；定向 `-Dtest=` 之后禁止跑（残缺报告会污染 `--update`）。
- WSL bash 不可用（`ext4.vhdx` / `HCS`），不要换 shell 绕。
- **行尾纪律**（上一卡的血教训）：凡做**逐字节比对**的锚，序列化必须钉死 `\n`（Java 里用 `.replace("\r\n","\n")`），因为 `.gitattributes` 让 `*.json` 在仓库里是 LF，而 `Jackson` 默认缩进器在 Windows 上写 CRLF。写入口测试必须能在**干净 checkout** 上默认模式跑绿。

## 8. 停步与回退

- 每组独立判定；某组不达标 → **只回退该组写入面**，其余组照常完成。禁「一组红、整卡放弃」。
- G1 若 R2 反序：不改判据，如实上报，G1 结论允许落到「路由内可见」这一支。
- G2 若 T4 不一致：**停 G2**，把两条路径的数写在报告里；G3 里检索口径标 `MISSING`。
- G3 若 D1/D2 无法满足：删掉无法核对的数字，**不得改锚**。

## 9. 铁律

1. 不改生产常量与融合权重；不改生产链路三文件。
2. 不改既有评测口径四文件（`RagQualityEvaluator` / `RagBenchmarkCase` / `ImageCrossModalSuite` / `PdfIngestImageFixture`）。
3. 零外呼、零迁移、零新依赖、契约零 diff。
4. 真值只许来自 provenance；**禁止编造标注或数字**。
5. 校验只在收尾跑一次全量；不过就如实上报，不「验证到通过」。

## 10. 回报格式（≤300 字）

```text
分支：feature/query-relevance-truth（N 笔，自 <sha>）
产出：<路径>(行数) 逐组一行
门禁：clean verify BUILD SUCCESS / surefire X/Y / failsafe A/B / baseline EXIT=0 / openapi EXIT=0
G1：verdict=… bounds=0.15/0.12/0.075（或实测值）/ 单调性反序=0 处 / gap 中位…
G2：正例=120 负例A=… 负例B=… / recallAtK@0.982=… contaminationAtK=… / T4 一致=是
G3：三口径齐=是/否（MISSING 项）/ 守卫 4 项
未解决项：…（无则写「无」）
待决策项：…（无则写「无」）
```
