-- apply-permission-matrix 任务 1.3，D1 拍板方案A：AI 模块 800 段权限 + D2 拍板复用 501-504 报表权限种植
-- 目的：把 audit-permission-matrix 审计报告的 PENDING_DECISION 57 端点落地为真实鉴权——
--       新增 AI 模块权限常量（800-807）与已存在但零引用的报表权限（501-504）种子，并给业务角色授权。
-- 影响表：permissions 新增 12 个权限项（501-504、800-807）；role_permissions 给全部业务角色授权。
-- 授权策略：保持现状访问面（工程默认 1）——全部现有业务角色授权新权限项；roleId=1 超管由 interceptor
--       直通无需授权行；roleId=0 冻结 / roleId=2 离职特殊角色不授。挂注解必须与种植同轮交付，否则非超管撞 12002。
-- 回滚注意：回滚需先确认无角色绑定 501-504/800-807 后再删除权限，并保留或按需重建业务数据；
--          不提供 DROP，回退 = 恢复迁移前数据库快照。

-- 报表权限（524 段，既有枚举零引用，双击漂移修复）
INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES
    (501, 'REPORT_VIEW_REPORT', '查看报表'),
    (502, 'REPORT_GENERATE_REPORT', '生成报表'),
    (503, 'REPORT_EXPORT_REPORT', '导出报表'),
    (504, 'REPORT_MANAGE_TEMPLATE', '管理报表模板');

-- AI 模块权限（800 段，D1 方案A）
INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES
    (800, 'AI_ASSIST_VIEW', '查看协助'),
    (801, 'AI_ASSIST_APPLY', '提交协助申请'),
    (802, 'AI_ASSIST_HANDLE', '处理协助'),
    (803, 'AI_CHAT_SESSION', '管理AI会话'),
    (804, 'AI_CHAT_STREAM', 'AI流式对话'),
    (805, 'AI_CHAT_CANCEL', '取消AI生成'),
    (806, 'AI_ACTION_VIEW', '查看待确认操作'),
    (807, 'AI_ACTION_CONFIRM', '确认执行待确认操作');

-- 给全部业务角色授权（保持现状访问面；CROSS JOIN 避开 roleId 0/1/2 特殊角色）
INSERT INTO role_permissions (permissions_id, role_id)
SELECT p.id, r.id
FROM permissions p
         CROSS JOIN sys_role r
WHERE p.id IN (501, 502, 503, 504, 800, 801, 802, 803, 804, 805, 806, 807)
  AND r.id NOT IN (0, 1, 2)
  AND r.is_deleted = b'0';