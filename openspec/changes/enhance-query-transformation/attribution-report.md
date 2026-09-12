# 归因报告 — enhance-query-transformation 立项触发条件核验

> 2026-09-12 ｜ 结论：**触发条件不满足（无基线数据可归因），不开分支、不实现任务组 1–3**。
> 本文同时记录提案 1–4 的完成度审计（含缺漏清单与解除顺序），供授权决策使用。

## 1. 触发条件核验结论

proposal.md 的立项前置是"以 add-rag-quality-baseline 及提案 2/3/4 后的基线归因为准"。核验结果：

- `docs/rag-quality/` 目录**为空**；全仓（`git ls-files`、`target/`、rag 学习工作区）均无 `baseline-v1.json` 与任何 `baseline-after-*.json`。
- 提案 1 任务 4.1（基线首跑）未勾，标注"待授权：真实外发约 60–90 次模型调用"；`baseline.md` 状态节仍是"基线数字待首跑"。
- 提案 2/3/4 的基线对照任务（5.1/5.2 及提案 4 的 4.4 全量 reingest）全部未勾，且均显式标注前置依赖提案 1 的 4.1。
- **根因**：`RAG_BENCHMARK_REAL=1` 真实外发的成本闸门（git-workflow §5）从未获授权，基线链从源头断掉。查询-文档词汇失配是否为主要漏召原因，**没有任何实测数据可归因**——三个触发条件（07/15/06）既不能证实也不能证伪。

判定：不满足 → 按任务书与 tasks.md §6 前置闸门，**不开分支**，产出本报告交差。11/14 待定台账（deferred.md，任务 5.1）属本提案执行范围，随立项一并顺延，本次不写。

## 2. 本轮实测证据（2026-09-12，master @ 8e281f4）

- `mvn -B -ntp test`：**Tests run 555, Failures 0, Errors 0, Skipped 0，BUILD SUCCESS**——与 ci.yml 基线 555 一致，单测链健康。
- 复跑 `mvn -B -ntp test-compile failsafe:integration-test -Dit.test=FlywayMigrationIT`（本机 Docker mysql:8.0.36）：**Tests run 3, Failures 2, Errors 1**，全部同根因：
  - `allMigrationsApplyInOrderOnRealMySql` → `Script V6__dynamic_config.sql failed ... near 'sensitive tinyint default 0 not null ...' at line 50`（`sensitive` 撞 MySQL 8.0 保留字）；
  - `chunkFulltextIndexExistsWithNgramParser`（V22 断言）与 `chunkParentLinkColumnsExist`（V23 断言）连锁失败——迁移链死于 V6，**V1..V23 从未在任何真 MySQL 上完整应用**，V22/V23 仅有独立探针容器实测记录。
- 静态核对：`V6__dynamic_config.sql` 第 50 行列名 `sensitive` 在库；提案 2/3/4 关键类（SparseRecallService/RrfFusion/Reranker/ContextBuilder/Compressor 族/语义切分/reingest runner）全部在库；`docs/dynamic-config-keys.md` 已建；ci.yml 三处 555 一致；`.env` 已含 DASHSCOPE_API_KEY（仅缺授权）；`docs/rag-quality/` 不在 .gitignore（基线 JSON 产出后可直接入库）。

## 3. 提案 1–4 缺漏审计清单

| # | 缺漏 | 性质 | 卡点 |
|---|---|---|---|
| A1 | 提案 1 任务 4.1：`baseline-v1.json` 基线首跑从未执行 | 量化验收缺位 | 成本闸门待授权（key 已在 .env，命令见 baseline.md） |
| A2 | 提案 1 任务 4.4：baseline.md"基线数字"节空置 | 随 A1 | A1 |
| B1 | 提案 2 任务 1.1/1.2：V22 迁移 + FlywayMigrationIT ngram 断言未勾 | 存量缺陷阻塞 | V6 `sensitive` 保留字，真库迁移链死亡 |
| B2 | 提案 2 任务 1.3–1.5/2.1–2.2：稀疏路 DB 级 IT、类目过滤真跑未勾 | 同上（代码已在库，管线级单测绿） | V6 |
| C1 | 提案 2/3/4 任务 5.1/5.2：基线对照重跑与 after JSON 全部未执行 | 量化验收缺位 | 成本闸门 + A1（无对照锚点） |
| C2 | 提案 4 任务 4.4：全量 reingest（重嵌入跑批）未执行 | 入库侧未升级 | 成本闸门待授权（runner 已实现：`KnowledgeReingestRunner`） |

说明：

- **代码本身无缺**：四个提案声明"已实现"的类与单测全部在库，surefire 555 绿背书；缺的是两类**待授权动作**（真实外发成本 ×2 类）与一个**存量缺陷修复**。
- **V6 修复方案已有论证**（提案 2 §0）：就地给 `sensitive` 加反引号，结构与 H2 auto-table 产物一致；因所有库均无 `flyway_schema_history`，无 checksum 冲突风险。注意"改错出 V(n+1)__fix"对本缺陷**不可行**（V6 失败后后续脚本永不执行）。这属于改已合入迁移脚本，须用户显式授权。
- 量化验收缺位的后果：提案 2（LEXICAL 召回提升）、提案 3（token 下降）、提案 4（切分升级不回退）的"不回退/提升"主张目前只有单测级等价证据，**没有真检索基准数字**。

## 4. 解除缺漏的最短顺序（全部需授权，按依赖排序）

1. **授权 V6 就地修复**（一行反引号）→ 本机 Docker 真跑 `mvn -B -ntp verify`：预期 FlywayMigrationIT 3 用例绿、V22/V23 断言转绿、稀疏路/类目过滤 DB 级 IT 真跑绿 → 补勾提案 2 的 1.1–2.2。
2. **授权提案 1 任务 4.1 基线首跑**（约 60–90 次 DashScope 调用）→ `baseline-v1.json` 落盘入库 → 回填 baseline.md（提案 1 的 4.4）。
3. **授权提案 4 任务 4.4 全量 reingest**（重嵌入 ×chunk 总量）→ 随后按链补跑提案 2/3/4 的 5.1/5.2（各自 after JSON + 差异摘要）。
4. 用最新基线重做本提案（5/5）的触发条件归因：基准套件 18 条中 **LEXICAL 6 条即词汇失配类**（含 2 条"向量相似度低但文本精确包含目标词"的构造），首跑后即可机械判定三个触发条件是否成立，再决定是否开 `feature/enhance-query-transformation`。

## 5. 本次执行边界

- 未开分支、未改任何代码/配置/迁移脚本；tasks.md 勾选状态未动（勾选须随真完成走）。
- 本报告为新增文件，落在 `openspec/changes/enhance-query-transformation/`，未提交（提交模型 = 任务组随分支走；报告-only 交差是否入库由用户定）。
- 未触碰成本闸门项；未删除/修改 `_rag优化交接.md`、`_vlm_transcribe.py` 两个已知未跟踪文件。

## 6. 后记（2026-09-12 晚，授权后执行记录）

- **V6 `sensitive` 保留字已获授权就地修复**：DDL 第 50 行反引号包裹 + `DynamicConfigItemEntity` 补
  `@TableField("`sensitive`")`（堵住 MyBatis-Plus 运行时生成 SQL 的同源雷）。真库迁移链 V1..V23 首次完整应用，
  §3 表中 B1/B2 缺漏解除，提案 2 的 1.1–2.2 已补勾。
- 真跑口径（本机 Docker；`env -u DASHSCOPE_API_KEY` + `.env` 键临时置空双防护，DashScope IT 3 skipped 确认零外发）：
  surefire **555 全绿**；failsafe **24 跑 = 15 绿 + 7 门控跳过 + 2 红**。2 红集中在 `WriteChainRegressionIT`
  （task18 时代存量测试缺陷：setup 引用不存在的 customer_contact/user 12 行，且任务删除链返回 90004
  INTERNAL_SERVER_ERROR）——该 IT 从未在真库跑过，**疑似写链路真实缺陷，另案登记待授权诊断**。
- A1/A2/C1/C2（基线首跑、全量 reingest、三案基线对照）仍待阿里云外发授权；§4 解除顺序不变，
  本提案立项判定维持**不开分支**。
