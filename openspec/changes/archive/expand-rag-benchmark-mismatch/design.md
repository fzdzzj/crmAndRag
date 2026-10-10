# 设计：expand-rag-benchmark-mismatch（P-ai）

> 中文 Javadoc/注释标注「expand-rag-benchmark-mismatch 任务 x.x」。本卡不碰检索管线主代码，全部改动在测试侧与规格侧。

## 1. 失配 fixture 设计准则（任务 1.x）

三个新 fixture，各含若干 GOLD 占位块（先例：`payment-plan.md` 内嵌 `【GOLD:xxx】`），**人名/数字/事件全部新造**（约束在案：新 fixture 必须用全新人名数字事件避免词法串扰，参考 expand 卡五文件命名风格）：

| fixture（建议名） | 失配模式 | 示例方向 |
|---|---|---|
| `trade-jargon-glossary.md` | 行话/术语失配 | 文档：「应收账款保理」「授信敞口」；查询侧用例：「最多能欠多少钱」「卖出去的钱怎么提前拿回来」 |
| `equipment-codebook.txt` | 编号体系失配 | 文档：「HZ-2026-041 三号车间数控焊机」；查询侧用例：「三号车间那台自动焊接的设备」 |
| `expense-colloquial-faq.md` | 口语-术语同义失配 | 文档：「差旅费报销细则」；查询侧用例：「出差垫付的钱怎么算」 |

**可桥接性红线**：失配只允许「同义/近义/口语-术语/行话解释」层——multi-query 改写或 HyDE 生成有望桥接。禁止「需外部知识映射才能对上」的不可桥接失配（检索根本救不动，激活卡会白跑）。

## 2. MISMATCH 用例组（任务 2.x）

- `RagBenchmarkCase` schema 不动（id/category/question/expectedChunkIds/expectedAnswerPoints/useKnowledgeBase，`RagBenchmarkCase.java:20-26`）。
- 新用例 M-01..M-N（8~10 条），category=`MISMATCH`，question 用失配词面，expectedChunkIds 指向新 fixture 的 GOLD 块。
- **机械失配判据（¥0 可验）**：每例 question 与全部 gold chunk 文本的 2-gram 交集必须为空或近零（证明词汇失配）；语义同指由 fixture 设计与黄金答案点保证。验证方式：临时脚本或 `ChunkNgramRecallGateIT` 既有机制抽验，结果粘贴 §0。
- `RagBenchmarkSuite.java:31` SUITE_VERSION "2.0"→"3.0"；caseCount 断言（`RagQualityRegressionTest.java:173-176`）将先红（54≠62~64）→ 新用例登记后转绿，**红测先行留痕**。

## 3. 内存回归基线 regen 辨析（任务 3.x）

- `baseline-v1.json` 双角色：真跑锚点（禁覆盖）+ 内存回归基线 fixtureRegression 段（**唯一写入口** `RagQualityRegressionTest.java:64-71` regen，expand 卡先例已走过一次）。
- 本卡 regen 流程：新增用例后跑 regen → fixtureRegression 段更新 caseCount 与各例内存实测 → 断言 failureRate=0、recall ≥ 基线×0.95 语义对全组成立。
- `scripts/test-baseline.txt` 仅在新增 surefire 单测（DataPreparer 加载测试等）使计数 1088→1088+N 时，用 `check-test-baseline.sh --update` 从同一真实跑写入。

## 4. 真跑授权节点（任务 4.x，同 P-ah-D3 模式）

- 命令（白名单防 `ModelProviderImplDashScopeIT` 双烧；**严禁缺省 out= 毁 baseline-v1.json**）：

  ```
  mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.out=docs/rag-quality/baseline-v3.json"
  ```

- 纯默认矩阵（V1、不注入 rag.*、三开关 false——与 after-quality-loop 同口径），不传 `-Drag.benchmark.run`。
- 对照：**旧 54 例对照 `baseline-after-quality-loop.json`**（V1 口径最新锚，含三修复卡代码），任一旧例 recall 回退即 FAIL 停步；MISMATCH 组 per-case recall<1.0 用例 ≥6 条为「缺口成立」落证。
- 产物：`baseline-v3.json`（新）+ `baseline-v3-diff.md`（对照差异说明，quality-loop 先例）；产物名禁用既有文件名。
- Docker 在线前置（B 组 Testcontainers fail-closed）；跑毕清 `RAG_BENCHMARK_REAL`；一次为限，不利不重跑。

## 5. 激活卡接口（本卡预留，不实施）

- 本卡只造缺口与锚点；激活卡（后续立项）以 baseline-v3.json 为关态基线，开 `rag.query.multi-query.enabled` / `hyde.enabled` 真跑对照，MISMATCH 组 recall 攀升即 07/15 收益实证。
- 本卡合入后在 `openspec/project.md` 处置表 07/15/06 行的台账注记「激活度量前置已闭合」（仅注记，不改处置判词）。

## 6. 写集（≤15 tracked 文件）

3 fixtures + RagBenchmarkSuite.java + RagBenchmarkDataPreparer.java(+Test) + RagQualityRegressionTest.java + baseline-v1.json（regen 段）+ test-baseline.txt（如有新单测）+ 三件套 3 + HANDOFF.md + project.md 注记行。
