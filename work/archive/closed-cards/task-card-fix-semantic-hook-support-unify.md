# 任务卡 · fix-semantic-hook-support-unify（B 档）

- 基点：master@c17cc18（surefire 287 报告/1802、failsafe 32/119/7、门禁 10/10）
- 分支：`feature/fix-semantic-hook-support-unify`（权威树检出）
- 来源：池账 #25（A-15）五路克隆泛型化

## 目标（门面委托策略，调用点零改动）
1. 新基类 SemanticHookSupport：runAfterCommitAsync 单一实现 + 泛型 reingestById（函数式注入）。
2. 五域类收缩薄门面（签名不变委托基类）；软删谓词分叉逐域保留；Contract deleteById 按调用面实测处置。
3. 基类靶向单测四象限 + 既有测试零弱化 + 缺失域补薄回归。

## 红线
零迁移 / 零新依赖 / 零新错误码 / 不动前端 / 基线只增不减 / afterCommit 与异步隔离行为逐字等价 / 软删谓词分叉不得抹平。

## 验收
定向+全量 verify + 单次基线 --update + merge-gate 10/10 + handoff 五域对照表。**B 档**：门禁 0 + 主 Agent 审 diff 裁决。**停步**：未 merge 未 push。
