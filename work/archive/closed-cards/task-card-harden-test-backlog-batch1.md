# 任务卡 · harden-test-backlog-batch1（C 档）

- 基点：master@590afae（surefire 296 报告/1838、failsafe 32/119/7、门禁 10/10）
- 分支：`feature/harden-test-backlog-batch1`（权威树检出）
- 来源：待办池 ①②③④⑥（测试加固批，消化 5/12）

## 目标
①空格形态防回归样本；②release 置空真库回环 IT；③TokenBudget volatile 区别力；④MessageConstant 9 处码断言；⑥A-7 三类 null id 用例。全部 src/test。

## 红线
**src/main 零 diff** / 零迁移/零依赖/零新错误码/不动前端/基线只增不减/既有断言不弱化/未授权不真跑（除 Docker 本地 IT）。

## 验收
定向+全量 verify（Docker）+ 单次基线 --update + merge-gate 10/10 + handoff 五点对照。**C 档**：机械门禁 0 + 主 Agent 审 diff。**停步**：未 merge 未 push。
