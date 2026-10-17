# 交接快照 · harden-oppo-stage-approval（商机阶段审批阻断，B 档，D4/D6 双卡 1/2）

> 实现子 Agent：完工停步（不 merge、不 push），回贴 Owner 审核。
> 分支：`feature/harden-oppo-stage-approval`（自 master@17008e2 检出，线性提交）
> 档位：B 档（零 DB 迁移 / 零权限常量 / 零冻结契约 / 预期零前端改动）

## 1. 提交清单（本卡提交以 git commit -m 内联，见分支 tip 实测）

| # | 类型 | 哈希 | 文件域 |
|---|------|------|--------|
| 1 | feat(server) | （见 tip） | `SalesOpportunityServiceImpl.java`（update 前置 `requireApprovedStageTransition` 阻断校验） |
| 2 | test(server) | （见 tip） | `SalesOpportunityServiceTest.java`（四向单测，新建） |
| 3 | test(baseline) | （见 tip） | `scripts/test-baseline.txt`（基线 1484→1488 --update 写库） |
| 4 | docs(ops) | （见 tip） | `openspec/changes/harden-oppo-stage-approval/{proposal,tasks}.md`、`work/handoff-harden-oppo-stage-approval.md`、`work/task-card-harden-oppo-stage-approval.md` |

分支 tip 哈希以 `git rev-parse --short HEAD` 实测为准（见 §7 树净自证）。

## 2. 口径依据与复用方式（不新造规则）

**「需审批阶段对」口径 = `SalesStageApprovalSupport#isValidStageTransition(int currentStage, Integer targetStage)`（L268-284）。**

- 该静态方法是审批域阶段流转合法性的**唯一**判定：审批创建链 `SalesStageApprovalServiceImpl.createStage` L148、`submitDraft` L226、草稿校验 `SalesStageApprovalSupport#requireDraftStageValid` L328 三处同源调用；`SalesStageApprovalSupport` 由 tighten-pmd-residual-325 拆自 `SalesStageApprovalServiceImpl`、行为等价。
- **复用方式**：`SalesOpportunityServiceImpl#requireApprovedStageTransition` 直接调用该静态方法判定目标阶段是否需审批（合法正向流转），不新造任何规则。该支持类与本卡实现同包（`com.slz.crm.server.service.impl`），无需新增依赖。
- 仅当 `isValidStageTransition(currentDB, dtoStage)` 为真（= 审批域定义的「需审批」合法正向流转）时，才走已通过审批记录强校验；`false`（非法/非需审批流转）与 stage 未变均按既有 update 行为放行。

## 3. 边界裁决（已通过审批记录「目标阶段匹配」语义）

**匹配语义 = `approved.getTargetStage() == targetStage`（等价匹配，非「任一已通过记录即放行」）。**

- 依据：`SalesStageApprovalServiceImpl.applyApprovedStageChange`（L300-318）审批同意时把商机 stage 直接置为 `entity.getTargetStage()`（L316），因此**已通过审批记录的 targetStage 即该阶段已合法到达的真相源**。审批域 state machine 只允许正向递增/跳级（0→2 可、倒流不可，见 `isValidStageTransition`），故目标阶段 T 一定存在一条 targetStage==T 的已通过记录，才证明「到达 T」经过了审批。
- 反例排除：DB stage 已为 T 时 dto.stage==T → 「stage 未变」提前放行，不查审批；只有从当前 T' 跃迁到更高 T 时才需 targetStage==T 的已通过记录。跳级到达（如 0→2 直批）同样产生 targetStage=2 记录，语义一致。
- 边界说明：本卡只阻断「更新时直接把 stage 改到需审批的目标阶段却无对应已通过审批」的绕过。若商机阶段是被 create 初始态或历史手工置为高阶段（无审批记录），后续 update 到更高阶段会被唯一阻断——这正是 D4 越权跳阶段风险的预期拦截面。

## 4. 错误码复用（不新增常量）

**抛出 `BaseException(ErrorCode.OPPORTUNITY_MUST_BE_CLOSED, "销售阶段推进需先完成审批…")`。**

- 该错误码（30003）即审批域**阶段流转失败**语义：`SalesStageApprovalServiceImpl.createStage` L150 对「无效的销售阶段流转」抛同码 `OPPORTUNITY_MUST_BE_CLOSED`。本卡阻断「需审批的推进无已通过审批」属同类阶段流转失败，复用同码保证前端全局错误通道行为一致，符合红线「错误码复用既有语义、零新增」。

## 5. 实现要点

- `SalesOpportunityServiceImpl#update`（L133 起）第一条即调 `requireApprovedStageTransition(salesOpportunityDTO)`（L135），随后照旧 BeanUtils copy + updateById + runSemanticSync。
- 新增私有方法 `requireApprovedStageTransition`（L101-129）：
  1. `salesOpportunityMapper.selectById(dto.getId())` 取库内现值；实体空或 stage 为 null 直接 return（交既有流程兜底，不新增拦截）；
  2. `targetStage == currentStage` → 非跃迁放行；
  3. `!isValidStageTransition(currentStage, targetStage)` → 非需审批流转放行；
  4. 需审批 → `salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId`（approval_status=1）→ `anyMatch(targetStage==目标)` 缺失抛业务异常。
- `salesStageApprovalMapper` 已在 L50 注入；`detail` L268 的同 mapper 展示调用未动。

## 6. 四向单测（挂新建 `SalesOpportunityServiceTest`，纯 JVM mock）

| 方向 | 用例 | 断言要点 |
|------|------|----------|
| 需审批且已通过 | `shouldAllowStageTransitionWhenApprovedRecordCoversTargetStage` | current=1→2，approved(targetStage=2) → update 放行并调 updateById |
| 需审批且未通过 | `shouldRejectStageTransitionWhenNoApprovedRecordCoversTargetStage` | current=1→2，approved=空 → BaseException，code==OPPORTUNITY_MUST_BE_CLOSED，不调 updateById |
| 非需审批变更 | `shouldAllowNonApprovalRequiredStageChange` | current=2→1（倒流，isValidStageTransition false）→ 放行，verify 不查审批 |
| stage 未变 | `shouldAllowWhenStageUnchanged` | current=3→3 → 放行，verify 不查审批 |

该测试类 4 run 全绿；`mvn -B -ntp test` 全量 **1488**（基线 1484 + 4）。

## 7. 门禁数字与树净自证

- `mvn -B -ntp test`：Tests run **1488**，Failures 0, Errors 0, Skipped 0（基线 1484 → 1488，+4；本卡未删改既有用例）。
- 基线更新：`bash scripts/check-test-baseline.sh --update` 已写库（surefire.tests=1488 / reports=227 / failsafe 118/7 未变）。
- `bash scripts/merge-gate.sh --with-verify`：**9/9 全绿**（unit surefire 1488 / spotbugs / pmd 0 违规 / it failsafe 118 跑 7 skip / baseline / frontend-unit 197 用例 / hook / bijection 12↔12 / pmd-baseline 0=0=0）。Docker 在线（29.6.2）。首轮 merge-gate 曾因本卡代码 2 条 `PMD.OnlyOneReturn` 违规与一次 `[it]` 容器瞬断而非全绿；前者已改用单出口布尔合一重构消解，后者单跑 `mvn -B -ntp verify` failsafe 118/0/7 全绿复现（瞬断，非回归），最终合并 run 9/9 全绿。端点权限覆盖审计与 schema 漂移审计结论同上轮（本卡零新增端点/零迁移，未见新增）。
- typed-router：零 diff（`git status --porcelain -- frontend/typed-router.d.ts` 为空，未见再生；如再生已 `git checkout --` 还原）。
- 分支 tip：`git rev-parse --short HEAD`（以实测为准）。
- 树净：`git status --porcelain` 完工后实测仅剩白名单 3 项（下一卡物料 `openspec/changes/add-confirm-reaudit-coverage/`、`work/task-card-add-confirm-reaudit-coverage.md`、`work/task-card-wire-customer-insight-panel.md`，零碰触）。本卡 openspec 目录与 task-card/handoff 已随 commit 入库移出白名单。
- **未 merge、未 push**：本分支独立存在，master 未动；未经 Owner 授权绝不 `git push`（可 `git log --oneline -1 master` 复核 master 仍为 17008e2）。

## 8. 红线自检

零迁移（未建 Vxx）、零权限常量、零冻结契约（spec/ 零 diff）、不改审批流本身（SalesStageApprovalServiceImpl 未动）、不改 update 之外方法、需审批判定复用 `isValidStageTransition` 未新造规则、预期零前端改动（实际零）、`detail` L268 展示调用未动。