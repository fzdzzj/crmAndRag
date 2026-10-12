-- add-prompt-template-slots 任务 2.2，AiChat 系统提示词分段槽位化种子（D5）
-- 目的：把 AiChat 运行时系统提示词的动态段从 Java 硬编码迁入模板槽位——在 aichat-system-prompt content 尾部按现追加顺序
--       （当前意图/已确认事实/历史摘要/澄清）加四 {{#slot:key}} 标记；渲染时由 AiChat 组装 slots 值表整段替换，
--       顺序=标记位置（挪标记即调序）、块数量=标记数（增删即开关）。aichat-session-title 零改动。
-- 影响表：prompt_template（UPDATE aichat-system-prompt content 尾部加四标记 + version+1 → 2）；
--         prompt_template_history（落一笔 operation=UPDATE 版本快照，回填主表新 content）。
-- 授权策略：零权限变更——槽位注入复用既有 808 PROMPT_TEMPLATE_VIEW 渲染链，无新增权限、无角色授权改动（D3）。
-- 回滚注意：回退需先确认无业务方依赖 V37 槽位 content 或已自定义迁移后模板；
--           不提供 DROP，回退 = 恢复迁移前数据库快照。
--
-- 回归锁：V37 模板态（槽位注入后）与 V35 旧模式产出逐字节等价——标记紧贴 V35 尾部换行符后，槽位值含 \n\n 标题间距，
--          与旧追加路径（骨架\\n + \\n\\n【段】）逐字节一致。

-- 1) aichat-system-prompt content 尾部按现顺序追加四槽位标记，version+1（V35 为 version=1 → V37 后 version=2）
UPDATE prompt_template
SET content = CONCAT(content, '{{#slot:currentIntent}}{{#slot:confirmedFacts}}{{#slot:historySummary}}{{#slot:clarify}}'),
    version  = version + 1
WHERE code = 'aichat-system-prompt';

-- 2) UPDATE 历史快照（version=2，回填主表新 content，V36 INSERT...SELECT 先例）
INSERT INTO prompt_template_history (template_id, version, content, variables, operation, operator)
SELECT id, version, content, variables, 'UPDATE', NULL
FROM prompt_template
WHERE code = 'aichat-system-prompt'
  AND version = 2;