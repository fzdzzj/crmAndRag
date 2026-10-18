# HANDOFF · harden-consistency-datascope-and-gate（一致性、数据权限与门禁加固，A 档）

- 日期：2026-10-18
- 执行子 Agent：按 `work/task-card-harden-consistency-datascope-and-gate.md` 独立执行
- 权威工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 特性分支：`feature/harden-consistency-datascope-and-gate`（自 master `80ffd6f` 检出）
- 状态：**代码与交付物料已全部提交，停步待 Owner 派发 A 档独立复核**。未 merge、未 push。

## 1. 提交列表（分支上共 6 笔，全部单行 ASCII message）

| # | 提交 | 内容 |
| --- | --- | --- |
| 1 | `3d5350b` | feat(knowledge): physically purge vectors and chunks when deleting knowledge file（模块 A） |
| 2 | `0c1f54d` | feat(security): record-level read asserts for contract and task detail access（模块 B） |
| 3 | `02b690e` | feat(gate): add checkstyle and spotless gates to merge-gate with selftest（模块 C） |
| 4 | `ceabe24` | docs(openspec): add harden-consistency-datascope-and-gate proposal and tasks |
| 5 | `23670bf` | refactor(pmd): extract collaborators to keep ncss under threshold（PMD 处置，见 §5） |
| 6 | `172798d` | test(baseline): update clean baseline to 23670bf |

最终 HEAD = `172798d`。分支与 master 之间 `git status` 干净，`git log master..HEAD` 恰为上述 6 笔。

## 2. 模块 A · 知识库删除向量与切片物理双清（C-11）

改动文件：`src/main/java/com/slz/crm/server/service/KnowledgeAdminService.java`、`KnowledgeAdminVectorSupport.java`（新增）、`KnowledgeAdminServiceTest.java`。

1. `KnowledgeAdminService` 新增 `CrmVectorStore vectorStore` 与 `DocumentVectorChunkMapper chunkMapper` 注入：新增 10 参 `@Autowired` 构造器；原 8 参与 5 参构造器**降级为兼容委托**（双清依赖传 null），`KnowledgeAdminRealPilotIT`（8 参）、`KnowledgeBaseMemberVisibilityBoundaryIT`（5 参）、`KnowledgeAdminCurrentUserTest`（5 参）既有调用方零改动。
2. `deleteFile(documentId)`：找到 `UploadedFileEntity` 后按序执行——软删（`setIsDeleted(true)` + `updateById`）→ `vectorStore.deleteByDocumentId(documentId)` 清空 Qdrant/内存向量点位 → `chunkMapper.deletePhysicallyByDocumentId(documentId)` 物理清空 `document_vector_chunk` 切片快照行（复用既有 `@Delete`）。双清依赖未装配（兼容构造路径）时 `deleteFile` 抛 `IllegalStateException` **fail-closed**，严禁留下"软删但向量/切片仍在"的半清状态。
3. `CrmVectorStore` 注入解析到 `KnowledgeVectorStoreConfiguration` 的 `@Primary` 知识库向量库（与 `DocumentIngestionService` 同一 bean），与业务实体库 `CrmEntityVectorStore` 隔离。
4. 单测（`KnowledgeAdminServiceTest`）：`deleteFile_existingDocument_softDeletesAndPhysicallyPurgesVectorsAndChunks`（断言 updateById 标记删除 + 双清各被准确触发一次）、`deleteFile_missingDocument_returnsFalseWithoutAnyPurge`（文件缺失时零副作用）。

## 3. 模块 B · AI 查询工具 DataScope 收敛与详情类属主断言（B-4 / B-5）

### 3.1 记录级读权限断言（BusinessRecordAccessService）

- 接口与实现补齐 `boolean assertCanReadContract(ContractEntity contract)` 与 `boolean assertCanReadTask(ContactTaskEntity task)`：无登录身份（`requireCurrentUser`）、实体缺失（null/id null）或越权一律抛 12002 `PERMISSION_DENIED`；放行时返回 true。
- 实现委托 `dataScopeService.canReadResource(...)`：合同传 `creatorId + ownerId`，任务传 `creatorId + assigneeId（执行人/负责人）+ assignerId（指派人）`。超管（roleId=1）ALL 放行、同部门（及以下）、数据共享、标签绑定均由 `canReadResource` 既有语义收口（`DataScopeLevelResolver`，行为未改）。
- 合同/任务无协助范围语义，故不接 `AssistScopeService`（与商机/公司/联系人三兄弟方法差异点，复核请关注）。

### 3.2 详情入口挂载

| 入口 | 断言方式 |
| --- | --- |
| `ContractServiceImpl.getDetailById` | 实体化重构：`getById` → 实体缺失 `CONTRACT_NOT_EXISTS` → `assertCanReadContract(entity)` → 复用抽取的 `toContractVO(entity)` 组装；`getContractById` 行为不变（公共 API 未挂断言，与卡规格一致） |
| `ContractOrderItemServiceImpl.getDetailById` | 订单读权限随所属合同：加载 `contractMapper.selectById(contractId)` 后断言；**所属合同缺失视为数据不完整，12002 fail-closed** |
| `TaskCommentServiceImpl.getByTaskId`（列表 + 分页两重载同闸） | 新增 `assertTaskReadable(taskId)`：任务不存在与越权统一 12002，**不向调用方区分"任务缺失"与"无权访问"**（不泄露任务存在性）；分页重载补闸防旁路 |

### 3.3 AiQueryToolExecutors 上下文守卫

- 新增 `requireLoggedInRole()`（实现在新协作类 `AiQueryToolSupport`，见 §5）：`BaseUnit.getCurrentRole()` 为 null 或 `id` 为 null 时抛 12002 fail-closed。
- 挂载 7 个执行器：`executeGetOrderDetail`、`executeGetContractDetail`、`executeQueryTaskComment`（详情类）+ `executeQueryContract`、`executeQueryPayment`、`executeQueryInvoice`、`executeQueryContactTask`（结构化查询类，与 tasks.md 2.2 点名清单一致）。
- 结构化查询工具非超管的数据范围收敛**依赖 `QueryWrapperAspect` Mapper 层注入**（contract / contact_task / payment_record 为受管表），方法 javadoc 已注明，不重复实现过滤。注：`invoice_info` 不在 `ResourceTypeConstant.MANAGED_TABLES`，切面对其不注入范围条件——这是现状既有事实，不在本卡扩权范围，已如实记录供复核定夺。

### 3.4 专项安全测试（越权拦截 + 合法放行）

- `BusinessRecordAccessServiceTest`：4→11 例，覆盖合同/任务的范围内放行（并**校验 relatedUserIds 实参精确形状**）、范围外 12002、实体缺失 12002、无登录身份 12002。
- `ContractServiceImplTest`：+3 例（实体缺失不进断言 / 跨属主 12002 / 合法属主正常返回含订单装配）。
- `ContractOrderItemServiceImplTest`（新建 4 例）：合同缺失 fail-closed / 订单缺失不进断言 / 跨属主 12002 / 合法属主返回。
- `TaskCommentServiceTest`：1→6 例（任务缺失 12002 / 列表口跨属主 12002 / 分页口跨属主 12002 防旁路 / 合法属主列表与分页正放）。
- `AiQueryToolExecutorsTest`：27→30 例，7 个受守卫执行器无身份全拦截 + 拦截码 12002 断言 + 有身份正向委托；6 个既有用例补齐登录身份（守卫前置于参数校验）。

## 4. 模块 C · merge-gate 与 CI 同源治理（G-6）

- `scripts/merge-gate.sh`：
  - 新增 `cmd_checkstyle=${MERGE_GATE_CMD_CHECKSTYLE:-mvn -B -ntp checkstyle:check}` 与 `cmd_spotless=${MERGE_GATE_CMD_SPOTLESS:-mvn -B -ntp spotless:check}`；
  - 静态检查环节新增 `run_gate checkstyle` 与 `run_gate spotless`（位于 [unit] 后、[pmd] 前），默认序列由 8 门变 **10 门**；
  - 头注补两道门禁的判定读者与 CI 映射说明（与 `.github/workflows/ci.yml` `static-analysis-gate` 的 `mvn -B -ntp compile checkstyle:check spotbugs:check spotless:check pmd:check` 同源；并说明 pom 还把它们绑在 validate 阶段，本脚本显式单点调用不依赖隐式绑定）；尾部"逐项排查命令"同步补齐。
- `scripts/tests/merge-gate-selftest.sh`：
  - 全部既有场景（含 7/8/10/11 真校验器场景）的打桩导出补齐两道新门禁，防止自测真跑 Maven；
  - 场景 2 失败指名循环纳入 checkstyle / spotless；
  - 新增场景 13 共 13 条断言：默认序列两道门禁具名 PASS、失败指名（FAIL [checkstyle] / FAIL [spotless] + 尾部清单）、`MERGE_GATE_CMD_CHECKSTYLE` / `MERGE_GATE_CMD_SPOTLESS` 覆盖命令真被执行（marker 透传）；
  - 自测总量 93 → **106 条断言全绿**。

## 5. PMD NcssCount 处置（首跑 verify 红 → 拆协作类，如实记录）

第一次 `mvn -B -ntp clean verify` 在 pmd:check 红：本卡改动使 `AiQueryToolExecutors`（NCSS 159）与 `KnowledgeAdminService`（NCSS 158）越过 classReportLevel=150（仓库零容忍口径，台账只许下调）。处置按仓库既有"拆协作类"惯例（CustomerSemanticHookSupport 等先例）：

- 新增 `KnowledgeAdminVectorSupport`（package-private）：管理端真向量开关读取 / 配额获取 / 真向量候选检索三方法拆自 `KnowledgeAdminService`，服务内懒初始化（同 `DataScopeServiceImpl` 协作对象模式），**既有 12 个 retrievalTest 单测的 mock 交互全部原样穿透，零改动全绿**；
- 新增 `AiQueryToolSupport`（package-private final，纯静态）：登录身份守卫、approvalStatus 数组/单值兼容归一、trimPageResult、toInt/toLong 拆自 `AiQueryToolExecutors`，行为逐语句等价（stage 审批解析改委托后 DTO setApprovalStatus(null) 与原"不设置"等价）；
- 处置后 `mvn -B -ntp pmd:check` BUILD SUCCESS，靶向单测复跑 119/119，verify 二跑全绿。

## 6. 实测流水与基线变动

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 靶向单测 | `mvn -B -ntp test -Dtest="*KnowledgeAdmin*Test,*Access*Test,*AiQuery*Test,*ContractServiceImplTest,*ContractOrderItemServiceImplTest,*TaskCommentServiceTest"` | **119/119 绿** |
| 门禁自测 | `bash scripts/tests/merge-gate-selftest.sh` | **106 条断言全绿**（新增场景 13） |
| 全量回归 | `mvn -B -ntp clean verify`（Docker 实测在线 29.6.2） | **BUILD SUCCESS，EXIT=0**；surefire **1671**/0/0/0，failsafe **118**/0/0/7（log md5 `6e56946f631ee5f8bd2e944c188ed88c`） |
| 基线更新 | `bash scripts/check-test-baseline.sh --update`（提交后工作树干净，单次命令写入） | surefire 1647→**1671**（reports 266→267），failsafe 118/31/7 不变；`source-revision=23670bf`，文件 md5 `944c56ad389197b04fd20b9c8bfb9c60` |
| 聚合门禁 | `bash scripts/merge-gate.sh` | **10/10 全 PASS，GATE_EXIT=0**（unit / checkstyle / spotbugs / spotless / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline；log md5 `c31df0c86b0fc9d91db15dcc7727263d`） |

Surefire 用例数在 1647 基础上净增 24（本卡新增），只增不减，满足卡要求。

## 7. 红线自检

- 零数据库迁移：`git diff master --stat -- src/main/resources/db/migration` 为空；V43 号段未动用。
- 零新增依赖：pom.xml 零改动。
- 零破坏性契约变更：frontend/openapi.yaml 零 diff。
- `frontend/typed-router.d.ts`：merge-gate 的 [frontend-unit] 触发的行尾噪声已按卡执行 `git checkout --` 还原，最终工作树干净（`git status --porcelain` 为 0 行）。
- 提交纪律：6 笔提交全部单行 ASCII（`git commit -m`），无多行、无中文。
- 未执行 `git merge`、未执行 `git push`。

## 8. 建议复核要点（A 档）

1. **fail-closed 语义边界**：任务不存在 → 12002（不泄露存在性）；订单所属合同缺失 → 12002；兼容构造器路径 `deleteFile` → IllegalStateException。三处"缺数据即拒绝"是否符合 Owner 对误伤率的容忍度。
2. **`getContractById` 未挂断言**：卡规格只点名 `getDetailById`；`getContractById` 仍被 `OrderActionExecutor`/`AiEntityResolver` 使用，未收口是否可接受（本卡未扩权）。
3. **invoice_info 非受管表**：结构化查询守卫只保身份，发票范围过滤依赖切面，而切面对非受管表不注入——现状事实，是否立项另卡收口。
4. **两个协作类拆分**：`KnowledgeAdminVectorSupport`（懒初始化）与 `AiQueryToolSupport`（纯静态）的等价性（单测全绿 + verify 全绿背书）。
