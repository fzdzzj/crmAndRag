# 交接快照 · harden-security-attachment-reset-and-invoice

> 执行子 Agent 交付（2026-10-18/19，权威工作树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/harden-security-attachment-reset-and-invoice`，基点 master `8ffc623`）。**严格停步：未 merge 未 push，待 Owner 派发 A 档独立复核。**

## 1. 分支与提交序列（6 笔，单行 ASCII 消息）

| # | commit | 内容 |
|---|--------|------|
| 1 | `d5ede9e` | feat(security): enforce record-level write checks on project file uploads（模块 A 全部 + ProjectFileUploadSecurityTest） |
| 2 | `1fd19eb` | feat(security): guard super-admin password reset against tampering（模块 B + UserResetPasswordSecurityTest） |
| 3 | `ebefce7` | feat(security): bring invoice_info under data scope and void ownership check（模块 C + InvoiceDataScopeSecurityTest） |
| 4 | `47ad58b` | chore(contract): sync openapi export for reset password dto validation（openapi.yaml +4 行 / SDK 重生成 / 2 个前端组件连锁适配） |
| 5 | `d6db1fa` | test(baseline): update clean baseline to 47ad58b（surefire 270/1697/0 + failsafe 31/118/7） |
| 6 | docs 收尾 | docs(card): record harden-security-attachment-reset-and-invoice evidence and handoff（本 handoff + tasks.md 勾选 + 任务卡） |

## 2. 写集清单（主代码 11 文件 +91/−73，测试 3 新增，契约+前端 3 文件）

**模块 A（附件写权限拦截与废弃收敛，H-2/A-2/A-3/B-10）**
- `common/enumeration/ModelName.java`：新增 `CONTRACT_ORDER_ITEM = "contract_order_item"` 常量（执行提示词假定了该常量已存在，实测缺失，系最小必要新增）。
- `server/service/impl/AttachmentModelScopeChecker.java`：构造器增加 contractMapper/contractOrderItemMapper 参数；`canWrite` 增加 `CONTRACT_ORDER_ITEM` 路由 → 新私有方法 `canWriteContractOrderItem`：订单项实体无属主字段，经 contractId 反查合同后按 `ownerId 或 creatorId` 判定（与读侧 `canReadContract` 归属矩阵一致；不加功能级权限闸，与其他写分支语义一致）。**不补此路由则非超管一律 default→false 全拒，合法上传被误伤**。
- `server/service/impl/AttachmentAccessServiceImpl.java`：向 checker 构造传参 2 行（自身构造签名未变，既有调用方/测试零波及）。
- `server/service/impl/ProjectFileServiceImpl.java`：`uploadByActivity`/`uploadByOrder` 在查出实体、取到 currentUserId 后前置 `canWriteAttachments` 判定，无权抛 12002 文案见 tasks.md。
- `server/service/ApprovalAttachmentService.java` + `impl/ApprovalAttachmentServiceImpl.java`：删除零调用点废弃方法 `removeByApprovalIds`/`getByApprovalIds`（全仓 grep 证实含测试在内零调用；`saveAttachments(Long,List)` 旧重载仍被 saveApprovalAttachments 使用，不在删除范围）。
- `server/controller/SalesStageApprovalController.java`：`updateStage` 15 行内联附件校验/存储重复块替换为 `saveApprovalAttachments(vo.getId(), dto.getApprovalAttachment())`，过滤语义等价。
- 测试：`src/test/java/com/slz/crm/unit/service/ProjectFileUploadSecurityTest.java`（12 用例：mock 层拦截/放行 4 + 真实 AttachmentAccessServiceImpl 路由 8）。

**模块 B（重置密码防篡改，H-4）**
- `pojo/dto/ResetUserPasswordDTO.java`：`userId` 加 `@NotNull`、`newPassword` 加 `@NotBlank`（中文 message 与卡一致）。
- `server/service/impl/UserServiceImpl.java`：类补 `@Slf4j`；`resetUserPassword` 在目标用户判存之后、空密码校验之前插入超管防篡改闸（`roleId=1 且 操作人≠本人 → 12002 "仅超级管理员本人可重置超级管理员密码"`）；更新成功后 `log.info("管理员重置用户密码成功: operatorId={}, targetUserId={}")`。
- 测试：`src/test/java/com/slz/crm/server/service/UserResetPasswordSecurityTest.java`（6 用例）。

**模块 C（invoice_info 纳管与作废收敛，I-5）**
- `server/constant/ResourceTypeConstant.java`：`MANAGED_TABLES` 增 `invoice_info`（11 表）；`TABLE_USER_FIELDS` 由 `Map.of` 改 `Map.ofEntries` 后增 `invoice_info → [creator_id]`（原 `Map.of` 恰 10 键已达上限，不改写法编译即错）。
- `server/service/impl/InvoiceInfoServiceImpl.java`：`voidInvoice` 在既有"不存在/已作废"检查之后加属主闸（操作人 = creatorId 或 operator.roleId=1，否则 12002）；两处裸抛规范化为 `BaseException(DATA_NULL, "开票信息不存在")`（91001）、`BaseException(BUSINESS_ERROR, "发票已作废，无法重复作废")`（92000）。userMapper/Objects/BaseUnit import 补齐。
- 测试：`src/test/java/com/slz/crm/unit/server/aspect/InvoiceDataScopeSecurityTest.java`（8 用例，JDK 代理 + 切面注入验证 + 作废矩阵）。

**契约与前端（连锁，同一提交 47ad58b）**
- `frontend/openapi.yaml`：springdoc 导出覆盖，仅 +4 行（`minLength: 1`、`required: [newPassword, userId]`）。
- `frontend/src/api/axios`：`pnpm gen:api` 重生成（目录 gitignore 不入库）。
- `frontend/src/components/user/ResetPasswordModal.vue`：表单 ref 类型改 `{ userId?: number; newPassword: string }`（userId 打开弹窗时回填，运行时行为不变）。
- `frontend/src/components/user/EditUserModal.vue`：`openResetPasswordModal` 对 `form.value.id` 判空后再调（SDK 收紧后 userId 必填）。

## 3. 实测流水

1. **定向测试**（`mvn test -Dtest=三新类`）：26/26 全绿（12+6+8）。首次 2 失败为既有 `BaseException(String)` 构造 code=null 旧习，断言改消息口径后全绿（未越界改服务代码）。
2. **首轮 clean verify**：surefire 1697 中仅 `OpenApiContractExportTest` 1 失败 = DTO 注解引发的契约漂移（预期内）；按卡预案 `check-openapi-consistency.sh --update`（导出测试 2/2 绿）+ `pnpm gen:api` + 前端 2 组件适配，预提交钩子 lint+type-check 全绿。
3. **终轮 `mvn -B -ntp clean verify`**：BUILD SUCCESS，12:47 min，Docker 29.6.2。Surefire **1697/0/0/0**（= 1671 基线 + 26 新增，只增不减）；Failsafe **118/0/0**，Skipped 7（既有环境条件跳过口径不变）。日志 `work/_verify-harden-security-2.log`（VERIFY_EXIT=0）。
4. **基线**：树净状态单次 `check-test-baseline.sh --update` → `source-revision=47ad58b`，surefire.reports=270/tests=1697/skipped=0，failsafe.reports=31/tests=118/skipped=7；复跑校验模式 EXIT=0；提交 `d6db1fa`。
5. **merge-gate**：`bash scripts/merge-gate.sh` **MERGEGATE_EXIT=0，10/10 PASS**：unit / checkstyle / spotbugs / spotless / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline。日志 `work/_mergegate-harden-security.log`。PMD NCSS 无新违规（新增代码均远低于拆分阈值，未触发协作类拆分）。

## 4. 红线自检

- ✅ 零数据库迁移：`git diff master --stat -- src/main/resources/db/migration` 空（`invoice_info.creator_id` 基线 V1 已实存，无需迁移）。
- ✅ 零新增依赖：`git diff master --stat -- pom.xml` 空。
- ✅ `frontend/typed-router.d.ts`：merge-gate 前端门禁重生成 +17 行噪声后已立即 `git checkout --` 还原，工作树零残留。
- ✅ 提交全部单行内联 ASCII；分支自 master `8ffc623` 检出。
- ✅ **未执行 `git merge`、未执行 `git push`**，严格停步待复核。
- ✅ 收尾工作树干净（仅剩本卡 openspec/ 与 work/ 规范文件随 docs 提交入库）。

## 5. 假设与偏差披露（供复核聚焦）

1. **`ModelName.CONTRACT_ORDER_ITEM` 系新增**（提示词假定为既有常量）；配套在 `AttachmentModelScopeChecker.canWrite` 补路由，属使提示词语义成立的必要实现，否则该判定对非超管恒 false。
2. **订单项写权限的属主定义** = 合同 `ownerId 或 creatorId`（实体无 creator 字段；与读侧订单反查合同的归属矩阵一致）。若 Owner 认定应更严（仅 ownerId），单点改 `canWriteContractOrderItem` 即可。
3. **`voidInvoice` 权限闸位置**在既有"已作废"检查之后（保持原错误顺序语义：先 92000 后 12002）；如需"先鉴权后状态"可调序，属语义选择非缺陷。
4. **既有 `BaseException(String)` 旧习**（`PARAM_EMPTY`/`ID_NOT_EXISTS` 两处 code=null）未在模块 B 范围内改动，测试按消息断言并已披露。
5. **首轮 verify 的 openapi 失败**是 DTO 校验注解的必然契约漂移，按卡预案走导出覆盖，无手工改动契约内容。
6. 行为面变化：`uploadByActivity`/`uploadByOrder` 与 `voidInvoice` 对越权者从"静默成功"变为 12002 拦截 —— 即本卡目的；`updateStage` 附件链路行为等价重构。

## 6. 复核入口建议

- 代码焦点：`AttachmentModelScopeChecker.canWriteContractOrderItem`（新路由语义）、`UserServiceImpl.resetUserPassword`（闸序与审计）、`InvoiceInfoServiceImpl.voidInvoice`（闸序与错误码）、`ResourceTypeConstant`（Map.ofEntries 改写等价性）。
- 复跑命令：`mvn -B -ntp clean verify`（Docker 在线）；`bash scripts/check-test-baseline.sh`；`bash scripts/merge-gate.sh`；定向 `mvn test -Dtest='ProjectFileUploadSecurityTest,UserResetPasswordSecurityTest,InvoiceDataScopeSecurityTest'`。
- 留存日志：`work/_verify-harden-security.log`（首轮含契约失败证据）、`work/_verify-harden-security-2.log`（终轮全绿）、`work/_mergegate-harden-security.log`（10/10）。
