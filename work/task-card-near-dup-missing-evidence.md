# 任务卡 · near-dup-missing-evidence（近重复位移证据补齐）

> **档位**：A 档（真跑 + 外呼 + 决策页改动）　**分支**：`feature/near-dup-missing-evidence`（从 `master` @ `5519c43` 检出，合并后永久保留）
> **来源**：Owner 选储备 ②「补齐三处缺失证据」　**规格**：`openspec/changes/near-dup-missing-evidence/{proposal,tasks}.md`（**判据以 tasks.md 为准**）

## 1. 要做成什么

上一卡 `near-dup-fusion-impact-v1` 的 `verdict=INVISIBLE-IN-ROUTE-ONLY` 挂了三项 `missingEvidence`。本卡把三项各变成**一条实测、一条有界结论、或一条写明原因的缺口**——不允许第四种状态（「大概」「看起来」「应该」）。

| # | 缺什么 | 本卡处置 |
|---|---|---|
| ① | 文本路 rerank 分数分布 | 真实混合查询**一次调用**读 `sources()` 的 `relevanceScore`，落分差实测（G1） |
| ② | 真实查询共现 | 同一次调用必须同时产出 `sourceType=IMAGE` 与非 IMAGE 来源（G1 D1） |
| ③ | 变体↔第三图余弦 | 144 图单次编码建全 144×144 矩阵（G2） |

## 2. 写集（只有你能写这些路径，无其他写入者）

```
openspec/changes/near-dup-missing-evidence/**            （仅勾选与补勾依据，不改判据文字）
work/task-card-near-dup-missing-evidence.md              （本文件，仅补勾记录）
src/test/java/com/slz/crm/quality/NearDupDisplacementEvidenceIT.java          （新）
src/test/java/com/slz/crm/quality/NearDupDisplacementEvidenceGuardTest.java   （新）
src/test/java/com/slz/crm/quality/ImageVariantCrossMatrixIT.java             （新）
src/test/java/com/slz/crm/quality/ImageVariantCrossMatrixGuardTest.java      （新）
docs/rag-quality/near-dup-displacement-evidence-v1.json                       （新，唯一写入口在 IT）
docs/rag-quality/near-dup-displacement-evidence-v1.md                         （新）
docs/rag-quality/image-variant-cross-matrix-v1.json                           （新）
docs/rag-quality/image-variant-cross-matrix-v1.md                             （新）
docs/rag-quality/near-dup-floor-decision-request-v1.md                        （**仅 §4**）
scripts/test-baseline.txt                                                     （仅经 `--update` 写）
```

**写集外一律不碰。** 四个新 IT/守卫类名已固定，不许改名（复核按名定位）。

## 3. 硬边界（违反任一条 = 整卡作废）

1. `src/main/java` **全域零 diff**——观测面（`SourceReference.relevanceScore` / `sourceType`）已经存在，改生产说明侦察结论错了，**停下上报**。
2. 网关常量与融合权重零改动：`0.9944` / `0.9887` / `0.75|0.6|0.5` / `0.70` / `0.30`。
3. 既有测试零 diff：`RagQualityEvaluator`、`RagBenchmarkCase`、`ImageNearDuplicateCalibrationIT`、`ProductionGrayRealRunIT`、`NearDuplicateCorpus`。（需要装配逻辑就**复制**到新 IT，测试代码重复可接受。）
4. 上游四锚只读：`image-threshold-calibration-v2.json` / `near-dup-fusion-impact-v1.json` / `near-dup-exchange-rate-v1.json` / `image-near-dup-truth-v1.json` **字节不变**。
5. 零迁移、零新依赖、`frontend/openapi.yaml` 零 diff。
6. **模态判别只许用 `sourceType`，禁用 `route`**——`route` 的取值域是 `vector|keyword|hybrid|ocr`（`SourceReference.java:14`），不是图文路由。用错这一条，G1 的归因全错。
7. 真跑必须真 opt-in：门控变量名固定 `RAG_NEAR_DUP_DISPLACEMENT_REAL=1`（G1）/ `RAG_IMAGE_CROSS_MATRIX_REAL=1`（G2）；锚写入门控固定 `-Dnear.dup.displacement.write=true` / `-Dimage.cross.matrix.write=true`，**默认模式逐字节校验**。
8. 判据冻结：跑后不许调 `W_max`、不许调 `1e-3` 偏差阈值、不许换语料、不许换分母。不达标就如实上报。
9. 数字零编造：缺数据写缺。

## 4. 三组要点（细节看 tasks.md）

**G1（真跑 + 少量外呼）**：一条查询**同时**带 `text` 与 `imageVector`，发起**一次** `retrieve`；读 `sources()` 落 `(sourceType, relevanceScore, chunkId)`。落 `cooccurrenceQueryCount / queryCount`、逐查询 `downwardGap`、`minDownwardGap`，按 `W_max = 0.30×(1−0.5)×1.0 = 0.15` 判 `NOT-DISPLACED-IN-CORPUS` / `POSSIBLY-DISPLACED`。另做**反事实重建**（540 对锚的近重复 `cos` 代入 `0.30×cos×w`），必须落 `assumption` 字段。**0 次共现 ⇒ `NO-COOCCURRENCE` 停 G1**，禁止退化成两次独立 retrieve 凑数。

**G2（真跑 + 144 次外呼）**：复用 `NearDuplicateCorpus.generate()`（**不许改生成逻辑**），每图单次编码，建 144×144 矩阵；与 v2 定标锚的 120+100+176 对逐对比较，`maxAbsDeviation ≤ 1e-3` 才可发布，**超阈值停 G2**。输出 `variantVsThirdImage`（120×23）分位与「是否存在 `cos ≥ 0.9887`」，给 `proxyVerdict` 三选一。

**G3（零外呼）**：新页逐条收口三态；签收页**只改 §4**（§1–§3/§5 逐字不动，`git diff` 自证）；扩展现有守卫（只准加断言，不准放松）。

## 5. 真跑命令（IT 不进 surefire，必须走 failsafe）

```bash
# G1：真混合查询（需 DASHSCOPE_API_KEY）
RAG_NEAR_DUP_DISPLACEMENT_REAL=1 mvn -B -ntp -Dit.test=NearDupDisplacementEvidenceIT verify
# 写锚（确认输出合理后再写）
RAG_NEAR_DUP_DISPLACEMENT_REAL=1 mvn -B -ntp -Dit.test=NearDupDisplacementEvidenceIT -Dnear.dup.displacement.write=true verify

# G2：144 图全矩阵
RAG_IMAGE_CROSS_MATRIX_REAL=1 mvn -B -ntp -Dit.test=ImageVariantCrossMatrixIT verify
RAG_IMAGE_CROSS_MATRIX_REAL=1 mvn -B -ntp -Dit.test=ImageVariantCrossMatrixIT -Dimage.cross.matrix.write=true verify
```

- 锚写完**必须**用默认模式（不带 write 参数）复跑一次，逐字节一致才算落定。
- ⚠️ `-Dit.test=` 会**覆盖** pom 的 failsafe `<includes>`，别顺手加别的排除表达式。
- ⚠️ 跑过这些定向 IT 后 `target/` 已被污染，`--update` 重锚前必须重新跑一次全量 `mvn -B -ntp clean verify`。

## 6. 门禁（四项全过才算交付）

1. `mvn -B -ntp clean verify` **BUILD SUCCESS**
2. `bash scripts/check-test-baseline.sh` **EXIT=0**（在全量 verify 之后）
3. `bash scripts/check-openapi-consistency.sh` **EXIT=0**
4. 四个新守卫类全绿 + 每个守卫的「变异自证」留痕（改坏 → 红 → 还原 → 绿）

## 7. 停步（分组独立，不连坐）

| 触发 | 处置 |
|---|---|
| G1 全轮 0 次共现 | 停 G1，`NO-COOCCURRENCE` + 原因；G2/G3 照常 |
| G1 需要改 `src/main` 才能观测 | **立即停 G1 上报**（侦察结论错） |
| G2 `maxAbsDeviation > 1e-3` | 停 G2，报实测偏差与超阈值对数 |
| G2 外呼中途失败 | 停 G2，落 `failedCalls` 实测值，不许删点重算 |
| G3 因前组停步 | **不放弃**：该项写 `仍缺 + 原因`，其余照写 |

## 8. 回报（≤300 字，细节留文件）

必含：产出路径 / 各文件行数 / 四项门禁结果 / `verdict` 与 `proxyVerdict` 取值 / `minDownwardGap` 与 `maxAbsDeviation` 实测值 / 停步组（若有）/ 待 Owner 决策项。**不得**把表格、逐条证据、日志粘进回报。

## 9. 修复上限

**1 次。** 首轮门禁或守卫红 → 修一次；仍红则如实上报并停，禁止「验证到通过」的循环。

## 10. 执行记录（2026-10-24，执行子 agent，分支 `feature/near-dup-missing-evidence` @ master `5519c43`）

**产出**（行数按 wc -l）：`NearDupDisplacementEvidenceIT.java`（约 1000 行）/ `NearDupDisplacementEvidenceGuardTest.java`（约 260 行）/ `ImageVariantCrossMatrixIT.java`（约 470 行）/ `ImageVariantCrossMatrixGuardTest.java`（约 250 行）/ `near-dup-displacement-evidence-v1.json+md` / `image-variant-cross-matrix-v1.json+md` / 签收页 §4（仅 §4，diff 4+/4-）/ `tasks.md` 全勾 / 本文件 / `scripts/test-baseline.txt`（经 --update）。

**四项门禁**：① clean verify BUILD SUCCESS（surefire 2122/0/0 + failsafe 147/15）；② 基线 --update 后 check EXIT=0；③ openapi 一致 EXIT=0；④ 三守卫绿 + 变异自证留痕（G1 minDownwardGap、G2 maxVariantVsThird+proxyVerdict、签收页删引用行，均红→还原 SHA 一致→绿；另做 IT 级 p50 变异 → replay 逐字节红 → 还原绿）。

**实测值**：G1 `queryCount=7`、共现 6/7、`minDownwardGap=0.00941087658500056` ≤ W_max 0.15 ⇒ `verdict=POSSIBLY-DISPLACED`、externalCalls=77、反事实 1479/2160（0.75/0.6/0.5 档 = 365/502/612）；G2 externalCalls=144、396 对对账 `maxAbsDeviation=0.0`（PASS，编码确定性实证）、variant↔第三图 2760 值 max=0.9973 带内 14 对 ⇒ `proxyVerdict=PROXY-UNDERSTATES`；两锚默认复跑逐字节一致、零外呼。锚 SHA：displacement `5444719a…ad9b`、cross-matrix `3adde4ec…319b`。

**已声明的执行口径（4 条，判据零改动）**：① 写锚真跑走 failsafe-only 定向调用（test-compile failsafe:integration-test failsafe:verify）——首跑全量 verify 红于新守卫「锚存在」断言（surefire 先于 failsafe 写锚的时序），failsafe-only 是同一 IT 的最小执行，锚落盘后全量 verify 全绿；② 「默认模式复跑」= opt-in 无 write，从锚内冻结向量本地重放生产检索链、零外呼、序列化与磁盘锚逐字节比对（VLM 描述与跨次编码不可逐字节确定，M2 的 1e-3 容差本身排除跨次真跑逐字节一致；两锚均已复验）；③ G1 downwardGap 按 tasks 1.4「逐查询」口径计算（含 1 条非共现查询的 IMAGE↔IMAGE gap 入 withinWindow；proposal D2「共现查询」窄口径下 minDownwardGap 同值、verdict 不变，md §1 披露）；④ 视觉模型 `-Drag.pdf.image.vision-model=qwen3-vl-plus`（灰卡成本锚同款；生产默认 qwen-vl-plus 在该 key 返回 model_not_found）。

**行尾修复（首次提交后补丁）**：Jackson INDENT_OUTPUT 默认对象缩进器在 Windows 落 \r\n，两锚首提交为 CRLF 工作树字节，而 .gitattributes 对 *.json 强制 eol=lf ⇒ CI checkout 后锚 SHA 漂移、引用与守卫必红。修复=两 IT 的 ObjectMapper 显式对象缩进器 DefaultIndenter("  ", "\n")（数组缩进器保持默认 FixedSpace，形态与默认打印机逐字节同构）+ 现锚 \r\n→\n 转换 + 全部引用 SHA 按 LF 字节重算（displacement=5444719a…9ad9b、cross-matrix=3adde4ec…319b），三守卫与两锚 replay 复验全绿。

**未解决 / 待 Owner**：① 查询-相关性标注（签收页 §4 第 2 条）本卡范围外，维持缺口；② 7 条查询为存在性证据，跨查询分布须另立卡（先验证 72 例基准套件检索端口是否生产路）；③ 卡 G3 决策请求维持「待签收」，`POSSIBLY-DISPLACED + PROXY-UNDERSTATES` 供签收参考，本卡不给推荐档；④ 分支未合并未 push，等 A 档复核与合并授权。
