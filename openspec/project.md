# OpenSpec — crmAndRag 检索链路优化

> 来源：对 `c:\Users\fzdzzj\Desktop\rag`（RAG 学习知识库 rag-kb）所教学的 17 个 RAG 优化方案做本项目适用性分析后的立项。
> 铁律（rag-kb `08-评估与迭代.md` 路径 B）：**先有度量再优化，不凭感觉调参**。所有检索侧变更以 `com.slz.crm.quality.RagQualityEvaluator` 基线（`add-rag-quality-baseline` 落盘）做"不回退"验收。

## 能力域

| 能力域 | 范围 | 首个变更 |
|---|---|---|
| `rag-quality` | 检索/生成质量基准集、数据准备、基线报告、CI 门禁 | add-rag-quality-baseline |
| `knowledge-retrieval` | 召回（稠密/稀疏）、融合、重排、过滤 | complete-hybrid-retrieval-and-rerank |
| `rag-context` | 检索后上下文组装：邻居增强、压缩、token 预算 | add-context-compression-and-enrichment |
| `document-chunking` | 切分策略、块头、双粒度索引、重建入库 | upgrade-semantic-chunking-and-index |
| `rag-query` | 查询侧增强：多查询、HyDE、衍生问题 | enhance-query-transformation |

## 17 方案 → 本项目处置总表

| rag-kb 编号 | 方案 | 处置 | 落点 |
|---|---|---|---|
| 16 | Hybrid Search | 部分已有 → 补全 | complete-hybrid-retrieval-and-rerank |
| 08 | Rerank | 部分已有 → 升级 | complete-hybrid-retrieval-and-rerank |
| 10 | Context Compression | 缺 → 新增 | add-context-compression-and-enrichment |
| 04 | Context Enriched | 缺 → 新增 | add-context-compression-and-enrichment |
| 02 | Semantic Chunking | 缺 → 新增 | upgrade-semantic-chunking-and-index |
| 05 | Chunk Header | 缺 → 新增 | upgrade-semantic-chunking-and-index |
| 03 | Small-to-Big | 缺 → 新增 | upgrade-semantic-chunking-and-index |
| 07 | Query Transformation | 部分已有 → 增强 | enhance-query-transformation |
| 15 | HyDE | 缺 → 可选新增（默认关） | enhance-query-transformation |
| 06 | Document Augmentation | 缺 → 可选新增 | enhance-query-transformation |
| 11 | Feedback Loop | 待定（触发：产品提供点赞/点踩信号） | enhance-query-transformation 附台账 |
| 14 | Hierarchical Index | 待定（触发：单库 chunk 量级超阈值/跨库路由需求） | enhance-query-transformation 附台账 |
| 12 | Self-RAG | 不做（复杂度；等基线归因后再议） | — |
| 13 | KG RAG | 不做（CRM 实体走 DB 查询，文档侧无多跳需求） | — |
| 17 | CRAG 完整版 | 不做（企业合规库不接 web 兜底；诚实兜底 D16 已覆盖） | — |
| 01 | Simple RAG | 基线对照，非升级项 | — |
| 09 | Sentence Window | 不单独立项（与 04 邻居增强同机制，按需在 rag-context 演进） | — |

## 硬约束（所有变更遵守）

- 不改 `platform/contract/` 冻结接口（`KnowledgeRetrievalPort`/`SourceReference`/`CrmVectorStore`/`ModelProvider`/`DynamicConfigService`/`TokenUsageRecorder`）；改契约走解冻流程。
- 库结构变更 = 新增 Flyway 迁移，**下一可用号以 `ls src/main/resources/db/migration` 实测为准**（早期 `V22` 起算的写法已过期；历史快照截至 2026-09-19 最高为 `V27`），禁改一切已合入 master 的脚本；新增脚本须同轮更新 `FlywayMigrationIT` 的 `EXPECTED_VERSIONS`（见 `openspec/git-workflow.md` §4）。
- 验收命令：`mvn -B -ntp test`（surefire，只增不减）、`mvn -B -ntp verify`（追加 failsafe，无 Docker 为下限口径）；**基线数字以 `.github/workflows/ci.yml` 为准（当前 surefire 657 / failsafe 13，截至 2026-09-19）**。
- 新 LLM 调用一律经 `ModelProvider` + `ModelCallOptions`，key 只走环境变量。
- rag-kb 知识引用口径：deck（S11）数字属工程经验口径，不作实测基线；实测以本项目基准报告为准。
- 执行 agent 的分支/提交/合并/CI 基线/成本闸门操作遵循 `openspec/git-workflow.md`（含本机坑：无 `git switch`、PowerShell 无 `&&`、无 remote 禁 push）；各提案 tasks.md 末节"Git 操作"是该提案的专属执行序。
