# 提案：动态配置键级权限分层 ACL（add-dynamic-config-key-tier-acl，卡 P-ad）

## 为什么

P-ac（执行文档 §68.3 闭环）交付企业三档分层第一缺口（per-KB 粒度）后，全局动态配置中心仍是「全端点超管专写」现状：`DynamicConfigAdminController` 7 端点全部无 `@RequirePermission`，由 `DynamicConfigAccessGuards.requireSuperAdmin`（roleId=1）在服务层硬闸（非超管 FORBIDDEN 96005，`OpenEndpointRegistry` 以「服务层已强制 roleId=1」为由登记 INTENTIONAL_OPEN）。后果：第一档运营调参键（topK / minScore / fusion.* / 熔断参数等 34 键）无法下放管理，业务角色连配置只读都没有，超管成为运营调参瓶颈；而成本/结构档键（22+7 键）确实不应下放。owner 2026-10-09 四点拍板（照录）：**权限号段 608、高等级键维持超管、读端点放开、全业务角色授权**——本卡补齐三缺口之二（键级权限分层 ACL；第三缺口成本键申请-审批流挂账）。

## 做什么

1. **权限常量与种子（608）**：`PermissionOperates` 新增 `PLATFORM_DYNAMIC_CONFIG_MANAGE(608L, "平台动态配置管理")`（6xx 权限管理段顺延，实测 601-607 在用、608 空缺）；Flyway **V30**（实测 V29 已用，下一可用 V30）按 V27 单码种子模式种 608 + CROSS JOIN 授权全部业务角色（role_id NOT IN (0,1,2)、is_deleted=0；超管拦截器直通不授；挂注解与种植同轮）。
2. **键级三档定级（封闭 63 键，零触碰既有 schema 面）**：新枚举 `ConfigKeyTier`（OPERATIONAL / COST / STRUCTURAL，platform/config 包）+ 定级登记 `ConfigKeyTierPolicy`（**不动** `ConfigKeyDefinition` record 与 Registry 63 个 def() 登记行）；OPERATIONAL 34 键（P-ac per-KB 12 键白名单全部在其中）/ COST 22 键（LLM 开关族 rerank.mode、compressor.mode、multi-query.*、hyde.*、derived-questions.*，VLM vision-pdf.*，管理端真向量检索 admin-vector.enabled，限流配额 rateLimit/quota，token-budget，重放 replay-*，模型选择 chatModel/visionModel）/ STRUCTURAL 7 键（chunking.*、chunkSize/chunkOverlap、embeddingModel、provider、dataScope 安全语义键）；sensitive=true 防御性映射 COST；census 防呆测试锁全集（新增注册键未定级即红）。
3. **写路径键级 ACL**：updateValue / rollback / deleteOverride 三写路径**按目标键定级**——OPERATIONAL → 608 持有者可写（方法级注解强制）、超管直通；COST/STRUCTURAL → **维持超管专写**（服务层闸 96005 语义与升级前一致）；读路径（list/get/history）与 cache/refresh 撤服务层超管闸，由方法级注解承接（JWT 登录闸、用户状态闸前置、掩码/版本/审计/乐观锁语义零改动；608 操作者身份经既有 ConfigOperator.ref 自动入审计历史）。
4. **端点注解与覆盖矩阵迁移**：7 端点全挂方法级 `@RequirePermission(608)`；`OpenEndpointRegistry` 7 条 INTENTIONAL_OPEN 登记（理由「服务层已强制 roleId=1」随服务层读闸撤除而失效）移除、端点转 SECURED + `PermissionCoverageComparatorTest` / `PermissionCoverageScannerTest` 同步（CONTROLLER_REGISTRY 已登记不动）；`PermissionCoverageAuditIT` 三选一门禁保持全绿（PENDING_DECISION 关闭状态不回退）。
5. **文档与契约记档**：docs/dynamic-config-keys.md 全部键表加「权限档位」列 + 「键级权限分层（tier ACL）」专节；contracts-frozen.md §10「仅超管可写」短语按 owner 四点拍板做受控解冻更新（接口冻结面本体零改动，行内注记解冻依据）。

## 不做什么

- 零接口变更：`DynamicConfigService.get(key, type, default)` 签名与命名空间、`DynamicConfigHistory` 审计语义、敏感掩码逻辑、版本/回滚/乐观锁、OpenAPI 既有路径全部不动（无新端点、无路径变更）。
- 不动 P-ac 面：per-KB 12 键白名单、4 个 900 端点、三层合并链路零触碰（per-KB 覆盖（900）与全局运营档（608）两写面分层互不越界，同一底层键的全局值与单库覆盖分表存储、三层合并既有语义不动）。
- 不做成本键申请-审批流（企业三档第二缺口挂账）；不做 608 之外的键级细粒度授权（608 为整档读写同权单码）；`ConfigItemView` 不加 tier 字段（管理端展示挂账）。
- 禁改 V1-V29 迁移（V30 起新建）；不动 `scripts/test-baseline.txt` 语义（surefire 只增 1048→N，更新必须来自一次真实运行）；不 push（推送归 owner）；真模型外呼零涉及（DASHSCOPE_API_KEY 全程置空）。

## 影响

- 运行面：全部业务角色（持 608）可读配置中心全部键 + 可写 34 运营档键；COST 22 / STRUCTURAL 7 维持超管专写；超管全量不变；冻结/离职状态闸不变。
- 台账面：docs/dynamic-config-keys.md tier 列与专节；覆盖矩阵 7 端点 INTENTIONAL_OPEN → SECURED；contracts-frozen.md §10 短语更新（行内注记解冻依据）。
- 挂账（后续候选，本卡不做）：成本键申请-审批流（企业三档第二缺口）、`ConfigItemView` tier 展示、P-ac tasks.md 7 格回补笔（owner 指定独立成笔，不搭本卡收口笔）。
