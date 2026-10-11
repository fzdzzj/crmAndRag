-- add-prompt-template-admin 任务 1.1，提示词模板管理双表建表
-- 目的：为 server 域提示词模板产品化提供落库（prompt_template 主表 + prompt_template_history 版本历史表）；
--       提示词从代码/调用方拼接改为数据库记录，支持版本、审计与变量渲染组装。
-- 影响表：prompt_template（新增）；prompt_template_history（新增）。
-- 授权策略：本表不在本脚本授权——权限 808/809 及种子模板由 V33__prompt_template_permission_seed.sql 种植（注册与授权同轮）。
-- 回滚注意：不提供 DROP，回退 = 恢复迁移前数据库快照。

CREATE TABLE prompt_template (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '提示词模板ID',
    code        VARCHAR(64)  NOT NULL COMMENT '模板业务键（全局唯一）',
    name        VARCHAR(128) NOT NULL COMMENT '模板名称',
    category    VARCHAR(64)  NULL DEFAULT NULL COMMENT '模板分类（如 agent-executor/agent-reviewer）',
    content     TEXT         NOT NULL COMMENT '模板正文（含 {{var}} 占位符）',
    variables   TEXT         NULL DEFAULT NULL COMMENT '变量定义 JSON 数组文本：[{"name","required","description"}]（以 TEXT 承载，Jackson 校验）',
    version     INT          NOT NULL DEFAULT 1 COMMENT '当前版本号（每次内容变更 +1）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 启用 / 1 停用',
    remark      VARCHAR(512) NULL DEFAULT NULL COMMENT '备注',
    created_by  BIGINT       NULL DEFAULT NULL COMMENT '创建人用户ID',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by  BIGINT       NULL DEFAULT NULL COMMENT '最后修改人用户ID',
    updated_at  DATETIME     NULL DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_prompt_template_code (code),
    KEY idx_prompt_template_category_status (category, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提示词模板表';

CREATE TABLE prompt_template_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '历史记录ID',
    template_id BIGINT      NOT NULL COMMENT '模板ID',
    version     INT         NOT NULL COMMENT '本次变更后的版本号',
    content     TEXT        NOT NULL COMMENT '历史正文快照',
    variables   TEXT        NULL DEFAULT NULL COMMENT '历史变量定义 JSON 数组文本（以 TEXT 承载）',
    operation   VARCHAR(16) NOT NULL COMMENT '操作类型：CREATE/UPDATE/STATUS',
    operator    BIGINT      NULL DEFAULT NULL COMMENT '操作人用户ID',
    operated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_prompt_template_history_tpl_version (template_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提示词模板版本历史表';