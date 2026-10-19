# 任务卡 · fix-engineering-debt-loop-write-batch（循环写收口批，A 档）

- 基点：master@8d61f89（surefire 279 报告/1738、failsafe 31/118/7、门禁 10/10）
- 分支：`feature/fix-engineering-debt-loop-write-batch`（权威树检出）
- 来源：池账 #14 余项 A-6/A-8/A-10（详见 proposal.md）

## 目标（三点，目标约束非微操）
1. **A-6 级联下沉**：ForeignKeyDeleteUtil 增批量入口（deleteCascade 本就 in 批量，仅单 id 包装拖累）；CustomerContactServiceImpl.batchLogicalDeleteByIds（:429-441）删自调用循环（:437-439）一次委托；单条入口保留；级联层级/顺序与计数含义不变。
2. **A-8 关联集合化**：BusinessActivityRelationSupport 8 处循环（:42/:59/:87/:105/:128/:159/:187/:202）→ 集合一次比对 + 批量写（允许新增 mapper 批量方法）；错误码/文案/null 跳过/请求内重复 id 逐路径旧语义不变；既有 BusinessActivityRelationSupportTest 保持全绿。
3. **A-10 批删单条化+置空修复**：UserServiceImpl.deleteById（:287-311）单条 update set(status,3)+set(email,null)+set(phone,null)+in(id)；预检闸/@CacheEvict/@Transactional 保留；旧实现 MP NOT_NULL 策略下置空从未生效（占用 uk_email/uk_phone）——新实现真置空＝修复，差异 handoff 单列。

## 红线
零迁移 / 零新依赖 / 零新增错误码常量 / 不改冻结契约 / 不动前端（错误码与文案均不变）/ 基线只增不减；A-8 允许新增 mapper 批量方法但不得引入新依赖。

## 验收
定向全绿（单趟级联/集合比对/单条 SET+IN 三面断言）+ clean verify 全绿 + 单次基线 --update + merge-gate 10/10 + handoff 含三模块证据与等价性论证（含 A-10 置空差异拍板记录）。**停步**：未 merge 未 push，待 A 档独立复核。
