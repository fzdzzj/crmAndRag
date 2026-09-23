# 规范增量：rag-quality

## ADDED Requirements

### Requirement: 图注黄金块必须含完整图注文
I-05 的占位 id `sla-arch-diagram` 所对齐的索引文本 MUST 包含服务台架构图注的承重词面（接入层分层与数据层台账/预警引擎），MUST NOT 与责任工程师/赔偿条款段落粘在同一固定滑窗切片中。

`FixedChunkingStrategy` 的 CHUNK_SIZE=320 / CHUNK_OVERLAP=40 MUST NOT 在本变更中修改。`SUITE_VERSION` MUST 保持 2.0。`CitationAligner` MUST NOT 因本变更改阈值。

#### Scenario: 黄金块词面
- **WHEN** RagBenchmarkDataPreparer 解析评测语料
- **THEN** `sla-arch-diagram` 对应 chunk 含「台账与预警引擎」
- **AND** 不含「何建军」与「赔偿当月服务费」

#### Scenario: 套件规模不变
- **WHEN** 标准基准套件加载
- **THEN** 仍为 54 条、占位 id 不变、SUITE_VERSION=2.0

#### Scenario: 未授权不外呼
- **WHEN** 用户未授权 I-05 重跑
- **THEN** 不得设置 `RAG_BENCHMARK_REAL=1`
