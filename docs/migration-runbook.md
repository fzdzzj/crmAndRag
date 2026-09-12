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

### 6.1 两类测试与命令

- 单元测试：`mvn -B -ntp test`（surefire，`**/*Test.java`，不需要数据库也不需要 Docker）。
  实测口径（2026-09-11，本机 `mvn -B -ntp verify`）：`target/surefire-reports` 下 93 个 `.txt` 报告，`Tests run` 合计 **467**。
  此数不依赖 Docker，CI 同口径，即 `.github/workflows/ci.yml` 阶段3 的 surefire 基线。
  （旧文档曾写「306 个 CRM 基线测试」，那是 V1 接管期的数字，已作废。）
- 集成测试：`mvn -B -ntp verify` 追加 failsafe（`**/*IT.java`、`**/*IntegrationTest.java`），报告写入 `target/failsafe-reports`。
  可执行 IT 共 8 个类（`AbstractMySqlIT` 是抽象基类，不产出报告）；无 Docker 实测其中 7 个的 `Tests run` 合计 **12**。

### 6.2 覆盖边界：本地无 Docker 时 `mvn verify` 不是绿灯

实测（2026-09-11，Docker Desktop 未运行）：`Tests run: 479, Failures: 0, Errors: 11, Skipped: 1` → **BUILD FAILURE**。

- **会直接报错的**：`AbstractMySqlIT` 的 5 个子类共 11 个用例——`CompanyGroupDeleteIT` 4、`CustomerCompanyControllerIT` 1、
  `CustomerCompanyDeptUniqueIT` 2、`PermissionControllerIT` 2、`WriteChainRegressionIT` 2。
  根因：`AbstractMySqlIT` 第 24 行以静态字段急加载 `new MySQLContainer<>("mysql:8.0.36")`，**没有 `assumeTrue` 守卫**，
  Docker 缺失时类初始化失败抛 `ExceptionInInitializerError`，同类其余用例接着报 `NoClassDefFoundError`。
- **会优雅跳过的只有三处**：
  1. `FlywayMigrationIT` 第 54 行 `assumeTrue(DockerClientFactory.instance().isDockerAvailable())` 在 `@BeforeAll`，
     整类中止，报告记 `Tests run: 0`（该类只有 1 个 `@Test`）；
  2. `V1BaselineMySqlIT` 需 `SLZ_MYSQL_VERIFY_DATABASE`，实测 `Tests run: 1, Skipped: 1`；
  3. `ModelProviderImplDashScopeIT` 第 91 行 `assumeTrue(apiKey 非空)`（3 个 `@Test`），无 key 时全 skip。

**因此：本地无 Docker 时，Flyway 真库迁移这条路径是被跳过（`Tests run: 0`）而不是通过。**
不能用本地构建结果支撑「Flyway 迁移已验证」或「真机模型链路已验证」；这两条只在 CI（`ubuntu-latest` 自带 Docker）真跑。

### 6.3 反向警告：本地 `mvn verify` 可能真的打外网

`ModelProviderImplDashScopeIT` 的 key 解析顺序是「环境变量 `DASHSCOPE_API_KEY` → 仓库根 `.env` 同名键」。
**只要任一处有值，它就会真跑**，向 `https://dashscope.aliyuncs.com/compatible-mode/v1` 发真实 chat/stream/embed 请求并消耗额度。
当前仓库根 `.env` 含该键（2026-09-11 实测命中 1 处），所以本地随手 `mvn verify` 会产生外发请求；
不想打外网又不能用 `-Dit.test=!ModelProviderImplDashScopeIT`（见 6.4），需临时把 env 与 `.env` 两处都置空。

### 6.4 `-Dit.test` 的坑

`-Dit.test=...` 会**覆盖** pom 里 failsafe 的 `<includes>`，而不是在其之上再过滤。
实测：`mvn -B -ntp verify "-Dit.test=!ModelProviderImplDashScopeIT"` 让 failsafe 把 93 个单元类全部又跑了一遍，
`target/failsafe-reports` 出现 100 个 `.txt`、`Tests run` 合计 479（= surefire 467 + IT 12）。
只想跑单个 IT 时用白名单写法，例如 `-Dit.test=V1BaselineMySqlIT`（见 6.5）。

### 6.5 V1 真库验证（可选、需本地 MySQL）

```bash
mysql -uroot -p -e "CREATE DATABASE crm_v1_verify DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
SLZ_MYSQL_VERIFY_DATABASE=crm_v1_verify \
SLZ_MYSQL_VERIFY_USERNAME=root \
SLZ_MYSQL_VERIFY_PASSWORD=*** \
mvn test-compile failsafe:integration-test -Dit.test=V1BaselineMySqlIT
mysql -uroot -p -e "DROP DATABASE crm_v1_verify;"
```

该 IT 只在 `SLZ_MYSQL_VERIFY_DATABASE` 存在时执行，因此 CI 无 MySQL 不会误报。

### 6.6 怎么读跳过数

看 `target/failsafe-reports/<类全名>.txt` 首行：`Tests run: X, Failures: Y, Errors: Z, Skipped: W`。

- `FlywayMigrationIT` 的 `X = 0` 就是「整类被 `@BeforeAll` 假设中止」的信号；
- **`X` 含 `W`**（skipped 也计入 `Tests run`），所以「总数没降」不等于「真跑了」，判断是否真跑要看 `W` 是否为 0；
- surefire 侧同理读 `target/surefire-reports/<类全名>.txt`。

CI 侧 failsafe 期望值推算为 12 + `FlywayMigrationIT` 1 + `ModelProviderImplDashScopeIT` 3 = **16**（推算值，非实测）。
`.github/workflows/ci.yml` 阶段3 当前把 failsafe 基线保守设在实测下限 12；首次 Docker 可用的 CI 跑完后，
应按 `target/failsafe-reports` 的实测合计把该数字上调。
