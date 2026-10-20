# 提案 · fix-projectfile-txn-and-batch-insert（上传事务边界 + 循环 insert 残尾收口）

## 为什么（推荐口径勘误 + 池账正名）
池账 #24「ProjectFile 域收口」正身为 **A-5+A-16**（此前看板推荐误并入 E-14/16，特此勘误）；**E-14/E-15/E-16 实为 #14「N+1/循环写收口批」的残尾**（A 面 A-6/7/8/9/10 已分两卡闭，E 面三项遗留）。五项同族（上传事务反模式 + 循环 insert），合一张 B 档卡收口。2026-10-20 权威树四源实读锚点全部新鲜。

## 做什么（四点）
1. **A-5+A-16（ProjectFileServiceImpl 单文件，#24 正身）**：三上传方法（uploadByActivity :61-121 / uploadByOrder :123-183 / uploadStandalone :185-233+）约 90% 克隆。抽 `uploadInternal` 模板合一（宿主校验/权限闸/字段装饰按宿主注入），**磁盘 IO 移出 @Transactional**：先全部写盘（失败清盘即抛），后短事务批量落库（TransactionTemplate 或等价形态，DB 失败回滚+清盘补偿）；对外错误码与文案逐字不变（PARAM_EMPTY/DATA_NULL/PERMISSION_DENIED/FILE_READ_FAILED）。
2. **E-15（ApprovalAttachmentServiceImpl.saveBatch :71-78）**：同型修复——先全部落盘成功再批量入库+失败清盘补偿+补事务（旧：循环内写盘+insert、无事务无补偿，中途失败留半提交附件）。
3. **E-14（CustomerContactRemarkValidator :19-62）**：replaceRemarks/insertRemarks 两方法改「先全量校验后 mapper.insertBatch」（XML foreach 批量已存在，Excel 导入链路在用）。
4. **E-16（BusinessActivityServiceImpl :86-112）**：create 路径 business_activity_contact/business_activity_user 两处 forEach 逐条 insert → 复用上上卡 A-8 新增的 `batchInsert` mapper 基建各一次批量写（create_time 走库默认，与旧单条等价）。

## 红线
零数据库迁移 / 零新依赖（TransactionTemplate 属 spring-tx 既有能力）/ 零新增错误码常量 / 不改冻结契约 / 不动前端 / 基线只增不减 / 磁盘失败补偿语义保留（清盘后上抛，不得吞异常）/ E-14 的 delete+insert 事务归属仍由调用方界定，本卡不自立事务边界。

## 档位
**B 档**（常规业务代码+测试，无 DB 迁移/无权限变更/无契约变更）：依 `docs/review-intensity-policy.md`，门禁退出码 0 + 主 Agent 审查 diff 裁决，**不派独立复核子 Agent**。

## 验收口径
四点各带靶向单测（上传：先盘后库顺序/全量补偿/批量调用形态/闸与校验保留；备注：insertBatch 单次调用+先校验；活动创建：双表 batchInsert 各一次+单条零调用）；定向+全量 verify + 单次基线 --update + merge-gate 10/10；A-5 事务边界迁移等价性论证与 E-15 行为增强（旧无补偿→新有补偿）单列 handoff。停步：未 merge 未 push，主 Agent 审 diff 后提请合并。
