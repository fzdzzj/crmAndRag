# 设计 — trace-citation-redundancy

**取证卡，零代码**：复用既有机制，产出为文档与数据，无行为变更。

- **判读框架**：citP 公式 = 引用块 ∈ goldenIds 的占比（i05-forensics 在案口径）。多黄金 case 模型选择性别引全黄金 → citP<1 未必是噪声，必须逐例看非黄金引用块的 excerpt 是否支撑答案，不得凭聚合数字下结论。
- **真跑命令形态**（授权后执行，Git Bash 或 PS 等价）：
  `RAG_BENCHMARK_REAL=1 mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT -Drag.benchmark.only=<20例逗号列表> -Drag.benchmark.out=docs/rag-quality/citation-forensics-v4-report.json -Drag.benchmark.trace.out=docs/rag-quality/citation-forensics-v4.json`
  白名单 `-Dit.test` 防 `ModelProviderImplDashScopeIT` 同轮双烧；`out` 严禁省略防覆盖锚点。
- **产物**：trace JSON（旁路）+ report JSON（非锚）+ md 结论文档，三件独立命名；SHA256 跑前跑后核对既有锚点不变。
- **风险**：判读主观性 → 要求 excerpt 原文粘贴 + 归因逐例留痕，复核可独立重判；真跑费用失控 → 白名单锁 20 例单轮，跑毕实测清开关。
