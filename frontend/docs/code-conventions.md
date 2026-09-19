# Code Conventions

## Styling — Tailwind CSS Only

Do NOT use `<style>` tags in Vue components. Use Tailwind CSS utility classes instead.

Examples:
- Instead of `<div class="user-list-container">` with scoped styles, use `<div class="p-4">`
- Instead of `<div class="pagination-wrapper">` with flexbox styles, use `<div class="mt-4 flex justify-end">`
- Common classes: `p-4` (padding), `m-2` (margin), `flex` (display flex), `justify-end`, `gap-2`

## Component Naming — PascalCase

Always use PascalCase (大驼峰) for component names, NEVER use kebab-case (短横线):
- ✅ CORRECT: `CreateSaleModal`, `StatisticsChart`, `ViewInvoiceModal`
- ❌ WRONG: `create-sale-modal`, `statistics-chart`, `view-invoice-modal`

This applies to both file names and component imports.

## API Calls — Must Use Hooks

**NEVER call API directly in components.** Always wrap API calls in custom hooks under `src/hooks/`. This provides consistent error handling, loading state management, data caching, and reusable business logic.

❌ **WRONG** — Direct API call in component:
```vue
<script setup lang="ts">
import apiClient from '@/api/apiClient';

const fetchDetail = async (id: number) => {
  const res = await apiClient.invoice.infoDetail(id);
  // ...
};
</script>
```

✅ **CORRECT** — Using a hook:
```vue
<script setup lang="ts">
import { useInvoiceDetail } from '@/hooks/useFinance';

const { data, isLoading } = useInvoiceDetail(invoiceId);
</script>
```

If a hook doesn't exist for the API operation you need, create it in the appropriate domain hook file (e.g., `useFinance.ts`, `useCompany.ts`, etc.).

## Component Imports

After writing or modifying a Vue component, ALWAYS verify that all components used in the `<template>` are properly imported in the `<script setup>` section. Common forgotten imports include:
- Ant Design Vue components: `Spin`, `Select`, `SelectOption`, `Modal`, etc.
- Custom components from the project

## Date Format

All date/time fields must use the format `yyyy-MM-dd HH:mm:ss` (e.g., "2024-01-15 14:30:00").
Error message: "日期格式错误，期望格式：yyyy-MM-dd HH:mm:ss"

## Playwright 测试检查点

每次新增、修改或删除功能代码时，必须同步在 `docs/test-checkpoints.md` 中记录测试检查点。

**规则：**
- **不需要立即运行 Playwright**，只需记录检查点
- 检查点累积到一定数量或用户要求时，统一调用 `/test-crm` 批量验证
- 已通过的检查点标记为 `[x]`，未执行的标记为 `[ ]`

**检查点格式：**
```markdown
- [ ] [日期] 页面：`/路由` | 操作：描述具体操作和预期结果
```

**示例：**
```markdown
- [ ] 2026-04-13 页面：`/sale/list` | 点击「创建销售订单」按钮，应弹出创建表单 Modal
- [ ] 2026-04-13 页面：`/finance` | 切换到「回款列表」Tab，表格应正确加载回款数据
- [x] 2026-04-13 页面：`/sale/list` | 表格数据正常加载，侧边栏菜单可点击 | 已验证
```
