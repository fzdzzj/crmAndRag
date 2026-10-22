# 真共享空间生产灰度授权路径登记（MULTIMODAL-GRAY-ROLLOUT-1.0）

> 卡：`multimodal-gray-and-eval-threshold` 任务组 1（2026-10-22 Owner 拍板：**只登记运行期灰度路径，不改代码**）。
> 性质：**纯文档**——零 `src/main` 改动、零迁移、零权限常量、**生产开关默认值保持 `false` 原样**。
> 关联锚点：`docs/rag-quality/production-gray-cost-v1.json`（成本基线）、`docs/rag-quality/image-threshold-calibration-v1.json`
> （异图判别阈值定标）、`docs/token-metering.md`（embedding 计量口径）。

## 1. 这张文档解决什么

`production-gray-real-run` 已经量出「打开开关后每张图的成本」，但**没有授权打开**：生产开关默认 `false`。本文件把「打开」这件事登记为
**运行期动态配置操作**（谁写、写哪几个键、按什么顺序、怎么回滚、看哪些指标），而不是代码改动——**本卡一行生产代码都不碰**。

## 2. 四个键与真实门控关系（读代码实测，勿凭键名想当然）

| 键 | 默认值 | 档位 | 作用 |
|---|---|---|---|
| `rag.retrieval.image-slice-ingest.enabled` | **false** | COST | 方案 B 总开关：PDF 内嵌图片 → VLM 描述 → 描述文本切片进既有 chunk/embed 链路 |
| `rag.retrieval.image-slice-ingest.kb-allowlist` | 空 | COST | **KB 灰度白名单**（逗号分隔 KB id；空 = 全库）。按 KB id 精确匹配，不做前缀/模糊 |
| `rag.retrieval.image-slice-ingest.max-images-per-document` | **5** | COST | 单文档图帽，范围 1~20（超出不送 VLM、不落盘）。**这就是生产帽** |
| `rag.retrieval.image-vector-ingest.enabled` | **false** | COST | 方案 A 真视觉向量开关：同一张图再经文本-图像联合 embedding 写独立 image 集合 |

**关键结构事实（`PdfImageSliceIngestService` 实测）**：真视觉向量路**嵌在方案 B 的门控之内**——
`buildSlices()` 先判 `enabled(knowledgeBaseId)`（= 方案 B 总开关 **且** KB 白名单命中），命中后才看
`imageVectorEnabled()`；**没有独立的 `image-vector-ingest.kb-allowlist` 键**。因此：

> 「灰度开真视觉」= 方案 B 总开关 true + 白名单限定 KB + 真视觉开关 true。
> 只写 `image-vector-ingest.enabled=true` 而方案 B 总开关仍 false 时，**链路完全不动**（零外呼、零落盘）。

## 3. 灰度打开步骤（超管运行期操作，按序执行）

1. **先定灰度范围**：把目标知识库 id 以逗号分隔写进 `rag.retrieval.image-slice-ingest.kb-allowlist`
   （例如 `12,15`）。**空值 = 全库生效**，灰度期不要留空。
2. **确认图帽**：`rag.retrieval.image-slice-ingest.max-images-per-document` = **5**（生产默认帽；如灰度期要压成本可临时调小，
   硬上限 20）。
3. **先开方案 B**：`rag.retrieval.image-slice-ingest.enabled = true`。此时只有白名单 KB 的 PDF 内嵌图会被描述入切片。
4. **再开真视觉**：`rag.retrieval.image-vector-ingest.enabled = true`。此后同一批图额外产生视觉向量进 image 集合
   （query-by-image 的检索面）。
5. **写入方式**：管理端动态配置（COST 档，**超管专写**；不支持 608 运营档写入）。四项都是运行期热生效，无需重启。
6. **观察窗口**：至少跑一个白名单 KB 的重建入库 + 若干次随图提问，再决定扩 KB。扩面 = 改白名单（第 1 步），不是改开关。

## 4. 成本基线引用（决策依据，不重复计算）

来源：`docs/rag-quality/production-gray-cost-v1.md` / `.json`（真实公有领域 PDF 真跑，`PRODUCTION-GRAY-COST-1.0`）。

| 每张**真正入库**的图 | 实测值 |
|---|---|
| vision token | **≈ 1 371**（9 600 / 7；prompt 8 001 + completion 1 599 真实回流） |
| vision 外呼 | **1.29**（9 / 7，含被描述质量闸门剔除的 2 张**沉没成本**） |
| 视觉 embedding 调用 | **2.00**（摄取 1 + 查询 1） |
| 文本 embedding 调用 | **4.57**（32 / 7，含查询侧多路拆路） |
| 端到端耗时 | **≈ 9.8 s** |

> **预算口径三条**（照抄基线的结论，勿自行换算）：① 按「每张入库图」摊分，不按每份 PDF（页数/图数方差极大）；② 真实文档提取→入库有
> **22% 沉没**（9 提取 / 7 入库，vision 已花），预留预算要按**提取图数**；③ vision 是唯一有真实 token 的环节，两类 embedding 端点
> **token 不可得**（口径见 `docs/token-metering.md`），只能按调用次数计价。

## 5. 判别阈值（灰度期查询侧口径）

- 程序化套件 `ImageCrossModalSuite` 的 **0.90 保持不变**（其语料是程序化 6 图，改它会动 `image-baseline-v1` 可比性）。
- **真实语料**的判别阈值以定标锚为准：`docs/rag-quality/image-threshold-calibration-v1.json`
  （`threshold.recommendedThreshold`，由「同图下限 / 异图上限」区间中点定义；不可分时该文件明确写「不给硬阈值」）。
- 生产灰度实测提醒：真实语料异图余弦最高到 **0.9887**（`production-gray-cost-v1`），**0.90 在真实语料上会把异图判成同图**——
  所以真实语料闸门读定标锚，不读 0.90。

## 6. 回滚（可逆，写回即可）

1. `rag.retrieval.image-vector-ingest.enabled = false`（先关费用最高的真视觉路）；
2. 需要全停时再 `rag.retrieval.image-slice-ingest.enabled = false`（方案 B 停 = 完全回到升级前行为，文本摄取不受影响）；
3. 已落盘的图片资产/切片不自动清理：重跑该 KB 的**重建入库**会按既有清理链（软删 → 清向量 → 删对象 → 物理删行）收口；
4. 不需要也不允许改代码、改默认值或回滚迁移（本卡零迁移）。

## 7. 灰度期观察指标

| 指标 | 用途 |
|---|---|
| `ai.token.total{type="vision"}` | vision 真实 token（唯一可读的真 token 账） |
| `ai.token.record.calls{type="embedding"}` | embedding **调用次数**（token 不可得时的唯一真账） |
| `ai.token.usage.unavailable{type="embedding"}` | token 不可得声明条数（应与 embedding 调用次数同步增长；不同步说明端点行为变了） |
| `ai.token.record.failure` | 计量落库失败（>0 说明账单有缺口） |
| 摄取 WARN「描述质量闸门」/「图片提取失败」 | 沉没成本与失败率 |

## 8. 未解决项 / 待决策项

1. **生产开关是否真的打开**（Owner 决）：本文件只登记路径与成本，不构成打开授权；打开动作本身是运行期操作，需 Owner 按
   §4 的成本基线拍板。
2. **图帽 5 的预算含义**：真实文档动辄上百张内嵌位图（DOE 那份实测 107 张），帽 5 决定单文档 vision 上限 ≈ 5 × 1 371 token
   ≈ 6 855 token（不含沉没）；若灰度期发现单文档成本超预期，优先调**图帽**再考虑关开关。
3. **KB 白名单是唯一灰度粒度**（观察）：没有 per-document / per-filename 灰度；需要更细粒度须另立卡（本卡不改代码）。
