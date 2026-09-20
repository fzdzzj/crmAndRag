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
- **实际落地与本表不符（以 `ls src/main/resources/db/migration` 为准）**：Wave 1 未启用 `V2x`/`V3x`/`V4x`/`V5x`/`V6x` 十位号段，历史脚本用了个位数 `V1`/`V3`/`V4`/`V4_1`/`V5`/`V6`；自 V21 起改顺序号，快照截至 2026-09-19 最高为 `V27`，**下一可用号取目录实测**。本表只作 lane 归属溯源，不作取号依据。
- **禁止**修改已合入 master 的脚本（Flyway 会校验 checksum）。改错必须出 `V(n+1)__fix_xxx.sql`；新增脚本必须同轮登记进 `FlywayMigrationIT` 的 `EXPECTED_VERSIONS`（见 `openspec/git-workflow.md` §4）。

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
  **基线数字以 `scripts/test-baseline.txt` 为准（阈值不在 YAML 里，`.github/workflows/ci.yml` 阶段3 只调 `bash scripts/check-test-baseline.sh` 裁决；当前 surefire 724 / failsafe 66，截至 2026-09-20 合并树实测 baseline `e2785b2`）**；本文不复制数字，历史快照（如"快照截至 2026-09-11 的 467"、"surefire 657 / failsafe 13"）一律以该文件与 `git log -p -- scripts/test-baseline.txt` 为准，更早的"306 个 CRM 基线测试"口径已作废。
  此数不依赖 Docker，CI 与本机同口径。
- 集成测试：`mvn -B -ntp verify` 追加 failsafe（`**/*IT.java`、`**/*IntegrationTest.java`），报告写入 `target/failsafe-reports`。
  可执行 IT 类共 **19 个**（`src/test/**/*IT.java` 实测 20 个文件，`AbstractMySqlIT` 是抽象基类不产出报告；`**/*IntegrationTest.java` 实测 0 个），声明用例 **66** 个（52 个 `@Test` 方法 + 1 个参数化方法展开为 14 次执行；19 份报告首行合计 `Tests run: 66, Skipped: 6`）；聚合口径与基线同样以 `scripts/test-baseline.txt` 为准。

### 6.2 覆盖边界：本地无 Docker 时 `mvn verify` 的"绿"不代表验证过

按现存 `src/test/**/*IT.java` 全量分类（TASK-22 复核 2026-09-21：逐类读文件头与守卫 + 合并树全量 `mvn verify` 的 `target/failsafe-reports/*.txt` 首行；"用例"= 该类报告的 `Tests run` 数，含参数化展开。TASK-24 复核 2026-09-21：`QdrantTimeoutConfigIT` 摘除误挂的 `@Testcontainers(disabledWithoutDocker = true)` 后由 B 组改归 A 组，B/A 计数与结论句同步。TASK-003R 复核：原 D 组已整体并入 B 组）：

**A · 真跑（无 Docker、无 env 依赖，本地与 CI 同口径）**

| 类 | 用例 | 说明 |
|---|---|---|
| `integration/permission/PermissionCoverageAuditIT` | 2 | 端点权限覆盖静态扫描门禁，纯 JVM |
| `integration/FailFastIntegrationIT` | 1 | 真启动链 Fail-Fast：Qdrant 指向必然拒绝连接的端口，数据源走 H2、存储走内存回退，全程离线 |
| `integration/vector/QdrantTimeoutConfigIT` | 3 | 纯 JVM：properties 装配 + `new QdrantVectorStore(...)` 构造断言（该类构造器 javadoc 明示连接错误延迟到实际请求），不启容器、不连库；TASK-24 摘除误挂的 Docker 注解后真跑 |

**B · 优雅跳过（Docker 守卫 → 整类不执行，报告记 `Tests run: 0`）——共 12 类 / 54 用例**

守卫写在本类：

| 类 | 用例 | 守卫位置 |
|---|---|---|
| `integration/FlywayMigrationIT` | 3 | `@BeforeAll` `assumeTrue(isDockerAvailable)`（真库迁移链全集门禁，`EXPECTED_VERSIONS` 需与迁移目录同步） |
| `integration/SparseRecallServiceIT` | 4 | `@BeforeAll` 同上 |
| `integration/ChunkNgramRecallGateIT` | 15 | `@BeforeAll` 同上；断言走生产稀疏服务（`SparseRecallService`→`DocumentVectorChunkMapper`，TASK-21），14 个 LEXICAL 参数化用例 + 1 个语料哨兵 = 15 次执行 |
| `integration/schema/SchemaDriftAuditIT` | 1 | `@BeforeAll` 同上 |

守卫继承自 `AbstractMySqlIT`（基类 `@BeforeAll` + `assumeTrue(isDockerAvailable)` + `mysqlStarted` 幂等；8 个子类自身均无 `static {}`、无 `@BeforeAll`、不引用 `MYSQL`）：

| 类 | 用例 |
|---|---|
| `integration/controller/CompanyGroupDeleteIT` | 4 |
| `integration/controller/CustomerCompanyControllerIT` | 1 |
| `integration/controller/CustomerCompanyDeptUniqueIT` | 2 |
| `integration/controller/PermissionControllerIT` | 3 |
| `integration/controller/PermissionsInterceptorStatusIT` | 3 |
| `integration/controller/PermissionApplyReportIT` | 5 |
| `integration/security/WriteChainRegressionIT` | 2 |
| `integration/service/CacheEvictIT` | 11 |

**C · 优雅跳过（env 显式门控，与 Docker 无关）**

| 类 | 用例 | 门控 |
|---|---|---|
| `unit/db/V1BaselineMySqlIT` | 1 | `@EnabledIfEnvironmentVariable(SLZ_MYSQL_VERIFY_DATABASE)` |
| `platform/model/ModelProviderImplDashScopeIT` | 3 | `@BeforeEach assumeTrue(RAG_BENCHMARK_REAL==1)`，再要 `DASHSCOPE_API_KEY`（见 6.3） |
| `quality/RagRealRetrievalBenchmarkIT` | 1 | `@Test assumeTrue(RAG_BENCHMARK_REAL==1)` + key |
| `knowledge/document/VisionPdfRealPilotIT` | 1 | `@Test assumeTrue(RAG_VISION_PDF_REAL==1)` + key |

**历史坑（已闭合，遇到旧日志时对照用）**：`AbstractMySqlIT` 曾在 `static {}` 里直接 `MYSQL.start()` 且无 `assumeTrue`，当时 Docker 缺失会让类初始化抛 `ExceptionInInitializerError`、同类其余用例连锁 `NoClassDefFoundError`——那 20 个用例整批变 Errors 把构建直接搞红。TASK-005（2026-09-19）把容器启动移到 `@BeforeAll` 的 `assumeTrue` 之后，并用 `mysqlStarted` 保证共享容器在同 JVM 内只启一次，故这 7 类改判为 B 组。⚠ "无 Docker 记 skipped"这一分支**尚未在无 Docker 的机器上实测**（本机 Docker 在线，Testcontainers 策略链无法用环境变量模拟缺失），依据是同构先例 + 静态成因已消除（见 `work/mailbox/tasks/TASK-005/handoff.md` 未完成 1）；真无 Docker 的 runner 复验前，B 组的跳过结论按"待复验"对待。

**因此：本地无 Docker 时 `mvn verify` 大概率是"绿但不证明任何东西"**——B 组 54 个用例、C 组 6 个用例全记跳过，只剩 A 组 6 个真跑。不能用本地构建结果支撑「Flyway 真库迁移已验证」「真 MySQL 写链/数据权限已验证」或「真机模型链路已验证」；这三条只在 CI（`ubuntu-latest` 自带 Docker）真跑。

### 6.3 反向警告：真外发 IT 一律需显式 opt-in

三个真发模型请求的 IT 都走环境变量开关，**默认全关**：

- `ModelProviderImplDashScopeIT` 与 `RagRealRetrievalBenchmarkIT` 共用 `RAG_BENCHMARK_REAL=1`；
- `VisionPdfRealPilotIT` 用 `RAG_VISION_PDF_REAL=1`；
- key 解析顺序都是「环境变量 `DASHSCOPE_API_KEY` → 仓库根 `.env` 同名键」，而 `.env` 常备该键。

⚠ 因两个基准共用同一个开关名，**一旦 `RAG_BENCHMARK_REAL=1` 且不加过滤，`mvn verify` 会同时打真机模型 IT 与基准 IT**，两边都烧额度。跑基准仍按铁律只 `-Dit.test=RagRealRetrievalBenchmarkIT` 白名单过滤（见 6.4）；不想外发就别设该变量。

### 6.4 `-Dit.test` 的坑

`-Dit.test=...` 会**覆盖** pom 里 failsafe 的 `<includes>`，而不是在其之上再过滤。
实测：`mvn -B -ntp verify "-Dit.test=!ModelProviderImplDashScopeIT"` 让 failsafe 把全量单元类又跑了一遍（`target/failsafe-reports` 的报告数与 `Tests run` 合计因此会异常虚高——等于 surefire 口径 + IT 口径的和）。
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

failsafe 侧的当前口径、CI 期望值与"无 Docker 下限"的含义一律看 `.github/workflows/ci.yml` 口径B 注释（该处有"首次 Docker 可用的 CI 跑完后按 `target/failsafe-reports` 实测合计上调"的 TODO）。本文不复制推算值。
