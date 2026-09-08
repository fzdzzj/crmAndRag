# 数据库表协调方案（CRM × RAG）

> 回应评审问题"两套系统的表是否做好协调"。结论：**此前只做了地基（统一 MyBatis-Plus / Flyway / 归属边界 / userId 映射），表级约定未协调**。本文件基于对两边实体的代码核实，给出协调决策与归属矩阵，作为 Flyway `V1__baseline.sql` 与后续迁移的依据。
> 图例：✅ 已定 · 🔧 需适配 · ❓ 待用户确认

## 一、现状差异（代码证据）

| 维度 | CRM | RAG | 证据 |
|------|-----|-----|------|
| 表名 | 单数（`contract`/`project_file`/`ai_message`/`sys_dept`/`user`） | 复数（`chat_messages`/`chat_conversations`/`uploaded_files`/`batch_tasks`/`batch_file_results`/`chunk_upload_sessions`/`document_vector_chunks`） | `@TableName` vs `@Table(name=...)` |
| 审计列 | `create_time`/`update_time` | `created_time`/`updated_time` | 实体字段 |
| 软删除 | `is_deleted`，类型**不统一**（`CustomerCompanyEntity` Boolean / `ContractOrderItemEntity` Integer），无 `@TableLogic`（`DataScopeResolverImpl` 手写 `is_deleted = false`） | `deleted`(boolean) + `deleted_by`(String，**存 username**) | `KnowledgeBaseEntity.deleted` / `UploadedFileEntity.deletedBy` |
| 用户引用 | `user.id` = Long(bigint) | `owner_user_id`/`user_id` = String，长度**不一致**（`BatchTask`/`ChunkUploadSession` 64 vs `KnowledgeBase`/`Member` 100） | 实体 `@Column(length=...)` |
| 主键 | `@TableId(IdType.AUTO)` Long + Long 外键 | `@GeneratedValue(IDENTITY)` Long 代理键 + String 业务键（documentId/conversationId/taskId/upload_session_id） | 实体注解 |
| 时间类型 | 多 `LocalDateTime`，`ContactTaskEntity` 用 `java.util.Date` | timestamp | 实体字段 |
| 会话/消息 | `ai_session`/`ai_message`（助手业务会话） | `chat_conversations`/`chat_messages`（知识库问答会话） | 语义重叠 |
| 文件 | `project_file`/`approval_attachment`（本地 `slz.file.path`） | `uploaded_files`（MinIO） | 两套存储 |

## 二、协调决策（C1–C10）

### C1 表命名：统一到单数 snake_case（✅ 已确认，对齐 CRM 基座）
- **统一约定**：全部表单数 snake_case（对齐 CRM 基座，改动面最小——只改 RAG 约 9 张表）。
- **RAG 复数表重命名**：`chat_messages→chat_message`、`chat_conversations→chat_conversation`、`uploaded_files→uploaded_file`、`document_vector_chunks→document_vector_chunk`、`chunk_upload_sessions→chunk_upload_session`、`batch_tasks→batch_task`、`batch_file_results→batch_file_result`、`knowledge_bases→knowledge_base`、`knowledge_base_members→knowledge_base_member`。
- 实体 `@TableName` 用新单数名；Flyway `V1__baseline.sql` 直接以统一名建表。
- 存量数据：融合为新平台，若需保留 RAG 存量数据，追加 `ALTER TABLE ... RENAME` 迁移（并入 V3x）。

### C2 审计列：统一 `create_time`/`update_time`（✅ 已确认，对齐 CRM 基座）
- **统一约定**：全部表审计列用 `create_time`/`update_time`（对齐 CRM）。
- **RAG 列重命名**：`created_time→create_time`、`updated_time→update_time`；实体 `@TableField` 同步。
- 定义平台审计基类（`createTime`/`updateTime` + MyBatis-Plus 自动填充），新旧表统一继承。
- 存量数据：若保留 RAG 存量，追加 `ALTER TABLE ... RENAME COLUMN`（并入 V3x）。

### C3 软删除：统一字段名 `is_deleted` + `@TableLogic`（✅ 已确认）
- **统一字段名**：全部软删除列统一为 `is_deleted`（对齐 CRM 基座）；RAG `deleted` → 重命名为 `is_deleted`。
- **统一类型**：`is_deleted` 统一 `Boolean`（修复 CRM `ContractOrderItemEntity` 的 `Integer` 离群值）。
- **统一机制**：全部改用 MyBatis-Plus `@TableLogic`，全局 `logic-delete-value=1 / logic-not-delete-value=0`（替换 CRM 手写 `is_deleted = false` 过滤）。
- **`deleted_by`**（RAG 存 username，与 D3/D8 冲突）→ 迁移为 CRM `user:<id>`（并入 V3x）。
- 迁移：新平台 Flyway V1 直接建 `is_deleted`；保留 RAG 存量则 V3x 追加 `deleted`→`is_deleted` RENAME COLUMN + CRM 类型校正。

### C4 用户引用：统一为 String(100) 的 `user:<id>`（🔧）
- RAG `owner_user_id`/`user_id` 长度**统一到 100**（修复 64/100 不一致），值形态 `user:<CRM user.id>`。
- CRM `user.id` 保持 Long；跨域引用**只走稳定 userId 字符串**，不建跨域数据库外键（对齐 D2"禁止跨域直接写对方表"）。
- 建立映射与完整性策略：用户软删除/交接（CRM `user_handover`）时，知识库归属随之转移或标记失效（逻辑处理，非 DB 级联）。

### C5 主键与引用风格：各域沿用（✅）
- CRM：Long 自增 PK + Long 外键。
- RAG：Long 自增代理 PK + String 业务键（documentId/conversationId/taskId）。
- 迁移到 MyBatis-Plus 后主键策略统一 `IdType.AUTO`；业务键唯一约束在 Flyway DDL 显式声明。

### C6 会话/消息语义重叠：保留两套（✅ 已确认，不合并）
- CRM `ai_session`/`ai_message` = 助手业务会话（工具调用、草稿确认、references）。
- RAG `chat_conversation`/`chat_message`（表名已按 C1 单数化）= 知识库问答会话（检索、思考模式、来源引用）。
- **不合并表**（字段/语义/生命周期不同），但**统一 Token 计量口径与 SSE 事件契约**（已在 ai-assistant / platform-governance 约定）。
- 若未来要"一个助手统一历史"，在应用层聚合两套会话，而非合表。

### C7 时间类型：新代码统一 `LocalDateTime`（🔧 低优先）
- 存量不强改；新表/新字段统一 `LocalDateTime`。
- 可选修复 `ContactTaskEntity` 的 `java.util.Date` → `LocalDateTime`（登记为技术债，非本期强制）。

### C8 文件/附件存储：本期并存，后续统一 MinIO（✅ 已确认，本期不迁）
- 现状：CRM 附件走本地 `slz.file.path`，RAG 走 MinIO。
- **本期并存**（CRM 本地 + RAG MinIO），**不迁** CRM 附件；登记为技术债，后续独立提案再统一到 MinIO（避免本期范围膨胀）。

### C9 数据权限 vs 知识库授权（表级）：双轨并行（🔧 明确边界）
- **CRM 业务表**：走 `MyDataPermissionHandler`（owner_id/creator_id + 部门 DEPT/DEPT_AND_CHILD 数据范围）。
- **RAG 知识库表**：走 KB 授权（`owner_user_id` + `knowledge_base_member`），**不叠加部门数据范围**（知识库按成员制，不按部门树）。
- **AI 工具查询**：业务数据按 `DataScope`，知识库按 KB 授权，二者**同时生效、各自独立**（对齐 org-data-scope 与 knowledge-rag 规范）。

### C10 归属矩阵 + Flyway 基线（🔧 交付物）
- 产出下方"表归属矩阵"作为 `V1__baseline.sql` 与迁移的依据。
- 每张表登记：域 / owner / 授权机制 / ID 策略 / 软删除 / 审计列 / 跨域引用。
- Flyway 号段见 agent-execution-plan §5；RAG 表 DDL 从 Hibernate 生成物固化（原 `ddl-auto`）。

## 三、表归属矩阵（协调基线）

### CRM 域（`com.slz.crm`，单数名 / `create_time,update_time` / `is_deleted` / Long PK·FK）
| 分组 | 表 |
|------|----|
| 业务 | customer_company, customer_contact, customer_contact_remark, customer_company_log, customer_merge_log, sales_opportunity, contract, contract_order_item, invoice_info, payment_record, business_activity, business_activity_contact, business_activity_user, contact_task, sales_stage_approval, project_file, approval_attachment |
| 组织/权限 | user, role, permissions, role_permissions, sys_dept(+leader_id), company_dept, company_group, data_share, tage_role_binding, tage_resource_binding, user_handover |
| AI 助手 | ai_session, ai_message, ai_pending_action, ai_tool_call_log, ai_insight(新, V4x) |

### 知识库域（`com.slz.crm.knowledge`，**统一后单数名** / **统一后 `create_time,update_time`** / **统一后 `is_deleted`**+`deleted_by` / Long PK + String 业务键 + String userId）
| 分组 | 表（统一后单数） | 协调动作 |
|------|----|----------|
| 知识库 | knowledge_base, knowledge_base_member | 表名单数化；deleted→is_deleted；owner_user_id/member.user_id → String(100) `user:<id>` |
| 文档/向量 | uploaded_file, document_vector_chunk, chunk_upload_session | 表名单数化；deleted→is_deleted；deleted_by username→userId；user_id 长度统一 100 |
| 批量任务 | batch_task, batch_file_result | 表名单数化；user_id 长度统一 100 |
| 问答会话 | chat_conversation, chat_message | 表名单数化；username→user_id（V3x）；保留，不与 ai_* 合并 |
| 认证（弃用） | teacher_account | 映射到 CRM user 后停用 |

### 平台治理/配置域（新增，`com.slz.crm.platform`，单数名 / `create_time,update_time` / Long PK）
| 表 | 号段 | 用途 |
|----|------|------|
| 配额、token 计量、生命周期事件、对账账本、审计事件 | V5x | 治理 |
| 动态配置项、配置版本历史 | V6x | 超管动态配置 |

## 四、协调项确认结果（✅ 全部已定）
1. **C1 表名**：统一单数 snake_case（RAG 复数表重命名）。✅
2. **C2 审计列**：统一 `create_time`/`update_time`（RAG `created_time` 重命名）。✅
3. **C3 软删除**：字段名统一 `is_deleted`（RAG `deleted` 重命名）+ 类型 Boolean + `@TableLogic`；`deleted_by`→userId。✅
4. **C6 会话表**：不合并，保留 CRM `ai_*` 与 RAG `chat_*` 两套，统一 Token/SSE 契约。✅
5. **C8 附件存储**：本期并存（CRM 本地 + RAG MinIO），不迁，登记技术债。✅

## 五、落地挂钩
- 规范：`specs/platform-fusion/spec-delta.md` 新增"数据库表协调统一约定"需求。
- 任务：`tasks.json` 任务 4 增补"表协调矩阵 + 约定统一"步骤；具体迁移并入 A 线（A1/A5）与 V3x。
- 迁移：C1(表名单数化 RENAME)、C2(审计列 RENAME COLUMN)、C3(deleted→is_deleted RENAME + 类型校正 + deleted_by→userId)、C4(user_id 长度)、C6(chat username→user_id) 并入 Flyway（新平台直接用统一名建 V1 基线；保留存量则 V3x 追加 RENAME）；新表约定从 V4x/V5x/V6x 起生效。
