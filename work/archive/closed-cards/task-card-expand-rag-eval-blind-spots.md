# 任务卡 · expand-rag-eval-blind-spots（B 档）

- 基点：master@3480c0c（surefire 290 报告/1818、failsafe 32/119/7、门禁 10/10）
- 分支：`feature/expand-rag-eval-blind-spots`（权威树检出）
- 来源：池账 #26（C-3/C-4/C-5 三点合一）

## 目标
1. C-3：RagBenchmarkCase/AiEvalCase 增 history + 多轮用例组，SUITE_VERSION 升 6.0（真跑落锚另授权）。
2. C-4：Category 增 TEMPORAL + 3-4 例（静态语料 fake 可验证）。
3. C-5：CaseOutcome 增 refused + evaluator D16 收紧（expected 空时 ansC=refused?1:0），既有公式零改动。

## 红线
零真模型外呼 / 零迁移 / 零依赖 / 零新错误码 / 不动前端 / 基线只增不减 / 既有 63 例与指标公式零改动 / SUITE_VERSION 语义不破坏。

## 验收
fake 单测四象限 + 定向+全量 verify + 单次基线 --update + merge-gate 10/10 + handoff 对照表。**B 档**：门禁 0 + 主 Agent 审 diff。**停步**：未 merge 未 push。
