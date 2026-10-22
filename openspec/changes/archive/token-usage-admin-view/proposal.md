# 变更提案 · token-usage-admin-view（超管按用户查看 AI token 消耗）

## 1. 问题
`platform_token_usage`（`V5__governance.sql`）自平台治理域落地起就在记录每次 AI 调用的 `user_id_ref` / `usage_type` / `prompt_tokens` / `completion_tokens` / `total_tokens` / `model` / `success` / `create_time`，并已建 `(user_id_ref, create_time)` 索引——数据面完整。

但**至今没有任何读端点**：`PlatformTokenUsageMapper` 只被写入侧（`TokenUsageRecorderImpl`）与预算判定（`TokenBudgetService`）内部使用。结果是超管无法回答最基本的问题：**这周谁在烧 token、烧了多少、烧在哪个 usage_type 上**。计量的价值止于"记账"，没有"对账"。

## 2. 目标
1. 超管可见**按用户聚合**的 token 消耗：用户 × 时间区间，含 token 三口径、调用次数、失败次数、占比、最近一次调用时间，支持排序与分页。
2. 后端只读 API + 前端「AI 用量」页，超管专享（新权限 810）。
3. 页面同时呈现**调用次数**与 token 口径，显式标注"embedding / 视觉联合 embedding 端点不返 usage，token 恒 0"（`docs/token-metering.md` §6），避免把 0 读成免费。

## 3. 非目标
- 不做配额/预算语义变更（`TokenBudgetService`、`platform_token_budget` 不动）。
- 不改写入侧 `TokenUsageRecorderImpl`（含上一卡刚落的 `ai.token.record.calls` / `ai.token.usage.unavailable` 两指标）。
- 不做按部门/角色/租户聚合（`platform_token_usage` 无部门列，需 join 用户表；留作后续卡）。
- 不做导出、不做告警、不做跨库对账。
- 零契约解冻、零新依赖、零表结构变更。

## 4. 方案
**权限**：`PermissionOperates` AI 段（800–899）下一个可用号 **810** `AI_TOKEN_USAGE_VIEW("查看AI用量")`；`V43__ai_token_usage_permission_seed.sql` 只种权限行、**不授业务角色**——超管（roleId=0）走拦截器直通，等价超管专享，后续要放开给运营角色时再加授权迁移。

**查询**：`PlatformTokenUsageMapper` 增一条聚合查询（`GROUP BY user_id_ref`，`WHERE create_time >= start AND create_time < end`，可选 `usageType` 过滤），返回每用户 `prompt/completion/total` 求和、`COUNT(*)`、失败计数（`success = 0`）、`MAX(create_time)`；按 total 降序，分页 `LIMIT/OFFSET`。走既有 `(user_id_ref, create_time)` 索引。

**用户维度还原**：`user_id_ref` 前缀规则 `user:<id>` / `user:system` / `user:unknown`。返回体剥离 `user:` 前缀并批量回查用户名；`system` → 「系统」、`unknown` → 「未知来源」、查不到的 id → 「已删除用户(#id)」。

**接口**（只读，全部 `@RequirePermission(AI_TOKEN_USAGE_VIEW)`）：
- `GET /ai/token-usage/by-user`：`startTime` / `endTime`（缺省近 30 天，上限 366 天）/ `usageType`（可空）/ `pageNum` / `pageSize`（≤200）→ `{ total, records: [{ userRef, userId, userName, promptTokens, completionTokens, totalTokens, calls, failedCalls, lastCallTime, sharePct }] }`
- `GET /ai/token-usage/summary`：同区间总览（总 token、总调用、按 usage_type 分组的占比），用于页面头部卡片。

**门禁登记**：新 controller 必须同步登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`（漏登记 `PermissionCoverageAuditIT` 直接红）。

**前端**：`frontend/src/pages/(dashboard)/token-usage/` 新页（时间区间选择 + 头部总览 + 用户表格 + 分页），`pathTitleMap.ts` 增 `token-usage` 标题，`SideBarNav.vue` 照既有 `menuPermissionPrefixes` 约定加可见性（以"持有 810 才显示"为准则，具体名字前缀按现有 `extractPermissionNames` 输出对齐；超管拥有全部权限自然可见）。

## 5. 风险与取舍
| 风险 | 处置 |
|---|---|
| 页面把 embedding 明细的 0 token 读成免费 | 同列展示调用次数 + 页面注记 + 链接 `docs/token-metering.md` |
| 大区间聚合拖慢库 | 区间上限 366 天、分页上限 200、只走既有索引 |
| 历史行 `user_id_ref` 为 `unknown` | 展示「未知来源」，不静默丢弃 |
| 810 号段冲突 | 已实测 800–809、900 占用，**810 为下一个可用**；落卡后以 grep 复核 |
| 前端菜单超管专享语义与现有 system 组不同 | 以 810 权限名判定；若与 `menuPermissionPrefixes` 结构冲突，按"不影响既有菜单"优先，并在 tasks 补勾依据里留痕 |

## 6. 验收
见 `tasks.md`：权限迁移 + 只读 API + 前端页 + 单测/真 MySQL IT + 门禁全绿；A 档独立复核四判据（迁移 / 权限 / 真跑或真库 / 契约）。
