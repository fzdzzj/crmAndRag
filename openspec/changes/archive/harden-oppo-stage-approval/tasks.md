# 任务清单 · 商机阶段审批阻断语义加固 (harden-oppo-stage-approval)

<!-- id: harden-oppo-stage-approval -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 阻断校验实现

- [x] 1.1 实测 `SalesStageApprovalServiceImpl` 流转合法性逻辑，定位「需审批阶段对」口径并设计复用方式（抽出公共判定或调用既有服务，禁新造规则），handoff 留痕口径依据（类名/方法/行号）
      - 口径 = `SalesStageApprovalSupport#isValidStageTransition`（L268-284），审批域创建链 L148 / submitDraft L226 / requireDraftStageValid L328 三处同源调用；本卡 update 直接复用该静态方法，不新造规则。依据详见 work/handoff-harden-oppo-stage-approval.md §2。
- [x] 1.2 `SalesOpportunityServiceImpl#update`（L92 起）实现：stage 跃迁检测 + 目标阶段需审批时校验 `selectApprovedApprovalsByOpportunityId` 已通过记录，缺失抛业务异常（错误码复用既有语义，handoff 留痕）
      - update 前置调用新增私有方法 `requireApprovedStageTransition`（SalesOpportunityServiceImpl L101-129）：selectById 取库内现值，stage 未变/非合法正向流转返回放行；`isValidStageTransition` 为真（需审批）时查 `selectApprovedApprovalsByOpportunityId`，无 targetStage==目标 的已通过记录抛 `BaseException(OPPORTUNITY_MUST_BE_CLOSED)`——该错误码即审批域阶段流转失败语义（SalesStageApprovalServiceImpl L150 同源）。detail L268 同 mapper 调用未动。依据见 handoff §2/§3。

## 任务组 2 · 测试与门禁

- [x] 2.1 四向单测（放行×2 / 拒绝×1 / stage 未变放行×1）；`mvn -B -ntp test` 基线只增后 `--update`；merge-gate 8/8（Docker 在线 9/9）；typed-router 零 diff；产出 `work/handoff-harden-oppo-stage-approval.md`（真实披露）
      - 新建 `SalesOpportunityServiceTest`（unit/service）四向 4 run 全绿；`mvn -B -ntp test` 全量 1488（基线 1484+4）→ `bash scripts/check-test-baseline.sh --update` 写库；`bash scripts/merge-gate.sh --with-verify` 9/9 全绿（Docker 29.6.2 在线）；typed-router `git status --porcelain -- frontend/typed-router.d.ts` 为空（vitest 行尾噪声已 git checkout 还原）；handoff 详见 work/handoff-harden-oppo-stage-approval.md。依据见手印 §6/§7。
