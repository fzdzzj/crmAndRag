# tasks — trace-citation-redundancy

## 任务组 1 · 本地准备（¥0）

- [x] 1.1 权威树自证 `feature/trace-citation-redundancy`（自 master @ cdcdb87 检出）；从 `RagBenchmarkSuite` 读取并登记 20 例（T-01,02,03,04,05,10,11,13,14,15 / TB-01,08,09,10,11 / I-03 / M-01,02,03,04）的 question 与 goldIds 清单，贴 §0
- [x] 1.2 起草真跑命令（design.md 形态，`-Drag.benchmark.only=` 20 例逗号列表），核对 `BenchmarkOnlyFilterTest` 白名单解析断言在案；既有锚点 SHA256 跑前留底

## 任务组 2 · 真跑授权节点（成本闸门）

- [ ] 2.1 停步请 owner 授权 `RAG_BENCHMARK_REAL=1`（说明：20 例单轮约 92s / 20k tokens 量级、未触额预期、跑毕即清）；授权后执行单轮真跑，failsafe 1/0/0/0，实测清开关；锚点 SHA256 跑后核对不变

## 任务组 3 · 判读与报告（¥0）

- [x] 3.1 逐例整理四元组表（引用块集 / 其中黄金数 / 非黄金块 excerpt 原文 / 支撑度判定 + 答案要点覆盖），**excerpt 必须原样粘贴不得转述**；三分类归因（A 噪声误引 / B 口径特性 / C recall 连带）逐例落表
- [x] 3.2 产出 `docs/rag-quality/citation-forensics-v4.md`：逐例明细 + 分类统计 + 三分支定向建议（A → 生成侧引用精化卡候选；B → 记录/受控校准；C → 归并 T-14 拆路残留）；与 v2 对照 T-15 citP 0.333→0 波动形态一并取证

## 任务组 4 · 收口

- [ ] 4.1 单笔中文提交（卡三件套 + citation-forensics-v4.{json,md} + report JSON，约 5 文件）；git commit 单独执行（Git Bash 用 -F 临时文件，PS 用 2>&1 | Out-String），不合并不 push
- [ ] 4.2 停步回报：原样粘贴 git log --oneline --graph -5 / git status --short / git show --stat HEAD / failsafe 真跑 raw 行（Tests run/elapsed/清开关实测）/ 锚点 SHA256 前后对照 / 1.1 登记清单；任何未实际执行的命令不得出现在回报中

## §0 执行记录

（执行方按任务组留痕：时间戳、命令原样、输出关键行）

### 任务组 1（2026-10-11 00:35 +0800，权威树 D:\code\crmAndRag-merge-add-knowledge-admin-api）

**1.1 权威树自证**

```
$ git rev-parse HEAD            → cdcdb87b7390a82d87709133dc17be999792a49c  (master，与卡面一致)
$ git rev-parse --abbrev-ref HEAD → master（检出前）
$ git status --short            → ?? work/   （净，仅 work/）
$ git checkout -b feature/trace-citation-redundancy
  → Switched to a new branch 'feature/trace-citation-redundancy'
$ git rev-parse --abbrev-ref HEAD → feature/trace-citation-redundancy
$ git status --short            → ?? work/   （检出后仍净）
```

**1.1 20 例登记清单**（自 `src/main/java/com/slz/crm/quality/RagBenchmarkSuite.java` SUITE_VERSION=3.0 standard() 读取；goldIds 为占位 id，真跑时由 RagBenchmarkDataPreparer 对齐 fixtures `【GOLD:占位id】` 替换为实际入库 chunkId）：

| id | 类别 | question | goldIds（占位） |
|----|------|----------|------------------|
| T-01 | TEXT | 销售合同审批流程是怎样的 | sales-flow-1, sales-flow-2 |
| T-02 | TEXT | 回款计划如何制定 | payment-plan-1 |
| T-03 | TEXT | 合同归档有什么要求 | sales-flow-3 |
| T-04 | TEXT | 合同生效后金额要改怎么办 | sales-flow-4 |
| T-05 | TEXT | 回款逾期了会怎么处理 | payment-plan-2 |
| T-10 | TEXT | 合同从审批到归档的完整链条是什么 | sales-flow-1, sales-flow-2, sales-flow-3 |
| T-11 | TEXT | 客户从建档、评级到回款策略的衔接流程是什么 | customer-onboard-1, customer-onboard-2, payment-plan-1 |
| T-13 | TEXT | 数量折扣与客户类型折扣的叠加规则是什么 | price-tiers, price-discount-1 |
| T-14 | TEXT | 客户主体变更后重新签合同要满足什么条件 | customer-onboard-4, sales-flow-4 |
| T-15 | TEXT | 战略客户合同终审通过后的报备要求是什么 | customer-onboard-3, sales-flow-2 |
| TB-01 | TABLE | 第三季度各区域销售额 | sales-q3-华东, sales-q3-华北 |
| TB-08 | TABLE | Q3 销售额在 800 万到 1000 万之间的区域有哪些 | sales-q3-华北, sales-q3-华南 |
| TB-09 | TABLE | 折扣率超过 15% 的折扣申请要走什么审批 | price-discount-1, price-discount-2 |
| TB-10 | TABLE | 计划工时超过 2 人时的维保项有哪些 | maint-xr500-q, maint-kq9000-m |
| TB-11 | TABLE | Q3 哪个区域销售额最高 | sales-q3-华东 |
| I-03 | IMAGE | 客户建档流程图的图注里，建档完成后会自动触发什么 | customer-flow-diagram |
| M-01 | MISMATCH | 货都卖出去一个多月了钱还没到账，有没有什么办法先把钱挪回来用 | jargon-factoring |
| M-02 | MISMATCH | 银行那边批给我们的额度，到底还有多少是能直接动的 | jargon-credit |
| M-03 | MISMATCH | 手里压了一张还没到期的票据，想尽早换成现钱该找谁 | jargon-discount |
| M-04 | MISMATCH | 三号车间那台专门把铁板接到一起的机器，隔多久要做一回保养 | eq-041 |

**1.2 真跑命令草案**（design.md 形态，Git Bash；白名单 20 例逗号列表；out 与 trace.out 独立命名，严禁省略 out）：

```bash
RAG_BENCHMARK_REAL=1 mvn -B -ntp test-compile failsafe:integration-test \
  -Dit.test=RagRealRetrievalBenchmarkIT \
  '-Drag.benchmark.only=T-01,T-02,T-03,T-04,T-05,T-10,T-11,T-13,T-14,T-15,TB-01,TB-08,TB-09,TB-10,TB-11,I-03,M-01,M-02,M-03,M-04' \
  -Drag.benchmark.out=docs/rag-quality/citation-forensics-v4-report.json \
  -Drag.benchmark.trace.out=docs/rag-quality/citation-forensics-v4.json
```

- 白名单解析断言在案：`src/test/java/com/slz/crm/quality/BenchmarkOnlyFilterTest.java`（only 留 1 例 / 空值不滤 / 未知 id 显式失败 / 旁路 JSON 字段 rawAnswer/alignedAnswer/citationsBefore|After/excerpts 断言）。
- 门控源码核对：`RagRealRetrievalBenchmarkIT` 要求 `RAG_BENCHMARK_REAL=1`（env）+ `DASHSCOPE_API_KEY`（env 或仓库根 .env）；`rag.benchmark.run` 缺省 v1，与 baseline-v3 锚 `runProfile=V1` 同口径。
- `-Dit.test=` 白名单防 `ModelProviderImplDashScopeIT` 同轮双烧；`out` 显式指向 citation-forensics-v4-report.json 防覆盖锚点。
- 风险提示实测项：跑毕 `echo ${RAG_BENCHMARK_REAL:-unset}` 实测清开关（Git Bash 前缀形态仅作用于单条命令）。

**1.2 既有锚点 SHA256 跑前留底**（docs/rag-quality/ 全部 17 文件，2026-10-11 00:35；快照副本 work/_tcite-hashes-before.txt，gitignore 收敛不入库）：

```
ee909f7e08de0e818b239d6cdb450e4ac710c7524cb756242da01918c482a3d4  after-selfrag.json
68bd4135212b5fc04b3e53f7da14cc9cbf8fc2cd3a95cb1e8ec962b124304f4e  baseline-after-chunking.json
b79185adcb8b463274859c5f1b67c7d7fad99a0efeeb4b0f60ebe83aa69d120b  baseline-after-context.json
cead3ff06bd27bdf5695abe50d4ed7c3bd522d39b9fb8ae599bec3f2513ddccf  baseline-after-hybrid.json
b55197c4c4fa281cbfc746549ee434777541771427b2b58aa06c8d612dc8c116  baseline-after-quality-loop.json
cfae5994125a80ae4ff918f4c0cbcbfa3522519b8917f43d25489b0ef45fb3e4  baseline-after-quality-loop.md
299d29a75f51c4b0ebb96b46f14686b09838dcf6f1f4ceac444088a60434a1a1  baseline-v1.json
9921093f49cc6465219643760d7e89025c09adfbe88df331518f121c8c458621  baseline-v2-anchor.md
d33778fb0e4edf4166662df1835fcc573b6d3a2bb185f64fbe01d04c6d0c911b  baseline-v2.json
8ae856a7a44960185295be270d35a9832e37aedf448f1a953a469add7d6e4c8c  baseline-v3.json
b0ea9dd71075f1f4d596ff3d70fd216ea745ef165293eeeaef66e2baf5619414  i05-after-caption-chunk-report.json
f47431671505e36294d3c2aba8d9e88e667c45b4c89a643228e6302c8478a3fa  i05-after-caption-chunk.json
dc19fd0b582ceb01cea0eb07e985105f39af50da8703cca5e82f52a24fdd24f2  i05-after-caption-chunk.md
43c6899bff6061f68e53dbbffe1105237a0b9dada3d1c1fe2597910790be64f5  i05-forensics-report.json
b6b9403209831b3a28c893b7fdc33ef2eb81b7b280590cc5a7f193a1bdbe0746  i05-forensics.json
f6be9a9627fe71ce1abc1f7f51ffd4be174097836b5cf053aa3b95c43433acfb  i05-forensics.md
fc4d189b2106d5db89dbfb043c3b09d45db9bc9551aeae74efb726a78c007a62  ladder-report.md
```

### 任务组 2（2026-10-11 00:42 +0800 首次停步；08:27 +0800 获授权）

**2.1 owner 已授权 RAG_BENCHMARK_REAL=1（2026-10-11 08:27:49 +0800）**

- 授权询问后 owner 回复"授权"。预检：分支 `feature/trace-citation-redundancy` 在位；仓库根 `.env` 含 `DASHSCOPE_API_KEY`（仅核名，值未打印）；Docker `29.6.2` 在线。
- 执行命令：见任务组 1 §0 1.2 草案原样（白名单 20 例 / out=citation-forensics-v4-report.json / trace.out=citation-forensics-v4.json / it.test 白名单防双烧），输出留痕 work/_tcite-realsuite.log。
- （跑毕留痕：failsafe raw 行、清开关实测、锚点 SHA256 复核——见下追加）

**2.1 真跑结果（EXIT=0，BUILD SUCCESS，2026-10-11 08:31:49 完成，总耗时 03:22 min）**

```
[rag-benchmark] run=V1 matrix={}
[rag-benchmark] 语料入库完成：chunks=57 goldens=53
[rag-benchmark] suite size after only-filter=20
[rag-benchmark] 基线已落盘（合并写，保留非本 run 的顶层段）: ...\docs\rag-quality\citation-forensics-v4-report.json
[rag-benchmark] metrics=Metrics[caseCount=20, meanRecallAtK=0.975, meanPrecisionAtK=0.3, mrr=0.925, hitRate=1.0, meanCitationPrecision=0.4708333333333333, meanAnswerConsistency=0.9666666666666668, meanTtftMs=442.15, meanTotalLatencyMs=6539.9, totalTokens=22438, failureRate=0.0]
[rag-benchmark] 取证旁路已落盘: ...\docs\rag-quality\citation-forensics-v4.json
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 144.4 s -- in com.slz.crm.quality.RagRealRetrievalBenchmarkIT
```

- 实测 tokens=22438（预估 20k 量级吻合）；本轮 20 例 citP 均值 0.4708（baseline-v3 同 20 例约 0.456，LLM 非确定性正常浮动）。
- **清开关实测**：`echo ${RAG_BENCHMARK_REAL:-unset}` → `RAG_BENCHMARK_REAL=unset`（Git Bash 前缀形态，无残留）。
- **锚点复核**：跑后 sha256sum 全目录与跑前留底归一 diff → `ANCHORS-UNCHANGED-17-FILES`（17 个既有产物含 baseline-v1/2/3、after-*、i05-* 全部不变；关键锚 baseline-v3.json = `8ae856a7…6e4c8c` 跑前跑后一致）。

### 任务组 3（2026-10-11 上午，判读与报告）

**3.1/3.2 判读结论**（明细与 excerpt 原文见 `docs/rag-quality/citation-forensics-v4.md` §3-§6；证据源 = citation-forensics-v4.json trace 旁路 + fixture 原文 + baseline-v2/v3 报告）

- 逐例四元组 20 例全落表，非黄金引用块 excerpt 全部原样粘贴（400 字窗口，注明截断处）。
- 三分类统计：**A 噪声误引 0 例；B 口径特性 18 例**（B1 切分边界内容重复/跨块段落 9、B2 黄金标注口径缺口 8、B3 黄金标记跨块撕裂 1）、**C recall 连带 1 例**（T-14，customer-sop-3 v2/v3/v4 三轮稳定未召回）；I-03 本轮 citP 复原 1.0（对齐器丢弃合法邻块引用，v2/v3 短板形态=语料双载 B）。
- 关键机制发现：M1 切分边界重叠为最大单一机制；M3 = T-15 fixture 标记 `【GOLD:customer-onboard-3】`（customer-sop.md L9 段首）被切分边界撕裂（customer-sop-3 excerpt 以残片 `customer-onboard-3】` 开头），数据准备器归点 customer-sop-2 而报备句主体在 customer-sop-3 → 黄金锚错位，v2 0.333→v3 0→v4 0 波动=引用集漂移×恒定错位；M4 = 对齐器重映射/丢弃双向噪声 4 例（TB-10 黄金引用错移丢分、T-15 丢黄金引用、M-04 重映射不支撑块错向抬分、I-03 丢弃后满分）。
- 定向建议：A 不立项（如开生成侧卡，抓手改为对齐器重映射正确性）；B 三分路（B1 判卷口径/切分契约受控解冻另卡、B2 升版受控校准只登记不改、B3 Preparer 标记加固候选小卡）；C 归并 T-14 拆路残留。
- 回归验证：`mvn -B -ntp test` 全量 surefire `Tests run: 1089, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，EXIT=0（零代码改动无回归，与验收行一致）。

### 任务组 4（收口）

- [x] 4.1 单笔中文提交（卡三件套 + citation-forensics-v4.{json,md} + report JSON，约 5 文件）；git commit 单独执行（Git Bash 用 -F 临时文件，PS 用 2>&1 | Out-String），不合并不 push
- [ ] 4.2 停步回报：原样粘贴 git log --oneline --graph -5 / git status --short / git show --stat HEAD / failsafe 真跑 raw 行（Tests run/elapsed/清开关实测）/ 锚点 SHA256 前后对照 / 1.1 登记清单；任何未实际执行的命令不得出现在回报中

**4.1 执行留痕**

- 三件套落位裁定：主树草稿 `work/tmp-cite-forensics/` 被 `.gitignore` `work/*` 规则忽略（仅 task-card/handoff/README 放行），按 AGENTS.md"openspec/changes=逐卡变更唯一新卡轨"与本仓 openspec 卡先例（i05 卡同构），三件套以 `openspec/changes/trace-citation-redundancy/{proposal,design,tasks}.md` 入库，主树草稿保持原样。
- 暂存集：`openspec/changes/trace-citation-redundancy/`（3 文件）+ `docs/rag-quality/citation-forensics-v4.json` + `citation-forensics-v4.md` + `citation-forensics-v4-report.json`，共 6 文件；提交前 `git status --short` 核对暂存恰为 6。
- 提交：git commit -F 临时文件（tr -d '\r' 归一 LF）单独执行；commit hash 见 4.2 停步回报与交接记录。

## 边界与禁令

- 零代码改动：src/main、src/test 一律不碰；SUITE_VERSION 不动；既有 fixture/case/锚点/i05-\* 取证件逐字保留
- 产物仅限：本卡三件套 + docs/rag-quality/citation-forensics-v4 三件（json/report.json/md）
- 真跑未获授权则停在 2.1 待命，不擅自执行
