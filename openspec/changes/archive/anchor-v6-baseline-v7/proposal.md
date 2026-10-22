# 提案 · anchor-v6-baseline-v7（v6.0 主套件真跑落锚 baseline-v7）

## 背景与动机

`RagBenchmarkSuite.SUITE_VERSION="6.0"`（72 例，expand-rag-eval-blind-spots 增多轮组 5 + 时间限定组 4 + D16
判分收紧）自升版以来**从未真跑落锚**——看板 §3 与 logbook §144 均登记「v6.0 真跑落锚 `baseline-v7.json` 属另卡授权
（沿 v3.0「暂未授权真跑落锚」先例）」。历史锚点 baseline-v1~v6 中，v6 锚是 SUITE_VERSION=5.0 的产物，v6.0（6.0 版）
的真跑基线缺失，导致 72 例套件的评测结论「无可比基线」。

本卡跑主套件真跑 runner 落锚 `baseline-v7.json`，补齐 v6.0 的评测基线缺口。

## 现状锚点（三源实读，2026-10-22）

- `RagRealRetrievalBenchmarkIT`（1025 行）：主套件真跑 runner，`RAG_BENCHMARK_REAL=1` opt-in + `DASHSCOPE_API_KEY`
  才执行，否则 Assume 跳过（默认 CI/本地 verify 恒零外呼）；历史锚文件名常量 `V6_ANCHOR_FILE_NAME="baseline-v6.json"`。
- `RagBenchmarkSuite.SUITE_VERSION="6.0"`（72 例：TEXT 21 / TABLE 13 / IMAGE 6 / LEXICAL 15 / EDGE 4 / MISMATCH 9 /
  TEMPORAL 4）。
- `RagBenchmarkV6AnchorValidationTest`（纯 JVM 零外发）已锁「v6 锚 = SUITE_VERSION 5.0」「SUITE_VERSION 必须为 6.0」。
- `RagBenchmarkDataPreparer`（fixture 幂等入库 + 黄金占位 id 对齐 + `rewriteSuite`）+ `RagQualityEvaluator`（recall/MRR/
  hit → `Report`）。

## Owner 拍板（2026-10-22）

**72 例全量真跑**：真嵌入 + 真查询改写 + 真模型生成 + 真判卷，`RAG_BENCHMARK_REAL=1` opt-in，落锚
`docs/rag-quality/baseline-v7.json`（suiteVersion=6.0）。

## 方案骨架

1. 跑 `RagRealRetrievalBenchmarkIT`（`RAG_BENCHMARK_REAL=1` + `DASHSCOPE_API_KEY`）→ 72 例全量真跑 → `Report`
   落盘 `baseline-v7.json`（suiteVersion=6.0）。
2. 同步 v6 锚文件名常量：`RagRealRetrievalBenchmarkIT` 增 `V7_ANCHOR_FILE_NAME="baseline-v7.json"`（对齐既有 V6 常量形态）。
3. 落盘后核对：suiteVersion=6.0、72 例 caseCount、与 baseline-v6（suiteVersion=5.0）不可比（跨版本声明）。
4. 锚点验证单测：对齐 `RagBenchmarkV6AnchorValidationTest` 形态，新增 v7 锚点对账（纯 JVM 零外发）。

## 边界与不改动面

- **不动** `RagBenchmarkSuite` 72 例 / `SUITE_VERSION` / 用例黄金片段（本卡只真跑落锚，不改套件）。
- **不碰** baseline-v1~v6 + image-baseline-v1 + pdf-ingest-image-baseline-v1（只新增 v7）。
- **零 src/main 改动**（纯 src/test + docs/rag-quality）；零迁移 / 零权限 / 零契约 / 零新依赖。

## 档位判定

**A 档（真跑）**：72 例全量真跑（真嵌入 + 真模型生成 + 真判卷），`RAG_BENCHMARK_REAL` opt-in。零生产代码但真跑判据触发 A 档。

## 验收判据

1. `baseline-v7.json` 落盘，suiteVersion=6.0、caseCount=72。
2. 72 例真跑完成（真嵌入 + 真查询改写 + 真模型生成 + 真判卷），非 mock 假绿。
3. 无 key 默认零外呼（Assume 跳过）；v7 锚与 v6（5.0）不可比声明齐备。
4. 独立复核四判据全过。