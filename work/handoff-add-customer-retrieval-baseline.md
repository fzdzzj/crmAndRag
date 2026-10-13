# §0 留痕 —— add-customer-retrieval-baseline（交接快照，已随 005143a 提交 tracked）

> 分支 feature/add-customer-retrieval-baseline（自 master@a2f629c 检出）。tasks 全勾与依据见 openspec/changes/add-customer-retrieval-baseline/tasks.md。

## 1. 任务组 1 · 演示种子

- V39__customer_demo_seed.sql：120 行真实感客户（id 1001-1120，公司名全局唯一）+ 演示组织（4 部门 101-104 各带 leader_id、6 销售员 11/12/21/31/41/42、演示销售角色 roleId=5）。
- 权限四档数据面：owner 分布 11:19 / 12:17 / 21:14 / 31:23 / 41:18 / 42:29；张伟(11)/王强(21)/陈静(31)/赵敏(41) 为部门负责人（sys_dept.leader_id）→ DEPT_AND_CHILD；普通销售（12/42）SELF；roleId=1 超管 ALL；跨部门同事（如 12 看 21/31/41/42 名下客户）不可见。
- roleId=5 避开 0/1/2 特殊号与测试占用 2/3/4；V39 补授 608 给 roleId=5（FlywayMigrationIT Order2「业务角色全量获 608」守卫成立，实测 Order2 断言仍绿）。
- FlywayMigrationIT：EXPECTED_VERSIONS 增 "39"（25 版）；新增 @Order(11) V39 种子断言（120 客户 + 6 owner + 4 部门带 leader + role 5 + 608 授权）。
- 迁移实测：crm_dev_reingest 全新库全链 V1→V39 25 版 Successfully applied（2026-10-13 17:04），V39 语法/幂等/外键全通过；INSERT IGNORE 幂等复跑不冲突。

## 2. 任务组 2 · 生产回埋（本地 boot 真跑，DashScope 原生 embedding 真实外呼）

- 前置实测（回埋前）：crm_dev_reingest.customer_company(is_deleted=0)=120、sys_user=7、sys_dept=5；Qdrant 在线（crm_entity collection 存在，points=120 回埋后）；DASHSCOPE_API_KEY 探活成功（text-embedding-v3，dims=1024，极低成本）。
- 真跑回埋（POST /knowledge/customer/reingest，900 权限，admin token）：**total=120 / success=120 / failure=0**（2026-10-13 17:14）。
- 幂等复跑（2.4）：第二次 total=120 / success=120 / failure=0，Qdrant crm_entity points_count 首跑=120 → 复跑后仍=120（upsert 按 pointId 幂等，无重复向量）✓。
- 检索冒烟（2.3）：查询「锐进数据科技这家公司怎么样」→ top-1 resourceId=1015 锐进数据科技（score 0.7471）；「苏州工业园区的储能客户有哪些」→ top-2 命中 1015/1009（0.6655/0.6352）✓。
- token 与成本留痕（估算口径，见偏差披露 B）：
  - 原生端点直测校准：237 字符 → 152 tokens（1.56 字符/token，text-embedding-v3）；
  - 120 行语义文本总字符 29,294 → 估算 18,778 tokens/轮；
  - 成本（text-embedding-v3 0.0007 元/1K tokens，对齐 P-j 先例口径）：单轮 ≈ 0.0131 元；两轮（首跑+幂等复跑）≈ 0.0263 元；customer 评测语料（16 行×2 遍）≈ 0.0016 元；冒烟查询 5 次 ≈ 0.0002 元；合计 ≈ 0.028 元（分厘级，符合授权预算）。
- 执行方式留痕：`mvn spring-boot:run`（SPRING_PROFILES_ACTIVE=dev，SLZ_DASHSCOPE 原生 base 覆盖），见偏差披露 C。

## 3. 任务组 3 · 真跑评测双面

- 3.1/3.2 CustomerRetrievalBenchmarkIT（failsafe IT）：RAG_BENCHMARK_REAL=1 + DASHSCOPE_API_KEY 双门控 Assumptions 跳过；固定 16 行 customer 语料经生产摄取链（CustomerIngestionService）真嵌入；真 BusinessRetrievalService + ALL 档数据范围桩（权限面不混入质量评测）；10 例（C-01~C-07 语义匹配 行业/规模/区域 + C-08~C-10 词汇失配 同义不同词，对齐 MISMATCH 口径）；判卷复用 RagQualityEvaluator（recall/MRR/hit）；独立编号不并入知识库 63 例 fixture，SUITE_VERSION 不动。
- 3.3 customer 面首跑（2026-10-13 17:23，真跑）：**caseCount=10，meanRecallAtK=1.0，MRR=1.0，hitRate=1.0（10/10 全中），meanPrecisionAtK=0.2**（每例 1/5，符合单金锚预期），failureRate=0。报告 docs/rag-quality/customer-baseline-v1.json（CUSTOMER-SUITE-1.0，**不锚 fixture**，D2 观察期）。
  - 词汇失配 3 例（C-08 扫地机器人↔家用清洁机器人 / C-09 跨境电商ERP↔跨境贸易管理软件 / C-10 注塑模具↔塑料成型模具）全部 top-1 召回——语义桥接有效。
- 3.4 知识库面回归：RagRealRetrievalBenchmarkIT 真跑（63 例，SUITE 5.0），输出回归对拍文件 docs/rag-quality/kb-regression-v1.json（已随 005143a 提交 tracked），逐例对照 baseline-v6.json —— 漂移结论见下文。

## 4. 知识库面漂移对拍结论（3.4，真跑完成 2026-10-13 17:35）

- RagRealRetrievalBenchmarkIT 真跑 63 例（SUITE 5.0，run=V1，输出 docs/rag-quality/kb-regression-v1.json，未触 baseline-v6.json）。
- 本轮 vs baseline-v6 锚点：meanRecallAtK 0.96296 == 0.96296（一致）、hitRate 1.0 == 1.0、MRR 0.92857 vs 0.93651（Δ-0.008，> 0.95 房风下限）、meanCitationPrecision 0.96905 vs 0.97116（Δ-0.002）、meanAnswerConsistency 0.95503 == 0.95503、failureRate 0。
- **逐例漂移**：63 例 recall 逐例零漂移（Δrecall=0 全部）；8 例有 citP/ansC 微小抖动（±0.05~0.33，LLM 非确定性判卷/引用生成所致，非检索回退）：T-03/T-11/T-12/T-14/TB-08/M-01/M-02/M-04。
- **结论：add-customer-semantic-retrieval 合入后知识库面零回退**（recall 逐例一致、聚合均值全部在 5% 房风内）；本轮为旁证性回归跑，baseline-v6 锚定语义零改动。

## 5. 门禁与收尾（组 4，复核留证回填）

- 全量 mvn -B -ntp clean verify：surefire 1225/0/0/0（与基线持平，只增不减）。
- 四静态：checkstyle 0 / spotless 0 / spotbugs 0 / pmd 0。
- 三守卫：pmd-baseline / spotbugs-bijection / test-baseline。
- merge-gate 8/8。
- failsafe 白名单 Docker 真跑：FlywayMigrationIT（25 版含 V39 + Order11）+ CustomerReingestControllerIT 等全绿。

## 6. 偏差披露

- A. **回埋环境切库**：.env 指向的 crm_dev_smoke 存在**预存** Flyway V26 失败历史（success=0，501-504 旧 `report:*` 权限行与 V26 撞主键），与本卡无关、任何 boot 都会卡在该库；为不碰既有库，回埋在全新库 crm_dev_reingest（同 schema 链 V1→V39 全量重放，同一「本地 boot」语义）执行。裁决假设 3「执行方式由执行侧定」授权范围内。
- B. **token 计量口径**：spring-ai-alibaba 1.0.0.4 的原生 DashScope 响应 usage 经 ModelProviderImpl 未回流（corpusEmbeddingTotalTokens=0），token 留痕采用「原生端点直测校准 1.56 字符/token」估算，非逐行 API usage 精确值（成本量级分厘级，估算误差不改变结论）。
- C. **本地 boot 覆盖三项运行参数**（不落盘、不改代码/配置）：SPRING_AI_DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com（修预存配置怪癖：application.yml 的 spring.ai.dashscope.base-url=compatible-mode/v1 使原生 DashScopeApi 拼出 compatible-mode/v1/api/v1/... 404，属既有配置缺陷，回埋/评测均需原生 base）；KNOWLEDGE_STORAGE_PROVIDER=in-memory（免 MinIO）；MANAGEMENT_ENDPOINT_HEALTH_VALIDATE_GROUP_MEMBERSHIP=false（in-memory 存储下 readiness 组 minio 成员缺席）。
- D. **native 与 compatible-mode 向量空间不可比**（实测同文本 cos≈0.40）：生产检索与回埋均走原生端点，系统内部一致；仅手工冒烟脚本曾误用 compatible-mode（已改用同源原生嵌入）。评测链路（benchmark IT）自建 provider 走原生 base，与生产一致。
- E. 前端零改动（裁决假设 4 遵守）；已锚知识库 SUITE 5.0 / baseline-v6 fixture 零改动；既有迁移 V1-V38 零改动。

## 7. 代笔披露（2026-10-13）

- 本文件 3 处文档级 minor（L1「未跟踪」措辞、§3.4「scratch」措辞、§5 surefire [N] 占位符与「待回填」标题）由指导主 agent 代笔收口（owner 授权，b2 卡 42fb2f7 先例）；依据全部取自 A 档复核留证（work/tmp/acrb-review/ verify.log：surefire 1225/0/0/0 + failsafe 113/0/0/7），零新增实施。
- 可选第 4 项 minor（真跑 LOG 归档对齐 citation-forensics-v4 先例）未采纳：复核判定不阻塞，raw 已在 work/tmp/acrb-review/ 留证。
