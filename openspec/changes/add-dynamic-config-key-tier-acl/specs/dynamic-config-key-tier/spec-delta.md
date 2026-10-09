# 增量契约规范：动态配置键级权限分层 ACL（add-dynamic-config-key-tier-acl）

### 契约 1：键级三档定级封闭表（Closed Tier Census）

- **GIVEN** `DynamicConfigKeyRegistry` 注册表全集（2026-10-09 实测普查恰 63 键）；
- **WHEN** 解析任一注册键的权限档位；
- **THEN** 档位 ∈ {OPERATIONAL, COST, STRUCTURAL} 且与 tasks.md 1.3 定级表逐键一致（OPERATIONAL 34 / COST 22 / STRUCTURAL 7）；表外注册键默认 OPERATIONAL，**新增注册键必须先在定级表登记**否则 census 防呆测试红；sensitive=true 防御性映射 COST；P-ac per-KB 12 键白名单全部 ∈ OPERATIONAL（两写面分层一致性锚）；定级表是封闭集，改档需 owner 拍板并同步 docs/dynamic-config-keys.md。

### 契约 2：键级写权限语义（Tiered Write Authority）

- **GIVEN** 任一值写路径（updateValue / rollback / deleteOverride——rollback 与 delete 按目标键定级，与其目标键同档）；
- **WHEN** 目标键 OPERATIONAL → 608 持有者可写（方法级注解强制）、超管直通；目标键 COST/STRUCTURAL → **维持超管专写**（服务层超管闸 96005，语义与升级前一致）；
- **THEN** 未登录 96003、未知键 96007、越权 96005 且零变更；守卫顺序 = 身份解析 → 目标键定级 → 高档超管闸；审计/版本/乐观锁/掩码语义零改动（608 操作者身份经既有 ConfigOperator.ref 自动入审计历史）。

### 契约 3：读端点放开语义（Read Opening）

- **GIVEN** 读端点 GET /items、GET /items/{key}、GET /items/{key}/history 与 POST /cache/refresh；
- **THEN** 全部挂方法级 @RequirePermission(608) 并撤服务层超管闸（读与缓存刷新不写任何键值，cache/refresh 按运营档处理）；用户状态闸（冻结 12006 / 离职 12007）在注解判空前前置的既有语义保持；服务层「超管硬闸」从 7 端点唯一鉴权面退位为「成本/结构档键写」的二级闸；敏感掩码/版本历史展示语义零改动。

### 契约 4：V30 种子与授权面（Seed and Grant Scope）

- **GIVEN** Flyway V30（实测 V29 已用，下一可用 V30；禁改 V1-V29 已合入脚本）；
- **THEN** permissions 新增 608 单行（PLATFORM_DYNAMIC_CONFIG_MANAGE）+ role_permissions CROSS JOIN 授权**全部业务角色**（role_id NOT IN (0,1,2) 且 is_deleted=0；超管拦截器直通不授）；对齐 V27 种子模式（挂注解与种植同轮）；回退 = 数据库快照（无 DROP 回滚）。

### 契约 5：覆盖矩阵档位迁移（Coverage Matrix Migration）

- **GIVEN** /platform/config 7 端点现登记 INTENTIONAL_OPEN（登记理由 = 服务层超管硬闸）；
- **THEN** 挂 608 注解后 7 条登记移除、端点转 SECURED；PermissionCoverageComparatorTest / PermissionCoverageScannerTest 同步；CONTROLLER_REGISTRY 已登记 DynamicConfigAdminController 不动；PermissionCoverageAuditIT 三选一门禁保持全绿（PENDING_DECISION 关闭状态不回退）。

### 契约 6：复核区与停步格回补惯例（承 P-ab / P-ac 同名契约）

- **GIVEN** 本卡 tasks.md §7 复核区与 6.5 停步格；
- **THEN** 勾选不在本卡内完成，卡片内预注册注记，回补载体为 owner 指定的后续 master 前向提交，带注记未勾格不构成悬空；三件套 tracked 入库、work/ 执行留痕不入库。

### 契约 7：基线与回归纪律（含 P-ac 三教训）

- **GIVEN** 本卡新增测试与台账更新；
- **THEN** 红测试先行（work/_pad-red-first/ 四类红证据：常量缺失 / 注解缺失 / 行为红 / 矩阵档位不符）记录后方可实现转绿；surefire 基线只增不减（1048 → N，N>1048），`scripts/test-baseline.txt` 更新必须来自一次真实运行（脚本 `--update`）且 failsafe 不降；**含新 IT 的卡 CI 全链验证前不得宣告闭环**（P-ac 教训①），新 IT 用例自足（@BeforeEach 清理 + 自备前置，教训②），raw 显式 UTF-8 无 BOM 写出（教训③）；RagRealRetrievalBenchmarkIT 与金标 fixtures 零触碰、SUITE_VERSION 不动。
