# 交接快照 · 销售线索管理与一键转化商机漏斗 (add-lead-management-and-conversion-funnel)

- **分支机构**：`feature/add-lead-management-and-conversion-funnel`（单亲线性，自 master 检出）
- **当前提交哈希**：`d9f0a0f`（分支 HEAD，1 笔整体提交，自 master 检出基点 `ce6ddd8` 单亲线性；增量复验 GO 后经 `merge --no-ff 54277c5` 合入 master）
- **复核档位**：**A 档**（含 Flyway V42 数据库迁移脚本 + Testcontainers Docker 校验）
- **交付形态/合规整改（NO-GO 复核后已闭合）**：① `frontend/typed-router.d.ts` 禁区文件已 `git checkout --` 还原，未纳入改动；② 测试基线已用 Git Bash `check-test-baseline.sh --update` 从真实运行写入并实测 `surefire.tests=1450`；③ `tasks.md` 全部项勾选 `[x]` 并补齐 6 空格实测依据；④ 本快照真实披露提交哈希/基线/还原记录。**执行状态**：代码 + 单测 + 契约 + 前端 UI 全部完成，`mvn verify` + `merge-gate.sh` 全绿，整改后规范提交为分支 commit，**未 merge、未 push**（停步等 Owner）

## 1. 交付物清单

### 后端新增
| 路径 | 说明 |
| --- | --- |
| `src/main/resources/db/migration/V42__sales_lead.sql` | Flyway V42 建 `sales_lead` 表 + 三组合索引 |
| `src/main/java/com/slz/crm/pojo/entity/SalesLeadEntity.java` | 线索实体 |
| `src/main/java/com/slz/crm/server/mapper/SalesLeadMapper.java` | 线索 Mapper（BaseMapper） |
| `src/main/java/com/slz/crm/pojo/dto/SalesLeadDTO.java` | 创建/更新 DTO |
| `src/main/java/com/slz/crm/pojo/dto/SalesLeadQueryDTO.java` | 分页查询条件 DTO |
| `src/main/java/com/slz/crm/pojo/dto/LeadConvertDTO.java` | 一键转化 DTO |
| `src/main/java/com/slz/crm/pojo/vo/SalesLeadVO.java` | 线索 VO |
| `src/main/java/com/slz/crm/pojo/vo/LeadConvertVO.java` | 转化结果 VO |
| `src/main/java/com/slz/crm/server/service/SalesLeadService.java` | 线索服务接口 |
| `src/main/java/com/slz/crm/server/service/impl/SalesLeadServiceImpl.java` | 服务实现（含 convert 单事务 + 异步向量联动） |
| `src/main/java/com/slz/crm/server/service/impl/SalesLeadAssembler.java` | 装配支持类（实体构建/VO 映射，PMD 合规拆分） |
| `src/main/java/com/slz/crm/server/controller/SalesLeadController.java` | `/sales/lead` 6 端点 |
| `src/test/java/com/slz/crm/server/service/impl/SalesLeadServiceTest.java` | 服务单测（16 例） |

### 后端修改
| 路径 | 说明 |
| --- | --- |
| `src/test/java/com/slz/crm/integration/FlywayMigrationIT.java` | `EXPECTED_VERSIONS` 登记 `"42"` + `sales_lead` 抽验 + V42 DDL 断言 |
| `src/test/java/com/slz/crm/integration/permission/PermissionCoverageScanner.java` | `CONTROLLER_REGISTRY` 登记 `SalesLeadController` |
| `src/test/java/com/slz/crm/integration/schema/SchemaDriftAuditor.java` | `ENTITY_REGISTRY` 登记 `sales_lead` 实体 |

### 前端
| 路径 | 说明 |
| --- | --- |
| `frontend/openapi.yaml` | 契约同步（新增 6 端点） |
| `frontend/src/hooks/useSalesLead.ts` | 线索数据层（列表/录入/更新/删除/一键转化） |
| `frontend/src/pages/(dashboard)/sale/lead.page.vue` | 线索列表 + 状态筛选 + 录入/跟进/转化弹窗 |
| `frontend/src/api/axios/**`（gitignored，重建产物） | `pnpm gen:api` 重新生成 SDK |

### 契约/台账
| 路径 | 说明 |
| --- | --- |
| `scripts/test-baseline.txt` | 基线更新 surefire 1434→1450（只增不减） |
| `openspec/changes/add-lead-management-and-conversion-funnel/tasks.md` | 全部勾选 + 6 空格留痕依据 |
| `work/handoff-add-lead-management-and-conversion-funnel.md` | 本快照 |

## 2. 硬性红线核验

- **迁移安全**：仅新增 `V42__sales_lead.sql`，**禁改 V1~V41**；Flyway 在 Testcontainers MySQL 8.0.36 真库跑通全部 28 条迁移至 v42（`FlywayMigrationIT` 14 例全绿）。
- **零权限常量新增**：`PermissionOperates.java` 零改动；6 端点全部复用既有 `CUSTOMER_ADD_COMPANY(111)` / `CUSTOMER_VIEW_COMPANY(117)` / `CUSTOMER_QUERY_COMPANY(116)` / `CUSTOMER_UPDATE_CUSTOMER_COMPANY(113)` / `CUSTOMER_DELETE_CUSTOMER_COMPANY(114)`。
- **零外呼**：未发起任何外部真实模型调用（严格零外呼）。
- **契约**：冻结契约 `contracts-frozen.md` 未触碰。
- **单亲线性 + 树净**：分支单亲自 master `ce6ddd8` 检出，无 merge 历史；完工 `git status` 树净；`frontend/typed-router.d.ts`（他人/生成物）已 `git checkout` 还原未纳入改动。

## 3. 一键转化事务设计说明

`SalesLeadServiceImpl.convert`：单 `@Transactional` 事务内依次创建客户公司（`CustomerCompanyEntity`）→ 首要联系人（`CustomerContactEntity`）→ 可选销售机会（`SalesOpportunityEntity`），随后回填线索状态为已转化（2）并写入 `converted_company_id` / `converted_contact_id` / `converted_opportunity_id` / `converted_time`。事务提交后经 `CustomerSemanticHookSupport` / `SalesOpportunitySemanticHookSupport` 的 `runAfterCommitAsync` 异步派发向量同步（`embeddingTaskExecutor`），异常 WARN 不阻断业务主流程。

## 4. 测试与门禁实测

| 门禁 | 实测结果 |
| --- | --- |
| `mvn -B -ntp test` | `Tests run: 1450, Failures: 0, Errors: 0, Skipped: 0`（1434→1450，+16） |
| `bash scripts/check-openapi-consistency.sh --update` | 契约一致（6 端点同步） |
| `mvn -B -ntp verify`（A 档 Docker MySQL） | `BUILD SUCCESS`（含 `FlywayMigrationIT` 14 例全绿 + `pmd:check` violations=0 + spotbugs） |
| `bash scripts/check-test-baseline.sh --update` | 写入 `surefire.tests=1450`（只增不减） |
| `bash scripts/merge-gate.sh` | 8 项门禁全绿 |
| 前端 `pnpm lint:check` / `type-check:check` | PASS |
| 前端 `pnpm test` | PASS（17 文件 / 179 例） |
| — [unit] | PASS（1450 例） |
| — [spotbugs] | PASS |
| — [pmd] | PASS（violations=0） |
| — [baseline] | PASS |
| — [frontend-unit] | PASS（179 例） |
| — [hook] | PASS |
| — [bijection] | PASS |
| — [pmd-baseline] | PASS（实测 0==登记 0==pom 0） |

## 5. 待 Owner 决策 / 遗留说明

1. **待 merge 授权**：验证完成后按纪律由 Owner 授权 `git merge --no-ff` 合入 master，**本子 agent 未执行 merge/push**。
2. **SDK 为 gitignored 产物**：`frontend/src/api/axios/` 不入库，已在本地由 `pnpm gen:api` 重新生成（含 6 端点）；CI/其他机器需在安装后重跑一次。
3. `frontend/typed-router.d.ts` 为其他 agent/生成物范围，本次经 `git checkout` 还原，未纳入本分支改动。