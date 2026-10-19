# 任务清单 · harden-security-attachment-reset-and-invoice

## 任务组 1 · 模块 A：附件越权拦截与废弃代码收敛

- [x] 1.1 在 `ProjectFileServiceImpl.uploadByActivity` 与 `uploadByOrder` 前置 `attachmentAccessService.canWriteAttachments` 属主判断，无权拦截抛出 12002 `PERMISSION_DENIED`
      实测：两入口分别挂 `ModelName.BUSINESS_ACTIVITY` / 新增常量 `ModelName.CONTRACT_ORDER_ITEM` 判定，越权文案「无权向该业务活动上传附件」「无权向该订单上传附件」；提交 d5ede9e。
      实测：`AttachmentModelScopeChecker` 新增 `canWriteContractOrderItem` 路由（订单项无属主字段，经 contractId 反查合同 ownerId/creatorId 判定），`AttachmentAccessServiceImpl` 构造注入 contractMapper/contractOrderItemMapper。
      实测：`ProjectFileUploadSecurityTest` 12/12 绿（拦截 2 例断言 12002 + 零落盘零落库；放行 2 例真实写盘+落库并绑定上传人；真实授权服务路由判定 8 例含超管直通/非在职 fail-closed）。
- [x] 1.2 清理 `ApprovalAttachmentService` 与 `ApprovalAttachmentServiceImpl` 中无任何调用点的废弃方法 `removeByApprovalIds` 和 `getByApprovalIds`，同步清理实现
      实测：全仓 grep 确认两方法仅接口+实现共 4 处定义、零调用点（含测试）；接口与实现同步删除，d5ede9e 后编译与 checkstyle 全绿。
- [x] 1.3 `SalesStageApprovalController.updateStage` 消除附件校验重复代码，统一复用私有协作方法 `saveApprovalAttachments`
      实测：POST /approval 的 15 行内联重复块替换为 `saveApprovalAttachments(vo.getId(), dto.getApprovalAttachment())`，与 saveDraft/updateDraft 同链；fileData/fileName 非空过滤语义等价保留。
- [x] 1.4 编写 `ProjectFileUploadSecurityTest` 专项安全单测，覆盖越权上传拦截与合法上传放行
      实测：`src/test/java/com/slz/crm/unit/service/ProjectFileUploadSecurityTest.java` 12 用例定向 `mvn test -Dtest` 全绿（Tests run: 12, Failures: 0, Errors: 0）。

## 任务组 2 · 模块 B：重置密码防提权与审计留痕

- [x] 2.1 `ResetUserPasswordDTO` 属性增加 `@NotNull(message = "目标用户ID不能为空")` 与 `@NotBlank(message = "新密码不能为空")` 校验
      实测：jakarta 注解落地，springdoc 导出新增 `minLength: 1` 与 `required: [newPassword, userId]`（openapi.yaml +4 行，提交 47ad58b）。
- [x] 2.2 `UserServiceImpl.resetUserPassword` 增加终极管理员（roleId=1）防篡改保护（非超管本人重置超管拦截抛 12002 `PERMISSION_DENIED`）并输出 `log.info` 安全审计日志
      实测：目标用户查出后前置 `roleId=1 且操作人非本人 → BaseException(PERMISSION_DENIED, "仅超级管理员本人可重置超级管理员密码")`；成功更新后 `log.info("管理员重置用户密码成功: operatorId={}, targetUserId={}")`；类补 `@Slf4j`。
- [x] 2.3 编写 `UserResetPasswordSecurityTest` 专项安全单测，覆盖超管防篡改拦截、空字段校验与正向重置审计
      实测：`src/test/java/com/slz/crm/server/service/UserResetPasswordSecurityTest.java` 6 用例定向全绿（越权 12002 零 SQL；超管本人/普通用户正向放行且 `PasswordHashUtil.matches` 验密；空/空白密码与目标缺失拦截零 SQL；DTO 校验注解断言两违规文案）。
      实测偏差：既有 `PARAM_EMPTY`/`ID_NOT_EXISTS` 两处为 `BaseException(String)` 旧习构造（code=null），属模块 B 范围外既有行为，测试按消息断言不掩盖。

## 任务组 3 · 模块 C：`invoice_info` 纳管与作废权限收敛

- [x] 3.1 `ResourceTypeConstant.MANAGED_TABLES` 加入 `"invoice_info"`，`TABLE_USER_FIELDS` 映射为 `List.of("creator_id")`，闭环 DataScope 自动切面注入
      实测：MANAGED_TABLES 增至 11 表；`TABLE_USER_FIELDS` 由 `Map.of`（10 键上限）改写 `Map.ofEntries` 增至 11 映射，语义不变。
      实测：基线迁移 V1 中 `invoice_info.creator_id bigint not null` 实存，实体 `InvoiceInfoEntity.creatorId` 对齐，零迁移改动。
- [x] 3.2 `InvoiceInfoServiceImpl.voidInvoice` 增加操作人身份与属主权限校验（仅创建人或超管可作废），规范异常为包含错误码的 `BaseException`
      实测：前置 `BaseUnit.getCurrentId()` + userMapper 查角色，`仅发票创建人或超级管理员可作废发票` 12002；裸抛两处规范化为 `BaseException(DATA_NULL, "开票信息不存在")`（91001）与 `BaseException(BUSINESS_ERROR, "发票已作废，无法重复作废")`（92000），权限闸置于既有已作废检查之后保持原顺序语义。
- [x] 3.3 编写 `InvoiceDataScopeSecurityTest` 专项安全单测，覆盖发票列表 DataScope 隔离过滤与作废越权拦截
      实测：`src/test/java/com/slz/crm/unit/server/aspect/InvoiceDataScopeSecurityTest.java` 8 用例定向全绿：InvoiceInfoMapper JDK 代理下 QueryWrapper/LambdaQueryWrapper 两形状均验证切面以表名 `invoice_info` 调用 `dataScopeService.addDataScopeCondition`；常量映射断言受管表+creator_id；作废越权 12002 零 SQL、创建人/超管放行且状态置 1、重复作废 92000、缺失 91001。

## 任务组 4 · 契约、门禁与基线收网

- [x] 4.1 校验或更新 `frontend/openapi.yaml` 契约一致性（`bash scripts/check-openapi-consistency.sh`）
      实测：首轮 clean verify 于 `OpenApiContractExportTest` 检出 DTO 注解引发的契约漂移（唯一失败），按卡预案 `bash scripts/check-openapi-consistency.sh --update` 导出覆盖（+4 行）+ frontend `pnpm gen:api` 重生成 SDK，同提交 47ad58b 内适配 `ResetPasswordModal.vue`/`EditUserModal.vue`（userId 收紧为必填的连锁类型修正），pnpm lint:check + type-check:check 全绿。
      实测：复验 `bash scripts/check-openapi-consistency.sh` 输出「契约一致」，merge-gate [hook] 门 PASS。
- [x] 4.2 运行 `mvn -B -ntp clean verify` 全量回归构建，验证 Surefire 1671+ 用例全绿、Docker IT 118 跑通过，并单次更新 `scripts/test-baseline.txt`
      实测：`mvn -B -ntp clean verify` BUILD SUCCESS（12:47 min，Docker 29.6.2 在线）：Surefire **Tests run: 1697, Failures: 0, Errors: 0, Skipped: 0**（1671 基线 + 26 新增，只增不减）；Failsafe **Tests run: 118, Failures: 0, Errors: 0, Skipped: 7**（既有环境条件跳过口径不变）。日志 `work/_verify-harden-security-2.log`，VERIFY_EXIT=0。
      实测：树净状态（diff-index clean）单次 `bash scripts/check-test-baseline.sh --update` 写入 `source-revision=47ad58b`：surefire.reports=270/tests=1697/skipped=0，failsafe.reports=31/tests=118/skipped=7；复跑校验模式「回归基线门禁通过」EXIT=0；提交 d6db1fa。
- [x] 4.3 运行 `bash scripts/merge-gate.sh`，确保 10/10 门禁全 PASS，还原 `frontend/typed-router.d.ts` 生成物噪声
      实测：`bash scripts/merge-gate.sh` MERGEGATE_EXIT=0，10/10 全 PASS：unit / checkstyle / spotbugs / spotless / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline。日志 `work/_mergegate-harden-security.log`。
      实测：门禁改写 `frontend/typed-router.d.ts`（+17 行生成噪声）后立即 `git checkout -- frontend/typed-router.d.ts` 还原，工作树零残留。
- [x] 4.4 产出 `work/handoff-harden-security-attachment-reset-and-invoice.md` 并完整勾选本清单且附实测证据行
      实测：handoff 已编写并随 docs 提交入库；分支提交序列 d5ede9e → 1fd19eb → ebefce7 → 47ad58b → d6db1fa → docs 收尾，严格停步待 A 档独立复核，未 merge 未 push。
