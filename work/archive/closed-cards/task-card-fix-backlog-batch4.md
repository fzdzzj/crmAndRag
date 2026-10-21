# 任务卡 · fix-backlog-batch4（收尾小卡 ⑬⑭，B 档）

> 规格全文：权威树 `openspec/changes/fix-backlog-batch4/{proposal.md, tasks.md}`。本卡为约束摘要，冲突时以规格为准。

## 目标

1. **⑬** `ChildHostAttachmentCleaner` 公司/联系人两入口补 `sales_stage_approval` 域附件清理（`opportunities` 一级集已现成，按 `opportunity_id` 查审批行 → `removeByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)`）；商机入口不加（`deleteStageApprovalAttachments` 先例已覆盖，避免重复清理）；IT 补两入口审批附件清零断言。
2. **⑭** `error-code-map.test.ts` 总数断言改生成化：Node fs 读 `ErrorCode.java` + `PlatformErrorCode.java` 正则计数，删硬编码 `100`；`import.meta.url` 相对路径保证双环境可用。

## 硬约束

- 零迁移 / 零新依赖 / 零新错误码（不动两枚举源文件）/ 不动 ForeignKeyConfig / `error-code-map.ts` 生产镜像零 diff / frontend/ 仅限 test.ts 一处测试工程改动（已拍板）
- 审批附件 model_name = `APPROVAL_ATTACHMENT`（与先例 `deleteStageApprovalAttachments:211` 口径一致，勿误用其他常量）
- 基线只增不减（当前 303/1889 + 34/123/7）；严禁 push / merge / reset / rebase / amend / 删分支

## 验证与停步

- 分支 `feature/fix-backlog-batch4` 自权威树 master `71a274b` 检出；提交分笔对齐任务组（fix → test(baseline) → docs）
- 定向 → 全量 `mvn -B -ntp clean verify`（Docker）→ 树净单次基线 `--update` + EXIT=0 → `merge-gate.sh` 10/10（含 [frontend-unit]）
- handoff 落 `work/handoff-fix-backlog-batch4.md`（⑬ 两入口闭包口径论证 / 商机不重复接线论证 / ⑭ 生成化前后对照与双环境路径论证）
- **停步于 merge/push 之前**，回贴执行报告
