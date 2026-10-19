# 提案 · fix-approval-attachment-cascade-dead-edge（级联死边：商机删除链路修复）

## 为什么
`ForeignKeyConfig:170-171` 声明 SALES_STAGE_APPROVAL_RELATIONS → `ApprovalAttachmentEntity` 外键字段 `approvalId`，但 V1:27 DDL 只有 `and_id`（多态列，配 `model_name` 判别，:33）且实体只有 `andId` 属性——该边自 Wave 0 起不可解析。生产环境 MP TableInfo 已初始化，`SalesOpportunityServiceImpl.delete:155` 删除已关闭商机（stage=5 闸后必经 `deleteCascade`）递归到该边时 `ColumnUtils` 抛 `ID_NOT_EXISTS`（ForeignKeyDeleteUtil:269-274），**整个删除事务回滚，已关闭商机删除端点全断**。上上卡 CI 首红时暴露，测试侧已做确定性收口（KNOWN_DEAD_RELATIONS 精确对表，be231e5），本卡修生产。

## 做什么（拍板项：修复形态，推荐 C）
- **C（推荐）移除死边 + 机会删除路径显式补删**：
  1. `ForeignKeyConfig` 移除 `:169-171` 死边（含注释）；`ALL_RELATIONS:62` 附件自身子图条目保留性评估（无子边则连条目一并移除或留空图，以实测为准）；
  2. `SalesOpportunityServiceImpl.delete` 在 `deleteCascade` **之前**显式补删审批附件：按 `opportunityId` 收集阶段审批 id → `listEntitiesByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)` → `approvalAttachmentService.removeByIds(...)`（canonical 路径，DB 物理删 + 磁盘清理，与 `SalesStageApprovalController.deleteStage:140-156` 同型）；级联系统级不逐附件鉴权（与 deleteStage 逐条 canWriteAttachments 的差异在 handoff 论证）；
  3. `ForeignKeyDeleteUtilBatchTest.KNOWN_DEAD_RELATIONS` 清空（死边不复存在），「新增死映射即红」门禁语义保持。
  - 依据：附件表无 is_deleted，删除语义=物理删+磁盘清理，deleteCascade 子删除无法承载磁盘 IO；and_id 多态（business_activity/sales_stage_approval 共用列空间），裸列级联必跨模型误删——显式按 (model_name, and_id) 收集是唯一安全形态。
- **B（备选，不推荐）**：级联机器扩展判别作用域边（FK spec 加 discriminator 条件）——动公共级联机器风险放大，且仍需解决子删除的磁盘清理层级问题。
- 全仓盘点 `deleteCascade`/`logicalDeleteByCascade` 调用方中图可达 `sales_stage_approval` 的根（预期仅商机删除一处），落 handoff 覆盖面论证。

## 红线
零数据库迁移 / 零新依赖 / 零新增错误码常量（删除从「必炸」变「成功」，无新码面）/ 不改冻结契约 / 不动前端 / 基线只增不减 / 附件删除一律走 canonical `removeByIds`（含磁盘清理），禁止 mapper 裸删。

## 档位
**A 档**：生产删除路径行为修复 + 级联图变更 + Docker 真库 IT 复证，需独立复核。

## 验收口径
真库 IT（Docker MySQL）：seed 已关闭商机 + 阶段审批 + 审批附件 + **他模型同 and_id 对照附件** → delete 成功；审批随级联处理；目标附件（model_name=approval_attachment, and_id∈approvalIds）物理删除；**他模型对照附件存活**（跨模型误删回归网）。修复前同 IT 实测抛 ID_NOT_EXISTS 作前证入 handoff。图单测全活（registry 空）+ clean verify + 单次基线 --update + merge-gate 10/10。严格停步：未 merge 未 push，待 A 档独立复核。
