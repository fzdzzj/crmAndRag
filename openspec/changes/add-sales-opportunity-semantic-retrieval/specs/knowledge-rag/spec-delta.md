# 规范增量：knowledge-rag

## ADDED 需求

### Requirement: 来源引用业务来源语义扩展（SourceReference.sourceType 受控解冻 · SALES_OPPORTUNITY）

`SourceReference.sourceType` SHALL 支持**销售商机来源**取值 `SALES_OPPORTUNITY`（大写，与既有文档类取值及 `CUSTOMER` / `BUSINESS_ACTIVITY` 业务取值并存）：销售商机语义检索命中经冻结契约 `SourceReference` 下发，字段映射规约如下：

| SourceReference 字段 | 销售商机取值口径 |
| --- | --- |
| `sourceType` | `SALES_OPPORTUNITY`（固定值，大写） |
| `route` | `vector` |
| `documentId` | 商机主键（`sales_opportunity.id` 十进制字符串） |
| `filename` | 商机名称（`opportunity_name`） |
| `chunkId` | 商机向量切片主键（`SALES_OPPORTUNITY:{商机主键}`，与 `documentId` 前缀口径一致） |
| `chunkIndex` | `0`（单商机单向量，与 `CUSTOMER` / `BUSINESS_ACTIVITY` 同口径） |
| `pageNo` / `rowIndex` | `null`（业务实体非分页文档） |
| `excerpt` | 该商机的语义文本（原样返回，前端做段内匹配） |
| `relevanceScore` | 向量相似度得分（0~1） |

字段结构、SSE `sources` payload 结构 MUST 零改动（复用现有十字段，前端 `{type,id,name}` 渲染面不变）；`sourceType` 既有文档类取值（`pdf|docx|txt|md|excel|image|manual`）与既有业务取值 `CUSTOMER` / `BUSINESS_ACTIVITY` 语义 MUST 零变化。

受控解冻依据：对齐 add-customer-semantic-retrieval 的 D3 先例（owner 2026-10-13 拍板）与 add-business-activity-semantic-retrieval 的 2026-10-14 先例；`contracts-frozen.md` 变更日志仅追加解冻记录条目，本增量文件为唯一 spec 侧登记（fix-citation-grading-overlap B1 先例）。

#### Scenario: 销售商机命中以 SALES_OPPORTUNITY 来源下发
- **WHEN** 业务数据检索命中一条销售商机并通过 `sales_opportunity` 权限闸
- **THEN** SSE `sources` 事件 items 出现 `sourceType=SALES_OPPORTUNITY` 的条目
- **AND** `documentId`=商机主键、`filename`=商机名称、`chunkId`=`SALES_OPPORTUNITY:{商机主键}`
- **AND** payload 字段集与既有条目完全一致（无新增字段）

#### Scenario: 三业务实体多路召回编号顺延
- **WHEN** 同一轮检索同时命中客户公司、商业活动与销售商机
- **THEN** 注入上文的 `【业务数据检索】` 片段按相似度降序统一编号（`[n]` 自 `numberStart` 起接续知识库片段）
- **AND** 每条片段的 `sources` 下标与上下文编号一一对应（`CUSTOMER` 与 `BUSINESS_ACTIVITY` 与 `SALES_OPPORTUNITY` 混合编号不重复）

#### Scenario: 既有来源零影响
- **WHEN** 知识库检索照常返回文档类来源、客户语义检索照常返回 `CUSTOMER` 来源、商业活动语义检索照常返回 `BUSINESS_ACTIVITY` 来源
- **THEN** `sourceType` 取值与渲染路径逐字节同旧路径

#### Scenario: 解冻边界
- **WHEN** 本卡实施
- **THEN** `SourceReference` record 签名、字段语义（除 sourceType 取值集扩展外）、SSE 结构零改动
- **AND** `contracts-frozen.md` 除变更日志追加条目外零触碰
