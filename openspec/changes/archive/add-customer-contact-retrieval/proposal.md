# proposal — add-customer-contact-retrieval

> customer_contact 接入检索：联系人作客户附属语义段，检索语义面从「公司」扩展到「公司+联系人」。

## 1. 背景

add-customer-retrieval-baseline（master c92a5ad 合入）闭环了 customer 检索运营基线：V39 演示种子 120 行、真跑回埋 120/120、CustomerRetrievalBenchmarkIT 10 例首跑 recall=1.0（CUSTOMER-SUITE-1.0 不锚）、知识库面零回退。但检索语义文本只含公司面字段（名称/行业/规模/区域/备注），存在一个语义缺口：

1. **联系人零接入**：真实销售查询常以联系人为线索——「张三对接的那家苏州储能公司」「手机尾号 2233 的那个客户」——当前语义文本无联系人信息，此类查询零召回；customer_contact 表与 CRUD 面完整存在（V1 L146-174、Controller/ServiceImpl/Mapper），但摄取链从未读取。

## 2. owner 拍板记录（2026-10-13）

- **D1 种子路径**：V40 数据种子——Flyway V40 种 ~240 行真实感联系人（120 客户×2），对称 V39「种子进 Flyway 正式数据」先例；放开「零迁移」字面表述（纯数据迁移、零 schema 改动，「零新表」仍成立），换取生产/评测/回埋三面同源可复现。
- **D2 评测口径**：并入现有 IT——CustomerRetrievalBenchmarkIT 新增 4-5 例联系人查询（expectedChunkIds 仍为 CUSTOMER_COMPANY:{id}），旧 10 例复跑验证语义变化后零回退，报告升 docs/rag-quality/customer-baseline-v2.json（CUSTOMER-SUITE-2.0，不锚，v1 报告保留不动）。
- **API 成本授权**：附属语义段方案下语义文本必然变化 → 120 行必须真跑重埋 + 评测真跑，直接授权；量级参照 acrb 实测口径（回埋单轮 ≈ 0.013 元、语义文本增长后略升），预计分厘级，执行后按实测留痕。
- **方案定案**：附属语义段（联系人拼入客户语义文本、同 pointId、复用 crm_entity）——否决 contact 独立 pointId 方案（该方案需扩权限过滤面/膨胀 Qdrant 点数/评测 expectedChunkIds 破坏性变更）。

## 3. 范围（四个任务组）

- **任务组 1 · 摄取链附属语义段**：CustomerSemanticTextSupport 语义文本追加联系人段（is_deleted=0、按 id 升序、上限 5 人防语义膨胀、格式确定）；CustomerIngestionService 摄取时查所属 contact 列表拼入（payload/pointId 规则零改动）；CustomerContactServiceImpl 增删改（含软删与 Excel 导入路径）事务提交后触发所属 company 重摄取（复用 CustomerSemanticHookSupport runAfterCommit + reingestById 模式）；单测覆盖（拼装确定性/软删过滤/hook 事务后语义/无 embedding 环境不致既有 IT 失败）。
- **任务组 2 · V40 数据种子**：Flyway V40（下一可用号以 `ls src/main/resources/db/migration | sed 's/__.*//' | sort -V | tail -1` 实测为准）种 240 行联系人，全部挂 V39 客户 id 1001-1120，真实感姓名/职位/手机/email，INSERT IGNORE 幂等；FlywayMigrationIT EXPECTED_VERSIONS 增版 + Order 断言（240 行、外键公司存在）；V40 显式 id 避开测试种子，既有 IT 全量验证不破坏。
- **任务组 3 · 真跑回埋与评测（A 档核心）**：前置实测（contact COUNT / Qdrant 在线 / KEY 探活）→ 真跑重埋 120 行（语义文本变化后）+ 幂等复跑（Qdrant points 恒 120）→ token/成本留痕 + 联系人线索冒烟（姓名/职位/手机查询召回正确客户）→ CustomerRetrievalBenchmarkIT 新增 4-5 例（C-11~C-15 按姓名/职位/手机/组合查客户），真跑全量（旧 10 例 recall 逐例零回退 + 新例指标），报告 v2 不锚 → 知识库面回归真跑对照 baseline-v6 零回退。
- **任务组 4 · 门禁与收尾**：全量 test（surefire 只增不减）/四静态/三守卫/merge-gate；基线 --update 仅当用例数净增；§0 留痕（回埋实测/token 成本/评测 v2 指标/旧例回退结论/知识库面漂移结论/偏差披露）。

## 4. 红线

- **授权范围限定**：真实外呼仅限本卡任务组 3 的回埋与评测链路；其余全部测试保持零外呼（DASHSCOPE_API_KEY 置空 + mock 桩），真跑 IT 保持 RAG_BENCHMARK_REAL opt-in 门控默认跳过。
- **已锚基线禁动**：知识库面 SUITE 5.0 / baseline-v6 fixture 零改动；customer-baseline-v1.json 报告保留不动（历史留痕）；既有迁移 V1-V39 禁改。
- **pointId 规则不变**：向量 ID 恒为 CUSTOMER_COMPANY:{id}（UUID.nameUUIDFromBytes 派生），Qdrant crm_entity 点数恒 120（upsert 覆盖语义），零新增向量记录形态。
- **权限面零新增**：零新端点、零新权限常量、零注解变更（contact CRUD 既有注解不动；PermissionCoverageScanner 无新 controller 登记需求）；权限四档演示语义不弱化。
- **surefire 只增不减**；新代码中文 Javadoc 标注「add-customer-contact-retrieval 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8。

## 5. 裁决假设（owner 可否决）

1. 联系人段上限 5 人（按 id 升序）——V39 客户每家 2 人实际不触上限，上限仅为防未来语义膨胀的保险丝；
2. 旧 10 例回退判定口径 = recall 逐例零回退（v1=1.0 全中），MRR/citP 微抖在 5% 房风内——语义段只增信息不删信息，理论召回只优不劣；
3. 回埋执行环境沿用 acrb 先例（本地 boot + crm_dev_reingest 库 + 三项运行参数覆盖不落盘 + 原生 base 修 404 配置怪癖，偏差同款披露）；
4. contact hook 在无 embedding 配置的测试环境静默降级，不致既有 IT 失败（对齐公司面 hook 同款语义）；
5. 前端零改动。

## 6. 验收

- 任务组 1-4 全勾且每勾留痕；回埋实测（total/success/failure/token/成本）在案；评测双面留痕（customer v2 指标含旧例零回退结论 + 知识库面零回退结论）；门禁全绿（全量 test 0 失败 + 四静态 0 + 三守卫 + merge-gate 8/8）；surefire 只增不减；Qdrant 点数恒 120。
