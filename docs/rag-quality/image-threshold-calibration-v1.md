# 异图硬阈值定标 v1（IMAGE-THRESHOLD-CALIBRATION-1.0）

> 落盘物：`docs/rag-quality/image-threshold-calibration-v1.json`（本文件全部数字取自该 JSON，不做二次加工）。
> 生成方式：`ImageThresholdCalibrationIT` 真跑（`RAG_VISION_EMBED_REAL=1` + `DASHSCOPE_API_KEY`）。
> 语料：9 张程序化确定性图（对照）+ 3 份真实公有领域 PDF 经生产提取器各取前 3 张位图（真实语料锚）。
> 边界：**只新增锚**——不碰 `image-baseline-v1` / `pdf-ingest-image-baseline-v1` / `production-gray-cost-v1` / `baseline-v1~v7`；
> 程序化套件 `ImageCrossModalSuite` 的 0.90 **原样未动**。

## 1. 这条锚回答什么

`production-gray-real-run` 诚实标注了「真实语料异图余弦最高 0.9887，不套 0.90」，但没给替代数。本卡的决策问题是：

> 真实语料上，「同图」与「异图」的余弦分界落在哪里？0.90 到底偏松还是偏紧？

答：**0.90 偏松**。真实语料里两份不同 NIST 出版物的首页版式图余弦 **0.9887 ≥ 0.90**，按 `同图余弦 ≥ 0.90` 的判据它们会被当成"同一张图"；
而定标出的真实语料判别阈值是 **0.9944**。

## 2. 定标口径（跑之前固定，不按结果调参）

三层档位，全部只用**真视觉 embedding**（`multimodal-embedding-v1`）：

| 档位 | 构造 | 语义 |
|---|---|---|
| `same-image` | 同一张图**编码两次** | 同图下限的观测口径（同字节再编码的余弦） |
| `same-class` | 同图型不同实例（程序化：同族不同配色/尺寸；真实：**同一出版物**的不同图） | "看起来像但不是同一张"，最难的负例 |
| `cross-class` | 不同图型 / **不同出版物** | 最容易判异的负例 |

- **同图下限** = `same-image` 档最小值；**异图上限** = `same-class ∪ cross-class` 的最大值（任何**不同**的图都必须被拒答，不能只看跨类）。
- **判别阈值** = (同图下限 + 异图上限) / 2，落在严格区间 `(异图上限, 同图下限]` 内；**不可分时不给阈值**（写"不给硬阈值"），不硬凑。
- 真实语料按 PNG **SHA-256 去重**（同字节不算异图）；每图 2 次外呼，共 36 次视觉 embedding 调用（无 token 回流，按次计量）。

## 3. 实测结果（2026-10-22，本机）

### 3.1 真实语料（锚的取值来源，`real-pdf`，9 图 / 45 对）

| 档位 | 对数 | min | mean | max |
|---|---|---|---|---|
| `same-image`（同图再编码） | 9 | **0.9999999999999998** | 1.0000 | 1.0000 |
| `same-class`（同一出版物内） | 9 | 0.1494 | 0.3095 | 0.8505 |
| `cross-class`（跨出版物） | 27 | 0.0909 | 0.2806 | **0.9887359400788237** |

| 结论项 | 值 |
|---|---|
| 同图下限 | **0.9999999999999998** |
| 异图上限 | **0.9887359400788237**（= `production-gray-cost-v1` 记录的 0.9887，独立复现一致） |
| 可分 | **是**（间隔 +0.0113） |
| **判别阈值** | **0.9943679700394117** |

### 3.2 程序化语料（对照，`programmatic`，9 图 / 45 对）

| 档位 | 对数 | min | mean | max |
|---|---|---|---|---|
| `same-image` | 9 | 0.9999999999999998 | 1.0000 | 1.0000 |
| `same-class`（同图型不同实例） | 9 | 0.9161 | 0.9536 | 0.9779 |
| `cross-class`（不同图型） | 27 | 0.5178 | 0.6076 | 0.7288 |
| 判别阈值（对照值，**不采信为锚**） | — | — | — | 0.9889 |

> **两档的差别本身就是结论**：程序化语料的"同类"是**结构性近同图**（同族不同配色 → 0.92~0.98），真实语料的"同类"多是**同文档内的不同图表**（0.15~0.85）；
> 真实语料真正危险的不是"同类"，而是**跨文档的版式图**（0.9887）——这类图的视觉语域几乎相同（页面级排版 + 徽标 + 大标题）。

## 4. 判据与消费口径

```text
同图命中:  cosine >= 0.9944   （真实语料判别阈值）
异图拒答:  cosine <  0.9944
```

- **消费方**：真实语料（生产灰度）的判别闸门读本锚 `threshold.recommendedThreshold`；`docs/multimodal-gray-rollout.md` §5
  已把它登记为灰度期查询侧口径。
- **不改变**程序化套件：`ImageCrossModalSuite.SAME_IMAGE_COSINE_THRESHOLD = 0.90` 保持原值（其语料是程序化 6 图，
  改它会动 `image-baseline-v1.json` 的可比性），本锚与它**并列而不覆盖**。
- **诚实边界**：0.9944 只区分"同图再编码"与"任何不同的图"。**近重复图**（同页截图的不同裁剪、同一图标的两种尺寸）落在
  `same-class` 的高端（真实语料最高 0.8505，程序化最高 0.9779），是否算"同图"是**业务定义问题**，不是阈值能解决的——
  本锚不宣称能区分它们。

## 5. 自洽门禁（防手改锚点）

`ImageThresholdCalibrationTest#shouldKeepAnchorSelfConsistent`（surefire，零外呼）读本锚并断言：
`crossImageCeiling < sameImageFloor`、`recommendedThreshold ∈ (ceiling, floor]`、`threshold` 与 `corpora.real-pdf` 块一致、
`pairs` 非空且对数 ≥ 同图档对数。**锚只允许由 IT 真跑重写**，手改数会被这条门禁打红。

## 6. 复现命令

```bash
# 真跑（落锚；无 opt-in 或 key 时整类 Assume 跳过，零外呼）
RAG_VISION_EMBED_REAL=1 mvn -B -ntp test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=ImageThresholdCalibrationIT -DfailIfNoTests=false

# 默认全量（零外呼，本类 +1 skipped）
mvn -B -ntp clean verify

# 覆盖落盘路径（不覆盖默认锚时不需要）
-Drag.image.threshold.out=target/threshold.json
```

## 7. 未解决项 / 待决策项

1. **阈值 0.9944 是否用于生产检索闸门**（待决策）：当前生产检索**没有**余弦硬闸门（`recallImage` 只做 KB + category 过滤 + topK），
   本锚目前是「灰度期判别口径」与后续卡的判据参考；要不要真的加一道 `minScore` 式闸门属检索行为变更，需另立卡。
2. **近重复图无法用单一阈值区分**（观察）：见 §4 的诚实边界；若要处理需图级去重（感知哈希）而非阈值调参。
3. **`same-image` 档恒为 1.0 的含义**（说明）：一次编码端点对同字节输入是确定性的，所以同图下限天然贴着 1.0；这也意味着
   **任何**真实语料里的近似异图都只能靠"离 1.0 有多远"来判，间隔只有 +0.011 就是这条链路的真实分辨率。
