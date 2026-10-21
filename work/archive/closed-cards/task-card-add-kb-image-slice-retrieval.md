# 任务卡 · add-kb-image-slice-retrieval（知识库图片切片检索 MVP，方案 B，A 档）

> 规格全文：权威树 `openspec/changes/add-kb-image-slice-retrieval/{proposal.md, tasks.md}`。本卡为约束摘要，冲突时以规格为准。

## 目标（方案 B 文本降维闭环，Owner 2026-10-21 拍板）

1. **摄取**：PDF 内嵌图片对象提取 → 资产落盘（对齐现有文档存储 + 删除联动清理）→ 每图 VLM 描述（复用 vision/计量/闸门/帽基建）→ 描述文本切片入现有 chunk/embed 链路（切片带资产追溯引用；零迁移优先）。
2. **回显**：`SourceReference.sourceType` 扩展 `IMAGE`（D3 受控解冻先例形态，spec-delta + contracts-frozen 登记）+ 图片读取端点（权限不宽于所属 KB）+ SSE sources 下发（结构零改动）。
3. **前端**：sourceType=IMAGE 渲染缩略图。

## 硬约束

- 开关 `rag.retrieval.image-slice-ingest.enabled` **默认 false**（默认行为零变化）；每文档图片数帽默认 5
- 零新 Maven 依赖；冻结契约只走受控解冻（不自造来源类型）；零迁移优先（确需走下一 Flyway 号并论证）
- **本卡无真外呼真跑**（VLM 描述链路用 mock/录制确定性测试）；生产灰度真跑由 Owner 另行授权
- 基线只增不减（304/1894 + 34/124/7）；严禁 push / merge / reset / rebase / amend / 删分支
- frontend/ diff 走 batch3 D1 先例裁决记录；契约生成物 diff 走 `--update` 唯一入口

## 验证与停步

- 分支 `feature/add-kb-image-slice-retrieval` 自权威树 master `b7b750f` 检出；提交分笔对齐任务组
- 定向 → 全量 verify（Docker）→ 基线 → merge-gate 10/10 → 前端 lint/type-check
- handoff 落 `work/handoff-add-kb-image-slice-retrieval.md`；**停步于 merge/push 之前**回贴执行报告
