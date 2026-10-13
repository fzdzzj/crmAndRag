-- add-customer-semantic-retrieval 任务 1.4，AiChat 系统提示词业务检索槽位标记种子（D3 槽位化注入）
-- 目的：在 aichat-system-prompt content 尾部（V37 四槽位标记之后）追加 {{#slot:retrievedContext}} 标记——
--       渲染时由 AiChat 组装 slots 值表整段替换，值=知识库+业务数据双源检索合并的上下文成品（空检索=空串，
--       PromptTemplateSlotSupport 对 slots!=null 的空缺 key 替空串，标记绝不外漏）。
--       模板无标记/回退态走既有旧路径逐字节不变（回归锁）；{{var}}/{{> code}}/既有四槽位语义零改动。
-- 影响表：prompt_template（UPDATE aichat-system-prompt content 尾部加一标记 + version+1 → 3）；
--         prompt_template_history（落一笔 operation=UPDATE 版本快照，回填主表新 content，V36/V37 先例）。
-- 授权策略：零权限变更——槽位注入复用既有渲染链，无新增权限、无角色授权改动。
-- 回滚注意：回退需先确认无业务方依赖 V38 槽位 content 或已自定义迁移后模板；
--           不提供 DROP，回退 = 恢复迁移前数据库快照。
--
-- 回归锁：V38 模板态（槽位注入后）与 V37 模式产出逐字节等价——标记紧贴 V37 四标记尾部，
--          槽位值=检索上下文成品；非槽位/回退态唯一路径（旧追加路径）逐字节不变。

-- 1) aichat-system-prompt content 尾部追加 retrievedContext 槽位标记，version+1（V37 为 version=2 → V38 后 version=3）
UPDATE prompt_template
SET content = CONCAT(content, '{{#slot:retrievedContext}}'),
    version  = version + 1
WHERE code = 'aichat-system-prompt';

-- 2) UPDATE 历史快照（version=3，回填主表新 content，V36/V37 INSERT...SELECT 先例）
INSERT INTO prompt_template_history (template_id, version, content, variables, operation, operator)
SELECT id, version, content, variables, 'UPDATE', NULL
FROM prompt_template
WHERE code = 'aichat-system-prompt'
  AND version = 3;
