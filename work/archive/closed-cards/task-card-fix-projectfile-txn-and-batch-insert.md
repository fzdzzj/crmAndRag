# 任务卡 · fix-projectfile-txn-and-batch-insert（B 档）

- 基点：master@e936459（surefire 283 报告/1779、failsafe 32/119/7、门禁 10/10）
- 分支：`feature/fix-projectfile-txn-and-batch-insert`（权威树检出）
- 来源：池账 #24 正身（A-5,16）+ #14 残尾（E-14,15,16），推荐口径勘误见 proposal

## 目标（四点合一）
1. **A-5+A-16**：ProjectFileServiceImpl 三上传（:61-233+）抽 uploadInternal 合一，磁盘 IO 出事务（先盘后库+全量清盘补偿），错误码文案不变。
2. **E-15**：ApprovalAttachmentServiceImpl.saveBatch（:71-78）先落盘后批量入库+补偿+事务（旧行为增强：旧无补偿）。
3. **E-14**：CustomerContactRemarkValidator（:19-62）先校验后 insertBatch 单次。
4. **E-16**：BusinessActivityServiceImpl create 路径（:86-112）双表 forEach → 复用 A-8 batchInsert。

## 红线
零迁移 / 零新依赖（TransactionTemplate 属 spring-tx 既有）/ 零新错误码 / 不动前端 / 基线只增不减 / 清盘补偿语义保留 / E-14 事务归属仍由调用方界定。

## 验收
四点靶向单测（顺序/补偿/批量形态/闸保留）+ clean verify + 单次基线 --update + merge-gate 10/10 + handoff 等价性与增强论证。**B 档**：门禁退出码 0 + 主 Agent 审 diff 裁决，不派独立复核。**停步**：未 merge 未 push。
