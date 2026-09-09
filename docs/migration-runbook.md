# Flyway 迁移运维手册（Agent-0 · Wave 0）

> 对应任务 4 步骤 5（迁移 / 备份 / 验证 / 回退步骤文档）。目标读者：负责发版的开发与运维。

## 1. 约定

- 库结构唯一真相源 = Flyway 脚本（`src/main/resources/db/migration`），生产 `auto-table.mode=none`。
- 版本号段（`agent-execution-plan.md §5`）：

| 号段 | 归属 | 内容 |
|------|------|------|
| `V1__*` | 基座（Agent-0） | CRM 现有 36 张表基线 |
| `V2x__*` | Lane A | `sys_dept.leader_id`、部门树权限种子 |
| `V3x__*` | Lane B | 知识库 7 表新建 + 授权 userId |
| `V4x__*` | Lane C | `ai_conversation_memory`、`ai_insight` |
| `V5x__*` | Lane D | 配额 / Token / 生命周期 / 对账 / 审计 |
| `V6x__*` | Lane E | 动态配置项 + 版本历史 |

- 号段内递增（如 `V31__`、`V32__`）；跨 lane 依赖表由被依赖方建，消费方不重复建。
- **禁止**修改已合入的脚本（Flyway 会校验 checksum）。改错必须出 `V(n+1)__fix_xxx.sql`。

## 2. 迁移步骤（新环境）

1. 复制 `.env.example` 为 `.env`，填好 `SLZ_DATASOURCE_*`、`SLZ_JWT_SECRET_KEY`、`SLZ_ATTACH_TOKEN_KEY`。
2. 空库建库（utf8mb4）：

   ```sql
   CREATE DATABASE crm DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

3. 以 `SPRING_PROFILES_ACTIVE=prod` 启动，Flyway 自动执行 `V1__baseline.sql`（36 张 CRM 表）。
4. 启动后核对：

   ```sql
   SELECT count(*) FROM information_schema.tables
    WHERE table_schema = database() AND table_name <> 'flyway_schema_history';   -- 期望 36
   SELECT version, description, success FROM flyway_schema_history;              -- 期望 1 行 success=1
   ```

## 3. 迁移步骤（已有 CRM 存量库）

- `spring.flyway.baseline-on-migrate=true` 会把已存在表认作基线并写入 history；
  首次接管前先人工核对表数与 V1 是否一致（36 张）。
- 若存量库结构与 V1 有差异（例如某些列缺失），先补齐再接管，避免后续 V2x 依赖不成立。

## 4. 备份与验证

1. 迁移前备份：

   ```bash
   mysqldump -u$USER -p --single-transaction --routines --triggers crm > crm_before_v1.sql
   ```

2. 迁移后验证：
   - 业务冒烟：登录 → 查询客户列表 → 发起一次 AI 助手对话（验证 SSE 通道）；
   - Flyway 侧：`SELECT count(*) FROM flyway_schema_history WHERE success = 0;` 期望 0。

## 5. 回退

- 本基线**不提供 DROP 回滚**（会丢数据）。回退 = 恢复数据库快照（第 4.1 步的 dump）。
- 后续破坏性变更（删列 / 改列类型）必须：
  1) 在新版本脚本中显式标注 `-- DESTRUCTIVE`；
  2) 先做兼容期（双写或先加列后删列）；
  3) 回退方案写进该脚本头部注释。

## 6. 本地验证方式

- 单元测试：`mvn test`（306 个 CRM 基线测试，不需要数据库）。
- V1 真库验证（可选、需本地 MySQL）：

  ```bash
  mysql -uroot -p -e "CREATE DATABASE crm_v1_verify DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  SLZ_MYSQL_VERIFY_DATABASE=crm_v1_verify \
  SLZ_MYSQL_VERIFY_USERNAME=root \
  SLZ_MYSQL_VERIFY_PASSWORD=*** \
  mvn test-compile failsafe:integration-test -Dit.test=V1BaselineMySqlIT
  mysql -uroot -p -e "DROP DATABASE crm_v1_verify;"
  ```

  该 IT 只在 `SLZ_MYSQL_VERIFY_DATABASE` 存在时执行，因此 CI 无 MySQL 不会误报。
