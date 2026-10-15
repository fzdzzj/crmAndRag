-- ============================================================
-- V42__sales_lead.sql —— add-lead-management-and-conversion-funnel：销售线索表与一键转化漏斗
--
-- 背景：
--   CRM 业务流程从客户公司（customer_company）/联系人（customer_contact）直接建立，缺少销售漏斗最前端
--   入口「销售线索（Lead）」。市场展会、官网留资、外部推广或转介绍获取的初步名单意向度未经验证，
--   直接建为正式客户会导致客户库冗余脏乱。
--
-- 设计（对应 openspec/changes/add-lead-management-and-conversion-funnel/proposal.md §3.1）：
--   1. 新建销售线索表 sales_lead：记录姓名、公司名、联系方式、线索来源、线索状态
--      （0待跟进/1跟进中/2已转化/3已关闭无效）、转化关联 ID 与转化时间；
--   2. 核心「一键转化」在单事务内生成客户公司/首要联系人及可选销售机会后，回填转化状态与 ID；
--   3. 组合索引加速负责人/状态列表与公司名检索。
--
-- 号段归属：顺序号段下一空闲 = V42（当前目录实测最高 V41）。
--
-- 回滚注意：
--   纯新增表，无破坏性变更；如需回退请用 DB 快照恢复（本仓约定不提供 DROP 回滚）。
-- ============================================================

CREATE TABLE sales_lead (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '线索ID',
    name VARCHAR(100) NOT NULL COMMENT '联系人姓名',
    company_name VARCHAR(200) NOT NULL COMMENT '公司名称',
    phone VARCHAR(50) DEFAULT NULL COMMENT '电话',
    email VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    source VARCHAR(50) DEFAULT NULL COMMENT '线索来源',
    status INT NOT NULL DEFAULT 0 COMMENT '线索状态(0待跟进/1跟进中/2已转化/3已关闭无效)',
    owner_id BIGINT NOT NULL COMMENT '负责人ID',
    creator_id BIGINT NOT NULL COMMENT '创建人ID',
    converted_company_id BIGINT DEFAULT NULL COMMENT '转化后客户公司ID',
    converted_contact_id BIGINT DEFAULT NULL COMMENT '转化后联系人ID',
    converted_opportunity_id BIGINT DEFAULT NULL COMMENT '转化后商机ID',
    converted_time DATETIME DEFAULT NULL COMMENT '转化时间',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    is_deleted INT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0正常/1已删除)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_sales_lead_owner (owner_id, is_deleted),
    KEY idx_sales_lead_status (status, is_deleted),
    KEY idx_sales_lead_company (company_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售线索表';