# proposal · image-slice-ingest-chain-reconciliation（图像切片入链完整性对账）

> 卡 27。零外呼 / 零迁移 / 零契约 / 零权限。**只做判定，不做修复。**

## §1 问题陈述（为什么值得单独一张卡）

卡 26 的 S1 消融在**第一条对账断言**就红停：过闸 **41** 图，文本 embed 实计 **36**。差 5。这 5 个缺口有两种解释，**处置完全相反**：

| 解释 | 后果 |
| --- | --- |
| **A 链路真丢** | 摄取链会静默丢弃已付费的图 ⇒ 任何「入库近重复」实验的地基不成立，必须先改 `src/main`（另卡） |
| **B 计数被缓存抑制** | 链路完好，只是**分母用错**：embed 次数不等于过闸图数 ⇒ 消融可继续，只需把分母换成真实子块行数 |

卡 26 换不来这个区分——它在**钱花完之后**才看到数字不符，且当时没有逐链计数。本卡用**零外呼的 stub 链**把整条链路逐节点数一遍，把「差 5」钉成「差在哪一链、差多少」。

## §2 侦察已得事实（执行者不必重查；行号实测于 `cc3c6d5`）

- **四步同在一个分支内**：`PdfImageSliceIngestService` `:248 if (description != null) {` → `:249 store(...)` → `:251 slices.add(toChunk(...))` → `:265 ImageVisualVectorSupport.embedAndStage(...)` ⇒ **闸门拒图连带丢掉图像向量**（卡 26 D3 已明写本方向禁改，**本卡同样禁改**）。
- **三条 per-image 静默跳过**（都是打日志后继续，不抛）：`:286` 未过质量闸门 / `:290` 描述调用失败 / `:310` 对象落盘失败。
- **一条文档级吞异常**：`ImageSliceIngestSupport:121-137 imageSlices(...)` 用 `catch (Exception)` 兜住 `buildSlices` ⇒ **异常时该文档整批图片切片全丢**，只打一条 WARN，**文本切片照常入库**。
- **每个 CHILD 行恰好一次 embed**：`DocumentIngestionSupport.chunkEmbedAndWrite:48-61`，且送进去的**不是原文**，而是 `ChunkHeaderText.wrap(文件名, 类目, pageNo, rowIndex, 原文)`。
- **`EmbeddingService`（45 行）没有任何缓存**；卡 26 S1 里的 `CachedBudgetTrackingProvider` 是**测试侧**缓存 ⇒ 「缓存抑制」只可能发生在测试桩，不在生产。
- **图片切片的头字段**：`toChunk:334-335` ⇒ `pageNo=image.pageNo()`、`rowIndex=null` ⇒ 包装头 = `文件名 | 类目 | 第N页`。
- **S1 现场日志**（`work/_nds1-write1.log`，权威树）：47 次 describe、**6 条**闸门跳过、**0 条**落盘失败/调用失败 ⇒ **排除** vision 失败与 storage 失败两条路。

**⇒ 解释 B 的机制成立且可人工构造**：**同名文件 + 同页码 + 同描述** ⇒ 相同 `embedText` ⇒ 测试侧缓存命中 ⇒ 计数 < 子块数。这是当前最可能的归因，但**必须用测试证明，不能靠推理定案**。

## §3 判据（R0–R5，执行与复核均以此为准）

- **R0 零外呼（结构保证）**：全程只用 stub `ModelProvider`（vision 返回确定性文本、`embedImage`/`embed` 返回确定性向量），**不得构造真实 provider、不得读 API key**。锚内必须显式声明该保证由「未构造真实 provider」在结构上成立。
- **R1 七链对账**：对确定性合成的 N 张图，逐链记数并断言相邻链的等式：`提取图数 → describe 次数 → 过闸数 → 切片数 → 子块行数 → embed 调用次数 → image 向量暂存数`。**每一链的差值必须显式落锚**（禁只报最终数）。
- **R2 缓存抑制判别（本卡核心）**：同一批输入跑两遍——**无缓存桩** vs **有缓存桩（文本→向量，键 = 完整 embedText）**，落 `{invocationsNoCache, invocationsWithCache, distinctEmbedTexts, childRows}` 四个数。判据：
  - `invocationsNoCache == childRows` ⇒ 链路完好；
  - `invocationsWithCache < invocationsNoCache` 且差值 = `childRows − distinctEmbedTexts` ⇒ **缓存抑制成立**；
  - 两者都不成立 ⇒ 链路真丢，进 R5 的 `CHAIN-BROKEN`。
  **必须构造至少一对「跨文档同页同描述」**（同名文件名 + 同页码 + 同描述）来**逼出**一次命中，否则本判据无鉴别力。
- **R3 两条丢弃语义钉住**（把隐性行为变成断言，不是修）：
  - `storage.store` 对某图抛异常 ⇒ **该图被跳过、其余图照常**（per-image），日志一条，切片数 = N−1；
  - `buildSlices` 内抛出**未被 per-image catch 兜住**的异常（如提取器抛）⇒ **该文档整批图片切片全丢**（切片数 = 0），**文本切片不受影响**，WARN 一条。
- **R4 分母口径**：落每个图片切片的 `(pageNo, rowIndex, embedText)` 三件套并断言 `rowIndex == null`；由此**推得**「何时两个不同图的 embedText 相同」的充分条件，写进锚。
- **R5 verdict 三选一（只许这三个字符串）**：`COUNTER-SUPPRESSED`（链路等、计数被缓存抑制）/ `CHAIN-BROKEN`（真丢，须附 `brokenLink` 与逐链差值）/ `INCONCLUSIVE`（探针无法区分，须附缺哪条证据）。**禁止**写第四种，也禁止在 verdict 里夹带修复建议。
- **R6 处置边界**：`src/main` **零 diff**；结论若为 `CHAIN-BROKEN`，**只记录不改**，修复另立卡。

## §4 交付物

- 新 surefire 测试（纯 JVM、无 Docker、无 Spring 上下文）：`src/test/java/com/slz/crm/quality/ImageSliceIngestChainReconciliationTest.java`
- 结论锚：`docs/rag-quality/image-slice-ingest-chain-v1.json`（LF）+ `.md`（≤40 行）
- 守卫：把 R2 的四数关系与 R5 的三选一钉进测试（锚与断言同源，禁手写死值）

## §5 成本

**0 次真实外呼**、0 迁移、0 契约改动、0 权限改动；surefire 期望 ≤60 s。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1** 用 stub provider 而不是录制回放——零外呼由「未构造真实 provider」在结构上保证，不依赖开关。
- **D2** 只读生产代码，`src/main` 一行不改；需要的语义全部从生产类**直接调用或子类化**取，不复制阈值常量。
- **D3** 本卡**不产出修复方案**。若判出 `CHAIN-BROKEN`，交付到此为止——修复要动 `src/main`，属另卡。

## §7 实测补充（执行后追加，判据正文未改动）

- **verdict = `COUNTER-SUPPRESSED`**（2026-10-25，feature 分支实测，锚 `docs/rag-quality/image-slice-ingest-chain-v1.json`）：七链相邻差值 n1/n8 全 0（`提取=describe=过闸=切片=子块=embed=暂存`），链路完好；R2 四数 `{6, 5, 5, 6}`、必然命中 1 次、差值 1 == childRows − distinctEmbedTexts，计数缺口被测试侧缓存完整复现。
- 两条丢弃语义实测钉住：`storage.store` 单图失败 = 切片 N−1（存活页 [1,3]、WARN 1、文本 2 行不受影响）；`buildSlices` 抛未兜住异常 = 整批图片切片归零（`ImageSliceIngestSupport:132` 外层 WARN 1、文本照常；提取器抛走内层 `:138`，切片 0、零描述外呼）。
- R4：图片切片 rowIndex 恒 null（17 个切片断言）；embedText 碰撞充分条件 = 文件名 ∧ 类目 ∧ 页码 ∧ 描述四输入全同（四因子各有数据对照，见锚）。
- 对卡 26 的口径结论：分母应换真实子块行数（缓存抑制只存在于测试桩，生产 `EmbeddingService` 无缓存）；消融实验地基成立。
- 附注：执行中发现卡 26 同型桩必须以「未命中数」为 embed 调用口径——把 mock 总调用数当外呼数会把命中也计入，测不出抑制差值。
