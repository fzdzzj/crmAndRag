-- ============================================================
-- V41__customer_public_sea_pool.sql —— add-customer-public-sea-pool：客户公海池与全生命周期流转机制
--
-- 背景：
--   当前 customer_company 由录入人/负责人（creator_id / owner_id）永久持有，缺乏"私海跟进 -> 超时自动回收 /
--   主动退回 -> 公海池共享 -> 认领/分派"的客户资产流转闭环。僵尸客户沉淀、离职划转无通道、占着客户不跟进。
--
-- 设计（对应 openspec/changes/add-customer-public-sea-pool/proposal.md §1）：
--   1. customer_company 扩展 4 列（纯 additive，不动 V1~V40 任何已合入脚本）：
--      - pool_status           TINYINT NOT NULL DEFAULT 0：公海状态，0-私海客户，1-公海池客户；
--      - pool_release_time     DATETIME NULL：进入公海时间（主动退回或系统自动回收时写入 CURRENT_TIMESTAMP）；
--      - pool_release_reason   VARCHAR(255) NULL：进入公海的原因（销售主动放弃 / 超时未跟进系统自动回收 / 离职划转等）；
--      - last_activity_time    DATETIME NULL：最近一次有效跟进时间（商业活动保存时异步联动刷新）；
--      组合索引 idx_customer_company_pool (pool_status, is_deleted) 加速公海列表与超时回收扫描。
--   2. 新增公海流转记录表 customer_pool_log：记录 CLAIM/RELEASE/ASSIGN/AUTO_RECYCLE 全生命周期流转时间线。
--
-- 存量数据语义（安全）：
--   存量客户均为私海客户 -> pool_status 默认 0 即可，无需回填；last_activity_time 未知为 NULL。
--
-- 号段归属：
--   customer_company 属 V1 基座 CRM 业务表 -> 顺序号段下一空闲 = V41（当前目录实测最高 V40）。
--
-- 回滚注意：
--   纯新增列与新增表，无破坏性变更；如需回退请用 DB 快照恢复（本仓约定不提供 DROP 回滚）。
-- ============================================================

-- 1. customer_company 扩展公海流转字段
ALTER TABLE customer_company
    ADD COLUMN pool_status TINYINT NOT NULL DEFAULT 0 COMMENT '公海状态（0-私海客户，1-公海池客户）',
    ADD COLUMN pool_release_time DATETIME NULL DEFAULT NULL COMMENT '进入公海时间（主动退回或系统自动回收时间）',
    ADD COLUMN pool_release_reason VARCHAR(255) NULL DEFAULT NULL COMMENT '进入公海的原因（销售主动放弃/超时未跟进自动回收/离职划转等）',
    ADD COLUMN last_activity_time DATETIME NULL DEFAULT NULL COMMENT '最近一次有效跟进时间（商业活动保存时异步联动刷新）';

ALTER TABLE customer_company
    ADD KEY idx_customer_company_pool (pool_status, is_deleted);

-- 2. 公海流转记录表
CREATE TABLE customer_pool_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '流转记录主键 ID',
    company_id BIGINT NOT NULL COMMENT '关联客户公司 ID',
    operator_id BIGINT NOT NULL COMMENT '操作人 ID（系统自动回收记录为 0）',
    action_type VARCHAR(32) NOT NULL COMMENT '流转动作：CLAIM-认领 / RELEASE-主动退回 / ASSIGN-主管分派 / AUTO_RECYCLE-系统超时自动回收',
    previous_owner_id BIGINT NULL DEFAULT NULL COMMENT '原负责人 ID',
    new_owner_id BIGINT NULL DEFAULT NULL COMMENT '新负责人 ID',
    reason VARCHAR(255) NULL DEFAULT NULL COMMENT '流转原因或备注说明',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_pool_log_company (company_id),
    KEY idx_pool_log_operator (operator_id),
    KEY idx_pool_log_action (action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户公海流转记录表（全生命周期流转时间线）';