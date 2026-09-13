# 规范增量：schema-integrity

## ADDED Requirements

### Requirement: 实体与迁移链 schema 一致性门禁
系统 SHALL 对全部 MyBatis-Plus 实体（`@TableName`）与 Flyway 迁移链在真 MySQL 上的实际 schema 做双向比对；实体映射的表或列在真库缺失时 MUST 使集成测试失败。实体清单 MUST 显式登记，扫描数与登记数不一致即失败（防新实体漏审）。

#### Scenario: 实体列缺失即失败
- **WHEN** 某实体字段（经 MP TableInfo 运行期映射得到的列名）在真库对应表中不存在
- **THEN** `SchemaDriftAuditIT` 失败，输出实体类名、字段名、目标表名三要素

#### Scenario: 已修复漂移不误报
- **WHEN** 漂移已由前向迁移补齐（如 V24 的 `approval_attachment.uploader_id`、V21 的 `company_dept.leader_id`）
- **THEN** 审计不再报该项

#### Scenario: 无 Docker 优雅跳过
- **WHEN** 本地无 Docker
- **THEN** IT 按假设跳过且构建不失败（CI 环境真跑），与 `FlywayMigrationIT` 同口径

### Requirement: 漂移分级与处置边界
审计 SHALL 将差异分级：CRITICAL（实体表/列缺失，运行期必炸）、WARN（类型不亲和）、INFO（表冗余列 / 有表无实体，`flyway_schema_history` 豁免）；仅 CRITICAL 触发失败，WARN/INFO MUST 记录于审计报告待人工定夺，不得自行处置。

#### Scenario: 冗余列仅记录不失败
- **WHEN** 真库表存在实体未映射的列
- **THEN** 审计记录为 INFO，构建不因此失败

#### Scenario: 审计报告落盘
- **WHEN** 首轮审计执行完成
- **THEN** `docs/schema-drift-audit.md` 含逐表差异、分级、证据行号与待定夺/待授权清单

### Requirement: 修复仅限前向 additive 迁移
CRITICAL 漂移的修复 MUST 为新增前向迁移（`ADD COLUMN` 可空 / `ADD INDEX` / 缺表 `CREATE TABLE`），号段按 `db-table-coordination.md` lane 归属取段内下一空闲；禁改已合入脚本（Flyway 校 checksum）。需回填、约束收紧、删列、改类型、表重建的修复 MUST 停止执行并等用户授权。

#### Scenario: 号段按 lane 归属
- **WHEN** 为 V1 基座 CRM 表补列
- **THEN** 新迁移落 Lane A（V2x）段内下一空闲版本，并登记进 `FlywayMigrationIT` 全集断言

#### Scenario: 超授权即停
- **WHEN** 漂移修复涉及数据回填或结构性变更（非 additive）
- **THEN** 不执行，写入报告"待授权清单"并停下向用户汇报
