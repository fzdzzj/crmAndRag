# tasks — add-customer-retrieval-baseline

> 红线：**授权范围限定**——真实外呼仅限任务组 2/3 的回埋与评测链路，其余测试保持零外呼（DASHSCOPE_API_KEY 置空 + mock 桩），真跑 IT 保持 RAG_BENCHMARK_REAL opt-in 门控默认跳过；**已锚基线禁动**——知识库面 SUITE 5.0 / baseline-v6 fixture 零改动；既有迁移 V1-V38 禁改；权限四档演示语义在种子里真实呈现；surefire 只增不减；新代码中文 Javadoc 标注「add-customer-retrieval-baseline 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8。

## 任务组 1 · 演示种子

- [ ] 1.1 Flyway V39（下一可用号以 `ls src/main/resources/db/migration | sed 's/__.*//' | sort -V | tail -1` 实测为准）种 50-200 行真实感客户：行业/规模/区域/备注语义丰富，头注释声明 add-customer-retrieval-baseline 任务 1.1
      依据（待执行回填）
- [ ] 1.2 种子 owner 归属覆盖权限四档演示场景：含归属人直查、上司部门可见、跨部门不可见、管理员全可见的客户分布（owner_id/owner_dept_id 反范式字段同步种入）
      依据（待执行回填）
- [ ] 1.3 V39 不破坏既有 IT：全量 failsafe 跑后 CustomerCompanyControllerIT 等既有断言全绿（COUNT 类断言若受影响修测试不改种子语义，§0 披露）
      依据（待执行回填）

## 任务组 2 · 生产回埋

- [ ] 2.1 前置实测留痕：customer_company COUNT（回埋前）、Qdrant 在线（crm_entity collection 存在性）、DASHSCOPE_API_KEY 有效性（探活方式零成本或极低成本）
      依据（待执行回填）
- [ ] 2.2 真跑回埋：本地部署环境执行（HTTP 900 端点或 boot 起服务，方式自定留痕为凭），记录 ReingestResult(total/success/failure)
      依据（待执行回填）
- [ ] 2.3 成本留痕：本次回埋 token 消耗与成本估算（对照 P-j 先例口径 149 tokens≈0.0001 元），附检索冒烟验证（回埋后至少 1 条真实查询能召回正确客户）
      依据（待执行回填）
- [ ] 2.4 幂等复跑验证：重复回埋一次，total 稳定、Qdrant 无重复向量（upsert 语义验证），留痕
      依据（待执行回填）

## 任务组 3 · 真跑评测双面

- [ ] 3.1 CustomerRetrievalBenchmarkIT 独立入口：RAG_BENCHMARK_REAL=1 + DASHSCOPE_API_KEY 双门控 Assumptions 跳过语义（对齐 RagRealRetrievalBenchmarkIT 先例），判卷复用 RagQualityEvaluator 规则面
      依据（待执行回填）
- [ ] 3.2 customer 检索用例 8-12 例：语义匹配（行业/规模/区域）+ 词汇失配（同义不同词，对齐 §75 结论），评测上下文 ALL 档（权限面不混入质量评测），expectedChunkIds 对应 crm_entity 向量 ID
      依据（待执行回填）
- [ ] 3.3 customer 面首跑：真跑留痕全量指标（recall/MRR/hit），**不锚 fixture**（D2），SUITE_VERSION 不动，结果记录进 §0 与 work/ 留痕
      依据（待执行回填）
- [ ] 3.4 知识库面回归真跑：RagRealRetrievalBenchmarkIT 对照 baseline-v6（SUITE 5.0）逐例漂移对拍，确认 add-customer-semantic-retrieval 合入后知识库面无回退，漂移结论留痕
      依据（待执行回填）

## 任务组 4 · 门禁与收尾

- [ ] 4.1 全量 mvn -B -ntp test 0 失败（surefire 只增不减），checkstyle/spotbugs/spotless/pmd 顺序跑全 0
      依据（待执行回填）
- [ ] 4.2 三守卫（pmd-baseline/bijection/test-baseline）+ merge-gate 8/8；基线 --update 仅当 surefire 净增（真跑 IT 为 opt-in 不计入）
      依据（待执行回填）
- [ ] 4.3 tasks 全勾 + 每勾下一行 6 空格依据行 + §0 留痕（回埋实测数字/token 成本/评测首跑指标/知识库面漂移结论/偏差披露）
      依据（待执行回填）
