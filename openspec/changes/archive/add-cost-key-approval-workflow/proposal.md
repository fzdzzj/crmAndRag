# 提案：成本键申请-审批流（add-cost-key-approval-workflow，卡 P-ae）

## 背景与动机

P-ad 落地键级三档 ACL 后，COST 22 键（LLM 费用红线族：rerank.mode=llm / compressor.mode=llm / multi-query / hyde / replay 等）为超管专写——运营侧想开任一成本开关只能找 owner 手工改，**零申请留痕、零审批链、零到期回收**。本卡把「手工拍板」系统化为可审计的申请-审批流，补齐企业三档分层最后一块治理拼图（一档 per-KB 热调 P-ac → 二档键级 ACL P-ad → 二档半成本键审批流本卡）。

## owner 四点拍板照录（2026-10-09）

1. **审批人**：仅超管（roleId=1，服务层超管闸镜像 P-ad 前的 requireSuperAdmin 模式，零新权限号、零种子授权）；
2. **生效方式**：审批通过即以**审批人身份**自动写入（走既有 DynamicConfigAdminService 写路径全语义：requireKeyWriteAccess 超管过 COST 闸 → validate → 乐观锁版本+1 → history+审计 → cache.invalidate），无过期语义、无两步走变更单；
3. **键范围**：仅 COST 22 键（白名单 = `ConfigKeyTierPolicy.COST_KEYS` 封闭集，零新白名单，census 同源锁定）；
4. **申请人**：608 持有者（复用 `PLATFORM_DYNAMIC_CONFIG_MANAGE`：写运营档键直写、写成本档键转申请——权限语义自洽）。

## 变更内容

- **V31 新表** `cost_key_change_request`（申请单：config_key / requested_value / reason / status / requester_id / approver_id / reject_reason / created_at / decided_at / applied_config_version；索引 status+created_at、config_key）；
- **实体 + Mapper + Service + Controller**（platform/config 包，镜像 SalesStageApproval 范式）；
- **5 端点**（全部挂 608 方法级注解；审批/驳回的服务层超管闸）：
  1. `POST /platform/config/cost-requests`——提交申请（键 ∈ COST_KEYS 且在 Registry 注册；值预校验同写路径 validate 语义但**不写入**；一键一单在途）；
  2. `GET /platform/config/cost-requests`——申请清单（分页；超管看全部、普通 608 只看自己）；
  3. `POST /platform/config/cost-requests/{id}/withdraw`——撤回（仅本人 PENDING 态）；
  4. `POST /platform/config/cost-requests/{id}/approve`——审批（仅超管；PENDING → APPROVED；以审批人身份调既有写路径；写入失败申请单留 PENDING 不落半状态）；
  5. `POST /platform/config/cost-requests/{id}/reject`——驳回（仅超管；PENDING → REJECTED + 理由）；
- **状态机**：PENDING → APPROVED（已生效）/ REJECTED / WITHDRAWN，终态不可变；
- **审计锚**：applied_config_version 回填写入后版本号 + 既有 history/审计全留痕；
- **矩阵登记**：新 controller 登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`（AGENTS.md 门禁要求）；
- **文档**：docs/dynamic-config-keys.md tier ACL 专节追加「成本键申请-审批流」子节。

## 不做什么

- **不动 STRUCTURAL 7 键**（chunking.strategy 等走迁移脚本变更管理惯例，不进运行时审批）；
- **不动 OPERATIONAL 34 键**（608 直写语义零变化）；
- **不做超管审批专权下放**（不新增 609/成本线角色——未来需要时另立卡）；
- **不做一次性变更单/过期/定时扫描**（通过即写入，无 EXPIRED 态）；
- **不动既有 7 端点**（/platform/config/items 系列 OpenAPI 既有路径、掩码/版本/审计语义零改动）；
- **不动 V1-V30 迁移、ConfigKeyDefinition record、63 键 def() 登记行、DynamicConfigService 接口**（冻结面零触碰）；
- **不碰 frontend/**（管理台 UI 后续卡）；**benchmark 零触碰**；**零真实外呼**（纯代码卡）。

## 影响面

- 写集预估 ≤ 18 文件（V31 / 实体 / Mapper / Service+Impl / Controller / DTO-VO 4 / 测试 3-4 / OpenEndpointRegistry 登记两处 / 文档 1 / 三件套 / test-baseline.txt）；
- 基线锚：surefire 1058 只增不减、failsafe 25/87/6 不降、merge-gate 8 PASS、CI 第 26 轮全绿（Run 37926555423）；
- Flyway 下一可用号 V31（实测 V30 已被 P-ad 占用）；权限常量 6xx 段止于 608（本卡零新增）。
