# 规范增量：knowledge-rag

## ADDED 需求

### Requirement: 来源引用业务来源语义扩展（SourceReference.sourceType 受控解冻 · CONTRACT / CONTACT_TASK）

`SourceReference.sourceType` SHALL 支持**合同与跟进任务来源**取值 `CONTRACT` 与 `CONTACT_TASK`（大写，与既有文档类取值及 `CUSTOMER`、`BUSINESS_ACTIVITY`、`SALES_OPPORTUNITY` 业务取值并存）：命中经冻结契约 `SourceReference` 下发，字段映射规约如下：

| SourceReference 字段 | 合同取值口径 | 联络任务取值口径 |
| --- | --- | --- |
| `sourceType` | `CONTRACT`（固定值，大写） | `CONTACT_TASK`（固定值，大写） |
| `route` | `vector` | `vector` |
| `documentId` | 合同主键（`contract.id` 字符串） | 任务主键（`contact_task.id` 字符串） |
| `filename` | 合同名称（`contract_name`） | 任务标题（`task_title`） |
| `chunkId` | `CONTRACT:{合同主键}` | `CONTACT_TASK:{任务主键}` |
| `chunkIndex` | `0` | `0` |
| `pageNo` / `rowIndex` | `null` | `null` |
| `excerpt` | 语义文本片段 | 语义文本片段 |
| `relevanceScore` | 向量相似度得分（0~1） | 向量相似度得分（0~1） |

字段签名、SSE `sources` payload 结构 MUST 零改动（复用现有十字段，前端 `{type,id,name}` 渲染面不变）。

#### Scenario: 业务实体来源下发
- **WHEN** 业务数据检索命中合同或联络任务并通过对应权限闸
- **THEN** SSE `sources` 事件 items 出现 `sourceType=CONTRACT` 或 `sourceType=CONTACT_TASK` 的条目
- **AND** `documentId`=对应主键、`filename`=对应名称或标题、`chunkId`=`{ENTITY}:{主键}`

#### Scenario: 五业务实体多路召回编号顺延
- **WHEN** 同一轮检索同时命中客户、活动、商机、合同、任务中的多个实体
- **THEN** 注入上下文的 `【业务数据检索】` 片段按相似度降序统一编号（`[n]` 自 `numberStart` 起接续知识库片段）
- **AND** 编号与 `sources` 下标一一对应，不重复、不冲突
