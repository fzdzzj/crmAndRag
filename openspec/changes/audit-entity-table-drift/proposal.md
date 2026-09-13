# 提案：实体↔真库 schema 漂移全量审计（永久门禁 + CRITICAL 根修）

> 变更 ID：`audit-entity-table-drift` ｜ 能力域：`schema-integrity` ｜ 序列：WriteChain 诊断 P1 收口（无前置依赖，独立可执行）
> 来源：`docs/writechain-regression-diagnosis.md` §3.4 / §4.4-P1 —— V24 只修了 `approval_attachment.uploader_id` 一处，同根因（实体带列而迁移链无列）大概率不止一处。

## Why

1. **漂移类缺陷已实证**：WriteChainRegressionIT 红灯 2 根因 = `ApprovalAttachmentEntity.uploader_id` 实体有、`V1__baseline.sql` 建表没有 → 真库上该表任何全字段查询 100% `BadSqlGrammarException`（对外 90004）。V24 已补，但那只是诊断时"顺带发现的一处"。
2. **掩盖机制至今仍在**：单测全跑 H2 auto-table（按实体建表），实体带什么列 H2 表就有什么列——**列漂移在单测层结构性不可见**。唯一暴露面是真库（Testcontainers MySQL + Flyway 全链），而现状只有 `FlywayMigrationIT` 抽查 5 张表存在性 + 个别列断言，**没有系统性的"实体字段 ↔ 表列"双向比对**。
3. **暴露面 = 53 个实体 × 10 个迁移版本**：`pojo/entity` 38 + `knowledge/entity` 7 + `platform/*` 8，映射 53 张表；迁移链 V1/V3/V4/V4.1/V5/V6/V21/V22/V23/V24 分属不同 lane、不同时期合入。任何一处"改实体忘配套迁移"都是下一颗只在真库/生产炸的 90004 地雷。

## What Changes

### 1. 漂移审计设施（测试域，纯函数核心可单测）
- `SchemaDriftAuditor`（`src/test/java/com/slz/crm/integration/schema/`）：
  - **实体侧**：ClassPath 扫描 `@TableName`（base package `com.slz.crm`，当前预期 53 个）→ 用 MyBatis-Plus `TableInfoHelper.initTableInfo` 提取每实体的表名 + 列名全集。走 MP 运行期同一套映射逻辑（含 `@TableId`、`@TableField` 显式列名、`exist=false` 排除、驼峰转下划线），不做正则解析 Java 源码。
  - **库侧**：Testcontainers `mysql:8.0.36`（与 `FlywayMigrationIT` 同镜像口径）+ Flyway 全链 V1..V2x → `information_schema.columns` 取表/列/类型/可空性。
  - **比对分级**：
    - **CRITICAL** = 实体映射的表在真库缺失，或实体列在表缺失（运行期必炸类，即 V24 同类）；
    - **WARN** = 粗粒度类型不亲和（如实体 Long ↔ 表 varchar）；
    - **INFO** = 表列实体未映射（冗余列）、有表无实体（`flyway_schema_history` 除外）。
- 比对器为纯函数（输入两侧 Map，输出分级 finding 列表），配 surefire 单测。

### 2. 永久门禁 IT（防再犯，本提案的核心价值）
- `SchemaDriftAuditIT`（failsafe，命名 `*IT.java`，无 Docker `assumeTrue` 跳过——本地无 Docker 不炸，CI ubuntu 真跑）：
  - CRITICAL 非空 → fail，逐项输出实体类名、字段、目标表名；
  - 今后"改实体忘配套迁移"在 CI 直接红，而不是等生产 90004。
- 实体清单登记：53 张表名+实体类登记为常量；扫描数 ≠ 登记数即失败（新实体必须同步登记，防漏审）。

### 3. 首轮审计 + CRITICAL 根修（预授权上限 = additive）
- 首跑全量差异落盘 `docs/schema-drift-audit.md`（逐表差异、分级、证据行号）。
- **CRITICAL 修复只允许前向 additive 迁移**（`ADD COLUMN ... NULL` / `ADD INDEX` / 缺表时 `CREATE TABLE`），号段按 `spec/changes/add-crm-rag-fusion-platform/db-table-coordination.md` lane 归属取段内下一空闲（V1 基座 CRM 表 → Lane A 的 V2x 段，即 V25 起）；**禁改已合入脚本**（Flyway 校 checksum）。
- 同步 `FlywayMigrationIT.EXPECTED_VERSIONS` 与执行数断言；重跑审计 CRITICAL 清零。
- **超出预授权即停**：需回填、NOT NULL 收紧、删列、改类型 → 不做，写进报告"待授权清单"停下汇报用户。
- WARN/INFO 只记录不处理（处理需单独授权）。
- 已知正确性自然校验：`approval_attachment.uploader_id`（V24 已修）必须不在 CRITICAL 清单中。

### 4. 收尾
- `mvn -B -ntp test` 全绿（surefire 590 → 590+N，实测计数）；`ci.yml` 基线三处同步；`HANDOFF.md` 与诊断报告 §4.4-P1 关闭标注。

## Impact

- **新增**：测试域审计器 + 比对器单测 + `SchemaDriftAuditIT`；`docs/schema-drift-audit.md`；（如首跑发现 CRITICAL）`V25__*.sql` 等 additive 迁移。
- **修改**：`FlywayMigrationIT`（登记新版本与计数）、`ci.yml`（基线数字）、`HANDOFF.md`、`docs/writechain-regression-diagnosis.md`（P1 关闭）。
- **不改**：任何运行期业务行为、既有迁移脚本内容、产品代码。

## 风险

- 修复迁移 = 数据库结构变更，属需授权动作 → 本提案把预授权上限定为"additive 且可空"，其余一律停下报告。
- MP `TableInfoHelper` 对特殊映射（typeHandler、加密映射）可能产生非物理列名 → 提取逻辑配单测兜底；发现特殊案例记录进审计报告再定夺。
- 审计对象是**迁移链定义的 schema**，不是生产实库（本地无生产数据；生产实库比对属生产运维另案）。

## Non-Goals

- 不做生产库实库比对（本地无生产数据）。
- 不清理冗余列/无实体表（INFO 级只记录，处理需单独授权）。
- 不改任何检索/业务行为，不做 reingest/模型调用（全程 ¥0 外发）。
