# 任务卡 · d8-image-asset-rollback（图片摄取失败孤儿对象即时回滚）

- 档位：**C 档**（清收逻辑补偿 + 纯单测，主 Agent 亲审 diff，不派独立复核）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/d8-image-asset-rollback`（从 master 检出）
- 目标：`DocumentIngestionService#ingest` 失败路径上，回滚本次已落盘、未落库的图片 PNG 对象，消除孤儿窗口

## 背景锚点（已实读，勿重复侦察）
- 孤儿窗口：`PdfImageSliceIngestService#describeAndStore` 先 `store()` 落盘 → `ImageSliceIngestSupport#parseWithImageSlices`
  合并切片 → `DocumentIngestionService#ingest` 的 `chunkEmbedAndWrite` 失败 → `markFailed` 只清向量/切片行，PNG 对象成孤儿。
- storageKey 只存于切片行 `extra_metadata_json`，行未落库即 key 随事务消失，对象永久不可清。
- `ImageAssetCleanup#deleteAssets`（WARN 不抛）仅服务 deleteFile/reingest，管不了「行从未落库」窗口。

## 任务（写集：`src/main` 2 文件 + `src/test` 1 文件，无其他写入者）
1. `PdfImageSliceIngestService#buildSlices`：收集本次新落盘 storageKey 集合透出（仅成功 `store()` 者）。
2. `DocumentIngestionService#ingest`：失败 catch 分支对集合逐一 `storage.delete()` 回滚（WARN 不抛，原始异常仍上抛）。
3. 新增单测：失败删对 key / 成功零额外删 / 回滚自身异常不吞原始异常 / 空集合零调用。

## 边界（禁改面）
- 禁改 `deleteFile` / `reingest` 既有清理联动、禁迁移、禁新权限/动态键/错误码/契约字段、禁真外呼（全 mock）。

## 验证
`mvn -B -ntp clean verify` BUILD SUCCESS + 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10；既有图片相关测试零回归。

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字（路径/行数/校验结果/未解决项/待决策项），细节留文件。