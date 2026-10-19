# 变更提案 · harden-security-attachment-reset-and-invoice（附件鉴权、密码重置与发票纳管加固，A 档）

## 1. 背景与动机

根据全面安全侦察与台账梳理，当前系统存在三项具有直接安全与权限隐患的未收口点：
1. **附件写权限缺失（H-2 / B-10）**：`ProjectFileServiceImpl` 的 `uploadByActivity` 与 `uploadByOrder` 仅有功能级权限，未对业务活动或订单记录调用 `AttachmentAccessService.canWriteAttachments` 做记录级鉴权，任意登录用户可跨属主注入恶意文件；同时控制器存在重复代码与废弃接口（A-2 / A-3）；
2. **管理员重置密码防线虚设（H-4）**：`ResetUserPasswordDTO` 属性无校验注解，`UserServiceImpl.resetUserPassword` 允许任意管理员重置终极管理员（roleId=1）的密码，且无任何安全审计日志留痕；
3. **`invoice_info` 游离数据权限受管表之外（I-5）**：`ResourceTypeConstant.MANAGED_TABLES` 纳管了 `payment_record` 却遗漏了同属财务核心实体的 `invoice_info`，导致全部开票记录对所有业务员裸奔无 DataScope 过滤；发票作废亦无属主权限防线。

## 2. 变更目标与范围

- **模块 A（附件越权拦截与废弃收敛）**：
  - `ProjectFileServiceImpl` 在 `uploadByActivity` 与 `uploadByOrder` 前置 `attachmentAccessService.canWriteAttachments` 记录级校验，无权抛 12002 `PERMISSION_DENIED`；
  - 彻底清理 `ApprovalAttachmentService` 与 `ApprovalAttachmentServiceImpl` 中未调用的 `@Deprecated` 废弃方法 `removeByApprovalIds` 和 `getByApprovalIds`；
  - `SalesStageApprovalController.updateStage` 消除附件校验重复代码，复用 `saveApprovalAttachments`。
- **模块 B（重置密码安全加固与防提权）**：
  - `ResetUserPasswordDTO` 增加 `@NotNull` / `@NotBlank` 校验；
  - `UserServiceImpl.resetUserPassword` 增加超管防篡改保护（非超管本人重置超管抛 12002）与安全审计日志留痕（`log.info` 记录操作者与目标用户）。
- **模块 C（`invoice_info` 纳管与作废权限收敛）**：
  - `ResourceTypeConstant.MANAGED_TABLES` 加入 `"invoice_info"`，`TABLE_USER_FIELDS` 映射为 `List.of("creator_id")`，闭环 DataScope 自动切面注入；
  - `InvoiceInfoServiceImpl.voidInvoice` 增加作废属主或管理员权限校验，并规范化错误码。
- **测试与门禁**：
  - 编写越权上传拦截、超管重置保护、发票 DataScope 隔离与作废越权拦截专项安全单元测试；
  - 保证全量 `mvn clean verify`（Surefire 用例数在 1671 基础上只增不减，Docker IT 全绿）与 `merge-gate.sh` 10/10 门禁全绿。

## 3. 边界与红线

- 零数据库迁移：`src/main/resources/db/migration/` 零改动（最高版本维持 V42，V43 未动用）；
- 零新增依赖：`pom.xml` 零改动；
- 零契约破坏：`openapi.yaml` 保持一致（若有 DTO 校验变动，执行导出覆盖）；
- `frontend/typed-router.d.ts` 零改动（若受构建影响立即还原）；
- 提交必须单行内联 ASCII，严禁私自 `git merge` 或 `git push`；
- 按照规范严格执行 A 档复核。
