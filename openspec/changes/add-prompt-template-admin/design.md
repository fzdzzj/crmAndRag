# design — add-prompt-template-admin

## D1 · 域归属：server 域（AI 助手平台能力）

PromptTemplateController 放 `server/controller/`（与 AssistController / AiChatController 同级，/promptTemplate 前缀），Service/Mapper 同层就位，entity 在 `pojo/entity/`（@AutoTable + @TableName("prompt_template")，参照 AssistRequestEntity 模式含索引注解）。理由：提示词模板是 AI 助手域的资产，消费方是 Assist/Chat/Action；platform 域的 dynamic-config 是通用配置治理（key-value），语义不同不混用，但**双表模式（主表 + history 版本表）直接参照** dynamic_config_item / dynamic_config_history。

## D2 · 权限两档（808 / 809）

- `PROMPT_TEMPLATE_VIEW(808)`：分页 / 详情 / 历史查询 / 渲染；
- `PROMPT_TEMPLATE_MANAGE(809)`：创建 / 更新 / 删除 / 启停用。
两档而非单档读写同权（606 先例）：模板查看与渲染是使用面（角色可放开），改模板是治理面（收紧）。V33 种子：INSERT permissions（808/809）→ INSERT...SELECT 授权业务角色（role_id NOT IN (0,1,2)，超管拦截器直通）——V26 §AI 段与 V30 先例同构。CONTROLLER_REGISTRY 登记 PromptTemplateController，全端点挂方法级 @RequirePermission，零 INTENTIONAL_OPEN。

## D3 · 渲染接口（本卡核心价值）

`POST /promptTemplate/render`：入参 `{code, variables: {name:value}}` → 取启用态模板 → 校验 variables 定义（required 缺失 → 12000 段业务异常；未定义变量提供值 → 拒绝，防静默吞错）→ `{{var}}` 占位符逐个替换（纯 Java 字符串处理，MessageFormat 不用——避免格式符歧义）→ 返回组装文本 + 实际使用模板 version（可追溯）。零真实模型外呼（不调 DashScope，渲染只是组装）。

variables 字段格式（JSON 列）：`[{"name":"cardName","required":true,"description":"变更卡名"}]`，Service 解析用 Jackson，坏 JSON 创建/更新时即拒绝。

## D4 · 版本与历史

- version 从 1 起，每次更新（含 content/variables 变更）+1；历史表落 operation=UPDATE 快照（CREATE 也落首笔）；
- 启停用不改 version（非内容变更），历史落 operation=STATUS；
- 删除为物理删（域内 AssistRequest 等先例无软删基建），历史表级联物理删（template_id 外键无 FK 约束，Service 内先删 history 再删主表，同事务）；
- 更新携带 expectedVersion 做乐观锁，不匹配 → 业务异常（并发防覆盖）。

## D5 · 种子模板（V33 内，2 个）

从本项目 agent 工作流实际任务书提炼骨架（占位符化）：
1. `agent-executor-brief`：执行子 agent 任务书骨架——变量 cardName/worktree/branch/baseCommit/taskGroups/redLines/gates/reportFormat，正文含角色定位/环境坑（PowerShell 5.1、外呼防线）/门禁/铁律/停步回报格式段；
2. `agent-reviewer-brief`：复核任务书骨架——变量同上裁剪 + reviewItems，含七项复核与 VERDICT 产出格式段。
两模板 status=0 启用。种子内容即「数据库记录」初衷的落地：后续卡可直接渲染微调，不必每次现场手写全文。

## 边界与不做

- 不做 AiChat 运行时模板引用（渲染接口先行，运行时集成后续卡）；
- 不做前端管理页、模板分类树、模板导入导出；
- 不动 contracts-frozen.md / 既有迁移 / SUITE_VERSION / 前端；
- 零真实外呼：全卡不触 DashScope，测试全 fake/mock。

## 测试设计

- 单测（surefire）：PromptTemplateServiceTest——渲染三态（正常替换/required 缺失拒绝/未定义变量拒绝）、版本递增与乐观锁冲突、code 唯一冲突、坏 variables JSON 拒绝、启停用不改版本、删除级联历史。
- IT（failsafe，Testcontainers MySQL）：PromptTemplateControllerIT——CRUD 全链 + 历史查询 + 渲染端到端 + 权限正反向（808 无 809 → 写 12002；无 808 → 读 12002）；参照 PermissionControllerIT 结构。
- 门禁自动面：PermissionCoverageAuditIT（CONTROLLER_REGISTRY 登记 + 全端点注解校验）、SchemaDriftAuditIT（AutoTable ↔ V32 DDL 一致）。
