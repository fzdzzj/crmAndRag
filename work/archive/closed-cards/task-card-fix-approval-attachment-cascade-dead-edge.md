# 任务卡 · fix-approval-attachment-cascade-dead-edge（级联死边，A 档）

- 基点：master@5024d8e（surefire 283 报告/1777、failsafe 31/118/7、门禁 10/10）
- 分支：`feature/fix-approval-attachment-cascade-dead-edge`（权威树检出）
- 来源：上上卡 CI 首红暴露 + 闭环带出高优（P1 候选），详见 proposal.md

## 目标（拍板形态 C：移除死边 + 显式补删）
1. **修生产缺陷**：ForeignKeyConfig:169-171 死边（`approvalId` 不存在于 DDL/实体）移除——当前已关闭商机删除必抛 ID_NOT_EXISTS 全断。
2. **显式补删附件**：SalesOpportunityServiceImpl.delete 在 deleteCascade 前按 (ModelName.APPROVAL_ATTACHMENT, approvalIds) 收集并 removeByIds（canonical：DB 物理删+磁盘清理，同 deleteStage 型）；and_id 多态列禁止裸列级联（跨模型误删）。
3. **测试网**：真库 IT（前证抛错 + 后证成功 + 他模型同 and_id 对照附件存活）；KNOWN_DEAD_RELATIONS 清空且「新增死映射即红」保持；全仓级联根盘点（预期仅商机删除）。

## 红线
零迁移 / 零新依赖 / 零新增错误码常量 / 不改冻结契约 / 不动前端 / 基线只增不减；附件删除一律走 canonical removeByIds（含磁盘清理），禁止 mapper 裸删。

## 验收
IT 双态证据（修复前抛 ID_NOT_EXISTS / 修复后全绿含跨模型对照行存活）+ clean verify + 单次基线 --update + merge-gate 10/10 + handoff 覆盖面与鉴权差异论证。**停步**：未 merge 未 push，待 A 档独立复核。
