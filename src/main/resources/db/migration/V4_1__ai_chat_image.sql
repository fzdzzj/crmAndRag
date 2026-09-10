-- AI 助手聊天图片存储（任务 10 图文解耦）
-- imageRef 只指向本表 ID/SHA-256，不回退“最近一张图”；L1 结果按会话内 hash 去重。
CREATE TABLE IF NOT EXISTS ai_chat_image
(
    id              bigint auto_increment
        primary key,
    session_id      bigint       not null comment '所属AI会话',
    user_id         bigint       not null comment 'CRM域归属用户',
    image_hash      char(64)     not null comment '图片SHA-256',
    storage_backend varchar(16)  not null comment '存储后端:minio|local',
    storage_key     varchar(255) not null comment '存储键',
    ocr_text        text         null comment 'OCR文本',
    image_summary   varchar(300) null comment '图片摘要（<=300字符）',
    key_entities    json         null comment '关键实体JSON数组',
    create_time     datetime     not null default CURRENT_TIMESTAMP comment '创建时间',
    update_time     datetime     not null default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP comment '更新时间',
    is_deleted      tinyint(1)   not null default 0 comment '是否删除',
    constraint uk_ai_chat_image_session_hash
        unique (session_id, image_hash)
)
    comment 'AI会话聊天图片';
