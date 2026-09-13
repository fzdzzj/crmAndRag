-- ============================================================
-- V25__invoice_info_add_remark.sql —— 实体↔真库 schema 漂移根修（audit-entity-table-drift 任务 4.x）
--
-- 背景：
--   WriteChainRegressionIT 诊断发现同根因漂移（实体带列、迁移链建表无列）不止 approval_attachment.uploader_id 一处。
--   全量审计（SchemaDriftAuditIT，真 MySQL + Flyway 全链单向比对）定位到剩余 1 处 CRITICAL：
--     InvoiceInfoEntity.remark（text，@Column type="text"）实体有；
--     V1__baseline.sql 的 invoice_info 建表无 remark 列（L526-L548）。
--   → 真库上 invoice_info 任何全字段查询（MP selectList/selectById）都会抛 Unknown column 'remark'（对外 90004）。
--   单一案在 H2 auto-table（按实体建表）长期不可见，仅真库暴露——与 approval_attachment.uploader_id 完全同类。
--
-- 迁移策略：
--   additive（仅 ADD COLUMN ... NULL），符合本提案预授权上限；不改任何已合入脚本（Flyway 校 checksum）。
--   remark 为可空 text，无存量数据（本库尚无生产/测试数据），无需回填。是本提案内唯一补列修正。
--
-- 号段归属：
--   invoice_info 属 V1 基座 CRM 业务表（db-table-coordination.md 表归属矩阵）→ 复用 Lane A 的 V2x 段下一空闲 = V25。
--
-- 回滚注意：
--   纯新增列，无破坏性变更；如需回退请用 DB 快照恢复（本仓库约定不提供 DROP 回滚）。
-- ============================================================

ALTER TABLE invoice_info
    ADD COLUMN remark text NULL COMMENT '备注（如：开票说明、特殊要求等）';