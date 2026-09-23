# Tasks — audit-entity-table-drift

> 执行契约见 `openspec/git-workflow.md`（分支/提交粒度/合并/基线 bump）；本提案当前基线：master @ `82b0bca`，surefire 590。

## 1. 审计设施：实体元数据提取

- [x] 1.1 `SchemaDriftAuditor` 实体侧：ClassPath 扫描 `@TableName`（base package `com.slz.crm`），当前预期 53 个（pojo 38 / knowledge 7 / platform 8）；扫描结果落"实体登记清单"常量（表名 + 实体类名），扫描数 ≠ 登记数即报错——新实体必须同步登记，防漏审
- [x] 1.2 用 MyBatis-Plus `TableInfoHelper.initTableInfo`（`MapperBuilderAssistant` + `MybatisConfiguration`）提取每实体 表名 + 列名全集：`@TableId` 计入、`@TableField` 显式列名优先、`exist=false` 排除、无注解字段走驼峰转下划线；输出 `Map<表名, Set<列名>>`
- [x] 1.3 提取逻辑单测（surefire）：对已知实体（如 `ApprovalAttachmentEntity` 必含 `uploader_id`；含 `exist=false` 字段的实体不产生该列）断言映射正确

## 2. 审计设施：真库内省 + 比对器

- [x] 2.1 真库侧：参照 `FlywayMigrationIT` 起 `mysql:8.0.36` 容器 + Flyway 全链 → `SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns`（按 `table_schema` 过滤本库）
- [x] 2.2 `SchemaDriftComparator`（纯函数）：输入实体侧/库侧两个 Map，输出分级 finding——CRITICAL（实体表缺失 / 实体列缺失）、WARN（类型不亲和）、INFO（表冗余列 / 有表无实体，`flyway_schema_history` 豁免）
- [x] 2.3 比对器单测（surefire，≥4 条）：构造夹具分别命中三档分类 + 零差异夹具产出空 finding

## 3. 永久门禁 IT + 首轮审计报告

- [x] 3.1 `SchemaDriftAuditIT`（failsafe，`*IT.java`）：Docker 门控 `assumeTrue`（无 Docker 跳过不炸，CI 真跑）；CRITICAL 非空 → fail 并逐项输出实体类名、字段名、目标表名；INFO/WARN 不失败
- [x] 3.2 首轮真跑，全量差异落盘 `docs/schema-drift-audit.md`：逐表清单、分级、证据（实体类 file:line ↔ 迁移链现状）、CRITICAL 修复建议（目标迁移号段）
- [x] 3.3 正确性自然校验：`approval_attachment.uploader_id`（V24 已修）不在 CRITICAL；`company_dept.leader_id`（V21 已加）等已知迁移不在误报清单

## 4. CRITICAL 根修（预授权上限 = additive 迁移）

- [x] 4.1 按 `spec/changes/add-crm-rag-fusion-platform/db-table-coordination.md` lane 归属定版本号（V1 基座 CRM 表 → V2x 段下一空闲，从 V25 起；跨 lane 表按归属段取号，拿不准停下问用户）；只允许 `ADD COLUMN ... NULL` / `ADD INDEX` / 缺表 `CREATE TABLE`
- [x] 4.2 每个新迁移登记进 `FlywayMigrationIT.EXPECTED_VERSIONS`，执行数断言同步；新迁移头部注释标注"audit-entity-table-drift 任务 4.x，源自 schema 漂移审计"
- [x] 4.3 重跑 `SchemaDriftAuditIT`：CRITICAL 清零全绿；WARN/INFO 留报告"待定夺清单"不自行处理
- [x] 4.4 若发现需回填 / NOT NULL 收紧 / 删列 / 改类型 / 表重建 → 不做，写进报告"待授权清单"并停下汇报用户

## 5. 回归与收尾

- [x] 5.1 `mvn -B -ntp test` 全绿，surefire 590 → 602（实测）；`ci.yml` 三处同步（口径A注释、`check_baseline` 数字、错误提示行数字）
- [x] 5.2 failsafe 侧基线保持 12（无 Docker 下限，本 IT 无 Docker 跳过不改变它）；`ci.yml` 口径B 注释更新 CI 推算 16→17。`docs/migration-runbook.md` §6 已读
- [x] 5.3 `HANDOFF.md` 更新（P1 关闭、迁移链 V1..V25、审计门禁上线）；`docs/writechain-regression-diagnosis.md` §4.4-P1 标注已修复
- [x] 5.4 git 收尾：`feature/audit-entity-table-drift`，提交按任务组（`type(scope): 中文描述`），亲验 `mvn -B -ntp test` + `git status` 干净后 `--no-ff` 合入 master，汇报带 commit hash
