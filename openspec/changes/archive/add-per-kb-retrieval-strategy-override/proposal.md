# 提案：知识库级检索策略覆盖（add-per-kb-retrieval-strategy-override，卡 P-ac）

## 为什么

当前全部 `rag.retrieval.*` 动态配置为平台级全局单值（`DynamicConfigAdminController` `/platform/config`，仅超管可写）。按 2026-10-09 主 agent 企业实践盘点（执行文档 §68 前立项讨论），企业实际中不同类型知识库（FAQ 库 vs 产品手册库）的最优检索参数必然不同——per-KB 策略覆盖是价值最大的缺口，owner 拍板按「第一档运营调参键放开、第二档成本键仍留超管全局、第三档结构键不进运行时开关」的分层方案实施本卡。既有冻结契约不动：`DynamicConfigService` 接口已冻（contracts-frozen.md §10/L113）、`RetrievalQuery{kbScope: List<String>}` 已冻（L83）——本卡全部旁路实现，零契约变更。

## 做什么

1. **覆盖键白名单（v1 恰 12 键，第一档运营调参类）**：`rag.retrieval.topK` / `minScore` / `fusion.mode` / `fusion.rrf-k` / `rerank.vector-weight` / `rerank.bm25-weight` / `rerank.candidate-multiplier` / `image-text-route-weight` / `image-vector-route-weight` / `context.neighbors` / `context.parent-expand` / `query-rewrite.enabled`。白名单外键一律拒绝写入（成本类 rerank.mode=llm、compressor.mode=llm、multi-query、hyde、derived-questions、vision-pdf.*、replay-* 与结构类 chunking.*、chunkSize/chunkOverlap 均不得 per-KB 覆盖——它们按分层定夺仍归超管全局或变更管理）。
2. **存储（Flyway V29，下一可用号实测 V28 之后）**：新表 `kb_retrieval_strategy`（id BIGINT PK、kb_id BIGINT NOT NULL、strategy_key VARCHAR(128)、config_value VARCHAR(512)、version INT、is_deleted、审计四列；UNIQUE(kb_id, strategy_key) 活覆盖唯一）+ `kb_retrieval_strategy_history`（镜像 dynamic_config_item/history 版本化审计模式）。
3. **读端三层合并语义**：合法白名单覆盖值 > 全局动态配置 > 注册表默认；覆盖值非法/越界 = 记审计日志 + 回落全局（打不断检索）。**作用域规则**：仅当检索请求经授权收敛后 kbScope 恰为单库时应用该库覆盖；多库/全库/kbId 不可解析 → 全局。专用缓存 `KbRetrievalStrategyService` 镜像 `DynamicConfigCache` 热失效模式（写后逐键失效、有界刷新兜底）。
4. **写端（复用既有知识库管理面）**：`KnowledgeAdminController`（既有 900 `KNOWLEDGE_ADMIN_MANAGE`，V27 已种权限）新增 4 端点：GET 策略清单（含生效值三来源标注）/ PUT 单键覆盖 / DELETE 单键覆盖（回落全局）/ POST 按版本回滚；全部挂 `@RequirePermission(900)`，写前白名单 + 类型/范围校验（复用 `DynamicConfigKeyRegistry` 语义），每写一行 history 审计。超管全局配置中心维持现状（冻结契约：仅超管可写）——形成企业分层：900 管 per-KB 运营参数，超管管全局与成本键。
5. **最强不变量（红测试锚）**：未配置任何覆盖时，全部检索输出（含多库/单库）与升级前逐字节一致；`RetrievalParamTruthSourceTest` 全局默认锚（topK=5、minScore=0.20）不动、保持绿。

## 不做什么

- 零契约变更：`DynamicConfigService` 接口、`RetrievalQuery`、`SourceReference`、既有 7 个知识库管理端点、四 list 端点、`/query` total 语义、OpenAPI 既有路径全部不动（新端点属新增面，走既有 OpenAPI 导出流程）。
- 不动成本类/结构类键（上列白名单即全集）；不做按用户/按角色灰度（挂账）；不做自动回归护栏（挂账，见 §影响）。
- 不动 `scripts/test-baseline.txt` 语义：新测试只能使 surefire 基线增、不能减，更新必须来自一次真实运行（脚本 `--update`）。
- 禁改已合入迁移脚本（V1-V28）；V29 起新建。不 push（推送归 owner）。真模型外呼零涉及（全部测试 DASHSCOPE_API_KEY 置空）。

## 影响

- 运行面：单库检索可按库调参（企业分层落地第一步）；默认行为零变化（无覆盖 = 全局）。
- 台账面：`docs/dynamic-config-keys.md` 新增 per-KB 覆盖章节（白名单、三层合并、单库作用域、热失效）；权限覆盖门禁（`PermissionCoverageScanner`）对新端点自动三选一校验；schema 漂移门禁对 V29 两表 + 新实体自动核验。
- 挂账（后续候选，本卡不做）：键级权限分层的细化 ACL、成本键的申请-审批流、策略变更触发金标 benchmark 回归护栏。
