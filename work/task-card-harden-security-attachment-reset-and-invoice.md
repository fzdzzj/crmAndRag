# 任务卡 · harden-security-attachment-reset-and-invoice（附件鉴权、密码重置与发票纳管加固，A 档）

> 目标：收敛业务活动与订单项目文件上传写权限（H-2）、加固管理员重置密码防超管篡改与审计（H-4）、纳管 `invoice_info` 入 DataScope 受管表与作废防线（I-5）。

## 边界与红线

- 零数据库迁移：`src/main/resources/db/migration/` 零改动（下一可用迁移版本仍为 V43）；
- 零新增依赖：`pom.xml` 零改动；
- 契约一致性：若 DTO 注解变更引发 openapi diff，走导出覆盖同步，不破坏前端；
- 严禁触碰 `frontend/typed-router.d.ts`（若被生成工具改动立即 checkout 还原）；
- 提交规范：所有 commit 信息必须使用 `-m "xxx"` 内联 ASCII 简短单行语句。

## 核心锚点

- **模块 A（附件鉴权与废弃收敛）**：`ProjectFileServiceImpl.uploadByActivity` 与 `uploadByOrder` 前置 `attachmentAccessService.canWriteAttachments` 属主判断（越权抛 12002 `PERMISSION_DENIED`）；移除 `ApprovalAttachmentService` 废弃方法；`SalesStageApprovalController.updateStage` 复用私有保存逻辑；
- **模块 B（重置密码安全加固）**：`ResetUserPasswordDTO` 增加 `@NotNull`/`@NotBlank` 校验；`UserServiceImpl.resetUserPassword` 增加非超管禁止重置超管密码防线（12002）与安全审计日志；
- **模块 C（`invoice_info` 纳管与作废收敛）**：`ResourceTypeConstant.MANAGED_TABLES` 加入 `"invoice_info"`，`TABLE_USER_FIELDS` 映射为 `List.of("creator_id")`；`InvoiceInfoServiceImpl.voidInvoice` 增加创建人/超管鉴权；
- **专项测试**：分别编写专项安全单元测试覆盖越权拦截与正向放行。

## 验收要求

- 专项测试全部通过；
- `mvn -B -ntp clean verify` 全绿（Docker Testcontainers 118 IT 全过）；
- `bash scripts/merge-gate.sh` 10/10 门禁全 PASS，Surefire 用例数在 1671 基础上只增不减；
- 产出 `work/handoff-harden-security-attachment-reset-and-invoice.md` 并完成 `tasks.md` 各项勾选与 6 空格实测证据行。

## 交付纪律

- 分支：`feature/harden-security-attachment-reset-and-invoice`（从最新 `master` 检出，完工工作树干净）；
- 完工严格停步待复核：严禁私自 `git merge` 或 `git push`！
