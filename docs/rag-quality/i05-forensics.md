# I-05 引用取证结论

> 变更：`trace-i05-citation-forensics` ｜ 跑次：仅 I-05 真外呼 ｜ 日期：2026-09-16  
> 旁路：`docs/rag-quality/i05-forensics.json` ｜ 单条报告：`docs/rag-quality/i05-forensics-report.json`（**非锚点**）  
> 禁改：CitationAligner / Evaluator 公式 / CaseScore / baseline-v1|v2|after-quality-loop

## 三选一结论

**KEEP 错号**（有 `[n]`，对齐前后相同，指向非黄金）。

| 判定项 | 实测 |
|---|---|
| 对齐前 citations | `[1]` |
| 对齐后 citations | `[1]`（与对齐前相同，未 remap、未 DROP） |
| `[1]` 指向 | `sla-3`（retrieved rank 1） |
| 黄金 id | `sla-2`（retrieved rank 3，n=3） |
| citationPrecision | `0.0` |
| answerConsistency | `1.0` |
| recall@5 | `1.0`（黄金在 topK 内） |

**不是漏引**：对齐前后答案均含 `[1]`。  
**不是 remap 到另一非黄金**：对齐器未改写编号（raw == aligned）。

## 对齐前后答案原文

**rawAnswer：**

```text
服务台架构图注分为四层：接入层、调度层、处理层、数据层 [1]。  
其中，数据层是 **SLA 台账与预警引擎** [1]。
```

**alignedAnswer：**（逐字相同）

```text
服务台架构图注分为四层：接入层、调度层、处理层、数据层 [1]。  
其中，数据层是 **SLA 台账与预警引擎** [1]。
```

## 召回排名与 excerpt（截断）

| n | chunkId | 是否黄金 | excerpt 要点 |
|---|---|---|---|
| 1 | `sla-3` | 否（被引用） | **完整**「服务台架构图注：接入层…→ 数据层 SLA 台账与预警引擎…」——词面最支撑答案 |
| 2 | `arch-0` | 否 | 系统架构/数据流（报表平台），与服务台分层无关 |
| 3 | `sla-2` | **是** | 以赔偿条款/责任工程师为主，图注正文被切到相邻块；本条 excerpt 词面弱于 n=1 |
| 4 | `arch-1` | 否 | 部署结构（nginx/K8s） |
| 5 | `customer-sop-2` | 否 | 客户分级 SOP |

## 解读（取证层，不改代码）

1. 模型把支撑最强的 rank1（`sla-3`，含完整图注）标成 `[1]`，答案要点覆盖满分。  
2. CitationAligner 按设计对原号做支撑分 KEEP：`sla-3` 词面覆盖「四层 / 台账与预警引擎」，KEEP 阈值满足 → **不 remap 到黄金 rank3**。  
3. citP 公式只认「引用编号 → retrieved[n-1] ∈ goldenIds」：`sla-3 ∉ {sla-2}` → citP=0。  
4. 根因候选（另案，本单不改）：黄金块切分/占位对齐把完整图注落在 `sla-3` 而黄金标在 `sla-2`；或对齐器在「非黄金但词面更强」时 KEEP 导致与 goldenIds 口径冲突。  
5. **本单禁止**改对齐器阈值、补漏引、改 I-05 黄金；下一步另立提案。

## 跑法复现

```powershell
$env:RAG_BENCHMARK_REAL='1'
mvn -B -ntp test-compile failsafe:integration-test `
  "-Dit.test=RagRealRetrievalBenchmarkIT" `
  "-Drag.benchmark.only=I-05" `
  "-Drag.benchmark.out=docs/rag-quality/i05-forensics-report.json" `
  "-Drag.benchmark.trace.out=docs/rag-quality/i05-forensics.json"
$env:RAG_BENCHMARK_REAL=''
```

确认未覆盖：`baseline-v1.json` / `baseline-v2.json` / `baseline-after-quality-loop.json`（SHA256 跑前跑后一致）。
