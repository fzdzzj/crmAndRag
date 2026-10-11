# proposal — add-prompt-template-admin（提示词模板管理产品化）

## Why

平台 AI 助手域（server/ai：Assist / AiChat / AiAction）目前没有提示词模板的管理能力，提示词散落在代码与调用方拼接中，无版本、无审计、无复用。owner 提出方向：提示词应可从文本读取、可后台配置、有数据库记录。platform 域已有 dynamic_config_item / dynamic_config_history 双表先例（配置 + 版本历史），提示词模板需要同构但独立的产品化能力（含变量定义与渲染组装）。

## What Changes

1. **DDL（V32 建表 + V33 权限种子，两文件跟 V26/V27/V30 先例）**：
   - `prompt_template`：id、code（业务键，唯一）、name、category、content（TEXT/MEDIUMTEXT）、variables（JSON 数组：name/required/description）、version（乐观递增）、status（0 启用 / 1 停用）、remark、created_by/created_at/updated_by/updated_at；
   - `prompt_template_history`：id、template_id、version、content、variables、operation（CREATE/UPDATE/STATUS）、operator、operated_at——参照 dynamic_config_history 模式。
2. **后端（server 域，与 Assist 同级）**：PromptTemplateController（/promptTemplate）+ PromptTemplateService + PromptTemplateMapper（MP BaseMapper）+ entity/dto/vo 分层（@AutoTable + @TableName）。
   - 端点：分页列表 / 详情 / 创建 / 更新（版本递增 + 历史落档）/ 删除 / 启停用 / 版本历史查询 / **渲染**（code + variables 入参 → 占位符替换组装，纯 Java 零外呼）。
3. **权限矩阵**：PermissionOperates 新增 `PROMPT_TEMPLATE_VIEW(808)` / `PROMPT_TEMPLATE_MANAGE(809)`；V33 种子按「注册与授权同轮」策略（INSERT permissions → INSERT...SELECT 授权全部业务角色，role_id NOT IN (0,1,2)）；PermissionCoverageScanner.CONTROLLER_REGISTRY 同步登记。
4. **测试**：Service 单测（渲染变量校验/必填拒绝/替换、版本递增、code 唯一冲突）+ PromptTemplateControllerIT（Testcontainers MySQL：CRUD/历史/权限正反向）+ PermissionCoverageAuditIT / SchemaDriftAuditIT 自动纳入。
5. **种子数据（V33 内）**：预置 2 个启用态模板——agent 执行任务书骨架 / agent 复核任务书骨架（从本项目 agent 工作流实际模板提炼，含变量定义示例）。

## Impact

- 代码：server/controller、server/service、server/mapper、pojo/{entity,dto,vo}、common/enumeration（PermissionOperates）、db/migration（V32/V33）、test 域。
- 契约：不动 contracts-frozen.md 既有 20 契约；新端点全部挂 @RequirePermission（写语义不裸奔）。
- 不做：AiChat 运行时模板引用集成（后续卡）、前端管理页（后续卡）、模板国际化、既有表改动、真实模型外呼。

## 拍板点（owner 已定向 L3 产品化，细化决策见 design）

- D1 域归属 server 域（与 Assist 同级）——已按调研建议
- D2 权限两档 808 VIEW / 809 MANAGE
- D3 渲染接口本卡含（{{var}} 替换 + 必填校验，纯 Java）
- D4 本卡纯后端，前端另卡
- D5 种子预置 2 个 agent 工作流骨架模板
