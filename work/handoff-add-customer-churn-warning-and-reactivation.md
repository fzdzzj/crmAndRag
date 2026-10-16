# 交接快照 · 客户流失预警与再激活任务流 (add-customer-churn-warning-and-reactivation)

- **分支机构**：`feature/add-customer-churn-warning-and-reactivation`（单亲线性，自 master `0563052` 检出）
- **基线 commit**：master `05630529d1d4618ebb9fdad467c39405c7cceed7`
- **复核档位**：B 档（零 DB 迁移 / 零权限常量 / 零 LLM 外呼 / 零冻结契约触碰）
- **执行状态**：代码 + 单测 + openapi 契约 + 前端 UI 全部完成，`mvn clean verify` 与 `merge-gate.sh --with-verify` 全绿，**未 merge、未 push**（停步等 Owner）

## 1. 交付物清单

### 后端新增
| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/slz/crm/pojo/vo/CustomerChurnWarningVO.java` | 流失风险条目 VO（公司/负责人/最后一笔互动/无互动天数/窗口天数） |
| `src/main/java/com/slz/crm/server/constant/CustomerChurnWarningConstant.java` | 任务类型「流失预警」/优先级 7/系统指派 0/状态口径常量 |
| `src/main/java/com/slz/crm/server/service/CustomerChurnWarningService.java` | 扫描建待办 + 风险清单接口 |
| `src/main/java/com/slz/crm/server/service/impl/CustomerChurnWarningServiceImpl.java` | 实现（三源判定 + `hasInProgressTask` 幂等防重） |
| `src/main/java/com/slz/crm/server/schedule/ChurnWarningScheduler.java` | `@Scheduled(0 0 4 * * ?)` 每日凌晨 4 点（与合同预警 3 点错峰） |
| `src/main/java/com/slz/crm/server/controller/CustomerChurnWarningController.java` | `POST /customer/churn/warning/scan` + `GET /customer/churn/warning/list` |
| `src/test/java/com/slz/crm/server/service/impl/CustomerChurnWarningServiceTest.java` | 服务单测（13 例） |
| `src/test/java/com/slz/crm/server/service/impl/ChurnWarningSchedulerTest.java` | 调度单测（2 例） |

### 后端修改
| 路径 | 说明 |
| --- | --- |
| `src/test/java/com/slz/crm/integration/permission/PermissionCoverageScanner.java` | `CONTROLLER_REGISTRY` 登记 `CustomerChurnWarningController`（30→31 server controller） |
| `scripts/test-baseline.txt` | 基线更新 1457→1472（`check-test-baseline.sh --update` 真实写入） |

### 前端
| 路径 | 说明 |
| --- | --- |
| `frontend/openapi.yaml` | 契约同步（新增 2 端点，+61 行） |
| `frontend/src/hooks/useCustomerChurnWarning.ts` | 风险清单查询 + 一键触发 hook |
| `frontend/src/pages/(dashboard)/client/list.page.vue` | 「流失风险」筛选模式 + 「生成流失预警待办」按钮（§109 contract.page.vue 风格） |
| `frontend/docs/test-checkpoints.md` | 追加 3 条 Playwright 检查点（frontend/AGENTS.md L13 规则） |
| `frontend/src/api/axios/**`（gitignored 重建产物） | `pnpm gen:api` 重新生成 SDK（含两个新端点函数） |

### 契约/台账
| 路径 | 说明 |
| --- | --- |
| `openspec/changes/add-customer-churn-warning-and-reactivation/tasks.md` | 全部勾选 + 6 空格实测依据 |
| `work/handoff-add-customer-churn-warning-and-reactivation.md` | 本快照 |

## 2. 硬性红线核验

- **零 DB 迁移**：未新增任何 `src/main/resources/db/migration/*.sql`；流失风险即时计算不持久化（无新列新表），完全复用 `customer_company`/`contact_task`/`business_activity`/`contract`/`payment_record` 既有表。
- **零权限常量新增**：`PermissionOperates.java` 零改动；端点复用 117（`CUSTOMER_VIEW_COMPANY`，list 查询）与 113（`CUSTOMER_UPDATE_CUSTOMER_COMPANY`，scan 触发写），与公海池 controller 同锚。
- **零 LLM 外呼**：纯规则判定 + 待办落库，无外部模型调用。
- **零冻结契约**：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 未触碰。
- **未改禁改本体**：`CustomerPoolService` 回池逻辑与 `CustomerInsightService` 诊断本体均未触碰；`frontend/typed-router.d.ts` 分支提交零 diff（vitest 行尾噪声已 `git checkout --` 还原）。
- **单亲线性**：分支为 master `0563052` 单亲检出，无 merge 历史，未执行 `git merge` / `git push`。

## 3. 规则判定与幂等防重实现说明

- **私海口径**：`customer_company` 中 `owner_id IS NOT NULL AND is_deleted = 0`，单次扫描上限 500 家（`SCAN_PAGE_SIZE`）。
- **三源信号**（窗口 = 配置 `crm.customer-churn.inactive-days`，默认 60）：
  1. 已完成的跟进任务 `contact_task(status=2)`，完成时间取 `update_time`（缺失回退 `create_time`）；
  2. 商业活动 `business_activity`，互动时间取 `activity_time`；
  3. 合同回款进展 `payment_record`，经 `contract.company_id` 归集到客户，互动时间取 `create_time`。
  三源全部为空（或最新互动早于窗口）即判流失风险；无互动天数以「最后一笔互动 / 客户创建时间」为基线。
- **幂等防重**（同 `ContractWarningServiceImpl#hasInProgressTask`）：以 `companyId + assigneeId(owner) + taskType=流失预警` 为键，`contact_task` 存在 `status IN (0,1)`（未开始/进行中）任务即跳过，杜绝每日调度重复轰炸销售员。
- **待办描述**：硬编码模板，含引导语「回访前先走客户诊断 → 基于诊断建议用 Insight→Action 转化链转待办或转活动」。
- **即时计算不落库**：风险清单（list 端点）与扫描（scan 端点）均为即时计算，判定结果不写任何新表/新列。

## 4. 测试与门禁实测

| 门禁 | 实测结果 |
| --- | --- |
| 新增单测（定向首跑） | `mvn -B -ntp test -Dtest='CustomerChurnWarningServiceTest,ChurnWarningSchedulerTest'` → `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn -B -ntp clean verify` | surefire `Tests run: 1472`（1457→1472，+15）/ failsafe `Tests run: 118, Skipped: 7`，BUILD SUCCESS（含 checkstyle/spotless/spotbugs/pmd） |
| `bash scripts/check-test-baseline.sh --update` | 写入 `surefire.tests=1472` / `failsafe.tests=118`（真实运行值，未手改） |
| `bash scripts/merge-gate.sh --with-verify` | `merge-gate 通过：所有已执行的子门禁绿`（9/9，含 [it]） |
| — [unit] | PASS（1472 例） |
| — [spotbugs] | PASS |
| — [pmd] | PASS（violations=0） |
| — [it] | PASS（failsafe 118，Docker 在线，7 skipped 为 opt-in 真外发 IT） |
| — [baseline] | PASS（surefire 1472 / failsafe 118） |
| — [frontend-unit] | PASS（188 例） |
| — [hook] | PASS |
| — [bijection] / [pmd-baseline] | PASS |
| openapi 契约 | `bash scripts/check-openapi-consistency.sh --update` → 「契约一致」（2 端点 +61 行） |
| 前端三检 | `pnpm lint:check` PASS / `pnpm type-check:check` PASS / `pnpm test` → 20 files / 188 tests PASS |

## 5. 待 Owner 决策 / 遗留说明

1. **待 merge 授权**：验证完成后按纪律由 Owner 授权 `git merge --no-ff` 合入 master，**本子 agent 未执行 merge/push**。
2. **SDK 为 gitignored 产物**：`frontend/src/api/axios/` 不入库，已在本地由 `pnpm gen:api` 重新生成（含 `postCustomerChurnWarningScan` / `getCustomerChurnWarningList`）；CI/其他机器需在安装后重跑一次。
3. **窗口天数可配**：判定窗口默认 60 天，`application.yml`/环境变量 `crm.customer-churn.inactive-days` 可覆盖（前端 Alert 文案仍按 60 天硬编码，若改配置需同步文案）。
4. **扫描上限**：单次扫描最多 500 家私海客户（防一次性拉取过大）；超出部分下个调度周期覆盖，超大租户需评估分批策略。
5. **ownerName 未回填**：风险清单返回 ownerId（负责人 ID），未做 `sys_user.real_name` 联查（保持与 ContactTask 系统派发口径一致，且避免新增联查依赖）；前端「负责人ID」列按数字展示，如需姓名列可作为后续增强。
6. **树净披露**：提交后工作树仅剩 1 个未跟踪文件 `work/task-card-wire-customer-insight-panel.md`——系前序卡（wire-customer-insight-panel）遗留、本卡开工前已存在（见开工时 `git status`），不属于本卡交付，未代为提交；`work/review-*.log` 等原始日志按 `.gitignore` 规则不入库。
