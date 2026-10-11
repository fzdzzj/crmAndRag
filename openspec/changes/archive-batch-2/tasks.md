# tasks — archive-batch-2

> 红线：纯移动+文档订正，零代码零 DDL；surefire 基线不动（188/1124/0 + 28/101/6）；已合入迁移禁改；archive/ 目标内同名目录不得覆盖。

## 任务组 1 · 归档与订正

- [x] 1.1 git mv 五目录入 archive/：trace-citation-redundancy / harden-gold-marker-tearing / fix-citation-grading-overlap / add-prompt-template-admin / archive-closed-changes（先 ls 确认 archive/ 无同名冲突）
- [x] 1.2 fix-citation-grading-overlap/tasks.md 订正 4 处 + §0 补披露（见 proposal What Changes 第 2 条，归档后的新路径下改）
- [x] 1.3 本卡三件套入分支，tasks 勾选与 §0 留痕

## 任务组 2 · 门禁

- [x] 2.1 mvn -B -ntp test 全量 0 失败（1124 只增不减预期不变）
- [x] 2.2 四静态 0 + 三守卫 + check-test-baseline 只读（不得 --update）+ merge-gate 默认序 8/8
- [x] 2.3 停步回报：分支自证、各笔 stat（git mv 须显 rename 记录）、门禁 raw、五目录归档前后对照（changes/ 仅剩 archive-batch-2 与 archive/）

## §0 执行留痕（执行方：执行子 agent，2026-10-11，权威树 feature/archive-batch-2）

  - **归档（1.1）**：归档前 `ls openspec/changes/archive/` 实测 57 目录，五目标名（trace-citation-redundancy / harden-gold-marker-tearing / fix-citation-grading-overlap / add-prompt-template-admin / archive-closed-changes）无同名冲突。逐个 `git mv` 入 archive/，归档后 archive/ 57→62，changes/ 仅剩 archive/ 与本卡 archive-batch-2/。
  - **MINOR 订正（1.2）**：fix-citation-grading-overlap/tasks.md 5 处订正——顶部红线 17→20、任务 1.4 17→20、§0 两处 `MVN_EXIT=1`→`BUILD FAILURE`（各补注「实际日志无 MVN_EXIT 字面标记」半句）、§0 尾部追加两行 work/ 域历史日志披露。见提交 diff。
  - **门禁（2.1/2.2）**：全量 surefire、四静态、三守卫、基线只读、merge-gate 8/8 见停步回报 raw。
