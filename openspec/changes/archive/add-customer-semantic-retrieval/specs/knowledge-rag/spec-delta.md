# 规范增量：knowledge-rag

## ADDED 需求

### Requirement: 来源引用业务来源语义（SourceReference.sourceType 受控解冻）

`SourceReference.sourceType` SHALL 支持**业务实体来源**取值 `CUSTOMER`（大写，与既有小写文档扩展名取值集并存）：业务检索命中经冻结契约 `SourceReference` 下发，`documentId`=客户主键（十进制字符串）、`filename`=company_name、`excerpt`=语义文本、`relevanceScore`=相似度得分、`route`=`vector`。字段结构、SSE `sources` payload 结构 MUST 零改动（复用现有字段，前端 `{type,id,name}` 渲染面不变）；`sourceType` 既有文档类取值（`pdf|docx|txt|md|excel|image|manual`）语义 MUST 零变化。

受控解冻依据：owner 2026-10-13 拍板 D3；`contracts-frozen.md` 变更日志仅追加解冻记录条目，本增量文件为唯一 spec 侧登记（fix-citation-grading-overlap B1 先例）。

#### Scenario: 业务命中以 CUSTOMER 来源下发
- **WHEN** 业务数据检索命中一条客户档案并通过权限闸
- **THEN** SSE `sources` 事件 items 出现 `sourceType=CUSTOMER` 的条目，`documentId`=客户主键、`filename`=公司名
- **AND** payload 字段集与既有条目完全一致（无新增字段）

#### Scenario: 既有文档来源零影响
- **WHEN** 知识库检索照常返回文档类来源
- **THEN** `sourceType` 取值与渲染路径逐字节同旧路径

#### Scenario: 解冻边界
- **WHEN** 本卡实施
- **THEN** `SourceReference` record 签名、字段语义（除 sourceType 取值集扩展外）、SSE 结构零改动
- **AND** `contracts-frozen.md` 除变更日志追加条目外零触碰
