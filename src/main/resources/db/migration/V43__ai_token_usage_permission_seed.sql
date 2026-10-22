-- token-usage-admin-view 任务 1.2：超管 AI 用量查看权限种子（只种权限行，不授业务角色）
-- 目的：为 AiTokenUsageController 两个只读端点（/ai/token-usage/by-user、/ai/token-usage/summary）提供鉴权锚点；
--       810 AI_TOKEN_USAGE_VIEW 与 PermissionOperates 常量同号同义。
-- 影响表：仅 permissions 新增 1 行（810）；role_permissions 不动——超管 roleId=1 由 PermissionsInterceptor 直通，
--       等价「超管专享」；后续若要放开给运营角色，另起一条授权迁移，不改本脚本。
-- 命名口径：permissions_name 用枚举裸名，与 V26（800-807）、V27（900）及 PermissionSyncRunner 同步口径一致。
-- 回滚注意：不提供 DROP；回退 = 恢复迁移前数据库快照（runbook 4.1 dump）。
--           若只需撤销授权面，先确认无角色绑定 810 再手工删除该行。
INSERT IGNORE INTO permissions (id, permissions_name, permissions_desc) VALUES
    (810, 'AI_TOKEN_USAGE_VIEW', '查看AI用量');
