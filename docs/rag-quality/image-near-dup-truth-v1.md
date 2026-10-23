# 图像近重复检索级真值锚 v1（query-relevance-truth · G2）

> **本文件的每一个数字都来自 `ImageNearDuplicateTruthEvaluationTest` 的固定表格**（stdout 前缀 `[QRT2][表 N/3 …]`），无手算数字。
> 机器可读锚：`docs/rag-quality/image-near-dup-truth-v1.json`（唯一写入口 = `ImageNearDuplicateTruthEvaluationTest` 的写模式
> `-Dnear.dup.truth.write=true`，默认模式逐字节校验）。
> 既有评测口径四文件（`RagQualityEvaluator` / `RagBenchmarkCase` / `ImageCrossModalSuite` / `PdfIngestImageFixture`）与上一卡两锚**零改动**；本卡零外呼、零生产常量改动。

## 0. 一句话结论

**T4 全绿：120 个正例（24 base × 5 kind 变体）经既有 `RagQualityEvaluator` 判卷的 `recallAtK`，与上一卡锚 `R(f)` 两条独立路径在全部 8 档逐档相等（含基线 0.9887）——pair 级同源保留率就是检索级真值召回；跨文档污染率全档 1/176 = 0.005682；同文档 hard negative 竞争率 0.02 → 0.08。**

## 1. 真值来源（provenance，非人工标注）

真值机制 = 项目既有 `expectedChunkIds` 黄金切片机制（`ImageCrossModalSuite:49`「expectedChunkIds = 同图切片 id，供 RagQualityEvaluator 判 recall」、`PdfIngestImageFixture:247` 同图用例形状），本卡把**近重复机制自身的判别面**接进去：

- **goldenId 唯一生成点**：`ImageNearDuplicateTruthSuite.goldenId(baseKey) = "golden:" + baseKey`——由 `NearDuplicateCorpus` 的键唯一导出，**无手写字面量 id**（T1）；
- **正例 120**（24 base × 5 kind，24×5 每格恰一例由 `assertPositiveCompleteness` 守）：query = 变体，期望 = 该 base 的黄金切片；得分 = 锚内 near-duplicate 档逐对余弦；
- **负例 A（跨文档近似）176**：query = base 再编码，期望 = 空；top-k 出现跨文档候选即污染；得分 = cross-class 档逐对余弦；
- **负例 B（同文档不同图，hard negative）100**：query = base 再编码，期望 = 查询 base 自身（same-image 档余弦，锚内实测 144 对全 1.0）；同文档其它 base 的 same-class 档余弦为竞争者——照 `RagQualityEvaluator` 口径如实分类（不在 expected 集内），**按 A1 假设域记「相关但不必须」，不计污染**。

**排序重建零 embedding（T5）**：所有得分一律取自校准锚（SHA-256 `82d24204…`）的逐对余弦（历史真跑产物），不重新 embedding、不设 `RAG_*_REAL`、不读 `.env`、不访问网络；检索判定 = `cosine >= floor`，与上一卡 `R(f)` 的保留判定同语义。

**与 proposal 的一处落地偏差（锚 `deviations` 已登记）**：proposal §2.2 负例的「query = base_i 变体」落地为「query = base_i 再编码」——校准锚 540 对（144 同图 + 120 近重复 + 276 base 两两）已穷尽，**不存在变体↔异 base 对**；零编造优先于字面形状。正例不受影响（变体↔自身 base 对恰是锚内 near-duplicate 档）。

## 2. 评测接入（T2，口径零改动）

- `recallAtK`：既有 `RagQualityEvaluator.evaluate(suite, retrieval, k=5)` 原公式，每 floor 跑 3 个子套件（正例 / 负例A / 负例B），公式零 diff；
- **`contaminationAtK = 1 − mean(recallAtK over 负例A)`**：expected 为空时 evaluator 的诚实空判（top-k 空 = 1 / 非空 = 0）即正确拒答率，污染率是其补数——**既有 top-k 结果的派生量，不是新评测口径**；
- `negativeBSameDocCompetitorRate = 负例B 中 precisionAtK < 1 的占比`：自身恒在 top-k 内，同文档竞争者（≥ floor）出现即把 precision 从 1 拉低——同样从 evaluator 逐例结果派生。

## 3. 逐 floor 表（T3，8 档；全部来自写模式固定表格）

| floor | recallAtK | retained/120 | contaminationAtK | negA 诚实拒答 | negB recallAtK | 同文档竞争率 | 上一卡 R(f) | T4 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **0.9887（基线）** | 0.583333 | 70 | 0.005682 | 0.994318 | 1.0 | 0.02 | 0.583333 | ✓ |
| 0.985 | 0.641667 | 77 | 0.005682 | 0.994318 | 1.0 | 0.03 | 0.641667 | ✓ |
| 0.982 | 0.666667 | 80 | 0.005682 | 0.994318 | 1.0 | 0.03 | 0.666667 | ✓ |
| 0.98 | 0.675 | 81 | 0.005682 | 0.994318 | 1.0 | 0.04 | 0.675 | ✓ |
| 0.9785 | 0.691667 | 83 | 0.005682 | 0.994318 | 1.0 | 0.05 | 0.691667 | ✓ |
| 0.975 | 0.716667 | 86 | 0.005682 | 0.994318 | 1.0 | 0.06 | 0.716667 | ✓ |
| 0.95 | 0.833333 | 100 | 0.005682 | 0.994318 | 1.0 | 0.08 | 0.833333 | ✓ |
| 0.90 | 0.941667 | 113 | 0.005682 | 0.994318 | 1.0 | 0.08 | 0.941667 | ✓ |

逐 kind 保留数与上一卡表 2/4 逐档一致（如基线 jpeg-q80=18 / scale-085=21 / scale-070=22 / crop-04=9 / crop-12=0；0.90 档 crop-12=17 仍为跌幅最大档），锚 `floors[].retainedByKind` 逐档落齐。

**读法**：

1. **T4 是结构性自检（两条独立路径算同一个数）**：本套件走「evaluator 判卷」（expectedChunkIds + top-k recall），上一卡走「pair 级保留计数」；8 档全部逐档相等 ⇒ 上一卡 `R(f)` 就是这 120 个 provenance 正例的检索级召回，pair 级证据可直接当检索级证据引用。
2. **跨文档污染全档恒 0.005682**：cross-class 余弦最高 0.988736（即基线那 1 对带内泄漏）、次高跌到 0.73——任何 ≥0.9887 的候选档污染面不变，与上一卡 C_cross 的结构结论一致。
3. **同文档 hard negative 挤不掉自身**：same-image 余弦 1.0 恒压过全部带内竞争者（negB recall 恒 1.0）；竞争率随 floor 下探单调上升（0.02→0.08），这就是上一卡 `S_same` 有害量上界在检索级的对应面（仍是上界：共现未测）。

## 4. T1–T5 逐判据照判

| 判据 | 实测 | 结论 |
| --- | --- | --- |
| **T1** 真值可复算 | goldenId 由 baseKey 唯一导出（无手写字面量）；24 base × 5 kind 每格恰一例；120/176/100 逐数相等 | 满足 |
| **T2** 口径复用 | `RagQualityEvaluator` 公式零 diff（本卡 git 状态自证）；contamination/sameDocCompetitor 只读其结果派生 | 满足 |
| **T3** 逐 floor 表 | 8 档（0.9887/0.985/0.982/0.98/0.9785/0.975/0.95/0.90）× recallAtK/contaminationAtK/保留数 全齐 | 满足 |
| **T4** 结构性自检 | 8 档 `mismatches=0`：recallAtK ≡ 上一卡 R(f)，retained ≡ retainedNearDup | 满足 |
| **T5** 零外呼 | 不读 `.env`、不设 `RAG_*_REAL`、不访问网络；锚 `externalCalls=0` | 满足 |

## 5. 适用范围声明（三处，诚实边界）

1. **只覆盖图像路内检索级（pair 级重建）**：不含文本路、不含融合后排序、不覆盖生产重排器行为；
2. **负例查询 = base 再编码**（锚内无变体↔第三图余弦，不做外推）；正例查询 = 变体（唯一有逐对余弦的判别面）；
3. **真值 = 语料 provenance（同图/同源/同文档键关系），不是用户意图标注**：上一卡 A1（同文档 ⇒ 可能相关）仍是假设；查询-相关性标注的必要性在 G3 决策请求第 4 节写明，本卡不实施。

## 6. 复算命令

```bash
# 全量复算（写模式；退出码 0 = 复算成功，T4 逐档一致）
mvn -B -ntp test -Dtest=ImageNearDuplicateTruthEvaluationTest -Dnear.dup.truth.write=true

# 默认模式（只断言不写，与已提交锚逐字节校验；全量 verify 内即跑）
mvn -B -ntp test -Dtest=ImageNearDuplicateTruthEvaluationTest
```
