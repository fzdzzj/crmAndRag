# proposal — b2-suite-recalibration（B2 升版受控校准：SUITE_VERSION 4.0 + 黄金口径补标 + T-15 锚修正 + 全量重锚）

## Why

trace-citation-redundancy 取证（citation-forensics-v4）确认 18 例 B 口径特性中，B1 切分边界 9 例已由 fix-citation-grading-overlap 判卷口径 v2 解决（citP 0.8272→0.9243）；遗留两类需动套件本体：

- **B2 黄金标注口径缺口 8 例**（T-01、TB-01、TB-08、TB-09、TB-10、TB-11、M-01、M-04）：答案真实支撑块（扩写段/排除论证非命中行/比较列举/补充论点/跨题归属）未标入黄金，模型引用真实支撑块被判错。取证逐例支撑判定在案（报告 §3）。
- **B3 标记撕裂 1 例**（T-15）：fixture 标记 `【GOLD:customer-onboard-3】` 被切分边界撕裂，按标记起点归块 → 黄金错归 customer-sop-2（仅段首句），报备句主体在 customer-sop-3——「无论模型引用哪块，黄金口径下必错一块」，citP 0 分不构成模型缺陷证据（报告 §6）。

处置语义在案：改黄金 = 改 case = 升 SUITE_VERSION（套件 javadoc），升版机制即为此类受控变更设计，不触碰「合并后迁移禁改」类红线。

## What Changes

1. **SUITE_VERSION 3.0→4.0**（RagBenchmarkSuite，63 例口径不变）。
2. **B2 八例黄金补标**：按取证报告 §3 逐例支撑判定，把真实支撑块补入黄金集（如 TB-01「各区域」补全 4 区、TB-10 XR-500 年度补标、T-01 扩写归档段、M-01 比较列举行、M-04 跨题归属块等）。
3. **T-15 锚修正（移标记）**：把 `【GOLD:customer-onboard-3】` 标记移至报备句主体所在段（customer-sop-3 excerpt），标记与被标内容完整落入同块；撕裂消失，黄金归点即内容主体。
4. **零行为锁与双钉同步**：PreparerTest 撕裂现场用例（T-15 断言右/左残段检出）反转为零撕裂断言；向量输入 SHA256 双钉按新语料更新。
5. **V5 锚点增量**：真跑 63 例单轮产出 baseline-v5.json（SUITE_VERSION=4.0 登记），v3/v4 锚点保留并存；锚点校验增量支持 v5（对齐 V4AnchorValidationTest 先例）。

## 受控解冻与红线

- 「existing fixtures and their gold IDs must not be modified」系 expand 时期防误改红线；本卡为取证驱动、升版机制下的受控校准（javadoc 语义在案），黄金补标与标记修正均以取证报告逐例判定为依据，非自由改写。
- 真实外呼仅限任务组 4 全量重锚单轮（RAG_BENCHMARK_REAL=1，owner 授权，参照上卡 310s / 61584 tokens）；其余任务组零外呼（DASHSCOPE_API_KEY 置空）。
- 判卷口径 v2（承载集）与主锚口径不动；本卡只动黄金集合与 SUITE_VERSION。

## Impact

- 写集预估：RagBenchmarkSuite + fixtures（B2 八例 + T-15 所在 fixture）+ PreparerTest + 双钉文件 + V5 校验测试 + baseline-v5.json + 三件套，约 10-14 files。
- 门禁预期：surefire 只增（PreparerTest 用例反转不减、V5 校验新增）；基线如需 --update 按全量口径刷新纪律。
- 指标预期：citP 上升（8 例误判解除 + T-15 脱离 0 分态），recall 黄金面扩大会略降（分母增大），MRR/ansC 基本不动——重锚后以 v5 为回归基线。

## 拍板点

- **D1 右残段剥离降级（与原「四件套」差异最大）**：原 B2 设想含「Preparer 右残段剥离（向量输入清理）」；取证深化后 T-15 移标记即消灭撕裂源头，剥离代码无实际对象——建议不做剥离，防御留待 harden 检出 WARN 提示未来案例（出现第二例撕裂再立项）。备选：仍做「检出即剥离」防御性实现（+1 任务组，向量输入再变一次）。
- **D2 八例补标范围**：建议全量 8 例一次修完（取证逐例支撑判定在案，一次升版一次重锚；只修 TB-01/TB-10「最明确漏项」会留 6 例错判，二次升版成本更高）。
- **D3 真跑授权**：63 例全量单轮（RAG_BENCHMARK_REAL=1），产出 v5 锚点 + v4→v5 指标对比报告，预期变化需与 B2 修正面吻合（修正例改善、未触碰例不动）。
- **D4 T-15 修正方式**：移标记（fixture 层，最小改动、语义直观）vs Preparer 按内容主体归点（代码层，改全局归点算法风险大）——建议移标记。
