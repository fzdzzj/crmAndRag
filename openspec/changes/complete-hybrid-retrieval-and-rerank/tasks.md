# Tasks — complete-hybrid-retrieval-and-rerank

## 0. 验证记录（执行 agent 填写）

- 2026-09-12（任务组1 执行时）：`FlywayMigrationIT` 在本机首次真跑（Docker mysql:8.0.36），暴露**存量缺陷**：
  `V6__dynamic_config.sql` 第 50 行列名 `sensitive` 撞 MySQL 8.0 保留字（最小语句复现 + 反引号可解），
  迁移链在 V6 即失败——V1..V21 从未在任何真 MySQL 上成功应用（此前本地/CI 均未带 Docker 真跑该 IT，
  H2 测试走 auto-table 不执行 Flyway 脚本）。已对 8 个迁移脚本 259 个列名做保留字全量探针，冲突仅此一处；
  V22 DDL 已在独立探针容器实测通过（FULLTEXT ngram 建索引成功，LEXICAL 型号整句查询黄金切片 rank 1）。
  影响：V6 卡死使一切依赖 Flyway 的 IT（含 1.1 断言与 1.2 闸门）无法跑绿；且"改错出 V(n+1)__fix"的
  补救对本缺陷不可行（V6 失败后后续脚本永不执行）。**待用户授权后就地修复**（`sensitive` 加反引号，
  结构与 H2 auto-table 产物一致、零 checksum 风险——本机各库均无 flyway_schema_history），修复并真跑后补勾 1.1/1.2。

- 2026-09-12（晚）：**V6 已获授权就地修复**——DDL 第 50 行 `sensitive` → `` `sensitive` ``；连带发现并修复同源
  运行时雷：`DynamicConfigItemEntity.sensitive` 无 `@TableField`，MyBatis-Plus 生成 SQL 裸用保留字，
  已补 `@TableField("`sensitive`")`。修复后真跑（本机 Docker；`env -u DASHSCOPE_API_KEY` + .env 键临时置空双防护，
  ModelProviderImplDashScopeIT 3 skipped 确认零外发）：surefire **555 全绿**；failsafe **24 跑 = 15 绿 + 7 门控跳过 + 2 红**
  （WriteChainRegressionIT 2 红为 task18 时代存量测试缺陷，与本提案无关，另案登记）。1.1–2.2 五项据此补勾。

## 1. 稀疏召回路（方案 16 补全）

- [ ] 1.1 新增 Flyway `V22__chunk_fulltext_index.sql`：`document_vector_chunk.chunk_text` 加 `FULLTEXT INDEX ft_chunk_text (...) WITH PARSER ngram`；`FlywayMigrationIT` 断言迁移成功且索引存在
- [x] 1.2 ngram 召回质量闸门：fixtures 里的 LEXICAL 文档灌入 Testcontainers MySQL，用 `MATCH...AGAINST` 查型号/编号/专名，断言黄金 chunk 全部命中；不达标则在本任务记录并切换方案 B（内存索引），V22 保留（只加索引无害）
  - 2026-09-12 真跑绿：ChunkNgramRecallGateIT 1/1，LEXICAL 6 用例黄金切片全部进 top5——**决策结论：方案 A（FULLTEXT ngram）达标，不切方案 B**。
    闸门"黄金发现集"断言由全等修正为"LEXICAL 黄金全部被发现"：干扰项 fixtures（sales-flow/payment-plan）与基准套件共用、自带各自 GOLD 标记，发现集天然是超集（该 IT 此前被 V6 阻塞从未真跑）
- [x] 1.3 实现 `SparseRecallService`：入参 query + 授权 KB 集合 + 类目 + 候选数，出候选块；`DocumentVectorChunkMapper` 增全文检索方法
- [x] 1.4 单测（InMemory/H2 或 Testcontainers）：构造"向量 miss + 词法 hit"用例（自定义 embedding 让向量路漏召），断言命中块经稀疏路进入最终 top-K
- [x] 1.5 越权单测：用户仅授权 KB1 时，KB2 的块不出现在稀疏路结果（伪造 metadata 不可放大授权集合）

> 2026-09-12 V6 修复后真跑绿：SparseRecallServiceIT **4/4**（授权 KB join 命中 / 越权 KB 不泄漏 / 类目过滤收窄 / limit 守卫），
> 1.3–1.5 补勾（管线级单测 SparseRecallPipelineTest 此前已随 surefire 全绿）。

## 2. 类目过滤（D17 收尾）

- [x] 2.1 `recall()` 向量路 filter 增 `category`（null/空 = 不过滤）；稀疏路同语义
- [x] 2.2 单测：类目命中过滤生效；空类目不过滤；越权类目不放大结果

> 2026-09-12 真跑绿：`CategoryFilterPipelineTest`（surefire）+ `SparseRecallServiceIT#categoryFilterShouldNarrowSparseResults`
> （真库稀疏路 SQL 侧），2.1–2.2 补勾。

## 3. RRF 融合

- [x] 3.1 实现 `RrfFusion`（k 读 `rag.retrieval.fusion.rrf-k`，默认 60）；`rag.retrieval.fusion.mode = rrf | weighted`（默认 rrf）
- [x] 3.2 单测：两路命中同一块时 RRF 分 = Σ1/(k+rank)（手算对照）；单路命中退化为该路排名
- [x] 3.3 回退开关单测：`fusion.mode=weighted` 时行为与升级前等价（既有 `KnowledgeRetrievalServiceImpl` 相关测试不改一行全绿）

## 4. 重排器抽象与 LLM rerank（方案 08 升级）

- [x] 4.1 抽取 `Reranker` 接口；默认实现搬运现有"向量/BM25 归一化加权"逻辑，行为等价单测（同输入同排序）
- [x] 4.2 LLM rerank 实现：`ModelProvider` listwise 打分，`rag.retrieval.rerank.mode = default | llm`（默认 default）；失败/空输出/超时回退默认链，单测覆盖三条回退路径
- [x] 4.3 `KnowledgeRetrievalServiceImpl` 接线：召回（向量+稀疏）→ 融合 → rerank → 图文路由融合 → topK（既有管线顺序不变，仅插入新环节）

## 5. 基线验收（对照 add-rag-quality-baseline）

- [ ] 5.1 重跑真检索基准：LEXICAL 用例 recall@k / MRR 较 `baseline-v1.json` 提升；全量用例聚合 recall@k / hitRate / citationPrecision 不低于基线 【待授权：DASHSCOPE_API_KEY 真实外发；且前置依赖提案1任务4.1基线首跑（baseline-v1.json 尚不存在）】
- [ ] 5.2 产出 `baseline-after-hybrid.json` 落盘同目录，差异摘要写入本 change 的验证记录 【待授权：同 5.1，随任务组7一并执行】
- [x] 5.3 `mvn -B -ntp test` 绿（surefire ≥473+新增）；`mvn -B -ntp verify` failsafe ≥12+新增（本地无 Docker 按 skip 口径）

  > 2026-09-12 实测：surefire **494 全绿**（=473+新增21）。failsafe 部分被 §0 V6 存量缺陷阻塞（本机有 Docker 也无法跑绿），
  > 修复 V6 后需真跑 `mvn -B -ntp verify` 复核并回填。
  > 2026-09-12（晚）V6 修复后复核：failsafe **24 跑 = 15 绿 + 7 门控跳过 + 2 红**（WriteChainRegressionIT 存量另案）；
  > 本提案新增 IT 全部真跑绿（FlywayMigrationIT 3 / SparseRecallServiceIT 4 / ChunkNgramRecallGateIT 1）。
- [x] 5.4 DynamicConfig 键清单更新到 `docs`（键名/默认值/回退语义），新键有默认回退（缺省 = rrf 模式下稀疏路可用、rerank=default）

  > 落盘 `docs/dynamic-config-keys.md`（含既有键盘点 + 提案2 新键 fusion.mode/fusion.rrf-k/rerank.mode/llm 两键）。

## 6. Git 操作（按 `openspec/git-workflow.md` 执行）

- 分支：`git checkout -b feature/complete-hybrid-retrieval-and-rerank`（基于提案 1 合入后的 master）。
- 提交序（任务组 → 提交）：
  1. `feat(db)`: V22 FULLTEXT ngram 迁移 + FlywayMigrationIT 断言（1.1）
  2. `test(retrieval)`: ngram 召回质量闸门 IT（1.2）——**决策点**：LEXICAL 黄金不达标 → 切方案 B（内存倒排），结论写入本文件验证记录后再继续 1.3
  3. `feat(retrieval)`: SparseRecallService + 稀疏路"向量miss/词法hit"与越权单测（1.3–1.5）
  4. `feat(retrieval)`: 类目过滤两路接入（2.1–2.2）
  5. `feat(retrieval)`: RrfFusion + 回退开关行为等价（3.1–3.3）
  6. `feat(retrieval)`: Reranker 抽象 + 默认实现等价搬运 + LLM rerank 三条回退链（4.1–4.3）
  7. `test(quality)`: 基线对照 + `baseline-after-hybrid.json` 入库（5.1–5.2）——**成本闸门**：依赖提案 1 tasks 4.1 基线首跑（`baseline-v1.json` 实测数字）；未完成则停下向用户要授权
  8. `chore(ci)`: surefire 基线 bump（473→实测值）+ DynamicConfig 键清单 docs（5.3–5.4）
- 合并：亲验测试绿 + status 干净后，`git checkout master; git merge --no-ff feature/complete-hybrid-retrieval-and-rerank -m "Merge branch '...'：提案2/5 混合检索补全+重排升级"`。
