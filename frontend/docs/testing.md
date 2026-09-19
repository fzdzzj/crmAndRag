# Testing Guide

## Playwright CLI

本项目使用 `playwright-cli` 进行浏览器自动化测试。

### 启动有头浏览器（推荐）

```bash
# 启动有头浏览器 + 持久化状态
playwright-cli open --headed --persistent http://localhost:5173/CRM/#/login
```

### 登录测试

```bash
# 打开登录页
playwright-cli open --headed --persistent http://localhost:5173/CRM/#/login

# 填写表单
playwright-cli fill e11 "$CRM_TEST_EMAIL"
playwright-cli fill e12 "$CRM_TEST_PASSWORD"

# 点击登录
playwright-cli click e13

# 保存登录状态（复用免登录）
playwright-cli state-save crm-auth.json

# 加载已保存的登录状态
playwright-cli state-load crm-auth.json
```

### 页面导航

```bash
# 导航到各页面（需等 2s 让 Vue 渲染完成）
playwright-cli goto "http://localhost:5173/CRM/#/sale/list"
playwright-cli run-code "async page => { await page.waitForTimeout(2000); return 'ok'; }"
playwright-cli snapshot
```

### 页面 URL 路由表

| 页面 | URL | 状态 |
|------|-----|------|
| 登录 | `/#/login` | 正常 |
| 客户列表 | `/#/client/list` | 路由冲突（重复 route name） |
| 联系人管理 | `/#/client/contacts` | 路由冲突 |
| 销售订单 | `/#/sale/list` | 正常 |
| 销售合同 | `/#/sale/contract` | 正常 |
| 阶段审批 | `/#/sale/stage-approval` | 正常 |
| 财务管理 | `/#/finance` | 正常（Tab: 发票列表/回款列表） |
| 联络任务 | `/#/contact/task` | 正常 |
| 业务活动 | `/#/contact/activity` | 正常 |
| 统计报表 | `/#/statistics` | 正常 |
| 角色管理 | `/#/privilege/role` | 正常 |
| 用户管理 | `/#/privilege/user` | 正常 |

### 侧边栏菜单结构

登录后可见侧边栏，结构如下：

| 菜单项 | 跳转 |
|--------|------|
| 客户管理 | `/#/client` → `/#/client/list` |
| 销售管理 | `/#/sale` → `/#/sale/list` |
| 财务管理 | `/#/finance` |
| 联络任务 | `/#/contact` → `/#/contact/task` |
| 统计报表 | `/#/statistics` |
| 权限管理 | `/#/privilege` → `/#/privilege/role` |

### 已知问题

- `/client/list` 和 `/client/contacts` 路由不匹配，原因是 `src/pages/(dashboard` 目录（缺少闭合括号）导致路由重复注册。`typed-router.d.ts` 中存在两条 `clientList` 路由。
- 页面导航后需等待约 2 秒让 Vue 组件渲染完成，再执行 snapshot。
