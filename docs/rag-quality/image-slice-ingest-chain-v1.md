# image-slice-ingest-chain-v1 · 图像切片摄取链逐链对账锚（卡 27）

方法：零外呼 stub 链。合成 PNG（≥64px、确定性）→ `NearDupIngestGateProbeIT` 同源 PDF 构造 → 生产 `PdfImageSliceIngestService#buildSlices` → 反射调生产 `DocumentIngestionSupport#chunkEmbedAndWrite`（含生产 `ChunkHeaderText.wrap`）→ `ImageVectorCleanupRegistry` 绑定 image 计数桩消费内部 flush。vision 桩按图片字节内容键控返回确定性描述；embed 桩可选按完整 embedText 缓存（卡 26 CachedBudgetTrackingProvider 同型机制，仅测试侧存在）。数值由 `ImageSliceIngestChainReconciliationTest` 全链重算逐字节复算，本文件不承载唯一数字源（JSON 为准）。

## 七链对账（R1，相邻差值全部为 0）

| 档 | 提取图数 | describe次数 | 过闸数 | 切片数 | 子块行数 | embed调用次数 | image向量暂存数 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| n1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 |
| n8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 |

跳过点登记（实测于 cc3c6d5）：`:286` 未过闸（单图）/ `:290` 描述失败（单图）/ `:310` 落盘失败（单图）/ `:138` buildSlices 内层文档级 catch（整批）/ `ImageSliceIngestSupport:132` 外层文档级 catch（整批）。`gatePassed` 口径 = `store` 尝试次数（生产序 describe→闸门→store ⇒ 与过闸数恒等）。

## R2 缓存抑制判别（四数）

`{invocationsNoCache=6, invocationsWithCache=5, distinctEmbedTexts=5, childRows=6}`，必然命中对 1 次（doc1/doc2 同文件名同页码同描述，embedText 逐字相同），差值 1 == childRows − distinctEmbedTexts。语料另含异文件名/异类目对照组：任一因子不同 ⇒ embedText 不同。缓存不改链路计数（两轮子块行数一致）。

## R3 两条丢弃语义（已钉成断言）

- `storage.store` 单图失败：3 图描述 3、落盘尝试 3、切片 2（存活页 [1,3]）、子块 2、embed 2、暂存 2、WARN 1；文本切片 2 行不受影响（`PdfImageSliceIngestService:310`）。
- `buildSlices` 抛出未兜住异常：整批图片切片全丢、文本切片 2 行照常、`ImageSliceIngestSupport` WARN 1、零描述外呼（`:132`）；提取器抛（非 PDF 字节）走内层 `:138` catch：切片 0、零描述外呼、WARN 1。

## R4 分母口径

图片切片 rowIndex 恒 null（17 个切片断言）；n8 八条 `(pageNo, rowIndex, embedText)` 三件套落 JSON。embedText 碰撞充分条件（由数据推出）：文件名、类目、页码、描述四输入全同 ⇔ embedText 相同。

## verdict

**`INCONCLUSIVE`** —— 两条硬理由：

1. 七链相邻差值全 0、R2 差值恰等于重复 embedText 数，只证明**机制可复现**（`r2.cacheSuppressionVerdict=COUNTER-SUPPRESSED` 仍成立），**不证明**卡 26 的缺口由它造成。本锚 `r4` 推出的碰撞条件要求「文件名 ∧ 类目 ∧ 页码 ∧ 描述四输入全同」，而 S1 三份合成 PDF 文件名互异（`near-dup-ingest-cooc-p1/p2/p3.pdf`）、同文档内每页一图 ⇒ **S1 不可能发生 embedText 碰撞** ⇒ 缓存零命中，该机制解释不了缺口。
2. 缺口两端 41/36 出自一个**已不存在的断言版本**（中止日志文案「ingest 阶段文本 embed 外呼必须等于过闸门图数」在提交版 `:344`/`:350` 均不存在），分母口径不可考。

`brokenLink`：无（实验室七链全等，链路本身没丢）。缺失证据：S1 第三份 PDF 的逐文档计数（需外呼授权）。本锚的替代候选机制：**`r3` 的文档级吞异常（`PdfImageSliceIngestService:138` / `ImageSliceIngestSupport:132`）——整批切片丢弃而 describe 早已计数**，与「47 describe / 41 过闸 / 36 embed」的形状更贴合，但同样未经 S1 语料取证。

**对消融的可操作含义**：分母一律用**实测子块行数**，不用推导值。这条结论不依赖 S1 缺口定案。

零外呼：stub 计数 vision=27 / embedImage=25 / embed=22（未命中口径）+ 缓存命中 1，全部为桩内计数，真实外呼 0 次；未构造真实 provider、未读 key。
