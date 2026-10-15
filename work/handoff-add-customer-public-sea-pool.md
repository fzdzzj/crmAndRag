# handoff-add-customer-public-sea-pool — 客户公海池与全生命周期流转机制

> 分支 `feature/add-customer-public-sea-pool` 自权威树 `master@0b4bc62` 检出，完工停步在册（4 笔提交悬于 master，未 push / 未合入）。
> **严禁**直接 push / 在 master 提交；合入需 owner 显式授权执行 `git merge --no-ff` 后保留分支。

## 一、交付内容（严格对齐 tasks.md 6 任务组）

### 组 1 · 数据库迁移与模型（Flyway V41）
- `src/main/resources/db/migration/V41__customer_public_sea_pool.sql`：`customer_company` 扩
  `pool_status`(TINYINT NOT NULL DEFAULT 0) / `pool_release_time`(DATETIME) / `pool_release_reason`(VARCHAR 255) /
  `last_activity_time`(DATETIME) + `KEY idx_customer_company_pool (pool_status, is_deleted)`；新建 `customer_pool_log`
  表（idx_pool_log_company/operator/action 三索引）。**未动任何 V1~V40 已合入脚本**。
- `CustomerCompanyEntity` +4 字段与 V41 列严格对齐；新增 `CustomerPoolLogEntity` + `CustomerPoolLogMapper`。
- `FlywayMigrationIT.EXPECTED_VERSIONS` 登记 `"41"`，新增 Order(13) `customerPublicSeaPoolSchemaApplied` 真库断言。

### 组 2 · 公海池业务服务与配额状态机
- `CustomerPoolService` / `CustomerPoolServiceImpl`：`queryPool`（`CustomerCompanyMapper.selectPoolPage` 专有
  `pool_status=1 AND is_deleted=0`，方法名/签名不落 `QueryWrapperAspect` 私海 DataScope 切面）；`claim`（私海 50 配额满额抛
  `CUSTOMER_POOL_QUOTA_EXCEEDED(20003)`）；`release`（负责人或主管可退）；`assign`（主管分派 + 目标配额校验）；`queryLogs`（倒序全生命周期）。
- 流转后经 `CustomerSemanticHookSupport.runAfterCommitAsync` 事务后异步同步向量（Qdrant 权限锚与所有者一致）。

### 组 3 · 自动回收与活动联动
- `CustomerPoolRecycleScheduler`（`@Scheduled(cron="0 0 2 * * ?")` + 手动触发 `recycle/trigger`）：扫描有跟进超 30 天 / 无跟进新建超 15 天的私海客户批量回公海并插 `AUTO_RECYCLE` 日志（operator=0 系统）。
- `BusinessActivityServiceImpl.create` 新增活动后事务提交异步刷新关联 `customer_company.last_activity_time`（经 `selectContactIdsByActivity` → `selectCompanyIdsByContactIds` → `updateLastActivityTime`）。

### 组 4 · 控制器、权限与契约
- `CustomerPoolController` 6 端点：`GET /company/pool`(116)、`POST /company/pool/claim/{id}`(113)、
  `POST /company/pool/release/{id}`(113)、`POST /company/pool/assign`(113)、`GET /company/pool/logs/{companyId}`(117)、
  `POST /company/pool/recycle/trigger`(113)。**零新增权限常量**（严复用 113/116/117，`PermissionOperates.java` 未动）。
- `PermissionCoverageScanner.CONTROLLER_REGISTRY` 已登记 `CustomerPoolController`（否则门禁红）。
- `frontend/openapi.yaml` 由 `check-openapi-consistency.sh --update` 同步（6 个 `/company/pool*` 端点），`pnpm gen:api` 再生 SDK。

### 组 5 · 前端公海池界面
- `frontend/src/pages/(dashboard)/client/pool.page.vue` 公海池视图（公司名/行业/级别/进入时间/进入原因），`client.page.vue` 增「公海池」Tab，`typed-router.d.ts` 增 `clientPool` 路由。
- 操作：业务员「认领」（二次确认 + 配额提示）、主管「分派」（`getUserOptions` 人员 Select + 原因 Modal）；`list.page.vue` 非删除操作列增「退回公海」（原因弹窗，`useReleasePoolCustomer`）。
- 数据层 `frontend/src/hooks/useCustomerPool.ts`（query/claim/release/assign/recycle）。

### 组 6 · 测试、回归基线与门禁
- `CustomerPoolServiceTest` 9 用例 + `CustomerPoolRecycleSchedulerTest` 3 用例（全绿）。
- `SchemaDriftAuditor.ENTITY_REGISTRY` 登记 `customer_pool_log`（否则 SchemaDriftAuditorTest 红）。
- 前端错误码契约：`ErrorCode` 新增 20003 同步 `error-code-map.ts`（ApiErrorCode + errorCodeMap）与 `error-code-map.test.ts` 的 `BACKEND_ERROR_CODE_NAMES=98`。

## 二、测试与门禁证据（实测）

- **surefire**：`mvn -B -ntp verify` → **1427 run / 0 fail / 0 error**（基线 1415 → +12，即 9+3 新单测）。
- **failsafe**：**117 run / 0 fail / 7 skip**（本地 Docker 实测，Flyway V1~V41 全绿，含新增 Order 13 V41 断言）。
- **四静态**：checkstyle 0 / spotbugs 0 / spotless ✓ / pmd check 0 违规（PMD 修复：`OnlyOneReturn`×3、`UnusedFormalParameter`、`AvoidCatchingGenericException` 加 @SuppressWarnings）。
- **前端三检**：`pnpm lint:check` ✓ / `pnpm type-check:check` ✓ / `pnpm test`（vitest）**179 pass**（17 文件）。
- **回归基线**：树净 `check-test-baseline.sh --update` 写入 `scripts/test-baseline.txt`（surefire 219 报告 / 1427；failsafe 31 / 117 / skip 7；source-revision=a4f98f7）。
- **OpenAPI**：`check-openapi-consistency.sh --update` 契约一致（git-bash 实跑）。

## 三、红线遵守

- V1~V40 历史脚本零改动，库结构唯一真相源为 V41；`PermissionOperates.java` 零改动（严复用 113/116/117）；公海流转事务后异步同步向量；分支在册未 push / 未 merge。

## 四、待决策 / 注意事项

1. **合入授权**：分支合入 master 前需 owner 显式授权执行 `git merge --no-ff`；未经授权严禁 push。
2. **WSL bash 陷阱**：本机 `bash` 走 WSL（挂载 ext4.vhdx 失败 `ERROR_FILE_NOT_FOUND`）；须用 Git Bash `D:\git\Git\bin\bash.exe` 跑 bash 脚本（openapi / baseline / merge-gate 均实测可跑）。
3. **回归基线号段**：surefire 1415→1427、failsafe 116→117（新增 12 单测 + 1 V41 断言方法）；source-revision=a4f98f7 净树记录。
4. **`typed-router.d.ts`**：本卡新增 `pool.page.vue` 使路由生成文件合法增 `clientPool` 条目（与前卡"他人改动勿碰"不冲突——本条目为本次新页面的原子生成物，需随卡提交否则 type-check 红）。
5. **error-code-map**：新增 20003 需两侧同步（后端 `ErrorCode` + 前端 `ApiErrorCode`/`errorCodeMap` + `BACKEND_ERROR_CODE_NAMES=98`），`ErrorCodeMapParityTest` 强制。
6. **`is_pool_status` 语义**：存量客户均私海（pool_status 默认 0），公海列表依赖手动退回/认领/自动回收/LOD 流转，无历史数据回填需求。
7. 交接快照：`work/handoff-add-customer-public-sea-pool.md`（本文件在权威树生成并入 git 跟踪，与既有 handoff-*.md 同级）。