# HANDOFF — expand-rag-benchmark-mismatch

> 日期：2026-10-10 ｜ 分支：`feature/expand-rag-benchmark-mismatch` ｜ 基线 master @ `c155226`

## 做了什么

1. **新增 3 份失配语料**（`src/test/resources/rag-quality/fixtures/`）：`trade-jargon-glossary.md`（行话/术语）/ `equipment-codebook.txt`（编号体系 EQ-26-）/ `expense-colloquial-faq.md`（口语-术语同义）。人名/编号/事件全新，与既有 12 语料零串扰。GOLD 占位 9 块各自落独立切片。
2. **新增 category=MISMATCH 用例组 M-01..M-09**（`RagBenchmarkSuite`），question 用口语/行话之外话面、gold 用术语/编号/书面语，词面 2-gram 交集 0–3（近零，机械判据已验）；答案点宽写（E 组先例）。
3. **套件升 v3.0**：`SUITE_VERSION "2.0"→"3.0"`，54→63 条（TEXT 17 / TABLE 13 / IMAGE 6 / LEXICAL 14 / EDGE 4 / **MISMATCH 9**）。
4. **门禁配套**：`RagBenchmarkDataPreparerTest` 新增失配语料对齐/加载用例、fixture 数 12→15、套件规模断言 63/3.0/MISMATCH 9；`RagQuantRegressionTest` regen 已更新 `baseline-v1.json` 的 `fixtureRegression` 段（suiteVersion 3.0 / caseCount 63 / recall@5 0.8413 / mrr 0.6839 / chunk 57 / fixture 15），真跑 metrics/cases 锚点段逐字保留。
5. **规格侧**：`openspec/project.md` 处置表 07/15/06 行台账注记「激活度量前置已闭合（expand-rag-benchmark-mismatch）」；本卡三件套已复制入权威树 `openspec/changes/expand-rag-benchmark-mismatch/`。

## 硬边界核对（亲验）

| 项 | 结果 |
|---|---|
| 既有 12 fixture 正文与 GOLD | **未动** |
| 既有 54 例 case 与 gold ID | **未动** |
| `baseline-*.json`/`after-*.json` 真跑锚点 | **未覆盖**（仅 regen 写入口更新 baseline-v1.json fixtureRegression 段） |
| 三开关默认 false | **未动** |
| `src/main/java` 检索管线 | **零改动**（仅测试域质量目录扩 MISMATCH 分类与 case） |
| frontend/ / DDL / 新依赖 | **未动 / 零 / 零** |
| surefire 计数 | 1088→**1089**（新增 1 个 DataPreparer 加载用例） |

## v3 锚点状态（待授权）

- 默认矩阵（V1、不注入 rag.*、三开关 false）的**真跑锚点 `docs/rag-quality/baseline-v3.json` 尚未落盘**（任务组 4，需 owner 显式授权 `RAG_BENCHMARK_REAL=1`）。本卡 ¥0 部分已合入，v3 锚点待补跑不阻塞后续授权流程。
- 对照口径：旧 54 例对照 `baseline-after-quality-loop.json`（V1 最新锚），任一旧例 recall 回退即 FAIL；MISMATCH 组 per-case recall<1.0 ≥6 条为「缺口成立」落证。
- 真跑命令/产物见 `design.md §4`。

## 激活卡接口（后续立项）

- 以 `baseline-v3.json` 为**关态基线**，开 `rag.query.multi-query.enabled` / `hyde.enabled` / `derived-questions.enabled` 真跑对照，MISMATCH 组 recall 攀升即 07/15/06 收益实证。

## 验证命令

```powershell
$env:DASHSCOPE_API_KEY=''
mvn -B -ntp test
bash scripts/check-test-baseline.sh
bash scripts/merge-gate.sh
```

## 遗留 / 非本单

- 任务组 4 真跑授权节点（`RAG_BENCHMARK_REAL=1` + `-Drag.benchmark.out=docs/rag-quality/baseline-v3.json`）**未执行**。
- merge / push 需 owner 显式授权（本卡停在分支，未 push）。