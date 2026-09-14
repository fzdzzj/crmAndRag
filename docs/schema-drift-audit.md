# Schema 漂移审计报告（实体 ↔ 真库）

> 来源：`openspec/changes/audit-entity-table-drift`（1.0，审计日 2026-09-13）｜ 门禁：`SchemaDriftAuditIT`
> 方法：真 MySQL（Testcontainers `mysql:8.0.36`，与 `FlywayMigrationIT` 同口径）+ Flyway 全链 V1..V25
> 比对：53 个实体（`pojo/entity` 38 + `knowledge/entity` 7 + `platform` 8）↔ 迁移链建出 55 张表
> 提取：实体侧 `SchemaDriftAuditor`（ClassPath 扫 `@TableName` + MyBatis-Plus `TableInfoHelper`，运行期同一套映射）；
> 库侧 `information_schema.columns`；比对 `SchemaDriftComparator`（纯函数）。

## 结论速览

- **CRITICAL = 0**（首轮发现 1 处，已由 V25 根修清零）
- **KNOWN = 7**（5 WARN 类型不亲和 + 2 INFO，均已于 **2026-09-14 定夺豁免**，登记于 `KnownDriftRegistry`，见 §3/§4）
- **NEW = 0**（无新出现待定夺漂移）
- **WARN = 5**（类型不亲和，定夺豁免，见 §3）
- **INFO = 2**（冗余列 / 有表无实体，定夺豁免，见 §4）

## 1. 已修复的 CRITICAAL（根修，additive）

| 表 | 列 | 实体 | 根因 | 修复 |
|----|----|------|------|------|
| `invoice_info` | `remark` (text) | [InvoiceInfoEntity.java](file:///d:/code/crmAndRag/src/main/java/com/slz/crm/pojo/entity/InvoiceInfoEntity.java#L77-L81) | 实体有 `remark`（text）字段；`V1__baseline.sql` L526-548 的 `invoice_info` 建表无此列 | `V25__invoice_info_add_remark.sql`（`ADD COLUMN remark text NULL`） |

> 与 `approval_attachment.uploader_id`（V24）同根因：实体带列、迁移链建表无列 → 真库任意全字段查询
> `Unknown column`（对外 90004）。单测 H2 auto-table 按实体建表故长期不可见，仅真库暴露。

### 正确性自然校验
- `approval_attachment.uploader_id`（V24 已修）**不在 CRITICAL** ✅
- `sys_dept.leader_id`（V21 已加）不在 CRITICAL ✅（提案 3.3 误写为 `company_dept`，实际归属 `sys_dept`，已按实表落实）
- `document_vector_chunk.parent_chunk_id` / `chunk_role`（V23）不在 CRITICAL ✅
- `dynamic_config_item.sensitive`：实体经 `@TableField("`sensitive`")` 反引号包裹（MySQL 8.0 保留字），MP 列名带反引号——审计器已做反引号归一化，**不误报** ✅

## 2. 门禁

`SchemaDriftAuditIT`（failsafe `*IT.java`）：
- Docker `assumeTrue` 守卫，无 Docker 本机跳过、CI（ubuntu）真跑；
- **CRITICAL 非空即 fail**，逐项输出实体类/字段/目标表——今后"改实体忘配套迁移"在 CI 直接红；
- 53 实体登记于 `SchemaDriftAuditor.ENTITY_REGISTRY`，扫描数/集合不一致即抛错，新实体必须同步登记；
- **KNOWN/NEW 二分（drift-disposition）**：WARN/INFO 命中 `KnownDriftRegistry`（7 项，见 §3/§4）→ KNOWN 仅计数；
  未命中 → NEW 显式打印提醒定夺（不失败）；单测防呆 `unmatchedKnownDrifts` 保证登记项必须仍产出真实漂移——
  登记过期/写错即报错。

## 3. WARN（类型不亲和，**已定夺 2026-09-14 豁免**）

> 5 项均为语义兼容/隐式转换现状可用，豁免定夺，登记于 `src/test/java/com/slz/crm/integration/schema/KnownDriftRegistry.java`；
> 审计命中采 KNOWN 计数不再随访。

| 表.列 | 实体类型 | 库类型 | 豁免理由 |
|-------|---------|--------|---------|
| `ai_chat_image.key_entities` | String | json | MyBatis 以 String 承载 JSON 文本，语义兼容；长度/JSON 校验由应用层负责 |
| `data_share.resource_type` | String | tinyint | 活象字符串 vs 整数编码，mapper 隐式转换现状可用；改写 SQL 注意编码对应 |
| `sales_stage_approval.current_stage` | Integer | varchar | MyBatis 隐式转换现状可用；数值语义稳定 |
| `sales_stage_approval.target_stage` | Integer | varchar | 同 `sales_stage_approval.current_stage` |
| `tage_resource_binding.resource_type` | String | tinyint | 同 `data_share.resource_type` 模式 |

> 类型不亲和未必是缺陷：可能是有意以字符串承载编码、或实体/库二择其一设计未对齐。经逐项查证均为现状可用，
> 本次按拍板口径**登记豁免，不动表结构**（不做类型对齐改造——破坏性 DDL 超出豁免口径，将来有真实痛点再单独立项）。

## 4. INFO（冗余列 / 有表无实体，**已定夺 2026-09-14 豁免**）

| 表 | 差异 | 豁免理由 |
|----|------|---------|
| `customer_company.dept_unique_key` | 冗余列 | **生成列**（V1 L112 `GENERATED ALWAYS AS … STORED`，软删自动失效唯一键），实体不映射是正确设计 |
| `platform_token_budget` | 有表无实体 | V5 治理域**预留表**，主代码零引用；待治理域启用，删除属破坏性 DDL 不值当 |

> 两项与 WARN 5 项一并登记录入 `KnownDriftRegistry`（2026-09-14），`flyway_schema_history` 另行豁免。
> 不删 `dept_unique_key` 列、不删 `platform_token_budget` 表、不为预留表补实体。

## 5. 待定夺清单（drift-disposition 2026-09-14 后）

**7 项遗留漂移已全部定夺豁免、移出待办**（KNOWN=7 / NEW=0，见 §3/§4）。后续新出现的漂移走 **NEW 登记流程**：
- WARN/INFO 漂移若未命中 `KnownDriftRegistry`，审计输出列为 **NEW** 并打印提醒——须按 `drift-disposition` 契约定夺：**登记豁免**（修订 `KnownDriftRegistry`）或**处置**（先停授权），勿任其累积；
- CRITICAL 依旧先停下授权：若有 NOT NULL 收紧、删列、改类型、表重建需求（additive 预授权之外），同样先停下授权。

## 6. 落地物

- 审计设施：`src/test/java/com/slz/crm/integration/schema/`（`SchemaDriftAuditor` / `SchemaDriftComparator` / 领域记录 + 2 单测）
- 门禁 IT：`SchemaDriftAuditIT`
- 迁移：`V25__invoice_info_add_remark.sql`（并登记 `FlywayMigrationIT.EXPECTED_VERSIONS` 至 25）
- surefire 基线：590 → **602**（新增 12：审计器 5 + 比对器 7）
- **drift-disposition（2026-09-14）**：追加 `KnownDriftRegistry`（7 项已定夺豁免登记）+ `SchemaDriftComparator` KNOWN/NEW 二分与防呆 `unmatchedKnownDrifts` + `SchemaDriftAuditIT` KNOWN/NEW 计数输出 + `KnownDriftRegistryTest` 3 条单测；surefire 基线 602 → **618**（新增 3）。