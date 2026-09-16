# HANDOFF — add-paragraph-chunking

> 日期：2026-09-16 ｜ 分支：`feature/add-paragraph-chunking` ｜ 基线 master @ `7a67c4f`（measure-perf-baseline）

## 做了什么

1. **新增** `ParagraphChunkingStrategy`：与 fixed 同窗 320 / 同重叠 40；窗口未触底时在 `[start,end)` 找最后 `\n\n`（否则 `\n`），仅当 snap ≥ start+80 收缩；snap 后下一窗从边界之后起算（不跨段拼下一段）；无换行 ≡ fixed 逐字；超长单段内仍 320/40 滑窗。
2. **改** `DocumentService.resolveStrategy`：仅 `paragraph` 显式启用新策略；`semantic` 分支保留；其余/空/无参构造仍 fixed。新增常量 `STRATEGY_PARAGRAPH`。
3. **单测** `ParagraphChunkingStrategyTest` 4 条：I-05 形图注整块、无换行≡fixed、超长单段覆盖、配置解析。
4. **文档**：`docs/dynamic-config-keys.md` 登记 paragraph；`docs/perf-baseline.md` 边界行注明默认仍 fixed、勿拿 paragraph 切片数冒充微基准。
5. **CI**：surefire 基线 **649→653**（ci.yml 口径A 注释 / check_baseline / 错误提示）。

## 硬边界核对（亲验）

| 项 | 结果 |
|---|---|
| `FixedChunkingStrategy` vs 7a67c4f | **无 diff** |
| `application.yml` 默认策略 | **未改**（无 paragraph / 无 rag.chunking.strategy 写入） |
| CitationAligner / 线程池 / RAG_BENCHMARK_REAL | **未动** |
| `fixedStrategyMustMatchLegacyAlgorithm` + Rollback 链1 | **绿**（随 `DocumentServiceTest,ChunkingRollbackDrillTest` 亲跑） |
| surefire 全量 | **653 / 0 / 0 / 0**（`DASHSCOPE_API_KEY=''`；2026-09-16） |

## 生产生效（必读）

**默认行为不变（仍 fixed）。** 要吃到 I-05 同类「图注不被 320 横切」修复，须显式配置：

```text
rag.chunking.strategy=paragraph
```

只影响**新摄取/重建**的切片；已入库向量不会自动重切。评测 `new DocumentService()` 无参 = fixed，54 条语料切分不漂移（本单未跑 54 条）。

## 验证命令

```powershell
$env:DASHSCOPE_API_KEY=''
mvn -B -ntp test
mvn -B -ntp test "-Dtest=DocumentServiceTest,ChunkingRollbackDrillTest,ParagraphChunkingStrategyTest"
```

## 遗留 / 非本单

- 不把 yml 默认切到 paragraph（需另案授权，避免评测切分漂移）
- 不跑 54 条真基准、不改 320/40 常量、不解冻 fixed 字节行为
- 三未跟踪件（`_rag优化交接.md` / `_vlm_transcribe.py` / `_技术深化交接.md`）勿动
