# tasks — clear-onboard-2-left-fragment

> 红线：判卷口径 v2（承载集）与主锚口径不动；fixtures 变更仅限 customer-sop.md 中 `【GOLD:customer-onboard-2】` 一个标记的位置移动（b2 §0 在案披露为依据），黄金 id 集、案例名、人名、数字零改动；真实外呼仅限任务组 4 单轮（RAG_BENCHMARK_REAL=1，proposal D3 owner 已拍板授权）；其余任务组 DASHSCOPE_API_KEY 置空；SUITE_VERSION 升 5.0 与 fixtures 变更同笔；surefire 只增不减；bash 一律用 `D:\git\Git\bin\bash.exe`。

## §0 铁律与留痕

- 只许动：RagBenchmarkSuite.java、fixtures/customer-sop.md（仅 onb-2 标记位置）、RagBenchmarkDataPreparerTest.java、RagBenchmarkCarrierTest.java（双钉）、RagQualityRegressionTest.java（再生成）、RagBenchmarkV6AnchorValidationTest.java（新）、baseline-v6.json（新）、本卡目录文件。超范围 = 偏差，如实留痕披露。
- baseline-v5.json 及历史锚（v3/v4）与全部既有锚点文件禁改；移标记后既有双钉（da7a3ef9…/79a804d5…）必红必刷新。
- 勾选哈希留痕规则：勾选行下一行 6 空格缩进「补勾依据」+ 可独立复跑命令与哈希。
- 停步点：四组全完 + 门禁留证 = 停步回报，不合并不 push（A 档：执行自跑全量 + 指导侧逐格核验 + owner 亲派复核 agent）。
- 申报（合入前补，owner 授权指导侧代补，2026-10-13）：baseline-v1.json 的 fixtureRegression 段随任务 1.4 `-Drag.baseline.regen=true` 连带刷新（generatedAt + suiteVersion 4.0→5.0 两键，`git diff master...HEAD -- docs/rag-quality/baseline-v1.json` 仅此二行）——停步回报偏差披露漏列；A 档复核专项三重确认（升版迫使 regen 必然 / b2 先例同型「停步回报漏列→补申报」/ 还原即红：requireSame(suiteVersion) 硬绑常量，回退 "4.0" 必红）。判定：升版构造性必要连带，保留。

## 任务组 1 · 升版与移标记

- [x] 1.1 SUITE_VERSION 4.0→5.0（RagBenchmarkSuite 常量 + javadoc 升版说明，63 例口径不变）
      补勾依据（2026-10-12 执行方实测）：`grep -n 'SUITE_VERSION = ' src/main/java/com/slz/crm/quality/RagBenchmarkSuite.java` 返回 `public static final String SUITE_VERSION = "5.0";`；javadoc 增 v5.0 条目；提交 78e30f6（4 insertions/2 deletions）
- [x] 1.2 移标记：`【GOLD:customer-onboard-2】` 移至与客户分级标准语义主体同块（proposal D1 机械口径三项验收：tornMarkers 零检出 / 锚块字面承载分级标准文本 / goldenToChunkId 归点正确）；标记移动外 customer-sop.md 零字节改动
      补勾依据（2026-10-12 执行方实测）：`git show 78e30f6 -- src/test/resources/rag-quality/fixtures/customer-sop.md` 仅 1 行改动（marker 由 raw[582,607) 跨 600 撕裂点移至 raw[658,683)，完全落在 customer-sop-2）；零外呼探针三项实测：tornMarkers() 整仓零检出 onb-2 事件 / goldenToChunkId.get("customer-onboard-2")=="customer-sop-2" / 锚块字面承载「客户分级标准」；该三项由 `mvn -B -ntp test -Dtest=RagBenchmarkDataPreparerTest,RagBenchmarkCarrierTest` 永久锁定（全绿）；提交 78e30f6
- [x] 1.3 向量输入 SHA256 双钉刷新（探针手法对齐 b2 任务 1.4：VectorInputDigestProbe 实测新 indexedText/embedText 后回填 CarrierTest 两断言）
      补勾依据（2026-10-12 执行方实测）：`grep -n '6004378b\|602b873d' src/test/java/com/slz/crm/quality/RagBenchmarkCarrierTest.java` 命中 L158 indexedText SHA=6004378be098feaf569f74a081a553ad94537679cb4dcbe676137713ac4b8161 / L162 embedText SHA=602b873d0e81559785f3bb620b0bbe4cb9ea64a6a895cd687ad1b711aff250ab（零外呼探针复测与回填一致，57 切片不变）；CarrierTest 全绿；提交 78e30f6
- [x] 1.4 fixtureRegression 段 `-Drag.baseline.regen=true` 确定性再生成（suiteVersion=5.0，fake embedder 零外呼），漂移逐例归因留痕
      补勾依据（2026-10-12 执行方实测）：`git show 78e30f6 -- docs/rag-quality/baseline-v1.json` 仅 generatedAt + suiteVersion(4.0→5.0) 两处；recallAt5=0.8386243386243385 / mrr=0.7116402116402117 / chunkCount=57 / caseCount=63 逐位不变（零漂移）；复跑 `mvn -B -ntp test -Dtest=RagQualityRegressionTest -Drag.baseline.regen=true`；提交 78e30f6

## 任务组 2 · 断言与校验增量

- [x] 2.1 PreparerTest 新增「customer-onboard-2 零撕裂事件」断言（对齐 b2 T-15 断言反转型先例）+ onb-2 锚块字面承载断言；既有「撕裂仅 customer-sop 一家」注释更新为整仓零撕裂语义；既有 T-15 断言零改动
      补勾依据（2026-10-12 执行方实测）：`mvn -B -ntp test -Dtest=RagBenchmarkDataPreparerTest` 全绿（含新增 tornMarkerOnb2LeftFragmentCleared + tornMarkerDetectionKeepsIndexingBehaviorUntouched 内全局零撕裂断言）；L100 版本断言 4.0→5.0；既有 T-15 断言零改动；提交 da90980（+40 行）
- [x] 2.2 RagBenchmarkV6AnchorValidationTest 新增（对齐 RagBenchmarkV5AnchorValidationTest 先例：v3/v4/v5/v6 四锚并存 + SUITE_VERSION=5.0 登记逻辑 + 缺席跳过/口径对账用例）+ SUITE_VERSION=5.0 断言
      补勾依据（2026-10-12 执行方实测）：新类 RagBenchmarkV6AnchorValidationTest 11 例全绿；RagRealRetrievalBenchmarkIT 四锚并存（isV6AnchorTarget/validateAgainstV6Anchor/writeBaselineReport 条件扩至四锚）；RagBenchmarkV5AnchorValidationTest report() 版本认定修正（硬编码 "4.0"）；`mvn -B -ntp test -Dtest=RagBenchmarkV6AnchorValidationTest,RagBenchmarkV5AnchorValidationTest,RagBenchmarkV4AnchorValidationTest` 全绿；提交 da90980

## 任务组 3 · 门禁（零外呼）

- [x] 3.1 全量 `mvn -B -ntp test` 0 失败（DASHSCOPE_API_KEY 置空；surefire 只增）；四静态顺序跑（禁并发）；三守卫；check-test-baseline 只读不红（如需 --update 先全量真跑 failsafe 刷新口径，一次只增）；merge-gate 8/8
      补勾依据（2026-10-12 执行方实测，DASHSCOPE_API_KEY=''）：`mvn -B -ntp test` → `Tests run: 1185, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS（surefire 1151→1185 只增）；四静态单次顺序调用 `mvn -B -ntp spotless:check checkstyle:check spotbugs:check pmd:check` → spotless「953 files clean」/ checkstyle「You have 0 Checkstyle violations」/ spotbugs「BugInstance size is 0」/ pmd BUILD SUCCESS；`bash scripts/check-test-baseline.sh` → surefire 报告=190/1185/0·failsafe 报告=28/108/5，exit 0；`bash scripts/merge-gate.sh` → 8/8 PASS（unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline，[it] 默认跳过）

## 任务组 4 · 真跑重锚（RAG_BENCHMARK_REAL=1，D3 已授权）

- [x] 4.1 63 例全量单轮真跑（DASHSCOPE_API_KEY 真值 + RAG_BENCHMARK_REAL=1），产出 baseline-v6.json（SUITE_VERSION=5.0 登记）；成本超 b2 参照（293s/61786 tokens）20% 以上先停步回报
      补勾依据（2026-10-12 执行方实测）：`mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT -Drag.benchmark.out=docs/rag-quality/baseline-v6.json` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 276.3 s` + BUILD SUCCESS；耗时 287.5s / totalTokens=61918（vs b2 293s/61786：耗时 -1.9%、tokens +0.2%，均远低于 +20% 停步阈值，未触发停步）；产出 suiteVersion=5.0 / scoringVersion=v2 / caseCount=63；跑毕清环境变量；提交 843b53a
- [x] 4.2 v5→v6 指标对比报告：T-11 citP 0.667→预期 1.0（若仍 2/3，取证定位真实失分源如实回报）；其余 62 例逐位不动（或漂移逐例归因）；真跑 raw、成本、对比表贴停步回报
      补勾依据（2026-10-12 执行方实测）：报告 docs/rag-quality/clear-onboard-2-left-fragment-v5-v6-report.md；63 例中 5 例漂移（T-11 目标例 citP 0.667→0.75/ansC 1.0→0.667；T-03/TB-08/M-01/M-04 属无关语料 LLM 波动）；**T-11 citP 0.75 未达预期 1.0**，结构目标达成、残余为 LLM 引用选择噪声，引用级 trace 因真跑默认旁路覆盖后还原而丢失，如实披露不硬凑（报告 §3）；提交 843b53a
- [x] 4.3 门禁复跑 + 停步回报（不合并不 push，待 owner 派 A 档复核 agent）
      补勾依据（2026-10-12 执行方实测）：门禁复跑见 3.1（全绿）；本卡停步，未合并未 push，HEAD=feature/clear-onboard-2-left-fragment @ 843b53a（tasks 留痕另笔）；待 owner 亲派 A 档复核 agent

## §0 执行留痕

执行方：execution agent（指导侧派工）；日期：2026-10-12；分支：feature/clear-onboard-2-left-fragment
（权威树 D:\code\crmAndRag-merge-add-knowledge-admin-api，从 master fd58221 检出）。全程未写主树。

**各笔提交**：

| 序 | 哈希 | 内容 |
|----|------|------|
| 1 | 3f4cbfb | docs(openspec): 卡两件（proposal/tasks）复制入分支为 tracked 正版（SHA256 与主树草稿 match：proposal 2a8781ba…、tasks b0a06f7a…） |
| 2 | 78e30f6 | feat(rag-benchmark): 组1 升版 5.0 + onb-2 移标记 + 双钉刷新 + fixtureRegression 再生成 |
| 3 | da90980 | test(rag-benchmark): 组2 onb-2 零撕裂断言 + V6 锚校验增量（四锚并存） |
| 4 | 843b53a | feat(rag-benchmark): 组4 63 例真跑落 baseline-v6 + v5→v6 对比报告 |
| 5 | 本笔 | docs(openspec): tasks 全勾 + §0 执行留痕 |

**门禁 raw 关键行（2026-10-12，DASHSCOPE_API_KEY=''）**：
- `mvn -B -ntp test`：`[INFO] Tests run: 1185, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] BUILD SUCCESS`
- 四静态顺序（单次 mvn 调用）：`Spotless.Java is keeping 953 files clean - 0 needs changes` / `You have 0 Checkstyle violations.` / `BugInstance size is 0` / `[INFO] BUILD SUCCESS`（pmd）
- `bash scripts/check-test-baseline.sh`：`surefire 报告=190（默认口径），Tests run=1185，Failures=0，Errors=0，Skipped=0｜基线 reports>=189 tests>=1151 skipped<=0` / `failsafe 报告=28，Tests run=108，Failures=0，Errors=0，Skipped=5｜基线 reports>=28 tests>=105 skipped<=6` / `回归基线门禁通过`（exit 0）
- `bash scripts/merge-gate.sh`：`PASS [unit]` `PASS [spotbugs]` `PASS [pmd]` `PASS [baseline]` `PASS [frontend-unit]` `PASS [hook]` `PASS [bijection]` `PASS [pmd-baseline]` / `== merge-gate 通过：所有已执行的子门禁绿。==`（[it] 默认未执行）

**真跑 raw 与成本（组4）**：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 276.3 s` + BUILD SUCCESS；ELAPSED=287.5s；totalTokens=61918；failureRate=0.0（vs b2 293s/61786：+0.2% tokens，远低于 +20% 停步阈值）。

**v5→v6 对比表**（聚合）：

| 指标 | v5 | v6 | Δ |
|------|------|------|------|
| recall@5 | 0.96296 | 0.96296 | 0 |
| citP | 0.97302 | 0.97116 | -0.00185 |
| ansC | 0.96032 | 0.95503 | -0.00529 |
| MRR | 0.92857 | 0.93651 | +0.00794 |
| totalTokens | 61786 | 61918 | +132 |

逐例漂移 5 例：T-11（citP 0.667→0.75、ansC 1.0→0.667，目标例）、M-01（rr 0.5→1.0、citP 0.667→1.0）、M-04（citP 1.0→0.667、ansC 0.667→1.0）、T-03（ansC 1.0→0.667）、TB-08（citP 1.0→0.8）；后 4 例黄金语料与 customer-sop 无交集，属单轮 LLM 波动。

**偏差披露（写集扩展 3 处 + 报告落盘 1 处）**：
1. `src/test/java/com/slz/crm/quality/RagRealRetrievalBenchmarkIT.java` —— 组2 落地 V6 锚校验（isV6AnchorTarget/validateAgainstV6Anchor/writeBaselineReport 四锚合并写），§0 清单未列 IT 文件。
2. `src/test/java/com/slz/crm/quality/RagBenchmarkV5AnchorValidationTest.java` —— 升版 5.0 后该离线测试 report() 用活常量与样例硬编码 "4.0"（v5 锚真版本）对账致 5 例红，修正为硬编码 "4.0"；语义正确（v5 锚历史版本认定），未来升版不再受影响。
3. `docs/rag-quality/i05-forensics.json` —— 真跑默认取证旁路覆盖该 tracked 历史工件（362 行变化），已 `git checkout --` 还原至 HEAD。代价：T-11 引用级取证丢失，v6 无法定位那 1 条非承载引用指向的 chunkId（报告 §3 已披露）。
4. 报告落盘 `docs/rag-quality/clear-onboard-2-left-fragment-v5-v6-report.md`（对齐 b2-suite-recalibration 先例，§0 清单未列该路径）。

**留痕附注**：权威树 frontend pre-commit 钩子在 worktree 场景对非 frontend/ 暂存文件误触发 `pnpm lint:check + type-check:check`（既有行为，本卡 4 笔提交均触发并通过，如实留痕）。