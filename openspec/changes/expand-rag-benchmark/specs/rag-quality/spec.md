# 规范增量：rag-quality

## ADDED Requirements

### Requirement: 基准集版本化扩容
`RagBenchmarkSuite` MUST 以 `SUITE_VERSION` 版本化（v1.0=18 条历史锚 baseline-v1.json；v2.0=54 条新锚 baseline-v2.json），增删用例/改黄金片段 MUST 升版本；跨版本报告不可直接比较（Report 携带版本字段供核对）。扩容后查询集 MUST 覆盖五类配比 TEXT 14 / TABLE 11 / IMAGE 5 / LEXICAL 12 / EDGE 4，且新难度面（同义改写、跨 chunk 关联、数值区间、多约束组合、近邻主题误导）各至少 1 条。

#### Scenario: 版本一致性校验
- **WHEN** 生成 RagQualityReport
- **THEN** Report 含 SUITE_VERSION=2.0，与运行套件一致

#### Scenario: 黄金对齐完整性
- **WHEN** RagBenchmarkDataPreparerTest 运行
- **THEN** 11 个语料解析成功、54 条占位 id 与 fixtures 的 GOLD 标记一一对应（对齐失败显式报错，不静默空集计分）

### Requirement: 语料自洽
新增 fixtures 的人名/编号/数值 MUST 与既有 6 个语料无冲突（防 LEXICAL 精确召回串扰）；语料内容 MUST 自洽（编号/人名/数值内部一致）。

#### Scenario: LEXICAL 精确性
- **WHEN** 查询新语料中的精确编号（如维保周期表的设备号）
- **THEN** 黄金命中唯一对应新语料 chunk，不被旧语料干扰

### Requirement: 真基准外呼授权门
`RagRealRetrievalBenchmarkIT` 的真外呼跑（RAG_BENCHMARK_REAL=1）MUST 在用户授权后执行：任务组 3（¥0 验证链）全绿后停下报成本预估，未授权不得设置环境变量发外呼。baseline-v2.json MUST 由授权跑产出（含全套指标：recall@5 / precision@5 / MRR / 引用率 / 拒答正确率 / token / 时延）。

#### Scenario: 未授权不外呼
- **WHEN** 用户未授权任务组 4
- **THEN** 无任何 DashScope 外呼发生，合并不含 baseline-v2.json，HANDOFF 标注待授权
