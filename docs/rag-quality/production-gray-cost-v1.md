# 真共享空间生产灰度成本基线 v1（PRODUCTION-GRAY-COST-1.0）

> 落盘物：`docs/rag-quality/production-gray-cost-v1.json`（本文件的全部数字取自该 JSON，不做二次加工）。
> 生成方式：`ProductionGrayRealRunIT` 真跑（`RAG_VISION_EMBED_REAL=1` + `DASHSCOPE_API_KEY`）。**只读基线**，不含任何生产代码改动。
> 语料：**真实公有领域政府出版物 PDF**（owner 拍板入库 `src/test/resources/rag-quality/real-pdf/`）。

## 1. 这张基线回答什么

前四张多模态卡的语料全是**程序化确定性图/PDF**（`pdfBytes()` 现场生成）。本卡第一次用**真实生产文档**跑完
「摄取 → 真视觉向量 → 真文本向量 → 双集合 → query-by-image 检索」，回答一个决策问题：

> 把 `rag.retrieval.image-vector-ingest.enabled` 真正打开后，**每张真正入库的图**要花多少次外呼、多少 token、多少时间？

与既有基线的边界：独立套件版本号 `PRODUCTION-GRAY-COST-1.0`、独立落盘文件；**不碰** `baseline-v1~v7`、`image-baseline-v1`、
`pdf-ingest-image-baseline-v1`（后者仅被**只读**引用作对照，见 §6）。

## 2. 语料与授权（3 份，合计 6.86 MB / 157 页）

| key | 机构 | 来源 URL | 体积 | 页数 | 生产提取器实测可提取位图 |
|---|---|---|---|---|---|
| `nist-sp800-101r1` | NIST | `https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-101r1.pdf` | 1.28 MB | 85 | 8 |
| `nist-sp800-183` | NIST | `https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-183.pdf` | 2.73 MB | 30 | 5 |
| `doe-renewable-energy-framework` | DOE | `https://www.energy.gov/sites/default/files/2018/11/f57/robi-wind.pdf` | 2.85 MB | 42 | 107 |

- 资产与授权逐份登记在 `src/test/resources/rag-quality/real-pdf/sources.json`（URL + SHA-256 + 授权依据 + 授权原文摘录）。
- 授权结论：三份均为美国联邦政府雇员职务作品，依 **17 U.S.C. §105** 不受美国版权保护；NIST 站点声明
  「除标注为受版权保护的材料外，NIST 站点上的信息视为公共信息，可分发或复制」（`https://www.nist.gov/copyrights-disclaimers`）。
- **可提取性是真实验证过的，不是"看着有图"**：`RealPdfAssetsTest` 逐份调生产 `PdfImageExtractor#extract` 实测
  ≥2 张 `PDImageXObject` 位图、最短边 ≥64px、PNG 可解码、SHA-256 与登记一致（默认门禁恒跑，零外呼）。

## 3. 真跑链路（每段都是生产实现）

1. `PdfImageSliceIngestService#buildSlices`（总开关 + 视觉路开、每文档图帽 3）→ `PdfImageExtractor` 取 `PDImageXObject` → 解码 PNG
   → **真 `vision` 描述** → 描述质量闸门（≥40 字）→ 图片对象落存储 → **真 `embedImage`** 暂存视觉向量；
2. `ImageVectorFlushSupport#flush` 按 storageKey 回填切片行（`chunkId` = 切片行主键）→ payload 走
   `DocumentIngestionSupport#createVectorRecord` → **image 集合**；
3. 同一批 VLM 描述文本 → **真文本 embedding** → **文本集合**（与 image 集合并存，互不替代）；
4. **query-by-image**：查询图 = **落盘的那张 PNG 字节**重新 `embedImage` → 生产 `KnowledgeRetrievalServiceImpl#recallImage`
   召回 image 集合，黄金 id 走生产口径 `IMAGE:{行主键}`；文本对照路 = 同一描述文本查文本集合。

## 4. 实测成本（2026-10-22，本机，`qwen3-vl-plus` / `text-embedding-v3` / `multimodal-embedding-v1`）

### 4.1 摄取段（真实 PDF → 切片 → image 集合）

| 文档 | 提取图数 | 过闸切片 | 闸门剔除 | 视觉向量入库 | vision 外呼 | 摄取耗时 |
|---|---|---|---|---|---|---|
| `nist-sp800-101r1` | 3 | 3 | 0 | 3 | 3 | 12.6 s |
| `nist-sp800-183` | 3 | 3 | 0 | 3 | 3 | 38.8 s |
| `doe-renewable-energy-framework` | 3 | 1 | **2** | 1 | 3 | 7.6 s |
| **合计** | **9** | **7** | **2** | **7** | **9** | **59.0 s** |

> 图帽 3 是本卡成本帽（真实文档动辄上百张内嵌图：DOE 那份实测 107 张可提取位图）。`extractedImages` 是**生产提取器**在帽内
> 真实返回的图数，`slicedImages` 是**过了描述质量闸门**的图数——两者之差就是"成本花了但没进库"的图（DOE 那份 2 张）。

### 4.2 调用次数与 token（CountingProvider 真调用点计数，非公式推导）

| 口径 | 实测值 | 说明 |
|---|---|---|
| `visionCallCalls` | **9** | 每张提取图一次（**含被闸门剔除的 2 张**：描述先于闸门） |
| `visionEmbeddingCalls` | **14** | 摄取侧暂存 7 + query-by-image 查询侧 7 |
| `textEmbeddingCalls` | **32** | 描述编码 7 + 查询侧 25（生产文本召回按确定性拆路多路 embed，次数随查询文本形态浮动，只能在调用点实测） |
| `totalCalls` | **55** | — |

| token 口径 | prompt | completion | total |
|---|---|---|---|
| vision（9 次，9 次回流 usage） | **8 001** | **1 599** | **9 600** |
| image embedding（14 次） | 0 | 0 | **0** |
| text embedding（32 次） | 0 | 0 | **0** |

**关键发现（口径纠正）**：上一卡记录的"视觉端点不回流 usage"在本机**不成立**——本次 vision 9 次调用全部回流 usage，
真实出 token **9 600**（prompt 8 001 / completion 1 599）。相反，两类 embedding 端点虽返回 usage 对象，但**token 恒 0**
（`callsWithUsage` 记到次数，token 记 0）——即"有样本、无计量"，不能当成"零成本"。

### 4.3 耗时

| 段 | 实测 | 口径 |
|---|---|---|
| 摄取段（3 份 PDF） | **59 019 ms** | 含 9 次 vision + 7 次 embedImage + 落盘 |
| 描述文本编码 | **1 512 ms** | 7 次文本 embed |
| 查询段（7 条 query-by-image + 7 条文本对照） | **7 893 ms** | 7 次 query embedImage + 文本查询侧 25 次 embed + 检索 |
| 三类外呼耗时合计 | **65 819 ms** | 不含测试装配与判分 |
| vision 单次均值 / 峰值 | **5 939 ms / 20 776 ms** | 峰值出现在文字最密的截图（描述 1 339 字） |

### 4.4 每图成本（决策用的那一列）

| 指标 | 实测（按 7 张入库图摊分） |
|---|---|
| vision 外呼 / 图 | **1.29**（9 / 7，含被剔除图的沉没成本） |
| 视觉 embedding / 图 | **2.00**（摄取 1 + 查询 1） |
| 文本 embedding / 图 | **4.57**（32 / 7，含查询侧多路） |
| 端到端耗时 / 图 | **≈ 9.8 s** |
| vision token / 图 | **≈ 1 371**（9 600 / 7） |

## 5. 检索结果（链路正确性判据，跑之前固定）

| 指标 | 实测 |
|---|---|
| 用例数（每张入库图一条 query-by-image） | 7 |
| 视觉路命中率（召回自己那张切片） | **7/7 = 1.00** |
| 文本对照路命中率 | **7/7 = 1.00** |
| 同图余弦最小值 | **1.0000**（阈値 0.90） |
| 异图余弦最大值 | **0.9887** |

- 同图余弦 ≈1.0 是必然的：查询图就是**落盘那张 PNG 的字节**再次编码，做不到就说明链路接错（image 集合装配 / 过滤键 / chunkId 映射）。
- **真实语料的异图关系不是程序化语料那种"摆好的"关系**：最大值 0.9887 来自 NIST 两份文档的**首页版式图**（同为"页面级排版 + 徽标 +
  大标题"视觉语域），余弦逼近同图，但仍严格低于同图余弦（判据取"异图 < 同图"的**顺序**，不设 0.90 硬阈值——真实语料里"视觉近似同图"
  是常态，硬阈值会把链路正确性判成模型能力问题）。程序化语料的异图间隔是 +0.13~+0.45，真实语料里**这个间隔可以只有 +0.011**。
- 文本对照路的同图余弦同样是 1.0，但那是**同文再编码**（查询文本 = 该图描述原文），只证明"文本集合接通了"，不构成质量主张。

## 6. 真实 PDF vs 程序化 PDF：成本差异观察

对照物 = `pdf-ingest-image-baseline-v1.json`（程序化 3 页 3 图，11 KB）：

| 维度 | 程序化 PDF（上一卡） | 真实 PDF（本卡） | 差异根因 |
|---|---|---|---|
| 文档体积 | 11 KB | **6.86 MB**（622×） | 真实出版物含大量矢量排版与位图 |
| 页数 / 图帽 | 3 页 / 3 图 | 157 页 / 9 图（帽 3×3） | 真实文档图分布稀疏，图数由帽而非页数决定 |
| 提取 → 入库转化 | 3 → 2（剔 1） | 9 → 7（剔 2，均在 DOE） | 真实图更易撞描述闸门（低信息量图/纯版式图） |
| vision 外呼 | 3 | 9 | 语料图数 |
| vision token | （未回流，记 0） | **9 600**（真实回流） | 真实截图文字密，输出长度是程序化图形的 3~10× |
| 文本 embedding 调用 | 8 | **32** | 描述条数与查询侧多路拆路 |
| 端到端耗时 | 未按图摊分 | **9.8 s / 图** | vision 单次均值 5.9 s 主导 |
| 检索判据可靠度 | 异图间隔充足（+0.13~+0.45） | 异图间隔可低至 **+0.011** | 真实语料存在"视觉近似同图"的版式图 |

**结论口径（建议写进灰度决策）**：

1. 成本必须按**每张真正入库的图**摊分，不能按"每份 PDF"（真实文档页数/图数方差极大）或"每张提取图"（闸门剔除的图花了 vision
   却不出切片）。
2. 打开开关后，**摄取期每图固定 1 次 vision + 1 次视觉 embedding**；查询期每图 1 次视觉 embedding + 文本路 ≥1 次文本 embedding。
3. vision 是唯一有真实 token 计量的环节（本机 ≈1 371 token/图），另两类 embedding 端点**不回 token**，只能按调用次数计费口径估算。
4. 描述质量闸门是**成本漏斗**：真实文档 9 提取 → 7 入库（22% 沉没），灰度期应按"提取图数"预留 vision 预算。

## 7. 复现命令

```bash
# 1) 真跑（落盘成本基线；无 opt-in 或 key 时整类 Assume 跳过，零外呼）
RAG_VISION_EMBED_REAL=1 mvn -B -ntp failsafe:integration-test failsafe:verify \
  -Dit.test=ProductionGrayRealRunIT \
  -Drag.pdf.image.vision-model=qwen3-vl-plus

# 2) 默认全量（零外呼，本类 +1 skipped）
mvn -B -ntp clean verify
```

**本机模型事实（留痕，影响复现）**：生产默认视觉模型 `qwen-vl-plus` 在该 `DASHSCOPE_API_KEY` 上返回 `404 model_not_found`（同机既有
`VisionPdfRealPilotIT` / `PdfIngestImageBenchmarkIT` 同症状，与本卡改动无关），故真跑用 `-Drag.pdf.image.vision-model=qwen3-vl-plus`；
实际使用的模型写进 JSON 的 `visionModel`。换环境若生产默认可用，去掉该属性即可。

## 8. 未解决项 / 待决策项

1. **DOE 那份的逐图版权未单独溯源**（待决策）：文件本身是联邦政府出版物，但 usa.gov 明确提示「联邦政府网站内容可能包含经受权使用的
   受保护知识产权」。本卡按「政府出版物原文 + 测试夹具、非商业再发布」使用并在 `sources.json` 记了 `licenseCaveat`。若 owner 要求逐图
   溯源，可只保留两份 NIST（NIST 声明最直接），或替换 DOE 那份。
2. **异图硬阈值不适用于真实语料**（待决策）：本卡只断言"异图余弦 < 同图余弦"的顺序，未断言 `< 0.90`。真实语料的版式图让 0.9887 成为
   合法值；若要给灰度设判别力闸门，应在后续卡用**多图同类/跨类语料**定标，而不是套用程序化语料的 0.90。
3. **每文档图帽 3 是成本帽，不是生产默认值**（说明）：生产默认 `max-images-per-document=5`（硬上限 20），本卡为控制真跑成本取 3；
   灰度决策时要按生产默认帽重算 vision 预算（每图 ≈1 371 token、≈5.9 s）。
4. **embedding 端点 token 不回流**（观察）：image/text embedding 均返回 usage 对象但 token 恒 0，成本只能按调用次数计量；若需 token 级
   账单，须由 Provider 侧补计量（属 `src/main` 改动，需另开卡）。
5. **单次 vision 峰值 20.8 s**（观察）：文字最密的截图（描述 1 339 字）单次耗时是均值的 3.5×，灰度期建议保留超时与降级路径（生产
   `timeout-seconds` 默认 60 s，本卡真跑未触发超时）。
