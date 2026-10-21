# 任务清单 · d8-image-asset-rollback

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 摄取失败即时回滚
- [x] 1.1 `PdfImageSliceIngestService#buildSlices` 收集本次新落盘的图片对象 storageKey 集合（仅成功 `store()` 者），透出给调用方；成功路径零行为变化
      补勾依据（2026-10-21 实现 + 实测）：`STAGED_ASSET_KEYS` 线程局部 `ThreadLocal<Set<String>>` + `beginAssetCollection/endAssetCollection/rollbackStagedAssets` 三静态方法；`store()` 仅在成功且 key 非空时 `staged.add(storageKey)` 登记（`+59` 行）。亲审 diff 确认作用域配对正确（begin→finally end），成功路径丢弃集合零额外删除。实测 `ImageSliceAssetRollbackTest#collectionScopeTracksOnlySuccessfulStores` 锁定「仅 store() 成功且非空者入集合 + 关闭后重开为空」。
- [x] 1.2 `DocumentIngestionService#ingest` 失败 catch 分支对本次 storageKey 集合逐一 `storage.delete()` 回滚；删除失败 WARN 不抛（对齐 `ImageAssetCleanup#deleteAssets` 语义），原始异常仍上抛
      补勾依据（2026-10-21 实现 + 实测）：ingest 开作用域（`beginAssetCollection`），两个 catch（RuntimeException / Exception）先 `rollbackStagedAssets(stagedImageKeys, fileStorageService)` 再 `markFailed` 原样上抛，finally `endAssetCollection`（`+19/-1` 行）。亲审：`rollbackStagedAssets` 空/null 零调用、单对象删除失败 `LOG.warn` 不抛（与 `ImageAssetCleanup#deleteAssets` L44-50 同语义）、绝不替换原始摄取异常。实测 `failureDeletesOnlyStagedImageKeys` / `rollbackDeleteFailureKeepsOriginalException`（`isSameAs` 断言原实例）绿。

## 2. 测试
- [x] 2.1 单测：失败回滚删除被调用且删对 key / 成功路径零额外删除 / 回滚删除自身异常不吞原始异常 / 空闲集合零调用
      补勾依据（2026-10-21 实测）：`ImageSliceAssetRollbackTest`（304 行 / 5 用例，`src/test/java/com/slz/crm/unit/knowledge/document/`）——`failureDeletesOnlyStagedImageKeys`（`verify(storage).delete(IMAGE_KEY)` + `never().delete(DOC_KEY)` 防误删文档原件）/ `successPathDeletesNothing`（`never().delete(anyString())` + `chunkCount==2`）/ `rollbackDeleteFailureKeepsOriginalException`（`isSameAs` 原实例）/ `emptyCollectionCallsDeleteNever`（旁路关闭零调用）/ `collectionScopeTracksOnlySuccessfulStores`（作用域边界）。VLM/向量全 mock，零真外呼。
- [x] 2.2 既有 `ImageAssetCleanupTest` / `ImageSliceRetrievalIT` / `DocumentIngestionService` 相关测试全绿（零回归）
      补勾依据（2026-10-21 实测）：`mvn -B -ntp clean verify` BUILD SUCCESS——surefire 309/1924/0/0/0（+1 报告 +5 用例，新增测试类所致）、failsafe 35/126/0/0/7（持平）；`ImageSliceRetrievalIT` 2/2 绿（Docker 29.6.2 实测在线）；既有图片相关测试零回归。

## 3. 验证与收口
- [x] 3.1 `mvn -B -ntp clean verify` BUILD SUCCESS（Docker 在线）+ 树净单次基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10
      补勾依据（2026-10-21 实测）：`mvn -B -ntp clean verify` BUILD SUCCESS（surefire 1924 / failsafe 126 / 7 skip，四静态全绿）；基线 `--update` 一次写入 309/1924/0 + 35/126/7（计数零漂移，仅 +1 报告 +5 用例）；门禁校验复跑 `EXIT=0`；`merge-gate.sh` 10/10 PASS（[it] 默认跳过，已由 verify 覆盖）。M1 走树等价快车道（`merge d10815c^{tree}` == `branch e7002f7^{tree}` == `7addcc82…` 逐字节相等）。
- [x] 3.2 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（C 档，主 Agent 亲审 diff，不派独立复核）
      补勾依据（2026-10-21）：本卡 C 档——主 Agent 亲审 diff（四判据全过：零迁移/零权限/零真跑/零契约字段 + 失败语义 + 成功路径零变化）裁定 GO，不派独立复核；Owner 已授权 merge --no-ff（接受 `DocumentIngestionService` NcssCount 类级豁免，理由：拆分会切「存储→切片→嵌入→向量→状态回写」同一事务边界）。归档/handoff/看板/台账更新随 M2 笔完成。