-- add-knowledge-admin-api 任务 2.2，知识库管理权限种子（单码读写同权 + 业务角色同轮授权）
-- 目的：为 KnowledgeAdminController 7 端点（/knowledge/*）提供鉴权；复用 606 读写同权模式；业务角色（非 0/1/2）同轮获权。
-- 影响表：permissions（新增 900）；role_permissions（CROSS JOIN 授权）。
-- 授权策略：保持现状访问面；roleId=1 超管 interceptor 直通；0/2 特殊不授；挂注解与种植同轮。
-- 回滚注意：回滚需先确认无角色绑定 900 后再删除权限；不提供 DROP，回退 = 恢复迁移前数据库快照。

-- 知识库管理权限（900 段，单码覆盖列表/上传/删除/重建/检索测试 7 端点）
INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES
    (900, 'KNOWLEDGE_ADMIN_MANAGE', '知识库管理');

-- 给全部业务角色授权（CROSS JOIN 避开 roleId 0/1/2）
INSERT INTO role_permissions (permissions_id, role_id)
SELECT p.id, r.id
FROM permissions p
         CROSS JOIN sys_role r
WHERE p.id IN (900)
  AND r.id NOT IN (0, 1, 2)
  AND r.is_deleted = b'0';
