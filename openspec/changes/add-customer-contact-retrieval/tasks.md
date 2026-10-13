# tasks — add-customer-contact-retrieval

> 红线：**授权范围限定**——真实外呼仅限任务组 3 的回埋与评测链路，其余测试保持零外呼（DASHSCOPE_API_KEY 置空 + mock 桩），真跑 IT 保持 RAG_BENCHMARK_REAL opt-in 门控默认跳过；**已锚基线禁动**——知识库面 SUITE 5.0 / baseline-v6 fixture、customer-baseline-v1.json（v1 报告保留不动）、既有迁移 V1-V39 零改动；**pointId 规则不变**——向量 ID 恒为 CUSTOMER_COMPANY:{id}，Qdrant crm_entity 点数恒 120；**权限面零新增**——零新端点/零新权限常量/零注解变更；surefire 只增不减；新代码中文 Javadoc 标注「add-customer-contact-retrieval 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8。

## 任务组 1 · 摄取链附属语义段

- [x] 1.1 CustomerSemanticTextSupport 扩展：语义文本追加联系人段——仅 is_deleted=0、按 id 升序、上限 5 人，段格式确定（姓名（职位，手机，email），分隔符与现有字段顺序规约一致），保持拼装确定性
      依据：CustomerSemanticTextSupport 新增两参 buildSemanticText(entity, contacts)（bc384bf）——仅取 is_deleted=0、按 id 升序、上限 5 人，段格式「联系人: 姓名（职位，手机，email）」、多人以「；」连接、空字段只输出存在项；单参重载零改动；单出口收敛（PMD OnlyOneReturn，ec2cbef）；确定性由 CustomerSemanticTextSupportTest 顺序/上限/软删过滤用例锁定（全量 1236 绿内）。
- [x] 1.2 CustomerIngestionService：摄取单条时查所属 contact 列表拼入语义文本；payload（entityType/resourceId/ownerUserId/ownerDeptId/companyName）与 pointId 派生规则零改动
      依据：CustomerIngestionService 新增 CustomerContactMapper 依赖（构造第 5 参），ingest 时查所属 contact 拼入语义文本（bc384bf）；payload 五键与 pointId 派生零改动（diff 实证）；customerContactMapper 缺失（评测/单测注入 null）跳过联系人段仅拼公司文本；CustomerIngestionServiceTest 10 例全绿。
- [x] 1.3 contact CRUD 增量 hook：CustomerContactServiceImpl 新增/修改/删除（含软删与 Excel 导入路径）事务提交后触发所属 company 重摄取，复用 CustomerSemanticHookSupport runAfterCommit + reingestById 模式；无 embedding 配置的测试环境静默降级不致既有 IT 失败（对齐公司面 hook 同款语义）
      依据：CustomerContactServiceImpl 增删改（insert/updateList/deleteOrRecoverByIds/deleteByIds/list Excel 导入）事务提交后触发所属 company 重摄取（bc384bf），逻辑抽取 CustomerContactReingestSupport（去重 + 批量重摄取，ec2cbef PMD 收敛拆分）；复用 CustomerSemanticHookSupport.runAfterCommit + reingestById；无 embedding 配置静默降级——全量 clean verify failsafe 114/0/0/7 既有 IT 零失败实证（CustomerContactControllerIT 等全绿）。
- [x] 1.4 单测：语义文本拼装确定性（顺序/上限/软删过滤）+ hook 事务后触发语义 + 摄取产物含联系人段；全部零外呼（mock 桩）
      依据：CustomerSemanticTextSupportTest(10) / CustomerIngestionServiceTest(10) / CustomerContactServiceImplTest(3) 共 23 例全绿零外呼（ed7046e 修复 baseMapper 注入与 updateBatchById 桩后任务组1+2 全绿）；全量 surefire 1236/0/0/0（work/verify-clean.log）。

## 任务组 2 · V40 数据种子

- [x] 2.1 Flyway V40（下一可用号以 ls src/main/resources/db/migration 实测为准）种 240 行联系人：全部挂 V39 客户 id 1001-1120（每家 2 人），真实感中文姓名/职位（销售总监/技术负责人/采购经理等分布）/手机/email，头注释声明 add-customer-contact-retrieval 任务 2.1，INSERT IGNORE 幂等
      依据：V40__customer_contact_demo_seed.sql（bc384bf）240 行 = 120 客户（id 1001-1120）× 2 人，显式 id 20001 起避开 init_data.sql 测试种子 id=1；真实感中文姓名/职位分布（销售总监/技术负责人/采购经理/运营总监等）/手机（全局唯一 idx_mobile）/email；INSERT IGNORE 幂等；头注释「add-customer-contact-retrieval 任务 2.1」；creator_id 落 V39 演示销售员（11/12/21/31/41/42）；实测迁移目录最高 V39 → V40 为下一可用号。
- [x] 2.2 FlywayMigrationIT：EXPECTED_VERSIONS 增版（26 版）+ 新 Order 断言（240 行联系人、每行外键公司存在、is_deleted=0、职位分布抽查）
      依据：EXPECTED_VERSIONS 增 "40"（V1→V40 26 版）+ 新增 @Order(12) V40 断言（240 行/120 公司/每家恰 2 人/外键公司存在/is_deleted=0/职位分布抽查，bc384bf）；真库 Docker 实测 mvn verify -Dit.test=FlywayMigrationIT Tests run: 12, Failures: 0, Errors: 0（20:02 留痕）；全量 clean verify 内 FlywayMigrationIT 12/0/0 复证（21:01）。
- [x] 2.3 V40 不破坏既有 IT：显式 id 避开 init_data.sql 测试种子（id 1），全量 failsafe 跑后 CustomerContactControllerIT 等既有断言全绿（COUNT 类断言若受影响修测试不改种子语义，§0 披露）
      依据：全量 mvn -B -ntp -o clean verify（2026-10-13 21:01，work/verify-clean.log）failsafe 114/0/0/7 全绿，CustomerContactControllerIT 等既有断言零失败；V40 显式 id 20001+ 避开 init_data.sql 种子（id=1）；无 COUNT 类断言受影响（§0 无该项披露）。

## 任务组 3 · 真跑回埋与评测（A 档核心）

- [x] 3.1 前置实测留痕：customer_contact COUNT（回埋前）、Qdrant 在线（crm_entity points=120 回埋前）、DASHSCOPE_API_KEY 探活（零成本或极低成本）
      依据：回埋前实测 crm_dev_reingest.customer_contact(is_deleted=0)=240（V40 已应用）；Qdrant 在线 crm_entity collection 存在 points_count=120；DASHSCOPE_API_KEY 探活成功（text-embedding-v3 原生端点真实响应）；详见 handoff §3。
- [x] 3.2 真跑重埋：本地 boot（沿用 acrb 先例：crm_dev_reingest 库 + SPRING_AI_DASHSCOPE_BASE_URL 原生 base 等三项运行参数覆盖不落盘，偏差同款披露）POST /knowledge/customer/reingest，记录 ReingestResult(total/success/failure)；幂等复跑一次，Qdrant points 恒 120 无重复
      依据：本地 boot（dev 档 + crm_dev_reingest + 三项运行参数覆盖不落盘，偏差 B 同款披露）HTTP POST /knowledge/customer/reingest（900 权限 admin token）——total=120 / success=120 / failure=0（26.1s）；幂等复跑 120/120/0（23.6s）；Qdrant crm_entity points_count 恒 120（pointId 确定性派生 upsert 无重复）。
- [x] 3.3 成本留痕：语义文本增长后重测字符数并按 acrb 校准口径（1.56 字符/token）估 token/成本；冒烟验证至少 3 条联系人线索查询（按联系人姓名/按职位/按手机号）能召回正确客户
      依据：120 行重构语义文本实测 49,686 字符 → 按 1.56 字符/token ≈ 31,850 tokens/轮 → text-embedding-v3 0.0007 元/1K tokens → 单轮 ≈0.0223 元、两轮 ≈0.0446 元（估算口径偏差 C 披露，分厘级符合授权）；冒烟 5 条全中——姓名（周建华→2001）/手机号（13912345678→2004）/姓名+区域（深圳林志强→2003）/职位+行业（C-12）/职位同义失配（经销商渠道↔渠道经理→2006）。
- [x] 3.4 CustomerRetrievalBenchmarkIT 扩展：新增 4-5 例联系人查询（C-11~C-15：按姓名找客户、职位+行业组合、按手机号、姓名+区域组合，可含 1 例职位同义词汇失配），语料行带联系人数据（测试内构造、经同一生产摄取链真嵌入），expectedChunkIds=CUSTOMER_COMPANY:{id} 不变；真跑全量——旧 10 例 recall 逐例零回退（v1=1.0 全中口径）+ 新例指标留痕，报告 docs/rag-quality/customer-baseline-v2.json（CUSTOMER-SUITE-2.0，不锚）
      依据：新增 C-11~C-15（ba9941e）——按姓名/职位+行业/按手机号/姓名+区域/职位同义失配各 1 例；语料 2001-2006 测试内构造虚构联系人经生产摄取链（mock CustomerContactMapper 按行喂入）真嵌入；expectedChunkIds 恒 CUSTOMER_COMPANY:{id}；真跑 CUSTOMER-SUITE-2.0：caseCount=15，meanRecallAtK=1.0，MRR=1.0，hitRate=1.0（15/15），meanPrecisionAtK=0.2；旧 C-01~C-10 逐例零回退（10/10 全中含 C-08~C-10 词汇失配）；报告 docs/rag-quality/customer-baseline-v2.json（8bb8972，不锚、v1 保留不动）。
- [x] 3.5 知识库面回归真跑：RagRealRetrievalBenchmarkIT 对照 baseline-v6（SUITE 5.0）逐例漂移对拍，确认语义文本变化只影响 customer 面、知识库面零回退，回归对拍文件与锚文件零改动
      依据：真跑 63 例（292.8s，输出 docs/rag-quality/kb-regression-v2.json 未触锚文件，8bb8972）——meanRecallAtK 0.96296 == 锚点 0.96296（逐例 recall Δ=0 零回退）、hitRate 1.0==1.0、MRR 0.92857 vs 0.93651（Δ-0.008 < 5% 房风）、citP Δ+0.0005 / ansC Δ-0.011（LLM 非确定性抖动均在房风内）；baseline-v6 fixture 零改动；i05-forensics.json 被真跑旁路覆写后 git checkout 还原（偏差 E）。

## 任务组 4 · 门禁与收尾

- [x] 4.1 全量 mvn -B -ntp test 0 失败（surefire 只增不减），checkstyle/spotbugs/spotless/pmd 顺序跑全 0
      依据：全量 mvn -B -ntp -o clean verify（2026-10-13 21:01 BUILD SUCCESS，work/verify-clean.log）surefire Tests run: 1236, Failures: 0, Errors: 0, Skipped: 0（基线 1225 净增 11 只增不减）、failsafe 114/0/0/7；四静态 verify 内顺序实测——checkstyle 0 violations、spotless 968 files clean / 0 needs changes、spotbugs BugInstance size is 0、pmd:check 通过（0 违规）。
- [x] 4.2 三守卫（pmd-baseline/bijection/test-baseline）+ merge-gate 8/8；基线 --update 仅当 surefire 净增（真跑 IT 为 opt-in 不计入）
      依据：三守卫（work/guards.log）——PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）/ BIJECTION_OK（12 条豁免 == 12 条 High）/ check-test-baseline 通过（surefire 194/1236 ≥ 193/1225、failsafe 30/114 ≥ 30/113、skipped 7 ≤ 7）；surefire 净增 11 → 基线 --update 真实收紧 1225→1236 / 113→114（source-revision=8bb8972 干净哈希，2026-10-13T13:03:23Z 写入）；merge-gate 8/8（unit/spotbugs/pmd/baseline/frontend-unit 179 例/hook/bijection/pmd-baseline 全 PASS，work/merge-gate.log）。
- [x] 4.3 tasks 全勾 + 每勾下一行 6 空格依据行 + §0 留痕（回埋实测数字/token 成本/评测 v2 指标含旧例回退结论/知识库面漂移结论/偏差披露），handoff 落 work/handoff-add-customer-contact-retrieval.md
      依据：tasks 15/15 全勾、每勾依据行在案（本收尾笔）；§0 留痕 = work/handoff-add-customer-contact-retrieval.md（§1-6 任务组 1-3 实测数字/token 成本/评测 v2 指标/KB 面漂移结论/偏差披露 A-F，§7 任务组 4 门禁实测数字）；handoff 随本收尾笔提交 tracked（acrb 005143a 先例）。