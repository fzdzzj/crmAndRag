# proposal — clear-onboard-2-left-fragment（onb-2 左残片清剿：T-11 撕裂归零 + 升版 5.0 + 全量重锚 v6）

> 复核档位（按 `docs/review-intensity-policy.md` 自 declare）：**A 档** —— 含真跑（RAG_BENCHMARK_REAL=1，D3）+ 套件黄金口径变更（SUITE_VERSION 升版 + fixtures 标记移动 + 基线锚点文件）。

## Why

b2-suite-recalibration §0 留痕在案（2026-10-12）：customer-onboard-2 标记跨切块边界仍产生一条 LEFT 残片（goldId=customer-onb, customer-sop-1），属 T-11 黄金、超出该卡「8+1 例」铁律，未触碰，原话「如需整仓零撕裂需另立卡处置」；该卡 D1 拍板亦预留「出现第二例撕裂再立项」。本卡即该预留立项。

现状实测（调研 2026-10-12，三面）：

1. **撕裂现场**：customer-sop.md L7 段首标记 `【GOLD:customer-onboard-2】` 被 320/40 滑窗在字符区间 [302,320) 切断——左残段 `【GOLD:customer-onb`（18 字符无闭合）落 customer-sop-1 尾部，被 FRAGMENT regex 正确剥离（不污染索引），但 tornMarkers 检出流常驻一条撕裂事件，检出器无法回归纯防御。
2. **断言面**：PreparerTest 撕裂用例只锁 T-15（customer-onboard-3）零检出，不锁 onb-2 残片——处置免改既有断言，只增新断言。
3. **真跑基线**：baseline-v5.json T-11 recall@5=1.0 / **citP=0.667（2/3）**——期望三重黄金（customer-onboard-1 / customer-onboard-2 / payment-plan-1）有一条引用未对齐，残片是唯一在案嫌疑；recall 满分说明检索正常，失分集中在引用对齐层。

## What Changes

1. **SUITE_VERSION 4.0→5.0**（RagBenchmarkSuite 常量 + javadoc 升版说明；63 例口径不变）。
2. **移标记（fixture 层，T-15 同手法）**：`【GOLD:customer-onboard-2】` 从 customer-sop.md L7 段首移至与客户分级标准语义主体同块位置；验收为机械口径三项（见 D1），标记与被标内容完整落入同块。
3. **零撕裂断言**：PreparerTest 新增「customer-onboard-2 零撕裂事件」断言（对齐 b2 T-15 断言反转型先例）；既有「撕裂仅 customer-sop 一家」注释更新为整仓零撕裂语义。
4. **向量输入 SHA256 双钉刷新**（indexedText/embedText 因标记移位而变；探针手法对齐 b2 任务 1.4）。
5. **fixtureRegression 段确定性再生成**：RagQualityRegressionTest 经 `-Drag.baseline.regen=true` 一次性刷新（suiteVersion=5.0，fake embedder 零外呼）。
6. **V6 锚点**：真跑 63 例全量单轮产出 baseline-v6.json（SUITE_VERSION=5.0 登记）；v3/v4/v5 锚点保留并存；锚点校验增量支持 v6（对齐 RagBenchmarkV5AnchorValidationTest 先例）。

## 受控解冻与红线

- 「existing fixtures and their gold IDs must not be modified」系 expand 时期防误改红线；本卡按 b2 先例走升版机制下受控校准，标记移动以 b2 §0 在案披露为依据，非自由改写。
- **真实外呼仅限任务组 4 全量重锚单轮**（RAG_BENCHMARK_REAL=1，owner 立项拍板即 D3 授权）；其余任务组 DASHSCOPE_API_KEY 置空。
- 判卷口径 v2（承载集）与主锚口径不动；本卡只动一个标记位置 + SUITE_VERSION。
- baseline-v5.json 与历史锚（v3/v4）禁改，在役锚推进到 v6。
- surefire 只增不减；基线文件按只读/--update 纪律。
- bash 一律用 `D:\git\Git\bin\bash.exe`；提交禁 pathspec 姿态拆 rename（本卡无 rename 面，防御性保留）。

## Impact

- 写集预估：RagBenchmarkSuite + customer-sop.md + RagBenchmarkDataPreparerTest + RagBenchmarkCarrierTest（双钉）+ RagQualityRegressionTest（再生成）+ RagBenchmarkV6AnchorValidationTest（新）+ baseline-v6.json（新）+ 本卡三件套，约 8-11 files。
- 门禁预期：surefire 只增（零撕裂断言新增 + V6 校验新增）；基线如需 --update 按全量口径刷新纪律一次只增。
- 指标预期：T-11 citP 0.667→**1.0**（真跑实证；若仍 2/3 则取证定位真实失分源如实回报，不硬凑）；其余 62 例逐位不动（或漂移逐例归因）——v5→v6 对比报告。

## 拍板点

- **D1 移标记位置口径（机械口径优先于具体坐标）**：移至与分级标准语义主体同块，三机械项验收——tornMarkers 零检出 / 锚块字面承载客户分级标准文本 / goldenToChunkId 归点正确。备选：硬编码目标字符位置——否，标记移动后坐标即变，口径以行为锚不以坐标锚。
- **D2 升版 4.0→5.0**：「改黄金 = 改 case = 升 SUITE_VERSION」语义在案（b2），向量输入变须重锚落 v6 新版登记。备选：不升版只改 fixtures——否，v5 锚可比性断裂且违背升版机制设计。
- **D3 真跑授权**：63 例全量单轮（RAG_BENCHMARK_REAL=1）产出 v6 锚 + v5→v6 对比报告；成本参照 b2 293s/61786 tokens，**超出参照 20% 以上先停步回报再跑**。owner 立项拍板即视为本决策点授权。
- **D4 右残段剥离仍不做**：整仓零撕裂后剥离实现无对象，检出器回归纯防御，对齐 b2 D1 拍板不追溯。备选：检出即剥离——否，无对象实现 + 向量输入二次变化。
