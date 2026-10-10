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

## v3 锚点状态（已落盘，2026-10-10 真跑实证）

- owner 已授权 `RAG_BENCHMARK_REAL=1`，任务组 4 单轮默认矩阵真跑已执行（failsafe 1/0/0/0，elapsed 290.7s，未触额，跑毕清开关）；`docs/rag-quality/baseline-v3.json` 落盘——suiteVersion 3.0 / caseCount 63 / failureRate 0 / meanRecallAtK 0.9550 / totalTokens 61734。
- 旧 54 例对照 `baseline-after-quality-loop.json` **零回退**（敏感例逐例一致：T-12=0.667 / T-14=0.5 / TB-01=1.0 / TB-10=1.0 / E-02=0 / E-04=0）。
- **证伪结论（验收 #3 FAIL）**：MISMATCH M-01..M-09 关态 recall 9/9 = 1.0，缺口面为零——默认检索稠密语义路线已桥接近义级词面失配，改写收益面在 synonym 级失配上结构性不存在；与 `ladder-report.md`「作用面为空」互证。设计假设「2-gram 词面近零 ⇒ 漏召缺口」被真跑推翻，本卡以证伪结案。

## 激活卡接口（不立项，2026-10-10 owner 拍板）

- 三开关（`rag.query.multi-query.enabled` / `hyde.enabled` / `derived-questions.enabled`）**留库默认关**，处置表 07/15/06 已改判「不做（真跑证伪收益面）」。
- 激活卡**不立项**：MISMATCH 组关态 recall 已满格，无攀升度量面。若未来生产出现**歧义/干扰项型**查询痛点（multi-query 真正作用面为改写消歧而非同义桥接），另立新卡再议（挂低优先级账）。
- `baseline-v3.json` 留档为 63 例 V1 口径现行锚，供后续任何卡锚定。

## 验证命令

```powershell
$env:DASHSCOPE_API_KEY=''
mvn -B -ntp test
bash scripts/check-test-baseline.sh
bash scripts/merge-gate.sh
```

## 遗留 / 非本单

- 任务组 4 真跑已执行并以证伪结案（见上）；`baseline-v3-diff.md` 不产出，结论并入 tasks.md §0 与本文件。
- merge / push 已完成：`c405776`（--no-ff 入 master），push `c155226..c405776`，CI 第 32 轮全绿。
- 本卡收口笔：baseline-v3.json + i05-forensics.json 证据入库、tasks.md 4.x 回填、处置表 07/15/06 改判（owner 2026-10-10「都授权」）。