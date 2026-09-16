# 提案：多条件查询零 LLM 拆路召回（T-14）

> 变更 ID：`fix-multicondition-recall` ｜ 能力域：`knowledge-retrieval` ｜ 序列：RAG 质量闭环第三单
> 来源：v2 锚点 T-14 recall=0.5；用户指定先做 T-14，再做摄取调研。引用对齐复测、Excel 表头复测仍挂起，本单禁止顺手跑。

## Why

T-14「客户主体变更后重新签合同要满足什么条件」：

| 已验证事实 | 证据 |
|---|---|
| recall=0.5、hit=true、MRR=0.5 | 两个黄金块只进了 1 个，且排第 2 |
| expected 两块跨文档 | `customer-onboard-4`（主体变更→风控复核→重签）+ `sales-flow-4`（合同变更审批链） |
| 同组 T-15/T-16/T-17 recall=1 | 不是「多黄金 TEXT 全坏」 |
| T-12 跨文档 4 黄金 recall=0.67 | 跨 chunk 也有缺口，本单不顺手修 |
| 多查询 LLM 默认关 | `rag.query.multi-query.enabled=false`（成本）；且 v2 锚点 run=V1 用 **6 参构造**，根本不装配 MultiQuery |

**当前假设**（JSON 无 retrieved 列表）：单查询向量被「合同变更」或「主体变更」一侧吸走，另一侧黄金进不了 top-5。把「A后B」拆成原查询 + 左句 + 右句再融合，两侧都有机会进候选。

不把 LLM 多查询默认打开：那是已拍板的成本闸门，且 V1 评测路径装配不到它，对照 v2 锚点会测空。

## What Changes

### 1. 纯函数拆句器（零 LLM、无 Spring）
新增 `com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter`（Javadoc 标「fix-multicondition-recall 任务 1.1」）：

- `split(String query) → List<String>`：首元素恒为原查询（strip 后）。
- 仅在原查询上找**第一处**分隔：`后` / `且` / `并且` / `同时`。
- 左右两侧都至少含 **4 个汉字**（或一侧汉字不足但含长度 ≥2 的 ASCII 词）才追加左右两路；否则只返回原查询。
- 不切 `的`、不递归、最多 +2 路。无网络、无配置键。

T-14 期望切出：
1. 客户主体变更后重新签合同要满足什么条件
2. 客户主体变更
3. 重新签合同要满足什么条件

### 2. 接入检索（6 参构造也必须走）
`KnowledgeRetrievalServiceImpl.retrieve`：改写之后，对 `split` 的每一路分别 `embed + recallTextRoute`，路数 >1 时用 **本地** `new RrfFusion().fuseAll(...)` 融合（即使 `this.rrfFusion == null` 的 V1 回退态也要融）。然后走现有 rerank / topK。

- **禁止改任何构造器签名**（6/9/10/全参测试直接 `new`）。
- 不打开 `rag.query.multi-query.enabled`。
- 普通查询（切不出）仍只 embed 1 次。

额外成本：命中拆句的查询多 2 次 embedding（无 chat）。提案视为可接受；不要为此加开关，除非复测全套 recall 回退。

### 3. ¥0 单测
- `ConstraintQuerySplitterTest`：T-14 形切出 3 路；无分隔/一侧过短不切；`并且`/`同时`；空白。
- 既有 `KnowledgeRetrievalServiceImplTest` / `QueryTransformationPipelineTest` 构造器不改仍绿。
- 可选：用内存向量桩证明「只搜整句 miss 一侧、拆路后两侧都进候选」（能写就写，写不出不要为了它引入真模型）。

### 4. 真基准复测（授权节点）
¥0 全绿后停。授权后纯默认矩阵（V1，不注入 `rag.*`）落 `docs/rag-quality/baseline-after-multicondition.json`。禁止覆盖 v1/v2/after-citation/after-excel-header。盯 T-14 recall（目标 1.0）；T-15/T-16/T-17 不回退。

## Impact

- **新增**：`ConstraintQuerySplitter.java` + 单测。
- **修改**：`KnowledgeRetrievalServiceImpl.retrieve` 召回路规划；ci.yml surefire 基线随实测上调。
- **不改**：MultiQuery 默认、HyDE、稀疏默认、构造器、SUITE_VERSION、用例与 fixtures、Flyway。

## 风险

- **过度切分**：「终审通过后的报备」也会切。T-15 已是 recall=1，融合含原查询，预期不降；复测若 T-15 回退则停下。
- **多 2 次 embed**：高频助手成本上升。只对过启发式的句子发生。
- **T-14 假设不成立**：若 miss 的是语义上不该召回的 `sales-flow-4`（讲金额工期而非主体），拆路也抬不起来。复测仍 0.5 则停下，不改黄金块、不打开 LLM 多查询。

## Non-Goals

- 不启用 LLM 多查询 / HyDE。
- 不修 T-12，不跑 after-citation / after-excel-header。
- 不改 CitationAligner、Excel 投影、54 条用例。

## 失败场景

1. 6 参构造测红或构造器被改。
2. 普通查询 embed 次数变成 N。
3. 复测 T-14 仍 0.5，或 T-15/T-17 recall 下降：停下汇报。
