# Token 计量口径（TOKEN-METERING-1.0）——调用次数已计 + token 不可得显式声明

> 卡：`multimodal-gray-and-eval-threshold` 任务组 3（embedding 计量诚实化）。
> 性质：`platform/token` 侧极小改动（**只加指标与 Javadoc**）+ `platform/model` Javadoc 留痕；**零迁移、零新端点、
> 零冻结契约改动**（`TokenUsageRecord` / `ModelCallResult` 见 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` §71：
> 「`TokenUsageRecord` **9 字段不改签名**」，本卡未解冻）。

## 1. 问题：embedding 的 token 恒 0，但 0 不是"零成本"

实测（`docs/rag-quality/production-gray-cost-v1.md` §4.2）：vision 9 次调用真实回流 9 600 token，而
**image embedding 14 次 / text embedding 32 次，token 全部为 0**。根因是端点客观限制，不是漏接：

| 调用点 | 代码位置 | 现象 |
|---|---|---|
| 文本 embedding | `ModelProviderImpl#embed`（L171-176 附近） | 取 `EmbeddingResponse.getMetadata().getUsage()`，DashScope 文本 embed **不返 usage** → `null` |
| 视觉 embedding | `ImageEmbeddingSupport#parseResponse`（L129-132） | 取 `usage.input_tokens` / `usage.total_tokens`，联合 embed 端点**不返** → `positiveOrNull` 得 `null` |

而落库表 `platform_token_usage` 的 `prompt_tokens` / `completion_tokens` / `total_tokens` 是
**`bigint NOT NULL DEFAULT 0`**（`V5__governance.sql`）。于是「未知」被 schema 归一成 **0**，账单看上去像"不花钱"。

## 2. 口径（本卡定稿）

1. **不伪造 token**：不按字符数估算、不按图片数折算、不补一个"看起来合理"的值。
2. **调用次数已计**：`TokenUsageRecorderImpl` 每收到一条计量记录就 `ai.token.record.calls{model,type,result}` +1
   ——**与 token 是否可得无关**，所以 embedding 的"花了多少次外呼"是真账。
3. **token 不可得显式声明**：三列 token 全为空的记录，另外 `ai.token.usage.unavailable{model,type}` +1。
4. **库内 0 读作"未知"**：不改表结构（零迁移），0 的语义由 §3 指标区分；任何按 `SUM(total_tokens)` 出成本账单的消费方，
   必须同时读 `ai.token.usage.unavailable` 才能判断该区间是否存在"未知"样本。

## 3. 指标（唯一读者 = `TokenUsageRecorderImpl`）

| 指标 | 标签 | 语义 |
|---|---|---|
| `ai.token.prompt` / `ai.token.completion` / `ai.token.total` | `model` `type` `result` | 既有 token 累加（不可得时按 0 累加，不改变既有看板数值） |
| `ai.token.record.calls` | `model` `type` `result` | **新增**：计量上报次数（调用次数口径） |
| `ai.token.usage.unavailable` | `model` `type` | **新增**：token 不可得声明次数 |
| `ai.token.record.failure` | `type` | 既有：落库失败（账单缺口告警位） |

**自洽校验**：对 embedding 类记录，`ai.token.usage.unavailable{type="embedding"}` 应与
`ai.token.record.calls{type="embedding",result="success"}` **同量增长**；两者背离说明端点行为变了（开始/停止回流 usage），
届时按实测重写本节而不改判据。

## 4. 为什么不碰冻结契约

`TokenUsageRecord` 加一个 `boolean usageAvailable` 字段是最直觉的做法，但：

- 它是**冻结契约**（`contracts-frozen.md` §71 明示 9 字段不改签名），改动要走受控解冻；
- 收益不足：`usageAvailable` 是**三列 token 的纯函数**（全空 ⇔ 不可得），在计量落库侧（`TokenUsageRecorderImpl`）
  同一判据即可推导，无需把信息搬进 record；
- 结论：**本卡不解冻**，判据落在 `TokenUsageRecorderImpl#usageAvailable`，由单测锁定。

## 5. 测试锁定

`TokenUsageRecorderImplTest` 两条新用例（零外呼）：

1. embedding 形态记录（三列 token 全 `null`）→ `ai.token.record.calls{type="embedding"}` = 1、
   `ai.token.usage.unavailable{type="embedding"}` = 1、落库三列仍为 schema 归一值 0；
2. 有 usage 的记录（CHAT 12/8）→ `calls` = 1、`usage.unavailable` **不存在**（`registry.find(...)` 为空）。

## 6. 未解决项

1. **token 级账单仍不可得**（端点限制）：若未来 DashScope 回流 embedding usage，`ModelProviderImpl#embed` 与
   `ImageEmbeddingSupport#parseResponse` 的取数代码已就位（无需改），只需重跑一次基线确认真回流。
2. **按次计价的换算系数不在本卡**（待决策）：把"调用次数"折成金额需要供应商单价，属业务定价口径，不应写进计量代码。

## 7. 超管按用户查看（token-usage-admin-view，权限 810）

超管侧读端点（`AiTokenUsageController`，只读、零写操作、零契约解冻）：

| 端点 | 口径 |
|---|---|
| `GET /ai/token-usage/by-user` | 按 `user_id_ref` 聚合：token 三口径、`calls`（含失败）、`failedCalls`（`success = 0`）、`lastCallTime`、`sharePct`；按总 token 降序分页 |
| `GET /ai/token-usage/summary` | 同区间总览：总 token / 总调用 / 失败 / 去重用户数 + 按 `usage_type` 分组占比 |

**参数与上限**：`startTime` / `endTime` 为 ISO-8601（`2026-09-01T00:00:00`，也接受空格分隔），
**两端必须同时提供或同时省略**（省略 = 近 30 天）；区间半开 `[start, end)`，上限 **366 天**，终点不得晚于当前时间；
`usageType` 可空（`chat`/`embedding`/`ocr`/`vision`/`summary`/`intent`，大小写不敏感）；
`pageNum ≥ 1`、`pageSize ∈ [1, 200]`。越界一律 VALIDATION(96007)，不新增错误码。

**权限**：`PermissionOperates.AI_TOKEN_USAGE_VIEW(810L, "查看AI用量")`，迁移
`V43__ai_token_usage_permission_seed.sql` 把 810 **只授权给 roleId=1（超级管理员角色）**——这行授权供前端
`getMyPermissionGrouped`（联查 `role_permissions`）读取，菜单 gate 才成立；鉴权本身对 roleId=1 本就走
`PermissionsInterceptor` 直通。业务角色零授权（口径同 V26/V27/V30/V33 的 `NOT IN (0,1,2)`），放开给运营角色需另起授权迁移。

**用户维度还原**：表内 `user_id_ref` 前缀规则 `user:<id>`；返回体剥离前缀并批量回查 `sys_user.real_name`，
`user:system` → 「系统」、`user:unknown` → 「未知来源」、查不到的 id → 「已删除用户(#id)」。`userRef` 原值保留供对账。

**零 token 的正确读法**：本页把 `calls` / `failedCalls` 与 token 并列展示，并显式标注 §1 / §6 的端点限制。
`totalTokens = 0` 的 embedding 行只说明"该端点没给 usage"，**不说明没有调用**；区间总量偏低时先看调用次数。
