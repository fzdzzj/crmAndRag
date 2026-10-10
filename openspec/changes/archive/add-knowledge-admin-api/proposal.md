# 提案：知识库管理 API（/knowledge 管理面，knowledge 域首批 HTTP 暴露）

> 变更 ID：`add-knowledge-admin-api` ｜ 能力域：`knowledge` ｜ 序列：前端方向二第一段（后端先行，前端配套提案 `add-knowledge-admin-frontend` 在 crm-front 仓）
> 来源：前端勘察（2026-09-17）——knowledge 模块零控制器，RAG 只能经 `/ai` 聊天间接消费，运营无法管理知识文档。
> 基线：master=`00323af`，surefire **653**。

## Why

knowledge 模块能力已齐备但无 HTTP 面：KB 与成员（`KnowledgeBaseEntity` / `KnowledgeBaseMemberEntity` / `KnowledgeBaseAuthorizationService`）、上传与摄取（`UploadedFileEntity` / `DocumentIngestionService`）、切分与向量化（`DocumentService` / `EmbeddingService`）、检索（`KnowledgeRetrievalServiceImpl`）、重建（`KnowledgeReingestRunner`）、批量任务（`BatchTaskEntity`）。前端管理台无从谈起，只能靠数据库与内部 runner。

本单只做**管理面暴露**，不动检索质量链与 `/ai` 管线。

## What Changes

### 1. `KnowledgeAdminController`（`/knowledge`，7 端点）

全部 `@RequirePermission`（新权限码），登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`（否则权限门禁红）：

| 端点 | 语义 | 复用 |
|---|---|---|
| `GET /knowledge/bases` | KB 列表（按当前用户经 `KnowledgeBaseAuthorizationService` 过滤） | 授权服务 |
| `GET /knowledge/files` | 文档分页（kbId 过滤） | 既有实体查询 |
| `POST /knowledge/files` | 上传摄取（MultipartFile + kbId） | `DocumentIngestionService` |
| `GET /knowledge/files/{id}` | 文档元数据（chunk 数、页数、状态、时间） | 既有实体 |
| `DELETE /knowledge/files/{id}` | 删除文档与向量 | 既有存储/向量店 |
| `POST /knowledge/files/{id}/reingest` | 单文档重建 | `KnowledgeReingestRunner` |
| `POST /knowledge/retrieval/test` | 检索 dry-run：返回候选 chunk 与分数 | 检索服务 |

### 2. 权限与迁移（V27）

- `PermissionOperates` 新增常量（数值不与既有冲突，执行时定夺并回报）；读写同权单码，不拆两码。
- `V27__knowledge_admin_permission_seed.sql`：注册权限 + 同轮 `INSERT...SELECT` 授权所有业务角色（`role_id NOT IN (0,1,2)`），格式对齐 `V26__permission_seed.sql`（头部注释含目的/受影响表/授权策略/回退说明）。
- 权限矩阵审计报告 `docs/permission-matrix-audit.md` 追加本控制器行。

### 3. 检索 dry-run 的成本边界

- 默认走**稀疏/BM25 路零外呼**；真向量检索需显式参数且属授权节点（任务组 5），与 `RAG_BENCHMARK_REAL` 同口径，不顺带跑 54 条基准。

### 4. 测试（¥0，mock embedding）

- 单测：service 编排（mock 依赖）、权限反向（无权限 403）。
- IT（Docker）：Testcontainers MySQL 全链，embedding 全 mock；上传/删除/重建/检索 dry-run 各至少 1 正 1 反。
- surefire 基线随实测只增不减（653→N）。

## Impact

- **新增**：controller、`KnowledgeAdminService`、pojo 的 dto/vo、V27、权限常量。
- **修改**：`PermissionCoverageScanner.CONTROLLER_REGISTRY`、`PermissionOperates`、HANDOFF、`docs/permission-matrix-audit.md`。
- **不改**：检索默认行为与质量链、`/ai` 管线、V1..V26、既有权限码、`DynamicConfigKeyRegistry.NAMESPACES`。

## 风险

- **真外呼**：上传摄取/重建会触发 embedding——测试一律 mock，生产操作由管理台按次触发并走 `TokenUsageRecorder` 计量。
- **门禁红**：新 controller 未登记 REGISTRY / 端点漏 `@RequirePermission`。
- **V27 冲突**：与 V26 权重或号段冲突——写前核对 `db/migration` 现状。

## Non-Goals

- 不做 KB 创建/成员管理 CRUD（首批只读 KB + 文档管理，KB 管理另立）。
- 不做质量基准、不跑 54 条、不改切分策略。
- 不新增 Maven 依赖。

## 失败场景

1. 权限覆盖门禁（`PermissionCoverageAuditIT`）或 schema 漂移门禁红。
2. 测试未 mock embedding 导致真外呼。
3. V27 校验失败（格式/号段/授权策略与 V26 不一致）。
