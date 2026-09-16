# I-05 图注切分对齐后重跑结论

> 变更：`fix-i05-caption-chunk` ｜ 跑次：仅 I-05 真外呼（授权后） ｜ 日期：2026-09-16  
> 旁路：`docs/rag-quality/i05-after-caption-chunk.json` ｜ 单条报告：`docs/rag-quality/i05-after-caption-chunk-report.json`（**非锚点**）  
> 对照取证：`docs/rag-quality/i05-forensics.md`（citP 曾为 0.0）  
> 禁改：CitationAligner / Evaluator 公式 / CaseScore / baseline-v1|v2|after-quality-loop|i05-forensics.json

## 结论

**对症有效：citP 0.0 → 1.0。** 图注独立成 `sla-arch-diagram.md` 后，黄金块与完整图注同一切片；模型引用 rank1 = 黄金 `sla-arch-0`，对齐器 KEEP `[1]`，公式口径一致。

| 判定项 | 取证前（forensics） | 本跑（after-caption-chunk） |
|---|---|---|
| 对齐前 citations | `[1]` | `[1]` |
| 对齐后 citations | `[1]`（KEEP） | `[1]`（KEEP） |
| `[1]` 指向 | `sla-3`（非黄金，完整图注） | `sla-arch-0`（**黄金**，完整图注） |
| 黄金 id | `sla-2`（rank 3，赔偿/何建军粘连） | `sla-arch-0`（rank 1） |
| citationPrecision | `0.0` | **`1.0`** |
| answerConsistency | `1.0` | `1.0` |
| recall@5 | `1.0` | `1.0` |
| MRR | — | `1.0` |
| failureRate | — | `0.0` |

未改对齐器；raw == aligned。

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
| 1 | `sla-arch-0` | **是（被引用）** | 完整「服务台架构图注：接入层…→ 数据层 SLA 台账与预警引擎…」 |
| 2 | `arch-0` | 否 | 系统架构/数据流（报表平台） |
| 3 | `arch-1` | 否 | 部署结构（nginx/K8s） |
| 4 | `sla-2` | 否 | 赔偿条款 + 责任工程师何建军（图注已拆走） |
| 5 | `customer-sop-2` | 否 | 客户分级 SOP |

## 解读

1. 语料拆分后 `【GOLD:sla-arch-diagram】` 落在独立短文件唯一切片 `sla-arch-0`，索引文本含完整图注、不含何建军/赔偿当月服务费（¥0 单测已锁）。
2. 召回 rank1 = 黄金；模型 `[1]` 指向黄金 → citP=1.0。
3. 取证「KEEP 错号」根因坐实为**黄金块切分离位**，非对齐器阈值问题；本单不改 CitationAligner 的决策正确。
4. 与 after-quality-loop 全量 54 条 **不可直接比**（仅 I-05 单条；SUITE_VERSION 仍 2.0）。本报告**不是**新质量锚点。

## 受保护文件 SHA256（跑前=跑后）

| 文件 | SHA256 |
|---|---|
| `baseline-v1.json` | `D415EC482535D90B1F998550FB5BC2853BCF79A00CAF66A37B033CCF16481186` |
| `baseline-v2.json` | `D33778FB0E4EDF4166662DF1835FCC573B6D3A2BB185F64FBE01D04C6D0C911B` |
| `baseline-after-quality-loop.json` | `B55197C4C4FA281CBFC746549EE434777541771427B2B58AA06C8D612DC8C116` |
| `i05-forensics.json` | `7321CD8A87D536D8149A215A128E6BF47698EEB06E0267ADCFB087C534F82B09` |

## 跑法复现

```powershell
$env:RAG_BENCHMARK_REAL='1'
mvn -B -ntp test-compile failsafe:integration-test `
  "-Dit.test=RagRealRetrievalBenchmarkIT" `
  "-Drag.benchmark.only=I-05" `
  "-Drag.benchmark.out=docs/rag-quality/i05-after-caption-chunk-report.json" `
  "-Drag.benchmark.trace.out=docs/rag-quality/i05-after-caption-chunk.json"
$env:RAG_BENCHMARK_REAL=''
```

禁止覆盖：`baseline-v1.json` / `baseline-v2.json` / `baseline-after-quality-loop.json` / `i05-forensics.json`。
