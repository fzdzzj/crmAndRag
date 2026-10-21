# 规范增量：knowledge-rag

## ADDED 需求

### Requirement: 来源引用图片切片语义扩展（SourceReference.sourceType 受控解冻 · IMAGE）

`SourceReference.sourceType` SHALL 支持**图片切片来源**取值 `IMAGE`（大写，与既有文档类取值及 `CUSTOMER` /
`BUSINESS_ACTIVITY` / `SALES_OPPORTUNITY` / `CONTRACT` / `CONTACT_TASK` 业务取值并存）：PDF 内嵌图片对象的 VLM
描述文本作为独立切片入库后，命中经冻结契约 `SourceReference` 下发，字段映射规约如下：

| SourceReference 字段 | 图片切片取值口径 |
| --- | --- |
| `sourceType` | `IMAGE`（固定值，大写） |
| `route` | `hybrid`（与文档来源同口径；图片切片走同一文本向量空间） |
| `documentId` | 图片资产 id（= `document_vector_chunk.id`，图片切片行主键，十进制字符串） |
| `filename` | 图片描述摘要（描述文本压掉换行/多空格后截断至 60 字，超长补 `…`；空描述回落「图片切片」） |
| `chunkId` | `IMAGE:{图片资产 id}`（与业务实体来源 `X:{主键}` 前缀口径一致） |
| `chunkIndex` | 该图片切片在文档内的切片序号（与文本切片共用单一序号空间） |
| `pageNo` | 图片所在页码（1 起） |
| `rowIndex` | `null`（非表格来源） |
| `excerpt` | 图片描述全文（原样返回，前端做段内匹配/展示） |
| `relevanceScore` | 向量相似度得分（0~1） |

字段结构、SSE `sources` payload 结构 MUST 零改动（复用现有十字段，`AiSsePayloadWriter#toSourceItems` 逐字段照旧）；
`sourceType` 既有文档类取值（`pdf|docx|txt|md|excel|image|manual`）与既有业务取值语义 MUST 零变化。

受控解冻依据：对齐 add-customer-semantic-retrieval 的 D3 先例（owner 2026-10-13 拍板）与后续三次同形解冻；
`contracts-frozen.md` 变更日志仅追加解冻记录条目，本增量文件为唯一 spec 侧登记（fix-citation-grading-overlap B1 先例）。

#### Scenario: 图片切片命中以 IMAGE 来源下发
- **WHEN** 检索命中一条由 PDF 内嵌图片描述生成的切片
- **THEN** SSE `sources` 事件 items 出现 `sourceType=IMAGE` 的条目
- **AND** `documentId`=图片资产 id、`filename`=描述摘要、`chunkId`=`IMAGE:{资产id}`
- **AND** payload 字段集与既有条目完全一致（无新增字段）

#### Scenario: 图片资产按所属知识库授权读取
- **WHEN** 客户端请求 `GET /knowledge/images/{assetId}`
- **THEN** 端点沿用 803 `AI_CHAT_SESSION` 方法级权限，服务内再按「切片行 → 所属文档 → 知识库」复用知识库检索授权链
- **AND** 授权集合不含该知识库时返回 12002，持有资产 id 不放大读取面
- **AND** 非图片切片行 / 软删行 / 不存在的资产一律参数错误（不区分，避免探测）

#### Scenario: 摄取开关默认关，默认行为零变化
- **WHEN** `rag.retrieval.image-slice-ingest.enabled` 未配置或为 false
- **THEN** 不提取图片对象、不调用 VLM、不落盘资产、不产生图片切片
- **AND** 文本切片、切片行、向量 payload 与升级前逐字节一致
- **AND** KB 灰度开关 `rag.retrieval.image-slice-ingest.kb-allowlist` 为空时只在总开关开启后对全部知识库生效

#### Scenario: 解冻边界
- **WHEN** 本卡实施
- **THEN** `SourceReference` record 签名、字段语义（除 `sourceType` 取值集扩展外）、SSE 结构零改动
- **AND** `contracts-frozen.md` 除变更日志追加条目外零触碰
- **AND** 零新增 Maven 依赖（图片提取复用既有 PDFBox 3）、零数据库迁移（资产引用写既有
  `document_vector_chunk.extra_metadata_json` 与向量 payload）
