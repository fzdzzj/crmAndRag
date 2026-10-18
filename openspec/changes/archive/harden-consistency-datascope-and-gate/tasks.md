# 任务清单 · 知识库删除一致性、AI 工具数据权限收敛与门禁同源加固 (harden-consistency-datascope-and-gate)

<!-- id: harden-consistency-datascope-and-gate -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 知识库删除向量双清与两路检索一致性 (C-11)

- [x] 1.1 `KnowledgeAdminService` 注入 `CrmVectorStore` 与 `DocumentVectorChunkMapper`，在 `deleteFile(documentId)` 中实现物理双清：软删 `uploaded_file` 的同时，调用 `vectorStore.deleteByDocumentId(documentId)` 清理 Qdrant/内存向量点位，并调用 `chunkMapper.deletePhysicallyByDocumentId(documentId)` 清理切片快照行。
      - 实测: 3d5350b + 23670bf 落地；新增 10 参 @Autowired 构造器，原 8/5 参降级兼容委托（RealPilotIT 等 3 处既有调用方零改动）；兼容构造路径 deleteFile fail-closed 抛 IllegalStateException。
- [x] 1.2 编写单测或集成测试：验证调用 `deleteFile` 后，`UploadedFile` 标记删除、向量库中对应文档点位已被移除，且 `document_vector_chunk` 表无残留行。
      - 实测: `mvn -B -ntp test -Dtest="*KnowledgeAdmin*Test,*Access*Test,*AiQuery*Test,*ContractServiceImplTest,*ContractOrderItemServiceImplTest,*TaskCommentServiceTest"` → Tests run: 119, Failures: 0, Errors: 0（KnowledgeAdminServiceTest 16→18 例，双清触发与文件缺失零副作用各 1）。

## 任务组 2 · AI 工具 DataScope 收敛与详情类属主断言 (B-4 / B-5)

- [x] 2.1 在 `BusinessRecordAccessService` 补齐合同与任务的属主/读权限校验（如 `assertCanReadContract`、`assertCanReadTask` 等），并在服务实现层（`ContractServiceImpl.getDetailById`、`ContractOrderItemServiceImpl.getDetailById`、`TaskCommentServiceImpl.getByTaskId`）落地鉴权断言，无权读取抛出 12002 `PERMISSION_DENIED`。
      - 实测: 0c1f54d 落地；断言委托 dataScopeService.canReadResource（合同 creator+owner，任务 creator+assignee+assigner），三个详情入口同卡挂载，分页重载补闸防旁路；BusinessRecordAccessServiceTest 4→11 例含 relatedUserIds 实参精确形状校验。
- [x] 2.2 在 `AiQueryToolExecutors` 强化上下文守卫：详情类工具与结构化查询类工具（`executeQueryContract`、`executeQueryPayment`、`executeQueryInvoice`、`executeQueryContactTask`）校验当前调用用户身份，非超管/无权跨属主查询时严格按数据权限 fail-closed。
      - 实测: AiQueryToolSupport.requireLoggedInRole（BaseUnit.getCurrentRole() 无身份抛 12002）挂载 7 个执行器（3 详情 + 4 结构化）；非超管范围收敛依赖 QueryWrapperAspect Mapper 层注入（javadoc 注明）；AiQueryToolExecutorsTest 27→30 例含 7 执行器无身份全拦截 + 12002 断言。
- [x] 2.3 编写专项安全单测：覆盖订单详情、合同详情、任务评论详情跨属主越权查询拦截（12002）与合法属主读取正向用例。
      - 实测: ContractServiceImplTest +3 / ContractOrderItemServiceImplTest 新建 4 / TaskCommentServiceTest 1→6（列表口+分页口双拦截、任务缺失 12002 不泄露存在性、合法属主双正放），靶向 119/119 绿。

## 任务组 3 · 聚合门禁 merge-gate 与 CI 同源缺口治理 (G-6)

- [x] 3.1 `scripts/merge-gate.sh` 补齐静态门禁：新增 `checkstyle` (`mvn -B -ntp checkstyle:check`) 与 `spotless` (`mvn -B -ntp spotless:check`)，支持环境变量覆盖（`MERGE_GATE_CMD_CHECKSTYLE`、`MERGE_GATE_CMD_SPOTLESS`）。
      - 实测: 02b690e 落地；静态环节 run_gate checkstyle / run_gate spotless（unit 后、pmd 前），默认序列 8 门→10 门；与 ci.yml static-analysis-gate 同源映射写入头注。
- [x] 3.2 修订 `scripts/tests/merge-gate-selftest.sh` 增加两道新门禁的独立覆盖场景（正常通过、失败指名、覆盖参数验证）。
      - 实测: `bash scripts/tests/merge-gate-selftest.sh` → 自测通过：106 条断言全绿（93→106，新增场景 13 共 13 条：具名 PASS / FAIL 指名 / 覆盖命令 marker 透传；既有场景打桩导出同步补齐防真跑 Maven）。
- [x] 3.3 同步修订 `scripts/merge-gate.sh` 头注及说明，如实对齐 CI 映射。
      - 实测: 头注补 [checkstyle]/[spotless] 判定读者与 CI static-analysis-gate（mvn -B -ntp compile checkstyle:check spotbugs:check spotless:check pmd:check）同源说明及 validate 阶段隐式绑定备注；尾部逐项排查命令同步补齐。

## 任务组 4 · 门禁、基线台账与交付归档

- [x] 4.1 全量测试通过，`mvn -B -ntp clean verify` 全绿后以 `bash scripts/check-test-baseline.sh --update` 单次写入最新基线（Surefire 用例数在 1647 基础上只增不减）。
      - 实测: verify 二跑 BUILD SUCCESS EXIT=0（surefire 1671/0/0/0、failsafe 118/0/0/7，Docker 29.6.2 实测，log md5 6e56946f631ee5f8bd2e944c188ed88c）；基线单次写入 1647→1671（reports 266→267，source-revision=23670bf，文件 md5 944c56ad389197b04fd20b9c8bfb9c60），172798d 提交。首跑 verify 曾红于 pmd NcssCount（159/158 超 150），拆 KnowledgeAdminVectorSupport 与 AiQueryToolSupport 协作类后复绿（23670bf）。
- [x] 4.2 本地运行 `bash scripts/merge-gate.sh` 确保全门禁全 PASS，还原 `frontend/typed-router.d.ts` 噪声，产出 `work/handoff-harden-consistency-datascope-and-gate.md` 并停步待 A 档独立复核。
      - 实测: `bash scripts/merge-gate.sh` → 10/10 全 PASS，GATE_EXIT=0（unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline，log md5 c31df0c86b0fc9d91db15dcc7727263d）；typed-router.d.ts 行尾噪声已 checkout 还原；handoff 已产出，未 merge 未 push 停步待 Owner 派发 A 档复核。
