# 任务清单：add-per-kb-retrieval-strategy-override（卡 P-ac）

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线 / 合并节点：feature/add-per-kb-retrieval-strategy-override / 基线 master@9379420 / 合并节点 ________（双亲 = 9379420 + feature 顶端，master 收口笔回填）
- 红测试先行：实施前在基线 master@9379420 实跑本卡新测试贴红（红输出归档 work/_pac-red-first/）：V29 表不存在（KbRetrievalStrategyMigrationContractTest「缺少 V29__kb_retrieval_strategy.sql…存储层表不存在」）、端点 404（KnowledgeAdminStrategyEndpointTest 4 用例全 404 CLIENT_ERROR）、服务/白名单未实现（KbRetrievalStrategyService/Whitelist 编译期找不到符号）；见 red-migration.log / red-endpoint404.log / red-runtime.log
- 门禁 raw 留证位置：work/_pac-gate-raw/（mvn-test.raw / 四静态 / baseline / 三自测 / merge-gate）
- surefire 基线变化：1019 → 1048（只增 +29；test-baseline.txt 更新来自一次真实运行 `--update`，surefire.reports 172→178，failsafe 不降保持 24/83/6）
- 未跑项明列：Testcontainers 系 IT（KbRetrievalStrategyIT 等）因本机 Docker 不可用（`docker info` exit 1）整类 assumeTrue 优雅跳（B 组）；[it]/failsafe 未重跑（沿用 P-ab 时 24/83/6 基线，failsafe 不降）；真实模型外呼零涉及（DASHSCOPE_API_KEY 置空）

## 1. 存储层（V29 + 实体 + Mapper）

- [x] 1.1 红测试先行：先写本卡全部测试（任务组 1-5 的红锚），在基线实跑贴红归档 work/_pac-red-first/（V29 表不存在、服务未实现、端点 404 为预期红）
- [x] 1.2 Flyway V29__kb_retrieval_strategy.sql：`kb_retrieval_strategy`（id BIGINT auto_increment PK、kb_id BIGINT NOT NULL、strategy_key VARCHAR(128) NOT NULL、config_value VARCHAR(512) NOT NULL、version INT NOT NULL default 1、is_deleted TINYINT NOT NULL default 0、created_by/updated_by VARCHAR(100)、created_at/updated_at DATETIME；UNIQUE KEY uk_kb_strategy(kb_id, strategy_key, is_deleted 语义按 dynamic_config_item 软删模式对齐)）+ `kb_retrieval_strategy_history`（镜像 dynamic_config_history 列结构 + kb_id）；头部注释含用途/影响表/授权策略/回退说明（禁 DROP 回滚，回退=快照，对齐 V26 格式）
- [x] 1.3 实体 KbRetrievalStrategy / KbRetrievalStrategyHistory（pojo/entity，中文 Javadoc 标注「add-per-kb-retrieval-strategy-override 任务 1.3」）+ MyBatis-Plus Mapper 两枚
- [x] 1.4 SchemaDriftAuditIT 实测通过（新表 ↔ 实体零漂移；不动 KnownDriftRegistry）

## 2. 白名单与校验

- [x] 2.1 覆盖键白名单常量（恰 12 键，proposal §1 清单，一处定义）：`KbRetrievalStrategyWhitelist`（knowledge/retrieval），逐键复用 DynamicConfigKeyRegistry 的类型/范围校验语义（Integer/Double/Boolean/String 枚举各自范围，越界拒绝）
- [x] 2.2 单测：白名单外键拒绝（rerank.mode / chunking.strategy / vision-pdf.enabled 等至少 6 个反例）、类型不匹配拒绝、越界拒绝（红锚转绿）

## 3. 读端三层合并

- [x] 3.1 `KbRetrievalStrategyService`：resolve(kbId, key, type, globalValue)——覆盖合法则用覆盖，否则全局；缓存镜像 DynamicConfigCache（写后逐键失效、有界全量刷新兜底、旁路改库兜底）；覆盖值非法 = WARN 审计日志 + 回落全局（单测覆盖）
- [x] 3.2 `RetrievalConfigResolver` 增加重载：现有方法签名逐字不动（既有调用方零改动），新增带 nullable singleKbId 的重载族（resolveTopK/resolveMinScore/fusion 全套/rerank 权重与倍数/图文路由权重/neighbors/parent-expand/query-rewrite）；singleKbId 为 null 时行为与现方法逐字节一致
- [x] 3.3 `KnowledgeRetrievalServiceImpl` 单库判定：授权收敛后 kbScope 恰 1 库时取该库 id 传入重载（String→BIGINT 解析失败 → null 走全局）；多库/全库/空 → null。除该传参外主链路逻辑零改动
- [x] 3.4 单测：三层合并矩阵（覆盖>全局>默认、非法覆盖回落、多库走全局、单库走覆盖、kbId 解析失败走全局）；**零行为回归锚**：无覆盖配置时新旧解析输出逐字节一致（含 topK=5、minScore=0.20 全局锚，RetrievalParamTruthSourceTest 保持绿不改动）

## 4. 写端管理面（900 复用）

- [x] 4.1 `KnowledgeAdminController` 新增 4 端点（全部 @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)，方法级注解）：GET 策略清单（该库白名单全键生效值 + 来源标注 override/global/default）/ PUT 单键覆盖（白名单 + 校验拒绝）/ DELETE 单键覆盖（软删回落全局）/ POST 按版本回滚（history 取版本写回）；Service 层写前校验、version 递增、每写一行 history
- [x] 4.2 PermissionCoverageScanner 门禁实测通过（新端点三选一自动覆盖，CONTROLLER_REGISTRY 若需登记则同步）；OpenAPI 导出流程按仓库既有惯例同步（既有路径零变动）
- [x] 4.3 IT（Testcontainers MySQL）：表 DDL + 软删唯一约束实测 + 4 端点正反权限（900 通过 / 无权限 403）+ 回滚后生效值断言（DASHSCOPE_API_KEY 置空）

## 5. 文档与台账

- [x] 5.1 docs/dynamic-config-keys.md 新增「per-KB 覆盖」章节：12 键白名单、三层合并语义、单库作用域规则、热失效与非法回落、写端点与权限（维护约定行同步更新）
- [x] 5.2 权限种子零变动确认（900 已由 V27 种入，本卡不新增常量不新增种子）；CHANGELOG/其他文档按仓库惯例盘点（无则记「无」）

## 6. 门禁与基线（merge 前全绿留 raw）

- [x] 6.1 mvn -B -ntp test（DASHSCOPE_API_KEY 置空）：0 失败 0 跳过，surefire 总数 1019 → N（N>1019，红测试全转绿）；数字偏离立即停步回报
- [x] 6.2 四静态 0 新增违规：checkstyle / spotbugs / pmd:check / spotless:check（pmd 台账只许下调，scripts/tests/pmd-baseline-check.sh 核验）
- [x] 6.3 守卫：check-dirty CLEAN；check-line-endings lf <本卡写集文件全清单>；check-write-set 9379420 写集恰 = 本卡写集清单（src/test/迁移/docs，frontend/ 零触碰）
- [x] 6.4 bash scripts/check-test-baseline.sh --update（必须来自 6.1 同一次真实运行；surefire 只增、failsafe 不降）后再跑一次不带 --update 确认通过
- [x] 6.5 三套自测（agent-helper / check-test-baseline / merge-gate）独立 raw + bash scripts/merge-gate.sh 全绿（含 [frontend-unit]；Testcontainers 系列属 failsafe，本地 Docker 可用则跑、不可用按 runbook §6 明列不掩瞒）
- [x] 6.6 benchmark 零触碰确认：RagRealRetrievalBenchmarkIT 与金标 fixtures 不改、SUITE_VERSION 不动（零覆盖路径逐字节一致由 3.4 锚保证）

## 7. 合并与停步

- [x] 7.1 提交按任务组分组（feat/4-5 笔：存储层 / 白名单与读端 / 写端 / 文档与台账 / 基线台账更新），中文提交信息，新代码中文 Javadoc 标注「add-per-kb-retrieval-strategy-override 任务 x.x」
- [x] 7.2 笔 N docs(openspec)：本卡 tasks.md 1.x-6.x/7.1-7.2 勾选 + §0 填齐
- [ ] 7.3 切 master → merge --no-ff（分支保留）→ git status 双确认 CLEAN
- [ ] 7.4 master 收口笔：§0 回填合并节点 hash + 7.3 勾选
- [ ] 7.5 严格停步回报（绝对禁止 git push）
      预注册（本卡 spec-delta 契约 6）：本格与 §8 复核区为终态未勾，回补载体为 owner 指定的后续 master 前向提交，带此注记的未勾格不构成悬空。

## 8. 复核（复核 agent，只读；按 spec-delta 契约 6，本区勾选由后续回补笔处理，本卡内维持未勾）

- [ ] 8.1 拓扑：合并节点双亲 = 9379420 + feature 顶端；真 --no-ff；分支保留
- [ ] 8.2 写集逐文件 = 任务组清单；冻结面零触碰（DynamicConfigService 接口 / RetrievalQuery / 既有 7 端点 / OpenAPI 既有路径 / V1-V28 迁移）；frontend/ 零 diff
- [ ] 8.3 白名单恰 12 键与 proposal 一致；三层合并与单库作用域逐测试核验；零行为回归锚（无覆盖逐字节一致）独立复跑；红测试先行留证核验（work/_pac-red-first/ 真红）
- [ ] 8.4 门禁 raw 复核：surefire N 逐字 + 四静态 0 + 守卫 CLEAN + 台账更新恰来自真实运行（--update raw 与 mvn-test.raw 同源）+ benchmark 零触碰
- [ ] 8.5 §0 执行记录填齐且收口笔仅改本卡 tasks.md
