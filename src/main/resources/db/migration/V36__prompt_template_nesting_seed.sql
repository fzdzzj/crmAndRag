-- add-prompt-template-nesting 任务 2.1，提示词模板嵌套引用种子示范（D5）
-- 目的：兑现 owner 动机——V33 两 agent 骨架（agent-executor-brief / agent-reviewer-brief）的公共纪律段
--       （铁律/门禁约定）分散重复，改一处需同步多处。本迁移落一个公共子模板 agent-common-discipline ，
--       并把两骨架的「## 铁律」段改写为 {{> agent-common-discipline}} 嵌套引用（render 递归展开）。
-- 影响表：prompt_template（INSERT agent-common-discipline；UPDATE 两骨架 content+version+1）；
--         prompt_template_history（每模板各落一笔快照：公共子模板 CREATE 一笔，两骨架 UPDATE 一笔）。
-- 授权策略：零权限变更——子模板仅作为内容组合资产被父模板 render 时引用，无独立访问面（D6）；
--           模板渲染复用既有 808 PROMPT_TEMPLATE_VIEW，无新增权限、无角色授权改动。
-- 回滚注意：回退需先确认无业务方直接渲染 agent-common-discipline / 依赖两骨架旧 content；
--           不提供 DROP，回退 = 恢复迁移前数据库快照。

-- 1) 公共子模板 agent-common-discipline（公共纪律段：铁律/门禁约定，抽取自两骨架公共段，normalize 为描述性标题）
INSERT INTO prompt_template (code, name, category, content, variables, version, status, remark)
VALUES ('agent-common-discipline', 'Agent 公共纪律段', 'agent-common',
        '## 铁律（违反即越权）\n{{redLines}}\n',
        '[{"name":"redLines","required":true,"description":"铁律清单"}]',
        1, 0, '公共纪律子模板：供 agent 骨架模板 {{> }} 嵌套引用，改一处即改所有骨架');

-- 2) 公共子模板历史 CREATE 快照（与主表一次写入对齐，V35 INSERT...SELECT 先例）
INSERT INTO prompt_template_history (template_id, version, content, variables, operation, operator)
SELECT id, 1, content, variables, 'CREATE', NULL
FROM prompt_template
WHERE code = 'agent-common-discipline';

-- 3) 两骨架 content 嵌入 {{> agent-common-discipline}}，version +1（保留既有 {{var}} 占位不动）
UPDATE prompt_template
SET content = REPLACE(content, '## 铁律（违反即越权）\n{{redLines}}\n\n', '{{> agent-common-discipline}}\n'),
    version  = version + 1
WHERE code = 'agent-executor-brief';

UPDATE prompt_template
SET content = REPLACE(content, '## 铁律\n{{redLines}}\n', '{{> agent-common-discipline}}'),
    version  = version + 1
WHERE code = 'agent-reviewer-brief';

-- 4) 两骨架 UPDATE 历史快照（version=2，回填主表新 content/variables，V35 INSERT...SELECT 先例）
INSERT INTO prompt_template_history (template_id, version, content, variables, operation, operator)
SELECT id, version, content, variables, 'UPDATE', NULL
FROM prompt_template
WHERE code IN ('agent-executor-brief', 'agent-reviewer-brief')
  AND version = 2;