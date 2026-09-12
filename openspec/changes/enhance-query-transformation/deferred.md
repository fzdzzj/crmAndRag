# 待定项台账 — enhance-query-transformation

> 按 openspec/project.md 17 方案处置总表与本提案 proposal.md §4 登记：以下两项**本提案不实现**，
> 触发条件满足时以新 change 立项（内容照录 proposal.md §4 表，任务 5.1）。

| 项 | rag-kb 编号 | 触发条件 | 届时落点 |
|---|---|---|---|
| 反馈环 | 11 Feedback Loop | 产品提供点赞/点踩信号（`ai_message.payload` 可挂） | 新 change：反馈 → 基准集进化 |
| 层级索引 | 14 Hierarchical Index | 单库 chunk 量级超阈值（如 >10 万）或出现跨库路由需求 | 新 change：摘要层 + 路由 |

## 明确不做（openspec/project.md 总表已记录理由，此处备查）

| 项 | 理由 |
|---|---|
| 12 Self-RAG | 复杂度；等基线归因后再议 |
| 13 KG RAG | CRM 实体走 DB 查询，文档侧无多跳需求 |
| 17 CRAG 完整版 | 企业合规库不接 web 兜底；诚实兜底 D16 已覆盖 |
