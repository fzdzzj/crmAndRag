-- ============================================================
-- V29__kb_retrieval_strategy.sql —— 知识库级检索策略覆盖（add-per-kb-retrieval-strategy-override 任务 1.2）
--
-- 目的：
--   为企业内不同类型知识库（FAQ 库 vs 产品手册库）提供「按库覆盖检索参数」的能力。
--   第一档运营调参键（恰 12 键白名单，见 proposal §1 / docs/dynamic-config-keys.md）允许 900 权限
--   运营面写入；成本类/结构类键永远不得进入 per-KB 覆盖（白名单封闭集）。
--   本脚本建两张表：
--     1) kb_retrieval_strategy        活覆盖表（当前生效值 + 软删除 + 乐观版本 + 审计）
--     2) kb_retrieval_strategy_history 版本历史表（每次写入/回滚追加一行，供追溯与回滚）
--
-- 语义（对齐 dynamic_config_item / dynamic_config_history 软删模式，V6）：
--   - (kb_id, strategy_key) 活覆盖唯一：软删行与活行同 key 时不新建，而是「复活」既有软删行
--     （read 过滤 is_deleted=0；写方若存在软删行则置回 0，维护唯一约束）。
--   - config_value  按 strategy_key 对应注册表类型的规范化文本存储；写前过白名单 + 类型/范围校验。
--   - version       每次变更（含回滚/复活）自增，作为乐观并发与回滚定位依据。
--   - is_deleted    软删除 = 该库该键覆盖失效、回落全局配置（历史保留可回滚/复活）。
--   - created_by/updated_by/created_at/updated_at 审计四列（跨域 user:<id> 口径，对齐 V6/V26）。
--
-- 读端三层合并（任务 3）：覆盖值（合法且单库作用域内） > 全局动态配置 > 注册表默认。
--   覆盖值非法/越界 = WARN 审计日志 + 回落全局，任何情况不得打断检索链路。
--
-- 影响表：kb_retrieval_strategy（活覆盖）；kb_retrieval_strategy_history（版本历史）。
-- 授权策略：写端 4 端点（清单/PUT 覆盖/DELETE 回落/POST 回滚）复用 PermissionOperates.KNOWLEDGE_ADMIN_MANAGE(900)，
--           V27 已种子，本脚本不新增常量、不新增种子。
-- 回退注意：本脚本为纯新增（CREATE TABLE IF NOT EXISTS），无破坏性变更；如需回退请用 DB 快照恢复。
--           测试期 H2（MODE=MySQL）与本脚本共用同一 DDL，故避免 MySQL 专有扩展语法。
-- ============================================================

CREATE TABLE IF NOT EXISTS kb_retrieval_strategy
(
    id            bigint auto_increment comment '覆盖记录ID'
        primary key,
    kb_id         bigint        not null comment '知识库ID（对齐 knowledge_base.id bigint）',
    strategy_key  varchar(128)  not null comment '覆盖键（12 键白名单内全限定键，如 rag.retrieval.topK）',
    config_value  varchar(512)  not null comment '覆盖值（按注册表类型的规范化文本；Integer/Double/Boolean/String 各自范围，写前校验）',
    version       int           default 1                 not null comment '当前版本号（每次变更/回滚 +1，乐观并发依据）',
    is_deleted    tinyint       default 0                 not null comment '软删除（1=已删除：覆盖失效回落全局，历史保留可回滚/复活）',
    created_by    varchar(100)  null comment '创建人 user:<id>',
    updated_by    varchar(100)  null comment '最后修改人 user:<id>',
    created_at    datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_at    datetime      default CURRENT_TIMESTAMP not null comment '最后更新时间（由应用在变更时显式维护，保证 H2/MySQL 行为一致）',
    unique key uk_kb_strategy (kb_id, strategy_key),
    key idx_kb_strategy_deleted (is_deleted)
) comment = '知识库级检索策略覆盖表（add-per-kb-retrieval-strategy-override；单库作用域 + 三层合并）';

CREATE TABLE IF NOT EXISTS kb_retrieval_strategy_history
(
    id            bigint auto_increment comment '历史记录ID'
        primary key,
    strategy_id   bigint      not null comment '覆盖记录ID（软删后仍保留引用，供追溯）',
    kb_id         bigint      not null comment '知识库ID（冗余存储，便于按库查历史与回滚）',
    strategy_key  varchar(128) not null comment '覆盖键（冗余存储）',
    version       int         not null comment '本次变更后的版本号（回滚/复活也 +1）',
    operation_type varchar(20) not null comment '操作类型：CREATE/UPDATE/DELETE/REVIVE/ROLLBACK',
    old_value     varchar(512) null comment '变更前值（CREATE/REVIVE 为空）',
    new_value     varchar(512) null comment '变更后值（DELETE 为空）',
    operator_ref  varchar(100) null comment '操作人 user:<id>',
    remark        varchar(500) null comment '操作备注（回滚时记录目标版本号）',
    created_at    datetime    default CURRENT_TIMESTAMP not null comment '操作时间',
    key idx_kb_strategy_history_key (strategy_key, kb_id, version),
    key idx_kb_strategy_history_strategy (strategy_id)
) comment = '知识库级检索策略版本历史表（add-per-kb-retrieval-strategy-override；回滚目标 = 目标版本行的 new_value）';
