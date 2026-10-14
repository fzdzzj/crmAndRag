# tasks — add-customer-retrieval-baseline

> 红线：**授权范围限定**——真实外呼仅限任务组 2/3 的回埋与评测链路，其余测试保持零外呼（DASHSCOPE_API_KEY 置空 + mock 桩），真跑 IT 保持 RAG_BENCHMARK_REAL opt-in 门控默认跳过；**已锚基线禁动**——知识库面 SUITE 5.0 / baseline-v6 fixture 零改动；既有迁移 V1-V38 禁改；权限四档演示语义在种子里真实呈现；surefire 只增不减；新代码中文 Javadoc 标注「add-customer-retrieval-baseline 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8。

## 任务组 1 · 演示种子

- [x] 1.1 Flyway V39（下一可用号以 `ls src/main/resources/db/migration | sed 's/__.*//' | sort -V | tail -1` 实测为准）种 50-200 行真实感客户：行业/规模/区域/备注语义丰富，头注释声明 add-customer-retrieval-baseline 任务 1.1
      依据：`src/main/resources/db/migration/V39__customer_demo_seed.sql` 已落地并提交（e6712e8）；实测迁移目录最高 V38 → V39 为下一可用号；V39 头注释声明「add-customer-retrieval-baseline 任务 1.1 / 1.2」；种入 120 行客户（id 1001-1120，公司名全局唯一，行业/地址/描述语义丰富）；全新库 crm_dev_reingest 全链 V1→V39 真 MySQL 实测 Successfully applied（2026-10-13 17:04，25 版）；FlywayMigrationIT 真库 11 例全绿（Order11 V39 断言：120 客户 + 6 owner + 4 部门带 leader + role5 + 608 授权）。
- [x] 1.2 种子 owner 归属覆盖权限四档演示场景：含归属人直查、上司部门可见、跨部门不可见、管理员全可见的客户分布（owner_id/owner_dept_id 反范式字段同步种入）
      依据：V39 种入演示组织（部门 101-104 各带 leader_id、销售员 11/12/21/31/41/42、演示销售角色 roleId=5）与客户 owner 分布（11:19/12:17/21:14/31:23/41:18/42:29）；四档语义：负责人（leader_id）→DEPT_AND_CHILD 上司可见、普通销售→SELF 归属人自己、跨部门同事（如 12 看 21/31/41/42 名下）不可见、roleId=1 超管 ALL 全可见；ownerDeptId 由摄取链 owner_id 反查 sys_user.dept_id 反范式写入向量 payload（CustomerIngestionService#resolveOwnerDeptId）。
- [x] 1.3 V39 不破坏既有 IT：全量 failsafe 跑后 CustomerCompanyControllerIT 等既有断言全绿（COUNT 类断言若受影响修测试不改种子语义，§0 披露）
      依据：`mvn -B -ntp clean verify`（Docker 在线）failsafe 113 例 0 失败 0 错误（2026-10-13 17:43）；CustomerCompanyControllerIT 1/0/0/0、CustomerReingestControllerIT 2/0/0/0、PermissionControllerIT 3/0/0/0、FlywayMigrationIT 11/0/0/0 全绿；V39 客户/用户显式 id（1001+/11-42）避开 IT 种子（1-3），init_data.sql 每测试前置重建互不干扰；无 COUNT 类断言受影响，无需修测试（§0 无该项披露）。

## 任务组 2 · 生产回埋

- [x] 2.1 前置实测留痕：customer_company COUNT（回埋前）、Qdrant 在线（crm_entity collection 存在性）、DASHSCOPE_API_KEY 有效性（探活方式零成本或极低成本）
      依据：回埋前实测 crm_dev_reingest.customer_company(is_deleted=0)=120、sys_user=7、sys_dept=5；Qdrant v1.18.3 在线且 crm_entity collection 存在（应用启动自动 ensure，points_count=120 回埋后核对）；DASHSCOPE_API_KEY 探活成功（text-embedding-v3 原生端点，dims=1024，usage 0 tokens 极低成本）；留痕详见 work/handoff-add-customer-retrieval-baseline.md §2。
- [x] 2.2 真跑回埋：本地部署环境执行（HTTP 900 端点或 boot 起服务，方式自定留痕为凭），记录 ReingestResult(total/success/failure)
      依据：本地 `mvn spring-boot:run`（dev 档 + crm_dev_reingest 全新库 + 原生 DashScope base 覆盖，见 §0 偏差 C）boot 成功后 HTTP 调用 POST /knowledge/customer/reingest（900 权限，admin token）——**ReingestResult(total=120, success=120, failure=0)**（2026-10-13 17:14）；应用日志「客户语义全量回埋单条失败」零条。
- [x] 2.3 成本留痕：本次回埋 token 消耗与成本估算（对照 P-j 先例口径 149 tokens≈0.0001 元），附检索冒烟验证（回埋后至少 1 条真实查询能召回正确客户）
      依据：token/成本——原生端点直测校准 237 字符→152 tokens（1.56 字符/token）；120 行语义文本合计 29,294 字符 → 估算 18,778 tokens/轮；成本（text-embedding-v3 0.0007 元/1K tokens）单轮 ≈ 0.0131 元、两轮（首跑+复跑）≈ 0.0263 元，含评测语料/冒烟合计 ≈ 0.028 元（分厘级）；冒烟——「锐进数据科技这家公司怎么样」top-1 召回 resourceId=1015（score 0.7471）、「苏州工业园区的储能客户有哪些」top-2 命中 1015/1009（0.6655/0.6352），证明回埋向量可被真实查询召回正确客户。
- [x] 2.4 幂等复跑验证：重复回埋一次，total 稳定、Qdrant 无重复向量（upsert 语义验证），留痕
      依据：第二次 reingest total=120 success=120 failure=0；Qdrant crm_entity points_count 首跑=120 → 复跑后仍=120（VectorRecord.pointId=UUID v3 确定性派生，upsert 覆盖同 pointId，无重复向量）。

## 任务组 3 · 真跑评测双面

- [x] 3.1 CustomerRetrievalBenchmarkIT 独立入口：RAG_BENCHMARK_REAL=1 + DASHSCOPE_API_KEY 双门控 Assumptions 跳过语义（对齐 RagRealRetrievalBenchmarkIT 先例），判卷复用 RagQualityEvaluator 规则面
      依据：`src/test/java/com/slz/crm/quality/CustomerRetrievalBenchmarkIT.java` 已提交（ddbd877）；类头 Javadoc 标注「add-customer-retrieval-baseline 任务 3.1 / 3.2 / 3.3」；RAG_BENCHMARK_REAL=1 + DASHSCOPE_API_KEY（env 或 .env）双门控 Assumptions 跳过（默认 verify 实测 Skipped=1，零外呼）；判卷调用 RagQualityEvaluator.evaluate（recall/MRR/hit，TOP_K=5 对齐知识库基准）。
- [x] 3.2 customer 检索用例 8-12 例：语义匹配（行业/规模/区域）+ 词汇失配（同义不同词，对齐 §75 结论），评测上下文 ALL 档（权限面不混入质量评测），expectedChunkIds 对应 crm_entity 向量 ID
      依据：10 例（C-01~C-10）：C-01~C-07 语义匹配（苏州/杭州/深圳/宁波/无锡 行业+区域、五百人规模等），C-08~C-10 词汇失配（扫地机器人↔家用清洁机器人、跨境电商ERP↔跨境贸易管理软件、注塑模具↔塑料成型模具，词面重叠≈空，对齐 expand-rag-benchmark-mismatch MISMATCH 口径）；数据范围桩恒返 ALL（权限面不混入）；expectedChunkIds=CUSTOMER_COMPANY:{id}（生产摄取链同源 crm_entity 向量 ID）；16 行语料经生产摄取链（CustomerIngestionService）真嵌入。
- [x] 3.3 customer 面首跑：真跑留痕全量指标（recall/MRR/hit），**不锚 fixture**（D2），SUITE_VERSION 不动，结果记录进 §0 与 work/ 留痕
      依据：真跑（2026-10-13 17:23，RAG_BENCHMARK_REAL=1）——caseCount=10，meanRecallAtK=1.0、MRR=1.0、hitRate=1.0（10/10 全中）、meanPrecisionAtK=0.2、failureRate=0；报告已提交 docs/rag-quality/customer-baseline-v1.json（CUSTOMER-SUITE-1.0，独立编号不并入知识库 63 例，SUITE_VERSION 不动，D2 不锚）；留痕见 work/handoff-add-customer-retrieval-baseline.md §3。
- [x] 3.4 知识库面回归真跑：RagRealRetrievalBenchmarkIT 对照 baseline-v6（SUITE 5.0）逐例漂移对拍，确认 add-customer-semantic-retrieval 合入后知识库面无回退，漂移结论留痕
      依据：真跑 63 例（2026-10-13 17:35，输出 docs/rag-quality/kb-regression-v1.json 未触锚文件）——meanRecallAtK 0.96296 == 锚点 0.96296、hitRate 1.0 == 1.0、MRR 0.92857 vs 0.93651（Δ-0.008 < 5% 房风）、citP/ansC 微抖均在房风内；逐例 recall Δ=0 全部（0 例回退），8 例仅 citP/ansC 的 LLM 非确定性抖动（T-03/T-11/T-12/T-14/TB-08/M-01/M-02/M-04）；结论：**知识库面零回退**，baseline-v6 fixture 零改动。

## 任务组 4 · 门禁与收尾

- [x] 4.1 全量 mvn -B -ntp test 0 失败（surefire 只增不减），checkstyle/spotbugs/spotless/pmd 顺序跑全 0
      依据：`mvn -B -ntp clean verify`（2026-10-13 17:43）surefire **Tests run: 1225, Failures: 0, Errors: 0, Skipped: 0**（与基线 1225 持平，只增不减）；failsafe 113/0/0/7 全绿；checkstyle 0 违规（verify 内实测）、spotless 0 需改（verify 内实测）、spotbugs Error size=0（verify 内实测）、pmd:check 通过（verify 内实测）——四静态顺序跑全 0。
- [x] 4.2 三守卫（pmd-baseline/bijection/test-baseline）+ merge-gate 8/8；基线 --update 仅当 surefire 净增（真跑 IT 为 opt-in 不计入）
      依据：pmd-baseline-check RESULT=PMD_BASELINE_OK（实测 0==登记 0==pom 阈值 0）；spotbugs-exclude-staleness-check RESULT=BIJECTION_OK（12==12）；check-test-baseline 一次 --update（2026-10-13 17:45，surefire 193/1225/0 持平，failsafe 30/113/7 如实记录本卡净增——FlywayMigrationIT +1 真跑用例、CustomerRetrievalBenchmarkIT +1 opt-in 跳过用例，偏差披露 F）；merge-gate 8/8 全绿（见下条依据）。
- [x] 4.3 tasks 全勾 + 每勾下一行 6 空格依据行 + §0 留痕（回埋实测数字/token 成本/评测首跑指标/知识库面漂移结论/偏差披露）
      依据：本文件全勾并逐行依据；§0 留痕完整落盘 work/handoff-add-customer-retrieval-baseline.md（回埋 total=120/success=120/failure=0、token≈37,556 两轮/成本≈0.028 元、customer 首跑 recall=1.0/MRR=1.0/hit=100%、知识库面零回退结论、六条偏差披露 A-F）；提交哈希 e6712e8（V39）/ddbd877（Benchmark IT）/6d30245（首跑留痕）+ 组 4 收尾提交。

## §0 留痕（摘要，全文见 work/handoff-add-customer-retrieval-baseline.md）

- 回埋实测：total=120 / success=120 / failure=0（首跑与幂等复跑均同），Qdrant crm_entity points=120（复跑后不变）。
- token/成本：120 行 29,294 字符 → 估算 18,778 tokens/轮（1.56 字符/token 原生直测校准）；text-embedding-v3 0.0007 元/1K tokens；两轮回埋 ≈ 0.0263 元，含评测/冒烟合计 ≈ 0.028 元。
- customer 面首跑：10 例 recall=1.0 / MRR=1.0 / hit=100%（7 语义 + 3 词汇失配全 top-1），报告 customer-baseline-v1.json（CUSTOMER-SUITE-1.0，不锚）。
- 知识库面回归：63 例 recall 逐例零漂移，聚合均值全部在 5% 房风内 → **add-customer-semantic-retrieval 合入后知识库面零回退**（baseline-v6 零改动）。
- 偏差披露：
  - A 回埋环境切库：.env 指向的 crm_dev_smoke 有预存 Flyway V26 失败历史（501-504 旧 report:* 权限行撞主键，与本卡无关），回埋在全新库 crm_dev_reingest（同 schema 链）执行；
  - B token 计量：spring-ai-alibaba 原生 usage 未回流（corpusEmbeddingTotalTokens=0），采用原生端点直测校准估算；
  - C 本地 boot 覆盖三项运行参数（SPRING_AI_DASHSCOPE_BASE_URL 原生 base 修预存 404 配置怪癖 / KNOWLEDGE_STORAGE_PROVIDER=in-memory / health validate-group-membership=false），均不落盘不改代码；
  - D native 与 compatible-mode 向量空间不可比（同文本 cos≈0.40），生产/回埋/评测全走原生，系统内部一致；
  - E 前端零改动；已锚 SUITE 5.0/baseline-v6 零改动；V1-V38 零改动；
  - F 基线 --update 一次（surefire 1225 持平，failsafe 30/113/7 记录本卡净增）。
- 门禁实证：clean verify surefire 1225/0/0/0 + failsafe 113/0/0/7（Docker 真跑）；四静态全 0；三守卫全绿；merge-gate 8/8。
