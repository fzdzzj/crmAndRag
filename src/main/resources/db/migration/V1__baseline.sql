-- ============================================================
-- V1__baseline.sql —— CRM 现有表基线（Agent-0 · Wave 0，任务 4）
--
-- 目的：
--   以 CRM 源仓 script.sql 为准，固化现有 36 张业务表（sys_user/sys_role/sys_dept/客户/商机/合同/…），
--   作为融合平台的库结构唯一真相源（D2：MyBatis-Plus + Flyway，禁 JPA/ddl-auto）。
--
-- 影响表：
--   共 36 张：31 张来自 CRM script.sql，另 5 张（assist_message/assist_request/project_file/tage/user_handover）
--   因 script.sql 未同步而按实体注解（@Column type/notNull/defaultValue + @TableIndex）补齐 DDL。
--
-- 边界：
--   * 知识库 7 表（knowledge_base/knowledge_base_member/uploaded_file/document_vector_chunk/
--     chunk_upload_session/batch_task/batch_file_result）由 Lane B 在 V3x 新建，不在本脚本；
--   * RAG 独立对话层 chat_conversation/chat_message/teacher_account 已随 D11 丢弃，不做迁移；
--   * 本脚本不含任何 INSERT 数据（权限/管理员初始化由应用启动期 PermissionSyncRunner/DataInit 完成）。
--
-- 回滚注意：
--   本脚本是“基线”，不提供 DROP 回滚；如需回退请使用 DB 快照恢复，
--   破坏性变更（删列/改列类型）后续只能出现在新版本脚本中并显式标注。
-- ============================================================

CREATE TABLE IF NOT EXISTS approval_attachment
(
    id          bigint auto_increment comment '附件ID'
        primary key,
    and_id      bigint                                    not null comment '关联表ID',
    file_name   varchar(200)                              not null comment '文件名称（原始文件名）',
    file_path   varchar(500)                              not null comment '文件存储路径（服务器存储地址）',
    file_size   bigint                                    not null comment '文件大小（单位：字节）',
    file_type   varchar(100)                              null comment '文件类型（如：image/png、application/pdf）',
    upload_time datetime    default CURRENT_TIMESTAMP     not null comment '上传时间',
    model_name  varchar(20) default 'approval_attachment' not null comment '模块名'
)
    comment '审批附件表';


create index fk_attachment_approval
    on approval_attachment (and_id);


CREATE TABLE IF NOT EXISTS permissions
(
    id               bigint auto_increment comment '权限ID，唯一标识'
        primary key,
    permissions_name varchar(50) not null comment '权限名字',
    permissions_desc varchar(50) null comment '权限描述'
)
    comment '权限表';


CREATE TABLE IF NOT EXISTS sys_role
(
    id          bigint auto_increment comment '角色ID'
        primary key,
    role_name   varchar(50)                        not null comment '角色名称（如：销售经理、管理员）',
    role_desc   varchar(200)                       null comment '角色描述（权限说明）',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    is_deleted  bit      default b'0'              not null,
    constraint uk_role_name
        unique (role_name)
)
    comment '角色表';


CREATE TABLE IF NOT EXISTS sys_user
(
    id          bigint auto_increment comment '用户ID'
        primary key,
    password    varchar(100)                       not null comment '加密密码（存储加密后的密码）',
    real_name   varchar(50)                        not null comment '真实姓名',
    phone       varchar(20)                        null comment '联系电话',
    email       varchar(100)                       null comment '邮箱',
    dept_id     bigint                             null comment '所属部门ID',
    role_id     bigint                             not null comment '角色ID（控制权限）',
    status      tinyint  default 1                 not null comment '状态（1-正常，0-冻结，2-离职，3-眼不见心不烦）',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime                           null on update CURRENT_TIMESTAMP comment '最后更新时间',
    creator_id  bigint                             null comment '创建者ID',
    constraint uk_email
        unique (email),
    constraint uk_phone
        unique (phone),
    constraint fk_user_creator
        foreign key (creator_id) references sys_user (id),
    constraint fk_user_role
        foreign key (role_id) references sys_role (id)
)
    comment '系统用户表';


CREATE TABLE IF NOT EXISTS customer_company
(
    id           bigint auto_increment comment '客户唯一标识 ID'
        primary key,
    company_name varchar(100)                       not null comment '公司名称（客户主体名称）',
    industry     varchar(50)                        null comment '所属行业（用于客户分类）',
    status       varchar(20)                        null comment '客户状态（用于标识客户当前阶段）',
    address      varchar(200)                       null comment '公司地址',
    phone        varchar(20)                        null comment '公司固定电话',
    website      varchar(100)                       null comment '公司官方网站',
    description  text                               null comment '公司描述（补充说明信息）',
    creator_id   bigint                             not null comment '创建人 ID（记录数据创建者）',
    owner_id     bigint                             null comment '负责人 ID（当前跟进的销售人员）',
    is_deleted   tinyint  default 0                 not null comment '是否删除（0-正常，1-回收站）',
    create_time  datetime default CURRENT_TIMESTAMP not null comment '记录创建时间',
    update_time  datetime                           null on update CURRENT_TIMESTAMP comment '记录最后更新时间',
    grade        int      default 0                 not null comment '等级 0 最低（0-9，0 最低，9 最高，只有管理员可以设置）',
    customer_type varchar(20)                       null comment '客户属性（代理/直销）',
    belong_group varchar(50)                        null comment '归属集团',
    dept         varchar(50)                        null comment '部门',
    dept_unique_key varchar(160)                    generated always as (if(is_deleted = 0, concat(company_name, '|', coalesce(dept, '')), null)) stored comment '未删除记录的 公司名|部门 唯一键（软删后自动失效）',
    constraint fk_company_creator
        foreign key (creator_id) references sys_user (id),
    constraint fk_company_owner
        foreign key (owner_id) references sys_user (id),
    constraint uk_company_name_dept
        unique (dept_unique_key)
)
    comment '客户公司表';


create index idx_industry_status
    on customer_company (industry, status);


create index idx_owner
    on customer_company (owner_id);


CREATE TABLE IF NOT EXISTS customer_company_log
(
    id          bigint auto_increment
        primary key,
    operation   varchar(20)              null comment '操作（增删改查）',
    creator_id  bigint                   not null,
    create_time datetime default (now()) not null,
    `sql`       text                     not null,
    form        varchar(20)              not null,
    constraint customer_company_log_sys_user_id_fk
        foreign key (creator_id) references sys_user (id)
)
    comment '日志';


CREATE TABLE IF NOT EXISTS customer_contact
(
    id            bigint auto_increment comment '联系人唯一标识 ID'
        primary key,
    company_id    bigint                             not null comment '所属公司 ID（多对一关联客户公司）',
    name          varchar(50)                        not null comment '联系人姓名',
    position      varchar(50)                        null comment '职位（在公司中的职务）',
    phone         varchar(20)                        null comment '固定电话',
    mobile        varchar(20)                        not null comment '手机号码（核心联系方式）',
    email         varchar(100)                       null comment '电子邮箱',
    gender        tinyint                            null comment '性别（1-男，2-女）',
    dept          varchar(50)                        null comment '部门',
    relation_level tinyint                           null comment '客户关系等级（1-9，9 为最紧密）',
    creator_id    bigint                             not null comment '创建人ID',
    is_deleted    tinyint  default 0                 not null comment '是否删除（0-正常，1-回收站）',
    create_time   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   datetime                           null on update CURRENT_TIMESTAMP comment '最后更新时间',
    constraint idx_mobile
        unique (mobile),
    constraint fk_contact_company
        foreign key (company_id) references customer_company (id),
    constraint fk_contact_creator
        foreign key (creator_id) references sys_user (id)
)
    comment '客户联系人表';


create index idx_company
    on customer_contact (company_id);


CREATE TABLE IF NOT EXISTS customer_contact_remark
(
    id             bigint auto_increment comment '备注 ID'
        primary key,
    contact_id     bigint                                    not null comment '联系人 ID',
    remark_type    tinyint                                   not null comment '备注类型（1-喜好，2-住址，3-本人出生日期，4-亲属出生日期，5-自定义）',
    remark_content varchar(500)                              null comment '备注内容（喜好、住址、自定义类型可填）',
    remark_name    varchar(50)                               null comment '备注姓名',
    remark_date    date                                      null comment '备注出生日期',
    creator_id     bigint                                    not null comment '创建人ID',
    create_time    datetime    default CURRENT_TIMESTAMP     not null comment '创建时间',
    update_time    datetime                                  null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint fk_remark_contact
        foreign key (contact_id) references customer_contact (id) on delete cascade,
    constraint fk_remark_creator
        foreign key (creator_id) references sys_user (id)
)
    comment '客户联系人备注表';


create index idx_contact_id
    on customer_contact_remark (contact_id);


create index idx_remark_type
    on customer_contact_remark (remark_type);


CREATE TABLE IF NOT EXISTS customer_merge_log
(
    id                bigint auto_increment comment '合并记录ID'
        primary key,
    target_company_id bigint                             not null comment '目标客户ID（合并后保留的客户）',
    merged_company_id bigint                             not null comment '被合并客户ID（合并后失效的客户）',
    operator_id       bigint                             not null comment '操作人ID（执行合并的用户）',
    merge_time        datetime default CURRENT_TIMESTAMP not null comment '合并操作时间',
    remark            varchar(500)                       null comment '合并说明（如：合并原因、处理方式）',
    constraint customer_merge_log_customer_company_id_fk
        foreign key (merged_company_id) references customer_company (id),
    constraint customer_merge_log_customer_company_id_fk_2
        foreign key (target_company_id) references customer_company (id),
    constraint fk_merge_operator
        foreign key (operator_id) references sys_user (id)
)
    comment '客户合并历史表';


create index idx_target
    on customer_merge_log (target_company_id);


CREATE TABLE IF NOT EXISTS data_share
(
    id            bigint auto_increment comment '共享记录ID'
        primary key,
    resource_type tinyint                            not null comment '资源类型（1-客户公司，2-联系人，3-销售机会，4-订单，5-合同，6-业务活动，7-联络任务，8-销售阶段变更审批）',
    resource_id   bigint                             not null comment '资源ID（对应类型的记录ID）',
    share_from    bigint                             not null comment '共享人ID',
    share_to      bigint                             not null comment '被共享人/角色ID',
    share_type    tinyint                            not null comment '共享类型（1-用户，2-角色）',
    expire_time   datetime                           null comment '过期时间（NULL表示永久）',
    create_time   datetime default CURRENT_TIMESTAMP not null comment '共享时间',
    is_reclaim    int                                null comment '是否回收（0--正常，1--权限已回收）',
    constraint uk_share_unique
        unique (resource_id, resource_type, share_to, share_type),
    constraint fk_share_from
        foreign key (share_from) references sys_user (id)
)
    comment '数据共享表';


CREATE TABLE IF NOT EXISTS role_permissions
(
    id             bigint auto_increment comment '角色权限ID'
        primary key,
    permissions_id bigint           not null comment '权限ID',
    role_id        bigint           not null comment '角色ID',
    creator_id     bigint           null comment '创建者ID',
    is_deleted     bit default b'0' not null,
    constraint uk_role_permission
        unique (permissions_id, role_id),
    constraint fk_rp_creator
        foreign key (creator_id) references sys_user (id),
    constraint fk_rp_permission
        foreign key (permissions_id) references permissions (id),
    constraint fk_rp_role
        foreign key (role_id) references sys_role (id)
)
    comment '角色权限表';


CREATE TABLE IF NOT EXISTS sales_opportunity
(
    id                  bigint auto_increment comment '销售机会ID'
        primary key,
    opportunity_name    varchar(100)                       not null comment '机会名称（如：XX公司年度服务采购）',
    company_id          bigint                             not null comment '关联客户公司ID',
    contact_id          bigint                             null comment '主要联系人ID（核心对接人）',
    stage               int                                not null comment '当前阶段（0种子/1潜在商机/2确认商机/3储备项目/4立项签约/5关闭）',
    amount              decimal(15, 2)                     null comment '预计金额（预估成交金额）',
    expected_close_date datetime                           null comment '预计成交日期',
    source              varchar(50)                        null comment '机会来源',
    description         text                               null comment '机会描述（如：客户需求、跟进要点）',
    owner_id            bigint                             not null comment '负责人ID（跟进销售）',
    creator_id          bigint                             not null comment '创建人ID',
    create_time         datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time         datetime                           null on update CURRENT_TIMESTAMP comment '最后更新时间',
    approver_id         bigint                             not null comment '审批人ID',
    is_deleted          int      default 0                 not null comment '是否删除',
    constraint fk_opportunity_company
        foreign key (company_id) references customer_company (id),
    constraint fk_opportunity_contact
        foreign key (contact_id) references customer_contact (id),
    constraint fk_opportunity_creator
        foreign key (creator_id) references sys_user (id),
    constraint fk_opportunity_owner
        foreign key (owner_id) references sys_user (id),
    constraint sales_opportunity_sys_user_id_fk
        foreign key (approver_id) references sys_user (id)
)
    comment '销售机会表';


CREATE TABLE IF NOT EXISTS business_activity
(
    id                bigint auto_increment comment '商业活动ID，唯一标识'
        primary key,
    activity_title    varchar(200)                       not null comment '活动标题（如：XX公司产品演示会、客户需求沟通会）',
    activity_type     varchar(50)                        not null comment '活动类型（电话沟通/邮件往来/线下拜访/线上会议/产品演示等）',
    activity_content  text                               null comment '活动内容（详细描述：沟通要点、需求反馈、演示内容等）',
    activity_time     datetime                           not null comment '活动发生时间',
    activity_duration int                                null comment '活动时长（单位：分钟，无时长则为NULL）',
    company_id        bigint                             null comment '关联客户公司ID（可选，若活动绑定具体客户公司）',
    opportunity_id    bigint                             null comment '关联销售机会ID（可选，若活动为跟进特定商机）',
    creator_id        bigint                             not null comment '活动创建人ID（通常为活动发起人）',
    create_time       datetime default CURRENT_TIMESTAMP not null comment '记录创建时间',
    remark            varchar(500)                       null comment '备注（如：活动效果、后续待办等）',
    task_id           int                                null comment '关联联络任务ID',
    constraint fk_activity_company
        foreign key (company_id) references customer_company (id),
    constraint fk_activity_opportunity
        foreign key (opportunity_id) references sales_opportunity (id)
)
    comment '商业活动主表（存储活动核心信息）';


create index idx_activity_company
    on business_activity (company_id);


create index idx_activity_creator
    on business_activity (creator_id);


CREATE TABLE IF NOT EXISTS business_activity_contact
(
    id           bigint auto_increment comment '关联记录ID，唯一标识'
        primary key,
    activity_id  bigint                             not null comment '关联商业活动ID',
    contact_id   bigint                             not null comment '关联客户联系人ID（活动涉及的外部联系人）',
    contact_role varchar(50)                        null comment '联系人在活动中的角色（如：决策者/技术对接人/参会人）',
    creator_id   bigint                             not null comment '关联记录创建人ID',
    create_time  datetime default CURRENT_TIMESTAMP not null comment '记录创建时间',
    constraint uk_activity_contact_unique
        unique (activity_id, contact_id),
    constraint fk_act_contact_activity
        foreign key (activity_id) references business_activity (id)
            on delete cascade,
    constraint fk_act_contact_contact
        foreign key (contact_id) references customer_contact (id)
)
    comment '商业活动-联系人关联表（记录活动涉及的外部联系人）';


create index fk_act_contact_creator
    on business_activity_contact (creator_id);


create index idx_act_contact_activity
    on business_activity_contact (activity_id);


create index idx_act_contact_contact
    on business_activity_contact (contact_id);


CREATE TABLE IF NOT EXISTS business_activity_user
(
    id          bigint auto_increment comment '关联记录ID，唯一标识'
        primary key,
    activity_id bigint                             not null comment '关联商业活动ID',
    user_id     bigint                             not null comment '关联公司员工ID（活动参与的内部员工）',
    user_role   varchar(50)                        null comment '员工在活动中的角色（如：主持人/主讲人/记录人/陪同人）',
    creator_id  bigint                             not null comment '关联记录创建人ID',
    create_time datetime default CURRENT_TIMESTAMP not null comment '记录创建时间',
    constraint uk_activity_user_unique
        unique (activity_id, user_id),
    constraint fk_act_user_activity
        foreign key (activity_id) references business_activity (id)
            on delete cascade
)
    comment '商业活动-员工关联表（记录活动参与的内部员工）';


create index fk_act_user_creator
    on business_activity_user (creator_id);


create index idx_act_user_activity
    on business_activity_user (activity_id);


create index idx_act_user_user
    on business_activity_user (user_id);


CREATE TABLE IF NOT EXISTS contact_task
(
    id             bigint auto_increment comment '任务ID'
        primary key,
    task_title     varchar(200)                       not null comment '任务标题',
    company_id     bigint                             null comment '关联客户公司ID（可选）',
    contact_id     bigint                             null comment '关联联系人ID（可选）',
    opportunity_id bigint                             null comment '关联销售机会ID（可选）',
    task_type      varchar(50)                        not null comment '任务类型（电话/邮件/拜访等）',
    task_content   text                               null comment '任务内容（详细描述）',
    start_time     datetime                           null comment '开始时间',
    end_time       datetime                           null comment '结束时间',
    priority       int                                not null comment '优先级（0-9低到高）',
    status         int      default 0                 not null comment '状态（0未开始/1进行中/2已完成/3已取消）',
    assigner_id    bigint                             null comment '任务分配人ID',
    assignee_id    bigint                             not null comment '任务执行人ID',
    creator_id     bigint                             not null comment '创建人ID',
    create_time    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time    datetime                           null on update CURRENT_TIMESTAMP comment '最后更新时间',
    constraint fk_task_company
        foreign key (company_id) references customer_company (id),
    constraint fk_task_contact
        foreign key (contact_id) references customer_contact (id),
    constraint fk_task_opportunity
        foreign key (opportunity_id) references sales_opportunity (id)
)
    comment '联络任务表';


create index fk_task_assigner
    on contact_task (assigner_id);


create index fk_task_creator
    on contact_task (creator_id);


create index idx_task_assignee
    on contact_task (assignee_id);


create index idx_task_status
    on contact_task (status);


CREATE TABLE IF NOT EXISTS contract
(
    id              bigint auto_increment comment '合同ID'
        primary key,
    contract_no     varchar(50)                        not null comment '合同编号（系统自动生成或手动录入，唯一）',
    opportunity_id  bigint                             null comment '关联销售机会ID（来源商机）',
    company_id      bigint                             not null comment '客户公司ID',
    contract_name   varchar(200)                       not null comment '合同名称',
    total_amount    decimal(15, 2)                     not null comment '合同总金额',
    sign_date       datetime                           not null comment '签约日期',
    start_date      datetime                           null comment '合同开始日期',
    end_date        datetime                           null comment '合同结束日期',
    contract_status int                                not null comment '合同状态（0预签约/1已生效/2已终止/3已完成/4已弃用）',
    owner_id        bigint                             not null comment '负责人ID',
    creator_id      bigint                             not null comment '创建人ID',
    create_time     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time     datetime                           null on update CURRENT_TIMESTAMP comment '最后更新时间',
    constraint uk_contract_no
        unique (contract_no),
    constraint fk_contract_company
        foreign key (company_id) references customer_company (id),
    constraint fk_contract_opportunity
        foreign key (opportunity_id) references sales_opportunity (id)
)
    comment '合同表';


create index fk_contract_creator
    on contract (creator_id);


create index fk_contract_owner
    on contract (owner_id);


create index idx_contract_company
    on contract (company_id);


create index idx_contract_status
    on contract (contract_status);


CREATE TABLE IF NOT EXISTS contract_order_item
(
    id           bigint auto_increment comment '明细ID'
        primary key,
    contract_id  bigint         not null comment '关联合同ID',
    product_name varchar(100)   not null comment '产品/ 服务名称',
    quantity     decimal(10, 2) not null comment '数量',
    unit_price   decimal(15, 2) not null comment '单价',
    amount       decimal(15, 2) not null comment '金额（数量×单价）',
    remark       varchar(500)   null comment '备注（如：规格、服务周期等）',
    is_deleted   int default 0  not null comment '是否删除',
    constraint contract_order_item_pk
        unique (product_name, remark, quantity, unit_price, contract_id),
    constraint fk_orderitem_contract
        foreign key (contract_id) references contract (id)
)
    comment '合同订单明细表';


CREATE TABLE IF NOT EXISTS payment_record
(
    id             bigint auto_increment comment '回款ID'
        primary key,
    contract_id    bigint                             not null comment '关联合同ID',
    order_item_id  bigint                             null comment '关联订单明细ID（精确到具体产品）',
    payment_no     varchar(50)                        not null comment '回款单号（唯一标识）',
    payment_amount decimal(15, 2)                     not null comment '回款金额',
    payment_date   datetime                           not null comment '回款日期',
    payment_method varchar(50)                        null comment '回款方式（如：银行转账、支票等）',
    payment_status int                                not null comment '回款状态（0已确认/1待确认）',
    remark         varchar(500)                       null comment '备注（如：付款方信息、到账说明）',
    creator_id     bigint                             not null comment '创建人ID',
    create_time    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    constraint uk_payment_no
        unique (payment_no),
    constraint fk_payment_contract
        foreign key (contract_id) references contract (id),
    constraint fk_payment_creator
        foreign key (creator_id) references sys_user (id),
    constraint fk_payment_orderitem
        foreign key (order_item_id) references contract_order_item (id)
)
    comment '回款记录表';


CREATE TABLE IF NOT EXISTS invoice_info
(
    id             bigint auto_increment comment '开票ID'
        primary key,
    contract_id    bigint                             not null comment '关联合同ID',
    payment_id     bigint                             null comment '关联回款ID（关联具体回款）',
    invoice_no     varchar(50)                        not null comment '发票编号',
    invoice_amount decimal(15, 2)                     not null comment '开票金额',
    invoice_date   datetime                           not null comment '开票日期',
    invoice_type   varchar(50)                        null comment '发票类型（如：增值税专用发票、普通发票）',
    status         int                                not null comment '状态（0已开具/1已作废）',
    creator_id     bigint                             not null comment '创建人ID',
    create_time    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    constraint uk_invoice_no
        unique (invoice_no),
    constraint fk_invoice_contract
        foreign key (contract_id) references contract (id),
    constraint fk_invoice_creator
        foreign key (creator_id) references sys_user (id),
    constraint fk_invoice_payment
        foreign key (payment_id) references payment_record (id)
)
    comment '开票信息表';


create index idx_opportunity_owner
    on sales_opportunity (owner_id);


create index idx_opportunity_stage
    on sales_opportunity (stage);


CREATE TABLE IF NOT EXISTS sales_stage_approval
(
    id               bigint auto_increment comment '审批记录ID'
        primary key,
    opportunity_id   bigint                             not null comment '关联销售机会ID',
    current_stage    varchar(30)                        not null comment '当前阶段（变更前的阶段）',
    target_stage     varchar(30)                        not null comment '目标阶段（申请变更到的阶段）',
    applicant_id     bigint                             not null comment '申请人ID（发起阶段变更的销售）',
    approver_id      bigint                             not null comment '审批人ID（上级或指定审批人）',
    approval_status  int                                not null comment '审批状态（0待审批/1同意/2拒绝/3退回修改）',
    approval_triggered tinyint(1) default 1              not null comment '是否已正式提交审批',
    approval_opinion text                               null comment '审批意见（审批人的反馈）',
    apply_time       datetime default CURRENT_TIMESTAMP not null comment '申请时间',
    approval_time    datetime                           null comment '审批完成时间',
    message          varchar(500)                       null comment '审批备注',
    version          int      default 0                 not null comment '乐观锁版本号（草稿并发防覆盖）',
    constraint fk_approval_applicant
        foreign key (applicant_id) references sys_user (id),
    constraint fk_approval_opportunity
        foreign key (approver_id) references sys_user (id),
    constraint sales_stage_approval_sales_opportunity_id_fk
        foreign key (opportunity_id) references sales_opportunity (id)
)
    comment '销售阶段变更审批表';


create index idx_approval_opportunity
    on sales_stage_approval (opportunity_id);


create index idx_approval_status
    on sales_stage_approval (approval_status);


CREATE TABLE IF NOT EXISTS task_comment
(
    id          bigint auto_increment comment '评论ID'
        primary key,
    task_id     bigint                             not null comment '关联任务ID',
    content     text                               not null comment '评论内容',
    creator_id  bigint                             not null comment '评论人ID',
    create_time datetime default CURRENT_TIMESTAMP not null comment '评论时间',
    constraint fk_comment_creator
        foreign key (creator_id) references sys_user (id),
    constraint fk_comment_task
        foreign key (task_id) references contact_task (id)
)
    comment '任务评论表';


-- 三级数据权限系统相关表
-- 标签资源绑定表
CREATE TABLE IF NOT EXISTS tage_resource_binding
(
    id           bigint auto_increment comment '绑定ID'
        primary key,
    tage_id      bigint   not null comment '标签ID',
    resource_type tinyint  not null comment '资源类型(1-客户公司,2-联系人,3-销售机会,4-订单,5-合同,6-业务活动,7-联络任务,8-销售阶段变更审批)',
    resource_id  bigint   not null comment '资源ID(对应类型的记录ID)',
    create_time  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    creator_id   bigint   not null comment '创建人ID',
    constraint uk_tage_resource
        unique (tage_id, resource_type, resource_id)
)
    comment '标签资源绑定表';


create index idx_tage_resource
    on tage_resource_binding (resource_type, resource_id);


-- 标签角��绑定表
CREATE TABLE IF NOT EXISTS tage_role_binding
(
    id          bigint auto_increment comment '绑定ID'
        primary key,
    tage_id     bigint not null comment '标签ID',
    role_id     bigint not null comment '角色ID',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    creator_id  bigint not null comment '创建人ID',
    constraint uk_tage_role
        unique (tage_id, role_id)
)
    comment '标签角色绑定表';


create index idx_tage_role
    on tage_role_binding (role_id);


-- 集团主数据表（客户归属集团下拉/搜索来源）
CREATE TABLE IF NOT EXISTS company_group
(
    id          bigint auto_increment comment '集团ID'
        primary key,
    group_name  varchar(50)                          not null comment '集团名称',
    status      tinyint    default 1                 not null comment '状态（1-启用，0-停用）',
    create_time datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime                             null comment '更新时间',
    constraint uk_company_group_name
        unique (group_name)
)
    comment '集团主数据表';


-- 部门主数据表（部门归属集团，客户表单联动下拉来源）
CREATE TABLE IF NOT EXISTS company_dept
(
    id          bigint auto_increment comment '部门ID'
        primary key,
    group_id    bigint                               not null comment '所属集团ID',
    dept_name   varchar(50)                          not null comment '部门名称',
    status      tinyint    default 1                 not null comment '状态（1-启用，0-停用）',
    create_time datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime                             null comment '更新时间',
    constraint uk_company_group_dept
        unique (group_id, dept_name),
    constraint fk_company_dept_group
        foreign key (group_id) references company_group (id)
)
    comment '部门主数据表';


create index idx_company_dept_group
    on company_dept (group_id);




CREATE TABLE IF NOT EXISTS sys_dept
(
    id          bigint auto_increment comment '����ID'
        primary key,
    dept_name   varchar(50)                           not null comment '��������',
    parent_id   bigint                                null comment '�ϼ�����ID',
    sort        int        default 0                  null comment '����',
    status      tinyint    default 1                  not null comment '״̬��1-����/0-ͣ�ã�',
    create_time datetime   default CURRENT_TIMESTAMP  not null comment '����ʱ��',
    update_time datetime                              null comment '����ʱ��',
    constraint chk_sys_dept_status check (status in (0, 1))
)
    comment 'ϵͳ���ű�';


create index idx_sys_dept_parent
    on sys_dept (parent_id);


-- ==================== AI 助手模块 ====================
CREATE TABLE IF NOT EXISTS ai_session
(
    id           bigint auto_increment comment '会话ID'
        primary key,
    user_id      bigint       not null comment '所属用户',
    title        varchar(100) default '新对话' null comment '会话标题',
    status       tinyint      default 1 null comment '1=活跃 0=归档',
    created_time datetime     not null comment '创建时间',
    updated_time datetime     not null comment '最后活跃时间'
)
    comment 'AI会话';


create index idx_user_time
    on ai_session (user_id, updated_time);


CREATE TABLE IF NOT EXISTS ai_message
(
    id           bigint auto_increment
        primary key,
    session_id   bigint       not null comment '所属会话',
    role         varchar(20)  not null comment '角色 user/assistant/tool',
    msg_type     varchar(20)  default 'text' null comment '消息类型 text/chart/actionCard/draftProgress/system',
    content      text         null comment '文本内容',
    payload      text         null comment '附加结构化JSON',
    token_count  int          default 0 null comment 'token估算',
    created_time datetime     not null comment '创建时间'
)
    comment 'AI消息';


create index idx_session
    on ai_message (session_id, id);


CREATE TABLE IF NOT EXISTS ai_pending_action
(
    id             bigint auto_increment
        primary key,
    pending_id     varchar(32)  not null comment '对外标识',
    session_id     bigint       not null comment '来源会话',
    user_id        bigint       not null comment '操作归属用户',
    action_type    varchar(30)  not null comment '操作类型 CREATE_ORDER等',
    status         varchar(20)  not null comment 'DRAFTING/PENDING/CONFIRMED/CANCELLED/EXPIRED/FAILED',
    payload        text         not null comment '执行参数JSON',
    missing_fields text         null comment '缺失字段JSON数组',
    ask_round      int          default 0 null comment '已追问轮数',
    preview        text         null comment '确认卡片展示摘要JSON',
    created_time   datetime     not null comment '创建时间',
    updated_time   datetime     not null comment '更新时间',
    confirmed_time datetime     null comment '确认/取消时间',
    expire_time    datetime     not null comment 'PENDING超时时间',
    result         text         null comment '执行结果JSON',
    constraint uk_pending
        unique (pending_id)
)
    comment 'AI待确认操作';


create index idx_user_status
    on ai_pending_action (user_id, status);


create index idx_session
    on ai_pending_action (session_id);


CREATE TABLE IF NOT EXISTS ai_tool_call_log
(
    id           bigint auto_increment
        primary key,
    session_id   bigint      null comment '会话ID',
    user_id      bigint      not null comment '用户',
    tool_name    varchar(50) not null comment '工具名',
    args         text        null comment '调用参数JSON',
    result       text        null comment '返回结果（脱敏后）',
    success      tinyint     default 1 null comment '是否成功',
    cost_ms      int         null comment '耗时（毫秒）',
    created_time datetime    not null comment '调用时间'
)
    comment 'AI工具调用审计';


create index idx_user_time
    on ai_tool_call_log (user_id, created_time);


-- ------------------------------------------------------------
-- 以下 5 张表在 CRM script.sql 中缺失，DDL 按实体注解补齐（字段类型/可空/默认值/索引一一对应）
-- ------------------------------------------------------------

-- 协助申请过程消息
CREATE TABLE IF NOT EXISTS assist_message
(
    id           bigint auto_increment comment '消息ID' primary key,
    assist_id    bigint not null comment '协助记录ID',
    sender_id    bigint null comment '发送人ID；系统消息为空',
    content      text not null comment '消息内容',
    message_type varchar(16) not null comment '消息类型（TEXT/SYSTEM）',
    create_time  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    index idx_assist_message_time (assist_id, create_time)
) comment '协助申请过程消息';

-- 协助申请记录表（跨模块通用：销售阶段推进审批、商业活动等）
CREATE TABLE IF NOT EXISTS assist_request
(
    id                   bigint auto_increment comment '协助记录ID' primary key,
    model_name           varchar(50) not null comment '模块名称（sales_stage_approval/business_activity）',
    record_id            bigint not null comment '关联业务记录ID（审批记录ID/活动ID）',
    applicant_id         bigint not null comment '申请人ID',
    apply_purpose        varchar(200) null comment '协作目的（申请人填）',
    apply_requirement    text null comment '协作要求（申请人填，具体要对方做什么）',
    assist_user_id       bigint not null comment '协助人ID',
    assist_status        int default 0 not null comment '协助状态（0待协助/1已协助/2已驳回/3已拒绝/4已取消）',
    assist_content       text null comment '协助内容/成果（协助人交付）',
    reject_reason        text null comment '驳回/拒绝理由（协助人填）',
    cancel_reason        text null comment '业务终结取消原因',
    parent_id            bigint null comment '重新申请来源记录ID（首次为空；驳回后重新申请指向原记录）',
    pending_key          varchar(16) null comment '待协助唯一键（仅待协助写入 PENDING，终态清空）',
    opportunity_snapshot text null comment '商机详情快照（历史兼容列；新流程不再写入或读取）',
    record_snapshot      text null comment '协助终态业务快照（JSON）',
    assist_time          datetime null comment '协助处理时间',
    create_time          datetime default CURRENT_TIMESTAMP not null comment '记录创建时间',
    index idx_assist_model_record (model_name, record_id),
    index idx_assist_user_status (assist_user_id, assist_status),
    index idx_assist_applicant (applicant_id, create_time),
    unique index uk_assist_pending (model_name, record_id, assist_user_id, pending_key)
) comment '协助申请记录表';

-- 项目文件表
CREATE TABLE IF NOT EXISTS project_file
(
    id             bigint auto_increment comment '主键ID' primary key,
    file_name      varchar(200) not null comment '文件名',
    file_path      varchar(500) not null comment '文件存储路径',
    file_type      varchar(100) null comment '文件类型(如:image/png、application/pdf)',
    file_size      bigint not null comment '文件大小（字节）',
    category       varchar(30) not null comment '分类(VISIT_RECORD/MEETING_MINUTES/PROPOSAL/BID_DOCUMENT/PROJECT_CONTRACT)',
    upload_time    datetime default CURRENT_TIMESTAMP not null comment '上传时间',
    uploader_id    bigint not null comment '上传人员ID',
    theme          varchar(200) null comment '主题',
    description    text null comment '说明',
    activity_id    bigint null comment '业务活动ID(可选)',
    opportunity_id bigint null comment '销售机会ID(可选)',
    contract_id    bigint null comment '合同ID(可选)',
    order_id       bigint null comment '订单项ID(可选,仅contract_id有值时使用)',
    index idx_file_category (category),
    index idx_file_activity (activity_id),
    index idx_file_opportunity (opportunity_id),
    index idx_file_contract (contract_id),
    index idx_file_order (order_id),
    index idx_file_uploader (uploader_id)
) comment '项目文件表';

-- 标签表
CREATE TABLE IF NOT EXISTS tage
(
    id          bigint auto_increment comment '标签ID' primary key,
    tage_name   varchar(20) not null comment '标签名字',
    creator_id  bigint null comment '创建者ID',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间'
) comment '标签表';

-- 用户交接记录表（记录用户离职时的数据交接历史）
CREATE TABLE IF NOT EXISTS user_handover
(
    id                bigint auto_increment comment '交接记录ID' primary key,
    from_user_id      bigint not null comment '原用户ID（离职用户）',
    to_user_id        bigint not null comment '交接用户ID（接收人）',
    task_count        int default 0 null comment '交接任务数量',
    customer_count    int default 0 null comment '交接客户数量',
    opportunity_count int default 0 null comment '交接销售机会数量',
    handover_time     datetime default CURRENT_TIMESTAMP not null comment '交接时间',
    operator_id       bigint not null comment '操作人ID',
    remark            varchar(500) null comment '交接备注',
    index idx_from_user (from_user_id),
    index idx_to_user (to_user_id),
    index idx_handover_time (handover_time)
) comment '用户交接记录表';
