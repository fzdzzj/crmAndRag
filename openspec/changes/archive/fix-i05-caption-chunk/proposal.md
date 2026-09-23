# 提案：I-05 图注与黄金块切分对齐

> 变更 ID：`fix-i05-caption-chunk` ｜ 能力域：`rag-quality` ｜ 序列：I-05 取证后的对症切口
> 来源：`docs/rag-quality/i05-forensics.md`。**禁止改 CitationAligner。**

## Why

取证已否掉「对齐器错号」：

| 项 | 实测 |
|---|---|
| 答案 | 有 `[1]`，对齐前后相同 |
| `[1]` | `sla-3`，完整「四层 / 台账与预警引擎」 |
| 黄金 | `sla-2`（rank 3），主文是赔偿 + 何建军，图注被 320 滑窗切走 |

`sla-terms.md` 里 `【GOLD:sla-arch-diagram】` 标在图注段首，但 `FixedChunkingStrategy` 是冻结的 320/40 滑窗（`document-chunking`：fixed 与升级前逐字等价）。GOLD 剥掉后图注与上一段责任工程师粘在同一窗口，标记落在 `sla-2`，完整图注在 `sla-3`。citP 只认 golden id → 模型引用了更对的块，分数仍是 0。

再调 KEEP/REMAP 会把正确的 `[1]` 改错。应对症：**让「GOLD 标记所在块」含完整图注**。

不改 320/40（解冻 fixed 另案）。本单只把图注从长 SLA 文里拆成独立短语料，滑窗再也粘不到赔偿段。这和真实知识库「图注单独成页/成文件」一致。

## What Changes

### 1. 语料
- 新增 `src/test/resources/rag-quality/fixtures/sla-arch-diagram.md`：仅保留现图注段（含 `【GOLD:sla-arch-diagram】` 原文，不改措辞）。
- 从 `sla-terms.md` **删除**该图注段，其余 GOLD 不动。
- `RagBenchmarkDataPreparer.FIXTURES` 登记新文件（key 如 `sla-arch`）。

### 2. ¥0 断言
`RagBenchmarkDataPreparerTest`：
- `sla-arch-diagram` 对齐到的 chunk 文本含「四层」或「接入层」以及「台账与预警引擎」；
- **不含**「何建军」「赔偿当月服务费」（证明没和责任/赔偿段粘在一起）；
- 文档前缀去重计数 11→12；54 条用例与占位 id 不变。

不升 `SUITE_VERSION`（不增删用例、不改占位 id）。I-05 与 after-quality-loop 的 citP 不可比，等取证重跑。

### 3. 不改
FixedChunkingStrategy、CitationAligner、Evaluator、I-05 的 expectedChunkIds。

### 4. 授权：只重跑 I-05 取证
确认 citP 不再是 0（目标 1.0；若 retrieved 里黄金不在被引 `[n]` 再停下）。禁止 54 条、禁止覆盖 v2 / after-quality-loop。

## Impact

- 评测 fixtures + FIXTURES + DataPreparerTest；ci surefire 随实测。
- 生产切分行为不变。长页中图注仍可能被 320 切开——那是解冻 paragraph-aware fixed 的另案，本单 Non-Goal。

## 风险

- 其它 sla-* 黄金因删段导致窗口位移：标记仍在各自段内，¥0 对齐测会抓漏。
- 独立短文件可能让图注变成唯一 chunk 且 rank 1，I-05 变「送分」：这正是切分正确后的预期，不是刷分。

## Non-Goals

- 不改对齐器、不改 320/40、不升 SUITE_VERSION、不跑 54 条、不改 T-14。
