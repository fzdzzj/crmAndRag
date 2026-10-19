# 任务清单 · fix-approval-attachment-cascade-dead-edge

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. 真库复证与前证
- [x] 1.1 Docker MySQL IT 搭建：seed 已关闭商机(stage=5)+阶段审批+审批附件(model_name=approval_attachment, and_id=审批id)+他模型对照附件(business_activity, 同 and_id)
      补勾依据（2026-10-19 `src/test/java/com/slz/crm/integration/controller/SalesOpportunityDeleteAttachmentCascadeIT.java` 建立；Testcontainers mysql:8.0.36 + `@Sql(/init_data.sql)` 供 FK 父行；固定主键商机 9500001 / 审批 9600001；`mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=SalesOpportunityDeleteAttachmentCascadeIT"` 实测 Tests run: 1 / F0 / E0 / S0）
- [x] 1.2 修复前实测：service.delete 抛 ID_NOT_EXISTS、事务回滚——证据（异常栈/断言输出）入 handoff
      补勾依据（2026-10-19 修复前同命令：Tests run: 2, Failures: 1, Errors: 1，异常 `BaseException: 【com.slz.crm.pojo.entity.ApprovalAttachmentEntity.approvalId】不存在`；临时回滚用例单跑 Tests run: 1 / F0 / E0，断言阶段审批 1 行、目标附件 1 行、对照附件 1 行仍全在且商机 is_deleted=0；摘录入 handoff §2，日志 work/evidence/pre-fix-it.log、pre-fix-evidence-only.log）

## 2. 修复（拍板形态 C）
- [x] 2.1 ForeignKeyConfig 移除 :169-171 死边（含注释）；ALL_RELATIONS:62 附件子图条目保留性实测评估
      补勾依据（2026-10-19 死边已删并留成因注释（现 :163-170）；`grep -rn ApprovalAttachmentEntity src/main/java` 在配置类零命中 → 附件子图恒空、无任何边指向该实体，故空图条目与其常量声明一并移除；`mvn -B -ntp "-Dtest=ForeignKeyDeleteUtilBatchTest" test` Tests run: 5 / F0 / E0）
- [x] 2.2 SalesOpportunityServiceImpl.delete：deleteCascade 前显式补删——收集 approvalIds → listEntitiesByAndIds(·, ModelName.APPROVAL_ATTACHMENT) → removeByIds（canonical 含磁盘清理）；@Transactional 内；级联不逐附件鉴权的差异论证落 handoff
      补勾依据（2026-10-19 实现见 SalesOpportunityServiceImpl:162 调用 + :190 `deleteStageApprovalAttachments`（复用 `selectAllApprovalsByOpportunityId`，无新增 SQL）；`mvn -B -ntp "-Dtest=SalesOpportunityServiceTest" test` Tests run: 6 / F0 / E0，含 InOrder 断言 canonical removeByIds 先于 deleteCascade；鉴权差异论证见 handoff §4）
- [x] 2.3 ForeignKeyDeleteUtilBatchTest：KNOWN_DEAD_RELATIONS 清空，「新增死映射即红」语义保持
      补勾依据（2026-10-19 `KNOWN_DEAD_RELATIONS = Set.of()`（ForeignKeyDeleteUtilBatchTest:68）；cascadeGraph 仍按 hasProperty 逐边过滤并与空登记表 assertEquals，新增死映射立即红；同命令 Tests run: 5 / F0 / E0）
- [x] 2.4 全仓盘点 deleteCascade/logicalDeleteByCascade 调用方图可达 sales_stage_approval 的根（预期仅商机删除），覆盖面落 handoff
      补勾依据（2026-10-19 `grep -rn "deleteCascade\|logicalDeleteByCascade" src/main/java` 得 5 个根（商机/公司/联系人/合同/合同订单项），图可达 sales_stage_approval 实为 3 个（商机直连、公司经 sales_opportunity.company_id、联系人经 sales_opportunity.contact_id）；预期「仅一处」不成立，已如实列入 handoff §6 并登记 F1/F2，未扩写集）

## 3. 测试
- [x] 3.1 IT 修复后全绿：删除成功、审批随级联、目标附件物理删（DB+磁盘）、他模型对照附件存活
      补勾依据（2026-10-19 同 IT 命令修复后 Tests run: 1 / F0 / E0 / S0；断言含目标附件 DB 行 0 + 磁盘文件不存在、对照附件行 1 + 文件存在、审批行 0、商机 is_deleted=1；全量 clean verify 内亦真跑，见 4.1）
- [x] 3.2 图单测：级联图全活（dropped 集为空）+ 既有 ForeignKeyDeleteUtilBatchTest 全绿
      补勾依据（2026-10-19 定向单测合计 Tests run: 11 / F0 / E0 / S0（ForeignKeyDeleteUtilBatchTest 5 + SalesOpportunityServiceTest 6），dropped 集与空登记表相等断言通过）

## 4. 基线与物料
- [x] 4.1 定向测试全绿 + 全量 mvn -B -ntp clean verify（Docker 在线）
      补勾依据（2026-10-19 `docker info` ServerVersion 29.6.2；`mvn -B -ntp clean verify` MVN_EXIT=0 / BUILD SUCCESS（15:38 min）：surefire 报告 283 / Tests run 1779 / F0 / E0 / S0，failsafe 报告 32 / Tests run 119 / F0 / E0 / S7；日志 work/evidence/clean-verify.log）
- [x] 4.2 树净单次 check-test-baseline.sh --update + 校验模式 EXIT=0 + 基线笔
      补勾依据（2026-10-19 `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update` UPDATE_EXIT=0，写入 source-revision=56ca735：surefire 283/1779/0、failsafe 32/119/7；复跑校验模式 CHECK_EXIT=0；基线笔 5ec0793；日志 work/evidence/baseline-update.log、baseline-check.log）
- [x] 4.3 merge-gate.sh 10/10（typed-router 噪声 checkout 还原）
      补勾依据（2026-10-19 `D:\git\Git\bin\bash.exe scripts/merge-gate.sh` GATE_EXIT=0，10 个 PASS：[unit]/[checkstyle]/[spotbugs]/[spotless]/[pmd]/[baseline]/[frontend-unit]/[hook]/[bijection]/[pmd-baseline]；typed-router.d.ts 噪声已 `git checkout --` 还原，frontend/ 零 diff；日志 work/evidence/merge-gate.log）
- [x] 4.4 handoff + tasks 勾选 + proposal/tasks 随 docs 笔提交；严格停步未 merge 未 push
      补勾依据（2026-10-19 handoff 落 work/handoff-fix-approval-attachment-cascade-dead-edge.md，tasks 全勾；**随分支提交的是 proposal.md 与 tasks.md**（openspec/changes/fix-approval-attachment-cascade-dead-edge/ 下，见 branch docs 笔），**任务卡不入库**——work/task-card-fix-approval-attachment-cascade-dead-edge.md 因 work/ 根被 .gitignore 覆盖且未 `git add -f`，全程保持未跟踪；分支 feature/fix-approval-attachment-cascade-dead-edge 保留，未 merge、未 push）
