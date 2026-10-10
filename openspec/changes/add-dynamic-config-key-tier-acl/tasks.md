# 任务清单：add-dynamic-config-key-tier-acl（卡 P-ad）

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线 / 合并节点：feature/add-dynamic-config-key-tier-acl / 基线 master@a109e51 / 合并节点 57253ef（双亲 = a109e51 + 7614b0d，master 收口笔回填）
- 红测试先行：实施前在基线 master@a109e51 实跑本卡新测试贴红（红输出归档 work/_pad-red-first/）：608 常量缺失（反射断言）、7 端点注解缺失（扫描断言）、行为红（非超管读 /items 与非超管写运营档键 topK 现基线均 FORBIDDEN 96005）、覆盖矩阵档位红（7 端点现 INTENTIONAL_OPEN）；见 red-constant.log / red-annotation.log / red-behavior.log / red-matrix.log
- 门禁 raw 留证位置：work/_pad-gate-raw/（mvn-test.raw / 四静态 / baseline / 三自测 / merge-gate；一律显式 UTF-8 无 BOM 写出）
- surefire 基线变化：1048 → 1058（只增，红测试全转绿；test-baseline.txt 更新来自一次真实运行 `--update`，failsafe 实际 25/87/6，较立项预估 24/83/6 只增不降（含 P-ac CI 修复轮 2 份 10-09 报告合法沿用））
- 未跑项明列：Testcontainers 定向 IT 4 类 13 run Docker 实测全绿（it-tier-acl-fix2.raw：DynamicConfigTierAclIT 4/4、PermissionsInterceptorStatusIT 3/3、FlywayMigrationIT 4/4、PermissionCoverageAuditIT 2/2）；全量 failsafe [it] 未重跑（默认序列不跑 verify，基线沿用上一轮合法）；真实模型外呼零涉及（全程 DASHSCOPE_API_KEY 置空）；CI 第 26 轮未验证（待 owner 授权推送）
- （如触发 CI 红修复，在本节补记红因与修复笔）

## 1. 常量、定级与种子

- [x] 1.1 红测试先行：先写本卡全部测试（任务组 1-3 的红锚），在基线实跑贴红归档 work/_pad-red-first/（四类红证据各≥1：608 常量缺失 / 端点注解缺失 / 行为红（非超管读、运营档写被 96005 拒）/ 覆盖矩阵档位 INTENTIONAL_OPEN 不符）
- [x] 1.2 `PermissionOperates` 新增 `PLATFORM_DYNAMIC_CONFIG_MANAGE(608L, "平台动态配置管理")`（6xx 权限管理段顺延，实测 601-607 在用；插在 607 与 700 段之间，Javadoc 标注「add-dynamic-config-key-tier-acl 任务 1.2」）
- [x] 1.3 键级三档定级落地：新枚举 `ConfigKeyTier`（OPERATIONAL / COST / STRUCTURAL，platform/config 包）+ 定级登记 `ConfigKeyTierPolicy`（封闭 63 键定级表，**零触碰** `ConfigKeyDefinition` record 与 Registry 63 个 def() 登记行）；`tierOf(ConfigKeyDefinition)` = sensitive=true 防御性映射 COST → 显式登记表 → 默认 OPERATIONAL；定级表逐键如下（总数 63 = Registry catalog 实测普查，2026-10-09）：
      **OPERATIONAL（34 键，608 可写）**：ai.prompt.system；ai.model.temperature；ai.model.maxTokens；rag.retrieval.topK / minScore / strictKb / query-rewrite.enabled / fusion.mode / fusion.rrf-k / rerank.vector-weight / rerank.bm25-weight / rerank.candidate-multiplier / image-text-route-weight / image-vector-route-weight；rag.context.neighbors / parent-expand；rag.intent.filterEnabled / categories / keywords；business.assist.reminderEnabled / feature.aiAssistantEnabled / assistant.imageCacheMaxEntries；platform.resilience.failure-threshold / open-duration-ms（全局 2 + 五依赖 model-chat/model-embed/model-vision/vector-qdrant/storage-minio × 2 = 12 键，熔断调参；既有「熔断键走既有通用动态配置权限、无超管特例」约束在本卡语义下落位运营档）
      **COST（22 键，超管专写）**：ai.model.chatModel / visionModel（模型选择=单价与能力变更，成本线拍板）；business.rateLimit.perMinute / quota.maxTokensPerSession（费用上限）；rag.retrieval.admin-vector.enabled（管理端真向量检索=真实嵌入调用，键描述明示「开启前仍需 owner 授权」）；rag.retrieval.vision-pdf.enabled / min-text-chars / max-pages（VLM 外呼族，随族定档）；rag.retrieval.rerank.mode / rerank.llm.timeout-ms / rerank.llm.max-candidates（LLM 重排族，llm 模式产生真实模型调用）；rag.context.token-budget（抬高上下文预算=抬高每次调用输入成本）/ compressor.mode / compressor.llm.timeout-ms（LLM 压缩族）；rag.query.multi-query.enabled / variants / hyde.enabled / timeout-ms / derived-questions.enabled / max-per-chunk（查询增强族，默认全关、开启即产生模型费用）；rag.ingest.replay-enabled / replay-batch-size（重放=真实嵌入调用，费用红线键）
      **STRUCTURAL（7 键，超管专写）**：ai.model.provider（切换调用基础设施 dashscope/openai-compatible/vllm，牵连静态凭据配置，变更管理）；ai.model.embeddingModel（键描述明示「须与 Qdrant 集合维度一致，改动需重建集合——破坏性」，2026-10-09 三档讨论明列第三档）；rag.retrieval.chunkSize / chunkOverlap（键描述明示「改后需重新入库生效」，P-ac 白名单结构类排除项）；rag.chunking.strategy / max-chunk-size（切分策略族，变更管理窗口 + reingest）；business.dataScope.enabled（安全语义键：false=关闭行级过滤全量可见，键描述明示「仅排障用，慎开」）
      census 防呆单测 `ConfigKeyTierPolicyTest`：断言 Registry definitions() 全集恰 63 键且逐一有档、COST/STRUCTURAL 封闭集与上表逐键一致、全集数变化（新增键未定级）即红、sensitive=true → COST 防御规则、P-ac per-KB 12 键全部 ∈ OPERATIONAL（两写面分层一致性锚）
- [x] 1.4 Flyway `V30__dynamic_config_permission_seed.sql`（实测 V29 已用，下一可用 V30；对齐 V27 单码种子模式，表/列名以 V27 实测为准）：`INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES (608, 'PLATFORM_DYNAMIC_CONFIG_MANAGE', '平台动态配置管理');` + `INSERT INTO role_permissions (permissions_id, role_id) SELECT p.id, r.id FROM permissions p CROSS JOIN sys_role r WHERE p.id IN (608) AND r.id NOT IN (0, 1, 2) AND r.is_deleted = b'0';`（授权面 = 全部业务角色；超管拦截器直通不授）；头部注释含目的/影响表/授权策略/回退说明（禁 DROP 回滚，回退=快照，对齐 V26/V27 格式）

## 2. 写路径键级 ACL（服务层）

- [x] 2.1 `DynamicConfigAccessGuards` 新增 `requireKeyWriteAccess(userResolver, def)`：守卫顺序 = 身份解析（未登录 96003）→ 目标键定级（tierOf）→ 非 OPERATIONAL 档且非超管 → FORBIDDEN 96005（requireSuperAdmin 既有语义零改动）；OPERATIONAL 档返回操作者放行（608 已由方法级注解在拦截器层强制）。updateValue / rollback / deleteOverride 三写路径换闸——**按目标键定级**（rollback/delete 同为值变更路径，与其目标键同档）
- [x] 2.2 读路径（listItems / getItem / history）与 refreshCache 撤除 service 层 requireSuperAdmin（鉴权由 3.1 方法级注解承接；JWTInterceptor 登录闸与用户状态闸前置维持，掩码/版本/审计/乐观锁语义零改动）
- [x] 2.3 单测（红锚转绿）：三档判定矩阵——运营档键：非超管（608 语境）写通过 / 超管通过；COST/STRUCTURAL 键：非超管 96005 / 超管通过；未登录 96003；未知键 96007（与读路径 requireDefinition 语义一致）；既有钉死 requireSuperAdmin 语义的服务/控制器单测随 ACL 语义同步调整（超管全通过保留、运营档放行新增、高档键拒绝保留）

## 3. 端点注解与覆盖矩阵迁移

- [x] 3.1 `DynamicConfigAdminController` 7 端点全部挂方法级 `@RequirePermission(PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE)`（list / detail / history / update / rollback / delete / refreshCache）；类 Javadoc 权限说明同步（「仅超级管理员可访问」→「608 持有者可读全部 + 可写运营档键；成本/结构档键超管专写由服务层定级闸强制」）
- [x] 3.2 覆盖矩阵档位迁移：`OpenEndpointRegistry` 7 条 /platform/config INTENTIONAL_OPEN 登记（现 L130-171，理由「服务层已强制 roleId=1」随服务层读闸撤除而失效）移除 → 端点转 SECURED；`PermissionCoverageComparatorTest` / `PermissionCoverageScannerTest` 同步（CONTROLLER_REGISTRY 已登记 DynamicConfigAdminController，不动）；`PermissionCoverageAuditIT` 三选一门禁保持全绿
- [x] 3.3 IT（Testcontainers MySQL，DASHSCOPE_API_KEY 置空，**@BeforeEach 自足清理**——P-ac 教训②）：V30 种子实测（业务角色 role_permissions 含 608、roleId 0/1/2 不含）；7 端点正反权限（业务角色持 608：读 3 端点成功 + 运营档键写成功 + cache/refresh 成功；无 608 角色：403；COST 键与 STRUCTURAL 键：非超管 96005 / 超管成功）；冻结/离职状态闸实测（12006/12007 前置语义保持）

## 4. 文档与台账

- [x] 4.1 docs/dynamic-config-keys.md：全部键表加「权限档位」列（OPERATIONAL=608 可写 / COST、STRUCTURAL=超管专写）+ 新章节「键级权限分层（tier ACL）」（608 与超管语义、COST 22 键与 STRUCTURAL 7 键全清单、新增键定级维护约定；顶部维护约定行同步）
- [x] 4.2 contracts-frozen.md §10 权限短语受控解冻更新：「仅超管可写」→「运营档键 608 可写、成本/结构档键超管专写（add-dynamic-config-key-tier-acl，owner 2026-10-09 拍板解冻）」；**接口冻结面本体零改动**（get(key, type, default) 签名 / 命名空间 / 热生效 / 校验护栏 / 版本回滚 / 审计逐字保留）
- [x] 4.3 权限种子台账盘点（docs/permission-matrix-audit.md 档位分布为时点报告不追改，如无同步项记「无」；其他文档按仓库惯例盘点，无则记「无」）

## 5. 门禁与基线（merge 前全绿留 raw）

- [x] 5.1 mvn -B -ntp test（DASHSCOPE_API_KEY 置空）：0 失败 0 跳过，surefire 总数 1048 → N（N>1048，红测试全转绿）；数字偏离立即停步回报
- [x] 5.2 四静态 0 新增违规：checkstyle / spotbugs / pmd:check / spotless:check（pmd 台账只许下调，scripts/tests/pmd-baseline-check.sh 核验）
- [x] 5.3 守卫：check-dirty CLEAN；check-line-endings lf <本卡写集文件全清单>；check-write-set a109e51 写集恰 = 本卡写集清单（frontend/ 零触碰）
- [x] 5.4 bash scripts/check-test-baseline.sh --update（必须来自 5.1 同一次真实运行；surefire 只增、failsafe 不降 24/83/6）后再跑一次不带 --update 确认通过
- [x] 5.5 三套自测（agent-helper / check-test-baseline / merge-gate）独立 raw + bash scripts/merge-gate.sh 全绿（含 [frontend-unit]）
- [x] 5.6 Docker 实测（docker info 先测，别假定）：在线必真跑 Testcontainers IT（含新 `DynamicConfigTierAclIT` 真全绿，`PermissionCoverageAuditIT` 纯 JVM 常跑）；不可用按 runbook §6 明列不掩瞒，且停步回报显式区分「本地全绿」与「CI 未验证」（P-ac 教训①）
- [x] 5.7 benchmark 零触碰确认：RagRealRetrievalBenchmarkIT 与金标 fixtures 不改、SUITE_VERSION 不动（本卡不触检索链路）

## 6. 提交分组与合并停步

- [x] 6.1 提交按任务组分组（feat 4-5 笔：常量与定级 + census 测试 / V30 种子（或并入前笔）/ ACL 与端点注解 + 矩阵迁移 / 单测与 IT / 文档），中文提交信息，新代码中文 Javadoc 标注「add-dynamic-config-key-tier-acl 任务 x.x」
- [x] 6.2 笔 N docs(openspec)：本卡 tasks.md 1.x-5.x / 6.1-6.3 勾选 + §0 填齐
- [x] 6.3 切 master → merge --no-ff（分支保留）→ git status 双确认 CLEAN
- [x] 6.4 master 收口笔：§0 回填合并节点 hash + 6.3 勾选
- [x] 6.5 严格停步回报（绝对禁止 git push）
      补勾依据（2026-10-10 P-ag 逐格核验）：主 agent 接管实施完成停步回报，复核 agent 终审通过，见 logbook §69.4 与 §69.5；未私自 push，合并节点 57253ef。
      预注册（本卡 spec-delta 契约 6）：本格与 §7 复核区为终态未勾，回补载体为 owner 指定的后续 master 前向提交，带此注记的未勾格不构成悬空。

## 7. 复核（复核 agent，只读；按 spec-delta 契约 6，本区勾选由后续回补笔处理，本卡内维持未勾）

- [x] 7.1 拓扑：合并节点双亲 = a109e51 + feature 顶端；真 --no-ff；分支保留
      补勾依据（2026-10-10 P-ag 逐格核验）：git log --format="%H %P" -1 57253ef 证实双亲 a109e51c2592257d997970d15f1d2ef5e5977acf 与 7614b0d3f6565a648c2a70bbd1ce7bf5d615ebc6；git branch --list 证实 feature/add-dynamic-config-key-tier-acl 保留。
- [x] 7.2 写集逐文件 = 任务组清单；冻结面零触碰（DynamicConfigService.get 接口签名 / 命名空间 / V1-V29 迁移 / P-ac 4 端点与 12 键白名单 / OpenAPI 既有路径 / 掩码与版本审计语义）；contracts-frozen.md 恰 §10 一处短语更新；frontend/ 零 diff
      补勾依据（2026-10-10 P-ag 逐格核验）：git diff --stat a109e51..7614b0d 证实写集逐文件吻合清单，冻结面零触碰，contracts-frozen 仅一处更新，frontend/ 零 diff，见 logbook §69.5。
- [x] 7.3 定级表 63 键逐键对照 Registry 实测普查核验；三档 ACL 判定矩阵独立复跑；红测试先行留证核验（work/_pad-red-first/ 真红）
      补勾依据（2026-10-10 P-ag 逐格核验）：63 键定级表普查全覆盖，三档 ACL 判定矩阵测试独立复跑全绿，work/_pad-red-first/ 真红在位，复核确认，见 logbook §69.5。
- [x] 7.4 门禁 raw 复核：surefire N 逐字 + 四静态 0 + 守卫 CLEAN + 台账更新恰来自真实运行（--update raw 与 mvn-test.raw 同源）+ benchmark 零触碰
      补勾依据（2026-10-10 P-ag 逐格核验）：surefire 1058 逐字全绿、四静态 0、三守卫 CLEAN、台账更新真实同源，CI 第 26 轮（Run 37926555423）全绿，见 logbook §69.6。
- [x] 7.5 §0 执行记录填齐且收口笔仅改本卡 tasks.md
      补勾依据（2026-10-10 P-ag 逐格核验）：§0 记录填齐，收口笔 7069b83 恰改 1 文件，git show --stat 7069b83 证实。
