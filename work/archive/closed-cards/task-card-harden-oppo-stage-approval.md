# 任务卡 · harden-oppo-stage-approval（商机阶段审批阻断，B 档，D4/D6 双卡 1/2）

> 目标：update() 阶段跃迁至需审批阶段时强校验已通过审批记录，杜绝绕过审批跳阶段。
> 锚点：SalesOpportunityServiceImpl#update L92；审批口径复用 SalesStageApprovalServiceImpl 既有流转合法性；
> 查证 SalesStageApprovalMapper#selectApprovedApprovalsByOpportunityId（approval_status=1）。

## 红线

- 零迁移 / 零权限常量 / 零冻结契约 / 预期零前端改动（typed-router 自然零 diff）；
- 不改审批流本身与 update 之外方法；需审批判定禁新造规则（复用既有口径并留痕依据）。
- 验收：四向单测（放行/拒绝/非需审批/stage 未变）；基线只增；merge-gate 8/8。

## 交付纪律

- 分支 `feature/harden-oppo-stage-approval`（自 master 检出，线性提交，完工树净）；
- 完工停步等 Owner：不 merge、不 push；tasks 勾选 + 6 空格依据行；handoff 真实披露。
