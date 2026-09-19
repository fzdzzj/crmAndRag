# CRM 前端对接变更文档 - 开发上下文

> 基于 `crm-back/git-logs/frontend-changelog.md`
> 变更范围：`684b06e` → `af0755d`（HEAD），共 22 个提交
> 生成日期：2026-04-02

---

## 代码库架构概览

```
crm-front/src/
├── api/
│   ├── axios/Api.ts        # 自动生成的 API 客户端（swagger-typescript-api）
│   ├── apiClient.ts         # Api 实例 + 拦截器（token header = "token"）
│   ├── httpClient.ts        # axios 实例（旧 service 层使用）
│   ├── config.ts            # API 基础配置
│   └── types.ts             # ApiResponse, ApiError
├── service/                 # 旧版 Service 层（httpClient 调用）
│   ├── company.ts           # CompanyListItem, addCompanyParams
│   ├── contact.ts           # 联系人 Service
│   ├── sales.ts             # SalesListItem
│   ├── contract.ts          # 合同 Service
│   └── user.ts              # 用户 Service
├── hooks/                   # Vue Query hooks（新架构，用 apiClient）
│   ├── useCompany.ts        # CompanyFilterType 含 source（需改）
│   ├── useContact.ts        # ContactSearchFilters 含 remark（需改）
│   ├── useFinance.ts        # 开票/回款 hooks
│   ├── useContract.ts       # 合同 hooks
│   └── useAuth.ts           # 认证
├── components/              # UI 组件（按模块分目录）
│   ├── client/              # CompanyCreateModal, CompanyEditModal
│   ├── contact/             # CreateContactTaskModal 等
│   ├── finance/             # CreateInvoiceModal, EditInvoiceModal, ViewInvoiceModal
│   ├── sale/                # CreateSaleModal, FileUploadModal
│   ├── statistics/          # CustomerDis（客户分组统计）
│   └── user/                # CreateUserModal, EditUserModal
├── pages/
│   └── (dashboard)/         # 路由页面
├── constants/               # 常量定义
└── utils/
    └── token.ts             # TokenManager（localStorage key = "token"）
```

**关键模式**：
- API 调用通过 `apiClient` (Api.ts 生成的类) 或旧版 `httpClient`（service/*.ts）
- hooks 封装所有 API 调用，组件不直接调用 API
- 组件使用 Ant Design Vue + Tailwind CSS
- JWT token header 名已确认为 `"token"`

---

## 变更任务分解

### 任务 1：客户公司模块
**提交**: `d92badf` → `7008348` → `f594951` → `24db803` → `addc58e` → `af0755d`

**需修改的文件**:
- `src/api/axios/Api.ts` — `CustomerCompanyDTO` 删除 `source`，新增 `customerType`/`belongGroup`；`CustomerCompanyVO` 同理；`customList` 查询参数更新；新增 `groupFindList` 方法
- `src/service/company.ts` — `CompanyListItem` 删除 `source`，新增字段；`addCompanyParams` 同理
- `src/hooks/useCompany.ts` — `CompanyFilterType` 删除 `source`，新增 `customerType`/`belongGroup`；`UpdateCompanyParams` 同理
- `src/components/client/CompanyCreateModal.vue` — 表单字段替换
- `src/components/client/CompanyEditModal.vue` — 表单字段替换
- `src/pages/(dashboard)/client/list.page.vue` — 列表列/筛选条件更新
- `src/components/statistics/CustomerDis.vue` — 分组字段 `source` → `customerType`

**关键变更**:
- `source` (String) → 删除
- `customerType` (String) → 新增，可选值 `"代理"` / `"直销"`，注意不是数字
- `belongGroup` (String) → 新增，归属集团
- `grade` → 范围改为 0-9（之前可能是 0-5 或无限制）
- 新增 `GET /company/group/find` 按集团模糊查询
- `/company/custom` 条件查询中 `source` → `customerType`(精确) + `belongGroup`(模糊)
- `/company/group` 分组统计中 `field=source` → `field=customerType`

### 任务 2：客户联系人模块
**提交**: `f496d0b` → `d594a78` → `438cb5c` → `ecd88d5` → `c205fa8` → `b273799`

**需修改的文件**:
- `src/hooks/useContact.ts` — `ContactSearchFilters` 删除 `remark`，新增 `relationLevel`/`dept` 相关
- `src/api/axios/Api.ts` — `CustomerContactDTO` 已有 `dept`/`relationLevel`/`remarks`（已更新！）；`CustomerContactVO` 同理；新增生日提醒方法
- 联系人创建/编辑表单组件 — 新增 `relationLevel`、`dept` 输入控件；`remark` → 多类型 `remarks` 列表

**关键变更**:
- `remark` (单个文本) → 删除，迁移到 `remarks` (多类型备注数组)
- `relationLevel` (TINYINT 1-9) → 新增，客户关系等级
- `dept` (String) → 新增，部门
- 新增 `GET /contact/birthday-message/{id}` 生日提醒接口
- 备注类型：1-喜好、2-住址、3-本人出生日期、4-亲属出生日期、5-自定义

**注意**: Api.ts 中类型已更新（`CustomerContactDTO` 已包含 `dept`, `relationLevel`, `remarks`），但组件和 hooks 尚未适配

### 任务 3：用户交接模块（全新功能）
**提交**: `fa90977`

**需创建的文件**:
- `src/hooks/useHandover.ts` — 新 hooks
- 用户交接 UI 组件 — 执行交接对话框、交接记录列表、统计卡片

**需修改的文件**:
- `src/pages/(dashboard)/privilege.page.vue` 或用户管理页面 — 添加交接入口
- 路由配置 — 如需独立页面

**API 已在 Api.ts 中就绪**:
- `user.handoverExecuteCreate(data: UserHandoverDTO)` — 执行交接
- `user.handoverQueryCreate(data: UserHandoverQueryDTO)` — 查询记录
- `user.handoverStatisticsDetail(userId: number)` — 统计

**类型已定义**: `UserHandoverDTO`, `UserHandoverVO`, `UserHandoverQueryDTO`, `HandoverStatisticsVO`

### 任务 4：开票信息 + 销售审批 + VO 增强
**提交**: `d594a78`（开票）、`c78e06e`→`083ce1f`（审批）、`042197b`/`5b3ef8d`（VO）

**4a. 开票信息**:
- `src/api/axios/Api.ts` — `InvoiceInfoDTO`/`InvoiceInfoVO` 新增 `remark` 字段
- `src/components/finance/CreateInvoiceModal.vue` — 添加备注输入
- `src/components/finance/EditInvoiceModal.vue` — 添加备注输入
- `src/components/finance/ViewInvoiceModal.vue` — 显示备注

**4b. 销售机会审批**:
- `src/components/sale/FileUploadModal.vue` — 审批表单：新增必填 `message`(审批备注)，附件改为可选
- `src/api/axios/Api.ts` — `SalesStageApprovalANDAttachmentDTO` 新增 `message` 字段

**4c. VO 字段增强**（纯新增，向后兼容）:
- `ContractVO` 新增 `ownerName`, `creatorName`
- `CustomerContactVO` 新增 `creatorName`（对应 `createId`）
- `SalesStageApprovalVO` 新增 `approverName`（对应 `approverId`）

---

## 无需前端适配的变更

| 提交 | 说明 |
|------|------|
| `5c482de` | AOP 切面自动绑定数据权限标签 |
| `e9bdd30` | Merge commit |
| `ecd88d5` | 闰年生日计算 bug 修复 |
| `c205fa8` | 联系人性别字段 null 处理优化 |
| `b2a7212`/`c51bbb5` | 审批人查询 N+1 性能优化 |
| `7008348` | 归属集团查询非空校验 |
| `d594a78` | 联系人空列表检查 |
| `24db803` | 客户等级和属性空值兜底 |

---

## 开发注意事项

1. **Api.ts 是自动生成的**（`pnpm gen:api`），标注 `@ts-nocheck`。直接修改可工作但下次重新生成会覆盖。理想流程：先更新 `openapi.yaml`，再 `pnpm gen:api`。但当前部分类型已更新，可直接在 Api.ts 补充缺失字段。
2. **组件不直接调用 API** — 必须通过 hooks 封装。
3. **customerType 类型**：前端必须发送字符串 `"代理"` 或 `"直销"`，不是数字 0/1。
4. **isDeleted 类型变更**：`SalesOpportunity` 的 `isDeleted` 从 Integer(0/5) 改为 Boolean。检查前端是否有 `=== 0` 或 `=== 5` 的判断。
5. **审批 message 必填**：审批接口的 `message` 字段不可为空或纯空格。
