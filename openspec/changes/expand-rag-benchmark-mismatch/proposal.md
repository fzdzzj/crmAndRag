# 提案：expand-rag-benchmark-mismatch —— 扩基准造词汇失配用例（P-ai）

## Why（为什么现在做）

- `docs/rag-quality/ladder-report.md:33-35` 定谳：现有 fixtures「无『查询-文档词汇失配』漏召缺口，多查询/HyDE 的『改写救漏召』作用面为空……触发条件不成立」；TEXT 5（T-01~T-05）/ LEXICAL 6（L-01~L-06）v1 口径 recall@k=MRR=1.0 已饱和，54 例 v2 实测 48/54 recall=1.0。
- 处置表 07/15/06 对应的 multi-query / hyde / derived-questions 三开关**代码已在库、默认 false**（`rag.query.multi-query.enabled` / `rag.query.hyde.enabled` / `rag.query.derived-questions.enabled`，`DynamicConfigKeyRegistry.java:609-684`），激活收益因基准饱和无法度量——资产闲置。
- 本卡为**激活度量铺路**：造失配语料与用例、升级套件 v3.0、落新真跑锚点 `baseline-v3.json`，使后续激活卡能以「关/开开关双轮对照」实证收益。这是 17 方案侧剩余活口中唯一可立即推进的钥匙卡（11/14 外部触发未满足）。

## What Changes（改什么）

1. **新失配语料**：`src/test/resources/rag-quality/fixtures/` 新增 3 个 fixture（行话对照 / 编号体系 / 口语-术语同义三类失配模式），人名/数字/事件全部新造，与既有 12 个 fixture 零词汇串扰。
2. **新用例组 MISMATCH**：`RagBenchmarkSuite` 新增 category=MISMATCH 用例 8~10 条（M-01..M-N），caseCount 54 → 62~64；SUITE_VERSION "2.0" → "3.0"。
3. **¥0 验证链**：`RagQualityRegressionTest` regen 更新内存基线段（`baseline-v1.json` fixtureRegression 段，唯一写入口在 :64-71）；`RagBenchmarkDataPreparer` 登记测试扩容；机械失配判据验证（MISMATCH 每例查询 vs gold chunk 2-gram 交集≈空）。
4. **真跑授权节点（任务组 4，同 P-ah-D3 模式）**：owner 授权 `RAG_BENCHMARK_REAL=1` 后纯默认矩阵（V1、不注入 rag.*、三开关保持 false）单轮真跑，落 `docs/rag-quality/baseline-v3.json` + 差异说明 .md；**未授权则标明待补跑，不阻塞合入**（expand 卡 spec 先例）。

## 不改什么（冻结面）

- 禁改 12 个既有 fixture 正文与 GOLD 占位、禁改 54 例既有 case 定义与 gold ID；
- 禁覆盖任何既有 `baseline-*.json` / `after-*.json` 真跑锚点（`baseline-v1.json` 仅允许 regen 写入口更新 fixtureRegression 段——内存回归基线非真跑锚点，expand 卡先例）；
- 三开关默认 false 不动、检索管线主代码零改动（本卡不碰 `src/main/java` 检索链路，仅测试侧扩容）；
- 零 DDL、零新依赖、禁 frontend/。

## 决策点（请 owner 拍板）

- **D1 新 category MISMATCH**（建议）vs 并入 LEXICAL：新 category 可在激活卡中单独对照 MISMATCH 组收益，不稀释 LEXICAL 组语义。
- **D2 真跑节点留在本卡尾段**（建议，与 expand 卡 f2bcfbd→4c3431e 先例同构：先 ¥0 合入、后授权跑落锚）vs 并入后续激活卡双轮：留本卡可让 v3 锚点尽早在库，任何后续卡可引用。
- **D3 失配缺口验收口径**（建议）：内存基准只验功能（failureRate=0、regen 后断言绿），「关态存在漏召缺口」以授权真跑 baseline-v3.json 的 MISMATCH 组 per-case recall<1.0 落证——不赌内存链路与真跑链路的语义一致性。

## 验收标准

- **¥0 段（合入门禁）**：mvn -B -ntp test 全绿且 surefire 计数 1088→1088+N 只增不减（`check-test-baseline.sh --update` 从真实跑写入）；四静态 0 违规；三守卫 CLEAN；merge-gate 全 PASS；MISMATCH 用例加载与判卷 failureRate=0；机械判据抽验通过。
- **授权段（任务组 4）**：baseline-v3.json 落盘（SUITE_VERSION=3.0、failureRate=0、54 旧例对照 after-quality-loop 锚无回退、MISMATCH 组 recall<1.0 用例 ≥6 条——缺口成立即激活卡有了度量面）。

## 风险

- MISMATCH 若设计成「不可桥接失配」（需外部知识映射），multi-query/hyDE 也救不动 → design.md 定失配设计准则（同义/行话/口语-术语层，改写可桥接），机械 2-gram 判据 + 黄金答案点宽写兜底判卷。
- 新 fixture 词汇串扰污染既有组 → 人名/数字/事件全新 + DataPreparer 测试验证。
- LLM 判卷对失配 case 误判 failure → 用例设计答案点宽松（E 组先例）。
