# 任务卡 · add-customer-churn-warning-and-reactivation（客户流失预警与再激活，B 档）

> 目标：确定性规则扫描私海客户 N 天无互动 → 幂等建高优待办 + 每日调度 + 端点/前端筛选触发，
> 补齐 §110 断点 2 规则侧自动漏斗，与 §113 Insight→Action 链路打通。

## 边界与红线

- 零 DB 迁移（风险即时计算不持久化）/ 零权限常量 / 零 LLM 外呼 / 零冻结契约；
- **禁改** `CustomerPoolService` 回池逻辑（公海治归属，本卡治挽回，两不相扰）与
  `CustomerInsightService` 诊断本体；
- 禁碰 `frontend/typed-router.d.ts`（再生立即还原，分支提交零 diff）。

## 锚点

- 幂等与调度先例：`ContractWarningService#hasInProgressTask` + `ContractWarningScheduler`（§109）；
- 三源信号：`contact_task`（已完成）/ `business_activity` / `payment_record`（§110 L107 四源聚合同族）；
- 端点权限锚：公海池同族 113/116/117；前端先例：contract.page.vue 预警筛选（§109）。

## 验收

- 判定边界 / 幂等 / 待办字段单测全绿；基线 1457 只增；merge-gate 8/8（Docker 在线加 9/9）；
  前端三检 + Playwright 检查点；openapi 同步；树净。

## 交付纪律

- 分支 `feature/add-customer-churn-warning-and-reactivation`（自 master 检出，线性提交，完工树净）；
- 完工停步等 Owner：不 merge、不 push；tasks 勾选 + 6 空格实测依据行；handoff 真实披露。
