# 任务卡 · 合同履约到期预警与逾期应收账款待办联动

- **变更标识**：`add-contract-warning-and-overdue-reminder`
- **复核档位**：**B 档**（常规代码+测试，零 DB 迁移、零权限语义新增、零真跑、零冻结契约触碰）
- **权威工作树**：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出：`feature/add-contract-warning-and-overdue-reminder`）
- **参考规范**：`openspec/changes/add-contract-warning-and-overdue-reminder/proposal.md` 与 `tasks.md`

## 1. 核心目标

1. 扩展 `ContractMapper`，实现到期合同（`contract_status=1` 且 30 天内到期或已超期）与逾期未结回款合同（`remainingAmount > 0` 且生效超 30 天或临近到期）统计；
2. 编写 `ContractWarningService` / `ContractWarningServiceImpl`，实现合同到期预警与欠款催款待办生成逻辑，为合同负责人（`ownerId`）自动生成 `ContactTask` 记录；
3. 严格实现幂等防重：同一合同且相同预警类型的进行中任务（未完成）存在时自动跳过，杜绝重复创建；
4. 编写 `ContractWarningScheduler` 定时调度（每天凌晨 3 点自动扫描）；
5. 暴露 `/contract/warning/expiring`、`/contract/warning/overdue-payment` 与 `/contract/warning/trigger` 端点，复用既有 147/148 权限常量；
6. 扩展前端 `contract.page.vue` 预警筛选视图与一键触发待办生成按钮；同步 `openapi.yaml`；
7. 补充单测（`ContractWarningServiceTest`、`ContractWarningSchedulerTest`），回归基线（1427 只增不减），`merge-gate.sh` 8 项全绿。

## 2. 纪律与红线

1. **零 DB 迁移**：完全复用既有表结构，禁止新增 `src/main/resources/db/migration/` 脚本；
2. **零权限语义新增**：`PermissionOperates.java` 零改动，端点全部复用既有 147/148 常量；
3. **零外呼**：禁止发起任何外部真实模型调用；
4. **线性提交与留痕**：分支线性单亲提交，`tasks.md` 每项勾选下方严格保留 6 空格缩进留痕依据行；
5. **完工停步**：完成全量自测与门禁后产出 `work/handoff-add-contract-warning-and-overdue-reminder.md` 即停步汇报，严禁执行 `merge` 或 `push`！
