-- add-cost-key-approval-workflow 任务 1.2，成本键变更申请单表
-- 目的：为成本键（COST 22 键）热调提供申请-审批工作流支撑，实现申请留痕、审批流转与生效审计；
--       审批通过后以审批人身份自动写入动态配置并回填生效版本号。
-- 影响表：cost_key_change_request（新增）。
-- 授权策略：无权限种子——本卡零新权限号，5 端点复用 608 PLATFORM_DYNAMIC_CONFIG_MANAGE，审批/驳回由服务层超管闸控制。
-- 回滚注意：不提供 DROP，回退 = 恢复迁移前数据库快照。

CREATE TABLE cost_key_change_request (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    config_key VARCHAR(128) NOT NULL COMMENT '申请变更的配置键',
    requested_value VARCHAR(512) NOT NULL COMMENT '期望值',
    reason VARCHAR(512) NULL DEFAULT NULL COMMENT '申请理由',
    status VARCHAR(16) NOT NULL COMMENT '申请状态：PENDING/APPROVED/REJECTED/WITHDRAWN',
    requester_id BIGINT NOT NULL COMMENT '申请人用户 ID',
    approver_id BIGINT NULL DEFAULT NULL COMMENT '审批人用户 ID',
    reject_reason VARCHAR(512) NULL DEFAULT NULL COMMENT '驳回理由',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请创建时间',
    decided_at DATETIME NULL DEFAULT NULL COMMENT '决策时间（审批/驳回/撤回）',
    applied_config_version BIGINT NULL DEFAULT NULL COMMENT '写入后的配置版本号',
    PRIMARY KEY (id),
    KEY idx_status_created (status, created_at),
    KEY idx_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成本键变更申请单表';
