# 交接快照 · 合同履约到期预警与逾期应收账款待办联动 (add-contract-warning-and-overdue-reminder)

- **分支机构**：`feature/add-contract-warning-and-overdue-reminder`（单亲线性，自 master 检出）
- **基线 commit**：master `c4fb18b`
- **复核档位**：B 档（零 DB 迁移 / 零权限语义新增 / 零外呼调用 / 零冻结契约触碰）
- **执行状态**：代码 + 单测 + openapi 契约 + 前端 UI 全部完成，`mvn test` 与 `merge-gate.sh` 全绿，**未 merge、未 push**（停步等 Owner）

## 1. 交付物清单

### 后端新增
| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/slz/crm/pojo/vo/ContractExpiringVO.java` | 到期/临期合同 VO（含未结敞口/剩余天数） |
| `src/main/java/com/slz/crm/pojo/vo/ContractOverduePaymentVO.java` | 逾期应收账款 VO |
| `src/main/java/com/slz/crm/server/constant/ContractWarningConstant.java` | 预警窗口/任务类型/优先级/系统指派常量 |
| `src/main/java/com/slz/crm/server/service/ContractWarningService.java` | 到期/逾期分页查询 + 扫描派发接口 |
| `src/main/java/com/slz/crm/server/service/impl/ContractWarningServiceImpl.java` | 实现（分页扫描 + 幂等防重） |
| `src/main/java/com/slz/crm/server/schedule/ContractWarningScheduler.java` | `@Scheduled(0 0 3 * * ?)` 定时调度 |
| `src/main/java/com/slz/crm/server/controller/ContractWarningController.java` | `/contract/warning/*` 三端点 |
| `src/test/java/com/slz/crm/server/service/impl/ContractWarningServiceTest.java` | 服务单测（5 例） |
| `src/test/java/com/slz/crm/server/service/impl/ContractWarningSchedulerTest.java` | 调度单测（2 例） |

### 后端修改
| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/slz/crm/server/mapper/ContractMapper.java` | 新增 `selectExpiringContracts` / `selectOverduePaymentContracts` |
| `src/main/resources/mapper/ContractMapper.xml` | 新增两段关联统计 SQL |
| `src/test/java/com/slz/crm/integration/permission/PermissionCoverageScanner.java` | `CONTROLLER_REGISTRY` 登记新 controller |

### 前端
| 路径 | 说明 |
| --- | --- |
| `frontend/openapi.yaml` | 契约同步（新增 3 端点） |
| `frontend/src/hooks/useContract.ts` | 新增 3 个 warning hooks |
| `frontend/src/pages/(dashboard)/sale/contract.page.vue` | 预警筛选 Segmented + 一键触发按钮 |
| `frontend/src/api/axios/**`（gitignored，重建产物） | `pnpm gen:api` 重新生成 SDK |

### 契约/台账
| 路径 | 说明 |
| --- | --- |
| `scripts/test-baseline.txt` | 基线更新 1427→1434 |
| `openspec/changes/add-contract-warning-and-overdue-reminder/tasks.md` | 全部勾选 + 6 空格留痕依据 |
| `work/handoff-add-contract-warning-and-overdue-reminder.md` | 本快照 |

## 2. 硬性红线核验

- **零 DB 迁移**：未新增任何 `src/main/resources/db/migration/*.sql`；完全复用 `contract`/`payment_record`/`contact_task` 既有表。
- **零权限常量新增**：`PermissionOperates.java` 零改动；端点全部复用 `SALES_VIEW_CONTRACT(215)`（查询）与 `SALES_UPDATE_CONTRACT(214)`（写）。任务卡提到的“147/148”在源码中实为 215/214 的旧称谓，按实际常量名挂载。
- **零外呼**：纯内部统计 + 待办落库，无外部模型调用。
- **零冻结契约**：`contracts-frozen.md` 未触碰。
- **单亲线性**：分支为 master `c4fb18b` 单亲检出，无 merge 历史，未执行 `git merge` / `git push`。

## 3. 幂等防重实现说明

`createTaskIfAbsent` 在生成前经 `hasInProgressTask` 判定：以 `companyId + assigneeId(owner) + taskType` 为键，在 `contact_task` 中检索 `status IN (0,1)`（未开始/进行中）的任务，命中即跳过。与 proposal 3.2 的 contact_task 查询口径一致；因 contact_task 无 contract_id 列（零迁移约束），以公司+负责人+类型收敛去重，杜绝每日定时重复轰炸同一负责人。

## 4. 测试与门禁实测

| 门禁 | 实测结果 |
| --- | --- |
| `mvn -B -ntp test` | `Tests run: 1434, Failures: 0, Errors: 0, Skipped: 0`（1427→1434，+7） |
| `bash scripts/check-test-baseline.sh --update` | 写入 `surefire.tests=1434` |
| `bash scripts/merge-gate.sh` | `merge-gate 通过：所有已执行的子门禁绿` |
| — [unit] | PASS（1434 例） |
| — [spotbugs] | PASS |
| — [pmd] | PASS（violations=0） |
| — [it] | 默认跳过 |
| — [baseline] | PASS（surefire 1434 / failsafe 117） |
| — [frontend-unit] | PASS（179 例） |
| — [hook] | PASS |
| — [bijection] | PASS（12=12） |
| — [pmd-baseline] | PASS（实测 0==登记 0==pom 0） |
| 前端 `pnpm type-check:check` / `lint:check` | PASS |

## 5. 待 Owner 决策 / 遗留说明

1. **待 merge 授权**：验证完成后按纪律由 Owner 授权 `git merge --no-ff` 合入 master，**本子 agent 未执行 merge/push**。
2. **SDK 为 gitignored 产物**：`frontend/src/api/axios/` 不入库，已在本地由 `pnpm gen:api` 重新生成（含三端点）；CI/其他机器需在安装后重跑一次。
3. **幂等去重粒度**：受零迁移约束按“公司+负责人+预警类型”收敛；若未来 contact_task 增加 contract_id 列可将去重粒度细化到单合同。
4. `frontend/typed-router.d.ts` 在其他 agent 改动范围内，本次已通过 `git checkout` 还原，未纳入本分支改动。