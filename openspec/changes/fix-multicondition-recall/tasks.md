# Tasks — fix-multicondition-recall

> 执行契约见 `openspec/git-workflow.md`。当前基线：master=`a184e55`，surefire **628**。
> 硬约束：任务组 1–3、5 全程 ¥0；任务组 4 授权节点。禁改构造器签名、禁打开 multi-query 默认、禁改 54 条用例、禁覆盖任何 baseline JSON、禁跑 after-citation / after-excel-header。

## 0. 执行记录（执行 agent 填写）

- 拆句：第一处「并且|同时|后|且」（较长优先）；两侧汉字≥4 或含 ASCII 词≥2 才追加左右路；首元素=原句。`ConstraintQuerySplitter` 纯静态。`retrieve` 对 split 每路 embed+recallTextRoute；>1 路 `(rrfFusion!=null?rrfFusion:new RrfFusion()).fuseAll`。surefire 实测 **634** 全绿（2026-09-16，=628+ConstraintQuerySplitterTest 6）。任务组4（2026-09-16 授权合并复测）：三单任务组4合并为一次默认矩阵真跑；产物=docs/rag-quality/baseline-after-quality-loop.json + aseline-after-quality-loop.md（不是三个分文件）。failsafe：Tests run 1 Failures 0 Errors 0，elapsed 197.0s；suiteVersion=2.0；failureRate=0。 T-14 recall 仍 0.5；T-15/T-17 recall 未回退（均 1.0）；T-14 ansC 0.667→1.0；T-15 citP 0.333→0（引用波动）。

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

## 4. 真基准复测（授权节点，未授权禁止执行）

- [x] 4.1 授权已获（质量闭环三单合并复测）
- [x] 4.2 已跑纯默认矩阵（合并产物 docs/rag-quality/baseline-after-quality-loop.json，非单独 after-multicondition；未覆盖 v1/v2；未注入 rag.*）
- [x] 4.3 已对照 v2：T-14 recall **仍 0.5**；T-15/T-17 recall 未回退（1.0/1.0）；T-14 ansC 0.667→1.0。详见 aseline-after-quality-loop.md
- [x] 4.4 合并产物 JSON+MD 入库；差异写入 §0（注明三单合并一次跑）


## 5. 收尾

- [x] 5.1 HANDOFF：拆句召回已落地；after-multicondition / after-citation / after-excel-header 未跑则都标待授权
- [x] 5.2 git：`checkout -b feature/fix-multicondition-recall`；提案三件套随首个提交；亲验全绿 + status 干净（三个未跟踪件勿提交勿删除）后 `--no-ff` 合入 master；**不 push**
