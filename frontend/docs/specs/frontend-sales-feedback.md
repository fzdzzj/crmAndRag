# Spec: 销售反馈前端优化（不动后端）

## Objective

基于 `CRM系统问题汇总（销售反馈）.xlsx` 中的销售反馈，在不改动后端代码的前提下，完成所有纯前端可落地的优化项，提升销售团队的使用体验。

### 用户故事

- 作为销售人员，我希望客户等级表头有明确说明，以便理解 0-9 星分别代表什么含义。
- 作为销售人员，我希望销售订单的"申请推进"入口更明显，避免找不到推进按钮。
- 作为销售人员，我希望业务活动模块有清晰的定义说明，避免不理解该填什么。
- 作为销售人员，我希望在手机端使用时页面不拥挤、目录栏不遮挡内容。
- 作为销售人员，我希望部分非关键字段不必强制填写，降低录入门槛。

### 本次 Scope（纯前端，不动后端）

| 编号 | 反馈 | 前端改动 |
|------|------|----------|
| 1 | 客户公司等级判定描述不清晰 | 客户列表「级别」表头增加 tooltip/hover 提示 |
| 3 | 销售订单申请推进不够明显 | 销售列表「推进"按钮视觉强化，增加 tooltip |
| 5 | 业务活动定义不够清晰 | 业务活动页面/表头增加说明提示 |
| 7/8 | 手机端页面不友好、目录栏遮挡 | 移动端侧边栏默认折叠、表格/表单响应式适配 |
| 9 | 部分字段强制填写影响积极性 | 降低部分表单字段的必填校验 |

### 排除在本次 Scope 之外（需后端配合）

| 编号 | 反馈 | 原因 |
|------|------|------|
| 2 | 客户列表增加部门列 | 后端 `CustomerCompanyVO` 无部门/区域字段 |
| 4 | 客户列表增加联系人列 | 后端 `CustomerCompanyVO` 无联系人字段 |
| 6 | 项目交付流程与实施衔接 | 需后端新增合同关键节点、交付状态等字段 |
| 10 | 权限隔离 | 需后端数据权限与共享规则调整 |
| 12 | 发票列表增加客户名称、备注 | 需后端 `InvoiceInfoVO` 返回客户名称 |
| 14/15 | 协助申请功能 | 需后端新增协助申请模型与审批流 |

## Tech Stack

- Vue 3 (Composition API + `<script setup>`)
- TypeScript
- Ant Design Vue 4.x
- Tailwind CSS 4.x
- @tanstack/vue-query
- @hey-api/client-axios（基于 `openapi.yaml` 生成）

## Commands

```bash
# 安装依赖并生成 API client（当前 src/api/axios 缺失，必须先执行）
pnpm install && pnpm gen:api

# 本地开发
pnpm dev

# 类型检查
pnpm type-check:app

# 构建
pnpm build

# 代码检查
pnpm lint:check
```

## Project Structure

```
crm/
├── docs/specs/                    # 规格说明书（本文件）
├── src/
│   ├── api/                       # API 客户端配置
│   │   ├── axios/                 # 自动生成的 API client（gitignore，需 pnpm gen:api）
│   │   ├── apiClient.ts
│   │   └── config.ts
│   ├── components/
│   │   ├── client/                # 客户相关组件
│   │   ├── sale/                  # 销售相关组件
│   │   └── activity/              # 业务活动组件
│   ├── pages/(dashboard)/
│   │   ├── client/list.page.vue   # 客户列表
│   │   ├── sale/list.page.vue     # 销售订单列表
│   │   └── contact/activity.page.vue  # 业务活动
│   ├── constants/                 # 常量、列定义
│   └── hooks/                     # Vue Query hooks
└── openapi.yaml                   # 后端 OpenAPI 规范
```

## Code Style

- 组件使用 Composition API + `<script setup>`。
- 列表列定义统一放在 `src/constants/` 下，页面引入。
- Tooltip/提示优先使用 Ant Design Vue 的 `<a-tooltip>` 或 `buildFilterColumn` 的 `tooltip` 配置。
- 响应式适配优先使用 Tailwind CSS 的响应式前缀（`md:`, `lg:`）。
- 不动后端，新增字段需求需记录为"待后端配合"，不在本次提交中硬编码不存在的 API 字段。

### 示例：表头增加 tooltip

```vue
<!-- 方式一：使用列配置的 tooltip -->
buildFilterColumn({
  title: '级别',
  dataIndex: 'grade',
  tooltip: '0星=潜在客户，9星=核心客户',
})

<!-- 方式二：使用 renderTitle 自定义表头 -->
{
  title: () => h(
    'span',
    {},
    h(ATooltip, { title: '0星=潜在客户，9星=核心客户' }, () => '级别')
  ),
  dataIndex: 'grade',
}
```

## Testing Strategy

- 本次优化以 UI/交互调整为主，以手动验证为主。
- 类型检查：`pnpm type-check:app` 必须通过。
- 构建检查：`pnpm build` 必须通过。
- 手动验证清单见下方 Success Criteria。

## Boundaries

### Always
- 只改动前端代码，不修改 `crm-back/` 目录下的任何文件。
- 新增 UI 文本/提示需要中文文案，与现有系统保持一致。
- 改动后必须能执行 `pnpm type-check:app` 无错误。

### Ask first
- 如果需要新增依赖。
- 如果需要修改 `openapi.yaml` 或重新生成后字段不一致。
- 如果涉及后端字段新增（超出本次 scope）。

### Never
- 硬编码后端不存在的字段到表单提交中。
- 直接修改 `.gitignore` 中 `src/api/axios` 的忽略规则（可通过生成脚本解决）。
- 提交生成后的 `src/api/axios/` 目录。

## Success Criteria

- [x] 客户列表「级别」表头 hover 时显示分级定义 tooltip。
- [x] 销售订单列表「推进」按钮使用更显眼的样式（`type="primary"`、`size="small"` 并带图标），并带 tooltip 说明。
- [x] 业务活动页面/表头有清晰的定义说明（tooltip 或提示文字）。
- [x] 移动端访问时，侧边导航默认折叠，主内容区不溢出；表格支持横向滚动。
- [x] 客户创建表单中，仅保留公司名称必填，并增加提示说明其他信息可后续补充。
- [x] `pnpm type-check:app` 通过。
- [x] `pnpm build` 通过。

## Open Questions

1. **分级定义文案**：0-9 星分别代表什么含义？需要销售/产品确认。临时文案可写为"0星=潜在客户，9星=核心客户"，但需替换为正式定义。
2. **必填字段范围**：除了公司名称，还有哪些字段必须保留必填？（如客户属性、公司等级是否必填？）
3. **移动端优化范围**：是否只需要侧边栏折叠和表格横向滚动，还是需要对登录页、表单页也做响应式调整？
4. **业务活动提示文案**："业务活动主要记录关键拜访及技术交流等，作为进入下一阶段的关键动作"是否可直接作为 tooltip 文案？
