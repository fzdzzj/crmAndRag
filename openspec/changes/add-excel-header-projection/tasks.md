# Tasks — add-excel-header-projection

> 执行契约见 `openspec/git-workflow.md`。当前基线：master=`836ecbf`，surefire **625** 全绿。
> 硬约束：任务组 1–3、5 全程 ¥0；**任务组 4 授权节点**。禁改检索管线主代码、禁改 54 条用例与 fixtures 正文、禁改 Flyway / `init_data.sql`、禁覆盖任何 `baseline-*.json`。引用对齐复测（after-citation）不在本单，禁止顺手跑。

## 0. 执行记录（执行 agent 填写）

- 任务组 4（2026-09-16 授权合并复测）：三单任务组4合并为一次默认矩阵真跑；产物=`docs/rag-quality/baseline-after-quality-loop.json` + `baseline-after-quality-loop.md`（不是 after-citation / after-excel-header / after-multicondition 三个分文件）。failsafe：Tests run 1 Failures 0 Errors 0，elapsed 197.0s；suiteVersion=2.0；failureRate=0。
  - TB-01 recall 0→**1.0**（citP 0→0.5，ansC 0→1）；TB-10 recall 0→**1.0**（citP 0→0.333，ansC 0→1）
  - 其余 11 条 TABLE recall **无回退**（全 SAME）
  - 全套 recall@5 0.9105→0.9475 / MRR 0.8210→0.9136 / hitRate 0.96→1.0

## 1. 表头投影（¥0）

- [x] 1.1 改 `src/main/java/com/slz/crm/knowledge/document/DocumentService.java` 的 `parseExcel` / `normalizeRow`：多列短表头 → 数据行 `列名：值` tab 拼接；表头行不入库；空列省略；空列名回退 `列{n}`；重名加 `_2`。Javadoc 标「add-excel-header-projection 任务 1.1」
- [x] 1.2 启发式：首行非空单元格 ≥ 2 且每个非空单元格长度 ≤ 32；不满足则与现行为逐字一致（含单列长句表）
- [x] 1.3 `rowIndex` 仍为工作表原始行号；PDF/md/txt 解析路径零改动

## 2. ¥0 单测

- [x] 2.1 `DocumentServiceTest` 新增多列表头用例：用「设备型号 / 维保周期 / 计划工时(人·时) / 参与人数」+ 两行数据（含 XR-500 季度 4 / 2）；断言数据行文本含「计划工时」、不含独立表头块、rowIndex 为 2 和 3（表头是第 1 行）
- [x] 2.2 既有 `semanticStrategyPreservesRowIndexAnchorsOnExcel` 继续绿（单列不投影）
- [x] 2.3 新增「首行超长不投影」用例：首行某格 > 32 字，行文本仍为原值拼接、首行仍入库
- [x] 2.4 `RagBenchmarkDataPreparerTest`：`maintenance-schedule.xlsx` / `regional-sales-q3.xlsx` 黄金行索引文本分别含「计划工时」与「销售额」（GOLD 标记仍剥离）

## 3. ¥0 回归与 CI 基线

- [x] 3.1 `mvn -B -ntp test` 全绿；**读本次 surefire 合计** 同步 ci.yml 三处。禁止推算
- [x] 3.2 Docker 可选：未开 Desktop 记 skip，禁止把 Tests run: 0 报绿

## 4. 真基准复测（授权节点，未授权禁止执行）

- [x] 4.1 授权已获（质量闭环三单合并复测）
- [x] 4.2 已跑纯默认矩阵（合并产物 `baseline-after-quality-loop.json`，非单独 after-excel-header；未覆盖 v1/v2；未注入 rag.*）
  ```
  $env:RAG_BENCHMARK_REAL='1'
  mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.out=docs/rag-quality/baseline-after-excel-header.json"
  ```
  禁止覆盖 v1 / v2 / after-citation；禁止注入 `rag.*`
- [x] 4.3 已对照 v2：TB-01/TB-10 recall 均 0→1；其余 TABLE 无回退；suiteVersion=2.0。详见 `baseline-after-quality-loop.md`
- [x] 4.4 合并产物 JSON+MD 入库；差异写入 §0

## 5. 收尾

- [x] 5.1 更新 `HANDOFF.md`（Excel 表头投影已落地；after-excel-header 待授权则标明；after-citation 仍待授权）
- [x] 5.2 git：`git checkout -b feature/add-excel-header-projection`（无 switch）；提交按任务组；提案三件套随首个提交入库；亲验全绿 + status 干净（三个已知未跟踪件勿提交勿删除）后 `--no-ff` 合入 master；**不 push**
