-- AI 会话持久记忆（任务 10 / C6）
-- ai_message 是唯一对话真相源；本表只保存 summary/facts/intent 三类加工品。
-- session_id 唯一保证 1:1 挂在 ai_session 下，重启/多实例可通过 DB 恢复；
-- user_id 是 CRM 域内 BIGINT，不是知识库跨域 userIdRef 字符串；
-- version 为乐观锁，配合 OptimisticLockerInnerInterceptor 防旁路任务并发覆盖。
CREATE TABLE IF NOT EXISTS ai_conversation_memory
(
    id           bigint auto_increment
        primary key,
    session_id   bigint       not null comment '所属AI会话',
    user_id      bigint       not null comment 'CRM域归属用户',
    summary      text         null comment '历史摘要（<=2000字符）',
    facts        text         null comment '已确认事实JSON数组（<=8条）',
    intent       varchar(200) null comment '当前单值意图（<=200字符）',
    version      int          default 0 not null comment '乐观锁版本号',
    created_time datetime     not null comment '创建时间',
    updated_time datetime     not null comment '更新时间',
    constraint uk_ai_memory_session
        unique (session_id)
)
    comment 'AI会话持久记忆';
