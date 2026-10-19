# 任务卡 · fix-engineering-debt-nplus1-loop-clone（工程债三合卡，A 档）

- 基点：master@6e99443（surefire 275 报告/1720、failsafe 31/118/7、门禁 10/10，树净实测 2026-10-19）
- 分支：`feature/fix-engineering-debt-nplus1-loop-clone`（权威树检出）
- 来源：池账 v2 §2 面A A-7/A-9/A-1（详见 proposal.md）

## 目标（三点，目标约束非微操）
1. **A-7 分页 N+1**：UserHandoverServiceImpl 分页 VO 转换逐行 3 次 selectById（convertToVOPage:202-213 → getUserName:157-166）→ selectBatchIds 建 Map 回填，单条路径不变。
2. **A-9 循环软删双胞胎**：InvoiceInfoServiceImpl.deleteByIds（:155-171，status=1）与 PaymentRecordServiceImpl.deleteByIds（:174-190，paymentStatus=2）逐条 selectById+updateById → 各自单条 IN 批量更新、形态对齐；语义等价（两实体零 @Version/@TableLogic、全仓零 MetaObjectHandler 已实测；重复 id 计数以实际行数为准，handoff 论证）。
3. **A-1 双轨收敛**：SQLExceptionHandler 手写 switch（:78-102）与 @Value database-field-map（application.yml:220-240）双轨 → 7 索引名键并入配置单源、删 switch、:104 TODO 消除；duplicate-key（含组合索引）/FK/SQL 三 handler 逐路径文案不变。

## 红线
零迁移 / 零新依赖 / 零新增错误码常量 / 不改冻结契约 / 不动前端（错误码与文案均不变）/ 基线只增不减；配置变更仅限 database-field-map 增 7 键。

## 验收
定向全绿（批量化/单条 SQL/文案三面断言）+ clean verify 全绿 + 单次基线 --update + merge-gate 10/10 + handoff 含三模块证据与等价性论证。**停步**：未 merge 未 push，待 A 档独立复核。
