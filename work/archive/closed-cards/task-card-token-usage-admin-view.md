# 任务卡 · token-usage-admin-view（超管按用户查看 AI token 消耗）

## 目标（一句话）
超管能看到「谁在烧多少 token」：后端新增只读聚合 API（按用户 × 时间区间），前端新增「AI 用量」页；超管专享新权限 810。

## 现状锚点（已实测，勿再猜）
- 计量表已存在：`platform_token_usage`（`V5__governance.sql:6`）：`user_id_ref`（`user:<id>` / `user:system` / `user:unknown`）、`usage_type`、`prompt_tokens` / `completion_tokens` / `total_tokens`、`model`、`session_id`、`knowledge_base_id`、`success`、`create_time`；**已有索引 `(user_id_ref, create_time)`**，按用户聚合无需新索引。
- **零读端点**：`PlatformTokenUsageMapper`（`platform/mapper`）当前只有写侧与 `TokenBudgetService` 内部查询，无任何 controller 暴露 token 用量。
- 权限段位：`PermissionOperates` 800–899 为 AI 段，已用 800–809、900；**下一个可用 = 810**。
- 迁移号：当前最高 `V42__sales_lead.sql` → **下一可用 V43**（实测 `ls src/main/resources/db/migration`）。
- 已知口径限制：DashScope embedding / 视觉联合 embedding **端点不返 usage**，这两类明细 token 恒 0（`docs/token-metering.md` §6）——页面必须同时展示**调用次数**，否则会被读成「免费」。

## 硬约束（违反即退回）
1. **零新增依赖**；零契约解冻（`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 不动）。
2. 迁移**只新增** V43 一条权限种子，禁改已合入脚本；不建表、不加列、不加索引（现有索引够用）。
3. 权限：新常量 `AI_TOKEN_USAGE_VIEW(810L, "查看AI用量")`；controller 每个端点挂 `@RequirePermission`；**新 controller 必须登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`**，否则 `PermissionCoverageAuditIT` 直接红。
4. 只读：所有查询参数化（禁拼 SQL）、分页上限 ≤200、时间区间上限 ≤366 天、不做导出/写操作。
5. 前端：仅新增页面与菜单项，改 `frontend/typed-router.d.ts` 之外的生成物一律不提交；`pnpm lint:check` + `pnpm type-check:check` 必须绿。
6. **严禁在观察树（`D:\code\crmAndRag`）产生任何文件**（含空文件），全部改动只在权威树分支内。

## 交付面（写集）
- 后端：`PermissionOperates`（+1 常量）、`V43__ai_token_usage_permission_seed.sql`、`PlatformTokenUsageMapper`（聚合查询）、新 DTO/VO、新 `AiTokenUsageController`、`PermissionCoverageScanner.CONTROLLER_REGISTRY`（+1 登记）。
- 前端：`frontend/src/pages/(dashboard)/token-usage/` 页面 + `pathTitleMap.ts` 菜单项 + `SideBarNav.vue` 可见性（照既有 `menuPermissionPrefixes` 约定；超管可见为准，取值口径写进 tasks）。
- 文档：`docs/token-metering.md` 增一节「超管按用户查看」；`docs/dynamic-config-keys.md` 不动（零新配置键）。

## 验收（自证）
- `mvn -B -ntp clean verify` BUILD SUCCESS；`bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0；`bash scripts/merge-gate.sh` 全绿。
- 单测 + 真 MySQL IT：种 2 用户 + system 明细 → 聚合值/排序/分页/区间断言；非超管 12002 反向、授 810 角色正向。
- 前端：`pnpm lint:check`、`pnpm type-check:check`、`pnpm build` 全绿。

## 停步条件
跑绿即停步：**不 merge、不 push**，回报分支名/commit 列表/门禁输出尾行/未解决项。
