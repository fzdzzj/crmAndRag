# A 档独立复核报告 · harden-security-attachment-reset-and-invoice

复核结论：GO
CRITICAL 0 / MAJOR 0 / MINOR 1。全部证据系复核侧在权威工作树 D:\code\crmAndRag-merge-add-knowledge-admin-api（分支 feature/harden-security-attachment-reset-and-invoice，tip 6dfafcb）独立实跑取得，未采信执行方日志。复核全程零 merge、零 push、零源码/台账改动；结束后 frontend/typed-router.d.ts 已 checkout 还原，最终 git status --porcelain 为空（0 脏文件）。

## 逐项核验结果

| 项 | 结论 | 一手证据 |
|---|---|---|
| R-1 写集与提交边界 | PASS | git log master..HEAD 恰 6 笔（d5ede9e→1fd19eb→ebefce7→47ad58b→d6db1fa→6dfafcb），全单行 ASCII；22 文件 +1074/−79 与卡范围一致；负向三项零 diff：db/migration、pom.xml、frontend/typed-router.d.ts，实测最高迁移号 V42（V43 未动用） |
| R-2 附件写权限与废弃清理 | PASS | uploadByActivity(65 行)/uploadByOrder(127 行) 在写磁盘列表构造之前挂 canWriteAttachments，无权抛 PERMISSION_DENIED(12002)；重点披露项 canWriteContractOrderItem(112 行) 经 contractId 反查合同按 ownerId ‖ creatorId 判定，订单项/合同缺失均 fail-closed 返 false，与读侧 ProjectFileAttachmentReader.canReadContract 归属矩阵逐字同构；removeByApprovalIds/getByApprovalIds 全仓 grep 残留 0；updateStage 已并入私有 saveApprovalAttachments（60/72/91 三处统一） |
| R-3 密码重置防提权 | PASS | DTO @NotNull/@NotBlank 落地，控制器入口 @Valid + @RequirePermission(SYSTEM_UPDATE_USER) 使其生效；闸序为 判存→防篡改（roleId=1L 且非本人→12002）→判空→更新落库；log.info 记录 operatorId 与 targetUserId 双 ID |
| R-4 发票纳管与作废 | PASS | MANAGED_TABLES 含 invoice_info；Map.of→Map.ofEntries 11 键与原 10 键逐一等价（逐行比对），新增映射 creator_id 正确；voidInvoice 属主闸（超管 roleId=1 或创建人，否则 12002）位于状态检查后；两处裸抛规范化为 DATA_NULL(91001)/BUSINESS_ERROR(92000)（常量实测存在） |
| R-5 测试与门禁独立复跑 | PASS | 见下方明细 |
| R-6 物料与台账对账 | PASS | tasks.md 14/14 勾选且每项带实测证据行（含 2.3 实测偏差披露）；handoff 六笔提交序列、写集、红线自检、6 项偏差披露与实测全部对账一致；三份留存日志（_verify-harden-security{,-2}.log、_mergegate-harden-security.log）实存 |

## R-5 独立复跑明细（全部本轮实测）

- 靶向单测：Tests run: 26, Failures: 0, Errors: 0, Skipped: 0（12+6+8），BUILD SUCCESS。断言非空洞：越权例断言 12002 错误码+文案+never() 零 SQL/零落盘，放行例用 ArgumentCaptor 回验落库绑定与 PasswordHashUtil.matches 密文，路由例走真实 AttachmentAccessServiceImpl（含超管直通零业务查询、非在职 fail-closed）。
- 全量 clean verify：BUILD SUCCESS（12:17 min，Docker 29.6.2）。Surefire 1697/0/0/0（=1671+26 精确）；Failsafe 118/0/0，Skipped 7（既有口径不变）。
- 基线对账：check-test-baseline.sh EXIT=0，实测 surefire 报告 270/tests 1697/skip 0 + failsafe 31/118/7 与 scripts/test-baseline.txt 精确吻合，source-revision=47ad58b，工作树净故无 -dirty。
- 聚合门禁：merge-gate.sh MERGEGATE_EXIT=0，10/10 PASS（unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline）；跑后已还原 typed-router 生成物。
- 静态分析：spotbugs:check BugInstance 0、pmd:check BUILD SUCCESS（STATIC_EXIT=0）；NCSS 无超限（pmd 门 0 违规 + pmd-baseline 实测 0==登记 0==阈值 0）。

## MINOR 记录（1 条，不阻断）

M-1：resetUserPassword 内两处既有 BaseException(String) 抛出（PARAM_EMPTY/ID_NOT_EXISTS）产生 code=null 异常，测试只能按消息断言无法断错误码。属范围外既有旧习（与 [[card-harden-user-password-and-privacy-status]] 同坑），执行方已在 tasks.md 2.3 与 handoff §5.4 如实披露且未越界改服务代码——处置正确；建议留给后续 BaseException 治理卡统一收敛。

另记两条观察（非缺陷，均有披露）：voidInvoice 权限闸置于"已作废"检查之后（先 92000 后 12002），handoff §5.3 披露为语义选择；复核过程中 Git Bash 向 mvn.cmd 传 -Dtest 逗号列表会被注入空格致 surefire 匹配失败（环境噪声，改脚本文件方式即复现通过，非代码问题）。

## 交付 Owner 裁决意见

复核侧建议：授权以 git merge --no-ff 将 feature/harden-security-attachment-reset-and-invoice（tip 6dfafcb）合入 master，合并后照例更新回归基线引用并归档本卡 openspec 目录。分支未 push，push 与否由 Owner 另行拍板。
