# 任务清单 · 销售线索管理与一键转化商机漏斗 (add-lead-management-and-conversion-funnel)

<!-- id: add-lead-management-and-conversion-funnel -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 数据库迁移与数据实体层 (Flyway V42)

- [x] 1.1 编写 Flyway 脚本 `src/main/resources/db/migration/V42__sales_lead.sql`，创建 `sales_lead` 销售线索表及索引
      - 实测：`src/main/resources/db/migration/V42__sales_lead.sql` 已创建，含 `sales_lead` 表及 lead_status / converted_at 三组合索引；Flyway V42 在 Testcontainers MySQL 8.0.36 真库跑通。
- [x] 1.2 创建 `SalesLeadEntity` 实体类，映射 `sales_lead` 表
      - 实测：`src/main/java/com/slz/crm/pojo/entity/SalesLeadEntity.java` 已创建，字段与 V42 DDL 一致，SchemaDriftAuditor 登记无 NEW 漂移。
- [x] 1.3 创建 `SalesLeadMapper` 接口与对应的 Mapper XML（若需自定义查询）
      - 实测：`src/main/java/com/slz/crm/server/mapper/SalesLeadMapper.java`（BaseMapper 继承）已创建，无需自定义 XML。
- [x] 1.4 在 `FlywayMigrationIT.EXPECTED_VERSIONS` 登记 `"42"` 版本，断言 V42 结构加载
      - 实测：`FlywayMigrationIT.EXPECTED_VERSIONS` 已登记 `"42"`，`mvn -B -ntp verify` 下 FlywayMigrationIT 14 例全绿（Docker MySQL 实测）。

## 任务组 2 · 核心线索服务与一键转化业务逻辑

- [x] 2.1 编写 DTO/VO 数据对象（`SalesLeadDTO`, `SalesLeadQueryDTO`, `LeadConvertDTO`, `SalesLeadVO`, `LeadConvertVO`）
      - 实测：`SalesLeadDTO` / `SalesLeadQueryDTO` / `LeadConvertDTO` / `SalesLeadVO` / `LeadConvertVO` 五份 pojo 均创建，字段与其使用方一致。
- [x] 2.2 编写 `SalesLeadService` 与 `SalesLeadServiceImpl`，实现基础录入、修改、状态流转与逻辑删除
      - 实测：`SalesLeadServiceImpl` 实现 create/update/status 流转/delete（逻辑删除）全链路，`SalesLeadServiceTest` 覆盖录入、状态校验、转化场景断言。
- [x] 2.3 在 `SalesLeadServiceImpl` 实现一键转化逻辑 `convert`，单事务内完成客户创建、联系人创建与可选商机创建，并回填线索转化状态，事务后异步联动向量同步
      - 实测：`convert` 单 `@Transactional` 依次创建客户/联系人/可选商机并回填 converted_* 字段，提交后经 `runAfterCommitAsync` 异步派发 embedding 向量同步，异常 WARN 不阻断。

## 任务组 3 · API 端点设计、权限与契约同步

- [x] 3.1 编写 `SalesLeadController`（路径 `/sales/lead`），挂载录入、详情、分页查询、更新、删除与 `/convert` 端点，复用既有 111/113/114/116/117 权限常量
      - 实测：`SalesLeadController` 已创建 `/sales/lead` 6 端点（POST / GET / GET page / PUT / DELETE / POST convert），`PermissionOperates.java` 零改动，全部复用既有 CUSTOMER_* 常量。
- [x] 3.2 在 `PermissionCoverageScanner.CONTROLLER_REGISTRY` 注册 `SalesLeadController`，门禁确保零裸奔端点
      - 实测：`PermissionCoverageScanner.CONTROLLER_REGISTRY` 已登记 SalesLeadController，权限覆盖门禁无裸奔端点告警。
- [x] 3.3 执行 `check-openapi-consistency.sh --update` 同步 `frontend/openapi.yaml` 契约并重新生成 API SDK
      - 实测：`bash scripts/check-openapi-consistency.sh --update` 契约一致（6 端点同步）；`frontend/src/api/axios/**`（gitignored）由 `pnpm gen:api` 重新生成。

## 任务组 4 · 前端线索管理视图与转化交互

- [x] 4.1 在 `frontend/src/pages/(dashboard)/sale/` 新建 `lead.page.vue`，实现线索分页列表、状态徽标与多条件筛选
      - 实测：`frontend/src/pages/(dashboard)/sale/lead.page.vue` 已创建，实现分页列表、状态徽标与多条件筛选；前端 `pnpm lint:check` / `type-check:check` / `pnpm test` 全 PASS。
- [x] 4.2 实现线索「录入」弹窗与「一键转化为客户商机」对话框（支持输入商机名称与预估金额）
      - 实测：`lead.page.vue` 内已实现线索录入弹窗与一键转化对话框（商机名称 + 预估金额输入），调用 `/sales/lead` 与 `/sales/lead/convert` 端点。
- [x] 4.3 补充前端数据请求 hook `useSalesLead.ts`，配置页面路由与菜单
      - 实测：`frontend/src/hooks/useSalesLead.ts` 已创建（列表/录入/更新/删除/一键转化），页面路由与菜单已配置。

## 任务组 5 · 测试闭环、回归基线与门禁验证

- [x] 5.1 编写 `SalesLeadServiceTest` 单元测试，全面覆盖线索录入、状态校验、一键转客户联系人与一键转商机全场景断言
      - 实测：`SalesLeadServiceTest` 已创建并覆盖录入、状态校验、一键转客户/联系人/商机断言；surefire 全量 `Tests run: 1450, Failures: 0, Errors: 0, Skipped: 0`。
- [x] 5.2 运行 `mvn -B -ntp verify`（含 Docker MySQL `FlywayMigrationIT`），使用 `--update` 更新 `scripts/test-baseline.txt`
      - 实测：`mvn -B -ntp verify` `BUILD SUCCESS`（含 FlywayMigrationIT 14 例全绿 + pmd violations=0 + spotbugs）；`bash scripts/check-test-baseline.sh --update` 写入 `surefire.tests=1450`。
- [x] 5.3 运行 `bash scripts/merge-gate.sh` 确保 8 项门禁全绿，产出交接快照 `work/handoff-add-lead-management-and-conversion-funnel.md`
      - 实测：`bash scripts/merge-gate.sh` 8 项门禁全绿；交接快照 `work/handoff-add-lead-management-and-conversion-funnel.md` 已真实披露 HEAD `ce6ddd8`、基线 1450 与 typed-router.d.ts 还原。
