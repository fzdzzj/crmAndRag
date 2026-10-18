# 变更提案 · 知识库删除一致性、AI 工具数据权限收敛与门禁同源加固 (harden-consistency-datascope-and-gate)

## 1. 现状与痛点

本变更由全面侦察台账中三项高价值缺陷合并立项推进（C-11 + B-4/B-5 + G-6）：

### 模块 A · 知识库删除向量双清与两路检索一致性（台账 C-11，A 档）
- **现状**：`KnowledgeAdminService.deleteFile(documentId)` 仅执行软删除 `file.setIsDeleted(true)` 并更新 `uploaded_file` 表，未同步清理 Qdrant 向量存储中的点位，亦未清理 MySQL `document_vector_chunk` 切片快照；
- **缺陷后果**：已删除文档在稀疏全文检索路（JOIN `uploaded_file` 带有 `is_deleted=0` 过滤）虽不可见，但在向量检索路（`KnowledgeRetrievalServiceImpl.recall` 仅按 `knowledgeBaseId` 过滤）仍会被召回并作为参考来源推荐进主答，导致两路检索结果严重分叉，且造成已删知识的敏感合规残留。

### 模块 B · AI 查询工具 DataScope 收敛与详情类属主断言（台账 B-4 / B-5，B 档）
- **现状**：
  1. 详情类工具裸查 ID：`AiQueryToolExecutors` 中的 `executeGetOrderDetail`（调用 `contractOrderItemService.getDetailById`）、`executeGetContractDetail`（调用 `contractService.getDetailById`）、`executeQueryTaskComment`（调用 `taskCommentService.getByTaskId`）未校验当前用户属主或部门范围，缺乏记录级权限断言（对照商机详情已有 `assertCanReadOpportunity`）；
  2. 结构化查询工具游离：`executeQueryContract`、`executeQueryPayment`、`executeQueryInvoice`、`executeQueryContactTask` 未校验当前会话用户上下文与部门隔离，直接把 DTO 透传底层，可能穿透数据权限。
- **缺陷后果**：通过 AI 助手对话构造工具调用，可跨属主读取任意用户的订单、合同与任务评论详情，形成越权漏洞（IDOR）。

### 模块 C · 聚合门禁 merge-gate 与 CI 同源缺口治理（台账 G-6，B 档）
- **现状**：本地聚合门禁 `scripts/merge-gate.sh` 仅运行 unit / spotbugs / pmd / it / baseline / frontend-unit / hook / bijection / pmd-baseline 九门，缺少 CI 中 `static-analysis` job 执行的 `checkstyle:check` 与 `spotless:check` 检查；门禁头注关于运行历史的描述与当前事实存在漂移；
- **缺陷后果**：代码在本地通过 `merge-gate.sh` 全绿合入后，推送到远端 CI 仍可能因代码格式或 Checkstyle 规则违规而在 `static-analysis` 挂红，破坏门禁同源性。

---

## 2. 方案与边界

### 模块 A · 知识库删除一致性
1. `KnowledgeAdminService` 显式注入 `CrmVectorStore` 与 `DocumentVectorChunkMapper`；
2. 在 `deleteFile(documentId)` 事务内或逻辑中：
   - 维持 `file.setIsDeleted(true)` 状态更新；
   - 联动调用 `vectorStore.deleteByDocumentId(documentId)` 清理 Qdrant / 内存向量点位；
   - 联动调用 `chunkMapper.deletePhysicallyByDocumentId(documentId)` 物理清空切片快照行（复用既有 `@Delete` 方法）；
3. 单元与集成测试守护：断言删除文件后向量库与切片表中该文档数据清零。

### 模块 B · AI 工具权限收敛与详情属主断言
1. 在 `BusinessRecordAccessService` 补齐 `assertCanReadContract(ContractEntity contract)` 与 `assertCanReadTask(ContactTaskEntity task)` 等记录级读权限校验方法；
2. 在 `ContractServiceImpl.getDetailById`、`ContractOrderItemServiceImpl.getDetailById`、`TaskCommentServiceImpl.getByTaskId` 服务入口增加当前用户可读性断言（无权访问抛 12002 `PERMISSION_DENIED`）；
3. `AiQueryToolExecutors` 在执行查询或详情获取时，确保当前上下文已加载 `BaseUnit.getCurrentRole()`，若无有效登录上下文则 fail-closed；
4. 编写专项测试验证越权访问拦截与属主正常访问放行。

### 模块 C · 聚合门禁与 CI 同源对齐
1. `scripts/merge-gate.sh` 增加 `checkstyle` (`mvn -B -ntp checkstyle:check`) 与 `spotless` (`mvn -B -ntp spotless:check`) 两道静态检查门禁；
2. 保持环境变量覆盖机制（`MERGE_GATE_CMD_CHECKSTYLE`、`MERGE_GATE_CMD_SPOTLESS`）；
3. 更新 `scripts/tests/merge-gate-selftest.sh` 增加两道新门禁的独立桩测试与全绿/报错断言；
4. 修订 `scripts/merge-gate.sh` 头注，如实对齐 CI 映射。

---

## 3. 红线与非目标

- 零数据库迁移：`src/main/resources/db/migration/` 零改动（下一可用迁移号仍为 V43）；
- 零外部依赖引入：`pom.xml` 零改动；
- 零破坏性契约变更：`openapi.yaml` 零 diff；
- 严禁触碰 `frontend/typed-router.d.ts`（若被生成工具改动立即 checkout 还原）；
- A 档复核全流程：因变更涉及知识检索核心数据生命周期、向量物理删除与数据权限断言，整卡按最高优先级评定为 **A 档**。
