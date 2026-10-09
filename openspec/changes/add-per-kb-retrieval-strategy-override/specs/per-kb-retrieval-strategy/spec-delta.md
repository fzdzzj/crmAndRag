# 增量契约规范：知识库级检索策略覆盖（add-per-kb-retrieval-strategy-override）

### 契约 1：覆盖键白名单封闭集（Closed Override Whitelist）

- **GIVEN** 任意 per-KB 策略写入请求；
- **WHEN** 目标键不在白名单（v1 恰 12 键：topK / minScore / fusion.mode / fusion.rrf-k / rerank.vector-weight / rerank.bm25-weight / rerank.candidate-multiplier / image-text-route-weight / image-vector-route-weight / context.neighbors / context.parent-expand / query-rewrite.enabled）；
- **THEN** 写入拒绝并返回明确错误；成本类键（rerank.mode / compressor.mode / multi-query.* / hyde.* / derived-questions.* / vision-pdf.* / replay-*）与结构类键（chunking.* / chunkSize / chunkOverlap）永远不得进入 per-KB 覆盖——白名单是封闭集，扩充需 owner 拍板并同步 docs/dynamic-config-keys.md。

### 契约 2：三层合并语义（Three-Layer Merge Semantics）

- **GIVEN** 单库检索请求且该库存在合法覆盖值；
- **WHEN** 解析检索参数；
- **THEN** 生效优先级 = 覆盖值 > 全局动态配置 > 注册表默认；覆盖值非法/越界（含旁路改库写坏）= WARN 审计日志 + 回落全局，任何情况不得使检索链路抛错；调用方显式传参优先级约定维持既有语义不变（topK 显式传参仍最优先）。

### 契约 3：单库作用域规则（Single-KB Scoping Rule）

- **GIVEN** 一次检索请求；
- **WHEN** 授权收敛后 kbScope 不恰为单库（多库 / 全库 / 空 / kbId 不可解析为 BIGINT）；
- **THEN** 一律走全局配置，per-KB 覆盖零参与；**最强不变量**：全库无任何覆盖配置时，所有检索输出与升级前逐字节一致（含多库与单库），RetrievalParamTruthSourceTest 全局锚（topK=5 / minScore=0.20）不动保持绿。

### 契约 4：写端权限与审计（Write Authority and Audit）

- **GIVEN** per-KB 策略写端点（清单 / PUT 覆盖 / DELETE 回落 / POST 回滚）；
- **THEN** 全部挂方法级 @RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900)（复用既有常量，不新增权限位、不动 V27 种子）；每次写前过白名单 + 类型/范围校验；每次写产生一行 history 审计（操作者、旧值、新值、版本）；回滚以 history 版本为源写回；超管全局配置中心维持冻结契约现状（仅超管可写）——900 与超管分层互不越界。

### 契约 5：存储与漂移对齐（Storage Alignment）

- **GIVEN** 新表 kb_retrieval_strategy / kb_retrieval_strategy_history（Flyway V29，序号以实测 V28 之后为准）；
- **THEN** 实体与迁移列集经 SchemaDriftAuditIT 零漂移核验；(kb_id, strategy_key) 活覆盖唯一（软删模式与 dynamic_config_item 对齐）；禁改 V1-V28 已合入脚本；回退 = 数据库快照（无 DROP 回滚）。

### 契约 6：复核区与停步格回补惯例（承 close-openspec-task-residue 契约 5）

- **GIVEN** 本卡 tasks.md §8 复核区与 7.5 停步格；
- **THEN** 勾选不在本卡内完成，卡片内预注册注记，回补载体为 owner 指定的后续 master 前向提交，带注记未勾格不构成悬空；三件套 tracked 入库、work/ 执行留痕不入库。

### 契约 7：基线与回归纪律（Baseline and Regression Discipline）

- **GIVEN** 本卡新增测试与台账更新；
- **THEN** surefire 基线只增不减（1019 → N，N>1019），`scripts/test-baseline.txt` 更新必须来自一次真实运行（脚本 `--update`）；RagRealRetrievalBenchmarkIT 与金标 fixtures 零触碰、SUITE_VERSION 不动（零覆盖路径逐字节一致由契约 3 保证）；红测试先行——实施前基线实跑贴红留证（work/_pac-red-first/），记录后方可实现转绿。
