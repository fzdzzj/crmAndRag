# handoff · d8-image-asset-rollback（图片摄取失败孤儿对象即时回滚）

> 档位：C 档（清收逻辑补偿 + 纯单测，主 Agent 亲审 diff 不派独立复核）
> 分支：feature/d8-image-asset-rollback（永久保留）｜M1 merge --no-ff `d10815c` → 基线收口 `91c9f47`

## §1 目标与背景
收口上一卡 add-kb-image-slice-retrieval 留的偏差 D8：图片对象在切片行落库**之前**已写进 `FileStorageService`，
当 `chunkEmbedAndWrite` 失败导致 DB 事务回滚时，storageKey 随事务「消失」，对象成无行引用、端点不可达的永久孤儿。

## §2 方案与实现面
- `PdfImageSliceIngestService` +59：`STAGED_ASSET_KEYS`（`ThreadLocal<Set<String>>`）+ `beginAssetCollection` /
  `endAssetCollection` / `rollbackStagedAssets`；`store()` 仅在成功且 key 非空时登记。
- `DocumentIngestionService` +19/-1：ingest 开作用域 → 两个 catch 先 `rollbackStagedAssets` 再 `markFailed` 原样上抛 →
  finally `endAssetCollection`。
- `ImageSliceAssetRollbackTest` 304 行 / 5 用例（`src/test/java/com/slz/crm/unit/knowledge/document/`）。

## §3 存储对齐论证（零迁移）
复用既有 `FileStorageService`（MinIO/InMemory 同一抽象）的 `delete`，零新对象存储、零新表、零新列、零 Flyway 号。
回滚删除失败只 WARN 不抛，与 `ImageAssetCleanup#deleteAssets`（L44-50）同一语义。

## §4 失败语义论证
- 回滚是「尽力而为」：单个对象删除失败不阻断整轮回滚，更不替换调用方正要上抛的原始摄取异常。
- 测试 `rollbackDeleteFailureKeepsOriginalException` 用 `assertThatThrownBy(...).isSameAs(embeddingFailure)` 锁死「原始异常同一个实例照常上抛」。
- 成功路径 `successPathDeletesNothing` 断 `never().delete(anyString())` + `chunkCount==2`，锁死零额外删除。

## §5 零迁移 / 零权限 / 零真跑 / 零契约论证（C 档四判据）
- 零迁移：`git diff --stat master...feature` 无 `db/migration`。
- 零权限：零新权限常量、零 `@RequirePermission` 改动。
- 零真外呼：VLM/向量全 mock，`RAG_VISION_PDF_REAL` / `RAG_BENCHMARK_REAL` 零新增。
- 零契约字段：零 `SourceReference` / SSE / OpenAPI 改动。

## §6 验证证据链（执行端实测）
- `mvn -B -ntp clean verify` BUILD SUCCESS：surefire 309/1924/0/0/0、failsafe 35/126/0/0/7（Docker 在线实测）。
- 基线 `--update` 一次：309/1924 + 35/126（计数零漂移）；校验复跑 EXIT=0。
- `merge-gate.sh` 10/10 PASS（[it] 默认跳过，verify 已覆盖）。
- `ImageSliceRetrievalIT` 2/2 绿（零回归）。

## §7 偏差处置与治理留痕
- **D1（锚点失真）**：执行端称「按 CustomerCompanyServiceImpl 先例加 `@SuppressWarnings("PMD.NcssCount")`」——实际
  全仓唯一类级 suppression 先例是 `CustomerContactServiceImpl`（L49，且 L52 自注「无同类先例」）；
  `CustomerCompanyServiceImpl` 走的是拆 `QuerySupport` 路线。真正先例锚点引用失真，但**不影响正确性**。
- **D2（NcssCount 豁免裁决）**：`DocumentIngestionService` 原本正贴 PMD 类级 150 线，本卡 +4 语句触发 NcssCount，
  执行端加类级豁免并注明「拆分将切开同一事务边界」。**Owner 2026-10-21 拍板：接受豁免**，理由=拆开会切
  「存储→切片→嵌入→向量→状态回写」同一事务边界，收益远小于风险。全仓类级 suppression 由此 1 → 2 处。
- **基线 source-revision 口径**：执行端分支内基线 `--update` 指向 `a9be513`（实现 tip）；M1 快车道 merge 后主 Agent
  补一笔 `91c9f47` 重锚 `source-revision=d10815c`（merge commit），计数零漂移。

## §8 停步声明
未 push 前停步；`git push origin master` 由主 Agent 在 Owner 授权 merge 后执行（`fdf5db2..91c9f47`）。未 reset/rebase/amend/删分支。