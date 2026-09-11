-- 平台治理域表结构（Lane D · V5）
-- 目的：承载 Token 计量/预算、内容安全事件、生命周期事件、跨存储对账和治理审计。
-- 影响表：全部为新增表，不修改业务表；无破坏性变更。
-- 回滚注意：这些表在业务接入后包含审计与恢复证据；生产回滚前必须先备份。

CREATE TABLE IF NOT EXISTS platform_token_usage
(
    id                bigint auto_increment comment '计量明细ID'
        primary key,
    model             varchar(100)  not null comment '实际模型名',
    user_id_ref       varchar(100)  not null comment '用户引用 user:<id>，系统任务为 user:system',
    session_id        varchar(100)  null comment '会话或业务批次ID',
    knowledge_base_id bigint        null comment '知识库ID；非知识库调用为空',
    usage_type        varchar(30)   not null comment '计量类型 chat/embedding/ocr/vision/summary/intent',
    prompt_tokens     bigint        not null default 0 comment '输入token',
    completion_tokens bigint        not null default 0 comment '输出token',
    total_tokens      bigint        not null default 0 comment '总token',
    success           tinyint       not null default 1 comment '调用是否成功；失败也计量',
    create_time       datetime      not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time       datetime      null comment '更新时间',
    constraint chk_platform_token_usage_non_negative
        check (prompt_tokens >= 0 and completion_tokens >= 0 and total_tokens >= 0)
)
    comment 'AI模型调用token计量明细';

create index idx_platform_token_user_time
    on platform_token_usage (user_id_ref, create_time);
create index idx_platform_token_kb_time
    on platform_token_usage (knowledge_base_id, create_time);
create index idx_platform_token_type_time
    on platform_token_usage (usage_type, create_time);

CREATE TABLE IF NOT EXISTS platform_token_budget
(
    id           bigint auto_increment comment '预算ID'
        primary key,
    scope_type   varchar(30)   not null comment '范围 USER/SESSION/KNOWLEDGE_BASE/GLOBAL',
    scope_id     varchar(100)  not null comment '范围值；全局固定 GLOBAL',
    period_type  varchar(10)   not null comment '周期 DAY/MONTH',
    token_limit  bigint        not null comment '周期内总token上限',
    enabled      tinyint       not null default 1 comment '是否启用',
    create_time  datetime      not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time  datetime      null comment '更新时间',
    constraint chk_platform_token_budget_positive
        check (token_limit > 0),
    constraint uk_platform_token_budget_scope
        unique (scope_type, scope_id, period_type)
)
    comment 'AI token周期预算';

CREATE TABLE IF NOT EXISTS platform_content_security_event
(
    id           bigint auto_increment comment '安全事件ID'
        primary key,
    source_type  varchar(30)   not null comment '来源 USER_INPUT/DOCUMENT/RETRIEVAL_CHUNK',
    source_id    varchar(100)  null comment '来源业务ID',
    risk_level   varchar(20)   not null comment '风险等级 SAFE/LOW/MEDIUM/HIGH',
    action       varchar(20)   not null comment '处置 ALLOW/DEGRADE/REVIEW/BLOCK',
    matched_rule varchar(100)  null comment '命中规则名',
    reason       varchar(500)  null comment '命中原因',
    payload      text          null comment '策略版本与脱敏后上下文',
    create_time  datetime      not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time  datetime      null comment '更新时间'
)
    comment '内容安全分级事件';

create index idx_platform_content_security_source
    on platform_content_security_event (source_type, source_id, create_time);

CREATE TABLE IF NOT EXISTS platform_lifecycle_event
(
    id               bigint auto_increment comment '生命周期事件ID'
        primary key,
    document_id      varchar(100)  not null comment '文档业务ID',
    event_type       varchar(30)   not null comment '事件 INGEST/PROCESS/DELETE/ARCHIVE/REBUILD/RESTORE',
    status_version   bigint        not null default 0 comment '状态版本，防乱序覆盖',
    idempotency_key  varchar(150)  not null comment '幂等键',
    payload          text          null comment '事件扩展数据',
    processed_time   datetime      null comment '处理完成时间',
    create_time      datetime      not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time      datetime      null comment '更新时间',
    constraint uk_platform_lifecycle_idempotency
        unique (idempotency_key)
)
    comment '文档生命周期幂等事件';

create index idx_platform_lifecycle_document
    on platform_lifecycle_event (document_id, status_version);

CREATE TABLE IF NOT EXISTS platform_reconcile_report
(
    id              bigint auto_increment comment '对账报告ID'
        primary key,
    report_id       varchar(50)   not null comment '对账报告业务ID',
    scan_type       varchar(30)   not null comment '扫描类型 MINIO/VECTOR/SNAPSHOT/FULL',
    dry_run         tinyint       not null default 1 comment '是否dry-run',
    status          varchar(20)   not null comment '状态 RUNNING/COMPLETED/FAILED',
    total_differences int         not null default 0 comment '差异数',
    started_time    datetime      not null comment '开始时间',
    completed_time  datetime      null comment '完成时间',
    retained_until  datetime      null comment '清理保留截止时间',
    operator_user_ref varchar(100) not null comment '操作人 user:<id>',
    create_time     datetime      not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time     datetime      null comment '更新时间',
    constraint uk_platform_reconcile_report_id
        unique (report_id)
)
    comment '跨存储对账报告';

CREATE TABLE IF NOT EXISTS platform_reconcile_item
(
    id            bigint auto_increment comment '差异明细ID'
        primary key,
    report_id     varchar(50)   not null comment '所属报告ID',
    storage_type  varchar(30)   not null comment '存储 MYSQL/MINIO/QDRANT/SNAPSHOT',
    resource_id   varchar(150)  not null comment '资源业务ID',
    diff_type     varchar(30)   not null comment '差异 MISSING/ORPHAN/STALE',
    detail        varchar(1000) null comment '差异详情',
    action        varchar(30)   null comment '建议或执行动作',
    resolved      tinyint       not null default 0 comment '是否已处理',
    resolved_time datetime      null comment '处理时间',
    create_time   datetime      not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time   datetime      null comment '更新时间',
    constraint uk_platform_reconcile_item
        unique (report_id, storage_type, resource_id, diff_type)
)
    comment '跨存储对账差异明细';

create index idx_platform_reconcile_resource
    on platform_reconcile_item (storage_type, resource_id);

CREATE TABLE IF NOT EXISTS platform_governance_audit
(
    id             bigint auto_increment comment '治理审计ID'
        primary key,
    event_type     varchar(50)   not null comment '事件类型 QUOTA_ADJUSTED/CLEANUP_TRIGGERED等',
    actor_user_ref varchar(100)  not null comment '操作人 user:<id>',
    target_type    varchar(50)   not null comment '目标类型 QUOTA/DOCUMENT/REPORT等',
    target_id      varchar(150)  not null comment '目标业务ID',
    action         varchar(50)   not null comment '动作',
    result         varchar(30)   not null comment '结果 SUCCESS/FAILED/DENIED',
    detail         text          null comment '审计扩展数据',
    trace_id       varchar(64)   null comment '链路ID',
    create_time    datetime      not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time    datetime      null comment '更新时间'
)
    comment '平台治理审计事件';

create index idx_platform_audit_actor_time
    on platform_governance_audit (actor_user_ref, create_time);
create index idx_platform_audit_target_time
    on platform_governance_audit (target_type, target_id, create_time);
