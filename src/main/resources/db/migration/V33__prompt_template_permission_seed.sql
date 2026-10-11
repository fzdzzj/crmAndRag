-- add-prompt-template-admin 任务 1.3，提示词模板权限种子（808/809 注册 + 业务角色同轮授权 + 种子 2 模板）
-- 目的：为 PromptTemplateController 8 端点（/promptTemplate/*）提供方法级 @RequirePermission 鉴权；
--       两档分权：808 PROMPT_TEMPLATE_VIEW（查看/详情/历史/渲染），809 PROMPT_TEMPLATE_MANAGE（创建/更新/删除/启停用）。
-- 影响表：permissions（新增 808/809）；role_permissions（CROSS JOIN 授权）；prompt_template（种子 2 模板）。
-- 授权策略：全部业务角色（roleId NOT IN (0,1,2) 且 is_deleted=0）同轮获权；roleId=1 超管由拦截器直通不授；
--           0（冻结）/2（离职）特殊角色不授；挂注解与种植同轮。种子模板 status=0 启用（渲染接口可用）。
-- 回滚注意：回滚需先确认无角色绑定 808/809 后再删除权限；不提供 DROP，回退 = 恢复迁移前数据库快照。

-- 提示词模板权限（808 VIEW / 809 MANAGE，AI 模块 800 段，插 807 之后）
INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES
    (808, 'PROMPT_TEMPLATE_VIEW', '查看提示词模板'),
    (809, 'PROMPT_TEMPLATE_MANAGE', '管理提示词模板');

-- 给全部业务角色授权（CROSS JOIN 避开 roleId 0/1/2）
INSERT INTO role_permissions (permissions_id, role_id)
SELECT p.id, r.id
FROM permissions p
         CROSS JOIN sys_role r
WHERE p.id IN (808, 809)
  AND r.id NOT IN (0, 1, 2)
  AND r.is_deleted = b'0';

-- 种子模板 1：agent 执行子 agent 任务书骨架（agent-executor-brief）
INSERT INTO prompt_template (code, name, category, content, variables, version, status, remark)
VALUES ('agent-executor-brief', 'Agent 执行任务书骨架', 'agent-executor',
        '# {{worktree}} / {{cardName}} 执行子 agent 任务书（{{taskGroups}}）\n'
        '\n'
        '## 角色与对象\n'
        '你是执行子 agent，在权威树实施变更卡 add-prompt-template-admin（{{taskGroups}}）。\n'
        '- 权威树（唯一操作树）：{{worktree}}，master @ {{baseCommit}}。\n'
        '- 分支：从 master 检出 {{branch}}。\n'
        '\n'
        '## 环境\n'
        '- Windows / PowerShell 5.1（无 &&；git 提交单独执行并用 2>&1 | Out-String 包裹防 stderr 静默中断）。\n'
        '- 命令前置 $env:DASHSCOPE_API_KEY=""（全卡零真实外呼防线）。\n'
        '\n'
        '## {{taskGroups}}\n'
        '（此处展开各任务组步骤）\n'
        '\n'
        '## 铁律（违反即越权）\n'
        '{{redLines}}\n'
        '\n'
        '## 门禁（全部自跑并粘贴 raw 关键行）\n'
        '{{gates}}\n'
        '\n'
        '## 停步回报格式\n'
        '{{reportFormat}}\n',
        '[{\"name\":\"cardName\",\"required\":true,\"description\":\"变更卡名\"},{\"name\":\"worktree\",\"required\":true,\"description\":\"权威树路径\"},{\"name\":\"branch\",\"required\":true,\"description\":\"feature 分支名\"},{\"name\":\"baseCommit\",\"required\":false,\"description\":\"基线 commit\"},{\"name\":\"taskGroups\",\"required\":true,\"description\":\"任务组清单\"},{\"name\":\"redLines\",\"required\":true,\"description\":\"铁律清单\"},{\"name\":\"gates\",\"required\":true,\"description\":\"门禁清单\"},{\"name\":\"reportFormat\",\"required\":true,\"description\":\"停步回报格式\"}]',
        1, 0, '本卡种子骨架，后续卡可直接渲染微调');

-- 种子模板 2：agent 复核子 agent 任务书骨架（agent-reviewer-brief）
INSERT INTO prompt_template (code, name, category, content, variables, version, status, remark)
VALUES ('agent-reviewer-brief', 'Agent 复核任务书骨架', 'agent-reviewer',
        '# {{cardName}} 复核任务书（{{branch}} @ {{baseCommit}}）\n'
        '\n'
        '## 复核对象\n'
        '对 {{worktree}} 上 {{branch}} 分支的变更做独立复核，不含并要求不含推送。\n'
        '\n'
        '## {{reviewItems}}\n'
        '（此处展开七项复核：范围/结构/权限/漂移/门禁/测试/留痕）\n'
        '\n'
        '## 输出格式（VERDICT）\n'
        '{{reportFormat}}\n'
        '\n'
        '## 铁律\n'
        '{{redLines}}\n',
        '[{\"name\":\"cardName\",\"required\":true,\"description\":\"变更卡名\"},{\"name\":\"worktree\",\"required\":true,\"description\":\"权威树路径\"},{\"name\":\"branch\",\"required\":true,\"description\":\"待复核分支名\"},{\"name\":\"baseCommit\",\"required\":false,\"description\":\"基线 commit\"},{\"name\":\"reviewItems\",\"required\":true,\"description\":\"复核项清单\"},{\"name\":\"redLines\",\"required\":true,\"description\":\"铁律清单\"},{\"name\":\"reportFormat\",\"required\":true,\"description\":\"VERDICT 产出格式\"}]',
        1, 0, '本卡种子骨架，后续卡可直接渲染微调');