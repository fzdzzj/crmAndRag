# add-sales-opportunity-semantic-retrieval — 任务清单

> 提交粒度与任务组对齐：每任务组一笔提交，分支 `feature/add-sales-opportunity-semantic-retrieval` 自权威树 master 检出。
> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。

## 任务 1 · 契约解冻与 spec-delta（契约层）

- [x] 1.1 卡内 `specs/knowledge-rag/spec-delta.md` 落地：声明 `SourceReference.sourceType` 支持 `"SALES_OPPORTUNITY"`，明确 documentId/filename/chunkId 字段映射规约
      依据：git diff openspec/changes/add-sales-opportunity-semantic-retrieval/specs/knowledge-rag/spec-delta.md 确认契约增量声明
- [x] 1.2 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 追加受控解冻变更条目（对齐既有先例）
      依据：git diff spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md 确认变更日志追记解冻条目

## 任务 2 · 异步增量机制与商机语义摄取（摄取与异步层）

- [x] 2.1 新增 `SalesOpportunitySemanticTextSupport` 与 `SalesOpportunityIngestionService`：富文本拼装（商机名称/客户/对接人/阶段描述/金额/来源/预计结单/需求描述/负责人）、调用 `EmbeddingService.embed`、打标 Payload（entityType="SALES_OPPORTUNITY", resourceId, ownerUserId, ownerDeptId, companyId, title）并写入 `CrmEntityVectorStore`
      依据：git diff src/main/java/com/slz/crm/knowledge/ingestion/ 确认摄取逻辑与 Payload 键
- [x] 2.2 异步增量 Hook：新增 `SalesOpportunitySemanticHookSupport.runAfterCommitAsync`，在 `SalesOpportunityServiceImpl` 的 create/update/delete 处通过事务同步器在 afterCommit 后异步派发至线程池执行重嵌（`reingestById`）与删除（`deleteById`），单条失败仅记 WARN 不阻断业务
      依据：git diff src/main/java/com/slz/crm/server/service/impl/SalesOpportunitySemanticHookSupport.java 确认异步派发与 WARN 隔离
- [x] 2.3 存量回埋：新增全量回埋端点 `POST /sales/reingestAll`，挂既有注解 `@RequirePermission(PermissionOperates.SALES_CREATE_SALE_OPPORTUNITY)`；若契约检查触发漂移，同步更新 `frontend/openapi.yaml`
      依据：git diff src/main/java/com/slz/crm/server/controller/SalesOpportunityController.java 确认端点挂注解

## 任务 3 · 业务数据检索多实体融合（检索层）

- [x] 3.1 权限闸接入 `sales_opportunity`：调用 `dataScopeService.getHighestDataScopeLevel(user, "sales_opportunity")`，执行 NONE 零外发 / SELF / DEPT / DEPT_AND_CHILD / ALL 过滤与 TAGE/共享补充召回
      依据：git diff src/main/java/com/slz/crm/server/service/impl/BusinessRetrievalService.java 确认权限分支与向量过滤参数
- [x] 3.2 三业务实体（客户、商业活动、销售商机）多路召回与合并：同轮检索召回各实体向量，按相似度降序截断 TopK，来源标注 `type=SALES_OPPORTUNITY`，纵深防御复核归属，组装 `【业务数据检索】` 上下文
      依据：git diff src/main/java/com/slz/crm/server/service/impl/BusinessRetrievalService.java 确认多实体合并与来源封装
- [x] 3.3 槽位注入与 AiChat 集成回归验证：确保无标记/回退态与纯知识库路径逐字节一致，编号自 numberStart 自动顺延
      依据：mvn -B -ntp test -Dtest=AiChatKnowledgeRetrievalServiceTest 退出码 0（13 全绿；业务零命中/业务端口未就绪两用例断言 context 与 sources 与纯知识库路径逐字节一致，编号自 numberStart 顺延）

## 任务 4 · 单元测试与门禁收尾（门禁层）

- [x] 4.1 新增商机检索权限反向测试（四角色全覆盖）：归属人本人可查、本部门上级可查、跨部门同事绝不可查、管理员全可查
      依据：mvn -B -ntp test -Dtest=BusinessRetrievalServiceTest 退出码 0（20 全绿，含三实体四角色矩阵与 SELF 跨部门同事商机反向用例）
- [x] 4.2 摄取拼装、异步非阻塞隔离与全量回埋 Mock 单测（纯 JVM，零真跑外呼）
      依据：mvn -B -ntp test -Dtest=SalesOpportunityIngestionServiceTest 退出码 0（10 全绿）+ SalesOpportunitySemanticTextSupportTest（8 全绿）+ SalesOpportunitySemanticHookSupportTest（8 全绿）
- [x] 4.3 `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1314），树净时单次 `--update` 基线
      依据：mvn -B -ntp clean verify 退出码 0，surefire 1342（净增 28：ingestion 10 + text-support 8 + hook 8 + retrieval 2），check-test-baseline.sh --update 写入 1314→1342（source-revision=d73c71d 树净，无 -dirty）
- [x] 4.4 四静态（checkstyle/spotbugs/spotless/pmd）顺序跑全绿 + 三守卫 + `scripts/merge-gate.sh` 退出码 0
      依据：bash scripts/merge-gate.sh 退出码 0 全绿（8/8：unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline），证据留档于 work/tmp/add-sales-opportunity-semantic-retrieval/
