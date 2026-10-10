# 任务：expand-rag-benchmark-mismatch（P-ai）

> 权威树：D:\code\crmAndRag-merge-add-knowledge-admin-api，master@c155226，分支 `feature/expand-rag-benchmark-mismatch`。
> 三件套主树草案（d:\code\crmAndRag\openspec\changes\expand-rag-benchmark-mismatch\）由执行者复制入权威树随首笔提交。
> 硬约束：任务组 1-3、5 全程 ¥0；任务组 4 授权节点（未授权标待补跑，不阻塞合入）。禁改 12 个既有 fixture 正文与 GOLD、禁改 54 例既有 case、禁覆盖任何既有真跑锚点 JSON、三开关默认 false 不动、检索主代码零改动、零 DDL、零新依赖、禁 frontend/。中文 Javadoc/注释标注「expand-rag-benchmark-mismatch 任务 x.x」。

## 任务组 1 · 失配语料扩容（¥0）

- [ ] 1.1 设计并新增 3 个失配 fixture（trade-jargon-glossary.md / equipment-codebook.txt / expense-colloquial-faq.md，或同构命名），三类失配模式各一，人名/数字/事件全新造，GOLD 占位登记入 `RagBenchmarkDataPreparer` FIXTURES 清单
- [ ] 1.2 新 fixture 加载红测先行：DataPreparer 加载测试先在基线跑红（fixture 未登记），登记后转绿，红绿输出粘贴 §0

## 任务组 2 · MISMATCH 用例组（¥0）

- [ ] 2.1 新增 M-01..M-N（8~10 条）MISMATCH 用例：question 用失配词面、expectedChunkIds 指向新 GOLD、expectedChunkIds 答案点宽写（E 组先例）
- [ ] 2.2 机械失配判据验证：每例 question vs gold chunk 2-gram 交集空/近零，验证输出粘贴 §0
- [ ] 2.3 SUITE_VERSION "2.0"→"3.0"；caseCount 断言红测先行（54≠新值）→ 用例登记转绿，红绿输出粘贴 §0

## 任务组 3 · ¥0 验证链（¥0）

- [ ] 3.1 `RagQualityRegressionTest` regen 更新 `baseline-v1.json` fixtureRegression 段（唯一写入口 :64-71），全组断言绿（failureRate=0、recall≥基线×0.95）
- [ ] 3.2 `DASHSCOPE_API_KEY=""` 后 `mvn -B -ntp test` 全绿，surefire 1088→1088+N 只增不减；`check-test-baseline.sh --update` 从同一真实跑写入（如有新单测）
- [ ] 3.3 四静态 0 违规（checkstyle/spotbugs 双射/spotless/pmd 台账）；三守卫 CLEAN；`bash scripts/merge-gate.sh` 全 PASS
- [ ] 3.4 红线自检：git diff 确认未触碰既有 fixture 正文、既有 case 定义、任何既有真跑锚点 JSON、检索主代码、frontend/

## 任务组 4 · 真跑授权节点（成本闸门，同 P-ah-D3 模式）

- [x] 4.1 已执行（2026-10-10 owner 授权：Docker 在线实测、白名单单轮默认矩阵真跑 290.7s、MVN_EXIT=0 未触额、跑毕清 RAG_BENCHMARK_REAL 实测确认；命令与 raw 见 §0 任务组 4 留痕）
- [ ] 4.2 验收：SUITE_VERSION=3.0、failureRate=0、旧 54 例对照 `baseline-after-quality-loop.json` 无回退、MISMATCH 组 recall<1.0 用例 ≥6（缺口落证）；产物 baseline-v3.json + baseline-v3-diff.md 差异说明
  - 未勾依据（2026-10-10 真跑实证）：前半达成（3.0 / failureRate=0 / 旧 54 例零回退），但「MISMATCH ≥6 例 recall<1.0」不成立——9/9 关态 recall=1.0，缺口面为零，按判据 FAIL 结案（证伪，非悬空格）；baseline-v3-diff.md 不再产出，结论并入 §0 与 HANDOFF。
- [ ] 4.3 未获授权则 §0 标「真跑待授权」合入收口，不阻塞（条件未触发：2026-10-10 已获授权并执行，兜底路径未启用；非悬空格）

## 任务组 5 · 收口（¥0）

- [ ] 5.1 `openspec/project.md` 处置表 07/15/06 行台账注记「激活度量前置已闭合（expand-rag-benchmark-mismatch）」；HANDOFF.md 更新（v3 锚点状态、激活卡接口）
- [ ] 5.2 分笔中文提交（组1 / 组2 / 组3 / 组5 对齐），`git merge --no-ff` 合入 master 保留分支；push 需 owner 显式授权
- [ ] 5.3 停步回报：粘贴 git log --oneline --graph -8 / git status --short / 各笔 show --stat / 门禁 raw 关键行 / 红绿测留痕；任何未实际执行的命令不得出现在回报中

## §0 执行记录

- 决策点拍板（2026-10-10 owner「按建议」）：D1 新 category `MISMATCH`；D2 真跑节点留本卡尾段（先 ¥0 合入后授权跑，expand 卡 f2bcfbd→4c3431e 先例同构）；D3 失配缺口以授权真跑 per-case recall<1.0 落证，内存基准只验功能。

### 执行留痕（2026-10-10）

**组1 红绿灯**（`DASHSCOPE_API_KEY=""`，`mvn -B -ntp test -Dtest=RagBenchmarkDataPreparerTest`）：
- 红（fixture 未登记）：`Tests run: 8, Failures: 1` `mismatchFixturesRegisterAndAlignGold` `失配语料 GOLD jargon-factoring 未对齐（fixture 未登记或分块丢失？）`
- 绿（登记后 + GOLD_MARKER_FRAGMENT 拓边）：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

**组2 红绿灯**（套件升 3.0 / caseCount 63）：
- `RagQualityRegressionTest.recallAt5AndMrrStayAboveNinetyFivePercentOfBaseline` 红：`基线度量口径与当前不一致：fixtureRegression.suiteVersion 基线=2.0，当前=3.0。`
- regen 后绿：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`

**组2 机械 2-gram 判据**（临时脚本，跑完即删；MISMATCH question bigrams vs gold chunk bigrams 交集）：

| case | q bigrams | gold bigrams | 交集 | gold->chunk |
|---|---|---|---|---|
| M-01 | 29 | 263 | 1 | jargon-factoring->trade-jargon-0 |
| M-02 | 23 | 261 | 1 | jargon-credit->trade-jargon-1 |
| M-03 | 23 | 263 | 3 | jargon-discount->trade-jargon-2 |
| M-04 | 27 | 278 | 3 | eq-041->equipment-0 |
| M-05 | 22 | 285 | 1 | eq-107->equipment-1 |
| M-06 | 28 | 284 | 2 | eq-172->equipment-2 |
| M-07 | 21 | 255 | 2 | expense-travel->expense-0 |
| M-08 | 24 | 242 | 0 | expense-advance->expense-1 |
| M-09 | 21 | 258 | 3 | expense-claim->expense-2 |

交集 0–3（近零/空），每例 gold 均落独立 chunk（映射扫描确认 9/9 分离），失配成立且黄金切片可定位。

**组2/组3 全套绿色行**：`mvn -B -ntp test` → `Tests run: 1089, Failures: 0, Errors: 0, Skipped: 0`；`mvn -B -ntp clean verify` → surefire 1089 / failsafe 98（6 skip）。

**组3 基线**：`check-test-baseline.sh --update`（从 clean verify 同一真实跑）→ `surefire.tests=1089`（1088→1089 只增）、`failsafe.tests=98 skipped=6`（不变）；回归基线门禁 check 通过。

**组3 静态与合并门禁**：checkstyle/spotbugs/pmd 三 maven 门禁 BUILD SUCCESS；`spotbugs-exclude-staleness-check.sh` RESULT=BIJECTION_OK（12 配对）；`pmd-baseline-check.sh` RESULT=PMD_BASELINE_OK（0==0==0）；`bash scripts/merge-gate.sh` 八子门禁全 PASS（unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline）。

**红线自检**：`git diff` 确认未触碰 12 个既有 fixture 正文/GOLD、54 例既有 case、任何既有真跑锚点 JSON（baseline-v1.json 仅 regen 写入口更新 fixtureRegression 段，metrics/cases 逐字保留）；三开关默认 false 未动；检索主代码零改动；零 DDL/零新依赖/禁 frontend（pre-commit 仅跑前端静态检查，未改 frontend 文件）；写集 15 个 tracked 文件对账 design.md §6。

**任务组 4（真跑授权节点，未执行）**：待 owner 授权 `RAG_BENCHMARK_REAL=1` 后按 design.md §4 白名单命令落 `docs/rag-quality/baseline-v3.json`（默认矩阵、不注入 rag.*、三开关 false），并以 `baseline-after-quality-loop.json` 对照旧 54 例无回退、MISMATCH 组 recall<1.0 ≥6 落证。未授权前本卡停步，不阻塞其后授权流程。

**任务组 4 真跑实证（2026-10-10，owner 授权 RAG_BENCHMARK_REAL=1，终判 FAIL=证伪结案）**：

- 命令原样：`mvn -B -ntp test-compile failsafe:integration-test '-Dit.test=RagRealRetrievalBenchmarkIT' '-Drag.benchmark.out=docs/rag-quality/baseline-v3.json'`（默认矩阵 V1、不注入 rag.*、三开关 false）→ failsafe `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 290.7 s`，MVN_EXIT=0，未触额，跑毕开关实测清除。
- 结果：`docs/rag-quality/baseline-v3.json` 落盘——suiteVersion 3.0 / caseCount 63 / failureRate 0 / meanRecallAtK 0.9550 / meanCitationPrecision 0.8272 / meanAnswerConsistency 0.9550 / totalTokens 61734；旧 54 例对照 `baseline-after-quality-loop.json` 零回退（敏感例逐例一致：T-12=0.667 / T-14=0.5 / TB-01=1.0 / TB-10=1.0 / E-02=0 / E-04=0）。
- **证伪结论**：MISMATCH M-01..M-09 关态 recallAtK 9/9 = 1.0，验收 #3「≥6 例 recall<1.0」不成立（0/9）——默认检索稠密语义路线已桥接近义/行话/口语级词面失配（嵌入空间本就含改写可造的语义桥），multi-query/HyDE 改写收益面在 synonym 级失配上**结构性不存在**；与 `ladder-report.md`「作用面为空」结论互证。设计假设「2-gram 词面近零 ⇒ 漏召缺口」被真跑推翻。
- 处置联动（owner 2026-10-10 拍板「都授权」）：project.md 处置表 07/15/06 改判「不做（真跑证伪收益面）」，三开关留库默认关；激活卡不立项；若未来生产出现歧义/干扰项型查询痛点（multi-query 真正作用面为改写消歧），另立新卡再议（挂低优先级账）。baseline-v3.json 留档为 63 例 V1 口径现行锚。
