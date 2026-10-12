# tasks — b2-suite-recalibration

> 红线：判卷口径 v2（承载集）与主锚口径不动；fixtures 变更仅限取证在案的 8+1 例（T-01/TB-01/TB-08/TB-09/TB-10/TB-11/M-01/M-04 黄金补标 + T-15 移标记），逐例对照 citation-forensics-v4 §3 支撑判定；真实外呼仅限任务组 4 单轮（RAG_BENCHMARK_REAL=1，owner 授权）；其余任务组 DASHSCOPE_API_KEY 置空；SUITE_VERSION 升 4.0 与 fixtures 变更同笔；surefire 只增不减。

## 任务组 1 · 升版与 fixtures 校准

- [ ] 1.1 SUITE_VERSION 3.0→4.0（RagBenchmarkSuite 常量 + javadoc 升版说明）
- [ ] 1.2 T-15 移标记：`【GOLD:customer-onboard-3】` 移至报备句主体段（customer-sop-3 excerpt），标记与被标内容完整同块；PreparerTest 撕裂现场用例反转为零撕裂断言（右/左残段零检出），原「右残段未剥离」零行为锁同步调整
- [ ] 1.3 B2 八例黄金补标：逐例按取证 §3 支撑判定补入真实支撑块（T-01 扩写归档段 / TB-01 全 4 区 / TB-08、TB-09 排除论证非命中行 / TB-10、TB-11 补充论点 / M-01 比较列举 / M-04 跨题归属），每例补标留一行依据注释（取证报告案例号）
- [ ] 1.4 向量输入 SHA256 双钉更新（T-15 语料标记位置变化）；fixtures 案例名/人名/数字零新增零改动（只动既有案例的黄金集与标记位）

## 任务组 2 · V5 锚点与校验增量

- [ ] 2.1 锚点校验增量支持 v5（对齐 V4AnchorValidationTest 先例：SUITE_VERSION=4.0 登记逻辑 + v3/v4/v5 三锚并存）
- [ ] 2.2 单测：补标八例 + T-15 的黄金集断言（Preparation 层，零外呼）；SUITE_VERSION=4.0 断言

## 任务组 3 · 门禁（零外呼）

- [ ] 3.1 全量 mvn -B -ntp test 0 失败（surefire 只增）；四静态顺序跑（禁并发）；三守卫；check-test-baseline 只读（如需 --update 先 DASHSCOPE_API_KEY="" 全量真跑 failsafe 刷新口径，一次只增）；merge-gate 8/8

## 任务组 4 · 真跑重锚（RAG_BENCHMARK_REAL=1，owner 授权）

- [ ] 4.1 63 例全量单轮真跑（DASHSCOPE_API_KEY 真值 + RAG_BENCHMARK_REAL=1），产出 baseline-v5.json（SUITE_VERSION=4.0 登记）
- [ ] 4.2 v4→v5 指标对比报告：修正九例（8+1）citP 应改善、T-15 脱离 0 分态；未触碰例 recall/MRR/ansC 逐位不动（或解释漂移）；recall 因黄金面扩大允许合理下降并逐例归因
- [ ] 4.3 门禁复跑 + 停步回报（真跑 raw、成本、对比表）

## §0 执行留痕

（执行方填写）
