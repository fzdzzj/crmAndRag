# rag-quality 增量 — run-baseline-ladder

## ADDED Requirements

### Requirement: 基线阶梯可复现

真检索基准 runner 必须支持以开关矩阵参数化执行，且报告自带复现所需的配置快照。

- [x] #### Scenario: 输出路径参数化
  - **WHEN** 以 `-Drag.benchmark.out=docs/rag-quality/baseline-after-hybrid.json` 运行
  - **THEN** 报告落盘到该路径，且缺省运行仍写 `baseline-v1.json`（向后兼容）
- [x] #### Scenario: 矩阵快照进报告
  - **WHEN** 任一跑完成并落盘 JSON
  - **THEN** 报告含当跑开关矩阵快照（fusion/context/chunking/query 各键值），凭报告可复现该跑
- [x] #### Scenario: 稀疏路必需而无 Docker 时显式跳过
  - **WHEN** 矩阵启用稀疏路但本机无 Docker（若 0.1 选 Testcontainers 方案）
  - **THEN** 该跑按假设跳过并输出警告原因，不静默降级跑出无稀疏路的假数字

### Requirement: 阶梯对照断言

每一跑对其锚点报告的断言结果必须机械可判，回退不得静默处置。

- [x] #### Scenario: after-hybrid 对 v1 的提升断言
  - **WHEN** 第二跑完成
  - **THEN** LEXICAL 类 recall@k/MRR 较 v1 提升，聚合 recall@k/hitRate/citationPrecision 不低于 v1；不满足则记录差异并汇报（不改默认值）
- [x] #### Scenario: after-context 的 token 断言
  - **WHEN** 第三跑完成
  - **THEN** token（生成+判卷口径）较 after-hybrid 下降，要点覆盖/answerConsistency/citationPrecision 不回退
- [x] #### Scenario: 触发不成立时第五跑收敛为结论
  - **WHEN** v1/after-hybrid 的 LEXICAL 实测不支持"词汇失配为主要漏召"
  - **THEN** 不执行多查询跑，P5 4.1/4.2 改标"触发不成立"并回填归因结论
