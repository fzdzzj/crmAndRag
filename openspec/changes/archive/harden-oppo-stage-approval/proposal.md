# 提案 · 商机阶段审批阻断语义加固 (harden-oppo-stage-approval)

> 档位：B 档（零迁移 / 零权限常量 / 零冻结契约 / 预期零前端改动）
> 来源：`work/_paa-candidates-pool.md` §7.3 决策点 D4 + §7.4 优化第 3 项
> 基线：master@17008e2（2026-10-16 Owner 拍板与 D6 同批立项，顺序派发 D4 → D6）

## 1. 背景与目标

`SalesOpportunityServiceImpl#update`（L92 起）直接更新商机含 stage 字段，**未校验审批记录**——前端可绕过
SalesStageApprovalServiceImpl 的审批流直接修改商机阶段（§7.3 D4 越权跳阶段风险）。审批域已就绪：
创建/二次校验链（SalesStageApprovalServiceImpl L139-L238）、已通过审批查询
（SalesStageApprovalMapper#selectApprovedApprovalsByOpportunityId，approval_status=1，L24）。

本卡在服务层补阻断：

1. `update()` 检测 stage 跃迁至**需审批阶段**时，强校验存在该目标阶段的已通过审批记录，否则抛业务异常；
2. 「需审批阶段对」判定**复用 SalesStageApprovalServiceImpl 既有流转合法性口径**（不新造规则，
   参照 add-queryuser-dept-isolation 豁免口径对齐先例，handoff 留痕口径依据行号）。

## 2. 非目标

零迁移、零权限常量、零冻结契约；不改审批流本身（SalesStageApprovalServiceImpl 的创建/审批逻辑）；
不动 update 之外的方法；不改前端（校验失败走既有全局错误通道）。

## 3. 验收

- 单测四向：跃迁至需审批阶段且有已通过审批 → 放行；无已通过审批 → 拒绝（错误码留痕）；
  非需审批阶段变更 → 放行；stage 未变 → 放行；
- 基线只增（当前 1484）；merge-gate 8/8（Docker 在线 9/9）；typed-router 零 diff。
