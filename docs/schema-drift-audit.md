# Schema 漂移审计报告（实体 ↔ 真库）

> 来源：`openspec/changes/audit-entity-table-drift`（1.0，审计日 2026-09-13）｜ 门禁：`SchemaDriftAuditIT`
> 方法：真 MySQL（Testcontainers `mysql:8.0.36`，与 `FlywayMigrationIT` 同口径）+ Flyway 全链 V1..V25
> 比对：53 个实体（`pojo/entity` 38 + `knowledge/entity` 7 + `platform` 8）↔ 迁移链建出 55 张表
> 提取：实体侧 `SchemaDriftAuditor`（ClassPath 扫 `@TableName` + MyBatis-Plus `TableInfoHelper`，运行期同一套映射）；
> 库侧 `information_schema.columns`；比对 `SchemaDriftComparator`（纯函数）。

## 结论速览

- **CRITICAL = 0**（首轮发现 1 处，已由 V25 根修清零）
- **WARN = 5**（类型不亲和，只记录不处理，见 §3）
- **INFO = 2**（冗余列 / 有表无实体，只记录不处理，见 §4）

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
- 53 实体登记于 `SchemaDriftAuditor.ENTITY_REGISTRY`，扫描数/集合不一致即抛错，新实体必须同步登记。

## 3. WARN（类型不亲和，只记录，待定夺）

| 表.列 | 实体类型 | 库类型 | 说明 |
|-------|---------|--------|------|
| `ai_chat_image.key_entities` | String | json | 实体存 JSON 文本字符串，库为 json 类型——语义兼容，长度/JSON 校验差异待定夺 |
| `data_share.resource_type` | String | tinyint | 实体活象字符串（权限资源类型名），库为整数编码 |
| `sales_stage_approval.current_stage` | Integer | varchar | 阶段值实体为整数，库存字符串 |
| `sales_stage_approval.target_stage` | Integer | varchar | 同上 |
| `tage_resource_binding.resource_type` | String | tinyint | 同 `data_share.resource_type` 模式 |

> 类型不亲和未必是缺陷：可能是有意以字符串承载编码、或实体/库二择其一设计未对齐。属需单独授权的改造范围，
> 本次按提案边界**只记录不处理**。

## 4. INFO（冗余列 / 有表无实体，只记录）

| 表 | 差异 | 说明 |
|----|------|------|
| `customer_company.dept_unique_key` | 冗余列 | 库侧存在、实体未映射（疑似预留/废弃字段） |
| `platform_token_budget` | 有表无实体 | V5 建表，当前无 `@TableName` 实体映射 |

> `flyway_schema_history` 已豁免。以上处理需单独授权。

## 5. 待授权清单（超出 additive 预授权，未处理）

1. **WARN 5 项**的类型对齐（改库表列类型 / 改实体字段类型——均需回填或破坏性 DDL，禁在不授权下执行）。
2. **INFO 2 项**：`customer_company.dept_unique_key` 是否删除或补实体映射；`platform_token_budget` 是否补实体。
3. 若有需要 NOT NULL 收紧、删列、改类型、表重建的 CRITICAL（本次无），同样先停下授权。

## 6. 落地物

- 审计设施：`src/test/java/com/slz/crm/integration/schema/`（`SchemaDriftAuditor` / `SchemaDriftComparator` / 领域记录 + 2 单测）
- 门禁 IT：`SchemaDriftAuditIT`
- 迁移：`V25__invoice_info_add_remark.sql`（并登记 `FlywayMigrationIT.EXPECTED_VERSIONS` 至 25）
- surefire 基线：590 → **602**（新增 12：审计器 5 + 比对器 7）