# 任务清单 · 客户流失预警与自动化再激活任务流 (add-customer-churn-warning-and-reactivation)

<!-- id: add-customer-churn-warning-and-reactivation -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 服务层：规则判定与幂等建待办

- [x] 1.1 新建 `CustomerChurnWarningService`（含实现）：扫描私海客户，确定性判定近 N 天（`@Value` 默认 60）无互动（无已完成跟进任务 / 无商业活动 / 无合同回款进展三源皆空）；为负责人幂等创建高优先级 `ContactTask`（`hasInProgressTask` 严格防重，参照 `ContractWarningService` 先例），描述含诊断 → Insight→Action 转化链引导
- [x] 1.2 新建 `ChurnWarningScheduler`：每日凌晨 4 点错峰调度（合同预警 3 点），调度注册与执行留日志

## 任务组 2 · 端点与契约

- [x] 2.1 `CustomerChurnWarningController`（或挂既有客户 controller 族）：`POST /customer/churn/warning/scan` + `GET /customer/churn/warning/list`，复用既有权限常量（117/116 族，与公海池同锚）；`PermissionCoverageScanner.CONTROLLER_REGISTRY` 同步登记；openapi `--update` + `pnpm gen:api` 同步 SDK

## 任务组 3 · 前端

- [x] 3.1 客户列表页流失风险筛选 + 一键触发扫描（§109 contract.page.vue 预警先例风格）；按 `frontend/AGENTS.md` L13 追加 Playwright 检查点至 `frontend/docs/test-checkpoints.md`

## 任务组 4 · 测试与门禁

- [x] 4.1 `CustomerChurnWarningServiceTest`：三源互动判定边界（各源有/无、N 天临界）、幂等防重（已有 in-progress 任务不重复建）、待办字段（高优先/负责人/描述含引导）断言；基线 1457 只增后 `check-test-baseline.sh --update` 写真实值
- [x] 4.2 `mvn -B -ntp test` 全绿；`bash scripts/merge-gate.sh` 8/8（Docker 在线加跑 `--with-verify` 9/9）；前端三检；typed-router.d.ts 零改动；产出 `work/handoff-add-customer-churn-warning-and-reactivation.md`（真实披露 HEAD/基线/树净/未 merge 未 push）

## 实测依据（命令 + 结果）

      bash -lc "mvn -B -ntp test -Dtest='CustomerChurnWarningServiceTest,ChurnWarningSchedulerTest' -DfailIfNoTests=false"
      结果：Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 —— BUILD SUCCESS（13 服务 + 2 调度）

      bash -lc "bash scripts/check-openapi-consistency.sh --update"
      结果：契约一致（frontend/openapi.yaml 与后端导出逐字相同）；新增 61 行（2 端点）

      bash -lc "cd frontend && pnpm gen:api"
      结果：@hey-api/openapi-ts v0.97.1 Done!（4 files）；sdk.gen.ts 生成 postCustomerChurnWarningScan / getCustomerChurnWarningList

      bash -lc "cd frontend && pnpm lint:check"
      结果：退出码 0（无输出）

      bash -lc "cd frontend && pnpm type-check:check"
      结果：退出码 0（无 error TS）

      bash -lc "cd frontend && pnpm test"
      结果：Test Files 20 passed (20) / Tests 188 passed (188)

      bash -lc "git status --porcelain frontend/typed-router.d.ts"
      结果：空（vitest 改写行尾后已 git checkout -- 还原，分支零 diff）

      bash -lc "mvn -B -ntp clean verify"
      结果：见 handoff §4（surefire 1472 / failsafe 118 实测口径）

      bash -lc "bash scripts/check-test-baseline.sh --update"
      结果：写入 surefire.tests=1472 / failsafe.tests=118（真实运行值，未手改）

      bash -lc "bash scripts/merge-gate.sh --with-verify"
      结果：merge-gate 通过（9/9，含 [it]），见 handoff §4
