# Tasks — expand-rag-benchmark

> 执行契约见 `openspec/git-workflow.md`。当前基线：master surefire 619 全绿。
> 硬约束：任务组 1-3 与 5 全程 ¥0（不发任何外呼）；**任务组 4 是授权节点——跑前必须停下向用户报成本预估，未授权不得设置 RAG_BENCHMARK_REAL=1**。禁改既有 fixtures 与既有 18 条用例、禁改检索管线主代码。

## 1. 评测语料扩容（fixtures 6 → 11）

- [x] 1.1 新增 5 个语料到 `src/test/resources/rag-quality/fixtures/`：customer-sop.md / pricing-policy.md / regional-policy.txt / sla-terms.md / maintenance-schedule.xlsx（多行多列表格用制表符/管道分隔的纯文本表达，参照 regional-sales-q3.xlsx 现有格式），每文件含 `【GOLD:占位id】` 标记，密度参照既有 fixtures
- [x] 1.2 语料自洽性核对：新语料中的人名/编号/数值与既有 6 个 fixtures 无冲突（LEXICAL 精确召回不串扰）
- [x] 1.3 `RagBenchmarkDataPreparer.FIXTURES` 登记新语料（key 与文件名对应，注释标注「expand-rag-benchmark 任务 1.3」）

## 2. 查询集扩容（18 → 54）

- [ ] 2.1 `RagBenchmarkSuite.standard()` 新增 36 条（用户确认分布 TEXT 17 / TABLE 13 / IMAGE 6 / LEXICAL 14 / EDGE 4）：TEXT +12（同义改写/跨 chunk/多条件各 4）、TABLE +10（多列交叉/数值区间/聚合比较 4/3/3）、IMAGE +4（四张图注各 1）、LEXICAL +8（编号/人名/版本号 3/3/2）、EDGE +2（近义闲聊/近邻主题误导各 1）；每条含 expectedChunkIds 占位 id 与要点词，中文注释标注「expand-rag-benchmark 任务 2.1」
- [ ] 2.2 `SUITE_VERSION` 升 "2.0"，Javadoc 注明 v1.0=18 条（历史锚 baseline-v1.json）、v2.0=54 条（新锚 baseline-v2.json），跨版本不可直接比较
- [ ] 2.3 新查询与新语料黄金对齐：占位 id 与 fixtures 标记一一对应（RagBenchmarkDataPreparer 的 rewriteSuite 对齐失败显式报错的既有机制兜底）

## 3. ¥0 验证链（先全绿，无需授权）

- [ ] 3.1 `RagBenchmarkDataPreparerTest` 扩充：11 语料全部解析成功、54 条占位 id 全对齐、新旧混合校验（断言总数=54、分类计数=17/13/6/14/4）
- [ ] 3.2 `ChunkNgramRecallGateIT`（免外呼）：跑新旧全集 ngram 召回闸门绿（本地 Docker 可用时真跑，不可用记录跳过）
- [ ] 3.3 `mvn -B -ntp test` 全绿，surefire 实测计数同步 ci.yml 三处（surefire 数字、check_baseline、错误提示行）

## 4. 真基准新锚点（授权节点，未授权禁止执行）

- [ ] 4.1 **停下**：向用户报告成本预估（54 条 ≈ 旧 18 条单跑的 3 倍调用量，¥ 个位数预估，以实际账单为准）与跑法命令，等待授权
- [ ] 4.2 （授权后）`$env:RAG_BENCHMARK_REAL='1'; $env:DASHSCOPE_API_KEY='<key>'; mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.out=docs/rag-quality/baseline-v2.json"`，产出 v2 锚点并在汇报中给出全套指标（recall@5 / precision@5 / MRR / 引用率 / 拒答正确率 / token / 时延）
- [ ] 4.3 （授权后）`docs/rag-quality/` 落 v2 锚点说明（版本 2.0、54 条口径、与 v1 不可比的声明）

## 5. 回归与收尾

- [ ] 5.1 `HANDOFF.md` 更新（基准集 2.0 扩容记录 + v2 锚点状态）；AGENTS.md 若有基准相关行同步
- [ ] 5.2 git 收尾：分支 `feature/expand-rag-benchmark`，提交按任务组 `type(scope): 中文描述`（提案三件套随首个提交入库），亲验全绿 + `git status` 干净（已知未跟踪件勿提交勿删除）后 `--no-ff` 合入 master，汇报带 commit hash + 54 条分类计数 + surefire 实测计数
- [ ] 5.3 若任务组 4 未获授权：合入时不含 baseline-v2.json，HANDOFF 标注"v2 锚点待授权后补跑"
