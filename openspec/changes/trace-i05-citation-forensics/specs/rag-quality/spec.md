# 规范增量：rag-quality

## ADDED Requirements

### Requirement: 引用失败可取证且不污染锚点
真检索基准 SHALL 支持按用例 id 过滤，并把对齐前后的答案、引用编号、召回 id 与 excerpt 写入独立取证文件。取证 MUST NOT 修改 `CaseScore` 字段集，MUST NOT 修改 citationPrecision 公式，MUST NOT 把单条报告当作可比基线。

未知 id 或过滤结果为空 MUST 失败，不得静默 0 条成功。

#### Scenario: 过滤 I-05
- **WHEN** `-Drag.benchmark.only=I-05`
- **THEN** 只评 I-05
- **AND** 默认基线路径不被覆盖

#### Scenario: 未知 id 失败
- **WHEN** only 指定的 id 不在套件中
- **THEN** 测试失败

#### Scenario: 取证字段
- **WHEN** 有命中的知识库用例跑完
- **THEN** 取证 JSON 含 rawAnswer、alignedAnswer、citationsBefore、citationsAfter、retrievedChunkIds、excerpts

#### Scenario: 未授权不外呼
- **WHEN** 用户未授权任务组 4
- **THEN** 不得设置 `RAG_BENCHMARK_REAL=1`
