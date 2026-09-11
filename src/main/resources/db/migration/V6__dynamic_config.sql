-- ============================================================
-- V6__dynamic_config.sql —— 超级管理员动态配置中心（Lane E · Wave 2，任务 16）
--
-- 目的：
--   为 AI/知识库/业务参数提供「DB 存储 + 按命名空间组织」的运行期动态配置模型（D10）：
--   动态值覆盖静态默认（application.yml / 代码默认），修改后热生效、无需重启。
--   本脚本建两张表：
--     1) dynamic_config_item    配置项表（当前生效值 + 类型/范围/枚举元数据 + 软删除）
--     2) dynamic_config_history 版本历史表（每次变更/回滚追加一行，供查看与回滚）
--
-- 命名空间约定（任务 16 / specs/dynamic-config/spec-delta.md）：
--   ai.prompt.*    提示词（如 ai.prompt.system）
--   ai.model.*     模型 Provider/名称/温度/最大 token（如 ai.model.temperature）
--   rag.retrieval.* 检索 topK/阈值/分块参数/strict-KB 空匹配兜底开关（D16）
--   rag.intent.*    意图类目与关键词、intent-filter-enabled 开关（D17）
--   business.*     限流/配额阈值、数据范围开关、功能开关、图片缓存上限（D13）等业务运行参数
--   配置键 = 命名空间 + '.' + 键名，全局唯一（uk_dynamic_config_key）。
--
-- 字段说明：
--   config_value   按 value_type 序列化的文本：数值类型直接存十进制文本；
--                  STRING_LIST 存 JSON 数组字符串（如 ["财务报销","人事制度"]）。
--   sensitive      1=敏感值：管理端读取与审计时掩码（密钥/凭据仍走环境变量，动态配置不承载）。
--   version        当前版本号：每次变更（含回滚）自增，作为乐观并发与回滚定位依据。
--   is_deleted     软删除：删除配置项 = 恢复静态默认（读取过滤 is_deleted=0 后回退默认值），
--                  历史行保留以便追溯/回滚；软删行再次写入时「复活」而非新建（唯一键约束）。
--   审计列         create_time/update_time + created_by/updated_by（跨域 user:<id> 口径，
--                  见 db-table-coordination.md）。
--
-- 索引：
--   uk_dynamic_config_key          配置键全局唯一（含软删行，复活语义依赖它）
--   idx_dynamic_config_ns          按命名空间分组展示/校验
--   idx_dynamic_config_deleted     读取侧过滤软删
--   idx_dynamic_config_history_key 按 键+版本 查历史与回滚定位（回滚目标=该版本行的 new_value）
--   idx_dynamic_config_history_config 按配置项 ID 查变更时间线
--
-- 回滚注意：
--   本脚本为纯新增（CREATE TABLE IF NOT EXISTS），无破坏性变更；如需回退请用 DB 快照恢复。
--   测试期 H2（MODE=MySQL）与本脚本共用同一 DDL，故避免 MySQL 专有扩展语法。
-- ============================================================

CREATE TABLE IF NOT EXISTS dynamic_config_item
(
    id          bigint auto_increment comment '配置项ID'
        primary key,
    config_key  varchar(200)                          not null comment '配置键（点分命名空间，如 rag.retrieval.topK；全局唯一含软删行）',
    namespace   varchar(50)                           not null comment '命名空间：ai.prompt/ai.model/rag.retrieval/rag.intent/business',
    value_type  varchar(20)                           not null comment '值类型：STRING/INTEGER/LONG/BOOLEAN/DOUBLE/STRING_LIST',
    config_value text                                 null comment '配置值（按类型序列化：数值为十进制文本，STRING_LIST 为 JSON 数组）',
    description varchar(500)                          null comment '配置项含义与影响面（超管界面展示）',
    sensitive   tinyint     default 0                 not null comment '是否敏感值（1=读取/审计掩码；密钥类仍走环境变量，动态配置一般不承载）',
    version     int         default 1                 not null comment '当前版本号（每次变更/回滚 +1，乐观并发依据）',
    is_deleted  tinyint     default 0                 not null comment '软删除（1=已删除：读取回退静态默认，历史保留可回滚/复活）',
    created_by  varchar(100)                         null comment '创建人 user:<id>',
    updated_by  varchar(100)                         null comment '最后修改人 user:<id>',
    create_time datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime    default CURRENT_TIMESTAMP not null comment '最后更新时间（由应用在变更时显式维护，保证 H2/MySQL 行为一致）',
    unique key uk_dynamic_config_key (config_key),
    key idx_dynamic_config_ns (namespace),
    key idx_dynamic_config_deleted (is_deleted)
) comment = '动态配置项表（Lane E；DB 动态值覆盖静态默认，删除即回退默认）';

CREATE TABLE IF NOT EXISTS dynamic_config_history
(
    id             bigint auto_increment comment '历史记录ID'
        primary key,
    config_id      bigint                               not null comment '配置项ID（软删后仍保留引用，供追溯）',
    config_key     varchar(200)                         not null comment '配置键（冗余存储，便于按键查历史与回滚定位）',
    version        int                                  not null comment '本次变更后的版本号（回滚/复活也 +1）',
    value_type     varchar(20)                          not null comment '变更时值类型快照',
    operation_type varchar(20)                          not null comment '操作类型：CREATE/UPDATE/ROLLBACK/DELETE/REVIVE',
    old_value      text                                 null comment '变更前值（CREATE/REVIVE 为空）',
    new_value      text                                 null comment '变更后值（DELETE 为空）',
    operator_ref   varchar(100)                         null comment '操作人 user:<id>',
    remark         varchar(500)                         null comment '操作备注（回滚时记录目标版本号）',
    create_time    datetime   default CURRENT_TIMESTAMP not null comment '操作时间',
    key idx_dynamic_config_history_key (config_key, version),
    key idx_dynamic_config_history_config (config_id)
) comment = '动态配置版本历史表（Lane E；回滚目标 = 目标版本行的 new_value）';
