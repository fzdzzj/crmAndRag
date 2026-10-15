# 任务清单 · 客户公海池与流转机制 (add-customer-public-sea-pool)

<!-- id: add-customer-public-sea-pool -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 数据库迁移与数据模型 (Flyway V41)

- [x] 1.1 编写 Flyway 脚本 `src/main/resources/db/migration/V41__customer_public_sea_pool.sql`，给 `customer_company` 增加 `pool_status`、`pool_release_time`、`pool_release_reason`、`last_activity_time` 字段与组合索引，创建 `customer_pool_log` 流转记录表
    - 新建 `src/main/resources/db/migration/V41__customer_public_sea_pool.sql`：`ALTER TABLE customer_company ADD COLUMN pool_status/pool_release_time/pool_release_reason/last_activity_time` + `KEY idx_customer_company_pool (pool_status, is_deleted)`；`CREATE TABLE customer_pool_log`（含 idx_pool_log_company/operator/action）。命令实跑：`mvn -B -ntp test` 编译通过；`Verify 阶段 FailFast 日志显示 Migrating schema ... to version "41 - customer public sea pool" ... now at version v41`。
- [x] 1.2 扩展 `CustomerCompanyEntity` 字段（`poolStatus`, `poolReleaseTime`, `poolReleaseReason`, `lastActivityTime`），严格对齐 V41 列定义
    - `src/main/java/com/slz/crm/pojo/entity/CustomerCompanyEntity.java` +4 字段，类型与 V41 对齐（`Integer poolStatus` tinyint / `LocalDateTime poolReleaseTime` / `LocalDateTime poolReleaseReason` / `LocalDateTime lastActivityTime`）。
- [x] 1.3 创建 `CustomerPoolLogEntity` 与 `CustomerPoolLogMapper`，映射 `customer_pool_log` 表
    - 新建 `src/main/java/com/slz/crm/pojo/entity/CustomerPoolLogEntity.java` 与 `src/main/java/com/slz/crm/server/mapper/CustomerPoolLogMapper.java`。
- [x] 1.4 在 `FlywayMigrationIT.EXPECTED_VERSIONS` 登记 `"41"` 版本，Docker 真 MySQL 验证迁移跑通
    - `src/test/java/.../FlywayMigrationIT.java` EXP中登记 `"41"`，新增 Order(13) `customerPublicSeaPoolSchemaApplied` 断言。Docker 实测：`mvn -B -ntp verify` failsafe 阶段 `FlywayMigrationIT` 用例绿，DDL 断言通过（详见任务组 6.3 日志）。

## 任务组 2 · 公海池业务服务与配额状态机

- [x] 2.1 编写 `CustomerPoolService` 与 `CustomerPoolServiceImpl`，实现公海客户分页查询 `queryPool`（专有过滤 `pool_status=1 AND is_deleted=0`，不被私海 DataScope 阻隔）
    - 新建 `server/service/CustomerPoolService.java` 与 `server/service/impl/CustomerPoolServiceImpl.java`；`CustomerCompanyMapper.selectPoolPage`（`@Select` 含 pool_status=1 过滤，方法名与签名不落入 `QueryWrapperAspect` 私海 DataScope 切面）。`queryPool` 走该专用查询。
- [x] 2.2 实现业务员自主认领 `claim`，包含私海客户保有量上限配额校验（默认 50 个，满额抛 20003 异常），成功后更新状态与负责人、插入 `CLAIM` 日志、事务后异步同步向量
    - `claim` 先 `assertQuotaNotExceeded`（count pool_status=0 AND owner=当前用户 ≥50 → 抛 `CUSTOMER_POOL_QUOTA_EXCEEDED(20003)`），`updateById` 置私海/owner、插 `CLAIM` 日志、`CustomerSemanticHookSupport.runAfterCommitAsync` 同步向量。单测：`CustomerPoolServiceTest.claim_quotaExceeded_throws` 断言配额码；`claim_happyPath`。
- [x] 2.3 实现主动退回公海 `release`，权限检查（仅负责人或主管可退回），更新客户为公海状态、记录退回原因、插入 `RELEASE` 日志、事务后异步同步向量
    - `release` 判 `isOwner` 或 `isPoolManager`（超管 roleId=1 或部门 leader），`updateById` 置公海/owner=null/填 pool_release_time+reason、插 `RELEASE` 日志、异步同步向量。单测：`release_byOwner`/`release_notOwnerNorManager_rejected`/`release_superAdminByPassesOwner`。
- [x] 2.4 实现主管分派 `assign`，校验目标业务员私海配额，设置新负责人并重置公海状态、插入 `ASSIGN` 日志、事务后异步同步向量
    - `assign` 判 `isPoolManager`，校验目标用户配额，`updateById` 置私海/owner=目标、插 `ASSIGN` 日志、异步同步向量。单测：`assign_notManager_rejected`/`assign_managerHappyPath`。
- [x] 2.5 实现客户流转日志查询 `queryLogs`，返回该客户的全生命周期流转记录
    - `queryLogs` 按 companyId 倒序 `selectList` 并映射 `CustomerPoolLogVO`。单测：`queryLogs_returnsTimeLine`。

## 任务组 3 · 超时自动回收调度与活动联动

- [x] 3.1 编写 `CustomerPoolRecycleScheduler` 定时任务，每天定时扫描私海客户：若最后一次跟进记录超过 30 天（或新建无跟进超 15 天），批量释放回公海并插入 `AUTO_RECYCLE` 日志
    - 新建 `server/schedule/CustomerPoolRecycleScheduler.java`（`@Scheduled(cron="0 0 2 * * ?")`）；`CustomerCompanyMapper.selectRecyclableCustomers` 条件 SQL `(last_activity_time IS NOT NULL AND <NOW()-30d) OR (last_activity_time IS NULL AND create_time < NOW()-15d)`，批量 updateById 置公海 + 插 AUTO_RECYCLE 日志（operator=0 系统）。单测：`CustomerPoolRecycleSchedulerTest` 3 用例。
- [x] 3.2 改造 `BusinessActivityServiceImpl`，新增或批量导入商业活动时，事务后异步更新对应 `customer_company.last_activity_time = now()`，实现跟进活跃度联动刷新
    - `BusinessActivityServiceImpl.create` 末尾追加 `refreshCustomerLastActivityAfterCommit`（事务提交后查活动关联联系人 → 反查客户公司 → `CustomerCompanyMapper.updateLastActivityTime`）；新增 mapper 方法 `selectContactIdsByActivity` / `selectCompanyIdsByContactIds` / `updateLastActivityTime`。

## 任务组 4 · API 端点设计、权限与契约同步

- [x] 4.1 编写 `CustomerPoolController`，挂载 `/company/pool`、`claim/{id}`、`release/{id}`、`assign`、`logs/{companyId}`、`recycle/trigger` 端点，复用 113/116/117 既有权限常量
    - 新建 `server/controller/CustomerPoolController.java` 6 端点，`/company/pool` GET 挂 `CUSTOMER_QUERY_COMPANY(116)`、claim/release/assign/recycle/trigger 挂 `CUSTOMER_UPDATE_CUSTOMER_COMPANY(113)`、logs 挂 `CUSTOMER_VIEW_COMPANY(117)`；零新增权限常量。
- [x] 4.2 在 `PermissionCoverageScanner.CONTROLLER_REGISTRY` 注册 `CustomerPoolController`，门禁确保零裸奔端点
    - `src/test/java/.../permission/PermissionCoverageScanner.java` `buildRegistry()` 增 `CustomerPoolController`；`mvn -B -ntp test` 阶段 `PermissionCoverageScannerTest` 绿。
- [x] 4.3 执行 `check-openapi-consistency.sh --update` 同步 `frontend/openapi.yaml` 契约
    - `bash scripts/check-openapi-consistency.sh --update`（Git Bash 真实跑通，日志见 `work/handoff-add-customer-public-sea-pool.md`）`frontend/openapi.yaml` 增 6 个 `/company/pool*` 端点；`pnpm gen:api` 再生 SDK（`getCompanyPool`/`postCompanyPoolClaimById`/`postCompanyPoolReleaseById`/`postCompanyPoolAssign`/`getCompanyPoolLogsByCompanyId`/`postCompanyPoolRecycleTrigger` + DTO/VO 类型）。

## 任务组 5 · 前端公海池界面集成

- [x] 5.1 在 `frontend/src/pages/(dashboard)/client/` 扩展公海池视图与公海客户列表展示
    - 新建 `frontend/src/pages/(dashboard)/client/pool.page.vue`（公海池 Tab：公司名/行业/级别/进入时间/进入原因），`client.page.vue` 增「公海池」Tab，`typing/router.d.ts` 增 `clientPool` 路由；`useCustomerPool.ts` 数据层（query/claim/release/assign/recycle）。
- [x] 5.2 实现公海客户"认领"按钮与配额提示、"退回公海"填写原因弹窗、主管"分派"人员选择框
    - `pool.page.vue`：认领二次确认（提示成为私海客户）、分派人员 `Select`（`getUserOptions`）+ 原因；`list.page.vue`：非删除操作列增「退回公海」`LogoutOutlined`→ 弹出填原因 `Modal`（`useReleasePoolCustomer`）。

## 任务组 6 · 测试闭环、回归基线与门禁验证

- [x] 6.1 编写 `CustomerPoolServiceTest` 单测（认领、配额上限拦截、退回、分派、流转日志全场景断言）
    - 新建 `src/test/java/.../CustomerPoolServiceTest.java` 9 用例实跑全绿（claim 命中/配额 20003/非公海拒绝/release 负责人/非负责人 12002/超管/release 日志/assign 权限与命中/queryLogs）。
- [x] 6.2 编写 `CustomerPoolRecycleSchedulerTest` 定时回收逻辑单测
    - 新建 `src/test/java/.../CustomerPoolRecycleSchedulerTest.java` 3 用例实跑全绿（候选回收+日志、空候选零写、多候选 count）。
- [x] 6.3 运行 `mvn -B -ntp verify`（含 Docker MySQL `FlywayMigrationIT`），用 `--update` 更新 `scripts/test-baseline.txt`
    - `mvn -B -ntp verify` → surefire `1427 run`/0 fail/0 error、failsafe `117 run`/`7 skip`（Docker 实测，Flyway V1~V41 全通过）；`bash scripts/check-test-baseline.sh --update` 树净状态写入 `scripts/test-baseline.txt`（source-revision=a4f98f7，surefire 219 报告/1427、failsafe 31/117/skip 7，见 `work/handoff-add-customer-public-sea-pool.md`）。
- [x] 6.4 运行四静态检查、前端三检与 `merge-gate.sh` 确保 8/8 全 PASS，留痕输出交接快照
    - 四静态：checkstyle 0 / spotbugs 0 / spotless ✓ / pmd check 0 违规；前端三检：`pnpm lint:check` ✓ + `pnpm type-check:check` ✓ + `pnpm test`（vitest）179 pass；`bash scripts/merge-gate.sh` → unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline 8/8 PASS 退出 0；交接快照 `work/handoff-add-customer-public-sea-pool.md`。