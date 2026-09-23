# 提案：端点权限矩阵映射落地（57 端点挂注解/登记 + 800 段常量 + 报表权限种植 + 冻结绕过修复）

> 变更 ID：`apply-permission-matrix` ｜ 能力域：`crm-permission` ｜ 序列：`audit-permission-matrix` 的直接后续（依赖其已合入门禁与报告）
> 来源：`audit-permission-matrix` 交付了永久门禁与拍板输入（`docs/permission-matrix-audit.md`），57 个零注解端点登记在 PENDING_DECISION 区——"登录用户即可调用、写语义含 AI 动作确认等敏感面"的现状未消除。本提案把拍板结论落地为真实鉴权。

## 拍板记录（2026-09-14 用户已拍板，决策门通过）

| # | 决策点 | 拍板结论 | 落地方式 |
|---|---|---|---|
| D1 | AI 模块 34 端点（Assist 23 / AiChat 7 / AiAction 4） | **方案A：新增 800 段权限常量 + 挂注解 + 种植授权** | `PermissionOperates` 新增 800-807；三 controller 逐方法挂注解；V26 种权限项并授权 |
| D2 | 报表/统计 6 端点（Report 2 / DataStatistics 4） | **复用 501/502/503 并 V26 种植激活** | Report 挂 501；DataStatistics 按 501/502 挂；V26 补种 501-504 |
| D3 | 冻结/离职绕过修复 | **纳入本提案** | `PermissionsInterceptor` 状态检查前置到注解判空之前 + 反向 IT |

**两个工程默认（随本提案一并生效，汇报中明示）：**
1. **角色授权策略 = 保持现状访问面**：V26 给全部现有业务角色授权新权限项（超管 roleId=1 由 interceptor 直通无需授权行；roleId=0 冻结 / roleId=2 离职特殊角色不授）——建立可管理权限面的同时不破坏任何现有角色访问（向后兼容硬约束）；后续管理员可经 606 权限管理路径按需收紧。
2. **`GET /company/template` / `GET /contact/template` 转 INTENTIONAL_OPEN**（不挂 118/107）：模板是静态资源无数据暴露面；挂导出权限会把"登录可下模板"收紧为"有导出权限才可下"，可能破坏 Excel 导入流程（能导入者未必有导出权限）。

## Why

1. **57 端点登录即可调，写语义含敏感面**：`AiActionController#confirm` 可执行"创建客户/开票"类动作；`AssistController` 写路径（apply/append/reapply/handle/附件增删）无任何功能权限面；`DataStatistics/Report` 统计端点零鉴权。映射表已产出（报告 §4），拍板已完成，缺的是落地。
2. **800 段（AI 模块）空闲**：`PermissionOperates` 枚举 501-504/1174+ 已有既有段，800 段从未使用——新增不与既有 id 冲突。
3. **报表权限常量↔端点双漂移**：501/502/503 存在于枚举、零端点引用、零种植（报告 §5.2）——复用 + V26 种植即双向消除。
4. **冻结/离职绕过是审计确认的缺陷**（报告 §5.1）：状态检查（roleId=0 冻结 / roleId=2 离职）在注解判空之后，零注解端点连冻结用户都能访问——同轮修复。

## What Changes

### 1. AI 模块 34 端点（D1 方案A）
- `PermissionOperates` 新增 800 段常量：`AI_ASSIST_VIEW(800)` / `AI_ASSIST_APPLY(801)` / `AI_ASSIST_HANDLE(802)` / `AI_CHAT_SESSION(803)` / `AI_CHAT_STREAM(804)` / `AI_CHAT_CANCEL(805)` / `AI_ACTION_VIEW(806)` / `AI_ACTION_CONFIRM(807)`；
- `AssistController`/`AiChatController`/`AiActionController` 按报告 §4.1 映射表逐方法挂方法级 `@RequirePermission`（类级不生效）；中文 Javadoc 标注「apply-permission-matrix 任务 1.x」；
- V26 种 `permissions` 800-807 + `role_permission` 给全部业务角色授权（见拍板记录工程默认 1）。

### 2. 报表/统计 6 端点（D2 复用）
- `ReportController`：contract/business 挂 `REPORT_VIEW_REPORT(501)`；
- `DataStatisticsController`：opportunityStageDistribution/summary 挂 501；chartData/chart 挂 `REPORT_GENERATE_REPORT(502)`；
- V26 同脚本补种 `permissions` 501-504 + 授权（同工程默认 1）。

### 3. 新发现 17 端点（报告 §4.3）
| 端点 | 落地方式 |
|---|---|
| `DELETE /user` | 挂 `SYSTEM_UPDATE_USER(603)` |
| `POST /user/find` | 挂 `SYSTEM_VIEW_USER(602)` |
| `POST /user/password` / `POST /user/update/my` / `GET /user/my` / `GET /user/options` | 转 INTENTIONAL_OPEN（自服务/协助人选择器，附理由） |
| `GET /company/template` / `GET /contact/template` | 转 INTENTIONAL_OPEN（工程默认 2：静态模板，导入流程依赖） |
| `GET /contact/auditor` / `GET /role` | 转 INTENTIONAL_OPEN（自查/下拉，与 `/permission/auditor` 同款） |
| `DynamicConfigAdminController` 7 端点 | 转 INTENTIONAL_OPEN（服务层已强制 roleId=1 抛 96005，登记避免双重鉴权漂移） |

### 4. 冻结/离职绕过修复（D3 纳入）
- `PermissionsInterceptor`：roleId=0 冻结 / roleId=2 离职状态检查移到 `@RequirePermission` 判空之前（任何登录请求先过状态闸，再判注解）；
- 新增反向 IT：冻结/离职用户调零注解端点（如 `/user/my`）被拒；在职用户调零注解端点仍放行（现状口径防回归）；
- 既有 `PermissionControllerIT`、助手相关 IT 回归全绿。

### 5. 门禁消解与收尾
- `OpenEndpointRegistry`：PENDING_DECISION 57 条全部消解（40 挂注解转 SECURED + 17 转 INTENTIONAL_OPEN），PENDING_DECISION 区清空；
- `PermissionCoverageAuditIT` 全绿：SECURED 146→186 / INTENTIONAL_OPEN 5→22 / PENDING_DECISION 57→0；
- 修正 `docs/permission-matrix-audit.md`：§1/§4.3「新发现 22」→ 17 笔误、§2 WARN 语义描述与实现对齐（WARN 非空即 fail 是实现口径）；新增 §6 拍板记录（D1/D2/D3 结论 + 两个工程默认）；
- `FlywayMigrationIT.EXPECTED_VERSIONS` 加 '26'；`HANDOFF.md`/AGENTS.md 更新；`ci.yml` 基线三处同步（surefire 615 → 615+N）。

## Impact

- **新增**：`V26__permission_seed.sql`（permissions 12 行 + role_permission 授权）；`PermissionOperates` 800 段 8 常量；`PermissionsInterceptor` 状态检查前置；端点注解约 40 处；冻结绕过反向 IT；OpenEndpointRegistry 移区。
- **修改**：`OpenEndpointRegistry`、`PermissionsInterceptor`、三 AI controller + Report/DataStatistics/User 两处、`FlywayMigrationIT`、`ci.yml`、`HANDOFF.md`、AGENTS.md、`docs/permission-matrix-audit.md`。
- **不改**：`WebMvcConfiguration` 路由与排除配置、`init_data.sql`（共享种子禁改，用 in-test seeding）、`PermissionController` 既有 3 处 606 注解、既有 V1..V25 迁移。

## 风险

- **挂注解=角色访问面变化**：本提案以"V26 全量业务角色授权"保持现状访问面（工程默认 1），若授权 SQL 角色口径写错会大面积撞 12002——测试必须覆盖非超管角色（已授权）正向 + 未授权反向；
- **状态检查前置**：可能暴露既有"零注解端点靠状态检查缺位兜底"的隐式依赖——回归必须覆盖正常路径（在职用户调零注解端点仍放行）；
- **V26 id 冲突**：501-504 与 800-807 需确认真库 permissions 表现状（V1 空表/V21 种 1174+/init_data 种 1-10，理论上不撞；执行时以 SELECT 校验为准）；
- **IT 环境权限数据**：H2 测试库权限来自 init_data.sql（仅 1-10）——新注解端点的 IT 必须 in-test seeding 权限行，禁改共享种子。

## Non-Goals

- 不处理 DataScope 数据权限（AOP 面）。
- 不新增 608/其他"借用"常量：AI 只走 800 段，报表只复用 501/502/503。
- 不改任何端点路径/HTTP 方法/业务逻辑；不引入新鉴权机制。
- 不做生产权限表实数据核对（本地无生产数据）；无外发调用（¥0，跑 IT 前设 DASHSCOPE_API_KEY 空）。
