# 任务卡 · fix-backlog-batch2（B 档）

- 基点：master@9cbf55e（surefire 297 报告/1848、failsafe 33/122/7、门禁 10/10）
- 分支：`feature/fix-backlog-batch2`（权威树检出）
- 来源：待办池 ⑦⑧⑫（工程收口批，消化 3/7）

## 目标
⑦ SQLExceptionHandler 四子项（:48 越界守卫/死代码清理/键空间隔离/裸列名走列名映射-拍板行为改善）；⑧ 5 DTO id 挂 @NotNull（null-id 前置 400 拒绝-拍板行为变化）；⑫ Reflector 经 Resolver 注入阈值（默认零漂移）。

## 红线
零迁移/零依赖/零新错误码/不动前端/基线只增不减/错误码与对外文案语义不变（⑦-④ 与 ⑧ 为明确拍板例外）/未授权不真跑。

## 验收
定向+全量 verify + 单次基线 --update + merge-gate 10/10 + handoff 对照四件套。**B 档**：门禁 0 + 主 Agent 审 diff。**停步**：未 merge 未 push。
