# proposal — prompt-template-enhance（提示词模板增强：revert 回滚 + feedback 反馈）

## Why

add-prompt-template-admin 上线后 owner 提出三个缺口：①历史版本只能看不能用（无回填）；②无使用反馈记录；③（分段装载/嵌套引用属后续卡，本卡不含）。本卡补齐 revert + feedback，使模板管理闭环：编辑产生版本 → 反馈记录版本效果 → 回滚恢复任意历史版本。

## What Changes

1. **V34__prompt_template_feedback.sql**：新表 `prompt_template_feedback`（id、template_id、version、score TINYINT 1-5、comment VARCHAR(512)、operator、created_at；索引 template_id+version）+ 实体 PromptTemplateFeedbackEntity（@AutoTable 与 DDL 对齐）+ PromptTemplateFeedbackMapper（MP BaseMapper）。V21 起头部注释格式。FlywayMigrationIT 登记V34（19→20）。
2. **revert 回滚端点**：`POST /promptTemplate/{id}/revert`（入参 targetVersion + expectedVersion，权限 809 MANAGE）——取该历史版本 content+variables 作为**新版本**落档（version 继续递增，history 落 operation=REVERT 并记 sourceVersion），审计链不破坏、源历史快照不动；乐观锁语义同 update（expectedVersion 不符→92001）；targetVersion 不存在于 history→92008 段业务异常。
3. **feedback 两端点**（权限 808 VIEW，使用面反馈）：
   - `POST /promptTemplate/{id}/feedback`：{version, score, comment}——version 必须存在于 history，score 越界（<1 或 >5）拒绝，comment 截断校验 ≤512；
   - `GET /promptTemplate/{id}/feedback/list`：分页（template_id 过滤，按 created_at 倒序）。
4. **既有端点增强**：无——history 列表现状已含全量快照字段（content/variables/operation/operator/operated_at），不新增详情端点（写明理由防误解）。
5. **测试**：ServiceTest（revert 三态：正常/目标版本不存在/乐观锁冲突；feedback：记录/越界 score/版本不存在）+ ControllerIT（revert 端到端含审计链验证：REVERT 后 history 链完整；feedback 记录+查询；权限正反向：VIEW 无 809 → revert 12002）+ SchemaDrift 面向新表零 CRITICAL。
6. **openapi.yaml** 契约同步（--update 通道）+ 本地 pnpm gen:api（不入库，生成物被 gitignore）。

## Impact

- 代码：V34 SQL + 实体/Mapper + Service/Controller 增强（revert/feedback 三端点 + 808/809 挂注解）+ DTO/VO + 测试 + openapi.yaml。
- 契约：不动 contracts-frozen.md；权限复用 808/809 零新增常量；registry 已登记 controller 级无需改（新端点由 PermissionCoverageScanner 全量扫挂注解）。
- 不做：嵌套引用 {{> code}}（后续卡）、段落级 section 元数据（后续卡）、使用级 usage 记录表（运行时集成卡再定）、前端页面。

## 拍板点

- D1 revert 语义：新版本落档（REVERT + sourceVersion，审计链不破坏）——推荐，不采用直接改写当前版本（破坏审计）
- D2 feedback 权限：808 VIEW（使用面）——建议；revert 用 809 MANAGE（治理面）
- D3 feedback 独立表（非 history 加列，保持快照表纯净）
- D4 score 1-5 + comment ≤512
- D5 不加 history 详情端点（列表已含全量字段）
