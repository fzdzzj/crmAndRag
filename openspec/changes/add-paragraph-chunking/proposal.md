# 提案：段落感知切分（解冻 fixed 的生产同类问题）

> 变更 ID：`add-paragraph-chunking` ｜ 能力域：`document-chunking` ｜ 序列：I-05 在真实入库上的同类修复
> **先决**：`measure-perf-baseline` 已合入。禁止与测量单并行。
> 来源：I-05 取证——320 滑窗把图注从 GOLD 段切开。评测已拆文件；**上传的长 md/pdf 页仍会横切**。

## Why

`FixedChunkingStrategy` 320/40 被规范锁成「与升级前逐字等价」。I-05 的根因就是这个窗口切开段落。不能改 CHUNK_SIZE/OVERLAP 来偷换；要新增一种策略，并 **保留 `fixed` 字节等价**，回退演练继续绿。

## What Changes

### 1. 新策略 `paragraph`（默认不启用）
`DocumentService.STRATEGY_KEY` 增加合法值 `paragraph`。算法：
- 窗口仍 320，重叠仍 40（常量不改）
- 若 `end < length`，在 `[start, end)` 内找**最后一处**段落界（优先 `\n\n`，否则 `\n`）
- 仅当 snap 位置 ≥ `start + 80` 才收缩 end，避免空切片或切太碎
- 单段本身 >320：该段内仍按 320/40 滑，不跨段拼下一段
- 无换行的长文：与 `fixed` 逐字相同

不新增动态配置命名空间；继续读 `rag.chunking.strategy`（与 semantic 相同，未登记键也可 get 默认）。

### 2. `fixed` 冻结保持
- `FixedChunkingStrategy` 实现与 320/40 **字节级不动**
- `fixedStrategyMustMatchLegacyAlgorithm`、`ChunkingRollbackDrillTest` 链 1 必须继续绿
- 无参 `DocumentService()` 仍走 `fixed`

### 3. 默认策略不自动切换
`application.yml` / 无配置 **仍是 fixed**。生产要吃到 I-05 同类修复，须显式 `rag.chunking.strategy=paragraph`（文档写明；**本单不改 yml 默认**，避免 54 条评测语料切分漂移）。评测 `DocumentService()` 无参 = fixed，I-05 独立文件不受影响。

若用户要求把生产默认改成 paragraph：停下另报，不在本单擅自改。

### 4. ¥0 单测
- I-05 形：赔偿段 + 空行 + 图注段（各 <320，合计 >320）在 `paragraph` 下图注整段在同一 chunk，且不含「何建军」与图注的错误粘连切开
- 无换行长文：`paragraph` 与 `fixed` 逐字相同
- 超长单段（>320）：段内仍 320/40，不丢字、覆盖全文
- 显式 `fixed` 仍等于 legacy
- pageNo/rowIndex 仍按页/行，不跨页

### 5. 不跑 54 条
评测默认 fixed，本单不授权真基准。

## Impact
- 新增 `ParagraphChunkingStrategy`（或同类）+ DocumentService 分支
- MODIFIED：document-chunking 规范增加 paragraph 场景；fixed 回退场景保留
- 不改 320/40 常量、不改对齐器、不改 fixtures（I-05 已拆文件保持）

## 风险
- 段落界在 PDF 抽文本里可能是单 `\n`：算法已 fallback 到 `\n`
- 用户忘了配 paragraph：生产行为不变——HANDOFF 必须写「要生效须配策略」

## Non-Goals
- 不解冻 320/40、不改 semantic、不加 NLP、不默认切换 yml、不跑 54 条、不改线程池。
