-- 前端 E2E 专用种子数据（在 crm-back/script.sql 与 crm-back/src/test/resources/init_data.sql 之后执行）
-- 所有测试账号密码均为 123456（MD5: e10adc3949ba59abbe56e057f20f883e）

INSERT INTO sys_role (id, role_name, role_desc, create_time, is_deleted) VALUES
(1, 'Admin', 'Administrator', NOW(), b'0'),
(2, '总监', 'Director', NOW(), b'0'),
(3, '销售经理', 'Sales Manager', NOW(), b'0'),
(4, '销售员', 'Sales', NOW(), b'0')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), role_desc = VALUES(role_desc);

INSERT INTO sys_user (id, password, real_name, phone, email, dept_id, role_id, status, creator_id, create_time, update_time) VALUES
(9, 'e10adc3949ba59abbe56e057f20f883e', 'Super User', '13800000010', 'admin1@slz.com', NULL, 1, 1, 1, NOW(), NOW()),
(4, 'e10adc3949ba59abbe56e057f20f883e', '李华', '13800000011', 'lihua@example.com', NULL, 4, 1, 1, NOW(), NOW()),
(5, 'e10adc3949ba59abbe56e057f20f883e', '张明', '13800000012', 'zhangming@example.com', NULL, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE password = VALUES(password), role_id = VALUES(role_id), status = VALUES(status);

-- 销售员角色：除“权限管理（system:*）”外的全部权限，保证页面可加载且不显示权限管理菜单
INSERT INTO role_permissions (role_id, permissions_id, creator_id, is_deleted)
SELECT 4, id, 1, b'0'
FROM permissions
WHERE permissions_name NOT LIKE 'system:%'
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = 4 AND rp.permissions_id = permissions.id AND rp.is_deleted = b'0'
  );
