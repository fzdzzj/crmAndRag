# 变更提案 · 合同履约到期预警与逾期应收账款待办联动 (add-contract-warning-and-overdue-reminder)

<!-- id: add-contract-warning-and-overdue-reminder -->
<!-- tier: B -->
<!-- status: proposal -->

## 1. 业务背景与问题痛点

在 CRM 业务运转过程中，合同管理与回款管理是销售成单闭环的核心生命线。目前系统虽已具备合同录入、订单明细和回款记录的基础功能，但仍存在以下关键业务脱节问题：

1. **履约到期无预警**：销售合同签署并生效后，临近到期日（如剩余 30 天内）或已超期未履约完毕的合同无法主动触达负责人，容易导致客户流失或服务断档；
2. **应收账款逾期缺乏跟进抓手**：合同总额与已确认回款之间存在应收敞口，销售人员常常忘记催收款项，无自动化机制提醒负责人跟进催收；
3. **跨模块业务缺乏协同闭环**：现有的联络待办任务（`ContactTask`）功能完备且已在首页仪表盘（Dashboard）展示，但合同与回款的风险事件无法自动转化为跟进任务。

## 2. 变更目标与方案概要

本提案旨在实现合同履约全生命周期的主动监控与自动化催办联动：

1. **到期预警与应收催款规则引擎**：
   - **合同到期预警**：扫描状态为已生效（`contract_status = 1`）且在未来指定天数（默认 30 天）内到期或已超期的合同；
   - **逾期应收催款**：计算合同总金额与已确认回款（`payment_status = 0`）差额，对生效超过 30 天仍有未结应收账款（`remainingAmount > 0`）或合同临近到期的合同标记逾期风险；
2. **联络待办自动生成与幂等防重**：
   - 扫描命中的风险项，自动为合同负责人（`owner_id`）创建一条优先级高的联络待办任务（`ContactTaskEntity`）；
   - **幂等防重机制**：检查该合同在 `contact_task` 中是否已存在进行中（未完成）的同类预警待办，避免每日定时重复轰炸销售员；
3. **定时调度与管理触发端点**：
   - 建立 `ContractWarningScheduler`，每天凌晨 3 点（`0 0 3 * * ?`）定时扫描并批量处理；
   - 暴露预警明细查询与手动触发端点，复用既有销售合同权限常量；
4. **前端交互与预警聚合**：
   - 合同管理页面增加预警过滤视图与一键触发待办生成操作。

## 3. 详细设计与关键契约

### 3.1 预警与催款判定模型

- **合同到期判定**：
  ```sql
  contract_status = 1 
  AND end_date IS NOT NULL 
  AND end_date <= DATE_ADD(NOW(), INTERVAL #{days} DAY)
  ```
- **逾期应收账款判定**：
  - 合同总额 `totalAmount`
  - 已确认回款 `paidAmount = SUM(payment_record.payment_amount WHERE payment_status = 0)`
  - 欠款差额 `remainingAmount = totalAmount - paidAmount`
  - 逾期条件：`remainingAmount > 0 AND (start_date <= DATE_SUB(NOW(), INTERVAL 30 DAY) OR end_date <= DATE_ADD(NOW(), INTERVAL 15 DAY))`

### 3.2 待办任务联动规则

- 任务指派人：系统（`operatorId = 0` 或当前触发人）
- 任务执行人：合同负责人（`contract.ownerId`）
- 关联客户：`contract.companyId`
- 关联商机：`contract.opportunityId`
- 防重幂等：
  - 查询 `contact_task`：`company_id = contract.companyId AND task_type = '合同续签' / '回款催收' AND status IN (0, 1)`；
  - 若已存在同合同未结任务，则跳过生成。

### 3.3 API 端点规划（全部复用既有权限常量）

| 路径 | 方法 | 权限常量 | 说明 |
| --- | --- | --- | --- |
| `/contract/warning/expiring` | GET | `SALES_VIEW_CONTRACT(215)` | 分页查询临期与超期合同 |
| `/contract/warning/overdue-payment` | GET | `SALES_VIEW_CONTRACT(215)` | 分页查询逾期未结款合同 |
| `/contract/warning/trigger` | POST | `SALES_UPDATE_CONTRACT(214)` | 手动触发全量扫描与待办生成 |

## 4. 复核档位自声明：B 档

- **判据核验**：
  - 零 DB 迁移：复用既有 `contract`, `payment_record`, `contact_task` 表结构，**零 SQL 文件**；
  - 零权限语义新增：复用既有 `SALES_VIEW_CONTRACT(215)` 与 `SALES_UPDATE_CONTRACT(214)`，`PermissionOperates.java` 零改动；
  - 零外呼调用：纯内部状态与统计逻辑；
  - 零冻结契约改动：`contracts-frozen.md` 不变。
- **结论**：本卡严格判定为 **B 档**。
