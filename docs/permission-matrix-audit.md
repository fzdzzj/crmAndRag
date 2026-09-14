# 全量端点权限矩阵审计报告（audit-permission-matrix）

> ⚠️ **建议映射未经用户拍板，禁止直接落地。** 本报告是"拍板输入"，40+ 端点挂注解 / 新增权限常量
> 属行为变更（角色访问面变化），**不在本提案预授权范围内**；用户对映射表拍板后另立提案执行。
> 本提案只交付：审计设施 + 永久门禁 + 本报告（零运行期行为变更，¥0 外发）。

- 审计时间：2026-09-13（扫描实测）
- 审计方式：ClassPath 静态扫描 `@RestController`/`@Controller`（`com.slz.crm`），读注解元数据不解析源码；
  门禁 `PermissionCoverageAuditIT` 无 Docker / 无 Spring 上下文 / 无外网依赖，本地与 CI 均真跑。
- 鉴权机制（口径）：方法级 `@RequirePermission` 由 `PermissionsInterceptor#preHandle` 执行，
  **注解缺失 = 拦截器直接放行**；类级注解不生效（`@Target(METHOD)`）。

## 1. 实测总览

| 项 | 摸底（2026-09-13 手工） | 实测（扫描首跑） | 差异说明 |
|---|---|---|---|
| controller 数 | 26 | **27** | 摸底漏计 `DynamicConfigAdminController`（platform/config） |
| 端点总数 | 227 | **208** | 摸底为手工估算；扫描实测为准 |
| SECURED（已挂注解） | 172（估算） | **146** | 实测为准 |
| INTENTIONAL_OPEN（有意开放） | 若干 | **5** | /login、/health、/public/**、getMyPermission、auditor |
| PENDING_DECISION（零注解待拍板） | 40 | **57** | 摸底 40 外**新发现 17**（User 6 / DynamicConfig 7 / template×2 / auditor / getMyRole） |
| CRITICAL（写裸奔未登记） | — | **0** | 首轮全登记，门禁放行（显式知情制） |

**四档分布：208 = SECURED 146 + INTENTIONAL_OPEN 5 + PENDING_DECISION 57 + CRITICAL 0 + WARN 0。**

摸底 40 的构成偏差：Assist 实为 23（非 22）、AiChat 实为 7（非 8），40 合计恰一致；摸底**漏掉了**
UserController、DynamicConfigAdminController、CustomerCompany/CustomerContact 模板端点、
RoleController#getMyRole 等 17 个零注解端点——这正是"无门禁时零注解静默合入"风险的实证。

## 2. 门禁语义（防再犯）

`PermissionCoverageAuditIT` 对每个端点强制三选一：① 方法级 `@RequirePermission`；② 登记
`OpenEndpointRegistry` INTENTIONAL_OPEN（附理由）；③ 登记 PENDING_DECISION（待拍板）。
任何端点不满足 → 写语义（POST/PUT/DELETE）判 CRITICAL 门禁直接红；读语义（GET/ANY）判 WARN，
**WARN 非空同样判 fail**（实现口径：零注解读端点若未登记进 PENDING/OPEN 即视为遗漏登记，一律报红）。
controller 扫描数 ≠ 登记数（27）→ 抛错红。新 controller 必须同步登记，防漏审。

## 3. 各 controller 端点矩阵（27 × 208）

| Controller | 端点数 | SECURED | INTENTIONAL_OPEN | PENDING_DECISION |
|---|---:|---:|---:|---:|
| AssistController | 23 | 0 | 0 | 23 |
| AiChatController | 7 | 0 | 0 | 7 |
| AiActionController | 4 | 0 | 0 | 4 |
| DataStatisticsController | 4 | 0 | 0 | 4 |
| ReportController | 2 | 0 | 0 | 2 |
| DynamicConfigAdminController | 7 | 0 | 0 | 7 |
| UserController | 11 | 4 | 1（/login） | 6 |
| CustomerCompanyController | 18 | 17 | 0 | 1 |
| CustomerContactController | 15 | 13 | 0 | 2 |
| RoleController | 4 | 3 | 0 | 1 |
| HealthController | 1 | 0 | 1 | 0 |
| PublicAttachmentController | 1 | 0 | 1 | 0 |
| PermissionController | 5 | 3 | 2 | 0 |
| BusinessActivityController | 12 | 12 | 0 | 0 |
| CompanyDeptController | 5 | 5 | 0 | 0 |
| CompanyGroupController | 5 | 5 | 0 | 0 |
| ContactTaskController | 15 | 15 | 0 | 0 |
| ContractController | 6 | 6 | 0 | 0 |
| ContractOrderItemController | 6 | 6 | 0 | 0 |
| InvoiceInfoController | 8 | 8 | 0 | 0 |
| PaymentRecordController | 8 | 8 | 0 | 0 |
| ProjectFileController | 9 | 9 | 0 | 0 |
| SalesOpportunityController | 7 | 7 | 0 | 0 |
| SalesStageApprovalController | 9 | 9 | 0 | 0 |
| SysDeptController | 5 | 5 | 0 | 0 |
| TaskCommentController | 8 | 8 | 0 | 0 |
| UserHandoverController | 3 | 3 | 0 | 0 |
| **合计** | **208** | **146** | **5** | **57** |

### 3.1 SECURED 146（合规，摘要）
含 `close-permission-read-gap` 三端点自然校验：`ANY /permission/list`、`POST /permission/addORDeletePermissionsToRole`、
`GET /permission/getByRole` 均落 SECURED 档（606，不回退）。其余 143 为 20 个 controller 既有注解端点
（客户/销售/财务/任务/系统/组织各模块），权限值覆盖 101–2295/301–4045 段，与本报告无关不做逐条展开。

### 3.2 INTENTIONAL_OPEN 5（有意开放，已登记）

| HTTP | 路径 | 理由 |
|---|---|---|
| POST | /user/login | JWT 层排除：登录接口匿名访问（publicPaths /login） |
| GET | /health | JWT 层排除：健康检查匿名探针（publicPaths /health） |
| GET | /public/attachment/download | JWT 层排除：公开附件下载（publicPaths /public/**，AES 令牌二次鉴权） |
| ANY | /permission/getMyPermission | 业务必需：用户自查自身权限（close-permission-read-gap 拍板） |
| GET | /permission/auditor | 业务必需：审批人下拉（close-permission-read-gap 拍板） |

## 4. 零注解端点映射建议（拍板输入，57 端点）

> 以下"建议映射"为**待拍板**，合入后门禁对 PENDING_DECISION 放行，落地由用户拍板后另立提案。

### 4.1 Assist / AiChat / AiAction（34 端点）——方案 A/B 两案并列

**方案A（权限细分）**：新增 800 段 AI 模块权限常量，按操作语义映射（示例）：
`AI_ASSIST_VIEW/APPLY/HANDLE`（协助）、`AI_CHAT_*`（会话）、`AI_ACTION_CONFIRM`（待执行确认）。
优点：与既有模块权限制式一致、可控访面；成本：新增常量 + 种植 + 角色分配。

**方案B（登录即可用）**：产品决策 AI 助手为全员基础能力（数据面已按会话归属/业务记录归属在服务层校验，
见 `AssistRequestService#canWriteAssistDelivery`、`AiSessionService#getOwnedSession`），
登记 INTENTIONAL_OPEN，仅保留"登录"门槛。优点：零权限种植成本；缺点：写操作（协助处理/确认执行）无功能权限面。

| HTTP | 路径 | 操作语义 | 方案A 建议 | 方案B 建议 |
|---|---|---|---|---|
| GET | /assist/my | 查我的被指派协助 | AI_ASSIST_VIEW | 登录即可用 |
| PUT | /assist | 协助人提交意见 | AI_ASSIST_HANDLE | 登录即可用 |
| GET | /assist/applications | 查我发起的协助 | AI_ASSIST_VIEW | 登录即可用 |
| POST | /assist/reapply | 驳回后重新申请 | AI_ASSIST_APPLY | 登录即可用 |
| POST | /assist/append | 追加协助人 | AI_ASSIST_APPLY | 登录即可用 |
| POST | /assist/apply | 发起协助申请 | AI_ASSIST_APPLY | 登录即可用 |
| GET | /assist/{id}/detail | 协助详情 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/messages | 过程消息 | AI_ASSIST_VIEW | 登录即可用 |
| POST | /assist/{id}/messages | 发送文本说明 | AI_ASSIST_APPLY | 登录即可用 |
| GET | /assist/{id}/related | 关联业务索引 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/opportunity | 关联商机详情 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/company | 关联客户公司 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/contact | 关联联系人 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/approval | 关联审批详情 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/activity | 关联业务活动 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/task | 关联联络任务 | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/activity/attachments | 来源附件（活动） | AI_ASSIST_VIEW | 登录即可用 |
| GET | /assist/{id}/task/attachments | 来源附件（任务） | AI_ASSIST_VIEW | 登录即可用 |
| POST | /assist/{id}/source-attachments | 上传来源附件 | AI_ASSIST_APPLY | 登录即可用 |
| DELETE | /assist/{id}/source-attachments | 删除来源附件 | AI_ASSIST_HANDLE | 登录即可用 |
| POST | /assist/{id}/attachments | 上传交付物 | AI_ASSIST_APPLY | 登录即可用 |
| GET | /assist/{id}/attachments | 交付物列表 | AI_ASSIST_VIEW | 登录即可用 |
| DELETE | /assist/{id}/attachments | 删除交付物 | AI_ASSIST_HANDLE | 登录即可用 |
| POST | /ai/chat/stream | SSE 流式对话 | AI_CHAT_STREAM | 登录即可用 |
| POST | /ai/chat/cancel | 取消生成 | AI_CHAT_CANCEL | 登录即可用 |
| POST | /ai/sessions | 建会话 | AI_CHAT_SESSION | 登录即可用 |
| GET | /ai/sessions | 会话列表 | AI_CHAT_SESSION | 登录即可用 |
| GET | /ai/sessions/{id}/messages | 消息历史 | AI_CHAT_SESSION | 登录即可用 |
| POST | /ai/sessions/{sessionId}/images | 上传聊天图片 | AI_CHAT_SESSION | 登录即可用 |
| DELETE | /ai/sessions/{id} | 归档会话 | AI_CHAT_SESSION | 登录即可用 |
| POST | /ai/actions/{pendingId}/confirm | **确认执行（创建客户/开票等）** | AI_ACTION_CONFIRM | 登录即可用（服务层按归属校验） |
| POST | /ai/actions/{pendingId}/cancel | 取消待确认操作 | AI_ACTION_CONFIRM | 登录即可用 |
| PUT | /ai/actions/{pendingId}/edit | 编辑草稿参数 | AI_ACTION_CONFIRM | 登录即可用 |
| GET | /ai/actions/{pendingId} | 查操作状态 | AI_ACTION_VIEW | 登录即可用 |

> 提示：`AiActionController#confirm` 是**敏感写操作**（按 action_type 运行时映射
> CREATE_ORDER→206 / CREATE_CONTRACT→212），若走方案B 需产品确认"待确认操作确认执行"不设功能权限面。

### 4.2 DataStatistics / Report（6 端点）——建议复用 501/502/503

| HTTP | 路径 | 操作语义 | 建议映射 | 备注 |
|---|---|---|---|---|
| GET | /report/contract | 签约合同数量 | REPORT_VIEW_REPORT（501） | 只读报表 |
| GET | /report/business | 商机数量 | REPORT_VIEW_REPORT（501） | 只读报表 |
| GET | /dataStatistics/opportunityStageDistribution | 商机阶段分布 | REPORT_VIEW_REPORT（501） | 只读图表 |
| POST | /dataStatistics/chartData | 图表 JSON 数据 | REPORT_GENERATE_REPORT（502） | 生成数据 |
| POST | /dataStatistics/chart | 生成图表图片（@Deprecated） | REPORT_GENERATE_REPORT（502） | 已废弃 |
| POST | /dataStatistics/summary | 综合统计 | REPORT_VIEW_REPORT（501） | 只读统计 |

**种植面现状（复用前提，需随下一提案处理）**：`permissions` 表 501/502/503/504 仅存在于
`PermissionOperates` 枚举，**零端点引用、零种植**——V1 建空表、V21 仅种组织权限（1174–4045）、
`init_data.sql` 仅种 id 1–10。复用 501/502/503 需在迁移/种子中补权限项并给角色授权（行为变更）。

### 4.3 审计新发现 17 端点（摸底遗漏）

| HTTP | 路径 | 操作语义 | 建议映射 |
|---|---|---|---|
| DELETE | /user | 删除用户 | SYSTEM_UPDATE_USER（603） |
| POST | /user/find | 条件查用户 | SYSTEM_VIEW_USER（602） |
| POST | /user/password | 改自己密码 | 登录即可用（自服务，建议 INTENTIONAL_OPEN） |
| POST | /user/update/my | 改自己信息 | 登录即可用（自服务，建议 INTENTIONAL_OPEN） |
| GET | /user/my | 查自己信息 | 登录即可用（自服务，建议 INTENTIONAL_OPEN） |
| GET | /user/options | 在职用户下拉 | 登录即可用（协助人选择器，建议 INTENTIONAL_OPEN） |
| GET | /company/template | 客户公司 Excel 模板 | 登录即可用 或 CUSTOMER_EXPORT_CUSTOMER_COMPANY（118） |
| GET | /contact/template | 联系人 Excel 模板 | 登录即可用 或 CUSTOMER_EXPORT_CONTACT（107） |
| GET | /contact/auditor | 联系人侧审批人下拉 | 登录即可用（与 /permission/auditor 同款） |
| GET | /role | 查自己角色 | 登录即可用（自查，建议 INTENTIONAL_OPEN） |
| GET | /platform/config/items | 配置项列表 | 服务层已强制 roleId=1（96005），建议 INTENTIONAL_OPEN（登录即可用+服务层闸） |
| GET | /platform/config/items/{key} | 配置项详情 | 同上 |
| GET | /platform/config/items/{key}/history | 版本历史 | 同上 |
| POST | /platform/config/items | 更新配置项 | 同上（写路径，服务层同闸） |
| POST | /platform/config/items/{key}/rollback | 回滚版本 | 同上 |
| DELETE | /platform/config/items/{key} | 软删配置项 | 同上 |
| POST | /platform/config/cache/refresh | 刷新缓存 | 同上 |

> 注：`DynamicConfigAdminController` 7 端点**无注解但服务层强校验**（`DynamicConfigAdminService` 对非
> roleId=1 抛 FORBIDDEN 96005）——属于"代码内校验"而非"拦截器注解"，审计如实呈现为 PENDING_DECISION，
> 建议后续统一为注解或显式登记 INTENTIONAL_OPEN，避免"双重鉴权机制"漂移。

## 5. 待拍板缺陷（记录，不在本提案修复）

### 5.1 冻结/离职用户绕过（`PermissionsInterceptor` L61-70）

状态检查（roleId=0 冻结 / roleId=2 离职）位于 `@RequirePermission` 判空**之后**——
**零注解端点连冻结/离职用户都可访问**。修复 = 把状态检查提到注解判空前（改 interceptor，属行为变更）。
本提案只记录，修复另立提案。

### 5.2 报表权限 501/502/503 零引用（常量↔端点双漂移）

`REPORT_VIEW_REPORT(501)` / `REPORT_GENERATE_REPORT(502)` / `REPORT_EXPORT_REPORT(503)` /
`REPORT_MANAGE_TEMPLATE(504)` 存在于枚举却无任何端点引用、无任何种植（见 §4.2）——
与 DataStatistics/Report 6 个报表端点形成"常量在、端点裸奔"的双侧漂移。拍板方向见 §4.2。

## 6. 拍板记录（apply-permission-matrix，2026-09-14 用户已拍板）

> 以下将 §4 全部"建议映射"正式落地为真实鉴权。落地细节见提案
> `openspec/changes/apply-permission-matrix/proposal.md` 与产出 `V26__permission_seed.sql`。

| # | 决策点 | 拍板结论 | 落地方式 |
|---|---|---|---|
| D1 | AI 模块 34 端点（Assist 23 / AiChat 7 / AiAction 4） | **方案A：新增 800 段权限常量 + 挂注解 + 种植授权** | `PermissionOperates` 新增 800-807；三 AI controller 按 §4.1 方案A 列逐方法挂注解；V26 种 `permissions` 800-807 并授权 |
| D2 | 报表/统计 6 端点（Report 2 / DataStatistics 4） | **复用 501/502/503 并 V26 种植激活** | Report 挂 501；DataStatistics 按 501/502 挂；V26 补种 501-504 并授权 |
| D3 | 冻结/离职绕过修复 | **纳入本提案** | `PermissionsInterceptor` 状态检查前置到注解判空之前 + 反向 IT |

**两个工程默认（随本提案一并生效）：**

1. **角色授权策略 = 保持现状访问面**：V26 给全部现有业务角色授权新权限项（超管 roleId=1 由 interceptor
   直通无需授权行；roleId=0 冻结 / roleId=2 离职特殊角色不授）——建立可管理权限面的同时不破坏任何现有角色
   访问（向后兼容硬约束）；后续管理员可经 606 权限管理路径按需收紧。
2. **`GET /company/template` / `GET /contact/template` 转 INTENTIONAL_OPEN**（不挂 118/107）：模板是静态资源
   无数据暴露面；挂导出权限会把"登录可下模板"收紧为"有导出权限才可下"，可能破坏 Excel 导入流程。

**消解结果**：57 条 PENDING_DECISION 全消解——42 条挂注解转 SECURED（SECURED 146→188：
AI 34 + 报表 6 + `DELETE /user`/`POST /user/find` 2）+ 15 条转 INTENTIONAL_OPEN（OPEN 5→20）；
PENDING_DECISION 57→0。§4 建议映射不再待拍板。

## 7. OPTIONS 与已知口径

- OPTIONS（CORS 预检）：`JWTInterceptor` 直接放行（`preHandle` 返回 false 不报错）、CORS `allowedMethods`
  含 OPTIONS——合理，无需处理，审计不产生 OPTIONS 端点。
- 静态扫描拼接路径与 Spring 运行期路由可能有细微差异（consumes/produces/多路径）——审计只关心
  "有没有注解"，路径仅用于报告展示与登记匹配；如后续发现拼接歧义按本报告定夺。
- 数据权限（DataScope AOP 面）不在本审计范围（只管功能权限方法级注解覆盖）。

---

> 拍板已由用户在 2026-09-14 完成（见 §6），§4 建议映射已由 `apply-permission-matrix` 落地；
> 本节（§6/§7）为产权与口径说明，不再构成"待拍板输入"。

> ⚠️ **再次强调：本报告的"建议映射"均为待拍板输入，未经用户拍板，禁止直接落地。**
> 落地（挂注解 / 新增 800 段常量 / 种植权限）属下一提案，执行方必须停下等用户拍板。
