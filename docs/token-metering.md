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
