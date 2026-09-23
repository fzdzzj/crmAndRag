# 规范增量：ai-citation

## ADDED Requirements

### Requirement: 生成后引用编号与片段对齐
系统 SHALL 在知识库答案生成完成后、引用编号对外可见（落库文本 / `references.citations` / 评测 citationPrecision 抽取）之前，用零外呼的确定性算法校验每个内联 `[n]` 与 `SourceReference` 列表的支撑关系，并按 KEEP / REMAP / DROP 改写编号。

对齐 MUST 同时用于生产落库路径与 `RagRealRetrievalBenchmarkIT` 评测抽取，禁止两套计分。对齐 MUST NOT 调用模型、MUST NOT 改变 `SourceReference` / SSE 事件名 / payload 字段集、MUST NOT 修改 `RagQualityEvaluator` 的 citationPrecision 公式。

#### Scenario: 错号重映射（I-05 形）
- **GIVEN** 检索命中列表第 3 条 excerpt 含承重事实，第 1/2 条不含
- **AND** 模型答案用这些事实作答但内联引用为 `[1]`
- **WHEN** 对齐器处理该答案与 sources
- **THEN** 该处编号变为 `[3]`
- **AND** 对外 citations 仅含 3

#### Scenario: 已对齐保持不动
- **GIVEN** 答案 `[n]` 对应 excerpt 对该子句为强支撑
- **AND** 不存在得分明显高于它的其他 excerpt
- **WHEN** 对齐器处理
- **THEN** 答案原文逐字保留

#### Scenario: 平局保留原编号
- **GIVEN** 两条 excerpt 对该子句得分接近（差值 < TIE_MARGIN）
- **WHEN** 对齐器处理
- **THEN** 不改原编号（避免误伤）

#### Scenario: 无支撑则删除编号
- **GIVEN** 答案含 `[n]` 且所有 excerpt 对该子句均低于 REMAP_MIN
- **WHEN** 对齐器处理
- **THEN** 删除该 `[n]`
- **AND** 不插入新的编号
- **AND** 不编造来源

#### Scenario: 不补漏引
- **GIVEN** 答案陈述了某 excerpt 中的事实但没有任何 `[n]`
- **WHEN** 对齐器处理
- **THEN** 不凭空插入引用编号

#### Scenario: 空来源与空白答案
- **GIVEN** sources 为空或答案空白
- **WHEN** 对齐器处理
- **THEN** 返回原文与空 citations，不抛错

### Requirement: 生产路径使用对齐后文本落库
`AiChatStreamLifecycle` 在流完成时 MUST 先对齐再抽取 citations，助手消息落库内容 MUST 为对齐后文本。已通过 SSE 流出的 token MUST NOT 回放改写（不牺牲 TTFT）。

#### Scenario: 落库与 citations 一致
- **WHEN** 知识库回合流式生成完成且 sources 非空
- **THEN** 落库助手文本中的 `[n]` 与 `references.citations` / audit payload 使用同一套对齐结果

#### Scenario: 构造器与契约不变
- **WHEN** 本变更合入
- **THEN** `AiChatStreamLifecycle` 构造器签名不变
- **AND** `SseContractTest` 的 references/sources 字段集断言仍然成立

### Requirement: 评测路径与生产共用对齐器
`RagRealRetrievalBenchmarkIT` 在从答案抽取 citations 之前 MUST 调用与生产相同的 `CitationAligner`。`SUITE_VERSION` MUST 保持 2.0。复测输出 MUST 写入新路径，MUST NOT 覆盖 `baseline-v1.json` 或 `baseline-v2.json`。

#### Scenario: 评测抽取经过对齐
- **WHEN** 真检索基准对有命中用例抽取 citations
- **THEN** 抽取基于对齐后文本
- **AND** 调用的是 `com.slz.crm.server.ai.CitationAligner` 而非评测包内的复制实现

#### Scenario: 未授权不外呼
- **WHEN** 用户未授权任务组 4
- **THEN** 不得设置 `RAG_BENCHMARK_REAL=1` 发真实外呼
- **AND** 合入可以不含 `baseline-after-citation.json`，但 HANDOFF 必须标注待补跑

#### Scenario: 复测对照 v2 锚点
- **WHEN** 授权后以纯默认矩阵跑完 54 条并落 `docs/rag-quality/baseline-after-citation.json`
- **THEN** 报告 `suiteVersion=2.0`
- **AND** I-05 的 citationPrecision 相对 v2 提升（目标 1.0）
- **AND** 全套 citationPrecision 不低于 v2 的 0.7843
- **AND** 若 I-05 未提升或全套 citP 回退，实现方 MUST 停下汇报，不得改评测口径或黄金块
