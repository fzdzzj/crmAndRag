# 提案 · d8-image-asset-rollback（图片摄取失败孤儿对象即时回滚）

## 背景与动机

上一卡 `add-kb-image-slice-retrieval` 已合入：PDF 内嵌图片对象 → VLM 描述 → PNG 资产落
`FileStorageService` → 描述文本切片走既有 chunk/embed 链路。看板 §2 条目 1 明确留了偏差
**D8 孤儿对象边界**，本卡收口该边界。

## 孤儿产生的精确窗口（三源锚点实读结论）

1. `PdfImageSliceIngestService#describeAndStore`（L160-173）：逐图「描述 → 闸门 → `store()` 落盘
   PNG → `toChunk()` 组装描述切片」。落盘发生在切片行落库**之前**。
2. `ImageSliceIngestSupport#parseWithImageSlices`（L40-61）：图片 slices 与文本 chunks 合并后，交给
   `DocumentIngestionService#ingest` 的 `chunkEmbedAndWrite`（`DocumentIngestionSupport`）做嵌入 + DB 快照
   + 向量库写入。
3. `DocumentIngestionService#ingest`（L232-239）：`chunkEmbedAndWrite` 抛异常 → catch 里 `markFailed`
   标 FAILED（`IngestionRecoveryPolicy` 清向量 + 物理清切片行），但**已落盘的 PNG 对象无人删除**。

**结论**：对象 storageKey 只存在于切片行的 `extra_metadata_json`。当 `chunkEmbedAndWrite` 失败导致 DB
事务回滚、切片行未落库时，storageKey 随事务「消失」，已写进 `FileStorageService` 的 PNG 成为无行引用、
端点不可达、永久不可清理的孤儿对象（存储泄漏）。

`ImageAssetCleanup#deleteAssets` 虽已存在，但只在 `deleteFile` / `reingest` 时按「已落库的切片行」读
storageKey——对「行从未成功落库」的失败窗口无能为力。

## 目标

在**首次入库 `ingest`** 失败路径上，对本次摄取过程中已落盘、但未成功落库的图片对象做**即时回滚删除**，
消除孤儿窗口。不改变成功路径行为、不改变删除/reingest 既有联动语义。

## 边界与不改动面（明确声明）

- **不碰** `deleteFile` / `reingest` 既有 `ImageAssetCleanup` 联动（那些已有「切片行在库」作删除依据，逻辑正确）。
- **不新增**：数据库迁移、权限常量、动态配置键、外部依赖、契约字段。
- **不新增**：错误码、审计事件、SSE 结构。
- **零真外呼**：本卡全部走 mock，无 VLM / 向量真跑。
- 仅处理**首次入库 `ingest`** 的失败窗口；`reingest` 失败路径已在合并前通过 `ImageAssetCleanup.deleteAssets`
  先清旧资产（`parseReingestWithImageSlices` L103），不存在同类孤儿。

## 方案

在 `PdfImageSliceIngestService` 的逐图落盘成功处收集本次 **storageKey 集合**（仅本次 `buildSlices` 调用
新落盘者），透出给 `DocumentIngestionService#ingest`；`ingest` 的 catch 分支（`markFailed` 前或后）对该集合
逐一 `storage.delete()` 回滚，删除失败只 WARN 不抛（与 `ImageAssetCleanup#deleteAssets` L44-50 同一语义）。

关键约束：**成功路径零变化**（正常入库不触发任何额外删除）；失败回滚仅删「本次新落盘、尚未有切片行引用」
的对象，绝不误删历史 / 他人 / 已成功落库的对象。

## 档位判定

**C 档**（清收逻辑补偿 + 纯单测）：零迁移、零权限、零契约、零真跑、零新依赖。复用既有 `FileStorageService`
删除抽象与 `WARN 不抛` 失败语义。

## 验收判据

1. `ingest` 入库失败（`chunkEmbedAndWrite` 抛异常）时，本次已落盘的图片对象被删除，无孤儿。
2. `ingest` 成功时，零额外删除调用，图片对象保留且可被端点回显。
3. 回滚删除失败（存储异常）不阻断原始失败的抛出语义，原始异常仍上抛。
4. 既有 `deleteFile` / `reingest` 联动行为零回归（其测试全绿）。