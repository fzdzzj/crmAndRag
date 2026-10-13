# tasks — add-customer-contact-retrieval

> 红线：**授权范围限定**——真实外呼仅限任务组 3 的回埋与评测链路，其余测试保持零外呼（DASHSCOPE_API_KEY 置空 + mock 桩），真跑 IT 保持 RAG_BENCHMARK_REAL opt-in 门控默认跳过；**已锚基线禁动**——知识库面 SUITE 5.0 / baseline-v6 fixture、customer-baseline-v1.json（v1 报告保留不动）、既有迁移 V1-V39 零改动；**pointId 规则不变**——向量 ID 恒为 CUSTOMER_COMPANY:{id}，Qdrant crm_entity 点数恒 120；**权限面零新增**——零新端点/零新权限常量/零注解变更；surefire 只增不减；新代码中文 Javadoc 标注「add-customer-contact-retrieval 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8。

## 任务组 1 · 摄取链附属语义段

- [ ] 1.1 CustomerSemanticTextSupport 扩展：语义文本追加联系人段——仅 is_deleted=0、按 id 升序、上限 5 人，段格式确定（姓名（职位，手机，email），分隔符与现有字段顺序规约一致），保持拼装确定性
- [ ] 1.2 CustomerIngestionService：摄取单条时查所属 contact 列表拼入语义文本；payload（entityType/resourceId/ownerUserId/ownerDeptId/companyName）与 pointId 派生规则零改动
- [ ] 1.3 contact CRUD 增量 hook：CustomerContactServiceImpl 新增/修改/删除（含软删与 Excel 导入路径）事务提交后触发所属 company 重摄取，复用 CustomerSemanticHookSupport runAfterCommit + reingestById 模式；无 embedding 配置的测试环境静默降级不致既有 IT 失败（对齐公司面 hook 同款语义）
- [ ] 1.4 单测：语义文本拼装确定性（顺序/上限/软删过滤）+ hook 事务后触发语义 + 摄取产物含联系人段；全部零外呼（mock 桩）

## 任务组 2 · V40 数据种子

- [ ] 2.1 Flyway V40（下一可用号以 `ls src/main/resources/db/migration | sed 's/__.*//' | sort -V | tail -1` 实测为准）种 240 行联系人：全部挂 V39 客户 id 1001-1120（每家 2 人），真实感中文姓名/职位（销售总监/技术负责人/采购经理等分布）/手机/email，头注释声明 add-customer-contact-retrieval 任务 2.1，INSERT IGNORE 幂等
- [ ] 2.2 FlywayMigrationIT：EXPECTED_VERSIONS 增版（26 版）+ 新 Order 断言（240 行联系人、每行外键公司存在、is_deleted=0、职位分布抽查）
- [ ] 2.3 V40 不破坏既有 IT：显式 id 避开 init_data.sql 测试种子（id 1），全量 failsafe 跑后 CustomerContactControllerIT 等既有断言全绿（COUNT 类断言若受影响修测试不改种子语义，§0 披露）

## 任务组 3 · 真跑回埋与评测（A 档核心）

- [ ] 3.1 前置实测留痕：customer_contact COUNT（回埋前）、Qdrant 在线（crm_entity points=120 回埋前）、DASHSCOPE_API_KEY 探活（零成本或极低成本）
- [ ] 3.2 真跑重埋：本地 boot（沿用 acrb 先例：crm_dev_reingest 库 + SPRING_AI_DASHSCOPE_BASE_URL 原生 base 等三项运行参数覆盖不落盘，偏差同款披露）POST /knowledge/customer/reingest，记录 ReingestResult(total/success/failure)；幂等复跑一次，Qdrant points 恒 120 无重复
- [ ] 3.3 成本留痕：语义文本增长后重测字符数并按 acrb 校准口径（1.56 字符/token）估 token/成本；冒烟验证至少 3 条联系人线索查询（按联系人姓名/按职位/按手机号）能召回正确客户
- [ ] 3.4 CustomerRetrievalBenchmarkIT 扩展：新增 4-5 例联系人查询（C-11~C-15：按姓名找客户、职位+行业组合、按手机号、姓名+区域组合，可含 1 例职位同义词汇失配），语料行带联系人数据（测试内构造、经同一生产摄取链真嵌入），expectedChunkIds=CUSTOMER_COMPANY:{id} 不变；真跑全量——旧 10 例 recall 逐例零回退（v1=1.0 全中口径）+ 新例指标留痕，报告 docs/rag-quality/customer-baseline-v2.json（CUSTOMER-SUITE-2.0，不锚）
- [ ] 3.5 知识库面回归真跑：RagRealRetrievalBenchmarkIT 对照 baseline-v6（SUITE 5.0）逐例漂移对拍，确认语义文本变化只影响 customer 面、知识库面零回退，回归对拍文件与锚文件零改动

## 任务组 4 · 门禁与收尾

- [ ] 4.1 全量 mvn -B -ntp test 0 失败（surefire 只增不减），checkstyle/spotbugs/spotless/pmd 顺序跑全 0
- [ ] 4.2 三守卫（pmd-baseline/bijection/test-baseline）+ merge-gate 8/8；基线 --update 仅当 surefire 净增（真跑 IT 为 opt-in 不计入）
- [ ] 4.3 tasks 全勾 + 每勾下一行 6 空格依据行 + §0 留痕（回埋实测数字/token 成本/评测 v2 指标含旧例回退结论/知识库面漂移结论/偏差披露），handoff 落 work/handoff-add-customer-contact-retrieval.md
