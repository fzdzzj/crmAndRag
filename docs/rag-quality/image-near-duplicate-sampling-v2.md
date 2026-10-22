# 近重复权重定标扩样操作单 v2（reserve-sweep-1 S3）

> 卡：`reserve-sweep-1`（S3 近重复定标管线）。性质：**操作单 + 口径登记**，本卡**不真跑、不改权重、不改锚 JSON**。
> 输入锚：`docs/rag-quality/image-threshold-calibration-v1.json`（`IMAGE-THRESHOLD-CALIBRATION-1.0`，90 对）。
> 现状结论：`docs/rag-quality/image-near-duplicate-bands-v1.md`（`same 18 / high 0 / mid 0 / low 1 / cross 71`）。
> 复算命令：`node scripts/rag-quality/near-duplicate-band-stats.mjs`（本卡新增，必须复现 18/0/0/1/71，否则非零退出）。

## 1. 为什么必须扩样

v1 锚的 90 对里，`NEAR_HIGH` 与 `NEAR_MID` **零样本**、`NEAR_LOW` **单样本**，而三档权重 0.75 / 0.6 / 0.5
（`ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH/MID/LOW`）只被「最低档 = 旧统一系数 0.5」这一条证据支撑。
换成一句能评审的话：**当前三档在结构上单调、与锚相容，但中高档权重未经任何样本校准**；`image-near-duplicate-bands-v1.md`
§6 已如实写明这一点，本文件把「怎么补样本」变成可复跑的操作单，而不是继续手算。

## 2. 目标（判定门槛写死在这里，跑完不许改）

| 带 | v1 样本数 | 扩样目标 | 用途 |
|---|---|---|---|
| `SAME_IMAGE` | 18 | ≥ 30 对 | 维持同图不降权（回归保护，不参与权重调整） |
| `NEAR_HIGH` | **0** | **≥ 30 对** | 唯一能支撑 0.75 上调/下调的样本 |
| `NEAR_MID` | **0** | **≥ 30 对** | 唯一能支撑 0.6 调整的样本 |
| `NEAR_LOW` | **1** | **≥ 30 对** | 验证 0.5 不放松 |
| `CROSS_IMAGE` | 71 | ≥ 30 对（负例下限） | 确认没有新增「异图被错判进近似带」 |

**门槛**：任一近似带不足 30 对 ⇒ 该带权重**维持现值、不调整**（缺样本不是调整理由）。
「每带 ≥30 对」是**下限**，语料能多就给多，不得为凑数删既有样本。

## 3. 需要哪类语料（按带设计，不按结果调参）

**同图变体（目标是落进近似带）**

- **缩放**：同图 0.5× / 0.75× / 1.5× 与原始分辨率各一份（重采样引入的微小位移正是近似带的来源）。
- **裁剪**：同图裁掉 5% / 10% / 20% 边距（模拟版面裁切）。
- **重压缩**：同图 JPEG 质量 95 / 80 / 60 与 PNG→JPEG 互转（模拟转格式与二次压缩）。
- **同页重复扫描**：同一页 PDF 扫描两次（或同一页两版导出），这是生产里最常见的近重复来源。

**异图对照（目标是落进 `CROSS_IMAGE`，防误召回）**

- **跨出版物版式图**：不同报告/论文的表格页、折线图页各取若干（v1 中唯一 `NEAR_LOW` 样本就是这类，见 v1 文档 §5）。
- **同版式不同内容**：同一模板填不同数据的图表页（结构相似但内容不同，最容易被视觉 embedding 判高）。

**语料规模折算（保守口径）**：≈ 120 张图 = 30 张/近似带 × 3 带（变体组内两两组合即产生同带对）+ 30 张异图对照。
真实文件必须落进 `RealPdfAssets` 同源资产或另行登记的公开领域语料，**不得用仓库里的程序化 9 图顶替**（那是稳定底座，不是扩样）。

## 4. 重跑前置（唯一写入口，禁止手改锚）

```bash
# 1) 显式真跑开关 + key（缺一即整类按假设跳过，构建不失败）
export RAG_VISION_EMBED_REAL=1
export DASHSCOPE_API_KEY=...            # 或仓库根 .env（IT 会回退读取）
# Windows PowerShell 等价写法：$env:RAG_VISION_EMBED_REAL='1'; $env:DASHSCOPE_API_KEY='...'

# 2) 唯一写入口：ImageThresholdCalibrationIT（failsafe IT；命令行 -D 会自动透传进 forked JVM）
#    落盘路径靠 -Drag.image.threshold.out 覆盖，**不要覆盖 v1 锚**
mvn -B -ntp test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=ImageThresholdCalibrationIT \
  -Drag.image.threshold.out=docs/rag-quality/image-threshold-calibration-v2.json
```

- **锚的唯一写入口**就是这条 IT（`ImageThresholdCalibrationIT` → `ImageThresholdCalibrator#pairs/calibrateByCorpus`），
  不许手改 JSON、不许另写脚本回写。
- 两类语料都走生产提取器 `PdfImageExtractor`，同字节按 SHA-256 去重——**同字节不算异图**。
- 每图**编码两次**（同字节再编码），两次的余弦就是该图的「同图下限」观测。
- 默认 verify 与 CI 恒零外呼：没有 `RAG_VISION_EMBED_REAL=1` + key 时整类跳过，**不要**为了「跑绿」把它改成默认执行。

## 5. 成本估算口径（复用既有基线，不新造单位）

复用 `docs/rag-quality/production-gray-cost-v1.json` / `.md` 的口径：**按调用次数摊分，不按 PDF 页数**；
vision 是唯一有真实 token 的环节，两类 embedding 端点 **token 不可得**（口径见 `docs/token-metering.md`）。

| 项 | 本次扩样的用量 | 依据 |
|---|---|---|
| 视觉 embedding 外呼 | **图数 × 2**（每图编码两次）；120 图 ⇒ ≈ 240 次 | `ImageThresholdCalibrationIT` 的两次编码设计 |
| vision 描述外呼 | **0 次** | 本 IT 只做 embedding，不经 VLM 描述链路 |
| vision token | **0** | 同上；灰度链路的 ≈1 371 token/图 不适用 |
| 端到端耗时 | 按 240 次 embedding 调用逐条累计（参考灰度实测 ≈9.8 s/图的端到端量级，仅作上限参考） | `production-gray-cost-v1` §4 |

> 结论口径：**扩样的成本 = 图数 × 2 次视觉 embedding 调用**，token 不计（读不到）。预算按「提取成功的图」计，
> 提取失败不算沉没（本 IT 不走描述质量闸门）。

## 6. 产出与落盘

1. 新锚落 **`docs/rag-quality/image-threshold-calibration-v2.json`**（v1 原样保留，历史结论可复现）。
2. 用本卡脚本复算新锚：`node scripts/rag-quality/near-duplicate-band-stats.mjs`（脚本读 `-v1` 锚；
   换锚时复制脚本改路径或加参数，**不要**把 v1 的基线数字改掉）。
3. 新落带表推翻或确认 v1 的「零样本」结论，写进新的 `image-near-duplicate-bands-v2.md`（本卡不建该文件）。
4. 若新锚的 `recommendedThreshold` 变化：`ImageSimilarityGate.DEFAULT_THRESHOLD` 与
   `docs/rag-quality/image-threshold-calibration-v1.md` 的一致性说明同步更新（属改常量，另立卡 + 复核）。

## 7. 权重改动判定门槛（跑完才允许谈改权重）

| 观察 | 处置 |
|---|---|
| `NEAR_HIGH` ≥30 对且多数人工判「同一张图的不同版本」 | 可上调至 0.8~0.9（仍 < 1.0） |
| `NEAR_HIGH` ≥30 对且多数是异图 | 维持 0.75 或下调（**不得低于 0.5**） |
| `NEAR_MID` 仍 <30 对 | **不动 0.6**（与相邻档差 0.15 已足够表达单调性） |
| `NEAR_LOW` <30 对 | **不动 0.5**（上调 = 放松近似图排序，下调 = 加重误杀） |
| 任何档改动 | 必须另立卡：改 `ImageSimilarityGate` 权重常量 + 重跑本 IT + 全量门禁，**不在文档里改** |

> 边界：**权重常量与锚 JSON 的改动只能由真跑 + 独立卡完成**；本卡只交管线与操作单，`git diff master -- ImageSimilarityGate.java image-threshold-calibration-v1.json` 必须为空。

## 8. 本卡实际执行情况

- **S3 是否真跑：否。** 未设置 `RAG_VISION_EMBED_REAL`、未外呼、未产生新样本、未改锚、未改权重。
- 已交付：`scripts/rag-quality/near-duplicate-band-stats.mjs`（复现 v1 落带 18/0/0/1/71，EXIT=0）+ 本操作单。
- 待 Owner 授权项：扩样真跑（需明确授权 + key + 预算），并指定新锚文件名与扩样语料来源。
