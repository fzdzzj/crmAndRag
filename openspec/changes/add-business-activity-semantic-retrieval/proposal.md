# add-business-activity-semantic-retrieval — 商业活动语义检索与权限过滤

## 背景与动机

- 在 `add-customer-semantic-retrieval` 与 `add-customer-contact-retrieval` 合入后，CRM 系统已实现针对「客户公司」与「联系人」的向量化语义检索与四档数据权限过滤；
- 但目前业务数据的语义检索仅覆盖客户单一实体。在实际销售场景中，**商业活动（`business_activity`）** 沉淀了大量极其宝贵的需求沟通要点、客户痛点反馈、产品演示记录与后续待办等非结构化富文本；
- 销售员经常向 AI 助手提出经验与跟进类问题，例如：
  - *“过去针对某某客户的现场沟通要点有哪些？”*
  - *“我们以前做过哪些关于工业智能制造的产品演示，客户关注的重点是什么？”*
- 本卡将「商业活动（`business_activity`）」接入向量化语义检索链路，在 Qdrant 的 `crm_entity` 集合中与客户实体并存，严格同锚既有 `SALES_VIEW_BUSINESS_ACTIVITY` 数据权限四档过滤，实现销售沟通经验的 RAG 智能化赋能。

## 变更内容

### 1. 契约受控解冻与多源标识

- 扩展 `SourceReference.sourceType` 语义：新增支持 `"BUSINESS_ACTIVITY"`（`documentId` 为活动主键，`filename` 为活动标题）；
- 保持 `SourceReference` 十字段 record 结构与 SSE `sources` 事件 payload 零变更；
- 产出卡内契约增量文档 `specs/knowledge-rag/spec-delta.md`，并在 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 追记解冻条目（对齐 B1 先例）。

### 2. 商业活动语义摄取服务（BusinessActivityIngestionService）

- **富文本拼装（`BusinessActivitySemanticTextSupport`）**：
  - 抽取活动标题、活动类型（线下拜访/线上会议/电话等）、关联客户名称、关联商机名称、**详细沟通内容（`activity_content`）**、备注、联系人与参与人；
  - 拼装为规范语义文本，调用 `EmbeddingService.embed(text)` 生成 1024 维向量。
- **Payload 权限与关联元数据打标**：
  - `entityType`: `"BUSINESS_ACTIVITY"`（固定值）
  - `resourceId`: 活动 ID
  - `ownerUserId`: 创建人 ID（`creator_id`）
  - `ownerDeptId`: 创建人所属部门 ID（反查 `sys_user.dept_id` 反范式缓存）
  - `companyId`: 关联客户公司 ID（可选）
  - `opportunityId`: 关联商机 ID（可选）
  - `title`: 活动标题
- **增量 Hook（`BusinessActivitySemanticHookSupport`）**：
  - 在 `BusinessActivityServiceImpl` 的新增、更新、删除操作中，通过 Spring 事务同步器（`afterCommit`）异步触发向量增量更新（`reingestById`）与删除。
- **存量回埋端点**：
  - 新增管理员全量回埋方法与端点（`POST /business/activity/reingestAll`），挂 `SALES_CREATE_BUSINESS_ACTIVITY` 权限保护。

### 3. 业务数据检索多实体融合（BusinessRetrievalService）

- **权限闸同锚**：
  - 调用 `dataScopeService.getHighestDataScopeLevel(user, "business_activity")`，严格执行：
    - `NONE`：零外发，直接返回空；
    - `SELF`：`ownerUserId == user.userId()`；
    - `DEPT`：`ownerDeptId == user.deptId()`；
    - `DEPT_AND_CHILD`：递归子部门树逐部门过滤并 union；
    - `ALL`：管理员无过滤；
    - TAGE 标签与显式共享授权补充召回超集不变量。
- **双实体多路召回与融合**：
  - 检索链路同时召回 `CUSTOMER_COMPANY` 与 `BUSINESS_ACTIVITY`，按向量相似度降序截断 TopK；
  - 格式化输出为 `【业务数据检索】`，统一注入 Prompt 模板 `{{#slot:retrievedContext}}` 槽位，引用角标 `[n]` 编号顺延。

## 不做什么

- 不新增任何数据库表或修改已有表结构（**零 Flyway 迁移脚本**）。
- 不新增任何权限码常量（复用 `SALES_VIEW_BUSINESS_ACTIVITY` 系列）。
- 不触碰 `frontend/` 目录。
- 零真跑模型外呼（单测与门禁全程 DASHSCOPE_API_KEY 置空）。

## 验收

1. 商业活动新增、更新、删除后，向量库自动增量同步，删除活动向量立即失效。
2. 权限隔离严格生效：无权用户或跨部门非归属人绝不能检索出他人的商业活动内容（单测反向用例断言锁定）。
3. 纯 JVM 单测覆盖摄取拼装、权限闸分支、多实体合并与降级保护。
4. `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1290）。
5. 四静态门禁（checkstyle / spotbugs / spotless / pmd）与聚合门禁 `merge-gate.sh` 退出码 0。

## 红线

- 零迁移（不碰 `db/migration`）、零新权限常量、零真跑外呼、零破坏性变更。
- 严禁未经授权执行 `git push`。
