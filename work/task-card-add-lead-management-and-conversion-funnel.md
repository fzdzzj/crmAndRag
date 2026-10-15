# 任务卡 · 销售线索管理与一键转化商机漏斗

- **变更标识**：`add-lead-management-and-conversion-funnel`
- **复核档位**：**A 档**（包含 Flyway V42 数据库迁移脚本与 Testcontainers Docker 校验）
- **权威工作树**：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出：`feature/add-lead-management-and-conversion-funnel`）
- **参考规范**：`openspec/changes/add-lead-management-and-conversion-funnel/proposal.md` 与 `tasks.md`

## 1. 核心目标

1. 编写 Flyway `V42__sales_lead.sql` 迁移脚本，创建 `sales_lead` 数据表与组合索引；
2. 创建 `SalesLeadEntity`、`SalesLeadMapper` 及对应 DTO/VO 数据对象；
3. 编写 `SalesLeadService` 与 `SalesLeadServiceImpl`，实现线索录入、跟进修改、无效关闭与核心「一键转化」业务流转（单事务内自动生成客户、联系人及可选商机，回填转化状态并异步同步向量）；
4. 编写 `SalesLeadController`（路径 `/sales/lead`），涵盖 CRUD 与 `/convert` 端点，复用既有 111/113/114/116/117 权限常量；`PermissionCoverageScanner` 登记闭环；
5. 新建前端 `lead.page.vue` 与 `useSalesLead.ts`，实现线索列表、状态筛选与一键转化弹窗交互；
6. 编写 `SalesLeadServiceTest` 单测，Docker MySQL 运行 `FlywayMigrationIT`，更新测试基线（1434 只增不减），`merge-gate.sh` 8 项全绿。

## 2. 纪律与红线

1. **迁移安全**：`V42` 脚本必须严格对齐 MySQL 语法，禁改历史 V1~V41 脚本，Docker 实测通过；
2. **零权限常量新增**：`PermissionOperates.java` 零改动，端点全部复用既有客户管理权限；
3. **零外呼**：禁止发起任何外部真实模型调用；
4. **线性提交与留痕**：分支单亲线性提交，`tasks.md` 每项勾选严格附 6 空格实测留痕依据行；
5. **完工停步**：完成全量自测后产出 `work/handoff-add-lead-management-and-conversion-funnel.md` 即停步汇报，严禁执行 `merge` 或 `push`！
