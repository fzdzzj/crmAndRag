# tasks — add-customer-semantic-retrieval

> 红线：**权限闸**——无权 customer 绝不出现在检索结果（SELF/DEPT/DEPT_AND_CHILD/ALL 四档 + TAGE/共享 union 反向用例全覆盖，NONE 级业务检索直接空返回零外发）；AiChat 主链路不断流（业务检索异常 WARN+空业务段降级，不阻断主答）；SourceReference 解冻走卡内 spec-delta 登记（B1 先例），contracts-frozen.md 仅追加解冻记录条目；既有迁移 V1-V37 禁改；零新增 ErrorCode；零外呼（DASHSCOPE_API_KEY 置空 + mock embedding）；surefire 只增不减；四静态与全量 test 禁并发顺序跑；新代码中文 Javadoc 标注「add-customer-semantic-retrieval 任务 x.x」；PMD 超阈拆 Support 对齐先例并 §0 登记；merge-gate 8/8。

## 任务组 1 · 契约、配置与 V38

- [ ] 1.1 `BusinessRetrievalPort`（server.ai.port，lane-local 对齐 KnowledgeRetrievalPort 先例）：`BusinessRetrievalResult retrieve(BusinessRetrievalQuery q)`；Query 携带 query 文本/userContext/topK；Result 复用 `SourceReference`（type=CUSTOMER）+ context 分节文本
- [ ] 1.2 SourceReference 受控解冻：sourceType 语义支持业务来源（type=CUSTOMER/id/name 复用现有字段），卡内 spec-delta 登记 + contracts-frozen.md 追加解冻记录条目（B1 先例，仅记录零语义改动）
- [ ] 1.3 QdrantProperties 增 `crmEntityCollection`（默认 crm_entity，1024 维同 embedding 模型）；InMemoryVectorStore 兜底对应支持；collection 建制对齐 knowledge_chunk 既有机制（启动 ensure，§0 实测登记）
- [ ] 1.4 V38__aichat_prompt_retrieval_slot.sql：UPDATE aichat-system-prompt content 加 `{{#slot:retrievedContext}}` 标记（version+1、history 快照、V21 起头注释格式）；aichat-session-title 零改动；FlywayMigrationIT 登记 V38（23→24）

## 任务组 2 · 摄取侧（D2 全量回埋 + 增量 hook）

- [ ] 2.1 `CustomerIngestionService`：单条拼装语义文本（非空字段「字段名: 值」标签化，§0 登记最终口径）→ EmbeddingService.embed → CrmVectorStore.upsert（crm_entity）；payload 四键 entityType(String)/resourceId(Long)/ownerUserId(Long)/ownerDeptId(Long，经 owner_id 反查 sys_user.dept_id 反范式缓存，合冻结 metadata 约定）
- [ ] 2.2 全量回埋管理端点（POST，挂 `@RequirePermission(KNOWLEDGE_ADMIN_MANAGE 900)` 复用先例）：分页游标回埋（is_deleted=0 全量），返回成功/失败计数；PermissionCoverageScanner 新端点登记同步
- [ ] 2.3 customer 增删改 Service 层 hook：新增/修改 → 同步 upsert（含 ownerDeptId 刷新）；软删 is_deleted=1 → 连带 delete 向量；hook try-catch WARN 不阻断业务主流程（回埋端点兜底）；执行事务语义 §0 实测登记（事务后同步 or 同事务）
- 红测先行：拼文本口径与 payload 构造用例、hook 触发路径（捕获「功能未接入」基线），留痕 work/ 日志域

## 任务组 3 · 检索侧与 AiChat 集成（D1 双源 + D3 槽位化）

- [ ] 3.1 `BusinessRetrievalService` 实现 `BusinessRetrievalPort`：权限闸四档分支（SELF→filter ownerUserId match；DEPT→ownerDeptId match 本部门；DEPT_AND_CHILD→可见部门集合 match；ALL→无 filter；NONE→空返回零外发）+ TAGE/共享补充召回（getTageResourceIds/getSharedResourceIds → resourceId match 多值 union）+ 向量 search + 结果转 SourceReference。**效果语义（owner 口径）**：归属人自己 / 上司（部门或子树档）/ 管理员（ALL）可检索，无档位覆盖的同事检索不到；检索权限与页面/SQL 权限（QueryWrapperAspect）严格一致
- [ ] 3.2 AiChat 双源合并：AiChatServiceImpl 检索调用处并行/串行知识库检索（既有不动）+ 业务检索（新），跟随 useKnowledgeBase 开关；context 分节合并、sources 合并发 SSE；业务检索异常 WARN+空业务段降级（回归锁：降级后主答流程逐字节同旧路径）
- [ ] 3.3 槽位化注入：PromptSystemPromptSegments.buildSlots 增产 retrievedContext（值=分节组装检索上下文成品，空检索=空串）；模板无标记/回退态走旧路径逐字节不变（回归锁断言）
- 红测先行：权限矩阵反向用例（四角色场景：归属人自己可检 / SELF 档同事检索不到他人客户 / DEPT 档上司可检下属部门 / 管理员 ALL 全可检 / NONE 级零外发 / TAGE 绑定与共享资源不漏）+ 降级链用例，留痕 work/ 日志域

## 任务组 4 · 测试与门禁

- [ ] 4.1 单测：CustomerIngestionService（拼文本/payload/mock embedding 桩）+ BusinessRetrievalService（四档 + TAGE/共享 union + NONE 空返回，InMemory 向量桩；四角色场景全绿：归属人/SELF 同事/DEPT 上司/管理员）+ AiChat 合并/降级 + 槽位注入回归锁（全档用例全绿）
- [ ] 4.2 IT：回埋端点 ControllerIT（权限 900 正反向）+ FlywayMigrationIT（24 版含 V38）+ 权限矩阵端到端（Docker 在线真跑 IT 白名单，对齐先例）
- [ ] 4.3 全量 mvn -B -ntp test 0 失败；四静态顺序跑；三守卫；check-test-baseline **--update 真实收紧**（1151 陈旧漂移历卡累积 → 本卡实测值收紧，只增语义保持）；merge-gate 8/8
- [ ] 4.4 提交按任务组分笔 type(scope): 中文描述；tasks 全勾 + §0 留痕；停步回报（不合并不 push）

## §0 执行留痕

- 分支：`feature/add-customer-semantic-retrieval`（权威树 master `a064c6b` 检出）
- 各笔提交：
  - （待执行回填）
- 拼文本口径实测结论：
- hook 事务语义实测结论：
- 权限闸 filter 实测结论（四档 + TAGE/共享）：
- 偏差披露：
- 门禁 raw 关键行：
