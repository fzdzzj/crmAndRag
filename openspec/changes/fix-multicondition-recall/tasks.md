# Tasks — fix-multicondition-recall

> 执行契约见 `openspec/git-workflow.md`。当前基线：master=`a184e55`，surefire **628**。
> 硬约束：任务组 1–3、5 全程 ¥0；任务组 4 授权节点。禁改构造器签名、禁打开 multi-query 默认、禁改 54 条用例、禁覆盖任何 baseline JSON、禁跑 after-citation / after-excel-header。

## 0. 执行记录（执行 agent 填写）

- 拆句：第一处「并且|同时|后|且」（较长优先）；两侧汉字≥4 或含 ASCII 词≥2 才追加左右路；首元素=原句。`ConstraintQuerySplitter` 纯静态。`retrieve` 对 split 每路 embed+recallTextRoute；>1 路 `(rrfFusion!=null?rrfFusion:new RrfFusion()).fuseAll`。surefire 实测 **634** 全绿（2026-09-16，=628+ConstraintQuerySplitterTest 6）。任务组4 未授权未跑。

## 1. 拆句器（¥0）

- [x] 1.1 新增 `src/main/java/com/slz/crm/knowledge/retrieval/ConstraintQuerySplitter.java`：`split` 首元素=原查询；第一处 `后|且|并且|同时`；两侧够格才追加左右路；无 Spring / 无 ModelProvider。Javadoc 标「fix-multicondition-recall 任务 1.1」
- [x] 1.2 `ConstraintQuerySplitterTest`：T-14 形 3 路；过短不切；无分隔不切；`并且`/`同时`；null/空白

## 2. 接入检索（¥0）

- [x] 2.1 `KnowledgeRetrievalServiceImpl.retrieve`：改写后对 split 每路 embed+recallTextRoute；>1 路用 `new RrfFusion().fuseAll`（不依赖 this.rrfFusion 非空）。**禁止改任何构造器签名**
- [x] 2.2 切不出时 embed 仍 1 次。不改 `rag.query.multi-query.enabled` 默认 false
- [x] 2.3 既有 `KnowledgeRetrievalServiceImplTest`、`QueryTransformationPipelineTest`、融合/稀疏管线测不改构造器全绿

## 3. ¥0 回归与 CI

- [x] 3.1 `mvn -B -ntp test` 全绿；读本次合计改 ci.yml 三处。禁止推算
- [x] 3.2 Docker 可选 skip

## 4. 真基准复测（授权节点）

- [ ] 4.1 **停下**报成本（v2 同量级 + 拆句查询多 2 次 embed，仍 ¥ 个位数）
- [ ] 4.2 （授权后，纯默认矩阵）
  ```
  $env:RAG_BENCHMARK_REAL='1'
  mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.out=docs/rag-quality/baseline-after-multicondition.json"
  ```
  禁止覆盖 v1/v2/after-citation/after-excel-header；禁止注入 rag.*
- [ ] 4.3 对照 v2：T-14 recall 目标 1.0；T-15/T-16/T-17 不回退；suiteVersion=2.0。失败则停，不改黄金、不打开 LLM 多查询
- [ ] 4.4 JSON 入库；差异写入 §0

## 5. 收尾

- [ ] 5.1 HANDOFF：拆句召回已落地；after-multicondition / after-citation / after-excel-header 未跑则都标待授权
- [ ] 5.2 git：`checkout -b feature/fix-multicondition-recall`；提案三件套随首个提交；亲验全绿 + status 干净（三个未跟踪件勿提交勿删除）后 `--no-ff` 合入 master；**不 push**
