# Handoff · fix-approval-attachment-cascade-dead-edge（级联死边，A 档 / 拍板形态 C）

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 基点：`master@5024d8e`（surefire 283 报告/1777、failsafe 31/118/7、merge-gate 10/10）
- 分支：`feature/fix-approval-attachment-cascade-dead-edge`（自 master 检出）
- 结论：形态 C 已落地，真库 IT 双态证据齐（修复前抛 `ID_NOT_EXISTS` 且整事务回滚 → 修复后删除成功、目标附件 DB+磁盘删除、他模型同 `and_id` 对照附件存活）。**未 merge、未 push**，分支保留待 A 档独立复核。

---

## 1. 缺陷机制与七源证据

级联链路（修复前）：`SalesOpportunityServiceImpl#delete` → `stage==5` 闸 → `deleteCascade(SalesOpportunityEntity, [id], 0)` →
子表 `sales_stage_approval(opportunity_id)` 命中 → 递归该子表 → 死边 `ApprovalAttachmentEntity#approvalId` →
`ColumnUtils.getColumn` 抛 `BaseException(ID_NOT_EXISTS)` → `@Transactional(rollbackFor=Exception.class)` 整事务回滚。

| # | 证据源 | 事实 |
|---|--------|------|
| S1 | `src/main/java/com/slz/crm/server/config/ForeignKeyConfig.java`（修复前 :169-171） | `SALES_STAGE_APPROVAL_RELATIONS.put(ApprovalAttachmentEntity.class, singletonList("approvalId"))` |
| S2 | `src/main/resources/db/migration/V1__baseline.sql:23-33` | `approval_attachment` 只有多态列 `and_id`（:27）配 `model_name` 判别（:33），**无 `approval_id`** |
| S3 | `src/main/java/com/slz/crm/pojo/entity/ApprovalAttachmentEntity.java:28-31` | 实体只有 `andId`（`@TableField("and_id")`），**无 `approvalId` 属性** |
| S4 | `src/main/java/com/slz/crm/common/untils/ForeignKeyDeleteUtil.java:252-276` | `ColumnUtils.getColumn`：`TableInfo` 已初始化时对不存在属性抛 `NoSuchFieldException` → `BaseException(ID_NOT_EXISTS)`；未初始化时静默走「属性名回退」（本卡缺陷之所以在单测干净 JVM 不可见） |
| S5 | `src/main/java/com/slz/crm/server/service/impl/SalesOpportunityServiceImpl.java`（修复前 :148-165） | `delete` 的 `stage != 5` 闸后必经 `deleteCascade`，无死边豁免 |
| S6 | `src/main/java/com/slz/crm/server/controller/SalesStageApprovalController.java:140-160` / `:184-204` | 附件语义来源：`deleteStage` 与 `getApprovalAttachment` 均按 `(ModelName.APPROVAL_ATTACHMENT, approvalIds)` 操作，即附件真属于阶段审批 |
| S7 | `src/main/java/com/slz/crm/server/service/impl/ApprovalAttachmentServiceImpl.java:89-107` | canonical 删除 = `IOUtils.deleteFile(entities)`（磁盘清理）+ `deleteBatchIds`（DB 物理删）；附件表无 `is_deleted`，逻辑删语义不成立 |

---

## 2. 前证（修复前实测，真库 Docker MySQL）

- 命令（PowerShell 直跑，不经 Git Bash）：
  `mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=SalesOpportunityDeleteAttachmentCascadeIT"`
- 实测输出（`work/evidence/pre-fix-it.log`，未跟踪留证）：

```
[ERROR] SalesOpportunityDeleteAttachmentCascadeIT.deleteClosedOpportunity_removesApprovalAttachmentsOnly:129 » Base 【com.slz.crm.pojo.entity.ApprovalAttachmentEntity.approvalId】不存在
[ERROR] Tests run: 2, Failures: 1, Errors: 1, Skipped: 0
com.slz.crm.common.exiception.BaseException: 【com.slz.crm.pojo.entity.ApprovalAttachmentEntity.approvalId】不存在
```

- 同期临时用例 `preFixEvidence_deadEdgeThrowsAndRollsBack`（修复前单跑）：`Tests run: 1, Failures: 0`，断言链 =
  `assertThrows(BaseException)` + 消息含 `approvalId` + **行级回滚证据**：`sales_stage_approval` 1 行、目标附件 1 行、
  他模型对照附件 1 行仍全在，`sales_opportunity.is_deleted=0`。日志 `work/evidence/pre-fix-evidence-only.log`。
- 该临时用例在修复提交中已删除（修复后删除不再抛错，保留即恒红）；删除动作与理由见 §12 D5。

---

## 3. 修复（形态 C）

### 3.1 diff 摘要（4 改 1 增）

| 文件 | 变更 |
|------|------|
| `ForeignKeyConfig.java` | 移除死边（原 :169-171，含注释）；:163-170 留「已移除 + 为什么」说明；`APPROVAL_ATTACHMENT_RELATIONS` 空图条目（`ALL_RELATIONS:62`）与其常量声明一并移除 |
| `SalesOpportunityServiceImpl.java` | `delete` 在 `deleteCascade` **之前**调用 `deleteStageApprovalAttachments(id)`（:162）；新增私有支撑方法（:190 起） |
| `ForeignKeyDeleteUtilBatchTest.java` | `KNOWN_DEAD_RELATIONS` 清空为 `Set.of()`（:68），注释改写成「死边已修 + 语义保持」 |
| `SalesOpportunityServiceTest.java` | 新增 2 个委托/顺序断言单测（canonical 补删发生在级联前；无审批时零附件查询） |
| `SalesOpportunityDeleteAttachmentCascadeIT.java`（新） | 真库 IT：Docker MySQL + `@Sql(/init_data.sql)` 提供 FK 目标行 + 固定主键种子 |

### 3.2 `ALL_RELATIONS:62` 附件子图条目保留性评估（实测结论：一并移除）

- 移除死边后，**没有任何边指向 `ApprovalAttachmentEntity`**：`grep -rn "ApprovalAttachmentEntity" src/main/java` 在 `ForeignKeyConfig.java` 中零命中
  （其余 104 处命中全在实体自身、附件服务/VO 组装、权限判定、`IOUtils` 文件读写、`PublicAttachmentController`、本卡新增的商机补删方法里，均非级联图配置）。
- 故附件子图恒空；`deleteCascade` 对 `directRelations == null` 与「空 map」走同一早退分支，行为完全等价。
- 判定：留空图只是把「不可达节点」留在图里，故与常量声明一起去掉；`ForeignKeyDeleteUtilBatchTest#cascadeGraph` 仍从根实体遍历真实图，全绿（§7）。

### 3.3 补删逻辑（canonical，禁止 mapper 裸删）

```
delete(id):
  stage==5 闸
  deleteStageApprovalAttachments(id)            // ← 新增，事务内、级联前
  deleteCascade(SalesOpportunityEntity, [id], 0)
  logicalDeleteById(id)

deleteStageApprovalAttachments(opportunityId):
  approvals   = salesStageApprovalMapper.selectAllApprovalsByOpportunityId(opportunityId)   // 复用既有查询，不新增 SQL
  approvalIds = approvals[*].id (nonNull)
  attachments = approvalAttachmentService.listEntitiesByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)   // 多态判别
  approvalAttachmentService.removeByIds(attachmentIds, ModelName.APPROVAL_ATTACHMENT)        // canonical：磁盘清理 + DB 物理删
```

- 顺序必要性：级联会在本事务内 **物理删** 审批行；附件清理必须在审批行消失前按 `and_id∈approvalIds` 收集，否则无法定位。
- 红线核对：未改迁移、未加依赖、未新增错误码、未动冻结契约、`frontend/` 零 diff、附件删除仅走 `removeByIds`（无 mapper 裸删）、
  未把 `and_id` 裸列写进级联图。

---

## 4. 鉴权差异论证（级联清理 vs `deleteStage`）

| 维度 | `SalesStageApprovalController#deleteStage` | 本卡 `deleteStageApprovalAttachments` |
|------|-------------------------------------------|----------------------------------------|
| 业务语义 | 独立端点：删「某几条审批」及其附件 | 内部级联：删「某个商机」时收尾子资源 |
| 权限校验点 | 端点级 `@RequirePermission(SALES_DELETE_STAGE_ADVANCE)` + **逐附件** `canWriteAttachments(model, andId, userId)` | 端点级商机删除权（既有）+ 系统级级联，不逐附件鉴权 |
| 为什么不同 | 单条审批可能属于他人申请、附件上传人各异，逐条校验才能防越权单删 | 父对象（商机）已整体删除，附件作为子资源必然随之消失；若逐附件鉴权，同一事务会因「上传人≠操作人」中途抛 `PERMISSION_DENIED` 导致整个删除回滚——正是本卡要消除的 P0 失败形态 |
| 权限面是否被放宽 | — | 未新增端点/权限码/参数；未放宽对「他人附件」的**独立**删除能力（该能力仍只由 `deleteStage`/附件删除端点承载，且仍逐条校验）；仅保留「删父对象时系统级清理子资源」这一条既有级联语义 |
| 残留风险 | — | 有商机删除权者可连带清掉他人上传的审批附件（与 `deleteCascade` 对 `sales_stage_approval` 等子表的既有系统级删除同源，本卡未改变该语义） |

---

## 5. registry 清空与门禁语义保持

- `KNOWN_DEAD_RELATIONS = Set.of()`（`ForeignKeyDeleteUtilBatchTest:68`）：死边不复存在，`dropped` 断言集为空。
- 「新增死映射即红」语义**未削弱**：`cascadeGraph` 仍按「实体（含父类）是否真实声明该属性」逐边过滤，并与登记表 `assertEquals` 精确对表——
  任何未来把不存在的属性写进 `ForeignKeyConfig` 的改动都会立刻转红，且完全不依赖 MP `TableInfo` 的 JVM 初始化顺序（这正是 2026-10-19 CI 首红的根因）。
- 代价：本类不再承担「已知死边白名单」职能，登记表回到空集（基线语境下的最严档）。

---

## 6. 全仓级联根盘点（覆盖面，实测 `grep -rn "deleteCascade\|logicalDeleteByCascade" src/main/java`）

| 根实体 | 入口（行号） | 图路径 | 可达 `sales_stage_approval`？ | 附件语义 |
|--------|--------------|--------|-------------------------------|----------|
| `SalesOpportunityEntity` | `SalesOpportunityServiceImpl#delete:164`（另有 `#cascadeDeleteByCompanyId:298` / `#cascadeDeleteByContactIds:308`，仅逻辑删商机行、不进级联图） | 直连 `sales_stage_approval(opportunity_id)` | **是（本卡唯一补删根）** | 本卡已按 canonical 补删 |
| `CustomerCompanyEntity` | `CustomerCompanyServiceImpl#deleteByIds:259`、`#logicalDeleteById:294` | `CUSTOMER_COMPANY_RELATIONS → SalesOpportunityEntity(company_id) → SalesStageApprovalEntity(opportunity_id)` | **是** | **未补删（F1）**：审批行被级联物理删，附件行+磁盘文件残留 |
| `CustomerContactEntity` | `CustomerContactServiceImpl#deleteByIds:261`、`#logicalDeleteByCascade:426`、`#batch...:437` | `CUSTOMER_CONTACT_RELATIONS → SalesOpportunityEntity(contact_id) → SalesStageApprovalEntity(opportunity_id)` | **是** | **未补删（F1）**：同上 |
| `ContractEntity` | `ContractServiceImpl:291` | `contract → contract_order_item/payment_record/invoice_info` | 否 | 不涉及 |
| `ContractOrderItemEntity` | `ContractOrderItemServiceImpl:101` | `contract_order_item → payment_record → invoice_info` | 否 | 不涉及 |

- **与任务卡预期不一致**：卡里写「预期仅商机删除一处」，实测为 **3 根**（商机 / 公司 / 联系人）。如实列出，未擅自扩写集。
- 补充边界（同一盘点）：`CustomerCompanyServiceImpl#deleteById:271`（逻辑删公司）与 `#cascadeDeleteByCompanyId:298` /
  `#cascadeDeleteByContactIds:308` 只做 `is_deleted` 位更新，**不进级联图**、不触碰审批/附件；真正进入级联图的是
  `#deleteByIds`（物理删）与 `#logicalDeleteById`（`logicalDeleteByCascade` 内部对子表仍是物理 `mapper.delete`）。
- 三条根共用同一张图，**修复前都会撞同一条死边**（公司/联系人删含已关闭商机的对象时同样抛 `ID_NOT_EXISTS` 全事务回滚）——本卡移除死边后一处修复、三根同时解除崩溃。
- **F1（交后续卡，本卡不扩写集）**：公司/联系人删除路径的审批附件清理缺失。建议把 `deleteStageApprovalAttachments` 抽成可复用支持类，由三根共用（或统一收口到「附件随审批行删除」的单一入口）。风险量级：不崩、不越权，只是残留 DB 行 + 磁盘文件（孤儿），可由后续卡按同一 canonical 口径补齐。
- **F2（顺带发现，未处理）**：`ForeignKeyConfig:95` 把 `TaskCommentEntity(taskId)` 挂在 `CUSTOMER_CONTACT_RELATIONS` 下（注释写的是「合同任务表」），
  即联系人删除会按 `task_comment.task_id == 联系人 id` 命中他人任务评论——Wave 0 起的既有配置漂移，不在本卡写集，建议单独立卡。

---

## 7. 测试清单与结果

### 7.1 定向（PowerShell 直跑，不经 Git Bash）

```
mvn -B -ntp "-Dtest=ForeignKeyDeleteUtilBatchTest,SalesOpportunityServiceTest" test
  → ForeignKeyDeleteUtilBatchTest      Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
  → SalesOpportunityServiceTest        Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
  → 合计 11/0/0/0   BUILD SUCCESS        （work/evidence/targeted-unit.log）

mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=SalesOpportunityDeleteAttachmentCascadeIT"
  → Tests run: 1, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS   （work/evidence/post-fix-it.log）
```

新 IT（`SalesOpportunityDeleteAttachmentCascadeIT`，failsafe `**/*IT.java`）断言面：

1. `salesOpportunityService.delete(已关闭商机)` 返回 `true`（修复前此处抛 `ID_NOT_EXISTS`）；
2. `sales_stage_approval WHERE opportunity_id=?` 归零（随 `deleteCascade` 现行语义物理删，日志实证 `删除子表 SalesStageApprovalEntity 记录数: 1`）；
3. 目标附件（`model_name=approval_attachment, and_id=审批id`）DB 行归零 + 磁盘文件 `Files.exists == false`；
4. **他模型同 `and_id` 对照附件（`model_name=business_activity`）DB 行存活 + 磁盘文件存活**（跨模型误删回归网）；
5. 商机本体走既有逻辑删除（`is_deleted=1`）。

单测新增委托断言：`InOrder` 强制 `listEntitiesByAndIds → removeByIds(canonical) → deleteCascade` 的先后关系；无阶段审批时 `listEntitiesByAndIds` 零调用。

### 7.2 全量

```
mvn -B -ntp clean verify   （docker info 实测在线：ServerVersion 29.6.2）
```

| 口径 | 结果 |
|------|------|
| surefire | 报告 283 份 / Tests run **1779** / Failures 0 / Errors 0 / Skipped 0 |
| failsafe | 报告 32 份 / Tests run **119** / Failures 0 / Errors 0 / Skipped 7 |
| 退出 | `MVN_EXIT=0`，`BUILD SUCCESS`，总耗时 15:38 min |

（日志 `work/evidence/clean-verify.log`；新 IT 已在该轮 failsafe 内真跑：`Running com.slz.crm.integration.controller.SalesOpportunityDeleteAttachmentCascadeIT`。）

---

## 8. 基线四元组

| 项 | 修复前（master@5024d8e 写入） | 本卡实测（`source-revision=56ca735`） | 结论 |
|----|-------------------------------|------------------------------------------|------|
| surefire.reports | 283 | 283 | 只增不减 |
| surefire.tests | 1777 | **1779**（+2：新增委托/顺序断言） | 只增不减 |
| surefire.skipped | 0 | 0 | 不增 |
| failsafe.reports | 31 | **32**（+1：新 IT） | 只增不减 |
| failsafe.tests | 118 | **119**（+1） | 只增不减 |
| failsafe.skipped | 7 | 7 | 不增 |

- `--update` 单次执行（树净，`source-revision=56ca735` 记实；`UPDATE_EXIT=0`）；复核模式 `check-test-baseline.sh` **EXIT=0**：
  `surefire 报告=283/Tests run=1779/Failures=0/Errors=0/Skipped=0`、`failsafe 报告=32/Tests run=119/Failures=0/Errors=0/Skipped=7`。
  证据：`work/evidence/baseline-update.log`、`work/evidence/baseline-check.log`。

---

## 9. merge-gate

`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → **10/10 PASS，`GATE_EXIT=0`**
（`PASS [unit]` / `[checkstyle]` / `[spotbugs]` / `[spotless]` / `[pmd]` / `[baseline]` / `[frontend-unit]` / `[hook]` / `[bijection]` / `[pmd-baseline]`；
`[it]` 默认序列不跑，已由 §7.2 的 `clean verify` 单独承载）。
typed-router 生成物噪声按门禁提示 `git checkout -- frontend/typed-router.d.ts` 还原（`frontend/` 最终零 diff）。日志：`work/evidence/merge-gate.log`。

---

## 10. 提交清单（分支 `feature/fix-approval-attachment-cascade-dead-edge`，均未 push）

| # | 哈希 | message |
|---|------|---------|
| 1 | `a688198` | `fix: drop unresolvable approval attachment cascade edge and clean stage approval attachments on opportunity delete` |
| 2 | `56ca735` | `style: keep single exit point in stage approval attachment cleanup helper` |
| 3 | `5ec0793` | `test(baseline): record cascade dead-edge fix baseline counts` |
| 4 | 本笔（docs） | `docs: record cascade dead-edge card materials, task ticks and handoff`（含本文件 + `openspec/changes/fix-approval-attachment-cascade-dead-edge/{proposal,tasks}.md`） |

> 第 2 笔的由来见 §12 D3（PMD `OnlyOneReturn` 首轮红；红线禁 amend，故独立成笔）。

---

## 11. tasks.md 勾选

见 `openspec/changes/fix-approval-attachment-cascade-dead-edge/tasks.md`：12 项全勾，每项下一行 6 空格 + 「补勾依据（日期 实测命令与结论）」。

---

## 12. 偏差清单

| id | 偏差 | 处置 |
|----|------|------|
| D1 | 任务卡预期「图可达 `sales_stage_approval` 的根仅商机删除一处」，实测 **3 根**（商机/公司/联系人） | 如实列入 §6；本卡只修商机根，公司/联系人根的附件残留登记为 F1 交后续卡（不擅自扩写集） |
| D2 | 执行提示词要求任务卡 `work/task-card-*.md`「不入库」，但 `tasks.md 4.4` 与 `.gitignore`（`!work/task-card-*.md`）期望入库 | 按执行提示词保持**未跟踪**；请复核者定夺是否随 docs 笔补入 |
| D3 | 首轮 `clean verify` 在 PMD 阶段红：`OnlyOneReturn` ×2（新支撑方法的多出口） | 重构为单出口（第二笔 `56ca735`），PMD 复跑 BUILD SUCCESS；未用 amend（红线） |
| D4 | 新 IT 初版未加 `@Sql(/init_data.sql)`，2 errors：`sales_opportunity.company_id` 外键（`fk_opportunity_company`）缺父行 | 补 `@Sql(scripts="/init_data.sql", BEFORE_TEST_METHOD)` 提供 `customer_company`/`sys_user` 种子；对外键命名约定（auto-table 由 `*_id` 推导 FK）无改动 |
| D5 | 前证临时用例 `preFixEvidence_deadEdgeThrowsAndRollsBack` 存在于工作分支但未随修复提交保留 | 其断言「必须抛 `ID_NOT_EXISTS`」在修复后恒红，已删除；输出摘录入 §2，原日志留证 |
| D6 | 证据日志落在 `work/evidence/`（被 `.gitignore` 的 `work/*` 覆盖，未跟踪） | 按「日志留证」保留，不入库；若复核要求入库请显式指示 |
| D7 | 台账 `--update` 前先提交实现（基线写入要求树净），故基线笔在实现笔之后 | 与任务卡「fix → test(baseline) → docs」顺序一致 |
