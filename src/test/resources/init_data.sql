-- 角色表
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_name` varchar(255) DEFAULT NULL,
  `role_desc` varchar(255) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `is_deleted` bit NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`)
);

-- 权限表
CREATE TABLE IF NOT EXISTS `permissions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `permissions_name` varchar(255) DEFAULT NULL,
  `permissions_desc` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
);

-- 角色-权限关联表
CREATE TABLE IF NOT EXISTS `role_permissions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint DEFAULT NULL,
  `permissions_id` bigint DEFAULT NULL,
  `creator_id` bigint DEFAULT NULL,
  `is_deleted` bit NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`)
);

-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `password` varchar(255) DEFAULT NULL,
  `real_name` varchar(255) DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `dept_id` bigint DEFAULT NULL,
  `role_id` bigint DEFAULT NULL,
  `status` tinyint DEFAULT NULL,
  `creator_id` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`)
);

-- 客户公司表
CREATE TABLE IF NOT EXISTS `customer_company` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `company_name` varchar(100) NOT NULL,
  `industry` varchar(50) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  `address` varchar(200) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `website` varchar(100) DEFAULT NULL,
  `description` text,
  `creator_id` bigint NOT NULL,
  `owner_id` bigint DEFAULT NULL,
  `is_deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  `grade` int NOT NULL DEFAULT 0,
  `customer_type` varchar(20) DEFAULT NULL,
  `belong_group` varchar(50) DEFAULT NULL,
  `dept` varchar(50) DEFAULT NULL,
  `dept_unique_key` varchar(160) GENERATED ALWAYS AS (IF(`is_deleted` = 0, CONCAT(`company_name`, '|', COALESCE(`dept`, '')), NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_company_name_dept` (`dept_unique_key`)
);

-- 联络任务表
CREATE TABLE IF NOT EXISTS `contact_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_title` varchar(200) NOT NULL,
  `company_id` bigint DEFAULT NULL,
  `contact_id` bigint DEFAULT NULL,
  `opportunity_id` bigint DEFAULT NULL,
  `task_type` varchar(50) NOT NULL,
  `task_content` text,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `priority` int NOT NULL,
  `status` int NOT NULL DEFAULT 0,
  `assignee_id` bigint NOT NULL,
  `assigner_id` bigint DEFAULT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
);

-- 销售机会表
CREATE TABLE IF NOT EXISTS `sales_opportunity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `opportunity_name` varchar(100) NOT NULL,
  `company_id` bigint NOT NULL,
  `contact_id` bigint DEFAULT NULL,
  `stage` int NOT NULL DEFAULT 0,
  `amount` decimal(15,2) DEFAULT NULL,
  `expected_close_date` datetime DEFAULT NULL,
  `source` varchar(50) DEFAULT NULL,
  `description` text,
  `owner_id` bigint NOT NULL,
  `approver_id` bigint NOT NULL,
  `creator_id` bigint NOT NULL,
  `is_deleted` bit NOT NULL DEFAULT b'0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
);

-- 合同表
CREATE TABLE IF NOT EXISTS `contract` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `contract_no` varchar(50) NOT NULL,
  `opportunity_id` bigint DEFAULT NULL,
  `company_id` bigint NOT NULL,
  `contract_name` varchar(200) NOT NULL,
  `total_amount` decimal(15,2) NOT NULL,
  `sign_date` datetime NOT NULL,
  `start_date` datetime DEFAULT NULL,
  `end_date` datetime DEFAULT NULL,
  `contract_status` int NOT NULL,
  `owner_id` bigint NOT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contract_no` (`contract_no`)
);

-- 回款记录表
CREATE TABLE IF NOT EXISTS `payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `contract_id` bigint NOT NULL,
  `order_item_id` bigint DEFAULT NULL,
  `payment_no` varchar(50) NOT NULL,
  `payment_amount` decimal(15,2) NOT NULL,
  `payment_date` datetime NOT NULL,
  `payment_method` varchar(50) DEFAULT NULL,
  `payment_status` int NOT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`)
);

-- 开票信息表
CREATE TABLE IF NOT EXISTS `invoice_info` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `contract_id` bigint NOT NULL,
  `payment_id` bigint DEFAULT NULL,
  `invoice_no` varchar(50) NOT NULL,
  `invoice_amount` decimal(15,2) NOT NULL,
  `invoice_date` datetime NOT NULL,
  `invoice_type` varchar(50) DEFAULT NULL,
  `status` int NOT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `remark` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invoice_no` (`invoice_no`)
);

-- 用户交接记录表
CREATE TABLE IF NOT EXISTS `user_handover` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `from_user_id` bigint NOT NULL,
  `to_user_id` bigint NOT NULL,
  `task_count` int DEFAULT 0,
  `customer_count` int DEFAULT 0,
  `opportunity_count` int DEFAULT 0,
  `handover_time` datetime NOT NULL,
  `operator_id` bigint NOT NULL,
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`)
);

-- ============================================
-- 重播种说明：真库表结构由 Flyway 建（role_permissions 等子表带指向 sys_role 的外键），
-- Testcontainers 容器跨用例共享、本脚本随用例重放，清库前须关外键检查，否则 DELETE 父表命中 fk_rp_role。
-- ============================================
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 插入测试角色 (Admin)
-- ============================================
DELETE FROM `sys_role`;
INSERT INTO `sys_role` (`id`, `role_name`, `role_desc`, `create_time`) VALUES (1, 'Admin', 'Administrator', NOW());

-- ============================================
-- 插入测试权限
-- ============================================
DELETE FROM `permissions`;
INSERT INTO `permissions` (`id`, `permissions_name`, `permissions_desc`) VALUES
(1, 'CREATE_USER', 'Create User'),
(2, 'VIEW_USER', 'View User'),
(3, 'UPDATE_USER_INFO', 'Update User Info'),
(4, 'UPDATE_PASSWORD', 'Update Password'),
(5, 'SYSTEM_UPDATE_USER', 'System Update User'),
(6, 'SYSTEM_VIEW_USER', 'System View User'),
(7, 'FINANCE_RECORD_INVOICE', 'Finance Record Invoice'),
(8, 'FINANCE_EDIT_INVOICE', 'Finance Edit Invoice'),
(9, 'FINANCE_DELETE_INVOICE', 'Finance Delete Invoice'),
(10, 'FINANCE_VIEW_INVOICE', 'Finance View Invoice');

-- ============================================
-- 赋予 Admin 角色所有权限
-- ============================================
DELETE FROM `role_permissions`;
INSERT INTO `role_permissions` (`role_id`, `permissions_id`) VALUES
(1, 1),
(1, 2),
(1, 3),
(1, 4),
(1, 5),
(1, 6),
(1, 7),
(1, 8),
(1, 9),
(1, 10);

-- ============================================
-- 标签-角色绑定表（TageRoleBindingEntity）
-- 测试环境建空表：角色未绑定标签时自动绑定逻辑直接跳过
-- ============================================
CREATE TABLE IF NOT EXISTS `tage_role_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tage_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `creator_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tage_role` (`tage_id`, `role_id`)
);

-- ============================================
-- 插入测试用户
-- 密码都是 123456 的 MD5: e10adc3949ba59abbe56e057f20f883e
-- ============================================
DELETE FROM `sys_user`;
INSERT INTO `sys_user` (`id`, `password`, `real_name`, `phone`, `email`, `role_id`, `status`, `create_time`) VALUES
(1, 'e10adc3949ba59abbe56e057f20f883e', 'Admin User', '13800000000', 'admin@slz.com', 1, 1, NOW()),
(2, 'e10adc3949ba59abbe56e057f20f883e', '离职用户', '13800000001', 'leaving@slz.com', 1, 1, NOW()),
(3, 'e10adc3949ba59abbe56e057f20f883e', '接收用户', '13800000002', 'receiver@slz.com', 1, 1, NOW());

-- ============================================
-- 插入客户公司测试数据
-- ============================================
DELETE FROM `customer_company`;
INSERT INTO `customer_company` (`id`, `company_name`, `industry`, `status`, `address`, `phone`, `creator_id`, `owner_id`, `is_deleted`, `grade`) VALUES
(1, '测试公司A', 'IT', '潜在客户', '北京市海淀区', '010-12345678', 1, 2, 0, 1),
(2, '测试公司B', '金融', '意向客户', '上海市浦东新区', '021-87654321', 1, 2, 0, 2),
(3, '测试公司C', '制造', '成交客户', '广州市天河区', '020-11112222', 1, 3, 0, 3);

-- ============================================
-- 插入联络任务测试数据
-- ============================================
DELETE FROM `contact_task`;
INSERT INTO `contact_task` (`id`, `task_title`, `company_id`, `task_type`, `task_content`, `priority`, `status`, `assignee_id`, `creator_id`) VALUES
(1, '跟进客户A需求', 1, '电话', '了解客户具体需求', 5, 0, 2, 1),  -- 未开始
(2, '拜访客户B', 2, '拜访', '现场演示产品', 8, 1, 2, 1),          -- 进行中
(3, '发送报价单', 1, '邮件', '发送详细报价', 6, 2, 2, 1),          -- 已完成（不参与交接）
(4, '回访客户C', 3, '电话', '确认合作意向', 4, 1, 3, 1);           -- 进行中，属于用户3

-- ============================================
-- 插入销售机会测试数据
-- stage: 0种子/1潜在商机/2确认商机/3储备项目/4立项签约/5关闭
-- ============================================
DELETE FROM `sales_opportunity`;
INSERT INTO `sales_opportunity` (`id`, `opportunity_name`, `company_id`, `stage`, `amount`, `owner_id`, `approver_id`, `creator_id`, `is_deleted`) VALUES
(1, '测试机会A', 1, 1, 50000.00, 2, 1, 1, b'0'),
(2, '测试机会B', 2, 2, 100000.00, 2, 1, 1, b'0'),
(3, '测试机会C', 3, 3, 80000.00, 3, 1, 1, b'0');

-- ============================================
-- 插入合同测试数据
-- ============================================
DELETE FROM `contract`;
INSERT INTO `contract` (`id`, `contract_no`, `company_id`, `contract_name`, `total_amount`, `sign_date`, `contract_status`, `owner_id`, `creator_id`) VALUES
(1, 'CONTRACT-2024-001', 1, '测试合同A', 50000.00, NOW(), 1, 2, 1),
(2, 'CONTRACT-2024-002', 2, '测试合同B', 100000.00, NOW(), 1, 2, 1),
(3, 'CONTRACT-2024-003', 3, '测试合同C', 80000.00, NOW(), 1, 3, 1);

-- ============================================
-- 插入回款记录测试数据
-- ============================================
DELETE FROM `payment_record`;
INSERT INTO `payment_record` (`id`, `contract_id`, `payment_no`, `payment_amount`, `payment_date`, `payment_method`, `payment_status`, `creator_id`) VALUES
(1, 1, 'PAY-2024-001', 30000.00, NOW(), '银行转账', 0, 1),
(2, 1, 'PAY-2024-002', 20000.00, NOW(), '银行转账', 0, 1),
(3, 2, 'PAY-2024-003', 50000.00, NOW(), '支票', 0, 1),
(4, 3, 'PAY-2024-004', 40000.00, NOW(), '银行转账', 0, 1);

-- ============================================
-- 插入开票信息测试数据
-- ============================================
DELETE FROM `invoice_info`;
INSERT INTO `invoice_info` (`id`, `contract_id`, `payment_id`, `invoice_no`, `invoice_amount`, `invoice_date`, `invoice_type`, `status`, `creator_id`) VALUES
(1, 1, 1, 'INV-2024-001', 30000.00, NOW(), '增值税专用发票', 0, 1),
(2, 2, 3, 'INV-2024-002', 50000.00, NOW(), '普通发票', 0, 1),
(3, 3, 4, 'INV-2024-003', 40000.00, NOW(), '增值税专用发票', 1, 1);  -- 已作废

-- ============================================
-- 商业活动主表（BusinessActivityEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `business_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_title` varchar(200) NOT NULL,
  `activity_type` varchar(50) NOT NULL,
  `activity_content` text,
  `activity_time` datetime NOT NULL,
  `activity_duration` int DEFAULT NULL,
  `company_id` bigint DEFAULT NULL,
  `opportunity_id` bigint DEFAULT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `remark` varchar(500) DEFAULT NULL,
  `task_id` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_activity_company` (`company_id`),
  KEY `idx_activity_creator` (`creator_id`)
);

-- ============================================
-- 商业活动-联系人关联表（BusinessActivityContactEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `business_activity_contact` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `contact_id` bigint NOT NULL,
  `contact_role` varchar(50) DEFAULT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_contact_unique` (`activity_id`, `contact_id`),
  KEY `idx_act_contact_activity` (`activity_id`),
  KEY `idx_act_contact_contact` (`contact_id`),
  KEY `fk_act_contact_creator` (`creator_id`)
);

-- ============================================
-- 商业活动-员工关联表（BusinessActivityUserEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `business_activity_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `user_role` varchar(50) DEFAULT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_user_unique` (`activity_id`, `user_id`),
  KEY `idx_act_user_activity` (`activity_id`),
  KEY `idx_act_user_user` (`user_id`),
  KEY `fk_act_user_creator` (`creator_id`)
);

-- ============================================
-- 审批附件表（ApprovalAttachmentEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `approval_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `and_id` bigint NOT NULL,
  `model_name` varchar(20) NOT NULL DEFAULT 'approval_attachment',
  `file_name` varchar(200) NOT NULL,
  `file_path` varchar(500) NOT NULL,
  `file_size` bigint NOT NULL,
  `file_type` varchar(100) DEFAULT NULL,
  `upload_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `uploader_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_attachment_approval` (`and_id`)
);

-- ============================================
-- 任务评论表（TaskCommentEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `task_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `content` text NOT NULL,
  `creator_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- ============================================
-- 协助申请记录表（AssistRequestEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `assist_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_name` varchar(50) NOT NULL,
  `record_id` bigint NOT NULL,
  `applicant_id` bigint NOT NULL,
  `assist_user_id` bigint NOT NULL,
  `assist_status` int NOT NULL DEFAULT 0,
  `assist_opinion` text,
  `assist_time` datetime DEFAULT NULL,
  `apply_purpose` varchar(200) DEFAULT NULL,
  `apply_requirement` text,
  `assist_content` text,
  `reject_reason` text,
  `cancel_reason` text,
  `parent_id` bigint DEFAULT NULL,
  `pending_key` varchar(16) DEFAULT NULL,
  `opportunity_snapshot` text,
  `record_snapshot` text,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_assist_model_record` (`model_name`, `record_id`),
  KEY `idx_assist_user_status` (`assist_user_id`, `assist_status`),
  UNIQUE KEY `uk_assist_model_record_user` (`model_name`, `record_id`, `assist_user_id`)
);

-- ============================================
-- 协助生命周期留言表（AssistMessageEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `assist_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assist_id` bigint NOT NULL,
  `sender_id` bigint DEFAULT NULL,
  `content` text NOT NULL,
  `message_type` varchar(16) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_assist_message_time` (`assist_id`, `create_time`)
);

-- ============================================
-- 集团主数据表（CompanyGroupEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `company_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_name` varchar(50) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_company_group_name` (`group_name`)
);

-- ============================================
-- 集团部门主数据表（CompanyDeptEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `company_dept` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL,
  `dept_name` varchar(50) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_company_group_dept` (`group_id`, `dept_name`),
  KEY `idx_company_dept_group` (`group_id`),
  CONSTRAINT `fk_company_dept_group` FOREIGN KEY (`group_id`) REFERENCES `company_group` (`id`)
);

-- ============================================
-- 系统部门表（SysDeptEntity，用户归属）
-- ============================================
CREATE TABLE IF NOT EXISTS `sys_dept` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dept_name` varchar(50) NOT NULL,
  `parent_id` bigint DEFAULT NULL,
  `sort` int DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_dept_parent` (`parent_id`),
  CONSTRAINT `chk_sys_dept_status` CHECK (`status` IN (0, 1))
);

-- ============================================
-- 销售阶段变更审批表（SalesStageApprovalEntity）
-- ============================================
CREATE TABLE IF NOT EXISTS `sales_stage_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `opportunity_id` bigint NOT NULL,
  `current_stage` varchar(30) NOT NULL,
  `target_stage` varchar(30) NOT NULL,
  `applicant_id` bigint NOT NULL,
  `approver_id` bigint NOT NULL,
  `approval_status` int NOT NULL DEFAULT 0,
  `approval_triggered` tinyint(1) NOT NULL DEFAULT 1,
  `approval_opinion` text,
  `apply_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `approval_time` datetime DEFAULT NULL,
  `message` varchar(500) DEFAULT NULL,
  `version` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_approval_opportunity` (`opportunity_id`),
  KEY `idx_approval_status` (`approval_status`)
);

SET FOREIGN_KEY_CHECKS = 1;
