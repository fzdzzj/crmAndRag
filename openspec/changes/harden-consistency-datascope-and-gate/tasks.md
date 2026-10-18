# 任务清单 · 知识库删除一致性、AI 工具数据权限收敛与门禁同源加固 (harden-consistency-datascope-and-gate)

<!-- id: harden-consistency-datascope-and-gate -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 知识库删除向量双清与两路检索一致性 (C-11)

- [ ] 1.1 `KnowledgeAdminService` 注入 `CrmVectorStore` 与 `DocumentVectorChunkMapper`，在 `deleteFile(documentId)` 中实现物理双清：软删 `uploaded_file` 的同时，调用 `vectorStore.deleteByDocumentId(documentId)` 清理 Qdrant/内存向量点位，并调用 `chunkMapper.deletePhysicallyByDocumentId(documentId)` 清理切片快照行。
- [ ] 1.2 编写单测或集成测试：验证调用 `deleteFile` 后，`UploadedFile` 标记删除、向量库中对应文档点位已被移除，且 `document_vector_chunk` 表无残留行。

## 任务组 2 · AI 工具 DataScope 收敛与详情类属主断言 (B-4 / B-5)

- [ ] 2.1 在 `BusinessRecordAccessService` 补齐合同与任务的属主/读权限校验（如 `assertCanReadContract`、`assertCanReadTask` 等），并在服务实现层（`ContractServiceImpl.getDetailById`、`ContractOrderItemServiceImpl.getDetailById`、`TaskCommentServiceImpl.getByTaskId`）落地鉴权断言，无权读取抛出 12002 `PERMISSION_DENIED`。
- [ ] 2.2 在 `AiQueryToolExecutors` 强化上下文守卫：详情类工具与结构化查询类工具（`executeQueryContract`、`executeQueryPayment`、`executeQueryInvoice`、`executeQueryContactTask`）校验当前调用用户身份，非超管/无权跨属主查询时严格按数据权限 fail-closed。
- [ ] 2.3 编写专项安全单测：覆盖订单详情、合同详情、任务评论详情跨属主越权查询拦截（12002）与合法属主读取正向用例。

## 任务组 3 · 聚合门禁 merge-gate 与 CI 同源缺口治理 (G-6)

- [ ] 3.1 `scripts/merge-gate.sh` 补齐静态门禁：新增 `checkstyle` (`mvn -B -ntp checkstyle:check`) 与 `spotless` (`mvn -B -ntp spotless:check`)，支持环境变量覆盖（`MERGE_GATE_CMD_CHECKSTYLE`、`MERGE_GATE_CMD_SPOTLESS`）。
- [ ] 3.2 修订 `scripts/tests/merge-gate-selftest.sh` 增加两道新门禁的独立覆盖场景（正常通过、失败指名、覆盖参数验证）。
- [ ] 3.3 同步修订 `scripts/merge-gate.sh` 头注及说明，如实对齐 CI 映射。

## 任务组 4 · 门禁、基线台账与交付归档

- [ ] 4.1 全量测试通过，`mvn -B -ntp clean verify` 全绿后以 `bash scripts/check-test-baseline.sh --update` 单次写入最新基线（Surefire 用例数在 1647 基础上只增不减）。
- [ ] 4.2 本地运行 `bash scripts/merge-gate.sh` 确保全门禁全 PASS，还原 `frontend/typed-router.d.ts` 噪声，产出 `work/handoff-harden-consistency-datascope-and-gate.md` 并停步待 A 档独立复核。
