# tasks — clear-onboard-2-left-fragment

> 红线：判卷口径 v2（承载集）与主锚口径不动；fixtures 变更仅限 customer-sop.md 中 `【GOLD:customer-onboard-2】` 一个标记的位置移动（b2 §0 在案披露为依据），黄金 id 集、案例名、人名、数字零改动；真实外呼仅限任务组 4 单轮（RAG_BENCHMARK_REAL=1，proposal D3 owner 已拍板授权）；其余任务组 DASHSCOPE_API_KEY 置空；SUITE_VERSION 升 5.0 与 fixtures 变更同笔；surefire 只增不减；bash 一律用 `D:\git\Git\bin\bash.exe`。

## §0 铁律与留痕

- 只许动：RagBenchmarkSuite.java、fixtures/customer-sop.md（仅 onb-2 标记位置）、RagBenchmarkDataPreparerTest.java、RagBenchmarkCarrierTest.java（双钉）、RagQualityRegressionTest.java（再生成）、RagBenchmarkV6AnchorValidationTest.java（新）、baseline-v6.json（新）、本卡目录文件。超范围 = 偏差，如实留痕披露。
- baseline-v5.json 及历史锚（v3/v4）与全部既有锚点文件禁改；移标记后既有双钉（da7a3ef9…/79a804d5…）必红必刷新。
- 勾选哈希留痕规则：勾选行下一行 6 空格缩进「补勾依据」+ 可独立复跑命令与哈希。
- 停步点：四组全完 + 门禁留证 = 停步回报，不合并不 push（A 档：执行自跑全量 + 指导侧逐格核验 + owner 亲派复核 agent）。

## 任务组 1 · 升版与移标记

- [ ] 1.1 SUITE_VERSION 4.0→5.0（RagBenchmarkSuite 常量 + javadoc 升版说明，63 例口径不变）
- [ ] 1.2 移标记：`【GOLD:customer-onboard-2】` 移至与客户分级标准语义主体同块（proposal D1 机械口径三项验收：tornMarkers 零检出 / 锚块字面承载分级标准文本 / goldenToChunkId 归点正确）；标记移动外 customer-sop.md 零字节改动
- [ ] 1.3 向量输入 SHA256 双钉刷新（探针手法对齐 b2 任务 1.4：VectorInputDigestProbe 实测新 indexedText/embedText 后回填 CarrierTest 两断言）
- [ ] 1.4 fixtureRegression 段 `-Drag.baseline.regen=true` 确定性再生成（suiteVersion=5.0，fake embedder 零外呼），漂移逐例归因留痕

## 任务组 2 · 断言与校验增量

- [ ] 2.1 PreparerTest 新增「customer-onboard-2 零撕裂事件」断言（对齐 b2 T-15 断言反转型先例）+ onb-2 锚块字面承载断言；既有「撕裂仅 customer-sop 一家」注释更新为整仓零撕裂语义；既有 T-15 断言零改动
- [ ] 2.2 RagBenchmarkV6AnchorValidationTest 新增（对齐 RagBenchmarkV5AnchorValidationTest 先例：v3/v4/v5/v6 四锚并存 + SUITE_VERSION=5.0 登记逻辑 + 缺席跳过/口径对账用例）+ SUITE_VERSION=5.0 断言

## 任务组 3 · 门禁（零外呼）

- [ ] 3.1 全量 `mvn -B -ntp test` 0 失败（DASHSCOPE_API_KEY 置空；surefire 只增）；四静态顺序跑（禁并发）；三守卫；check-test-baseline 只读不红（如需 --update 先全量真跑 failsafe 刷新口径，一次只增）；merge-gate 8/8

## 任务组 4 · 真跑重锚（RAG_BENCHMARK_REAL=1，D3 已授权）

- [ ] 4.1 63 例全量单轮真跑（DASHSCOPE_API_KEY 真值 + RAG_BENCHMARK_REAL=1），产出 baseline-v6.json（SUITE_VERSION=5.0 登记）；成本超 b2 参照（293s/61786 tokens）20% 以上先停步回报
- [ ] 4.2 v5→v6 指标对比报告：T-11 citP 0.667→预期 1.0（若仍 2/3，取证定位真实失分源如实回报）；其余 62 例逐位不动（或漂移逐例归因）；真跑 raw、成本、对比表贴停步回报
- [ ] 4.3 门禁复跑 + 停步回报（不合并不 push，待 owner 派 A 档复核 agent）

## §0 执行留痕

（执行方填写：执行人、日期、分支、各笔提交哈希、偏差披露）
