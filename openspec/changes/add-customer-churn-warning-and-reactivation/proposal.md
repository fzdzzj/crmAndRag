# 提案 · 客户流失预警与自动化再激活任务流 (add-customer-churn-warning-and-reactivation)

> 档位：B 档（零 DB 迁移 / 零权限常量 / 零 LLM 外呼 / 零冻结契约触碰）
> 来源：`work/_paa-candidates-pool.md` §7 扩充清单第 4 项（L190）；§110 断点 2 的规则侧补充
> 基线：master@0563052，Surefire 1457 / Failsafe 118，前端 188 用例

## 1. 背景与目标

客户 360° 诊断（`CustomerInsightService`）已聚合四源信号输出流失风险预警（`riskAlerts`），但纯只读、
需用户手动消费（§110 L154 断点）；且诊断需人工逐客户触发。本卡补齐**规则侧自动漏斗**：

1. **确定性流失判定**（零 LLM）：扫描私海客户，近 N 天（默认 60，`@Value` 可配）无任何互动
   （无已完成的跟进任务 / 无商业活动 / 无合同回款进展）即判流失风险；
2. **幂等建待办**：为客户负责人创建高优先级 `ContactTask`「流失预警：客户 X N 天无互动，建议回访」，
   描述内引导走诊断 → Insight→Action 转化链（§112/§113 刚收口的闭环），参照
   `ContractWarningService#hasInProgressTask` 严格幂等防重；
3. **每日调度**：`ChurnWarningScheduler` 每日凌晨 4 点（错峰：合同预警 3 点 / 公海回池既有时刻）；
4. **端点**：`POST /customer/churn/warning/scan`（手动触发）+ `GET /customer/churn/warning/list`
   （风险清单），复用既有权限常量（117 查看 / 116 变更锚，与公海池同族）；
5. **前端**：客户列表页流失风险筛选 + 一键触发（§109 contract.page.vue 先例）。

## 2. 边界（与公海池的区分）

- 公海池回池 = **归属权治理**（释放给他人认领）；本卡 = **私海内挽回提醒**，不改变客户归属、
  不触碰回池判定与 `CustomerPoolService` 任何逻辑；
- 流失风险**即时计算不持久化**（无新列新表）；不改 `CustomerInsightService` 诊断本体。

## 3. 非目标

零迁移、零权限常量、零 LLM 外呼、零冻结契约；不建再激活话术库（描述模板硬编码即可）。

## 4. 验收

- 规则判定边界（有/无互动各源、N 天边界）、幂等防重、端点权限、调度注册均有单测；
- `mvn test` 基线 1457 只增；`merge-gate.sh` 8/8；前端三检全过；openapi 同步；
- 真实 Docker 时 `--with-verify` 9/9。
