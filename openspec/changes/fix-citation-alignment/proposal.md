# 提案：生成后引用编号↔片段对齐（I-05 类缺陷）

> 变更 ID：`fix-citation-alignment` ｜ 能力域：`ai-citation` ｜ 序列：RAG 质量闭环第一单（锚点→弱项→修→复测）
> 来源：`docs/rag-quality/baseline-v2-anchor.md` 异常清单 I-05；用户拍板「深化技术、更符合常理」，本单用生成侧小切口先跑通闭环。

## Why

v2 锚点（54 条，SUITE_VERSION 2.0）已量化出一类**检索已成功、答案也对、引用编号却全错**的缺陷：

| 已验证事实 | 证据 |
|---|---|
| I-05 recall@5=1、hit=true | 黄金块进了 top-5 |
| precision@5=0.2、MRR=1/3 | 黄金块排第 3（5 条里 1 条相关） |
| answerConsistency=1.0 | 要点「四层 / 台账与预警引擎」判卷全覆盖 |
| citationPrecision=0.0 | 答案里的 `[n]` 映射到的都不是黄金块 |
| 生产与评测抽取逻辑同构 | 只做 `1..sources.size()` 范围过滤，**不核对编号对应片段是否支撑该句** |

黄金块原文（`sla-terms.md` `【GOLD:sla-arch-diagram】`）含「接入层→调度层→处理层→数据层 SLA 台账与预警引擎」。上下文组装（`ContextBuilder`）已保证 `[n]` 与 `sources` 下标一一对应——编号错位发生在**生成之后**，不是检索/压缩破坏映射。

**当前假设**（基线 JSON 不存答案原文，未亲见 I-05 的 `[n]` 列表）：模型把承重结论标成了 rank-1/2 的他块编号。本单用生成后、零外呼的编号↔片段校验把错号改回支撑块；若复测 I-05 的 citP 不动，按失败场景停下汇报，不调阈值硬凑。

为什么先修它、不先动 TABLE/数值检索：后者要改检索架构（大提案）；本单能先建立「修 → 54 条复测 → 对照 v2 锚点」仪式，后续大修才有对照锚。

## What Changes

### 1. 纯函数对齐器（零外呼、无 Spring 依赖）
新增 `com.slz.crm.server.ai.CitationAligner`（Javadoc 标注「fix-citation-alignment 任务 1.1」）：

```
align(answer, sources) → Alignment(text, citations)
```

- **计分**：对每个 `[n]` 取所在子句（按 `。！？；\n` 切，编号挂在其前子句），与每条 `SourceReference.excerpt` 算 CJK 二字 Jaccard + 共享 ASCII/数字 token 加成，得分 `[0,1]`。
- **保守决策**（防止误伤已经标对的引用）：
  - **KEEP**：`n` 在 `1..N` 内，且 `score(n) ≥ KEEP_MIN`，且 `score(n) + TIE_MARGIN ≥ best` → 不动。
  - **REMAP**：存在 `best ≥ REMAP_MIN`，且（`n` 越界 **或** `score(n) < KEEP_MIN` **或** `best - score(n) ≥ TIE_MARGIN`）→ 把该处 `[n]` 改成 best 的 1-based 编号。
  - **DROP**：原编号弱支撑且没有任何 excerpt ≥ `REMAP_MIN` → 删掉该 `[n]`，不编造来源。
  - **不补漏引**：答案里本来没有 `[n]` 的，不对齐器凭空插入。
- 阈值写死在类内常量（建议 KEEP_MIN=0.12、REMAP_MIN=0.22、TIE_MARGIN=0.08；实现时以单测夹住行为，允许微调但必须在汇报里写明最终值）。**不新增动态配置键**。
- 确定性、无 `ModelProvider`、无网络。`sources == null/empty` 或 `answer` 空白 → 原文 + 空 citations。

### 2. 接入两点（同一对齐器，禁止评测/生产两套逻辑）
- **生产** `AiChatStreamLifecycle` `doOnComplete`（约 L151–161）：在 `extractCitations` **之前**对 `content` 做 `CitationAligner.align`；**落库文本**与 `references.citations` / audit payload 用对齐后结果。
- **评测** `RagRealRetrievalBenchmarkIT.evaluateCase`（约 L204–207）：生成后先 align 再 `extractCitations`；判卷可用对齐后文本（只改编号，要点词不变）。
- **不改** `AiChatStreamLifecycle` 构造器签名（`AiChatStreamLifecycleHeartbeatTest` / `AiChatServiceImplTest` 直接 `new`）。
- **不改** 已流出的 SSE token（流式 TTFT 不牺牲）；已知限制：直播过程中用户可能短暂看到模型原编号，刷新历史看到对齐后文本。

### 3. ¥0 单测夹住行为
`src/test/java/com/slz/crm/unit/ai/CitationAlignerTest.java`（中文 Javadoc 标任务号），至少覆盖：
1. **I-05 形**：黄金块排第 3，答案引用 `[1]` 但子句只被第 3 块支撑 → 改为 `[3]`，citations=`[3]`。
2. **已对齐不动**：答案已标 `[3]` 且第 3 块强支撑 → 原文逐字保留。
3. **平局保原号**：两块得分接近 → KEEP 原编号。
4. **无支撑则删号**：`[1]` 与所有 excerpt 都弱 → 去掉该 `[n]`。
5. **空 sources / 无编号 / 越界号**：不抛错；越界号仅在有强支撑块时才 REMAP。
6. **不补漏引**：无 `[n]` 的正确陈述保持无编号。

`RagQualityEvaluator` 的 citationPrecision **公式不准改**（C1/C2/C3 手算单测是口径锁）。

### 4. 真基准复测（授权节点）
任务组 1–3 全绿后停下。授权后用**纯默认矩阵**重跑 54 条，落 `docs/rag-quality/baseline-after-citation.json`（**禁止覆盖** `baseline-v1.json` / `baseline-v2.json`），对照 v2 锚点写差异说明。

成本预估：与 v2 锚点同结构（约 54×3–4 次 LLM + 入库嵌入），¥ 个位数，以实际账单为准。对齐器本身 ¥0。

## Impact

- **新增**：`CitationAligner.java`、`CitationAlignerTest.java`；授权后新增 `docs/rag-quality/baseline-after-citation.json`（及短说明可写在 tasks.md 执行记录）。
- **修改**：`AiChatStreamLifecycle`（complete 路径，构造器不动）、`RagRealRetrievalBenchmarkIT.evaluateCase`；surefire 基线随新增单测上调（ci.yml 三处，读实测再改）；`HANDOFF.md` 收尾更新。
- **随手带（非本单功能）**：`openspec/changes/expand-rag-benchmark/tasks.md` 任务 4.1–4.3 补勾（v2 锚点已跑，只是 checkbox 未勾）。
- **不改**：检索/分块/压缩、`SourceReference` / `SseEventName` / SSE payload 字段集、`SUITE_VERSION`（仍 2.0）、既有 54 条用例与 fixtures、Flyway、`init_data.sql`、动态配置键表。

## 风险

- **误伤已正确引用**：阈值过松会把对的 `[n]` 改走，全套 citP 可能低于 v2 的 0.7843。对策：保守 KEEP/平局规则 + 单测夹住「已对齐不动」；复测若全套 citP 下降，**停下汇报，不在本单里放宽阈值硬凑**。
- **I-05 假设不成立**：若模型写了正确要点却引用的 excerpt 与黄金块词面差太远，词法对齐抬不动 citP。对策：复测 I-05 未到 1.0 时记下答案原文与对齐前后编号，作为失败场景，不改评测口径。
- **流式已发出的 token 与落库不一致**：已知限制，写进 spec；不为此改成「攒齐再流」（会牺牲 TTFT）。
- **`[80%]` 一类非引用方括号**：现有 `\d{1,3}` 且越界忽略；N=topK≤默认 5，80 不会被当成引用。单测不需要为百分号新开路径，但实现时不得放宽成「任意数字都 remap」。

## Non-Goals

- 不改 TABLE 聚合/数值区间检索（TB-01/TB-10）——下一张大提案。
- 不改 T-14 多条件分解。
- 不引入 LLM 判引（成本闸门）。
- 不改 citationPrecision 公式、不升 SUITE_VERSION、不覆盖历史锚点 JSON。
- 不改 `references` 事件「仅当业务 items 非空才下发」的既有门（KB-only 答案的前端高亮是相邻问题，另案）。
- 不回放改写已流出的 SSE token。
- 不新增 controller / 权限点 / 迁移脚本 / Maven 依赖。

## 失败场景（实现中撞上必须停）

1. 复测 I-05 citP 仍为 0，或全套 citationPrecision < v2 的 0.7843。
2. recall@5 / MRR / hitRate 相对 v2 出现检索侧才该有的变动（本单不改检索，若动了说明接错线）。
3. 为凑指标改 `RagQualityEvaluator`、改黄金块、或注入 `rag.*` 跑锚点。
