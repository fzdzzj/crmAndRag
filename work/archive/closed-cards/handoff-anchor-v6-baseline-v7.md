# handoff · anchor-v6-baseline-v7（v6.0 主套件真跑落锚 baseline-v7）

> 档位：A 档（72 例全量真跑 opt-in 落锚；零 src/main / 零迁移 / 零权限 / 零新依赖）
> 分支：feature/anchor-v6-baseline-v7（永久保留）｜M1 merge --no-ff `f74c5c9` → 基线收口 `a5e7fce`

## §1 目标与背景
`RagBenchmarkSuite.SUITE_VERSION="6.0"`（72 例）自 expand-rag-eval-blind-spots 升版以来从未真跑落锚（看板/logbook §144 登记
「baseline-v7 缺席，另卡授权」）。本卡跑主套件真跑 runner 落锚 `baseline-v7.json`，补齐 v6.0 评测基线缺口。Owner 拍板 72 例全量真跑。

## §2 方案与实现面（5 文件 +1391/−6，零 src/main）
- `RagRealRetrievalBenchmarkIT`（+77/−6）：增 `V7_ANCHOR_FILE_NAME="baseline-v7.json"`。
- `RagBenchmarkV7AnchorValidationTest`（286 行新增）：纯 JVM 零外发，对账 suiteVersion=6.0 / caseCount=72 /
  scoringVersion=v2 / 与 v6（5.0）不可比。
- `docs/rag-quality/baseline-v7.json` + `baseline-v7-anchor.md` 落锚。

## §3 实测结论（72 例真跑，341.2s）
suiteVersion=6.0、caseCount=72、scoringVersion=v2；72/72 tokens>0、totalTokens=72347（逐例求和严等）；
hitRate 67/68=.9853（合格集逻辑见 RagQualityEvaluator.java:104-123）。

## §4 零迁移 / 零权限 / 零契约论证
- `git diff --quiet -- src/main` 退出码 0；pom.xml 无 diff。
- 零新 controller/端点/权限常量。
- `RagBenchmarkSuite`/`SUITE_VERSION`/用例黄金片段/baseline-v1~v6/image/pdf 锚 blob 全同（只新增 v7）。

## §5 真跑论证（A 档判据）
`RagRealRetrievalBenchmarkIT` 双重 Assume（`RAG_BENCHMARK_REAL=1` + `DASHSCOPE_API_KEY`）未改动；72/72 tokens>0、
totalTokens 72347 严等、341.2s 真跑、指标逐例复算全命中（复核方独立复算 hitRate 67/68=.9853）。

## §6 偏差处置与治理留痕
- **D1（test-baseline -dirty，非否决）**：执行端在分支 --update 时 source-revision 记 `e248e6e-dirty`（base 未 merge 的笔序产物）；
  主 Agent M1 快车道 merge 后重锚干净 `f74c5c9`，计数零漂移。
- **D2（本次无新增 IT，skip 口径不变）**：failsafe 132/10 持平（跑既有 RagRealRetrievalBenchmarkIT 而非新 IT），
  +11 单测 = 新增 v7 锚点对账单测。

## §7 停步声明
未 push 前停步；push 由主 Agent 在 Owner 授权 merge 后执行（`e248e6e..a5e7fce`）。未 reset/rebase/amend/删分支。