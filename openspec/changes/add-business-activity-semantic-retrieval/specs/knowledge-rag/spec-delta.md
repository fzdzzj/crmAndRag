# 规范增量：knowledge-rag

## ADDED 需求

### Requirement: 来源引用业务来源语义扩展（SourceReference.sourceType 受控解冻 · BUSINESS_ACTIVITY）

`SourceReference.sourceType` SHALL 支持**商业活动来源**取值 `BUSINESS_ACTIVITY`（大写，与既有文档类取值及 `CUSTOMER` 业务取值并存）：商业活动语义检索命中经冻结契约 `SourceReference` 下发，字段映射规约如下：

| SourceReference 字段 | 商业活动取值口径 |
| --- | --- |
| `sourceType` | `BUSINESS_ACTIVITY`（固定值，大写） |
| `route` | `vector` |
| `documentId` | 活动主键（`business_activity.id` 十进制字符串） |
| `filename` | 活动标题（`activity_title`） |
| `chunkId` | 活动向量切片主键（`BUSINESS_ACTIVITY:{活动主键}`，与 `documentId` 前缀口径一致） |
| `chunkIndex` | `0`（单活动单向量，与 `CUSTOMER` 同口径） |
| `pageNo` / `rowIndex` | `null`（业务实体非分页文档） |
| `excerpt` | 该活动的语义文本（原样返回，前端做段内匹配） |
| `relevanceScore` | 向量相似度得分（0~1） |

字段结构、SSE `sources` payload 结构 MUST 零改动（复用现有十字段，前端 `{type,id,name}` 渲染面不变）；`sourceType` 既有文档类取值（`pdf|docx|txt|md|excel|image|manual`）与既有业务取值 `CUSTOMER` 语义 MUST 零变化。

受控解冻依据：对齐 add-customer-semantic-retrieval 的 D3 先例（owner 2026-10-13 拍板）；`contracts-frozen.md` 变更日志仅追加解冻记录条目，本增量文件为唯一 spec 侧登记（fix-citation-grading-overlap B1 先例）。

#### Scenario: 商业活动命中以 BUSINESS_ACTIVITY 来源下发
- **WHEN** 业务数据检索命中一条商业活动并通过 `business_activity` 权限闸
- **THEN** SSE `sources` 事件 items 出现 `sourceType=BUSINESS_ACTIVITY` 的条目
- **AND** `documentId`=活动主键、`filename`=活动标题、`chunkId`=`BUSINESS_ACTIVITY:{活动主键}`
- **AND** payload 字段集与既有条目完全一致（无新增字段）

#### Scenario: 双业务实体多路召回编号顺延
- **WHEN** 同一轮检索同时命中客户公司与商业活动
- **THEN** 注入上文的 `【业务数据检索】` 片段按相似度降序统一编号（`[n]` 自 `numberStart` 起接续知识库片段）
- **AND** 每条片段的 `sources` 下标与上下文编号一一对应（`CUSTOMER` 与 `BUSINESS_ACTIVITY` 混合编号不重复）

#### Scenario: 既有来源零影响
- **WHEN** 知识库检索照常返回文档类来源、客户语义检索照常返回 `CUSTOMER` 来源
- **THEN** `sourceType` 取值与渲染路径逐字节同旧路径

#### Scenario: 解冻边界
- **WHEN** 本卡实施
- **THEN** `SourceReference` record 签名、字段语义（除 sourceType 取值集扩展外）、SSE 结构零改动
- **AND** `contracts-frozen.md` 除变更日志追加条目外零触碰
