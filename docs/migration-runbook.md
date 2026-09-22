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

### 6.7 `maven-pmd-plugin` 静态检查：**已启用**（原豁免登记，2026-09-21 由 `wire-pmd-ruleset` P2 解除）

**当前状态（2026-09-21 P2 实测，非推断）**：`pom.xml` pmd 块的 `<skip>true</skip>` **已删除**（`grep -c "<skip>" pom.xml` 实测 **0**），`mvn -B -ntp pmd:check`、`mvn -B -ntp verify` 与本地聚合门禁 `bash scripts/merge-gate.sh` 的 `[pmd]` 都在真跑，`.github/workflows/ci.yml` 的静态检查步骤也已把 `pmd:check` 加回。判定口径三层、每层只有一个读者：

- **量哪把尺子** = pom pmd 块的 `<ruleset>src/main/resources/pmd-rules.xml</ruleset>`（声明 **25 条**规则；2026-09-21 Q4 拍板补回 `EmptyControlStatement` 后 24→25，不是插件内置 quickstart）。行号随 pom 漂移，定位一律 `grep -n "<ruleset>src" pom.xml`。
- **哪条违规计入条数** = 同块 `<failurePriority>`（`grep -n failurePriority pom.xml`）。它管优先级，与条数不可换算。
- **计入多少条算失败** = 同块 `<maxAllowedViolations>`（`grep -n maxAllowedViolations pom.xml`）——**全仓唯一的条数读者**。它的值只允许由一次真实 `mvn -B -ntp pmd:check` 经 `bash scripts/tests/pmd-baseline-check.sh --update` 写进台账 `scripts/tests/pmd-violation-baseline.txt`（头三行口径照 `scripts/test-baseline.txt`：`source-revision` + `measured-at` + 生成命令），**只许下调不许上调**，且 `--update` 在实测 > 登记时直接拒绝写入。当前值与完整口径（含"只覆盖 `src/main/java`""86.4% 集中在 3 条规则"）以那两个文件为准，**本节不复制数字**，避免又养出第二个读者。
- **边界语义是实测的**（2026-09-21 两次真跑：实测=登记 → exit 0；实测=登记+1 → exit 1）：插件按"严格大于才失败"。推论很重要 —— 修掉一条异味后 `pmd:check` **仍然绿**，"该下调了"只能由 `bash scripts/tests/pmd-baseline-check.sh` 判（登记 > 实测 → 红；实测 > 登记 → 红；pom 与台账两个数字不等 → 红）。自测锁在 `scripts/tests/merge-gate-selftest.sh` 场景 11（含 CRLF 双向）。

以下三条是**解除前的原豁免理由**。按 `wire-pmd-ruleset` spec R4「解除必须留真跑证据」逐条标注消除日期并**保留原措辞作历史对照，不抹除**；任一条复活都按本节末的复测命令重新登记。

登记依据：`openspec/changes/operationalize-harness-gates` 组 3（spec R1 第③要素「项 + 关闭理由 + 复测命令 + 到期触发条件」）。该豁免此前只存在于引入提交 `ba57e2b` 的正文里，本节是它第一次进被跟踪文档。以下数字均为 2026-09-21 离线实测（`mvn -o`），非历史快照。

- **项**（历史表述，解除前）：`pom.xml` 的 `maven-pmd-plugin` 插件块（定位用 `grep -n "<artifactId>maven-pmd-plugin" pom.xml`；该块内的 `<skip>true</skip>` 是字面量，`-Dpmd.skip=false` 抬不动）。绑定 `verify` 阶段，即当前 `mvn verify` 与 CI 阶段2 里 PMD 一步都不跑。
  → 2026-09-21 `wire-pmd-ruleset` P1 后该块**已接** `<rulesets>`（唯一尺子 = `src/main/resources/pmd-rules.xml`，不再是内置 quickstart），但 `<skip>` 仍在位，所以"PMD 一步都不跑"这一条**尚未改变**——变的是"打开时跑的是哪把尺子"。
  → **2026-09-21 P2 已消除**：`<skip>` 已从 pom 删除（`grep -c "<skip>" pom.xml` = 0），上面那句"PMD 一步都不跑"自此起不再成立；本节保留它是为了对照"当初为什么关"。
- **关闭理由**（三条，逐条有实测，任一未解都不该打开）：
  1. **（已消除，2026-09-21 P1）摘除 `<skip>` 后 PMD 会跑，但跑的不是仓库声明的那把尺子**。当时 `grep -n "ruleset" pom.xml` 实测 **0 命中** —— 插件从未通过 `<rulesets>` 引用 `src/main/resources/pmd-rules.xml`，该文件是**孤儿配置**。此时 `mvn -o -B -ntp pmd:check` 退出 1，报 **122 条**违规（9 个规则，priority 3 共 56 条 / priority 4 共 66 条），其中 `UnnecessaryFullyQualifiedName`(62)、`UnusedPrivateMethod`(32)、`CollapsibleIfStatements`(5)、`UselessParentheses`(4)、`UnnecessarySemicolon`(2)、`UnnecessaryModifier`(1) **6 个规则不在仓库声明的 28 条之内**（属插件内置 `rulesets/java/quickstart.xml`）。P1 接线后同一命令复现 122/9/56+66 不变（该数只在**未接线**状态下出现）。
  2. **（已消除，2026-09-21 P1）按声明接线会直接构建硬错**。`src/main/resources/pmd-rules.xml` 的 28 条去重 `ref` 与本地缓存 `pmd-java-7.9.0.jar` 内 7 个 `category/java/*.xml` 比对，实测 **18 条可解析 / 10 条不可解析**（本条此前写"19 条可解析 / 10 条不可解析"，19+10=29>28 是计数错一位，2026-09-21 P1 复算更正）：9 条规则名在 7.9.0 已不存在（`codestyle/MultipleStringLiterals`、`codestyle/UnusedImports`、`bestpractices/MethodReturnsNewArray`、`security/HardCodedCrypto`、`security/InsecureCryptoWithIV`、`performance/SimplifyStartsWith`、`errorprone/AvoidCatchingGenericException`、`errorprone/DetectedEmptyClause`、`errorprone/CheckResultSet`，其中 2 条只是搬了分类），另 1 条是分类**文件名**写错（`category/java/codestyle.java/LocalVariableNamingConventions` 应为 `codestyle.xml`）。
     → **P1 新发现的第二把锁（本条此前不知道）**：失效的除了 `ref`，还有**规则属性名**——`CognitiveComplexity`/`CyclomaticComplexity`/`NcssCount` 的 `classMax`/`methodMax`、`TooManyMethods` 的 `maxMethods`、`CommentSize` 的 `minLines` 在 7.9.0 一律 "Cannot set non-existent property"，只修 ref 依旧跑不出 `target/pmd.xml`。且**同一个 `<properties>` 块里首个错误会吞掉后续报文**（一次运行只报 5 条，真数是逐条别名探针逼出来的），所以"数日志错误行数 = 坏条数"是**错的判别式**。已按 7.9.0 真名（`reportLevel` / `classReportLevel` / `methodReportLevel` / `maxmethods` / `maxLines`）改名保数值修好；`CognitiveComplexity` 的类级 `75` 因 7.9.0 该规则只剩方法级而被丢弃（平台强制，非选择）。
  3. **（曾长期未消除，2026-09-21 P2 才消除）阈值口径未定夺**：pom 注释写"阈值≤5"、`<failurePriority>4</failurePriority>`（真读者，但与"条数"不可换算）两处互不相干。（本条原列的第三处 —— `ci.yml` 的 `PMD_MAX_VIOLATIONS: 5` —— 已于 2026-09-21 `operationalize-harness-gates` 组 5.1 作为**无读者的装饰性阈值删除**，该 env 现不存在。）
     → 更正：装饰性"条数阈值"其实还有**第三处**在本节普查面之外——`src/main/resources/pmd-rules.xml:4` 头部注释"阈值≤5 violations 通过"。它是**规则集文件自己**的话，`grep pom.xml scripts/ docs/` 按定义扫不到，所以"全仓不存在第二处条数阈值表述"这类判别必须把 `src/main/resources/*.xml` 纳入普查面。
     → **2026-09-21 P2 已消除**：条数口径落到 `maxAllowedViolations` 这一个读者（值与只降不升的约束见本节开头），三处装饰性表述里活着的两处 —— `pom.xml` 的"代码异味检测（阈值≤5）"与 `src/main/resources/pmd-rules.xml` 头部的"阈值≤5 violations 通过" —— 已改写为指认真读者的措辞（句中不再出现任何条数数字）。扩面普查（`pom.xml scripts/ docs/ openspec/ src/main/resources/*.xml` + `.github/workflows/ci.yml`）实测：除上述两处已清外，全仓只剩**引用式指针**（`docs/` 与 `openspec/` 里转述"某处曾写着阈值≤5"、`ci.yml:119` 列已删 env 名的历史说明），按 `harness-gates` R1 口径不算第二处读者。
  → 修复要动 `src/main/resources/pmd-rules.xml` 并重新定阈值口径，越出组 3"只动 pom 两个插件块"的文件面，**另立项**。→ 已立项 `openspec/changes/wire-pmd-ruleset`：理由 1/2 由 P1（接线修尺）消除，理由 3 待 P2（落 `maxAllowedViolations` + 摘 skip + 回接门禁）。
  → **2026-09-21 P2：三条理由全部消除，本豁免作废**（`spec R4` 的"到期触发条件 ③"即此）。
- **复测命令**（本机离线可复现，产物只落 `target/`；P2 删掉 `<skip>` 后**不再需要**任何临时旁路）：
  ```bash
  grep -c "<skip>" pom.xml                    # 0 = 未退回跳过态；>0 即豁免复活，本节"已启用"结论当场作废
  grep -n "rulesets" pom.xml                  # 无输出 = 又退回内置 quickstart（理由 1 复活）
  grep -o 'ref="category/java/[^"]*"' src/main/resources/pmd-rules.xml | wc -l   # 2026-09-21 P1 接线后 24；Q4 拍板补回后 25（接线前 28）
  mvn -o -B -ntp pmd:check                    # 基线内：exit 0 且打印 "The build has not failed because N violations are allowed (maxAllowedViolations)."
  grep -c "<violation " target/pmd.xml        # 接线后声明尺子的底数：2026-09-21 实测 1329；与台账 pmd.violations 相等才算未过期
  bash scripts/tests/pmd-baseline-check.sh    # 登记 / 实测 / pom 三方对齐判别（任一对不齐即非零，含"该下调了"这一项）
  grep -o 'priority="[0-9]"' target/pmd.xml | sort | uniq -c   # p1=39 / p3=1290（p2/p4=0）
  ```
  历史口径（解除前、`wire-pmd-ruleset` P1 期间用的旁路法，原文保留）：当时要先"临时注释掉 pom pmd 块的 `<skip>`"才跑得动，跑完**必须把 `<skip>` 原样放回**，否则 `mvn verify` 会因这 1329 条红掉（接线前是 122 条）；`grep -c "PMD Failure" /tmp/pmd.log` 与条数相等，而 **122 只会在未接线（内置 quickstart）状态下出现**。自 P2 删除 `<skip>` 起，"放回"这一步连同它带来的"本地跑一次就脏一次 pom"的副作用一起消失。
  口径提醒：PMD 默认源目录**不含 `src/test/java`**，该 1329 只覆盖 241 个 main 文件（P2 复测同值），不能与 SpotBugs 的 12 条 High 基线类比。
- **到期触发条件**（① ② ③ 均已于 2026-09-21 触发/满足，本豁免至此作废；保留原文以便下一次有人把 PMD 关掉时知道该补什么）：① 下一次任何 Java 侧变更触碰 `pom.xml` 的 pmd 块或 `src/main/resources/pmd-rules.xml` 时，必须复跑上面的复测命令并按实测更新本条（**2026-09-21 已由 `wire-pmd-ruleset` P1 触发一次并按实测改写**：接线状态、18/10 计数更正、属性名陷阱、24 条尺子、1329 底数；**P2 再次触发**：删 `<skip>`、落 `maxAllowedViolations`、改写本条状态；**Q4 拍板后触发**（2026-09-21）：补回 `codestyle/EmptyControlStatement`，24→25 条、实测 0 命中、底数与阈值不变）；② 阈值口径定夺完成（`failurePriority` 与条数二选一、`ci.yml` 的 env 有读者或删除）时（**P2 已完成**：条数口径 = `maxAllowedViolations` 单一读者，`failurePriority` 只管"哪条计入"，`ci.yml` 无任何阈值 env）；③ 上述理由 1/2/3 全部消除后本豁免作废并删除 `<skip>`，且需按 6.2 的口径补一次"`mvn verify` 真跑 PMD"的实测记录（**P2 已完成**，记录见下一条）。

- **解除后的真跑记录**（到期条件③要求的"`mvn verify` 真跑 PMD"，按 6.2 的口径把两件事分开陈述）：2026-09-21 本机（Windows，`core.autocrlf=true`；Maven 3.9.4 / JDK 21.0.9 / maven-pmd-plugin 3.26.0 / PMD 7.9.0；命令 `mvn -o -B -ntp verify`，离线；耗时 05:57；exit **0 / BUILD SUCCESS**）：
  - **PMD 真跑 = 是**：`verify` 生命周期里 `--- pmd:3.26.0:pmd (pmd) ---`（`:5210`）与 `--- pmd:3.26.0:check (pmd-check) ---`（`:5234`）两个 goal 都实际执行，结论行是 `PMD 7.9.0 has found 1329 violations.` + `The build has not failed because 1329 violations are allowed (maxAllowedViolations).`（`:6564`/`:6565`），`target/pmd.xml` 当场重新产出。单点调用同口径：`mvn -o -B -ntp pmd:check` → exit 0。日志：`work/mailbox/tasks/PMDC-P2/run-8-verify-real.log`。
  - **failsafe 新鲜度 = 是（但这一条与本轮简报相反，如实登记）**：同一轮里 surefire `Tests run: 724, Failures: 0, Errors: 0, Skipped: 0`（`:3261`）与 failsafe `Tests run: 66, Failures: 0, Errors: 0, Skipped: 6`（`:5189`）双双等于 `scripts/test-baseline.txt`，且日志里能看到 Testcontainers 真的创建了 `mysql:8.0.36` 容器 —— 说明**这台机器本轮 Docker 可用**，那 6 个跳过是上面 6.2 的 **C 组 env 门控**（`RAG_BENCHMARK_REAL` / `RAG_VISION_PDF_REAL` / `SLZ_MYSQL_VERIFY_DATABASE`），不是"缺 Docker 导致 B 组整类假设跳过"。
  - 因此本条记录**只支撑**"PMD 在 verify 里真跑 + 本机一轮 IT 新鲜"，**不支撑**"无 Docker 时 B 组会优雅跳过"——6.2 里"该分支尚未在无 Docker 的机器上实测"那句悬置状态**未被本轮改变**，仍需一台真无 Docker 的 runner 复验。也不改变 6.2 的分层：「Flyway 真库迁移已验证」「真 MySQL 写链/数据权限已验证」这类结论必须有真库报告为凭，不能拿 `pmd:check` 的绿来顶。
- **对照记录**（同一次变更的另 half，不属豁免）：`spotbugs-maven-plugin` 的 `<skip>` **已于 2026-09-21 删除并启用**，阈值的唯一读者是 pom 该块的 `threshold=High` + `excludeFilterFile=src/main/resources/spotbugs-exclude.xml`（存量 High 基线 12 条，由 `mvn -B -ntp compile spotbugs:spotbugs` 的一次真实运行生成，只允许由同一条命令更新）。新出现的 High 缺陷会让 `mvn -B -ntp spotbugs:check` 与 `mvn verify` 直接变红。
  → **2026-09-21 `wire-pmd-ruleset` P2：这处不对称已闭合** —— PMD 现在与 SpotBugs 同态（pom 单一读者 + 入库台账 + 过期防呆脚本 + 能变红的自测），本节剩下的"豁免"字样全部是解除前的历史对照。

### 6.8 `tighten-pmd-violations` 分片 C 收窄：AvoidCatchingGenericException 归零（2026-09-22）

**Q6 处置方式（owner 已拍板，混合双轨）**：① 叶子层能确定抛出源者**收窄为具体异常**；② 真正顶层兜底（调度 / 外呼 / 文件边界 / SSE 生命周期 / 异步任务）用 `@SuppressWarnings("PMD.AvoidCatchingGenericException")` + 中文理由注释，**不动宽捕获逻辑**。

**实测结果（总收窄后）**：134 处 `catch` 通用异常归零，CATCH=0，全仓 PMD 条数 **459→325**（台账与 pom `<maxAllowedViolations>` 同步落 325）。逐模块处置表（收窄 25 + 豁免 109，其中 8 个 catch 共享 101 个方法级注解）：

| 模块 | 收窄 | 豁免 |
|---|---|---|
| common | 3 | 6 |
| quality | 0 | 1 |
| knowledge | 1 | 46 |
| platform | 0 | 12 |
| server | 21 | 44 |
| **合计** | **25** | **109** |

（磁盘数一致的权威处置表见 `openspec/changes/tighten-pmd-violations/tasks.md` 3.1-T；每处豁免都带中文理由注释。）

**两个新坑（复跑时对照）**：

- **PMD 的 `@SuppressWarnings` 必须带 `PMD.` 前缀**（`PMD.AvoidCatchingGenericException`）才被识别；裸规则名字符串（`"AvoidCatchingGenericException"`）实测无效——带类型解析的 catch 计数纹丝不动，直到加上前缀。
- **`java.lang.SuppressWarnings` 不可重复**：同一元素上两个 `@SuppressWarnings` 注解 = 编译失败，进而中断 Lombok 注解处理，级联出大量假「找不到符号」（ErrorCode/ProjectFileCategory/PermissionOperates/ActuatorProtectionFilter 等）。必须合并成单个 `@SuppressWarnings({"unchecked","PMD.AvoidCatchingGenericException"})`。合并后 `mvn -B -ntp test` 724/0/0/0 全绿复验。

**读数坑（重基线时对照）**：`mvn clean pmd:check`（无编译产物）产出的是**伪值**——无类型解析时 PMD 会额外误报 `UnnecessaryImport` 等，当时读到 334 = 真值 325 + `UnnecessaryImport`(9)。**PMD 分析依赖已编译 class**，耗准确读数必须 `mvn -B -ntp compile pmd:check`（有 class）或直接 `mvn -B -ntp verify`。`pmd-baseline-check.sh --update` 硬拒绝上调，故向上对齐只能手编台账+pom 且需 owner 覆盖，非真值勿走此路。
