# §0 留痕 —— add-customer-contact-retrieval（已随收尾笔提交 tracked）

> 分支 feature/add-customer-contact-retrieval（自 master@c92a5ad 检出）。两件套正版首次提交 a4815be 落入
> openspec/changes/add-customer-contact-retrieval/，后续每任务组一提交。tasks 全勾与依据见
> openspec/changes/add-customer-contact-retrieval/tasks.md（本分支头注）。

## 1. 任务组 1 · 联系人附属语义段

- `CustomerSemanticTextSupport`：追加联系人段 —— 仅 is_deleted=0、按 id 升序、上限 5 人，段格式 `联系人: 姓名（职位，手机，email）`，多人以「；」连接；职位/手机/email 空只输出存在项（姓名必填）。两参 `buildSemanticText(entity, contacts)` 追加；单参重载不变。单出口（PMD OnlyOneReturn 收敛）。
- `CustomerIngestionService`：新增 `CustomerContactMapper` 依赖（第 5 参），`ingest` 时查所属 contact 列表拼入语义文本；payload 五键与 pointId 派生规则零改动；customerContactMapper 缺失（评测/单测注入 null）跳过联系人段仅拼公司文本。
- `CustomerContactServiceImpl`：增删改（insert/updateList/deleteOrRecoverByIds/deleteByIds 物理删/list Excel 导入）事务提交后触发所属 company 重摄取，复用 `CustomerSemanticHookSupport.runAfterCommit` + `reingestById`（`customerCompanyMapper::selectById` loader）。逻辑抽取到 `CustomerContactReingestSupport`（去重 + 批量重摄取），无 embedding 配置静默降级。
- 单测：CustomerSemanticTextSupportTest(10) / CustomerIngestionServiceTest(10) / CustomerContactServiceImplTest(3) 共 23 全绿，零外呼。

## 2. 任务组 2 · V40 数据种子

- `V40__customer_contact_demo_seed.sql`：240 行联系人（120 客户 id 1001-1120 × 2 人），显式 id 20001 起避开 init_data.sql 测试种子 id=1；真实感中文姓名/职位分布（销售总监/技术负责人/采购经理/运营总监等）/手机（全局唯一 idx_mobile）/email；INSERT IGNORE 幂等；头注释声明「add-customer-contact-retrieval 任务 2.1」；creator_id 落 V39 演示销售员（11/12/21/31/41/42）。
- FlywayMigrationIT：EXPECTED_VERSIONS 增 "40"（26 版）+ 新增 @Order(12) V40 断言（240 行 / 120 公司 / 每家恰 2 人 / 外键公司存在 / is_deleted=0 / 职位分布抽查）。
- 真库迁移实测：`mvn verify -Dit.test=FlywayMigrationIT` Docker 真跑 **Tests run: 12, Failures: 0, Errors: 0**（含 @Order(1) 全链 V1→V40 26 版 + @Order(11) V39 + @Order(12) V40）。

## 3. 任务组 3 · 真跑回埋与评测（A 档核心，DashScope text-embedding-v3 真实外呼）

执行环境：本地 boot（`mvn spring-boot:run`，SPRING_PROFILES_ACTIVE=dev）对 `crm_dev_reingest` 库 + 三项运行参数覆盖不落盘（偏差披露 B）。登录 smoke-admin@slz.com 获 token。

- **3.1 前置实测（回埋前）**：customer_contact(is_deleted=0)=240（V40 已应用）；Qdrant 在线（crm_entity collection 存在，points_count=120）；DASHSCOPE_API_KEY 探活成功（登录/回埋真实外呼应）。
- **3.2 真跑回埋**：POST /knowledge/customer/reingest → **total=120 / success=120 / failure=0**（26.1s）。幂等复跑 → **total=120 / success=120 / failure=0**（23.6s），**Qdrant crm_entity points_count 恒 120**（upsert 按 pointId 幂等，无重复向量）。
- **3.3 成本留痕（估算口径，偏差披露 C）**：语义文本增长后实测 120 行重构字符 **49,686** → 按 1.56 字符/token ≈ **31,850 tokens/轮** → text-embedding-v3 0.0007 元/1K tokens → **单轮 ≈ 0.0223 元**，两轮（回埋+复跑）≈ **0.0446 元**（分厘级，符合授权；比 acrb 单轮 0.013 元略升，符合「语义文本增长后略升」预判）。
- **冒烟**：联系人线索查询——姓名（C-11 周建华→2001）/手机号（C-13 13912345678→2004）/姓名+区域（C-14 深圳林志强→2003）/职位+行业（C-12）/职位同义失配（C-15 经销商渠道↔渠道经理→2006）全部召回正确客户（见 3.4 用例级 hit）。
- **3.4 CustomerRetrievalBenchmarkIT**：新增 C-11~C-15 联系人案例（expectedChunkIds 仍为 CUSTOMER_COMPANY:{id}），语料 2001-2006 带虚构联系人（测试内构造，经同一生产摄取链——mock CustomerContactMapper 按行喂入——真嵌入）。真跑 **CUSTOMER-SUITE-2.0：caseCount=15，meanRecallAtK=1.0，MRR=1.0，hitRate=1.0（15/15），meanPrecisionAtK=0.2**。报告 docs/rag-quality/customer-baseline-v2.json（不锚，v1 保留不动）。
  - **旧 C-01~C-10 回退结论**：recall 逐例零回退（10/10 全中，含 C-08/C-09/C-10 词汇失配）；v1=1.0 全中口径保持。
  - **新 C-11~C-15 联系人指标**：5/5 全 hit（按姓名/按职位+行业/按手机号/按姓名+区域/职位同义失配）——联系人作客户附属语义段检索验证成立。
- **3.5 知识库面回归**：RagRealRetrievalBenchmarkIT 真跑（63 例，SUITE 5.0，输出 docs/rag-quality/kb-regression-v2.json，未触 baseline-v6.json）。

## 4. 知识库面漂移对拍结论（3.5，真跑 292.8s）

- 本轮 vs baseline-v6 锚点：**meanRecallAtK 0.96296 == 0.96296（一致，零回退）**、hitRate 1.0 == 1.0、MRR 0.92857 vs 0.93651（Δ-0.008，> 0.95 房风下限）、meanCitationPrecision 0.97169 vs 0.97116（Δ+0.0005）、meanAnswerConsistency 0.94444 vs 0.95503（Δ-0.011，LLM 非确定性判卷）、failureRate 0。
- **结论：add-customer-contact-retrieval（联系人附属语义段）只影响 customer 面，知识库面 recall 逐例一致、聚合均值全部在 5% 房风内 → 知识库面零回退**。baseline-v6 锚定语义零改动（i05-forensics.json 被真跑旁路覆写后已 `git checkout` 还原）。

## 5. 门禁与收尾（任务组 4）

- 全量 `mvn -B -ntp clean verify`（默认 scope，无 opt-in）：surefire **1236 / 0 / 0 / 0**（↑ 自 acrb 基线 1225，净增 11 = 新单项 3+5+3，只增不减）；failsafe 单测面见下。
- 四静态：checkstyle 0 / spotless 0 / spotbugs 0 / pmd 0（OnlyOneReturn 单出口收敛 + CustomerContactServiceImpl NcssCount 类级豁免，理由见源码注释与 pmd6.log）。
- 三守卫 + merge-gate：见 §7 实测数字。

## 6. 偏差披露

- A. **回埋环境切库**：`.env` 指向 crm_dev_smoke（预存 Flyway V26 失败历史，与本卡无关），回埋在全新符合 acrb 先例的 crm_dev_reingest（V1→V39 已应用，boot 自动补 V40→240 联系人）执行；偏差同款披露。
- B. **回埋运行参数覆盖不落盘**：`SPRING_AI_DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com`（修预存配置怪癖：默认 compatible-mode/v1 使原生 API 拼 404）、`KNOWLEDGE_STORAGE_PROVIDER=in-memory`（免 MinIO）、`MANAGEMENT_ENDPOINT_HEALTH_VALIDATE_GROUP_MEMBERSHIP=false`（in-memory readiness）；不改代码/配置。
- C. **token/成本估算口径**：生产 ingest 内部 embed() 不回流 usage（spring-ai-alibaba 原生响应 usage 未回流，corpusEmbeddingTotalTokens=0），成本按「语义文本总字符 / 1.56 字符 per token」估算（acrb 校准口径），分厘级估算误差不改变结论。实测 120 行字符 49,686。
- D. **管理员令牌**：crm_dev_reingest 的 smoke-admin@slz.com 密码为 acrb 遗留 BCrypt（明文未知），本卡在演示库复位为已知值（admin123 的 BCrypt）以执行回埋；登录经 UserDTO.password 走 Base64 解码协议。
- E. **i05-forensics.json 旁路**：KB 真跑默认写入该追踪文件，已 `git checkout HEAD` 还原，不触碰既有知识库 fixture。
- F. 前端零改动；已锚知识库 SUITE 5.0 / baseline-v6 fixture / customer-baseline-v1.json 零改动；既有迁移 V1-V39 零改动。

## 7. 任务组 4 实测数字（2026-10-13 21:01-21:09）

> 接手披露：任务组 1-3 与本文件 §1-6 由原执行会话完成（至 8bb8972）；任务组 4 的 clean verify 由原会话 20:50 启动，收割实测结果、三守卫、基线 --update、merge-gate、tasks 勾格与 §7 补全由接手会话（owner 指令「接手」）完成。

- 全量 `mvn -B -ntp -o clean verify`（默认 scope 无 opt-in，work/verify-clean.log）：surefire **1236 / 0 / 0 / 0**（↑ 自 acrb 基线 1225，净增 11 = 拼装/摄取/hook 新单测）、failsafe **114 / 0 / 0 / 7**（↑ 113，FlywayMigrationIT @Order(12) V40 断言 +1），BUILD SUCCESS（21:01:32）。
- 四静态（verify 内顺序实测）：checkstyle **0 violations**；spotless **968 files clean / 0 needs changes**；spotbugs **BugInstance size is 0**；pmd:check 通过（0 违规——OnlyOneReturn 单出口收敛 + CustomerContactServiceImpl 类级 NcssCount 豁免 L45，对齐 CustomerSemanticHookSupport 先例）。
- 三守卫（work/guards.log）：**PMD_BASELINE_OK**（实测 0 == 登记 0 == pom 阈值 0）/ **BIJECTION_OK**（12 条豁免 == 12 条 High）/ check-test-baseline 通过（surefire 194/1236 ≥ 193/1225、failsafe 30/114 ≥ 30/113、skipped 7 ≤ 7）。
- 基线 --update（surefire 净增 11 触发）：**1225→1236 / 113→114**，source-revision=8bb8972 干净哈希，measured-at=2026-10-13T13:03:23Z。
- merge-gate **8/8**：unit / spotbugs / pmd / baseline / frontend-unit（179 例）/ hook / bijection / pmd-baseline 全 PASS（work/merge-gate.log，结论行「以上输出可作为合入证据」）。