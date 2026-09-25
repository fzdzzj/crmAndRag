# 提案：补登记 rag.retrieval 文档已有、注册表缺失的动态配置键

> 变更 ID：`register-rag-retrieval-dynamic-keys`｜能力域：dynamic-config｜方案日期：2026-09-25｜依据：权威 `master` `d0ecaf2` 上的只读配置漂移核查。**全程 ¥0。不扩命名空间白名单，不翻任何代码默认值，不授权真实模型调用、生产开关、reingest 或 push。**

## Why

`docs/dynamic-config-keys.md` 已列出检索管线、融合、重排、图文路由等键，但 `DynamicConfigKeyRegistry` 当前只登记了 `rag.retrieval` 下的 topK / minScore / chunkSize / chunkOverlap / strictKb / admin-vector.* / vision-pdf.*。未注册键在 `DynamicConfigServiceImpl.get` 一律回落调用方默认值，管理端 `requireDefinition` 视为未知键，超管无法按文档调参。

这不是运行期缺陷：代码默认值仍生效。缺口是**文档声称可运营，注册表却不认**。`rag.context.*` / `rag.chunking.*` / `rag.query.*` 不在 `NAMESPACES` 白名单（仅 `ai.prompt` / `ai.model` / `rag.retrieval` / `rag.intent` / `business`），补登记前必须先扩白名单，**不在本案**。

## What Changes

只把下列 **11** 个已落在 `rag.retrieval` 命名空间、文档有而注册表无的键，按现有 `def(...)` 登记进 `DynamicConfigKeyRegistry`，默认值与当前代码内联默认对齐：

| 键 | 类型 | 默认 | 校验 |
|---|---|---|---|
| `rag.retrieval.query-rewrite.enabled` | Boolean | true | — |
| `rag.retrieval.fusion.mode` | String | `rrf` | 允许 `rrf` / `weighted` |
| `rag.retrieval.fusion.rrf-k` | Integer | 60 | ≥1 |
| `rag.retrieval.rerank.mode` | String | `default` | 允许 `default` / `llm` |
| `rag.retrieval.rerank.vector-weight` | Double | 0.60 | 0~1 |
| `rag.retrieval.rerank.bm25-weight` | Double | 0.40 | 0~1 |
| `rag.retrieval.rerank.candidate-multiplier` | Integer | 4 | ≥1 |
| `rag.retrieval.rerank.llm.timeout-ms` | Long | 3000 | ≥1 |
| `rag.retrieval.rerank.llm.max-candidates` | Integer | 20 | ≥1 |
| `rag.retrieval.image-text-route-weight` | Double | 0.70 | 0~1 |
| `rag.retrieval.image-vector-route-weight` | Double | 0.30 | 0~1 |

同步：

1. `DynamicConfigKeyRegistryTest` 断言这 11 键 `definitionOf` 存在，并覆盖类型/越界拒绝（至少 Boolean、枚举、0~1、整数下限各 1 例）。
2. `docs/dynamic-config-keys.md` 标明这 11 键**现已注册**、可由既有超管动态配置入口写入；未改语义与默认值。若文档与代码默认不一致，**以代码读取点为准**并改文档，不改运行默认。

## 不改

- 不扩 `NAMESPACES`，不登记 `rag.context.*` / `rag.chunking.*` / `rag.query.*`。
- 不改检索实现、权限、迁移、前端、冻结契约。
- 不把 `rerank.mode` 默认改成 `llm`，不关查询改写，不打开 vision-pdf / admin-vector。
- 不为 `ai.*` / `business.*` 补文档（文档标题本就限定检索链路）。

## Impact

- **行为**：缺省运行路径与现在一致。变化是超管（roleId=1）登记后可通过既有动态配置管理入口**改**这些键，热生效。`query-rewrite.enabled` 默认仍为 true；`rerank.mode=llm` 仍须超管显式写入才会走 LLM 重排（可能产生模型费用）。这是既有超管写权的延伸，不是新权限码，也不是生产开关授权。
- **测试**：只增注册表单测；surefire 基线若增加，只能用仓库脚本从干净真实运行 `--update`。
- **风险**：登记后误把 `rerank.mode` 写成 `llm` 会增加 chat 调用。对策：默认保持 `default`；描述写明 LLM 重排有费用；非法值仍按现有代码回落 `default`。`fusion.mode=weighted` 会关掉稀疏路参与，属既有语义，描述写清。

## 验收

实现后：11 键均可 `definitionOf` + `validate`；未注册的 `rag.query.*` / `rag.context.*` / `rag.chunking.*` 仍失败。用 mock/单测证明默认读取值与登记前代码默认一致。禁止真实模型、reingest、push、归档无关提案。
