-- ============================================================
-- V3__knowledge.sql —— 知识库域 7 表（Lane B-persist）
-- 表名统一单数；审计列统一 create_time/update_time；软删统一 is_deleted。
-- 跨域用户引用统一 user_id varchar(100)，格式 user:<sys_user.id>。
-- ============================================================

CREATE TABLE IF NOT EXISTS knowledge_base
(
    id            bigint auto_increment comment '知识库ID' primary key,
    name          varchar(100)  not null comment '知识库唯一名称',
    display_name  varchar(200)  not null comment '展示名称',
    owner_user_id varchar(100)  not null comment '负责人跨域引用 user:<id>',
    visibility    varchar(20)   not null default 'PRIVATE' comment 'PRIVATE=私有 PUBLIC=登录可见',
    create_time   datetime      not null default current_timestamp comment '创建时间',
    update_time   datetime      not null default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted    tinyint       not null default 0 comment '是否删除 0=否 1=是',
    constraint uk_knowledge_base_name unique (name)
) comment '知识库表';

CREATE TABLE IF NOT EXISTS knowledge_base_member
(
    id                bigint auto_increment comment '成员ID' primary key,
    knowledge_base_id bigint       not null comment '知识库ID',
    user_id           varchar(100) not null comment '成员跨域引用 user:<id>',
    member_role       varchar(20)  not null default 'READER' comment 'OWNER/EDITOR/READER',
    create_time       datetime     not null default current_timestamp comment '创建时间',
    update_time       datetime     not null default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted        tinyint      not null default 0 comment '是否删除 0=否 1=是',
    constraint uk_knowledge_base_member unique (knowledge_base_id, user_id, is_deleted)
) comment '知识库成员表';

CREATE INDEX idx_knowledge_base_member_user ON knowledge_base_member (user_id);

CREATE TABLE IF NOT EXISTS uploaded_file
(
    id               bigint auto_increment comment '上传文件ID' primary key,
    user_id          varchar(100)  not null comment '上传人跨域引用 user:<id>',
    filename         varchar(255)  not null comment '展示文件名',
    original_filename varchar(255) not null comment '原始文件名',
    file_type        varchar(50)   not null comment '文件类型小写标识',
    document_id      varchar(64)   not null comment '文档业务唯一键',
    storage_key      varchar(500)  not null comment '对象存储Key',
    file_size        bigint        null comment '文件大小字节',
    content_type     varchar(100)  null comment 'MIME类型',
    segment_count    int           not null default 0 comment '文本分块数',
    vector_count     int           not null default 0 comment '向量数',
    status           varchar(30)   not null default 'PROCESSING' comment 'PROCESSING/COMPLETED/FAILED',
    error_message    varchar(1000) null comment '失败原因',
    deleted_by       varchar(100)  null comment '删除人跨域引用 user:<id>',
    file_hash        varchar(64)   null comment 'SHA-256哈希',
    batch_task_id    varchar(64)   null comment '批量任务业务ID',
    knowledge_base   varchar(100)  not null comment '所属知识库ID字符串',
    create_time      datetime      not null default current_timestamp comment '创建时间',
    update_time      datetime      not null default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted       tinyint       not null default 0 comment '是否删除 0=否 1=是',
    constraint uk_uploaded_file_document unique (document_id)
) comment '知识库上传文件表';

CREATE INDEX idx_uploaded_file_user ON uploaded_file (user_id);
CREATE INDEX idx_uploaded_file_status ON uploaded_file (status);
CREATE INDEX idx_uploaded_file_knowledge_base ON uploaded_file (knowledge_base);
CREATE INDEX idx_uploaded_file_batch_task ON uploaded_file (batch_task_id);

CREATE TABLE IF NOT EXISTS document_vector_chunk
(
    id                 bigint auto_increment comment '切片ID' primary key,
    document_id        varchar(64)  not null comment '所属文档业务键',
    chunk_index        int          not null comment '切片序号 0起',
    chunk_text         text         not null comment '切片原文',
    chunk_hash         varchar(64)  null comment '切片内容哈希',
    filename           varchar(255) null comment '来源文件名',
    category           varchar(100) null comment '类目或意图过滤标签',
    keywords           varchar(500) null comment '关键词',
    extra_metadata_json varchar(1000) null comment '扩展元数据JSON',
    page_no            int          null comment '页码 1起',
    row_index          int          null comment 'Excel行号 1起',
    create_time        datetime     not null default current_timestamp comment '创建时间',
    update_time        datetime     not null default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted         tinyint      not null default 0 comment '是否删除 0=否 1=是',
    constraint uk_document_vector_chunk unique (document_id, chunk_index)
) comment '文档向量切片快照表';

CREATE INDEX idx_document_vector_chunk_document ON document_vector_chunk (document_id);

CREATE TABLE IF NOT EXISTS chunk_upload_session
(
    id                bigint auto_increment comment '分片会话ID' primary key,
    upload_session_id varchar(64)  not null comment '分片上传业务键',
    task_id           varchar(64)  null comment '批量任务业务ID',
    user_id           varchar(100) not null comment '上传人跨域引用 user:<id>',
    original_filename varchar(255) not null comment '原始文件名',
    total_chunks      int          not null default 0 comment '总片数',
    received_chunks   int          not null default 0 comment '已接收片数',
    upload_id         varchar(200) null comment '外部上传ID',
    status            varchar(30)  not null default 'CREATED' comment 'CREATED/RECEIVING/COMPLETED/FAILED',
    knowledge_base    varchar(100) not null comment '所属知识库ID字符串',
    create_time       datetime     not null default current_timestamp comment '创建时间',
    update_time       datetime     not null default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted        tinyint      not null default 0 comment '是否删除 0=否 1=是',
    constraint uk_chunk_upload_session unique (upload_session_id)
) comment '分片上传会话表';

CREATE INDEX idx_chunk_upload_session_user ON chunk_upload_session (user_id);

CREATE TABLE IF NOT EXISTS batch_task
(
    id             bigint auto_increment comment '批量任务ID' primary key,
    task_id        varchar(64)  not null comment '批量任务业务键',
    user_id        varchar(100) not null comment '创建人跨域引用 user:<id>',
    knowledge_base varchar(100) not null comment '所属知识库ID字符串',
    category       varchar(100) null comment '统一类目',
    total_files    int          not null default 0 comment '文件总数',
    success_count  int          not null default 0 comment '成功数',
    failure_count  int          not null default 0 comment '失败数',
    status         varchar(30)  not null default 'CREATED' comment 'CREATED/PROCESSING/COMPLETED/FAILED',
    error_message  varchar(1000) null comment '失败原因',
    create_time    datetime     not null default current_timestamp comment '创建时间',
    update_time    datetime     not null default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted     tinyint      not null default 0 comment '是否删除 0=否 1=是',
    constraint uk_batch_task_task unique (task_id)
) comment '批量上传任务表';

CREATE INDEX idx_batch_task_user ON batch_task (user_id);

CREATE TABLE IF NOT EXISTS batch_file_result
(
    id               bigint auto_increment comment '批量结果ID' primary key,
    task_id          varchar(64)  not null comment '批量任务业务键',
    file_name        varchar(255) not null comment '文件名',
    is_success       tinyint      not null default 0 comment '是否成功 0=否 1=是',
    error_message    varchar(1000) null comment '失败原因',
    document_id      varchar(64)  null comment '文档业务键',
    uploaded_file_id bigint       null comment '上传文件ID',
    segment_count    int          not null default 0 comment '分块数',
    status           varchar(30)  not null default 'FAILED' comment 'COMPLETED/FAILED',
    storage_key      varchar(500) null comment '对象存储Key',
    create_time      datetime     not null default current_timestamp comment '创建时间',
    update_time      datetime     not null default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted       tinyint      not null default 0 comment '是否删除 0=否 1=是'
) comment '批量上传文件结果表';

CREATE INDEX idx_batch_file_result_task ON batch_file_result (task_id);
