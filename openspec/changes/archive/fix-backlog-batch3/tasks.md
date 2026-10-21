# 任务清单 · fix-backlog-batch3

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. ⑤ 「已存在」精确码
- [x] 1.1 ErrorCode 新增 DATA_ALREADY_EXISTS(92029)；SQLExceptionHandler duplicate-key 三出口改 `Result.error(ErrorCode.DATA_ALREADY_EXISTS, 原文案)`（文案逐字节不变，code 0→92029；FK/缺省值路径不动）
      补勾依据（2026-10-21 `mvn -B -ntp clean verify` BUILD SUCCESS）：`ErrorCode.java` 新增 92029（92xxx 段下一可用号）；三出口 diff 只加 `ErrorCode.DATA_ALREADY_EXISTS` 参数、三处文案字面量一字未改；FK 路径与「缺少默认值」路径零 diff。
- [x] 1.2 测试：三出口码断言 92029 + 既有 SQLExceptionHandlerTest 断言同步（若锁 code==0 逐处列出）+ 前端影响面论证（apiClient code!==1）入 handoff
      补勾依据（2026-10-21 `mvn -B -ntp "-Dtest=SQLExceptionHandlerTest" test` 15/15 绿）：全仓唯一锁 `code==0` 的断言 SQLExceptionHandlerTest:117 已改 92029；新增 `duplicateKey_allThreeExits_carryPreciseCode`（三出口码 + 文案）与 `nonDuplicateKeyPaths_keepLegacyCode`（FK/缺省值仍 0）。前端影响面实证与 D1 见 `work/handoff-fix-backlog-batch3.md` §1.3/§1.4。

## 2. ⑨ 批量 IN 统一上限闸
- [x] 2.1 `common/untils` 新增 BatchIdsGuard（null/空集→PARAM_EMPTY；distinct 后 >MAX_BATCH_IDS(500)→PARAM_OUT_OF_RANGE；常量 javadoc 注明选型依据）
      补勾依据（2026-10-21 `BatchIdsGuardTest` 7/7 绿）：`BatchIdsGuard.java` 新增，`MAX_BATCH_IDS=500` + 三条选型依据；另覆盖「全 null 元素→PARAM_EMPTY」（见 handoff D2）。
- [x] 2.2 16 端点接入（每处一行）：BusinessActivityController:65 / ContactTaskController:106 / ContractController:95 / ContractOrderItemController:45 / CustomerCompanyController:84,108,284 / CustomerContactController:75,87,201 / InvoiceInfoController:54 / PaymentRecordController:54 / ProjectFileController:149 / SalesStageApprovalController:140 / TaskCommentController:35 / UserController:172
      补勾依据（2026-10-21 grep `BatchIdsGuard.check` 实测 16 命中）：@RequestBody 13 处 + @RequestParam 3 处；接入行号对照表见 handoff §2.2（改后行号，因插入一行整体后移）。ContractController 的闸放在 `try` 之前（handoff D6）。
- [x] 2.3 测试：BatchIdsGuard 单测（null/空集/恰 500/501/重复 id）+ 3 个代表性端点 MockMvc 抽查（@RequestBody / @RequestParam / 级联删各一），接入对照表入 handoff
      补勾依据（2026-10-21 `BatchIdsGuardTest` 7 + `BatchIdsGuardEndpointTest` 3 全绿）：三形态各一，空集→50001（service 零调用）、501→50007（文案含 500）、合规入参→照常下沉。

## 3. ⑩ batchLogicalDeleteByIds 批量单趟化
- [x] 3.1 改 `foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(CustomerCompanyEntity.class, idList)` + PARAM_EMPTY 前置闸 + afterCommit 逐公司向量清理语义保持
      补勾依据（2026-10-21 `mvn -B -ntp clean verify` 绿）：级联 N 轮→1 轮、主表 N 条 update→1 条 `update ... where id in (...)`、afterCommit 注册 N 次→1 次回调内逐公司派发；前置闸复用 `BatchIdsGuard.check`（同为 PARAM_EMPTY）。前后对照见 handoff §3.1。
- [x] 3.2 测试：级联单趟断言（verify logicalDeleteByCascadeBatch 恰一次）+ PARAM_EMPTY 闸 + 既有删除行为回归
      补勾依据（2026-10-21 `CustomerCompanyBatchLogicalDeleteTest` 4/4 绿）：委托恰一次且 ids 原样下沉、重复 id 仍一次、空入参/null 抛 PARAM_EMPTY 且零交互、单条入口 `logicalDeleteById` 不被批量入口牵连。

## 4. ⑪ 附件孤儿债
- [x] 4.1 removeByIds 跨模型误删修复（:148 改删过滤后 entities 的 id，与 removeSourceAttachmentsByDistinctIds 正确形态一致）
      补勾依据（2026-10-21 `ApprovalAttachmentRemoveByIdsTest` 2/2 绿）：混入他域 id 时 `deleteBatchIds` 只收过滤集（并 `never()` 断言原始 ids 形态不出现）；过滤集为空时零删除调用。
- [x] 4.2 三调用方物理级联删前补子宿主附件清理：CustomerCompanyServiceImpl.deleteByIds（BUSINESS_ACTIVITY + CONTACT_TASK 域）/ CustomerContactServiceImpl 物理删（CONTACT_TASK 域）/ SalesOpportunityServiceImpl.delete（BUSINESS_ACTIVITY + CONTACT_TASK 域）
      补勾依据（2026-10-21 `SalesOpportunityServiceTest` 6/6 + 全量 verify 绿）：新增协作类 `ChildHostAttachmentCleaner`（三入口、两级展开闭包、canonical `removeByAndIds`），三调用方均在 `deleteCascade` 之前调用；InOrder 锁「审批附件补删 → 子宿主清理 → deleteCascade」。ForeignKeyConfig 零 diff。
- [x] 4.3 测试：removeByIds 混入他域 id 不误删单测 + 孤儿债 IT（Testcontainers：公司物理删 → 子宿主附件行清零 + 跨模型同 and_id 对照存活）；assist_request 勘误（无洞）入 handoff
      补勾依据（2026-10-21 `CustomerCompanyPhysicalDeleteAttachmentCascadeIT` 1/1 绿，Docker 29.6.2 实测在线，mysql:8.0.36 真库）：公司物理删后活动/任务附件行清零 + 磁盘文件删除，跨模型同 and_id 对照附件行与文件均存活；assist_request 勘误见 handoff §4.4。

## 5. 验证与留证
- [x] 5.1 定向全绿 + 全量 mvn -B -ntp clean verify（Docker 实测）
      补勾依据（2026-10-21 `mvn -B -ntp clean verify` = BUILD SUCCESS / EXIT 0）：surefire 报告 303 / 用例 1889 / 失败 0 / 错误 0 / 跳过 0；failsafe 报告 34 / 用例 123 / 失败 0 / 错误 0 / 跳过 7。日志 `work/logs/verify-fix-backlog-batch3-run2.log`。
- [x] 5.2 树净单次 check-test-baseline.sh --update + 校验 EXIT=0 + merge-gate 10/10
      补勾依据（2026-10-21 树净 `bash scripts/check-test-baseline.sh --update` 写入 303/1889/0 + 34/123/7，source-revision=ceb8fdb；复跑 `bash scripts/check-test-baseline.sh` EXIT=0；`bash scripts/merge-gate.sh` 10/10 PASS：unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline，[it] 默认不跑）。日志 `work/logs/merge-gate-fix-backlog-batch3-run2.log`。
- [x] 5.3 handoff（四项前后对照/行为变化论证/勘误）+ tasks 勾选 + docs 笔；停步未 merge 未 push
      补勾依据（2026-10-21 提交链 ceb8fdb → 0d1c673 → 397e9b5 → docs 笔；handoff `D:\code\crmAndRag\work\handoff-fix-backlog-batch3.md` 含 ⑤ 前端实证与断言同步清单、⑨ 16 端点对照表、⑩ 单趟化前后对照、⑪ IT 证据与 assist_request 勘误、D1-D6 偏差与待裁决项；未 merge、未 push、未 reset/rebase/amend、未删分支）。
