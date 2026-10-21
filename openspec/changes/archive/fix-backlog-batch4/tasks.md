# 任务清单 · fix-backlog-batch4

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. ⑬ sales_stage_approval 域附件清理补齐
- [x] 1.1 `ChildHostAttachmentCleaner` 公司/联系人两入口补审批域清理：`selectIds(salesStageApprovalMapper, "opportunity_id", opportunities)` → `removeByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)`；商机入口不加（先例已覆盖）；javadoc「不做什么」段与依赖注入同步更新
      补勾依据（2026-10-21 `git diff -- ChildHostAttachmentCleaner.java` 复核）：公司/联系人两入口各加 `removeStageApprovalAttachments(opportunities)`，新私有方法复用既有 `selectIds`（空集零 SQL）+ `removeByAndIds(..., ModelName.APPROVAL_ATTACHMENT)`；商机入口未接线；构造器 +1 参数（`SalesStageApprovalMapper`），三处调用方均为 `@Autowired` 字段、零改动；类注释新增「sales_stage_approval 域附件的归属」段并改写「不做什么」。
- [x] 1.2 IT 扩展：`CustomerCompanyPhysicalDeleteAttachmentCascadeIT` 加公司/联系人两入口的 `model_name=approval_attachment` 附件清零断言 + 跨模型同 and_id 对照存活；单元测试同步（Cleaner 三入口既有用例补审批域 verify）
      补勾依据（2026-10-21 `mvn -B -ntp failsafe:integration-test failsafe:verify -Dit.test=CustomerCompanyPhysicalDeleteAttachmentCascadeIT` → Tests run: 2, Failures: 0, Errors: 0；`mvn -B -ntp test -Dtest=ChildHostAttachmentCleanerTest` → Tests run: 5, Failures: 0）：IT 新增种子（联系人/商机/审批行）+ 审批附件与同 and_id（model_name=business_activity）对照附件，公司入口与联系人入口各一组清零/存活断言；Cleaner 单元测试为**新建** `src/test/java/com/slz/crm/unit/service/ChildHostAttachmentCleanerTest.java`（仓库内并无既有的公司/联系人入口单测用例，见 handoff D1）。

## 2. ⑭ 错误码总数断言生成化
- [x] 2.1 `error-code-map.test.ts` 删 `BACKEND_ERROR_CODE_NAMES = 100` 硬编码，改 Node fs 读 `ErrorCode.java` + `PlatformErrorCode.java` 正则计数（路径 `import.meta.url` 相对推导，本地 Windows 与 CI Linux 双环境可用；口径：两空格缩进 + 常量名 + `(` + 数字）；`error-code-map.ts` 生产镜像零 diff
      补勾依据（2026-10-21 `git status --porcelain`）：仅 `frontend/src/constants/error-code-map.test.ts` 一处改动，`error-code-map.ts` 零 diff；计数实现 `ENUM_CONSTANT = /^ {2}([A-Z][A-Z0-9_]*)\(\s*(\d+)/gm`（`\s*` 容忍 `BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS` 的换行形态，否则少数 1 条），路径 `resolve(dirname(fileURLToPath(import.meta.url)), '../../..')` 回仓根。
- [x] 2.2 验证：`pnpm vitest run src/constants/error-code-map.test.ts` 全绿 + 生成计数与 ParityTest 口径一致性论证入 handoff（当前应得 100）
      补勾依据（2026-10-21 `pnpm exec vitest run src/constants/error-code-map.test.ts` → Test Files 1 passed / Tests 70 passed；同日 node 复算两文件 90 + 10 = 100）：生成计数=100，与 `ErrorCodeMapParityTest`（反射 `ErrorCode.values()` + `PlatformErrorCode.values()` 并集）同口径，论证见 handoff §⑭。

## 3. 验证与留证
- [x] 3.1 定向全绿 + 全量 mvn -B -ntp clean verify（Docker 实测）
      补勾依据（2026-10-21 权威树实测，`docker info` → 29.6.2 在线）：定向 `mvn -B -ntp test -Dtest=ChildHostAttachmentCleanerTest` 5/5、`failsafe:integration-test -Dit.test=CustomerCompanyPhysicalDeleteAttachmentCascadeIT` 2/2；全量 `mvn -B -ntp clean verify` BUILD SUCCESS，surefire Tests run: 1894 / 0 / 0 / 0，failsafe Tests run: 124 / 0 / 0 / 7 skipped，checkstyle 0 violations，spotless+pmd 通过。
- [x] 3.2 树净单次 check-test-baseline.sh --update + 校验 EXIT=0 + merge-gate.sh 10/10（含 [frontend-unit]）
      补勾依据（2026-10-21）：`--update` 后复跑 `check-test-baseline.sh` → EXIT=0（surefire 304/1894/0，failsafe 34/124/7，只增不减）；`merge-gate.sh` → EXIT_GATE=0，10/10 PASS = [unit] [checkstyle] [spotbugs] [spotless] [pmd] [baseline] [frontend-unit] [hook] [bijection] [pmd-baseline]，其中 [frontend-unit] 21 files / 197 tests passed。
- [x] 3.3 handoff（⑬ 两入口闭包口径与商机不重复接线论证 / ⑭ 生成化前后对照与双环境路径论证）+ tasks 勾选 + docs 笔；停步未 merge 未 push
      补勾依据（2026-10-21）：handoff 落观察树 `work/handoff-fix-backlog-batch4.md`（含 §2 闭包口径与商机不重复接线论证、§3 生成化前后对照与双环境路径论证、§5 偏差 D1-D7、§6 待裁决 3 项）；本文件勾选已留痕；分支 `feature/fix-backlog-batch4` 三笔提交，**未 merge、未 push、未删分支**。
