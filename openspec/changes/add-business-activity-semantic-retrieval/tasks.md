# add-business-activity-semantic-retrieval — 任务清单

> 提交粒度与任务组对齐：每任务组一笔提交，分支 `feature/add-business-activity-semantic-retrieval` 自权威树 master 检出。
> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。

## 任务 1 · 契约解冻与 spec-delta（契约层）

- [x] 1.1 卡内 `specs/knowledge-rag/spec-delta.md` 落地：声明 `SourceReference.sourceType` 支持 `"BUSINESS_ACTIVITY"`，明确 documentId/filename/chunkId 字段映射规约
      依据：git diff openspec/changes/add-business-activity-semantic-retrieval/specs/knowledge-rag/spec-delta.md 确认契约增量声明
- [x] 1.2 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 追加受控解冻变更条目（对齐 B1 先例）
      依据：git diff spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md 确认变更日志追记解冻条目

## 任务 2 · 商业活动语义摄取服务（BusinessActivityIngestionService）

- [x] 2.1 新增 `BusinessActivitySemanticTextSupport` 与 `BusinessActivityIngestionService`：富文本拼装（标题/类型/客户/商机/沟通内容/备注/人员）、调用 `EmbeddingService.embed`、打标 Payload（entityType="BUSINESS_ACTIVITY", resourceId, ownerUserId, ownerDeptId, companyId, opportunityId）并写入 `CrmEntityVectorStore`
      依据：git diff src/main/java/com/slz/crm/knowledge/ingestion/ 确认摄取逻辑与 Payload 键
- [x] 2.2 增量 Hook：在 `BusinessActivityServiceImpl` 新增/更新/删除处通过 `BusinessActivitySemanticHookSupport.runAfterCommit` 注册 Spring 事务提交回调，实现异步重嵌入（`reingestById`）与删除（`deleteById`）
      依据：git diff src/main/java/com/slz/crm/server/service/impl/BusinessActivityServiceImpl.java 确认事务钩子注册
- [x] 2.3 存量回埋：新增全量回埋端点 `POST /business/activity/reingestAll`，挂既有注解 `@RequirePermission(PermissionOperates.SALES_CREATE_BUSINESS_ACTIVITY)`
      依据：git diff src/main/java/com/slz/crm/server/controller/BusinessActivityController.java 确认端点挂注解与注册表同步

## 任务 3 · 业务数据检索服务多实体扩展（BusinessRetrievalService）

- [x] 3.1 权限闸接入 `business_activity`：调用 `dataScopeService.getHighestDataScopeLevel(user, "business_activity")`，执行 NONE 零外发 / SELF / DEPT / DEPT_AND_CHILD / ALL 过滤与 TAGE/共享补充召回
      依据：git diff src/main/java/com/slz/crm/server/service/impl/BusinessRetrievalService.java 确认权限分支与向量过滤参数
- [x] 3.2 客户与商业活动双实体多路召回与合并：同轮检索同时召回 `CUSTOMER_COMPANY` 与 `BUSINESS_ACTIVITY`，按相似度降序截断 TopK，来源标注 `type=BUSINESS_ACTIVITY`，组装 `【业务数据检索】` 上下文
      依据：git diff src/main/java/com/slz/crm/server/service/impl/BusinessRetrievalService.java 确认多实体合并与来源封装
- [x] 3.3 槽位注入与 AiChat 集成回归验证：确保无标记/回退态与纯知识库路径逐字节一致，编号自动顺延
      依据：mvn -B -ntp test -Dtest=AiChatKnowledgeRetrievalServiceTest 退出码 0（13 全绿；业务零命中/业务端口未就绪两用例断言 context 与 sources 与纯知识库路径逐字节一致，编号自 numberStart 顺延）

## 任务 4 · 单元测试与门禁收尾

- [x] 4.1 新增业务活动检索权限反向测试（四角色全覆盖）：归属人本人可查、本部门上级可查、跨部门同事绝不可查、管理员全可查
      依据：mvn -B -ntp test -Dtest=BusinessRetrievalServiceTest 退出码 0（18 全绿，含四角色矩阵与 SELF 跨部门反向用例）
- [x] 4.2 摄取拼装与全量回埋 Mock 单测（纯 JVM，零真跑外呼）
      依据：mvn -B -ntp test -Dtest=BusinessActivityIngestionServiceTest 退出码 0（12 全绿）+ BusinessActivitySemanticTextSupportTest（9 全绿）
- [x] 4.3 `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1290），树净时单次 `--update` 基线
      依据：mvn -B -ntp clean verify 退出码 0，surefire 1314（净增 24：ingestion 12 + text-support 9 + retrieval 3），check-test-baseline.sh --update 写入 1290→1314（source-revision=07e011e 树净，无 -dirty）
- [x] 4.4 四静态（checkstyle/spotbugs/spotless/pmd）顺序跑全绿 + 三守卫 + `scripts/merge-gate.sh` 退出码 0
      依据：bash scripts/merge-gate.sh 退出码 0 全绿（8/8：unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline），证据留档于 work/tmp/add-business-activity-semantic-retrieval/
