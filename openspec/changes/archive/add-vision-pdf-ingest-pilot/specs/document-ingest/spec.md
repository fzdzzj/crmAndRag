# 规范增量：document-ingest

## ADDED Requirements

### Requirement: 图像 PDF 视觉转写试点默认关闭
系统 SHALL 在 PDF 文本层过短时可选地对该页做视觉转写。总开关 `rag.retrieval.vision-pdf.enabled` MUST 默认为 false。开关关闭、转写器未装配、渲染失败、模型失败或质量闸门失败时，MUST 保留该页文本层，MUST NOT 使整篇摄取因视觉失败而抛错。

视觉转写 MUST 使用既有 `ModelProvider.vision`，MUST NOT 新增 Maven 依赖，MUST NOT 把 PPTX/OCR 引擎纳入本变更。转写成功后该页 `pageNo` MUST 与文本层解析时相同。

#### Scenario: 默认关闭与现行为一致
- **GIVEN** 开关未开或 `DocumentService` 无参构造
- **WHEN** 解析无文本层 PDF
- **THEN** 不调用 `ModelProvider.vision`
- **AND** 行为与升级前一致（空页跳过；整篇空则解析结果为空）

#### Scenario: 开关开启且转写通过闸门
- **GIVEN** 开关开启、文本层短于阈值、视觉转写返回合格正文
- **WHEN** 解析该 PDF 页
- **THEN** 该页切片文本为转写正文
- **AND** `pageNo` 仍为该 PDF 页码

#### Scenario: 质量闸门或调用失败回退
- **GIVEN** 开关开启且文本层过短
- **AND** vision 抛错，或转写过短，或「（截图不清）」占比过高
- **WHEN** 解析该页
- **THEN** 保留文本层
- **AND** 不向调用方抛出视觉相关异常

#### Scenario: 富文本页不走视觉
- **GIVEN** 开关开启且该页文本层长度 ≥ 阈值
- **WHEN** 解析该页
- **THEN** 不调用 vision
- **AND** 页锚点单测语义保持

#### Scenario: 单文档页数帽
- **GIVEN** 开关开启且图像页数超过 `rag.retrieval.vision-pdf.max-pages`（默认 3）
- **WHEN** 解析该文档
- **THEN** 仅对帽内的过短页调用 vision
- **AND** 其余页保留文本层

### Requirement: 视觉摄取计量与配置登记
视觉转写的 token MUST 在 recorder 可用时按 `TokenUsageType.VISION` 上报。三个配置键 MUST 登记在 `rag.retrieval` 命名空间，MUST NOT 扩展 `DynamicConfigKeyRegistry.NAMESPACES`。

#### Scenario: 未授权不外呼
- **WHEN** 用户未授权任务组 5
- **THEN** 测试与合入路径不得设置 API key 发真实 vision 请求
