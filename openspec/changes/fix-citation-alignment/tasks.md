# Tasks — fix-citation-alignment

> 执行契约见 `openspec/git-workflow.md`。当前基线：master=`0501722`，surefire **619** 全绿。
> 硬约束：任务组 1–3、5 全程 ¥0（不发任何外呼）；**任务组 4 是授权节点**——未授权不得设 `RAG_BENCHMARK_REAL=1`。禁改检索管线、禁改 `RagQualityEvaluator` 公式、禁改已合入 Flyway、禁改 `init_data.sql`、禁覆盖 `baseline-v1.json` / `baseline-v2.json`。

## 0. 执行记录（执行 agent 填写）

- 分支：`feature/fix-citation-alignment`（自 master `0501722`）
- 阈值最终值：KEEP_MIN=0.12 / REMAP_MIN=0.22 / TIE_MARGIN=0.08（类内常量，未外置）
- 计分：CJK 二字**子句覆盖率**（clause bigrams ∩ excerpt / |clause|）+ ASCII/数字 token 加成 0.25；相对 proposal 的对称 Jaccard 改为覆盖率，避免长 excerpt 稀释 I-05 形支撑分（单测夹住）
- surefire：任务组 1 局部 `CitationAlignerTest` 6 绿；全量计数见任务组 3
- 任务组 4（2026-09-16 授权合并复测）：三单任务组4合并为一次默认矩阵真跑；产物=`docs/rag-quality/baseline-after-quality-loop.json` + `baseline-after-quality-loop.md`（不是 after-citation / after-excel-header / after-multicondition 三个分文件）。failsafe：Tests run 1 Failures 0 Errors 0，elapsed 197.0s；suiteVersion=2.0；failureRate=0。
  - 全套 citationPrecision 0.7843→**0.8302**（≥ v2）；recall@5 0.9105→0.9475；MRR 0.8210→0.9136；hitRate 0.96→1.0；failureRate=0
  - **I-05 citP 仍为 0**（recall=1/ansC=1 持平）——对齐目标未达，按契约不调阈值、不改黄金；记下残留


## 1. 对齐器纯函数 + 单测（¥0）

- [x] 1.1 新增 `src/main/java/com/slz/crm/server/ai/CitationAligner.java`：`align(String answer, List<SourceReference> sources) → Alignment(text, citations)`；CJK 二字 Jaccard + 共享 ASCII/数字 token；KEEP / REMAP / DROP 规则按 proposal；无 Spring、无 ModelProvider；Javadoc 标注「fix-citation-alignment 任务 1.1」
- [x] 1.2 新增 `src/test/java/com/slz/crm/unit/ai/CitationAlignerTest.java`，覆盖 proposal §3 的 6 类：I-05 形 remap、已对齐不动、平局保原号、无支撑删号、空 sources/无编号/越界、不补漏引
- [x] 1.3 阈值以单测夹住；若实现时微调 KEEP_MIN/REMAP_MIN/TIE_MARGIN，把最终值写入本节执行记录，不得靠动态配置外置

## 2. 接入生产落库与评测抽取（¥0）

- [x] 2.1 `AiChatStreamLifecycle` `doOnComplete`（现 L151–161）：`extractCitations` 之前调用 `CitationAligner.align`；`persistAssistantMessage` / `toReferencesJson` / `toAuditJson` 使用对齐后 `content` 与 citations。**禁止改构造器签名**
- [x] 2.2 `RagRealRetrievalBenchmarkIT.evaluateCase`（现 L204–207）：生成后先 align 再 `extractCitations`；判卷用对齐后文本。评测与生产必须调用同一个 `CitationAligner`，禁止复制一套计分
- [x] 2.3 既有 `AiChatStreamLifecycleHeartbeatTest`、`AiChatServiceImplTest`、`SseContractTest`、`RagQualityEvaluatorTest` 不改全绿（构造器、SSE 字段集、citationPrecision 公式均不动）

## 3. ¥0 回归与 CI 基线

- [x] 3.1 `mvn -B -ntp test` 全绿；**读本次 surefire 合计**，同步 `.github/workflows/ci.yml` 三处（口径A 注释、`check_baseline target/surefire-reports`、错误提示行）。禁止推算
- [x] 3.2 （可选 skip：本轮未开 Docker Desktop，未跑 failsafe 切片；不算绿）`$env:DASHSCOPE_API_KEY=''; mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=PermissionCoverageAuditIT,SchemaDriftAuditIT,WriteChainRegressionIT"`。Tests run: 0 是 daemon 未运行，不得报绿

## 4. 真基准复测（授权节点，未授权禁止执行）

- [x] 4.1 授权已获（质量闭环三单合并复测）；成本与 v2 同量级
- [x] 4.2 已跑纯默认矩阵（合并产物 `baseline-after-quality-loop.json`，非单独 after-citation；未注入 rag.*；未覆盖 v1/v2）
  ```
  $env:RAG_BENCHMARK_REAL='1'
  mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.out=docs/rag-quality/baseline-after-citation.json"
  ```
  key 从仓库根 `.env` 解析。禁止覆盖 v1/v2 JSON
- [x] 4.3 已对照 v2：I-05 citP **仍 0**（记下残留、不调阈值）；全套 citP 0.8302 ≥ 0.7843；recall/MRR/hitRate 相对 v2 上升；suiteVersion=2.0，failureRate=0。详见 `baseline-after-quality-loop.md`。原验收细则：
  - I-05 citP 应升（目标 1.0；未到则记下对齐前后编号与答案原文，**停下不调阈值**）
  - 全套 citationPrecision ≥ v2 的 0.7843
  - recall@5 / MRR / hitRate 与 v2 同量级（本单不改检索）
  - `suiteVersion` 仍为 2.0，failureRate=0
- [x] 4.4 合并产物 JSON+MD 入库；差异写入 §0（注明非 after-citation 分文件）

## 5. 收尾

- [x] 5.1 更新 `HANDOFF.md`（引用对齐已落地 + after-citation 锚点状态；任务组 4 未授权则标注待补跑）
- [x] 5.2 随手带：`openspec/changes/expand-rag-benchmark/tasks.md` 4.1–4.3 补勾（v2 锚点已在 `0501722` 合入，只补 checkbox）
- [x] 5.3 git：分支 `feature/fix-citation-alignment`（本机无 `git switch`，用 `checkout -b`）；提交按任务组；提案三件套随首个提交入库；亲验全绿 + `git status` 干净（已知未跟踪 `_rag优化交接.md` / `_vlm_transcribe.py` / `_技术深化交接.md` 勿提交勿删除）后 `--no-ff` 合入 master；**不 push**
