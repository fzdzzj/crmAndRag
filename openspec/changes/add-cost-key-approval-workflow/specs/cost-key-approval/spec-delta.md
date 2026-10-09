# Spec Delta：成本键申请-审批流（add-cost-key-approval-workflow）

> 基线：master@7069b83（P-ad 已合入）。本卡为企业三档分层治理线收官卡（P-ac per-KB 热调 → P-ad 键级 ACL → 本卡成本键审批流）。

## 契约 1：COST 白名单封闭（零新白名单）

申请-审批流覆盖的键集 = `ConfigKeyTierPolicy.COST_KEYS` 恰 22 键封闭集，**零新白名单常量**。白名单外键（OPERATIONAL 34 / STRUCTURAL 7 / 未注册键）提交申请一律拒绝（未知键 96007 语义）。COST 键集未来增删随 P-ad 定级表联动，本卡不复制键清单。

## 契约 2：状态机四态封闭

`PENDING → APPROVED | REJECTED | WITHDRAWN`，四态封闭集。终态（APPROVED/REJECTED/WITHDRAWN）不可再转移，由服务层守卫 + 单测矩阵锁定。无 EXPIRED 态（owner 拍板：通过即写入，无过期语义）。

## 契约 3：审批写入原子性与身份

approve = 以**审批人身份**调用既有 DynamicConfigAdminService 写路径（requireKeyWriteAccess 超管过 COST 闸 → validate → 乐观锁版本+1 → history+审计 → cache.invalidate），全部语义复用零旁路。**写入失败则申请单保持 PENDING**（不落 APPROVED 半状态），异常向上抛审批人可见可重试可驳回。APPROVED 单回填 applied_config_version（写入后版本号）作为审计锚。

## 契约 4：一键一单在途

同一 config_key 存在 PENDING 态申请单时，新提交拒绝（避免同键多单审批歧义）。撤回/驳回/审批完结后同键可再申请。

## 契约 5：权限语义（零新权限号）

- 提交/查看/撤回 = `PLATFORM_DYNAMIC_CONFIG_MANAGE(608)` 方法级注解（608 持有者；清单超管看全部、普通 608 只看自己——角色判定用当前请求实时角色，不跨请求缓存）；
- 审批/驳回 = 服务层超管闸（roleId=1，非超管 96005，镜像 P-ad 前的 requireSuperAdmin 模式）；
- V31 **无权限种子**（本卡零新权限号、零角色授权变更）。

## 契约 6：冻结面零触碰

V1-V30 迁移 / ConfigKeyDefinition record 与 63 键 def() 登记行 / DynamicConfigService 接口签名 / 既有 7 端点（/platform/config/items 系列）OpenAPI 路径与掩码、版本、审计语义 / P-ac 4 端点与 12 键白名单 / P-ad 定级表与 ACL 守卫语义 / frontend/ 全部零改动。新增 5 端点为新面（仅追加不改既有）。

## 契约 7：基线与回补纪律

surefire 1058 只增不减；failsafe 25/87/6 不降；test-baseline --update 同源；四静态 0；benchmark 零触碰；新 controller 登记 PermissionCoverageScanner.CONTROLLER_REGISTRY（三选一门禁 SECURED 路线）。6.5 与 §7 复核区未勾格为终态未勾，回补载体为 owner 指定的后续 master 前向提交（沿 P-ac/P-ad 契约 6 惯例），不构成悬空。
