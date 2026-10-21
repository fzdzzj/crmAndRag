# 任务卡 · fix-backlog-batch3（待办池清空卡 ⑤⑨⑩⑪，B 档）

> 规格全文：权威树 `openspec/changes/fix-backlog-batch3/{proposal.md, tasks.md}`。本卡为约束摘要，冲突时以规格为准。

## 目标

1. **⑤** `ErrorCode` 新增 `DATA_ALREADY_EXISTS(92029)`；`SQLExceptionHandler` duplicate-key 三出口改该码（文案不变，code 0→92029）。
2. **⑨** 新增 `common/untils/BatchIdsGuard`（空集→50001，>500→50007）；16 个批量端点统一接入（清单见 tasks 2.2）。
3. **⑩** `CustomerCompanyServiceImpl.batchLogicalDeleteByIds` 改 `logicalDeleteByCascadeBatch` 单趟 + PARAM_EMPTY 闸，向量清理语义保持。
4. **⑪** `ApprovalAttachmentServiceImpl.removeByIds` 跨模型误删修复（只删过滤后行）；三处物理级联删调用方（公司 deleteByIds / 联系人物理删 / 商机 delete）在 deleteCascade 前补子宿主附件 `removeByAndIds` 清理。

## 硬约束

- 零迁移 / 零新依赖 / 除 92029 外零新错误码 / 不动 ForeignKeyConfig / frontend/ 零 diff（springdoc 生成物 diff 仅可走 `--update` 入口并留证）
- 两处行为变化已拍板：⑤ code 0→92029（前端只判 code!==1，零影响）；⑪ 物理级联删从留孤儿附件变为连带清理（行+磁盘）
- 基线只增不减（当前 299/1871 + 33/122/7）；严禁 push / merge / reset / rebase / amend / 删分支

## 验证与停步

- 分支 `feature/fix-backlog-batch3` 自权威树 master 检出；提交分笔对齐任务组（fix → test(baseline) → docs）
- 定向 → 全量 `mvn -B -ntp clean verify`（Docker）→ 树净单次基线 `--update` + EXIT=0 → `merge-gate.sh` 10/10
- handoff 落 `work/handoff-fix-backlog-batch3.md`（含 ⑤ 前端影响实证、⑨ 接入对照表、⑩ 前后对照、⑪ IT 证据与 assist_request 勘误）
- **停步于 merge/push 之前**，回贴执行报告
