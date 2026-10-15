# 任务清单 · 合同履约到期预警与逾期应收账款待办联动 (add-contract-warning-and-overdue-reminder)

<!-- id: add-contract-warning-and-overdue-reminder -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 业务计算模型与数据持久层

- [x] 1.1 扩展 `ContractMapper`，编写到期合同统计与逾期应收账款关联统计 SQL（包含已回款差额计算）
      - 实测留痕：`ContractMapper.java` 新增 `selectExpiringContracts`/`selectOverduePaymentContracts`（Page 返回）；`ContractMapper.xml` 新增两段 `<select>`，LEFT JOIN `customer_company`/`sales_opportunity`/`sys_user`/`payment_record`（payment_status=0），`COALESCE(SUM(payment_amount),0)` 计已回款、`total_amount-已回款` 计未结敞口、`DATEDIFF(end_date,NOW())` 计剩余天数；`mvn test` 全绿（1434）。
- [x] 1.2 编写 `ContractExpiringVO` 与 `ContractOverduePaymentVO` 数据传输对象，携带合同编号、剩余天数、合同总额、已回款金额及未结敞口
      - 实测留痕：`pojo/vo/ContractExpiringVO.java`、`pojo/vo/ContractOverduePaymentVO.java` 各含 contractNo/remainDays/totalAmount/paidAmount/remainingAmount/ownerId 等字段，映射列与 mapper 别名一致；前端 openapi 契约已生成 `ContractExpiringVo`/`ContractOverduePaymentVo` 类型（`types.gen.ts:1617/1650`）。

## 任务组 2 · 核心预警与待办联动服务

- [x] 2.1 编写 `ContractWarningService` 与 `ContractWarningServiceImpl`，实现到期合同分页查询 `queryExpiringContracts` 与逾期回款查询 `queryOverduePaymentContracts`
      - 实测留痕：`server/service/ContractWarningService.java` 定义三个方法；`ContractWarningServiceImpl` 实现分页透传并兜底默认天数/分页；`ContractWarningServiceTest.queryExpiringContracts_passesDefaultDaysAndPage`/`queryOverduePaymentContracts_passesPage` 通过（5 例全绿）。
- [x] 2.2 实现自动化扫描与待办生成 `scanAndGenerateWarningTasks`，为合同负责人生成 `ContactTask` 记录
      - 实测留痕：`ContractWarningServiceImpl.scanAndGenerateWarningTasks` 分页循环扫描两类命中合同，为 ownerId 创建 `ContactTask`（taskType=合同续签/回款催收，系统指派 assignerId/creatorId=0，优先级 7，未开始状态）；`ContractWarningServiceTest.scan_generatesBothKindsOfTasks` 通过（captor 断言 2 续签+1 催收）。
- [x] 2.3 实现待办任务幂等防重机制，针对进行中的同合同同类型任务自动跳过，杜绝重复创建
      - 实测留痕：`createTaskIfAbsent` 先经 `hasInProgressTask`（LambdaQueryWrapper 按 companyId+assigneeId+taskType 且 status IN (0,1) 判重）命中即跳过；`ContractWarningServiceTest.scan_skipsWhenInProgressTaskExists`（存在进行中任务→created=0、`never insert`）与 `scan_noCandidates_doesNothing` 通过。

## 任务组 3 · 定时调度与管理触发端点

- [x] 3.1 编写 `ContractWarningScheduler` 定时任务，每天凌晨 3 点（`0 0 3 * * ?`）自动扫描并批量派发待办任务
      - 实测留痕：`server/schedule/ContractWarningScheduler.java` 标注 `@Scheduled(cron = "0 0 3 * * ?")`，`scheduledScan` 调 `scanAndGenerateWarningTasks`，另有同源 `triggerScan` 供手动触发；`@EnableScheduling` 已在 `CrmApplication` 开启；`ContractWarningSchedulerTest`（2 例）通过。
- [x] 3.2 编写 `ContractWarningController` 挂载 `/contract/warning/expiring`、`/contract/warning/overdue-payment` 与 `/contract/warning/trigger` 端点，复用既有 215/214 权限常量
      - 实测留痕：`server/controller/ContractWarningController.java` 三个端点分别挂 `@RequirePermission(PermissionOperates.SALES_VIEW_CONTRACT)`（expiring/overdue-payment）与 `SALES_UPDATE_CONTRACT`（trigger）；`PermissionOperates.java` 零改动（215/214 常量未新增）；`PermissionCoverageScannerTest`（8 例）扫描含新 controller 通过。
- [x] 3.3 在 `PermissionCoverageScanner.CONTROLLER_REGISTRY` 注册控制器，确保零裸奔端点
      - 实测留痕：`PermissionCoverageScanner.java` 登记 `ContractWarningController`（字母序插入 ContractOrderItemController 前），注释同步 server/controller 30+platform 2=32；`PermissionCoverageComparatorTest`（7 例）与 `PermissionCoverageScannerTest`（8 例）通过，无 PENDING_DECISION/CRITICAL。

## 任务组 4 · 前端界面集成与契约同步

- [x] 4.1 执行 `check-openapi-consistency.sh --update` 同步 `frontend/openapi.yaml` 契约并重新生成 API SDK
      - 实测留痕：Git Bash 下 `bash scripts/check-openapi-consistency.sh --update` 输出“契约一致”；`frontend/openapi.yaml` 新增 `/contract/warning/{expiring,overdue-payment,trigger}`；`frontend/` 下 `pnpm gen:api` 重生成 SDK，`sdk.gen.ts` 含 `getContractWarningExpiring`/`getContractWarningOverduePayment`/`postContractWarningTrigger`。
- [x] 4.2 在 `frontend/src/pages/(dashboard)/sale/contract.page.vue` 增加预警筛选与一键触发待办生成按钮
      - 实测留痕：`contract.page.vue` 增 `Segmented`（全部合同/临近到期/逾期账款）驱动动态列与数据源，并增加「生成预警待办」按钮（`Permission SALES_UPDATE_CONTRACT`）调 `useTriggerContractWarning`；`useContract.ts` 增 `useQueryContractWarningExpiring`/`useQueryContractWarningOverduePayment`/`useTriggerContractWarning`；`pnpm type-check:check` 通过、`pnpm lint:check` 通过、`pnpm test`（179 例）通过。

## 任务组 5 · 测试闭环、回归基线与门禁验证

- [x] 5.1 编写 `ContractWarningServiceTest` 单元测试，覆盖到期判定、欠款差额计算、幂等防重及待办创建逻辑
      - 实测留痕：`src/test/java/com/slz/crm/server/service/impl/ContractWarningServiceTest.java` 含 5 例（分页透传×2、双类型生成、幂等跳过、无候选零写），`mvn -B -ntp test` 运行该类全绿。
- [x] 5.2 编写 `ContractWarningSchedulerTest` 调度单元测试
      - 实测留痕：`src/test/java/com/slz/crm/server/service/impl/ContractWarningSchedulerTest.java` 含 2 例（委托服务并透传新建数量、无命中透传 0），`mvn -B -ntp test` 通过。
- [x] 5.3 运行 `mvn -B -ntp test` 确保全部单测通过（基线 1427 只增不减），运行 `check-test-baseline.sh --update` 更新基线
      - 实测留痕：`mvn -B -ntp test` 实测 `Tests run: 1434, Failures: 0, Errors: 0, Skipped: 0`（+7=1427→1434）；Git Bash 下 `bash scripts/check-test-baseline.sh --update` 写入 `surefire.tests=1434`（scripts/test-baseline.txt）。
- [x] 5.4 运行 `bash scripts/merge-gate.sh` 确保 8 项门禁全绿，产出交接快照 `work/handoff-add-contract-warning-and-overdue-reminder.md`
      - 实测留痕：Git Bash 下 `bash scripts/merge-gate.sh` 输出 `merge-gate 通过：所有已执行的子门禁绿`；[unit](1434 例)/[spotbugs]/[pmd]/[baseline]/[frontend-unit](179 例)/[hook]/[bijection]/[pmd-baseline] 全 PASS；已产出 `work/handoff-add-contract-warning-and-overdue-reminder.md`。
