-- 目的：为组织数据权限新增部门负责人字段和“本部门/本部门及以下”权限种子。
-- 影响表：sys_dept 新增 leader_id；permissions 新增 18 个权限项。
-- 授权策略：只登记权限，不写入 role_permissions，默认不改变任何既有角色的可见范围。
-- 回滚注意：回滚需先确认无角色绑定 1174/1175、2044/2045、2114/2115、2154/2155、
--          2204/2205、2254/2255、2294/2295、3044/3045、4044/4045 后再删除权限，
--          并保留或按需重建 sys_dept.leader_id 相关业务数据。

ALTER TABLE sys_dept
    ADD COLUMN leader_id bigint NULL COMMENT '部门负责人用户ID' AFTER parent_id;

CREATE INDEX idx_sys_dept_leader
    ON sys_dept (leader_id);

INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES
    (1174, 'CUSTOMER_VIEW_COMPANY_DEPT', '查看客户公司 - 本部门'),
    (1175, 'CUSTOMER_VIEW_COMPANY_DEPT_AND_SUB', '查看客户公司 - 本部门及以下'),
    (2044, 'SALES_VIEW_SALE_OPPORTUNITY_DEPT', '查看销售机会 - 本部门'),
    (2045, 'SALES_VIEW_SALE_OPPORTUNITY_DEPT_AND_SUB', '查看销售机会 - 本部门及以下'),
    (2114, 'SALES_VIEW_ORDER_DEPT', '查看订单 - 本部门'),
    (2115, 'SALES_VIEW_ORDER_DEPT_AND_SUB', '查看订单 - 本部门及以下'),
    (2154, 'SALES_VIEW_CONTRACT_DEPT', '查看合同 - 本部门'),
    (2155, 'SALES_VIEW_CONTRACT_DEPT_AND_SUB', '查看合同 - 本部门及以下'),
    (2204, 'SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT', '查看销售机会阶段 - 本部门'),
    (2205, 'SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT_AND_SUB', '查看销售机会阶段 - 本部门及以下'),
    (2254, 'SALES_VIEW_BUSINESS_ACTIVITY_DEPT', '查看商业活动 - 本部门'),
    (2255, 'SALES_VIEW_BUSINESS_ACTIVITY_DEPT_AND_SUB', '查看商业活动 - 本部门及以下'),
    (2294, 'SALES_VIEW_PROJECT_FILE_DEPT', '查看项目文件 - 本部门'),
    (2295, 'SALES_VIEW_PROJECT_FILE_DEPT_AND_SUB', '查看项目文件 - 本部门及以下'),
    (3044, 'FINANCE_VIEW_PAYMENT_DEPT', '查看回款 - 本部门'),
    (3045, 'FINANCE_VIEW_PAYMENT_DEPT_AND_SUB', '查看回款 - 本部门及以下'),
    (4044, 'TASK_VIEW_TASK_DEPT', '查看任务 - 本部门'),
    (4045, 'TASK_VIEW_TASK_DEPT_AND_SUB', '查看任务 - 本部门及以下');
