# 规范增量：document-ingest

## ADDED Requirements

### Requirement: 视觉摄取差距对照必须先于实现
在为图像型 PDF / PPTX / 扫描件增加生产解析之前，系统的变更记录 SHALL 包含一份对照文档，映射学习工作区做法与本仓 `DocumentService` 现状，并给出单一推荐切口。该调研变更 MUST NOT 修改生产解析代码，MUST NOT 发起视觉模型外呼。

#### Scenario: 对照文档完备
- **WHEN** `research-visual-ingest` 合入
- **THEN** 仓库存在 `docs/ingest-gap-map.md`
- **AND** 文档含本仓现状、工作区现状、差距表、成本粗估、恰好一个推荐下一实现切口
- **AND** 声明未调用 DashScope

#### Scenario: 学习工作区隔离
- **WHEN** 调研执行
- **THEN** `C:\Users\fzdzzj\Desktop\rag` 不被拷贝进本仓
- **AND** `_vlm_transcribe.py` 保持未跟踪、不提交不删除
