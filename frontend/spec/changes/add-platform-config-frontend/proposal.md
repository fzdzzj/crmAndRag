# 提案：平台动态配置管理页（接 /platform/config）

## Why

后端 `DynamicConfigAdminController`（`/platform/config`）已提供动态配置管理能力（主仓 `DynamicConfigKeyRegistry` 五命名空间：键、类型、默认值、范围、影响面描述齐全），是后端**唯一已就绪**的治理端点。但前端 `openapi.yaml` 未含该端点，管理员调整配置只能直连数据库或裸调接口，且看不到键的影响面描述，误配风险高。

## What Changes

- **前置核验（阻塞点）**：核对后端 `DynamicConfigAdminController` 的权限状态——若端点缺 `@RequirePermission` 或未在权限矩阵登记，**停下回主仓补**（走 permission-matrix 处置契约），不在前端绕过、不裸奔上线。
- **契约同步**：从后端 `/v3/api-docs.yaml` 同步 `openapi.yaml`（补 `/platform/config`），执行 `pnpm gen:api`，生成目录禁手改。
- **hooks**：新增 `src/hooks/usePlatformConfig.ts`（配置键列表/搜索、读取当前值、修改保存），Vue Query 封装，组件内禁止直接调 API。
- **页面**：新增配置管理页（挂 privilege 域 tab 或独立路由 `platform-config`）：
  - 按命名空间分组的键列表 + 关键字搜索；
  - 每行展示：键名、类型、当前值、默认值、范围、**影响面描述**；
  - 修改交互：按类型出表单控件（Boolean 开关 / Integer 数字输入含范围校验 / String 文本），保存前确认弹窗回显「旧值 → 新值 + 影响面描述」。
- **规范**：Tailwind-only、PascalCase、Playwright 检查点记 `docs/test-checkpoints.md`；hooks 补 Vitest。

## Impact

### 受影响的规范

- 新增 `spec/specs/platform-config-frontend/spec.md`。

### 受影响的代码

- `openapi.yaml`、`src/api/axios/`（生成）
- `src/hooks/usePlatformConfig.ts`（新增）
- 配置管理页面组件（新增）
- `docs/test-checkpoints.md`

### API 变更

- 前端不新增端点；消费后端 `/platform/config`。

### 需要迁移

- [ ] 数据库迁移：否
- [ ] API 版本提升：否
- [ ] 用户沟通：否
- [ ] 文档与测试检查点更新：是

## 时间线评估

小到中等工作量：核验+契约 0.5 天，hooks+页面 1 天，测试与检查点 0.5 天。

## 风险

- **动态配置是生产敏感操作**：改错直接影响检索/模型行为——确认弹窗必须带影响面描述与新旧值对照，Boolean 开关防误触。
- 后端权限缺口：若核验发现裸奔端点，本提案阻塞，回主仓处置（这是故意的，避免前端先行放大风险）。
- 值类型与后端 `ConfigValueType` 不匹配 → 以契约生成为准，不做手写类型。
