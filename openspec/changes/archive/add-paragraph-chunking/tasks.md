# Tasks — add-paragraph-chunking

> 先决：measure-perf-baseline 已在 master。禁改 FixedChunkingStrategy 字节行为。不改 yml 默认。不跑 54 条。

## 0. 执行记录
- 策略类：`ParagraphChunkingStrategy`（窗 320 / 重叠 40 / snap min 80；`\n\n` 优先否则 `\n`；snap 后跨过边界不跨段拼）
- 单测：`ParagraphChunkingStrategyTest` 4 条（I-05 形 / 无换行≡fixed / 超长单段覆盖 / DocumentService 解析）
- yml 默认：未改（仍 fixed）；生产生效须 `rag.chunking.strategy=paragraph`
- FixedChunkingStrategy 相对 7a67c4f：无实现 diff
- surefire 实测：**653**（Failures 0 / Errors 0 / Skipped 0）

## 1. 策略实现（¥0）
- [x] 1.1 新增段落感知策略，窗口 320/重叠 40，snap `\n\n` 否则 `\n`，min 80
- [x] 1.2 `DocumentService.resolveStrategy`：`paragraph` 启用；其它/空仍 fixed。Javadoc 标任务号
- [x] 1.3 `FixedChunkingStrategy` 零 diff（或仅注释）。`fixedStrategyMustMatchLegacyAlgorithm` 必须绿

## 2. 单测（¥0）
- [x] 2.1 I-05 形两段：图注整块、不与责任人段错误对切
- [x] 2.2 无换行长文 = fixed 逐字
- [x] 2.3 超长单段覆盖全文
- [x] 2.4 ChunkingRollbackDrillTest 链 1 仍绿

## 3. 文档与 CI
- [x] 3.1 `docs/dynamic-config-keys.md` 或 perf-baseline/HANDOFF 写明：生产要生效设 `rag.chunking.strategy=paragraph`；默认仍 fixed
- [x] 3.2 `mvn -B -ntp test` 实测改 ci.yml 三处（649→653）

## 4. 收尾
- [x] 4.1 HANDOFF
- [x] 4.2 `feature/add-paragraph-chunking`；`--no-ff`；不 push；三未跟踪件勿动
