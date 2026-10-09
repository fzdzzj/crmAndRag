-- add-dynamic-config-key-tier-acl 任务 1.4，动态配置管理权限种子（运营档 608 + 业务角色同轮授权）
-- 目的：为 DynamicConfigAdminController 7 端点（/platform/config/*）提供方法级 @RequirePermission 鉴权；
--       读端点（列表/详情/历史）与缓存刷新、运营档键写入对 608 持有者放开，成本/结构档键仍由服务层超管闸专写（96005）。
-- 影响表：permissions（新增 608）；role_permissions（CROSS JOIN 授权）。
-- 授权策略：全部业务角色（roleId NOT IN (0,1,2) 且 is_deleted=0）同轮获权；roleId=1 超管由拦截器直通不授；
--           0（冻结）/2（离职）特殊角色不授；挂注解与种植同轮（矩阵迁移：7 端点从 INTENTIONAL_OPEN 转 SECURED）。
-- 回滚注意：回滚需先确认无角色绑定 608 后再删除权限；不提供 DROP，回退 = 恢复迁移前数据库快照。

-- 平台动态配置管理权限（608，6xx 平台管理段，插 607 之后）
INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES
    (608, 'PLATFORM_DYNAMIC_CONFIG_MANAGE', '平台动态配置管理');

-- 给全部业务角色授权（CROSS JOIN 避开 roleId 0/1/2）
INSERT INTO role_permissions (permissions_id, role_id)
SELECT p.id, r.id
FROM permissions p
         CROSS JOIN sys_role r
WHERE p.id IN (608)
  AND r.id NOT IN (0, 1, 2)
  AND r.is_deleted = b'0';
