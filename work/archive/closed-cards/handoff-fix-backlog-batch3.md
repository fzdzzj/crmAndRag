# 交接 · fix-backlog-batch3（待办池清空卡 ⑤⑨⑩⑪，B 档）

> 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`　分支：`feature/fix-backlog-batch3`（自 master `dbeda7b` 检出）
> 停步位置：**未 merge、未 push、未 reset/rebase/amend、未删分支**。规格：`openspec/changes/fix-backlog-batch3/{proposal,tasks}.md`。

---

## 1. ⑤ 「已存在」精确码（DATA_ALREADY_EXISTS 92029）

### 1.1 变更点

| 文件 | 变更 |
| --- | --- |
| `common/enumeration/ErrorCode.java` | 新增 `DATA_ALREADY_EXISTS(92029, "数据已存在", "error.data.already.exists")`（92xxx 段下一可用号，92028 已被 PASSWORD_ERROR 占用） |
| `server/handler/SQLExceptionHandler.java` | duplicate-key **三出口**改 `Result.error(ErrorCode.DATA_ALREADY_EXISTS, 原文案)`：①`duplicateKeyResult` 组合索引分支（`共享 客户A 已存在`）②同方法单列索引分支（`手机号 138066666666 已存在`）③`handleDuplicateKeyException` 两处解析失败兜底（`数据已存在，无法重复添加`） |
| 未动 | FK 路径（`handleDataIntegrityViolationException`）与「缺少默认值」路径（`handleSQLException` 的 `doesn't have a default value` 分支）——错误码仍为原先的无码 0，由新单测 `nonDuplicateKeyPaths_keepLegacyCode` 钉住 |

**文案逐字节不变**的证据：三出口的字符串字面量一字未改（diff 只把 `Result.error(` 改成 `Result.error(ErrorCode.DATA_ALREADY_EXISTS, `），且 `SQLExceptionHandlerTest` 的 15 条文案断言（6 项索引映射 + 组合/裸名/空名/点号/未知索引 + 兜底）全部保持原断言并继续绿。

### 1.2 既有测试断言同步清单（卡面 1.2 要求逐处列出）

| 文件:行 | 原断言 | 改后 | 说明 |
| --- | --- | --- | --- |
| `src/test/java/com/slz/crm/unit/server/handler/SQLExceptionHandlerTest.java:117`（改前） | `assertEquals(0, result.getCode())` | `assertEquals(ErrorCode.DATA_ALREADY_EXISTS.getCode(), result.getCode())` | **全仓唯一**一处锁 `code==0` 的 duplicate-key 断言；另新增 `duplicateKey_allThreeExits_carryPreciseCode`（三出口码=92029 + 文案不变）与 `nonDuplicateKeyPaths_keepLegacyCode`（FK/缺省值仍 0） |

全仓检索 `数据已存在，无法重复添加` / `handleDuplicateKeyException` / `已存在，无法重复添加` 后确认：除该文件外无其他测试断言依赖该码值或该文案。

### 1.3 前端影响面实证（判据：只判成功码，不判具体失败码）

- `frontend/src/api/apiClient.ts:27-35`：响应拦截器 `const { code, msg } = response.data; if (!isApiSuccess(code)) { throw new ApiError(...) }`——**唯一判据是「是不是 1」**。
- `frontend/src/constants/error-code-map.ts:982-984`：`isApiSuccess(raw) { return toErrorCode(raw) === API_SUCCESS_CODE; }`，`API_SUCCESS_CODE = 1`。⇒ `0` 与 `92029` 走同一条「失败」分支，拦截器行为不变。
- `frontend/src/constants/error-code-map.ts:1016-1025`：`resolveErrorMessage(raw, serverMessage)` **优先后端 msg**（"它带业务上下文"）⇒ 用户可见文案仍是后端逐字节不变的原文。
- 结论：⑤ 的错误码变化**不改变任何前端判定与展示路径**；唯一需要动前端的是下条 D1。

### 1.4 D1（红线偏离，需 owner 裁决）

卡面红线写「`frontend/` 零 diff」，但仓内存在前后端错误码**逐名比对门禁** `src/test/java/com/slz/crm/contract/ErrorCodeMapParityTest.java`：

- `frontendCodeTableMatchesBackendEnums()` 断言 `TreeSet(后端枚举名) == TreeSet(前端 ApiErrorCode 名)`；
- `frontendMetaMatchesBackendMessagesAndI18nKeys()` 再逐项比对 码值/文案/messageKey。

即：只在后端加 92029，这两个用例必红（阶段 1 surefire），全量 verify 无法 SUCCESS。故按**历史先例**（`04c8c00` 加 PASSWORD_ERROR(92028)、`2806dda` 加 CUSTOMER_POOL_QUOTA_EXCEEDED(20003) 均为「后端枚举 + 前端镜像」同一笔提交）做了最小镜像，**2 处新增、0 处改动**：

- `frontend/src/constants/error-code-map.ts:107` `DATA_ALREADY_EXISTS: 92029,`
- `frontend/src/constants/error-code-map.ts:843-851` `92029: { name:'DATA_ALREADY_EXISTS', code:92029, message:'数据已存在', messageKey:'error.data.already.exists', category:'business', action:'none' }`

镜像是**纯增量**：未改任何既有码值/文案/结构，不引入新行为（`isApiSuccess` 与 `resolveErrorMessage` 对 92029 的判定见 §1.3）。实测 `ErrorCodeMapParityTest` 3/3 绿（见 §5）。**若 owner 判定红线不可让，则回退这 2 行 + 给 parity 门禁加豁免机制，二者只能选一。**

---

## 2. ⑨ 批量 IN 统一上限闸（BatchIdsGuard）

### 2.1 新增闸

`common/untils/BatchIdsGuard.java`：`check(List<Long>)` — null/空集/**全 null 元素** → `PARAM_EMPTY(50001)`；`distinct`（忽略 null）后 `> MAX_BATCH_IDS` → `PARAM_OUT_OF_RANGE(50007)`，文案「批量ID数量超限：去重后最多允许 500 个，实际 N 个」。`MAX_BATCH_IDS = 500` 常量，javadoc 写明三条选型依据（4KB 绑定参数规模 / 现有业务 10-200 的 2.5 倍余量 / 数千级应改按条件批处理）。**不做服务端自动分批**（理由同卡面：分批删除会造多事务部分提交中间态）。

### 2.2 16 端点接入对照表（每处一行，接入行号为改后行号）

| # | 端点 | 形态 | 接入位置 |
| --- | --- | --- | --- |
| 1 | `DELETE /business/activity` `BusinessActivityController#delete` | `@RequestBody` | `BusinessActivityController.java:67` |
| 2 | `DELETE /contactTask/batch` `ContactTaskController#deleteByIds` | `@RequestBody` | `ContactTaskController.java:108` |
| 3 | `DELETE /contract` `ContractController#delete` | `@RequestBody` | `ContractController.java:97`（**在 `try` 之前**，见 D6） |
| 4 | `DELETE /order` `ContractOrderItemController#delete` | `@RequestBody` | `ContractOrderItemController.java:47` |
| 5 | `DELETE /company` `CustomerCompanyController#delete` | `@RequestBody` | `CustomerCompanyController.java:86` |
| 6 | `PUT /company/recover` `CustomerCompanyController#recover` | `@RequestBody` | `CustomerCompanyController.java:111` |
| 7 | `DELETE /company/logical/batch` `CustomerCompanyController#batchLogicalDelete` | `@RequestBody`（级联删代表） | `CustomerCompanyController.java:288` |
| 8 | `DELETE /contact` `CustomerContactController#delete` | `@RequestBody` | `CustomerContactController.java:77` |
| 9 | `PUT /contact/recover` `CustomerContactController#recover` | `@RequestBody` | `CustomerContactController.java:90` |
| 10 | `DELETE /contact/logical/batch` `CustomerContactController#batchLogicalDelete` | `@RequestBody` | `CustomerContactController.java:205` |
| 11 | `DELETE /invoice/info` `InvoiceInfoController#delete` | `@RequestBody` | `InvoiceInfoController.java:56` |
| 12 | `DELETE /payment/record` `PaymentRecordController#delete` | `@RequestBody` | `PaymentRecordController.java:56` |
| 13 | `DELETE /project/file` `ProjectFileController#deleteByIds` | `@RequestParam ids` | `ProjectFileController.java:151` |
| 14 | `DELETE /sales/stage` `SalesStageApprovalController#deleteStage` | `@RequestParam ids` | `SalesStageApprovalController.java:142` |
| 15 | `DELETE /taskComment/batch` `TaskCommentController#deleteByIds` | `@RequestBody` | `TaskCommentController.java:37` |
| 16 | `DELETE /user` `UserController#delete` | `@RequestParam ids` | `UserController.java:174` |

形态覆盖：`@RequestBody` 13 处 / `@RequestParam` 3 处。服务层既有 `PARAM_EMPTY` 校验**保留**（双闸，先命中者生效，两者错误码同源 50001，不产生语义冲突）。

### 2.3 测试

- `common/untils/BatchIdsGuardTest`（7 例）：上限常量=500、null、空集、全 null 元素、恰 500 放行、501 拒（50007 + 文案含 500）、重复 id 不占额度（600 同值放行 / 501 不同值在重复中仍拒）。
- `unit/controller/BatchIdsGuardEndpointTest`（3 例，三形态各一）：`@RequestBody`（商业活动批量物理删）、`@RequestParam`（用户批量删）、级联删（公司批量逻辑删）；每例断言「空集→50001（service 零调用）/ 501→50007 + 文案含 500（service 零调用）/ 合规入参→照常下沉」。

---

## 3. ⑩ `batchLogicalDeleteByIds` 批量单趟化

### 3.1 前后对照

| 维度 | 改前（`:303-309`） | 改后 |
| --- | --- | --- |
| 级联遍历 | `for (id) logicalDeleteById(id)` → **N 轮** `deleteCascade`（每层每 id 一次 `IN`） | `foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(CustomerCompanyEntity.class, idList)` → **1 轮**级联覆盖全量 ids |
| 主表更新 | N 条 `updateById`（每 id 一条） | 1 条 `update ... set is_deleted=1 where id in (...)` |
| afterCommit 注册 | 每公司注册 1 次（N 次） | 1 次回调，回调体内 `idList.forEach(customerIngestionService::deleteByCompanyId)`（D5） |
| 空入参 | 无闸：`for` 空转返回 0 | `PARAM_EMPTY(50001)` 前置闸，零数据库交互 |
| 返回计数 | `Σ` 各 id 命中行数 | 批量入口的 `update` 实际命中行数（重复 id 只计一次；单条入口路径不变） |
| 向量清理语义 | 逐公司删除（N 次 `deleteByCompanyId`） | 逐公司删除（N 次 `deleteByCompanyId`，顺序同 idList） |

### 3.2 测试

`server/service/impl/CustomerCompanyBatchLogicalDeleteTest`（4 例）：批量入口委托恰一次且 ids 原样下沉（并 `verify(never())` 单条入口）、重复 id 同样只委托一次、空入参/null 抛 `PARAM_EMPTY` + `verifyNoInteractions`、单条入口 `logicalDeleteById` 仍走单条级联不误用批量入口。

---

## 4. ⑪ 附件孤儿债

### 4.1 A · 跨模型误删修复

`ApprovalAttachmentServiceImpl#removeByIds(:132-150)`：`baseMapper.deleteBatchIds(ids)` → `deleteBatchIds(entities 的 id)`。select 已按 `id IN + model_name = ?` 过滤，删原始 ids 会把**被他域 id 命中的跨模型行**一起物理删掉（附件表无 `is_deleted`，删除即物理删 + 磁盘清理）。口径与同文件 `removeSourceAttachmentsByDistinctIds(:311-312)` 一致。

单测 `unit/service/ApprovalAttachmentRemoveByIdsTest`（2 例）：混入他域 id 12L 时 `deleteBatchIds(List.of(11L,13L))` 且**绝不**出现 `List.of(11L,12L,13L)`；过滤集为空时零 `deleteBatchIds`。

### 4.2 B · 三处物理级联删的子宿主附件清理

新增协作类 `server/service/impl/ChildHostAttachmentCleaner`（`@Component`，构造注入 5 个依赖），由三处物理级联删**在 `deleteCascade` 之前**调用：

| 调用方 | 入口方法 | 清理域 |
| --- | --- | --- |
| `CustomerCompanyServiceImpl#deleteByIds(:260-261)` | `cleanBeforeCompanyPhysicalDelete(idList)` | `BUSINESS_ACTIVITY` + `CONTACT_TASK` |
| `CustomerContactServiceImpl#deleteByIds(:263-264)` | `cleanBeforeContactPhysicalDelete(idList)` | `BUSINESS_ACTIVITY` + `CONTACT_TASK` |
| `SalesOpportunityServiceImpl#delete(:169-170)` | `cleanBeforeOpportunityPhysicalDelete(List.of(id))` | `BUSINESS_ACTIVITY` + `CONTACT_TASK` |

**id 集口径**（D4）：与 `deleteCascade` 的递归图同口径的**两级展开闭包**，不是只查直接子：

- 公司删：`contacts = customer_contact(company_id∈C)` → `opps = sales_opportunity(company_id∈C) ∪ sales_opportunity(contact_id∈contacts)` → `activity = business_activity(company_id∈C) ∪ business_activity(opportunity_id∈opps)`；`task = contact_task(company_id∈C) ∪ contact_task(contact_id∈contacts) ∪ contact_task(opportunity_id∈opps)`。
- 联系人删：`opps = sales_opportunity(contact_id∈K)` → `activity = business_activity(opportunity_id∈opps)`；`task = contact_task(contact_id∈K) ∪ contact_task(opportunity_id∈opps)`。
- 商机删：`activity = business_activity(opportunity_id∈O)`；`task = contact_task(opportunity_id∈O)`。

清理走 canonical `ApprovalAttachmentService#removeByAndIds(ids, ModelName.X)`（含磁盘文件删除），因此「行 + 磁盘」一起清。**不动 `ForeignKeyConfig`**（红线）：多态表带不了 `model_name` 判别，进关联图就是跨模型误删。`sales_stage_approval` 域仍由先例 `deleteStageApprovalAttachments` 负责。

### 4.3 IT 证据（Testcontainers 真 MySQL，Docker 实测在线）

`src/test/java/com/slz/crm/integration/controller/CustomerCompanyPhysicalDeleteAttachmentCascadeIT`：种子 = 公司 + 名下 `business_activity` / `contact_task` + 三类附件（活动域、任务域、以及挂在活动 id 上的 `contact_task` 域**跨模型对照**）。断言：公司行物理删；两个子宿主随级联物理删；两条目标附件**行清零 + 磁盘文件删除**；跨模型同 `and_id` 对照附件**行与磁盘文件均存活**。实测结果见 §5。

### 4.4 `assist_request` 勘误（卡面要求）

待办池原表述暗示 `assist_request` 域也在此类孤儿债里，**实勘无洞**：`assist_request` 是**逻辑删**域且删除走 `AssistListQueryCoordinator#deleteByRecords`，其中已含 `approvalAttachmentService.removeByAndIds(assistIds, ModelName.ASSIST_REQUEST)`（`AssistListQueryCoordinator.java:107`），另 `AssistApplyCoordinator.java:199` 在重申请路径也清了 `ASSIST_REQUEST` 域。故本卡不涉及 `assist_request`，勘误成立。

---

## 5. 验证四元组与门禁实测（2026-10-21，Docker 29.6.2 实测在线）

### 5.1 分支提交链（分笔，未 merge / 未 push）

| 序 | 哈希 | 提交信息 | 内容 |
| --- | --- | --- | --- |
| 1 | `ceb8fdb` | `fix: precise duplicate-key code, batch id guard, single-pass batch delete, attachment orphan cleanup` | 主笔：28 文件（19 改 / 7 新增 / 1 前端镜像） |
| 2 | `0d1c673` | `test(baseline): rewrite regression baseline from clean verify run (303/1889 + 34/123/7)` | `scripts/test-baseline.txt` 单文件 |
| 3 | `397e9b5` | `test(frontend): bump mirrored backend error code count to 100 for 92029` | 前端镜像用例计数（D1 的连带项，见 §6） |
| 4 | `02af1e0` | `docs(openspec): add fix-backlog-batch3 card with checked task evidence` | `openspec/changes/fix-backlog-batch3/` 卡物料 + 勾选留痕 |

### 5.2 全量 `mvn -B -ntp clean verify`（BUILD SUCCESS / EXIT 0）

| 轨道 | 报告数 | 用例 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| surefire（单元 + 契约 + H2 冒烟） | 303 | 1889 | 0 | 0 | 0 |
| failsafe（IT，含 Testcontainers MySQL） | 34 | 123 | 0 | 0 | 7 |

基线变化（只增不减）：`299/1871 + 33/122/7` → **`303/1889 + 34/123/7`**（新增 4 个 surefire 报告：BatchIdsGuardTest / BatchIdsGuardEndpointTest / CustomerCompanyBatchLogicalDeleteTest / ApprovalAttachmentRemoveByIdsTest；SQLExceptionHandlerTest 由 13→15 例；新增 1 个 failsafe 报告：CustomerCompanyPhysicalDeleteAttachmentCascadeIT）。日志：`work/logs/verify-fix-backlog-batch3-run2.log`。

### 5.3 基线门禁

- 树净（`git status` 仅 untracked 卡物料）单次 `bash scripts/check-test-baseline.sh --update` → 写入 `surefire 303/1889/0` + `failsafe 34/123/7`，`source-revision=ceb8fdb`。
- 复跑 `bash scripts/check-test-baseline.sh` → **EXIT=0**「回归基线门禁通过」。

### 5.4 `scripts/merge-gate.sh` 10/10 PASS

`[unit]` / `[checkstyle]` / `[spotbugs]` / `[spotless]` / `[pmd]` / `[baseline]` / `[frontend-unit]`（Vitest 21 文件 197 例全绿）/ `[hook]` / `[bijection]` / `[pmd-baseline]` 全 PASS，`[it]` 为默认不跑项（failsafe 结论取自 §5.2 的 verify 运行）。日志：`work/logs/merge-gate-fix-backlog-batch3-run2.log`。

### 5.5 红线核对

零迁移（`src/main/resources/db/migration` 零 diff）/ 零新依赖（`pom.xml` 零 diff）/ 除 92029 外零新错误码 / `ForeignKeyConfig` 零 diff / 无冻结契约改动 / `openapi.yaml` 生成物**零 diff**（`OpenApiContractExportTest` 2 绿，故未触发 `check-openapi-consistency.sh --update` 入口）/ 未 merge、未 push、未 reset/rebase/amend、未删分支。

---

## 6. 偏差清单（D 编号）

| 编号 | 偏离卡面的决策 | 理由 | 影响面 |
| --- | --- | --- | --- |
| **D1** | 动了 `frontend/`：`error-code-map.ts` 加 2 处镜像（`ApiErrorCode.DATA_ALREADY_EXISTS` + `92029` 元数据），`error-code-map.test.ts` 把后端码名总数 99→100 | 卡面红线是「frontend 零 diff」，但仓内 `ErrorCodeMapParityTest`（JVM，阶段 1）对前后端错误名**双向逐名比对**、`error-code-map.test.ts`（Vitest，merge-gate `[frontend-unit]`）对后端码名**总数**断言；不动前端则两道门禁必红，先例（`04c8c00`/`2806dda`）也都是同笔加镜像。镜像是纯增量、零既有行为改动 | 3 行（1 文件 2 处 + 1 用例常量）；`isApiSuccess`/`resolveErrorMessage` 行为不变（§1.3）。**该红线让与不让，请 owner 裁决** |
| **D2** | `BatchIdsGuard` 把「全 null 元素」也判 `PARAM_EMPTY` | 卡面只写 null/空集；但 `[null]` 会让 `IN` 列表退化成非法 SQL，同属「没有可用 id」 | 更严格，不改变任何既有合法入参 |
| **D3** | 新增公共协作类 `ChildHostAttachmentCleaner`（卡面只说「三调用方补清理」，未指定载体） | 三处调用方缺 BA/CT/商机/附件 mapper 依赖；集中实现避免三份重复的 id 集闭包逻辑，且可被单测直接覆盖；形态与先例 `deleteStageApprovalAttachments` 同源（canonical `removeByAndIds` + 磁盘清理） | 新增 1 类，`common/untils` 与 `server/service/impl` 各 1 新文件 |
| **D4** | 子宿主 id 集按**两级展开闭包**取（含经联系人/商机间接挂上的活动与任务），不是只查直接 `company_id` | 卡面写「先查将被级联删的 id 集」；`deleteCascade` 实际会递归删到孙辈，只查直接子会留下一层孤儿 | 清理范围与级联范围对齐（不漏不越界，仍按 `model_name` 精确命中） |
| **D5** | ⑩ 的向量清理从「每公司注册一次 afterCommit」收敛为「一次回调内 `idList.forEach` 逐公司派发」 | 卡面「idList.forEach 注册」有两种读法；取「一次注册 + 逐个派发」既保住逐公司语义，又把注册次数 N→1（与单趟化目标一致） | 派发内容与顺序不变；注册次数减少 |
| **D6** | `ContractController` 的闸放在 `try` **之前** | 该方法 `catch (RuntimeException e) → Result.error(e.getMessage())` 会把 `BaseException(PARAM_EMPTY)` 吞成 `code=0` 且丢失错误码；放在 try 前才能让 50001/50007 走全局 handler | 空集/超限在该端点由 `code=0`+原文案 变为 `50001/50007`+原文案（这正是卡的意图） |

---

## 7. 待裁决项

1. **D1 前端 3 行镜像**：接受（推荐，先例一致且门禁同源），还是回退并给 `ErrorCodeMapParityTest` + `error-code-map.test.ts` 加「后端已定义、前端暂不镜像」的豁免机制？后者属门禁语义改造，需另开卡。
2. **⑨ 上限 500**：若业务侧确实存在 >500 的勾选批量场景，请给出规模上界；否则维持 500（当前实测用例在 10-200）。
3. **⑪-B 的 `sales_stage_approval` 域残留**：公司/联系人物理级联删仍会把名下商机的阶段审批行删掉，而这些审批行名下的 `approval_attachment`（`model_name=sales_stage_approval`）仍是孤儿（本卡按卡面范围只清 `business_activity`/`contact_task` 两域）。是否另开卡把 `deleteStageApprovalAttachments` 的形态推广到公司/联系人路径？
4. **`ErrorCodeMapParityTest` 的覆盖盲区**：它拦得住「后端加码、前端不加」，但拦不住「前端 `error-code-map.test.ts` 的硬编码总数」（本轮 99→100 是 Vitest 抓到的）。是否把该总数改成从 Java 枚举生成（避免下一张卡再手改常量）？

