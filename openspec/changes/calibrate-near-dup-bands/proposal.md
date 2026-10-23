# 变更提案 · calibrate-near-dup-bands（近重复三档定标真跑 v2：带回带样本、出 v2 锚）

> 复核档位自 declare：**A 档**（判据「真跑 + 外呼」：本卡真跑视觉 embedding，产出新的定标锚并改写落带结论）。
> 复核按 `docs/review-intensity-policy.md` 独立四判据走，**不因「只改测试与文档」降档**。
> Owner 授权口径（2026-10-24）：「推真跑定标，语料你自己找，授权外呼」——语料选型与一次外呼授权已给定，**不改权重常量**不在授权内。

## 1. 为什么必须真跑

`ImageSimilarityGate` 的三档降权 `0.75 / 0.6 / 0.5`（`NEAR_DUPLICATE_WEIGHT_HIGH/MID/LOW`）目前只有一条证据支撑：
最低档 0.5 = 旧统一系数。`docs/rag-quality/image-near-duplicate-bands-v1.md` §6.2 已如实写明——
**`NEAR_HIGH` / `NEAR_MID` 在 v1 锚的 90 对上零样本**，两个中高档权重从未被任何样本校准；
`NEAR_LOW` 只有 1 个样本，且那个样本还是**异图**（跨出版物版式图 0.9887359）。

v1 语料是「9 图 + 9 图两两组合」，**结构上不可能产生同源近重复对**（同图变体），所以缺样本不是运气问题，是语料设计问题。
本卡把"同图变体"造进语料，才能第一次看到间隔带 `[0.9887, 0.9944)` 里到底装的是什么。

## 2. 语料来源定夺（Owner 已授权"语料你自己找"）

**定夺：复用已登记的三份公有领域 PDF 派生，不下载新文件、不新增二进制、不改 `sources.json`。**

| 文档（`src/test/resources/rag-quality/real-pdf/`） | 实测有效位图 | 本卡取 | 说明 |
|---|---|---|---|
| `nist-sp800-101r1-mobile-forensics.pdf` | 8 | 8 | 全取 |
| `nist-sp800-183-networks-of-things.pdf` | 5 | 4 | 取前 4 |
| `doe-renewable-energy-framework.pdf` | **107** | 12 | 取前 12 |
| **base 合计** | | **24** | 按 SHA-256 去重后的实际值（去重后不足则记实际值，不得凑数） |

三条理由：

1. **版权与仓库成本已结清**：三份资产是 Owner 拍板入库的美国政府出版物（`sources.json` 逐份登记 URL + SHA-256 + 授权原文），复用它们零新增授权风险；变体在**内存**里生成、只落余弦，`git` 里不出现任何图片字节。
2. **`3` 只是成本帽，不是能力上限**：`RealPdfAssets.MAX_IMAGES_PER_DOCUMENT = 3` 是 `production-gray-real-run` 的取图帽，`extract(byte[], int)` 本身接受任意上限。本卡在**新类**里传 8/4/12，不动那个常量（动了会改既有真跑 IT 的成本口径）。
3. **先证伪再扩语料**：先回答"现有语料能不能把间隔带填起来"。若 v2 显示带内主要装异图（走 §4 的 F3），才需要下载新语料 + 逐图版权登记 + 二进制入仓——那是另一张卡的预算，本卡不为它先付。

**变体设计（每 base 5 种，确定性可复现，强度从轻到重）**：

| 序 | kind | 做法 | 模拟的生产来源 |
|---|---|---|---|
| 1 | `jpeg-q80` | PNG→JPEG(q0.80)→解码回位图→PNG | 二次压缩 / 转格式 |
| 2 | `scale-085` | 0.85× 双线性重采样 | 客户端预览图 / 缩放导出 |
| 3 | `scale-070` | 0.70× 双线性重采样 | 缩略图 |
| 4 | `crop-04` | 四周各裁 4% 边距 | 版面裁切 |
| 5 | `crop-12` | 四周各裁 12% 边距 | 版面裁切 + 页码/页眉去除 |

变体最短边 < 48px 者跳过并**计数登记**（不静默丢弃）。同页重复扫描无法在无扫描仪的情况下合成，本卡**不覆盖**，在 v2 落带文档里如实标注为未覆盖类。
期望图数 = 24 base + 24×5 variant = **144**；视觉 embedding 调用 = 144 × 2（每图编码两次测同图下限）= **288 次**。

## 3. 现状锚点（实测，非转述）

- **锚 v1**（`docs/rag-quality/image-threshold-calibration-v1.json`，`IMAGE-THRESHOLD-CALIBRATION-1.0`）：`realImageCount 9`、`imageEmbeddingCalls 36`、`real-pdf` 块 `same-image 9 / same-class 9 / cross-class 27`，`crossImageCeiling 0.9887359400788237`、`recommendedThreshold 0.9943679700394117`。
- **落带 v1**（`image-near-duplicate-bands-v1.md` §2，脚本 `scripts/rag-quality/near-duplicate-band-stats.mjs` 已固化复算）：`SAME_IMAGE 18 / NEAR_HIGH 0 / NEAR_MID 0 / NEAR_LOW 1 / CROSS_IMAGE 71`；`NEAR_MID/NEAR_HIGH` 空走廊，`floor` 以下最高的非同一图余弦仅 `0.9778706`。
- **生产闸门**：`ImageSimilarityGate.DEFAULT_THRESHOLD = 0.9944`、`NEAR_DUPLICATE_FLOOR = 0.9887` ⇒ `Δ = 0.0057`、`b1 = 0.9906`、`b2 = 0.9925`（三等分现算，随锚缩放）。
- **写入口唯一性**：v1 锚只能由 `ImageThresholdCalibrationIT` 写；本卡为 v2 锚新增**同构的第二个唯一写入口**，v1 链路一行不改。
- **真跑通道已就绪**：仓库根 `.env` 存在且含 `DASHSCOPE_API_KEY`（长度已核，未读取明文），opt-in 键沿用 `RAG_VISION_EMBED_REAL=1`，默认 CI 与本地 `verify` 恒零外呼。

## 4. 交付（五块）与**预注册判定树**

**交付**：

1. `NearDuplicateCorpus`（新，测试侧）：base 选取 + 5 类变体生成（纯 `javax.imageio`/`Graphics2D`，无随机源、无时间戳），暴露 `bases()` / `variants()` / `skips()`。
2. `NearDuplicateBandAnalyzer`（新，纯函数零外呼）：落带（与 `ImageSimilarityGate` 逐式对齐）+ 分位 + 按强度序的单调性 + 带内精度。
3. `ImageNearDuplicateCalibrationIT`（新，failsafe，opt-in 门控）：**v2 锚的唯一写入口**，落 `docs/rag-quality/image-threshold-calibration-v2.json`。
4. `docs/rag-quality/image-near-duplicate-bands-v2.md`（新）：v1 对照 + 生产视图/锚视图双落带表 + 判定树结论 + 未覆盖类声明；**所有数字必须来自第 5 条的脚本输出**。
5. `scripts/rag-quality/near-duplicate-band-stats.mjs` 扩展 `--anchor <path>` / `--expect <S,N_H,N_M,N_L,C>`；**不带参数时逐字维持 v1 自检行为**（仍打印 `18/0/0/1/71` 并以 EXIT=0 结束）。

**预注册判定树（跑之前写死，跑完不许改口径，只许照判）**：

输入 = v2 锚的 `productionView`（floor 0.9887 / threshold 0.9944）与 `anchorView`（floor = 实测 `crossImageCeiling`、threshold = 其与 `sameImageFloor` 的中点）两块落带计数，以及 `nearDuplicate` 档 120 对的 `p05`。

| 分支 | 触发条件 | 结论（写进 v2 文档，不改任何常量） |
|---|---|---|
| **F0 带可达且样本足** | 每带（`NEAR_HIGH`/`NEAR_MID`/`NEAR_LOW`）≥ **30** 对且带内近重复占比 ≥ 0.5 | 三档获得「样本充分」标记，`0.75/0.6/0.5` 记为**有样本支撑的现值**（仍不改，理由见 §5） |
| **F1 带基本不可达** | `nearDuplicate` 对中余弦 < 0.9887 的比例 > **50%** | 记**缺陷证据**：生产阈值下近似带对真实近重复基本不触发 → 建议另立卡下调 `NEAR_DUPLICATE_FLOOR`（本卡只交 `candidateFloor = nearDuplicate.p05`） |
| **F2 单阈值前提被推翻** | `anchorView` 不可分（`crossImageCeiling >= sameImageFloor`） | 记**锚前提失效**：更大语料下"异图上限 < 同图下限"不成立 → 建议另立卡重议阈值语义（本卡不硬凑阈值，不比 v1 更乐观） |
| **F3 带内装的是异图** | 某带 ≥ 30 对但带内近重复占比 < 0.5 | 该带降权方向需重新论证（降权打在"看着像但不是同一张"的图上而非真重复上）→ 另立卡；本卡只如实记录 |
| **F4 样本仍不足** | 任一近似带 < 30 对 | 按 `image-near-duplicate-sampling-v2.md` §2 门槛：**该带权重维持现值**，缺样本不是调整理由 |
| **F5 锚视图优于生产视图** | `anchorView` 每带样本数 > `productionView` 同带样本数，且 F1 未触发 | 记录"现有 floor 取值把本该降权的近重复挡在带外"的证据强度，供后续卡决定 floor 取 `crossImageCeiling` 还是 `nearDuplicate.p05` |

**任分支都不改常量**：`ImageSimilarityGate` 三档权重、`DEFAULT_THRESHOLD`、`NEAR_DUPLICATE_FLOOR` 全部**零 diff**。

## 5. 为什么"样本足了也不在本卡改权重"

本卡能量的是**分布与可达性**，量不了"权重取多少最优"：把近重复候选乘 0.5 还是 0.75，只有在**有查询-相关性真值**（哪些 chunk 该进 top-k）时才有唯一解；余弦分布不含排名效用信息。
拿"带内多数是同源变体"直接推出"应上调到 0.8~0.9"（`sampling-v2.md` §7 那条门槛的粗糙版）是**用分类证据冒充排序证据**，比不改更危险。
因此本卡的产出边界写死为：**给出可达性、带内精度、强度单调性三个可复核事实 + `candidateFloor` 一个候选值**；真要让权重数字落地，另立卡并自带排序口径。

> 客观事实（供 Owner 判断）：三档权重对**近似带内部**的排序是保序的（`w` 随 `c` 单调不减，`c₁>c₂ ⇒ c₁w₁>c₂w₂`），
> 所以它们只改变"近似重复组 vs 低分异图组"的相对位置，不改变"越像同图越靠前"。这也是它可以在无排序真值时**安全维持现状**的原因。

## 6. 非目标

- 不改任何生产常量、不改 v1 锚、不改 `image-near-duplicate-bands-v1.md`、不改 `sources.json`、不改 `RealPdfAssets` / `ImageThresholdCalibration*`（v1 链路冻结）。
- 不下载新语料、不新增二进制资产、不引入新依赖、零迁移、零契约解冻。
- 不打开 `rag.retrieval.image-vector-ingest.enabled`，不跑检索端到端评测。
- 不同页重复扫描类语料（无法合成，如实标注未覆盖）。

## 7. 风险与取舍

| 风险 | 处置 |
|---|---|
| 真跑外呼失败/超时污染锚 | 单次调用失败即整体失败（不写半成品锚）；锚落盘只在全部编码完成后一次写 |
| 288 次外呼成本失控 | 语料规模写死为常量（24 base × 5 变体 = 120 变体）；要改动就等于改本卡口径，须重新登记；跑后回报实际调用数与耗时 |
| 把 `--anchor` 参数改动弄坏 v1 自检 | 不带参数路径必须是**逐字**旧行为，作为独立验收项单列 |
| 变体生成引入随机性 → 不可复现 | 禁用随机源与时间戳；同名 base 重生成必须字节相等（单测守） |
| 门禁误触发真跑 | 门禁命令前必须清除 `RAG_VISION_EMBED_REAL`；缺 key/未 opt-in 时 IT 走 `Assumptions` 跳过 |

## 8. 验收

`tasks.md` 逐项；门禁 = `mvn -B -ntp clean verify` + `bash scripts/check-test-baseline.sh --update` 后 `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿 + `bash scripts/check-openapi-consistency.sh` EXIT=0（本卡零契约改动，应零 diff）。
复核方独立四判据：①真跑证据（锚的 `generatedAt`/`optIn`/调用数与本次运行自洽）②预注册判定树照判无事后改口径 ③v2 数字可由脚本逐数复算 ④常量与 v1 资产零 diff。
