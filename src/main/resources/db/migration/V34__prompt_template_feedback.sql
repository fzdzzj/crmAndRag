-- prompt-template-enhance 任务 1.1，提示词模板反馈表建表 + 历史表扩 source_version 列
-- 目的：为提示词模板管理闭环补齐使用反馈（feedback）与版本回溯（revert）能力——反馈记录版本效果（score 1-5）、
--       revert 把任意历史版本重新落档为最新版本并保留审计链（operation=REVERT + source_version 记录被回滚的源版本）。
-- 影响表：prompt_template_feedback（新增）+ prompt_template_history（扩列 source_version，可空，兼容既有历史存量）。
-- 授权策略：本表不建权限种子——revert 复用 809 PROMPT_TEMPLATE_MANAGE、feedback 两端点复用 808 PROMPT_TEMPLATE_VIEW
--        （V33 已种，同轮 CROSS JOIN 授权，业务角色全量获权）。
-- 回滚注意：不提供 DROP，回退 = 恢复迁移前数据库快照。

-- 使用反馈表：score TINYINT 1-5 评分，comment 最大 512 字，索引 template_id+version 便于按模板版本检索。
CREATE TABLE prompt_template_feedback (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '反馈记录ID',
    template_id BIGINT       NOT NULL COMMENT '提示词模板ID',
    version     INT          NOT NULL COMMENT '被反馈的模板版本号',
    score       TINYINT      NOT NULL COMMENT '评分 1-5',
    comment     VARCHAR(512) NULL DEFAULT NULL COMMENT '反馈意见（≤512 字符）',
    operator    BIGINT       NULL DEFAULT NULL COMMENT '反馈人用户ID',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '反馈时间',
    PRIMARY KEY (id),
    KEY idx_prompt_template_feedback_tpl_version (template_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提示词模板使用反馈表';

-- 历史表扩 source_version：revert 落档 REVERT 笔时记录被回滚的目标版本号（源版本已存在，本列仅作显式追溯）。
-- 既有 create/update/status 笔 source_version 为 NULL，兼容存量数据。
ALTER TABLE prompt_template_history
    ADD COLUMN source_version INT NULL COMMENT 'revert 时被回滚的源版本号（仅 REVERT 笔非空）' AFTER version;