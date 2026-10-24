# tasks · image-slice-ingest-chain-reconciliation（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`=完成、`[-]`=按裁定不做、`[ ]`=未做；三态之外不许自造标记。
判据 R0–R6 与决策 D1–D3 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束（先记住）

- **零外呼**：只允许 stub `ModelProvider`；**禁**读 `DASHSCOPE_API_KEY`、**禁**构造真实 provider、**禁**跑任何 `*REAL*` opt-in IT。
- **`src/main` 零 diff**；既有六锚 + 签收锚 + 入库面两锚（`near-dup-ingest-gate-probe-v1` / `near-dup-ingest-s1-not-run-v1`）**零字节变动**；既有 IT/守卫**不得修改**（只能新增）。
- **不做修复**：判出问题只记录（D3）。

## A · 装配与零外呼自证

- [x] 1. 新增 `ImageSliceIngestChainReconciliationTest`（surefire，纯 JVM）：stub provider 提供 `vision` / `embedImage` / `embed` 三方法确定性返回
      实测：`src/test/java/com/slz/crm/quality/ImageSliceIngestChainReconciliationTest.java` 新增（ChainProvider：vision 按图片字节内容键控确定性描述、embedImage/embed 确定性向量，键=完整 embedText 缓存可选）；surefire 7 用例全绿（`Tests run: 7, Failures: 0`）。
- [x] 2. 零外呼自证：断言测试内**未构造**任何真实 provider（以「桩对象计数 > 0 且无网络配置读取」+ 不引用 API key 变量为据），并打印 stub 调用计数
      实测：`r0ZeroExternalCallByConstruction` 断言 `Mockito.mockingDetails(provider).isMock()` + `ModelProviderProperties.getApiKey()` 空 + 桩计数 >0；运行输出 `[chain-reconcile] stub 计数（外呼=0 对账）: vision=1 embedImage=1 embed=1`；锚 `zeroExternalCall.stubCallCounters` 全链合计 vision=27/embedImage=25/embed=22/命中=1。
- [x] 3. 合成输入：确定性图片（≥64px）+ 生产 `PdfImageSliceIngestService` 真实 `buildSlices` 路径（配置矩阵用既有 `RagBenchmarkPipelineFactory.dynamicConfig` 同源写法）
      实测：`png(ordinal)` Graphics2D 确定性渲染 96~112×100px（≥64 闸门）；`newService()` 走生产构造器，配置矩阵 `RagBenchmarkPipelineFactory.dynamicConfig(configMatrix())`（ENABLED/IMAGE_VECTOR/MAX_IMAGES=20，与 NearDupIngestGateProbeIT:815 同源写法）；PDF 构造复用 `NearDupIngestGateProbeIT.buildPdfs`。

## B · 七链对账（R1）

- [x] 4. 落 `提取图数 / describe 次数 / 过闸数 / 切片数 / 子块行数 / embed 调用次数 / image 向量暂存数` 七个数
      实测：锚 `r1.rows` 两行七数：n1 = [1,1,1,1,1,1,1]，n8 = [8,8,8,8,8,8,8]；提取图数经 `RealPdfAssets.extract`（反射生产 `PdfImageExtractor#extract`），子块行/embed/暂存经反射生产 `DocumentIngestionSupport#chunkEmbedAndWrite`。
- [x] 5. 断言相邻链等式，并把**每链差值**显式打印（禁只报最终数）；对**小于**前链的每一处，必须给出该链对应的生产跳过点行号
      实测：`r1SevenChainReconciliation` 相邻链恒等断言 + 打印 `相邻差值=[0, 0, 0, 0, 0, 0]`（n1/n8 均无小于前链处）；锚 `r1.skipPoints` 登记 5 条跳过点（`:286`/`:290`/`:310`/`:138`/`ImageSliceIngestSupport:132`）；守卫 `r1DeltasRecomputedFromCounts` 从计数重算差值对账。
- [x] 6. 覆盖 N=1 与 N≥8 两档（小样本抓装配错误，大样本抓帽/批边界）
      实测：n1 提取=1、n8 提取=8（帽 MAX_IMAGES=20 显式配置未触发截断，断言 `values[0]==n`）；两档相邻差值全 0。

## C · 缓存抑制判别（R2，核心）

- [x] 7. 同输入跑两遍：无缓存桩 / 有缓存桩（键 = 完整 `embedText`），落 `{invocationsNoCache, invocationsWithCache, distinctEmbedTexts, childRows}`
      实测：`runCacheCorpus` 四文档语料两轮；锚 `r2`：`{invocationsNoCache=6, invocationsWithCache=5, distinctEmbedTexts=5, childRows=6}`；embedCalls 口径=真实模型调用（未命中），命中单独计数。
- [x] 8. **构造一次必然命中**：两份合成 PDF 用**同一文件名**，把**同一张图**放在**同一页码**，stub 对同一图返回同一描述 ⇒ 断言两次 `embedText` 逐字相同、`invocationsWithCache` 恰少 1
      实测：doc1/doc2 同名 `reconcile-chain-probe.pdf`、图 A 同在页 1、桩同字节同描述；诊断实证键序第 1、3 项逐字相同（`【reconcile-chain-probe.pdf | 对账类目甲 | 第1页】\n确定性对账描述[42785f2]…`）；命中 1 次，6−5=1。
- [x] 9. 按 R2 三条判据机械判定（命中差值必须等于 `childRows − distinctEmbedTexts`），结果写 `cacheSuppressionVerdict` 字段
      实测：判据一 noCache==childRows（6==6）过；判据二 差值 1 == 6−5 过；`cacheSuppressionVerdict=COUNTER-SUPPRESSED`（由测量值机械推导，守卫 `r2FourNumbersRelationsHold` 重导一致）。

## D · 两条丢弃语义钉住（R3）

- [x] 10. `storage.store` 对指定图抛异常 ⇒ 断言「该图跳过、其余照常」：切片数 = N−1、日志一条、文本切片未受影响
      实测：`r3StorageFailureDropsSingleImageOnly` 注入 `image-2-0.png` 落盘失败：描述 3、落盘尝试 3、切片 2、存活页 [1,3]、子块 2、embed 2、暂存 2、WARN 1（`:310`）；生产合并入口 `parseWithImageSlices` 结果 2 文本 + 2 图片切片（文本未受影响）。
- [x] 11. `buildSlices` 内抛未兜住的异常（如提取器抛）⇒ 断言「该文档整批图片切片全丢」：切片数 = 0、`ImageSliceIngestSupport` 打 WARN 一条、**文本切片照常入库**
      实测：`r3DocumentLevelSwallowDropsWholeImageBatch`：(a) 注入 `getIfAvailable()` 抛出的存储 provider（位于 buildSlices try 外、per-image catch 兜不住）经生产 `parseWithImageSlices`：图片切片 0、文本切片 2 照常、`ImageSliceIngestSupport` WARN 1（`:132`）、零描述外呼；(b) 非 PDF 字节（提取器抛）走内层 `:138` catch：切片 0、零描述、WARN 1。两层语义分别钉住不混淆。
- [x] 12. 两条语义都必须写成**断言**（`assertThrows` / 计数），不许只写注释
      实测：R3a/R3b 各 8~9 条 assertEquals/assertTrue 计数断言（切片数、存活页、WARN 数、文本保全、零外呼）；守卫 `r3DropSemanticsConsistent` 从锚再对账一遍。

## E · 分母口径与结论锚（R4/R5）

- [x] 13. 落每个图片切片 `(pageNo, rowIndex, embedText)` 三件套；断言 `rowIndex == null`
      实测：锚 `r4.triples` 8 条（n8 全量，embedText 全文落锚）；`rowIndexNullCount=17`（n1+n8+R2 语料+R3a 存活切片上逐一断言 null）。
- [x] 14. 由数据**推出**「两个不同图 embedText 相同」的充分条件，写进锚（禁凭推理写）
      实测：锚 `r4.sufficientCollisionCondition`——四因子全同⇔相同，每个因子一个数据对照：描述（doc1 图B vs doc2 图C 同页同名异描述）、文件名（doc3）、类目（doc4）、页码（n1/n8 八图同名同类目跨八页两两互异）；四全同命中对 doc1-A vs doc2-A。
- [x] 15. 锚 `docs/rag-quality/image-slice-ingest-chain-v1.json`（LF）+ `.md`（≤40 行：方法、七链表、R2 四数、两条丢弃语义、verdict、缺失证据）
      实测：json 180 行纯 LF（测试内 `DefaultIndenter("  ","\n")` 生成 + `contains("\r")` 断言 + 逐字节复算比对）；md 31 行（原记 15 行系笔误，复核 MINOR-1）；数值全部由 `anchorIsByteReproducible` 全链重算钉死，非手写。
- [x] 16. `verdict` ∈ {`COUNTER-SUPPRESSED`, `CHAIN-BROKEN`, `INCONCLUSIVE`} 三选一；`CHAIN-BROKEN` 必须带 `brokenLink` 与逐链差值；**禁**夹带修复建议
      实测（fix2 后）：`verdict="INCONCLUSIVE"`、`missingEvidence` 写两条硬理由、`brokenLink` 仍 null、`r2.cacheSuppressionVerdict` 保持 `COUNTER-SUPPRESSED`（机制层为真不动）；守卫 `verdictThreeWayWithConditionalFields` 三选一 + 条件字段钉死。
      fix2 追记（2026-10-25，主 agent 代笔）：原判 `COUNTER-SUPPRESSED` 用本锚自己推出的 `r4` 条件一照即不成立——S1 三份 PDF 文件名互异（`near-dup-ingest-cooc-p1/p2/p3.pdf`，`d7abc37` 版 `NearDupIngestCooccurrenceIT:437`）、同文档内每页一图 ⇒ `embedText` 碰撞为零。七链全等是事实（已提为守卫**无条件**断言），但它证明的是「这一步没丢」，不是「卡 26 的 5 个缺口由缓存造成」；`r3` 的文档级吞异常比缓存更贴合该缺口形状。

## F · 守卫与门禁

- [x] 17. 守卫：从锚重算 R2 四数关系与 R5 三选一（改坏锚 → 红 → 还原 → 绿，留红绿日志）
      实测：`ImageSliceIngestChainGuardTest` 6 用例；变异三连红——R2 数字 5→4 红（`r2FourNumbersRelationsHold`，work/_isic-mut1-r2number-red.log）、verdict→CHAIN-BROKEN 红（`verdictThreeWayWithConditionalFields`，work/_isic-mut2-verdict-red.log）、n8 切片 8→7 红（`r1DeltasRecomputedFromCounts`，work/_isic-mut3-r1count-red.log）；还原后探针+守卫 13/0 绿（work/_isic-mut-restore-green.log），锚与备份 `cmp` 逐字节一致。
- [x] 18. `mvn -B -ntp test` 全绿，且 surefire 只增不减（贴统计行）
      实测：`Tests run: 2150, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（work/_isic-full-test-clean.log）；surefire 报告 347 ≥ 基线 345、用例 2150 ≥ 基线 2137、skipped 0==0（`scripts/test-baseline.txt` 口径，只增不减）。
- [x] 19. `git status` 只含写集内文件；`git diff master -- src/main` **空**；三份既有锚（六锚 + 签收锚）与入库面两锚 SHA 复算等值
      实测：status 恰 7 项（2 新测试 + 2 新锚 + openspec 卡目录 + 任务卡，无其他）；`git diff master -- src/main` 空、`git diff master --numstat` 空（全部既有文件零字节变动）；SHA256 复算 `near-dup-ingest-gate-probe-v1.json`=6671204ec2fae043、`near-dup-ingest-s1-not-run-v1.json`=d67fef744528c90c、`near-dup-floor-signoff-v1.json`=dbe799471fd1bdb1 与 master 逐字节相等。
- [x] 20. 收口自检：临时脚本/日志已删；`git diff --check` 干净；**外呼次数 = 0** 已对账（贴 stub 计数）
      实测：perl 产生的 `*.java.bak`/`*.json.bak` 已删（`find . -name "*.bak"` 空）；`git diff --check` 无输出；外呼=0：桩计数 vision=27/embedImage=25/embed=22/缓存命中=1（锚 zeroExternalCall.stubCallCounters，真实 provider 未构造、key 未读取）。
