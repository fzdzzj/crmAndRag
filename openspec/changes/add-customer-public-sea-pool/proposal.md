# 变更提案 · 客户公海池与流转机制 (add-customer-public-sea-pool)

- **提案状态**：`proposed`
- **复核档位**：`A 档`（含 Flyway `V41` 数据库迁移脚本、库结构变更，依据 `docs/review-intensity-policy.md` 强制 A 档）
- **影响范围**：`src/main/resources/db/migration/V41__customer_public_sea_pool.sql`、`CustomerCompanyEntity`、`CustomerPoolLogEntity`、`CustomerPoolService` / `ServiceImpl`、`CustomerPoolController`、`CustomerPoolRecycleScheduler`、`BusinessActivityServiceImpl`（跟进联动）、前端 `client/list.page.vue`（公海池视图）、`FlywayMigrationIT`、`PermissionCoverageScanner`
- **权限与契约**：零新权限常量（复用 `CUSTOMER_QUERY_COMPANY=116`、`CUSTOMER_UPDATE_CUSTOMER_COMPANY=113`、`CUSTOMER_VIEW_COMPANY=117`）；冻结契约零触碰

---

## 一、背景与动机

当前 CRM 系统中，客户（`customer_company`）一旦创建，由录入人或负责人（`creator_id` / `owner_id`）永久持有：
1. **僵尸客户沉淀**：当销售人员离职、调岗或长期搁置不跟进时，有价值的潜在企业客户被长期锁死在私海，团队缺乏透明的释放与再分配通道；
2. **缺乏流转闭环**：新员工入职无客户可认领，主管无法统筹分派未分配资产，业务员无法主动退回无效或不匹配客户；
3. **缺乏自动回收**：缺乏按跟进时效（如超过 30 天无拜访记录）自动释放客户回公海的保护机制，导致“占着客户不跟进”。

本卡旨在建立完整的**客户公海池与全生命周期流转机制**，打通“私海跟进 -> 超时自动回收/主动退回 -> 公海池共享 -> 业务员认领/主管分派”的 CRM 核心资产流转闭环。

---

## 二、详细设计与方案

### 1. 数据库迁移与数据模型（Flyway V41）

1. **`customer_company` 字段扩展**：
   - `pool_status`（TINYINT NOT NULL DEFAULT 0）：公海状态，`0-私海客户`，`1-公海池客户`；
   - `pool_release_time`（DATETIME DEFAULT NULL）：进入公海时间（主动退回或系统自动回收的时间）；
   - `pool_release_reason`（VARCHAR(255) DEFAULT NULL）：进入公海的原因（如：“超时未跟进自动回收”、“销售主动放弃”、“离职人员划转”）；
   - `last_activity_time`（DATETIME DEFAULT NULL）：最近一次有效跟进时间（由跟进活动动态联动更新）；
   - 建立组合索引 `idx_customer_company_pool (pool_status, is_deleted)` 加速公海列表与定时扫描。
2. **新增公海流转记录表 `customer_pool_log`**：
   - `id`（BIGINT AUTO_INCREMENT PRIMARY KEY）；
   - `company_id`（BIGINT NOT NULL）：关联客户公司；
   - `operator_id`（BIGINT NOT NULL）：操作人 ID（系统自动回收记录为 0）；
   - `action_type`（VARCHAR(32) NOT NULL）：流转动作（`CLAIM`-认领、`RELEASE`-主动退回、`ASSIGN`-主管分派、`AUTO_RECYCLE`-系统超时自动回收）；
   - `previous_owner_id`（BIGINT DEFAULT NULL）：原负责人；
   - `new_owner_id`（BIGINT DEFAULT NULL）：新负责人；
   - `reason`（VARCHAR(255) DEFAULT NULL）：流转原因或备注说明；
   - `create_time`（DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP）：记录时间。

### 2. 公海池核心业务流转与规则状态机

- **公海列表查询（`GET /company/pool`）**：
  - 查询 `pool_status = 1 AND is_deleted = 0` 的公海客户，支持名称、行业、级别组合筛选；
  - **权限与隔离**：公海客户面向全体持有客户查询权限的销售开放，在公海池专用查询中避开 `QueryWrapperAspect` 的私海 `creator_id` 过滤限制。
- **自主认领（`POST /company/pool/claim/{id}`）**：
  - 销售人员自主从公海认领客户；
  - **防占坑配额检查**：检查当前用户在私海持有的未归档客户数量，若达到上限（默认 50 个，可配置），抛出业务异常 `ErrorCode.OPERATION_NOT_ALLOWED`（“已达到私海客户保有量上限，无法继续认领”）；
  - 更新客户：`pool_status = 0, owner_id = currentUserId, pool_release_reason = null`，记录 `CLAIM` 日志，同步触发向量更新。
- **主动退回（`POST /company/pool/release/{id}`）**：
  - 销售人员主动将客户退回公海并提交原因；
  - 权限校验：只能退回自己负责的客户（或具有超管/部门主管权限）；
  - 更新客户：`pool_status = 1, owner_id = null, pool_release_time = now(), pool_release_reason = reason`，记录 `RELEASE` 日志，同步触发向量更新。
- **主管分派（`POST /company/pool/assign`）**：
  - 主管或管理员将公海客户指定给某个销售人员；
  - 校验目标销售人员的私海配额；
  - 更新客户：`pool_status = 0, owner_id = targetUserId, pool_release_reason = null`，记录 `ASSIGN` 日志，同步触发向量更新。
- **流转日志查询（`GET /company/pool/logs/{companyId}`）**：
  - 查询指定客户的历史流转时间线。

### 3. 超时自动回收定时任务与活动联动

- **活动联动更新**：
  - 在 `BusinessActivityServiceImpl` 保存/导入/批量新增商业活动时，事务后异步更新对应 `customer_company.last_activity_time = now()`；
- **定时自动回收调度（`CustomerPoolRecycleScheduler`）**：
  - 采用 Spring `@Scheduled(cron = "0 0 2 * * ?")`（每天凌晨 2 点执行，且支持手动触发管理端点用于测试与运维）；
  - 扫描规则：`pool_status = 0 AND is_deleted = 0`，且 `(last_activity_time IS NOT NULL AND last_activity_time < DATE_SUB(NOW(), INTERVAL 30 DAY)) OR (last_activity_time IS NULL AND create_time < DATE_SUB(NOW(), INTERVAL 15 DAY))`；
  - 保护规则：若客户名下存在待审批商机或执行中任务，可选择豁免；其余客户自动更新为 `pool_status = 1, owner_id = null, pool_release_reason = '超时未跟进系统自动回收'`，并插入 `AUTO_RECYCLE` 日志。

### 4. API 端点设计与权限同锚

| 端点 | 方法 | 权限常量 | 说明 |
|------|------|----------|------|
| `/company/pool` | GET | `CUSTOMER_QUERY_COMPANY` (116) | 分页查询公海池客户 |
| `/company/pool/claim/{id}` | POST | `CUSTOMER_UPDATE_CUSTOMER_COMPANY` (113) | 业务员自主认领公海客户 |
| `/company/pool/release/{id}` | POST | `CUSTOMER_UPDATE_CUSTOMER_COMPANY` (113) | 业务员主动退回客户至公海 |
| `/company/pool/assign` | POST | `CUSTOMER_UPDATE_CUSTOMER_COMPANY` (113) | 主管分派公海客户给指定人员 |
| `/company/pool/logs/{companyId}` | GET | `CUSTOMER_VIEW_COMPANY` (117) | 查询客户公海流转历史日志 |
| `/company/pool/recycle/trigger` | POST | `CUSTOMER_UPDATE_CUSTOMER_COMPANY` (113) | 手动触发一次超时自动回收扫描 |

- **权限零新增**：全部端点复用 113、116、117 既有常量；
- **门禁守卫**：新 Controller `CustomerPoolController` 注册至 `PermissionCoverageScanner.CONTROLLER_REGISTRY`，严格杜绝端点裸奔。

### 5. 前端公海池界面集成

- 在 `frontend/src/pages/(dashboard)/client/` 增加公海池入口（独立 Tab 或公海切换按钮）；
- 公海列表展示：公司名、行业、等级、进入公海时间、进入公海原因；
- 操作列：
  - 业务员：“认领”按钮（带二次确认与配额提示）；
  - 主管/管理员：“分派”按钮（打开销售人员下拉选择弹窗）；
- 客户详情/列表操作：“退回公海”按钮（弹出填写退回原因对话框）。

---

## 三、不做什么（明确边界）

1. **不动既有客户主键与核心外键关系**：联系人、商机、合同仍通过 `company_id` 关联客户，客户流转至公海或重新认领时不破坏其历史合同与活动数据；
2. **不新增 PermissionOperates 常量**：严格复用 113/116/117，避免 Flyway 补种权限与角色授权断裂；
3. **不触碰已合入的 V1~V40 迁移脚本**：严格使用新增的 `V41__customer_public_sea_pool.sql`，严格维护 Flyway checksum；
4. **不引入分布式调度框架**：单机 Spring `@Scheduled` + 幂等更新 SQL，足够支撑千万级以下 CRM 场景，避免引入外部重型组件。

---

## 四、复核档位判定

- **判据**：包含新建表与加列的 Flyway 迁移脚本 `V41__customer_public_sea_pool.sql`。
- **档位**：**A 档**。
- **复核执行要求**：
  1. 本地 Docker 环境运行真 MySQL 跑通 `FlywayMigrationIT`，验证 V1~V41 顺序迁移成功；
  2. 跑通 `SchemaDriftAuditIT`，断言零 CRITICAL 且零新增漂移；
  3. 全量 `mvn -B -ntp verify`（含 Testcontainers failsafe IT）BUILD SUCCESS；
  4. 产出执行提示词与复核提示词交给 Owner 亲发。
