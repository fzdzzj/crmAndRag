-- token-usage-admin-view 任务 1.2：超管 AI 用量查看权限种子（只授超管角色，业务角色零授权）
-- 目的：为 AiTokenUsageController 两个只读端点（/ai/token-usage/by-user、/ai/token-usage/summary）提供鉴权锚点；
--       810 AI_TOKEN_USAGE_VIEW 与 PermissionOperates 常量同号同义。
-- 影响表：permissions 新增 1 行（810）；role_permissions 新增 1 行（810 → role_id=1）。
-- 授权口径：roleId=1 = 超级管理员角色。这里显式授权不是给拦截器用（roleId=1 由 PermissionsInterceptor 直通），
--       而是让前端 getMyPermissionGrouped（走 role_permissions 联查）能拿到 810，从而「AI 用量」菜单可见；
--       业务角色仍零授权——与 V26/V27/V30/V33 的 CROSS JOIN 口径一致（那些脚本一律 NOT IN (0, 1, 2)）。
-- 命名口径：permissions_name 用枚举裸名，与 V26（800-807）、V27（900）及 PermissionSyncRunner 同步口径一致。
-- 回滚注意：不提供 DROP；回退 = 恢复迁移前数据库快照（runbook 4.1 dump）。
--           若只需撤销授权面，回退需同时清理 role_permissions 中 810/role 1 这行，再删 permissions 810
--           （先确认无其他角色绑定 810）。
INSERT IGNORE INTO permissions (id, permissions_name, permissions_desc) VALUES
    (810, 'AI_TOKEN_USAGE_VIEW', '查看AI用量');

-- 超管角色显式授权：仅供前端菜单 gate 的数据面读取（拦截器对 roleId=1 直通，不依赖本行）
INSERT IGNORE INTO role_permissions (permissions_id, role_id) VALUES (810, 1);
