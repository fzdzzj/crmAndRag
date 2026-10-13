# tasks — add-customer-semantic-retrieval

> 红线：**权限闸**——无权 customer 绝不出现在检索结果（SELF/DEPT/DEPT_AND_CHILD/ALL 四档 + TAGE/共享 union 反向用例全覆盖，NONE 级业务检索直接空返回零外发）；AiChat 主链路不断流（业务检索异常 WARN+空业务段降级，不阻断主答）；SourceReference 解冻走卡内 spec-delta 登记（B1 先例），contracts-frozen.md 仅追加解冻记录条目；既有迁移 V1-V37 禁改；零新增 ErrorCode；零外呼（DASHSCOPE_API_KEY 置空 + mock embedding）；surefire 只增不减；四静态与全量 test 禁并发顺序跑；新代码中文 Javadoc 标注「add-customer-semantic-retrieval 任务 x.x」；PMD 超阈拆 Support 对齐先例并 §0 登记；merge-gate 8/8。

## 任务组 1 · 契约、配置与 V38

- [x] 1.1 `BusinessRetrievalPort`（server.ai.port，lane-local 对齐 KnowledgeRetrievalPort 先例）：`BusinessRetrievalResult retrieve(BusinessRetrievalQuery q)`；Query 携带 query 文本/userContext/topK；Result 复用 `SourceReference`（type=CUSTOMER）+ context 分节文本
      依据（2026-10-13 归档批逐格核验）：`src/main/java/com/slz/crm/server/ai/port/BusinessRetrievalPort.java` + Query/Result record 已落盘（提交 db2f94f，任务 1.1）；Query 携带 query/userContext/topK/numberStart 四字段，Result=context + sources(List<SourceReference>) + hitCount，SourceReference type=CUSTOMER 见 toSourceReference。
- [x] 1.2 SourceReference 受控解冻：sourceType 语义支持业务来源（type=CUSTOMER/id/name 复用现有字段），卡内 spec-delta 登记 + contracts-frozen.md 追加解冻记录条目（B1 先例，仅记录零语义改动）
      依据（2026-10-13 归档批逐格核验）：卡内 spec-delta 已落盘 `openspec/changes/add-customer-semantic-retrieval/specs/knowledge-rag/spec-delta.md`（db2f94f）；`openspec/changes/add-customer-semantic-retrieval/contracts-frozen.md` 已追加解冻记录条目（sourceType=custom 語义，SSE payload 零改动）；SourceReference javadoc 同步（331fb13 精简至 17 行过 CommentSize）。
- [x] 1.3 QdrantProperties 增 `crmEntityCollection`（默认 crm_entity，1024 维同 embedding 模型）；InMemoryVectorStore 兜底对应支持；collection 建制对齐 knowledge_chunk 既有机制（启动 ensure，§0 实测登记）
      依据（2026-10-13 归档批逐格核验）：`QdrantProperties.crmEntityCollection`（默认 crm_entity）+ `CrmEntityVectorStoreConfiguration`（qdrant 态启动 ensure、in-memory 态独立实例）已落盘（db2f94f）；CrmEntityVectorStore implements CrmVectorStore 双候选以主集合 @Primary 消歧（914e973，§0 偏差披露①）。
- [x] 1.4 V38__aichat_prompt_retrieval_slot.sql：UPDATE aichat-system-prompt content 加 `{{#slot:retrievedContext}}` 标记（version+1、history 快照、V21 起头注释格式）；aichat-session-title 零改动；FlywayMigrationIT 登记 V38（23→24）
      依据（2026-10-13 归档批逐格核验）：`V38__aichat_prompt_retrieval_slot.sql`（version=3、history version=3 UPDATE 快照、title 零改动）已落盘（db2f94f）；FlywayMigrationIT 登记 23→24 版（MIGRATIONS 数组）+ Order-10 V38 断言；Order-9 V37 用例改痕迹断言（终态由 V38 承担，331fb13）；Docker 真跑 FlywayMigrationIT 10/10 绿（it-whitelist-raw4.txt）。

## 任务组 2 · 摄取侧（D2 全量回埋 + 增量 hook）

- [x] 2.1 `CustomerIngestionService`：单条拼装语义文本（非空字段「字段名: 值」标签化，§0 登记最终口径）→ EmbeddingService.embed → CrmVectorStore.upsert（crm_entity）；payload 四键 entityType(String)/resourceId(Long)/ownerUserId(Long)/ownerDeptId(Long，经 owner_id 反查 sys_user.dept_id 反范式缓存，合冻结 metadata 约定）
      依据（2026-10-13 归档批逐格核验）：`CustomerIngestionService` + `CustomerSemanticTextSupport` 已落盘（9214ad0）；payload 实为五键（四键 + companyName 展示补充键，§0 披露）；ownerDeptId 经 owner_id 反查 sys_user.dept_id；point id 经 nameUUIDFromBytes 确定性派生幂等。
- [x] 2.2 全量回埋管理端点（POST，挂 `@RequirePermission(KNOWLEDGE_ADMIN_MANAGE 900)` 复用先例）：分页游标回埋（is_deleted=0 全量），返回成功/失败计数；PermissionCoverageScanner 新端点登记同步
      依据（2026-10-13 归档批逐格核验）：`POST /knowledge/customer/reingest`（@RequirePermission(900)，id 游标分页 200/批，ReingestResult{total,success,failure}）已落盘（9214ad0）；PermissionCoverageScanner CONTROLLER_REGISTRY 已含 KnowledgeAdminController（SECURED 档，PermissionCoverageAuditIT 2/2 真跑绿）；端点 ControllerIT 补件（331fb13，900 正反向 2/2 真跑绿）。
- [x] 2.3 customer 增删改 Service 层 hook：新增/修改 → 同步 upsert（含 ownerDeptId 刷新）；软删 is_deleted=1 → 连带 delete 向量；hook try-catch WARN 不阻断业务主流程（回埋端点兜底）；执行事务语义 §0 实测登记（事务后同步 or 同事务）
      依据（2026-10-13 归档批逐格核验）：CustomerCompanyServiceImpl 九处 hook（add/updateList/deleteOrRecover/deleteByIds/deleteById/logicalDelete/batchLogicalDelete/级联删/merge）已落盘（9214ad0）；事务语义=事务激活注册 afterCommit（提交后同步、回滚不写向量）、无事务立即同步（§0 实测登记）；闸拆 CustomerSemanticHookSupport（331fb13，NcssCount 拆分行为等价）。
- [x] 红测先行：拼文本口径与 payload 构造用例、hook 触发路径（捕获「功能未接入」基线），留痕 work/ 日志域
      依据（2026-10-13 归档批逐格核验）：骨架期 UnsupportedOperationException 基线红 12 失败/错误（CustomerIngestionServiceTest 9 + CustomerSemanticTextSupportTest 3）→ 实现后三测试类 20/20 全绿、surefire 净增 12；留痕 `work/add-customer-semantic-retrieval-exec-log.md` 任务组 2 段。

## 任务组 3 · 检索侧与 AiChat 集成（D1 双源 + D3 槽位化）

- [x] 3.1 `BusinessRetrievalService` 实现 `BusinessRetrievalPort`：权限闸四档分支（SELF→filter ownerUserId match；DEPT→ownerDeptId match 本部门；DEPT_AND_CHILD→可见部门集合 match；ALL→无 filter；NONE→空返回零外发）+ TAGE/共享补充召回（getTageResourceIds/getSharedResourceIds → resourceId match 多值 union）+ 向量 search + 结果转 SourceReference。**效果语义（owner 口径）**：归属人自己 / 上司（部门或子树档）/ 管理员（ALL）可检索，无档位覆盖的同事检索不到；检索权限与页面/SQL 权限（QueryWrapperAspect）严格一致
      依据（2026-10-13 归档批逐格核验）：`BusinessRetrievalService` 全量实现（243caa2）；四档 filter 分支 + NONE 零外发（不 embed 不 search）+ TAGE 补充召回对 TAGE/DEPT/DEPT_AND_CHILD 档恒开/共享补充召回非 ALL 档恒开（修正笔对齐页面列表 SQL 超集不变量，见 §0 偏差披露② 与修正记档）；15 单测全绿（四角色场景 + 反向）。
- [x] 3.2 AiChat 双源合并：AiChatServiceImpl 检索调用处并行/串行知识库检索（既有不动）+ 业务检索（新），跟随 useKnowledgeBase 开关；context 分节合并、sources 合并发 SSE；业务检索异常 WARN+空业务段降级（回归锁：降级后主答流程逐字节同旧路径）
      依据（2026-10-13 归档批逐格核验）：AiChatKnowledgeRetrievalService 5 参 retrieve 双源合并（知识库 sources.size()+1 接续编号、sources 合并）+ WARN+null 降级链（243caa2）；AiChatServiceImpl dispatchChatStream 接线；13 单测全绿（含 5 个降级链用例 + 7 个新用例）。
- [x] 3.3 槽位化注入：PromptSystemPromptSegments.buildSlots 增产 retrievedContext（值=分节组装检索上下文成品，空检索=空串）；模板无标记/回退态走旧路径逐字节不变（回归锁断言）
      依据（2026-10-13 归档批逐格核验）：buildSlots 尾部置 retrievedContext（clarify 之后，空检索=空串）+ AiChatPromptService.buildMessages 3 参槽位化（injected 旗标，非槽位/回退态逐字节旧路径）（243caa2）；19 单测全绿（含 legacyAppend/fallbackState 回归锁）。
- [x] 红测先行：权限矩阵反向用例（四角色场景：归属人自己可检 / SELF 档同事检索不到他人客户 / DEPT 档上司可检下属部门 / 管理员 ALL 全可检 / NONE 级零外发 / TAGE 绑定与共享资源不漏）+ 降级链用例，留痕 work/ 日志域
      依据（2026-10-13 归档批逐格核验）：骨架红 19 失败/错误（BusinessRetrievalService 14 ERROR + AiChatKnowledgeRetrievalService 2F + AiChatPromptService 1F + AiChatServiceImpl 2F）→ 绿 78/78（14+13+19+6+26），净增 24；留痕 `work/add-customer-semantic-retrieval-exec-log.md` 任务组 3 段（raw：red-run-group3-raw.txt / green-run-group3-raw.txt）。

## 任务组 4 · 测试与门禁

- [x] 4.1 单测：CustomerIngestionService（拼文本/payload/mock embedding 桩）+ BusinessRetrievalService（四档 + TAGE/共享 union + NONE 空返回，InMemory 向量桩；四角色场景全绿：归属人/SELF 同事/DEPT 上司/管理员）+ AiChat 合并/降级 + 槽位注入回归锁（全档用例全绿）
      依据（2026-10-13 归档批逐格核验）：五测试类 78/78 全绿（CustomerIngestion 12 + SemanticText 8 + BusinessRetrieval 15 + AiChatKnowledgeRetrieval 13 + AiChatPrompt 19 + AiChatServiceImpl 26 + RetrievalParamTruthSource 6 归属组内）；mock embedding 桩（embed→固定 1024 维）；四角色反向用例全覆盖。
- [x] 4.2 IT：回埋端点 ControllerIT（权限 900 正反向）+ FlywayMigrationIT（24 版含 V38）+ 权限矩阵端到端（Docker 在线真跑 IT 白名单，对齐先例）
      依据（2026-10-13 归档批逐格核验）：Docker 29.6.2 在线（`docker info` 实测）failsafe 真跑白名单 20/20 全绿（CustomerReingestControllerIT 2/2 + FlywayMigrationIT 10/10 含 V38 共 24 版 + PermissionControllerIT 3/3 + PermissionsInterceptorStatusIT 3/3 + PermissionCoverageAuditIT 2/2，raw：work/tmp/casr/it-whitelist-raw4.txt）；回埋 ControllerIT 属任务组 2 缺口补件（331fb13）。
- [x] 4.3 全量 mvn -B -ntp test 0 失败；四静态顺序跑；三守卫；check-test-baseline **--update 真实收紧**（1151 陈旧漂移历卡累积 → 本卡实测值收紧，只增语义保持）；merge-gate 8/8
      依据（2026-10-13 归档批逐格核验）：全量 test 1224 用例 0 失败 0 错误 0 跳过 BUILD SUCCESS（两轮，raw：full-test-group4-final-raw.txt / full-test-after-pmdfix-raw.txt）；clean verify failsafe 111 用例 0 失败 6 跳过（raw：full-verify-group4-raw.txt）；四静态顺序 checkstyle/spotbugs/spotless/pmd 全 BUILD SUCCESS（raw：static-four-group4-raw.txt）；PMD 5 条超阈全修清零不抬阈（331fb13，§0 偏差披露③）；三守卫全绿（PMD_BASELINE_OK 0==0==0 / BIJECTION_OK 12==12 / test-baseline 通过）；--update 真实收紧 1151→1224（surefire）/105→111（failsafe），source-revision=331fb13；merge-gate 8/8（raw：merge-gate-group4-raw.txt）。**修正笔复跑**（DEPT/DEPT_AND_CHILD tage 补充召回）：全量 test 1225 用例 0 失败 0 错误 0 跳过 BUILD SUCCESS（raw：full-test-fix-raw.txt）；四静态顺序 checkstyle/spotbugs/spotless/pmd 全 exit 0（raw：static-checkstyle.txt / static-spotbugs.txt / static-spotless.txt / static-pmd.txt）；三守卫绿（raw：guard-pmd-baseline.txt / guard-bijection.txt / guard-baseline.txt）；基线再 --update 1224→1225（surefire）/111（failsafe），source-revision=30d09f5-dirty；merge-gate 8/8（raw：merge-gate-fix.txt）。
- [x] 4.4 提交按任务组分笔 type(scope): 中文描述；tasks 全勾 + §0 留痕；停步回报（不合并不 push）
      依据（2026-10-13 归档批逐格核验）：任务组分笔 + 收口笔 + 本次修正笔，均见 §0 各笔提交清单；本文件勾选与 §0 同笔收口；停步回报待 owner 复核，不合并不 push。

## §0 执行留痕

- 分支：`feature/add-customer-semantic-retrieval`（权威树 master `a064c6b` 检出）
- 各笔提交：
  - `db2f94f` feat(ai): 任务组1 业务检索端口、crm_entity 向量装配与 V38 槽位标记
  - `9214ad0` feat(customer): 任务组2 客户语义摄取、全量回埋端点与增删改 hook
  - `243caa2` feat(aichat): 任务组3 业务检索权限闸、AiChat 双源合并与槽位化注入
  - `914e973` fix(knowledge): 任务组4 全量测试修复——向量库主集合 @Primary 消歧与前后端契约同步
  - `331fb13` fix(customer): 任务组4 PMD 清零与 IT 白名单补件——hook 拆 Support、回埋 ControllerIT、迁移断言终态适配、零外呼桩
  - `30d09f5` chore(test): 任务组4 门禁基线收紧与 tasks 勾选 §0 留痕
  - （修正笔）fix(aichat): DEPT/DEPT_AND_CHILD 档补 TAGE 补充召回 + 用例矩阵与 Javadoc 口径同步（哈希回填于停步回报）
- 拼文本口径实测结论：非空字段「字段名: 值」以「；」拼接（`CustomerSemanticTextSupport.buildSemanticText`），grade>0 才入 grade 字段；空语义文本抛 IllegalArgumentException 跳过嵌入（由 hook WARN、回埋计失败）。
- hook 事务语义实测结论：`TransactionSynchronizationManager.isSynchronizationActive()` 为真注册 afterCommit（**事务提交后**同步，回滚不写向量）；无事务立即同步。闸拆 `CustomerSemanticHookSupport`（331fb13）后语义逐字节等价。IT 侧实证：CustomerCompanyDeptUniqueIT（无 @Transactional 真实提交）走立即同步路径（@MockitoBean 桩 EmbeddingService 防外呼）。
- 权限闸 filter 实测结论（四档 + TAGE/共享）：NONE→empty 零外发（不 embed 不 search，用例断言 vectorStore 零交互）；ALL→仅 entityType filter；SELF/TAGE→ownerUserId=userId；DEPT→ownerDeptId=deptId；DEPT_AND_CHILD→子树逐 deptId union（DataScopeDepartmentResolver 递归，MP 3.5.x eq 参数惰性绑定按调用序 mock 实证）；TAGE 补充召回对 TAGE/DEPT/DEPT_AND_CHILD 档恒开、SELF 档不补（修正笔对齐页面列表 SQL「部门归属 OR 标签资源 OR 显式共享」超集不变量）、共享补充召回非 ALL 档恒开；merge chunkId 去重取高分→score 降序→limit topK；resourceType=customer_company。
- 偏差披露：
  - ① 主集合 @Primary 消歧（914e973）：设计定见「知识库既有装配零改动」的必要例外——CrmEntityVectorStore implements CrmVectorStore 后按类型注入双候选（NoUniqueBeanDefinitionException 实爆 Smoke/Prometheus/OpenApi 三测试类），取最小侵入方案而非改消费方 @Qualifier。
  - ② TAGE 补充召回档位范围（**已被指导侧部分否决并于修正笔更正**）：原实现仅 TAGE 档补查（镜像 canReadResource 单条判权）；复核认定与页面列表 SQL 语义割裂——`DataScopeServiceImpl#addDeptScopeCondition` L215/L220 白纸黑字「部门归属 OR 标签资源 OR 显式共享」超集不变量（A3），DEPT 档用户页面看得到 tage 绑定客户而助手检索不到（保守不泄漏但违反「页面看得到检索得到」锚定）。修正笔：DEPT/DEPT_AND_CHILD 档补 TAGE 召回，SELF 档保持不补（SQL 层 SELF 无 tage），共享恒开不变。
  - ③ payload 五键（四键 + companyName）：SourceReference.name 需 company_name 展示名（String keyword 合 B4）。
  - ④ SELF 档锚 owner_id（SQL 先例 userFields=creator_id）：owner 口径拍板优先，§0 登记。
  - ⑤ FlywayMigrationIT Order-9 V37 用例改痕迹断言：任务组 1 登记 V38 时漏改的旧终态断言（该 IT 首次 Docker 真跑暴露），V1-V37 迁移脚本零改动（改的只是测试断言）。
  - ⑥ 回埋 ControllerIT 属任务组 2 漏件（9214ad0 未落），4.2 补件（331fb13）。
  - ⑦ 模块归属偏差：`CustomerIngestionService` 实际落 `knowledge/ingestion`（并 import server 域 mapper），proposal 原文写「server 域」；实测 knowledge→server 依赖有 9 个既有先例（KnowledgeRetrievalServiceImpl/DocumentIngestionService 等），耦合方向不违先例——指导侧裁「可接受，补披露即可」。
- 门禁 raw 关键行：
  - 全量 test：`Tests run: 1224, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（full-test-after-pmdfix-raw.txt；首轮 full-test-group4-final-raw.txt 同数字）
  - 全量 test（修正笔复跑）：`Tests run: 1225, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（full-test-fix-raw.txt；BusinessRetrievalServiceTest 15/15）
  - clean verify：`Tests run: 111, Failures: 0, Errors: 0, Skipped: 6`（failsafe）+ `BUILD SUCCESS`（full-verify-group4-raw.txt）
  - IT 白名单：`Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`（it-whitelist-raw4.txt，Docker 29.6.2）
  - 四静态：checkstyle/spotbugs/spotless/pmd 四段 `BUILD SUCCESS`（static-four-group4-raw.txt）
  - 四静态（修正笔复跑）：checkstyle/spotbugs/spotless/pmd 四段 exit 0（static-checkstyle.txt / static-spotbugs.txt / static-spotless.txt / static-pmd.txt）
  - 三守卫：`RESULT=PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）` / `RESULT=BIJECTION_OK（12 条豁免与 12 条 High 一一对应）` / `回归基线门禁通过`
  - 三守卫（修正笔复跑）：`RESULT=PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）` / `RESULT=BIJECTION_OK（12 条豁免与 12 条 High 一一对应）` / `回归基线门禁通过`（raw：guard-pmd-baseline.txt / guard-bijection.txt / guard-baseline.txt）
  - 基线收紧：2026-10-13 首轮 `surefire.reports=193 surefire.tests=1224 surefire.skipped=0 / failsafe.reports=29 failsafe.tests=111 failsafe.skipped=6`（source-revision=331fb13）；**修正笔再 --update 收紧为** `surefire.tests=1225`（source-revision=30d09f5-dirty，scripts/test-baseline.txt）
  - merge-gate：8/8 PASS（merge-gate-group4-raw.txt：unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline）
  - merge-gate（修正笔复跑）：8/8 PASS（merge-gate-fix.txt，同上八项；[it] 默认跳过按设计）
