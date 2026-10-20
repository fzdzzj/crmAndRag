# 提案 · fix-semantic-hook-support-unify（五路语义同步 hook 泛型化合一）

## 为什么（池账 #25 / A-15，B 档）
`runAfterCommitAsync`+`reingestById` 模板五份并行演化（Contract :35-55 ≅ Customer :35 ≅ ContactTask/SalesOpportunity/BusinessActivity 同构），改事务/异步语义需五处同改，且注释口径已现漂移。2026-10-20 权威树实读双样本坐实：`runAfterCommitAsync` 逐字同形（仅日志域前缀差异）；`reingestById` 存在**语义分叉**——Customer 带 `isDeleted` 软删谓词（:70），Contract 无（物理删域）且另有 `deleteById`。

## 做什么
1. **新基类** `SemanticHookSupport`（server/service/impl，final 工具类）：
   - `runAfterCommitAsync(domainLabel, scene, executor, action)` 单一实现——guarded 捕获 WARN 不阻断 + 事务激活注册 afterCommit / 无事务直发，行为与现有五份逐字等价；日志文案参数化保留五域定位能力（文案统一形态属非契约，handoff 单列）。
   - `<E> reingestById(id, entityLoader, shouldDelete 谓词, ingest 动作, delete 动作)` 函数式注入——软删谓词差异由各域显式表达。
2. **五域类收缩为薄门面**（拍板：门面委托策略）：保留各域静态签名与类名，内部委托基类，**全部调用点零改动**；各域 reingest 语义分叉（软删谓词有无）逐域保留；Contract 的 `deleteById` 去留按调用面实测（有调用则保留门面，无调用则随卡删除并留证）。
3. **测试**：新基类靶向单测（事务激活 afterCommit 提交后才派发 / 无事务直发 / action 抛异常 WARN 不阻断 / reingest 三分支 null→删、软删→删、正常→ingest）；既有 3 专项 + 1 间接测试全绿零弱化；Customer/BusinessActivity 域如无专项则补薄门面回归（薄到仅验证委托不倒）。

## 红线
零数据库迁移 / 零新依赖 / 零新增错误码 / 不改冻结契约 / 不动前端 / 基线只增不减 / **afterCommit 语义与异步隔离行为逐字等价（注册时机、回滚不派发、异常 WARN 兜底）** / 各域软删谓词分叉不得抹平。

## 档位
**B 档**（纯重构+测试，无 DB/权限/契约变更）：门禁退出码 0 + 主 Agent 审 diff 裁决，不派独立复核。

## 验收口径
新基类单测四象限 + 既有 hook 相关测试全绿；定向 + 全量 verify + 单次基线 --update + merge-gate 10/10；handoff 含五域前后对照表（每域：签名/谓词/deleteById 形态/日志文案变化）。停步：未 merge 未 push，主 Agent 审 diff 后提请合并。
