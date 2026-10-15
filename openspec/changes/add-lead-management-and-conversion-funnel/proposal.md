# 变更提案 · 销售线索管理与一键转化商机漏斗 (add-lead-management-and-conversion-funnel)

<!-- id: add-lead-management-and-conversion-funnel -->
<!-- tier: A -->
<!-- status: proposal -->

## 1. 业务背景与问题痛点

在目前的 CRM 业务链条中，销售流程直接从“客户公司（CustomerCompany）”和“联系人（CustomerContact）”开始建立，缺乏销售漏斗的最前端入口——**销售线索（Lead）**：

1. **公私域漏斗缺失前置触点**：通过市场展会、官网留资、外部推广或转介绍获取的初步名单，意向度未经验证，直接创建为正式客户会导致客户库数据严重冗余和脏乱；
2. **缺乏清洗转化闭环**：销售人员在跟进初期需要记录线索来源、初步沟通意向与跟进状态；确认具备商业价值后，缺乏“一键转化”为正式客户、首要联系人及销售商机的一体化动作；
3. **转化率与漏斗分析断层**：由于没有线索表与转化记录字段，无法统计市场投放与线索到客户/商机的转化率。

## 2. 变更目标与方案概要

本提案旨在补齐 CRM 销售漏斗第一公里：

1. **数据模型与数据库迁移 (Flyway V42)**：
   - 新建 `sales_lead` 数据表，记录线索姓名、公司名、联系方式、线索来源（展会/官网留资/电话开拓/转介绍等）、线索状态（待跟进/跟进中/已转化/已关闭无效）、转化关联 ID 与转化时间；
2. **线索全生命周期流转与一键转化服务**：
   - 支持线索录入、跟进记录更新与无效关闭；
   - 核心能力：**一键转化（Convert）**：在单事务内自动创建客户公司（`CustomerCompany`）、首要联系人（`CustomerContact`），并可选创建关联销售商机（`SalesOpportunity`），状态跃迁为已转化并回填 ID；事务后异步联动向量同步；
3. **API 控制器与权限治理**：
   - 新建 `SalesLeadController`（路径 `/sales/lead`），涵盖 CRUD 与 `/convert` 端点；
   - 全部复用既有客户管理权限常量（111/113/114/116/117），零新增权限常量；`PermissionCoverageScanner` 闭环登记；
4. **前端线索管理与转化交互**：
   - 新建 `frontend/src/pages/(dashboard)/sale/lead.page.vue`，实现线索列表、状态筛选、快速录入与「一键转客户商机」弹窗交互；更新路由与 OpenAPI 契约；
5. **测试与回归门禁**：
   - 包含单测与 Docker MySQL `FlywayMigrationIT` 验证，更新基线。

## 3. 详细设计与核心契约

### 3.1 数据表定义 (V42__sales_lead.sql)

```sql
CREATE TABLE IF NOT EXISTS `sales_lead` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '线索ID',
  `name` VARCHAR(100) NOT NULL COMMENT '联系人姓名',
  `company_name` VARCHAR(200) NOT NULL COMMENT '公司名称',
  `phone` VARCHAR(50) DEFAULT NULL COMMENT '电话',
  `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
  `source` VARCHAR(50) DEFAULT NULL COMMENT '线索来源',
  `status` INT NOT NULL DEFAULT 0 COMMENT '线索状态(0待跟进/1跟进中/2已转化/3已关闭无效)',
  `owner_id` BIGINT NOT NULL COMMENT '负责人ID',
  `creator_id` BIGINT NOT NULL COMMENT '创建人ID',
  `converted_company_id` BIGINT DEFAULT NULL COMMENT '转化后客户公司ID',
  `converted_contact_id` BIGINT DEFAULT NULL COMMENT '转化后联系人ID',
  `converted_opportunity_id` BIGINT DEFAULT NULL COMMENT '转化后商机ID',
  `converted_time` DATETIME DEFAULT NULL COMMENT '转化时间',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0正常/1已删除)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY `idx_sales_lead_owner` (`owner_id`, `is_deleted`),
  KEY `idx_sales_lead_status` (`status`, `is_deleted`),
  KEY `idx_sales_lead_company` (`company_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售线索表';
```

### 3.2 一键转化契约 (LeadConvertDTO)

- 入参：
  - `leadId`: 待转化的线索 ID；
  - `createOpportunity`: 是否同步创建销售商机（Boolean）；
  - `opportunityName`: 商机名称（可选）；
  - `estimatedAmount`: 预估商机金额（可选）；
- 响应：
  - `LeadConvertVO`: 包含 `companyId`, `contactId`, `opportunityId`。

### 3.3 API 端点规划（全部复用既有权限常量）

| 路径 | 方法 | 权限常量 | 说明 |
| --- | --- | --- | --- |
| `/sales/lead` | POST | `CUSTOMER_ADD_COMPANY(111)` | 录入新线索 |
| `/sales/lead/{id}` | GET | `CUSTOMER_VIEW_COMPANY(117)` | 查看线索详情 |
| `/sales/lead/query` | POST | `CUSTOMER_QUERY_COMPANY(116)` | 分页查询线索列表 |
| `/sales/lead` | PUT | `CUSTOMER_UPDATE_CUSTOMER_COMPANY(113)` | 更新线索资料或跟进状态 |
| `/sales/lead/{id}` | DELETE | `CUSTOMER_DELETE_CUSTOMER_COMPANY(114)` | 逻辑删除线索 |
| `/sales/lead/convert` | POST | `CUSTOMER_ADD_COMPANY(111)` | 一键转化线索为客户/联系人/商机 |

## 4. 复核档位自声明：A 档

- **判据核验**：
  - 含 DB 迁移脚本：新建 Flyway `V42__sales_lead.sql` 脚本，触发 A 档判据；
  - 权限常量：零新增常量（全部复用既有 111/113/114/116/117）；
  - 外呼：零模型外呼；
  - 契约：冻结契约不碰。
- **复核要求**：本地 Docker Testcontainers MySQL 验证 `FlywayMigrationIT`，进行 A 档全量独立复核。
