# 提案：schema 漂移定夺落地（7 项登记豁免 + 已定夺/新出现二分）

> 变更 ID：`drift-disposition` ｜ 能力域：`schema-integrity` ｜ 序列：`audit-entity-table-drift` 的定夺后续
> 来源：`docs/schema-drift-audit.md` 遗留 5 WARN（类型不亲和）+ 2 INFO（冗余列/有表无实体）待定夺；用户已拍板处置口径："登记豁免 + 文档化为主，避免为改类型动表结构"（2026-09-14）。

## Why

1. **7 项漂移悬而未决**：审计报告 §3/§4 标注"待定夺"，每轮审计都在重打同样的 WARN/INFO——定夺结论不落设施，"已定夺的漂移"与"新出现的漂移"永远混在一起，门禁失去信号价值。
2. **逐项查证后均可豁免**（见 What Changes 定夺表）：类型不亲和 5 项均为语义兼容/隐式转换现状可用；INFO 2 项一为生成列（实体不映射是正确设计）、一为治理域预留表（零业务引用）。
3. **权限域已有同构先例**：`audit-permission-matrix` 的 INTENTIONAL_OPEN 登记制（显式登记+理由+防新混入）已验证两次，移植到 schema 域零风险。

## What Changes

### 1. 七项定夺结论（拍板口径：登记豁免，不动表结构）

| 项 | 表.列 | 差异 | 定夺 | 理由 |
|---|---|---|---|---|
| W1 | `ai_chat_image.key_entities` | String↔json | 豁免 | MyBatis 以 String 承载 JSON 文本，语义兼容；长度/JSON 校验由应用层负责 |
| W2 | `data_share.resource_type` | String↔tinyint | 豁免 | 活象字符串 vs 整数编码，mapper 隐式转换现状可用；标注"改写 SQL 注意编码对应" |
| W3 | `stage_resource_binding.resource_type` | String↔tinyint | 豁免 | 同 W2 模式 |
| W4 | `sales_stage_approval.current_stage` | Integer↔varchar | 豁免 | MyBatis 隐式转换现状可用；数值语义稳定 |
| W5 | `sales_stage_approval.target_stage` | Integer↔varchar | 豁免 | 同 W4 |
| I1 | `customer_company.dept_unique_key` | 冗余列 | 豁免 | **生成列**（V1 L112 GENERATED ALWAYS AS … STORED，软删自动失效唯一键），实体不映射是正确设计 |
| I2 | `platform_token_budget` | 有表无实体 | 豁免 | V5 治理域**预留表**，主代码零引用；待治理域启用，删除属破坏性 DDL 不值当 |

### 2. 设施：已定夺漂移登记（KNOWN/NEW 二分）
- `SchemaDriftAuditor`（或新增 `KnownDriftRegistry` 常量类）登记 7 项：表.列 + 差异 + 理由 + 定夺日期，中文注释标注「drift-disposition 任务 x.x」；
- 审计输出区分：命中登记 → `KNOWN`（已定夺豁免，仅计数）；未命中 → `NEW`（新出现，待定夺，打印明细）；
- 门禁语义不变：CRITICAL fail 兜底，`SchemaDriftAuditIT` 全绿；新增单测覆盖登记命中/未命中/防呆（登记项不存在于实体与迁移链时报错）。

### 3. 文档收尾
- `docs/schema-drift-audit.md` §3/§4 改为"已定夺（2026-09-14）"并逐项标注豁免理由与登记指针；§5 待授权清单同步收敛（7 项移出）；
- `HANDOFF.md` / AGENTS.md 漂移章节更新：遗留定夺清零，新漂移走 NEW 登记流程。

## Impact

- **修改**：schema 审计设施（`src/test/java/com/slz/crm/integration/schema/`）+ 2 单测、`docs/schema-drift-audit.md`、`HANDOFF.md`、AGENTS.md。
- **不改**：任何实体类、任何迁移脚本（V1..V26 均不动）、`SchemaDriftAuditIT` 的 CRITICAL 门禁语义、表结构。
- **零行为变更**：纯测试设施与文档，运行期代码零改动。

## 风险

- **豁免漂移掩盖新问题**：登记按"表.列"精确匹配——同表同列的类型变化会重新落 NEW，不会误豁免；
- **登记与实际漂移脱节**：单测防呆（登记项必须真实存在）兜底。

## Non-Goals

- 不做任何类型对齐改造（改列类型/改实体字段——破坏性 DDL，超出豁免口径，将来有真实痛点再单独立项）；
- 不删 `dept_unique_key` 列、不删 `platform_token_budget` 表、不为预留表补实体；
- 不动 CRITICAL 门禁语义与迁移链。
