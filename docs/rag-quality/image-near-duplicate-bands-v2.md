# 近重复三档落带（v2 · 真跑定标）

> 变更卡：`openspec/changes/calibrate-near-dup-bands/`（A 档真跑）。本文**全部数字抄自**
> `node scripts/rag-quality/near-duplicate-band-stats.mjs --anchor docs/rag-quality/image-threshold-calibration-v2.json --expect 207,3,1,6,323`
> 的输出（§14 有完整复算命令），唯一例外是 §1.1 的成本台账（IT 控制台叙事，已在表内标注来源）。
> 锚的唯一写入口 = `src/test/java/com/slz/crm/quality/ImageNearDuplicateCalibrationIT.java`（opt-in `RAG_VISION_EMBED_REAL=1`）。

## 0. 结论（三句）

1. **间隔带（`[0.9887, 0.9944)`）在 144 图 / 540 对的真跑语料上仍然几乎装不进真近重复**：生产视图三档合计只有
   `3 / 1 / 6` 对，而 120 对同源变体里有 **50 对（41.7%）落在 floor 以下**——不是"带内装的是异图"，是"整段近重复区间对这批变体基本不可达"；
   但该比例 **没有过 0.5 的 F1 门槛**，所以按预注册口径 **F1 不命中**，本卡**不给候选下限**（`candidateFloor = null`）。
2. **判定树只命中 F4**（三带 `3 / 1 / 6` 全部 < 30 对）⇒ 按 `image-near-duplicate-sampling-v2.md` §2 的门槛，**三档权重维持现值
   `0.75 / 0.6 / 0.5`**，缺样本不是调整理由；F0 / F1 / F2 / F3 / F5 全部不命中，**任何常量零改动**。
3. 首轮的 `F1 命中 + candidateFloor = 0.4199` 结论**作废**：它建立在 6 个「变体坍缩」的退化 base 上（30/120 对），
   本轮先修栅格化（白底合成）再重跑；修后这 6 个 base 全部保留、像素属性与 18 个对照 base 同量级，**退化剔除 0 个**。

## 1. 语料与成本（锚内 `corpus` 块）

| 项 | 值 |
|---|---|
| base（SHA-256 全局去重 + 退化剔除后） | **24** |
| 变体（每 base 5 类确定性变换） | **120** |
| 图总数 | **144** |
| 变体跳过（最短边 < 48px） | **0** |
| 视觉 embedding 调用 | **288**（= 图数 × 2，每图编码两次测同图下限） |
| 样本对 | **540** = 144（同图）+ 120（近重复）+ 276（cross = 24×23/2） |
| 用时 | `elapsedMillis = 469111`（469.1 s，节奏 1100 ms/次 ≈ 0.61 图/秒） |
| 失败调用 / 限流重试 | **0 / 0** |
| 锚生成时间 | `generatedAt = 2026-10-23T06:01:52.956643300Z` |

源语料 = `sources.json` 已登记的三份美国政府出版物经生产 `PdfImageExtractor` 各取前 8 / 4 / 12 张位图（不下载新文件、不新增二进制、
不改 `sources.json`）；变体在内存里生成，`git` 里不出现任何图片字节。

### 1.1 外呼成本台账（含作废轮，与锚显式对账）

**对账口径**：锚里的 `corpus.imageEmbeddingCalls` 只记**一次成功运行**的调用数（本锚 288，脚本 §10 已机械校验 `calls = images×2`）；
"累计"是**整卡生命周期内真实打到端点的成功调用数**，二者不是同一个量，不可互相替代。

| 轮次 | 结果 | 成功外呼 | 说明 |
|---|---|---|---|
| run 1 / 2 | 失败，无锚 | 0 | JDK `HttpClient` 进程内首次连接挂起（裸 `Socket` 50 ms、`.NET`/`curl` 正常）→ 连接超时抬到 60 s、读超时 120 s |
| run 3 | 失败，无锚 | 0 | 新类未过 spotless，validate 阶段即失败 |
| run 4 | 失败，无锚 | **144** | 端点 2 QPS 触发 `429 Throttling.RateQuota`（第 72 图）→ 加 1100 ms 节奏 + 仅 429 的有界退避 |
| run 5 | 失败，无锚 | **288** | 同图档只覆盖 base（144 期望 vs 24 实际）——配对口径 bug，fail-fast 未落半成品锚 |
| run 6 | 成功，落锚 → **后被判定作废** | **288** | 首版 v2 锚；6 个 base 变体坍缩（见 §11），F1/candidateFloor 结论作废 |
| run 7（本锚） | 成功 | **288** | 白底合成修复后重跑，覆盖同一路径 |
| **累计** | | **1008** | = 144 + 288 + 288 + 288 |

作废轮**没有**写锚（fail-fast 生效），所以锚里看不到它们；它们的成本记在这张表里而不是锚里。

## 2. 与 v1 的对照

| 项 | v1（`image-threshold-calibration-v1.json`） | v2（本锚） |
|---|---|---|
| 语料 | 9 图（两两组合） | **144 图**（24 base + 120 同源变体） |
| 样本对 | 90 | **540** |
| 外呼 | 36 | **288** |
| 同源近重复对 | **结构上不可能有**（0 对） | **120 对** |
| 生产视图落带 | `18 / 0 / 0 / 1 / 71` | `207 / 3 / 1 / 6 / 323` |
| 三档样本数 | `NEAR_HIGH 0 / NEAR_MID 0 / NEAR_LOW 1` | `3 / 1 / 6` |
| 同图下限 | 1.0000000000000002（max） | `same-image.min = 0.9999999999999998` |
| 异图上限 | `0.9887359400788237` | `0.9933404610426125` |
| 判定树 | 空走廊，无可判样本 | F4 命中，其余不命中 |

**一句话读法**：v2 把 v1「结构上不可能产生同源近重复对」的缺陷补上了（120 对真变体），代价是三档依旧没有 30 对/带——但现在的"没有"
是**实测出来的没有**，不是语料缺陷。

## 3. 生产视图落带（floor 0.9887 / threshold 0.9944，`ImageSimilarityGate` 现值）

| 带 | 区间 | 对数 | 占比 | 近重复对 | 带内精度 |
|---|---|---|---|---|---|
| `SAME_IMAGE` | `[0.9944, 1]` | 207 | 38.3% | 63 | 0.3043 |
| `NEAR_HIGH` | `[0.9925, 0.9944)` | **3** | 0.6% | 2 | 0.6667 |
| `NEAR_MID` | `[0.9906, 0.9925)` | **1** | 0.2% | 1 | 1.0000 |
| `NEAR_LOW` | `[0.9887, 0.9906)` | **6** | 1.1% | 4 | 0.6667 |
| `CROSS_IMAGE` | `[0, 0.9887)` | 323 | 59.8% | 50 | 0.1548 |

`floor=0.9887  threshold=0.9944  Δ=0.0057  b1(低/中)=0.9906  b2(中/高)=0.9925`

三档合计 **10 对**（占全部 540 对的 1.9%）。带内精度按 proposal §4 的口径是「该带里 `near-duplicate` 档的占比」，
不是"该带里该被降权的比例"——两者的差就是 F3 要回答的问题。

## 4. 锚视图落带（floor = 实测异图上限，threshold = 其与同图下限的中点）

实测 `sameImageFloor = 0.9999999999999998`、`crossImageCeiling = 0.9933404610426125` ⇒ **可分**（F2 未命中）。

| 带 | 区间 | 对数 | 占比 | 近重复对 | 带内精度 |
|---|---|---|---|---|---|
| `SAME_IMAGE` | `[0.9966702305213062, 1]` | 202 | 37.4% | 58 | 0.2871 |
| `NEAR_HIGH` | `[0.9956, 0.9967)` | **2** | 0.4% | 2 | 1.0000 |
| `NEAR_MID` | `[0.9945, 0.9956)` | **3** | 0.6% | 3 | 1.0000 |
| `NEAR_LOW` | `[0.9933, 0.9945)` | **3** | 0.6% | 2 | 0.6667 |
| `CROSS_IMAGE` | `[0, 0.9933)` | 330 | 61.1% | 55 | 0.1667 |

`floor=0.9933404610426125  threshold=0.9966702305213062  Δ=0.0033  b1(低/中)=0.9945  b2(中/高)=0.9956`

锚视图三档合计 **8 对**，比生产视图的 10 对**更少**（F5 的"每带都更宽"不成立），且 F1 未命中 ⇒ F5 未命中。
这说明把 floor 抬到实测异图上限**不是**解决近重复可达性的办法：真正的问题不在 floor 的绝对值，而在
「这批同源变体的余弦本身就落在 0.75–0.99 这一段」（见 §7 的 `near-duplicate` 分位）。

## 5. stratum × band 交叉表（生产视图）

| stratum | `SAME_IMAGE` | `NEAR_HIGH` | `NEAR_MID` | `NEAR_LOW` | `CROSS_IMAGE` | 合计 |
|---|---|---|---|---|---|---|
| `same-image` | 144 | 0 | 0 | 0 | 0 | 144 |
| `near-duplicate` | 63 | 2 | 1 | 4 | **50** | 120 |
| `same-class` | 0 | 1 | 0 | 1 | 98 | 100 |
| `cross-class` | 0 | 0 | 0 | 1 | 175 | 176 |

读法：120 对同源变体里 **63 对（52.5%）进了同图档**（`>= 0.9944`）、10 对进了三档近似带、**50 对掉到 floor 以下**。
这 50 对不是噪声：它们是 `crop-12` / `crop-04` / `jpeg-q80` 这类重变换（见 §9 的 min 值）。

## 6. 各带分位（生产视图 / 锚视图同表）

生产视图：

| 带 | n | min | p05 | p25 | median | p75 | p95 | max |
|---|---|---|---|---|---|---|---|---|
| `SAME_IMAGE` | 207 | 0.9945 | 0.9976 | 0.9999 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| `NEAR_HIGH` | 3 | 0.9933 | 0.9933 | 0.9934 | 0.9934 | 0.9936 | 0.9938 | 0.9938 |
| `NEAR_MID` | 1 | 0.9921 | 0.9921 | 0.9921 | 0.9921 | 0.9921 | 0.9921 | 0.9921 |
| `NEAR_LOW` | 6 | 0.9887 | 0.9888 | 0.9889 | 0.9890 | 0.9891 | 0.9900 | 0.9903 |
| `CROSS_IMAGE` | 323 | 0.0747 | 0.1238 | 0.1794 | 0.2497 | 0.4648 | 0.9781 | 0.9887 |

锚视图：

| 带 | n | min | p05 | p25 | median | p75 | p95 | max |
|---|---|---|---|---|---|---|---|---|
| `SAME_IMAGE` | 202 | 0.9967 | 0.9978 | 0.9999 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| `NEAR_HIGH` | 2 | 0.9958 | 0.9958 | 0.9959 | 0.9959 | 0.9960 | 0.9961 | 0.9961 |
| `NEAR_MID` | 3 | 0.9945 | 0.9945 | 0.9945 | 0.9945 | 0.9950 | 0.9953 | 0.9954 |
| `NEAR_LOW` | 3 | 0.9933 | 0.9933 | 0.9934 | 0.9934 | 0.9936 | 0.9938 | 0.9938 |
| `CROSS_IMAGE` | 330 | 0.0747 | 0.1239 | 0.1798 | 0.2528 | 0.4983 | 0.9830 | 0.9921 |

## 7. 档位总览与 `candidateFloor`

| stratum | n | min | p05 | median | max |
|---|---|---|---|---|---|
| `same-image` | 144 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| `near-duplicate` | 120 | **0.7483** | **0.8563** | 0.9956 | 1.0000 |
| `same-class` | 100 | 0.0849 | 0.1093 | 0.2607 | 0.9933 |
| `cross-class` | 176 | 0.0747 | 0.1235 | 0.2074 | 0.9887 |

- **`candidateFloor = null`**（锚里是 JSON `null`，脚本也复算为 `null`）。原因按 `proposal.md` §9.5 写死：**F1 未命中就不给候选下限**。
  `near-duplicate.p05 = 0.8562763134010537` 只作**分布参考**——把它当 floor 会让 41.7% 的真近重复对升格成"不是近重复"，而这 41.7%
  恰恰是最重的变换（`crop-12` min 0.7483），属于必须继续降权的那一档。首轮把 `0.4199` 写成 `candidateFloor` 就是这种误读的样本。
- `near-duplicate` 分布是**双峰**：median 0.9956（轻变换几乎等于同图）与 min 0.7483（重裁切）之间空了一大段 ⇒
  "用一个 p05 当 floor"这个方法本身在这批变体上不成立。
- `same-class.max = 0.9933` 与 `cross-class.max = 0.9887` 都低于 `same-image.min = 0.9999999999999998` ⇒ **异图上限 < 同图下限**
  （F2 的单一阈值前提在本语料上成立）。

## 8. 可达性（belowFloor / aboveThreshold）

| 视图 | belowFloor | 占比 | aboveThreshold | 占比 | nearDup < floor | 占比 |
|---|---|---|---|---|---|---|
| `productionView` | 323 | 59.8% | 207 | 38.3% | **50 / 120** | **41.7%** |
| `anchorView` | 330 | 61.1% | 202 | 37.4% | **55 / 120** | **45.8%** |

- 生产 floor 把 **41.7%** 的同源变体挡在带外（首轮退化数据下这个数是 55.8%，且其中 30 对来自坍缩 base——那是假证据，已作废）。
- 锚视图（floor 抬到 0.9933）把这个比例推到 **45.8%**：**抬高 floor 只会让更多真近重复掉出去**，方向与"让带可达"相反。

## 9. 变体强度单调性（中位数应随变换加重而非递增）

| kind | n | min | p05 | median | p75 | max |
|---|---|---|---|---|---|---|
| `jpeg-q80` | 24 | 0.9652 | 0.9673 | **0.9981** | 0.9992 | 0.9997 |
| `scale-085` | 24 | 0.9846 | 0.9869 | **0.9996** | 0.9999 | 1.0000 |
| `scale-070` | 24 | 0.9703 | 0.9757 | 0.9994 | 0.9998 | 1.0000 |
| `crop-04` | 24 | 0.9150 | 0.9414 | 0.9814 | 0.9924 | 0.9978 |
| `crop-12` | 24 | 0.7483 | 0.7722 | 0.9366 | 0.9587 | 0.9873 |

**单调（中位数随强度非递增）：否，反序 1 处** —— `jpeg-q80(0.9981) → scale-085(0.9996)`。

反序的读法：`jpeg-q80` 是**有损重编码**，`scale-085` 是**无损缩小**；对这批以细线条/小字为主的位图，0.85 倍缩小反而比 q0.80 二次压缩
更"保真"，所以中位数略高。这是**变换语义**的差异，不是语料错误——记录在案，不改强度序（改了等于事后改口径）。
另两点可复核：`scale-070` 的 min（0.9703）低于 `scale-085`（0.9846），`max` 触到 1.0000（部分 base 的小图缩到 0.85/0.70 后
与自身重编码视觉上无差别）。

## 10. 判定树照判（proposal §4 条件原文，跑前写死）

| 分支 | 条件 | 结果 | 依据（锚字段） |
|---|---|---|---|
| **F0** 带可达且样本足 | 三带各 ≥30 对且带内近重复占比 ≥0.5 | **未命中** | `NEAR_HIGH=3(0.6667)`、`NEAR_MID=1(1.0000)`、`NEAR_LOW=6(0.6667)`（3 带都 <30） |
| **F1** 带基本不可达 | `nearDuplicate` 中 <0.9887 的比例 >0.5 | **未命中** | `nearDuplicateBelowFloor=50/120`，占比 **0.4167** < 0.5 |
| **F2** 单阈值前提被推翻 | `crossImageCeiling >= sameImageFloor` | **未命中** | 0.9933404610426125 < 0.9999999999999998，`anchorView.separable=true` |
| **F3** 带内装的是异图 | 某带 ≥30 对但带内近重复占比 <0.5 | **未命中** | 无样本 ≥30 的带（同 F0/F4 依据） |
| **F4** 样本仍不足 | 任一近似带 <30 对 | **命中** | 样本不足带：`NEAR_HIGH=3`、`NEAR_MID=1`、`NEAR_LOW=6` |
| **F5** 锚视图优于生产视图 | 锚视图每带样本数 > 生产视图同带，且 F1 未触发 | **未命中** | 生产 `3/1/6` vs 锚 `2/3/3`：`NEAR_HIGH` 3→2、`NEAR_LOW` 6→3，**不满足"每带都更多"** |

**结论落点**：F4 命中 ⇒ 三档权重**维持 `0.75 / 0.6 / 0.5`**；F1 未命中 ⇒ 没有"生产阈值下真近重复基本不触发"的缺陷证据，
因此**不建议**下调 `NEAR_DUPLICATE_FLOOR`；F2 未命中 ⇒ 单一阈值前提仍成立。若后续要动 floor/权重，**另立卡**并自带排序口径
（proposal §5：余弦分布不含排名效用信息，不能拿分类证据冒充排序证据）。

## 11. 语料数据质量：白底合成修复、像素属性、退化剔除

**首轮缺陷（复核退回的 D1）**：24 个 base 里 **6 个**（`doe-renewable-energy-framework-p4-b5/b6/b7/b8`、`p5-b9`、`p5-b10`）的五类变体余弦
逐位全等（如 `0.4070087706878587 × 5`）。根因是变体栅格化用 `TYPE_INT_RGB` 目标（**初始像素为黑**）却没铺底色，而源 PNG 的透明区
被合成成黑；这 6 个 base 恰好是**整幅图没有任何全不透明像素**（内容全在部分 alpha 里）的页面 ⇒ 合成后近纯黑 ⇒ 五种变换坍缩成同一张图。

**修复**：`NearDuplicateCorpus.toRgb/scale/crop` 一律 **先 `fillRect` 白底再合成**（与浏览器/PDF 渲染器默认背景一致）。
修后逐 base 像素属性（`corpus.baseDetail`，脚本 §10 打印）：

| base | colorModel | hasAlpha | uniqueColors | mean | stdDev |
|---|---|---|---|---|---|
| `nist-sp800-101r1-p1-b1` | ComponentColorModel#3 | false | 256 | 213.898 | 85.5226 |
| `nist-sp800-101r1-p16-b2` | ComponentColorModel#3 | false | 33229 | 218.1016 | 31.1244 |
| `nist-sp800-101r1-p18-b3` | ComponentColorModel#4 | true | 15713 | 154.0264 | 44.1755 |
| `nist-sp800-101r1-p19-b4` | ComponentColorModel#3 | false | 15905 | 244.928 | 34.9816 |
| `nist-sp800-101r1-p22-b5` | ComponentColorModel#4 | true | 29008 | 236.5478 | 46.5794 |
| `nist-sp800-101r1-p23-b6` | ComponentColorModel#3 | false | 7462 | 233.4559 | 27.5782 |
| `nist-sp800-101r1-p27-b7` | ComponentColorModel#3 | false | 19652 | 205.946 | 61.1035 |
| `nist-sp800-101r1-p46-b8` | ComponentColorModel#3 | false | 3240 | 245.7205 | 27.448 |
| `nist-sp800-183-p1-b1` | ComponentColorModel#3 | false | 57 | 216.9528 | 80.9455 |
| `nist-sp800-183-p14-b2` | ComponentColorModel#3 | false | 10734 | 241.6928 | 48.816 |
| `nist-sp800-183-p16-b3` | ComponentColorModel#3 | false | 13868 | 237.2473 | 51.7467 |
| `nist-sp800-183-p18-b4` | ComponentColorModel#3 | false | 16768 | 234.2378 | 54.1083 |
| `doe-renewable-energy-framework-p1-b1` | ComponentColorModel#3 | false | 9606 | 96.4022 | 50.0831 |
| `doe-renewable-energy-framework-p1-b2` | ComponentColorModel#4 | true | 70 | 251.4466 | 10.7915 |
| `doe-renewable-energy-framework-p3-b3` | ComponentColorModel#3 | false | 268051 | 121.1012 | 42.1931 |
| `doe-renewable-energy-framework-p4-b4` | ComponentColorModel#4 | true | 14999 | 205.6147 | 76.2664 |
| `doe-renewable-energy-framework-p4-b5` | ComponentColorModel#4 | true | **83** | 248.7725 | 18.1251 |
| `doe-renewable-energy-framework-p4-b6` | ComponentColorModel#4 | true | **83** | 249.5756 | 17.0368 |
| `doe-renewable-energy-framework-p4-b7` | ComponentColorModel#4 | true | **83** | 246.8481 | 20.2929 |
| `doe-renewable-energy-framework-p4-b8` | ComponentColorModel#4 | true | **83** | 247.2855 | 19.8457 |
| `doe-renewable-energy-framework-p5-b9` | ComponentColorModel#4 | true | **88** | 250.1793 | 14.182 |
| `doe-renewable-energy-framework-p5-b10` | ComponentColorModel#4 | true | **89** | 250.1478 | 14.2621 |
| `doe-renewable-energy-framework-p6-b11` | ComponentColorModel#4 | true | 4521 | 233.2306 | 41.7101 |
| `doe-renewable-energy-framework-p6-b12` | ComponentColorModel#4 | true | 10614 | 226.3236 | 45.9682 |

**退化剔除：0 个**（`corpus.excludedBases = []`、`excludedBaseCount = 0`）。这是修复后的真实结果，不是漏检：
按 §9.2 的判据（白底合成后唯一色数 ≤ 1）逐个量过，24 个 base 的唯一色数落在 **57 – 268051**，最低的 `nist-sp800-183-p1-b1 = 57` 也远大于 1。
原 6 个可疑 base 修复后唯一色数 **83 / 83 / 83 / 83 / 88 / 89**、`mean 246.85–250.18`、`stdDev 14.18–20.29`，与其它 base 同量级
（它们是**高亮低对比的页面**，不是空白页）。因此语料**没有收缩**，第二轮仍是 24 base / 120 变体 / 144 图 / **288 次外呼**
（proposal §9 里"预计 18/90/108/216"是"若这 6 个被剔除"的情景预估，实际未剔除）。
`documents[].taken` 与 `corpus.bases` 已由 §9.2 的登记口径对齐（逐文档保留数之和 = 24，IT 内有断言）。

**防退化守卫（默认跑、零外呼）** —— 本轮新增，失败注入-还原各一次：

| 守卫 | 位置 | 注入方式 | 注入结果 | 还原后 |
|---|---|---|---|---|
| ① 每 base / 每变体白底合成后唯一色数 > 1 | `NearDuplicateCorpusTest#pixelContentIsNotDegenerate` | `scale()` 里去掉 `drawImage`（变体只剩白底） | 红：`变体白底合成后像素全同（唯一色数 = 1）: nist-sp800-101r1-p1-b1@scale-085` | 绿 |
| ② 同 base 五变体两两 SHA-256 不同 | `NearDuplicateCorpusTest#variantsOfEachBaseArePairwiseDistinct` | `scale-070` 改用 0.85 系数 | 红：`同 base 五变体的 SHA-256 必须两两不同…expected: <5> but was: <4>` | 绿 |
| ③ 变体与 base 字节不同 | `NearDuplicateCorpusTest#everyVariantDiffersFromItsBase` | 变体字节改成 base 字节 | 红：`变体与 base 字节相同…: nist-sp800-101r1-p1-b1@jpeg-q80` | 绿 |
| ④ 任一 base 五类余弦不得全等 | `ImageNearDuplicateCalibrationAnchorTest#variantsOfEachBaseAreNotCollapsed` | 锚里把 `doe-renewable-energy-framework-p4-b5` 的 5 个余弦改成同值 | 红：`五类变体的余弦逐位全等 = 变体坍缩…: [doe-renewable-energy-framework-p4-b5 → 0.9823611815125848 × 5]` | 绿 |

守卫 ④ 在**修复前的旧锚**上直接就是红，且精确点名 6 个坍缩 base（`p4-b5/b6/b7/b8`、`p5-b9/b10`）——这正是"锚级回归检查"该有的行为。

## 12. 为什么本卡不再扩语料

三带样本不足（3/1/6）是**语料结构**决定的，不是数量不够：这 120 对变体的余弦分布是"轻变换贴着 1、重裁切掉到 0.75–0.95"的双峰，
再多加同类变体只会把同一双峰加厚，不会凭空长出 30 对落在 `[0.9887, 0.9944)` 的样本。真要填带，需要**另一类同源变体**
（如水印、网点、轻微模糊、同页重复扫描），那是另一张卡的语料工作（含版权与成本口径），本卡不为它先付。

## 13. 未覆盖类（如实声明）

- **同页重复扫描**（同一张纸扫两次）：没有扫描仪无法合成，本卡**不覆盖**。它的余弦通常紧贴同图下限，是最可能落进 `NEAR_HIGH` 的真实来源，
  因此本卡的"三带无样本"结论**不能推广**到该类。
- **带 alpha 的真实业务图**：本卡语料里 10 个 base 带 alpha（`hasAlpha=true`），全部经白底合成处理；生产若用别的底色（如深色主题），
  合成结果会不同 ⇒ 本卡的近重复口径只对"白底"这一种渲染成立。
- **文字型截图/文档封面**：语料来自三份技术出版物，以图表与版式为主；纯文字截图未覆盖。

## 14. 复算命令（本文所有数字的唯一来源）

```bash
node scripts/rag-quality/near-duplicate-band-stats.mjs \
  --anchor docs/rag-quality/image-threshold-calibration-v2.json --expect 207,3,1,6,323
# => 11 节输出 + 「锚内已存块与本次复算一致（productionView / anchorView / strata / candidateFloor / judgements）」
#    + 「语料口径自洽（baseDetail / excludedBases / imageEmbeddingCalls 三项均与计数一致）」
#    + 「结果：复现 v2 生产视图落带 207/3/1/6/323 ✓（EXIT=0）」
```

回归：`node scripts/rag-quality/near-duplicate-band-stats.mjs`（无参数）必须仍打印 `18/0/0/1/71` 并以 EXIT=0 结束——
v1 自检路径一行未改。用法错误（给 `--anchor` 不给 `--expect`）EXIT=2；数字不符 EXIT=1，**不静默通过**。

数据质量的零外呼复核：`mvn -B -ntp test -Dtest=NearDuplicateCorpusTest`（计数/确定性/像素属性/退化剔除/五变体两两不同）、
`mvn -B -ntp test -Dtest=ImageNearDuplicateCalibrationAnchorTest`（已提交锚的结构与防退化闸）。

## 15. 常量零改动自证

- `git diff master -- src/main` 空 ⇒ `ImageSimilarityGate` 的 `DEFAULT_THRESHOLD=0.9944`、`NEAR_DUPLICATE_FLOOR=0.9887`、
  三档权重 `0.75/0.6/0.5`、分带公式与边界归属**全部原样**（脚本 §1/§2 的 `floor / threshold / b1 / b2` 直接引用生产常量复算）。
- `git diff master -- docs/rag-quality/image-threshold-calibration-v1.json docs/rag-quality/image-near-duplicate-bands-v1.md
  src/test/resources/rag-quality/sources.json src/test/resources/rag-quality/real-pdf
  src/test/java/com/slz/crm/quality/RealPdfAssets.java "src/test/java/com/slz/crm/quality/ImageThresholdCalibration*" pom.xml frontend` 空。
- 本卡的判定只产出**证据**：`candidateFloor = null`、F4 命中 ⇒ 权重维持现值；任何 floor / 权重调整都必须另立卡。
