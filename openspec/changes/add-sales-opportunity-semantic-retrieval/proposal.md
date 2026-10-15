# add-sales-opportunity-semantic-retrieval — 销售商机语义检索接入与异步增量保障

## 背景与动机

- 在 `add-customer-semantic-retrieval` 与 `add-business-activity-semantic-retrieval` 合入后，CRM 系统已实现针对「客户公司」与「商业跟进活动」的向量化语义检索与四档数据权限过滤；
- 但销售场景中另一个核心业务实体——**销售商机（`sales_opportunity`）** 沉淀了更为关键的商业决策信息：包括商机名称、商机阶段（种子/潜在/确认/立项等）、预估成交金额、预计结单日期、核心联系人、尤其是**详细需求与跟进要点描述（`description` 富文本）**；
- 销售人员与管理层常向 AI 助手提问：
  - *“近期针对某某客户我们有哪些处于立项阶段的商机，客户的核心技术痛点是什么？”*
  - *“过去半年预估金额在 50 万以上的项目中，有哪些与信创改造相关的商机记录？”*
- **用户核心指示与性能硬约束**：
  1. 尽量接入高价值业务实体（销售商机作为第三实体扩展）；
  2. **增量处理必须使用异步方式（Asynchronous）**：向量嵌入模型调用（RTT 往往达数百毫秒）与向量库写入绝对不能阻塞业务主线程，严禁影响 CRM 主业务接口（商机创建/更新/删除）的吞吐量与响应时延；
  3. 异常必须严格隔离，单条向量同步失败仅记 `WARN`，绝不回滚或阻断业务事务，由管理员全量回埋端点兜底。

## 变更内容

### 1. 契约受控解冻与多源标识

- 扩展 `SourceReference.sourceType` 语义：新增支持 `"SALES_OPPORTUNITY"`（`documentId` 为商机主键，`filename` 为商机名称，`chunkId` 为 `SALES_OPPORTUNITY:{商机主键}`）；
- 保持 `SourceReference` 十字段 record 结构与 SSE `sources` 事件 payload 零变更；
- 产出卡内契约增量文档 `specs/knowledge-rag/spec-delta.md`，并在 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 追记解冻条目（对齐既有先例）。

### 2. 异步增量架构与事务回调（SalesOpportunitySemanticHookSupport）

- **真正的异步解耦**：
  - 在 `SalesOpportunitySemanticHookSupport` 中实现 `runAfterCommitAsync(scene, executor, action)`；
  - 当 Spring 事务激活时，在 `TransactionSynchronization.afterCommit()` 回调中将向量同步任务分发给 `@Qualifier("embeddingTaskExecutor")`（或异步任务执行器），HTTP 主线程在事务提交后立即返回响应（微秒级），不等待任何外部向量嵌入耗时；
  - 当无事务环境时，同样提交给异步线程池执行；
  - 任务用 `try-catch` 严密包裹，任何网络抖动或模型异常仅打印 `WARN` 日志，绝不向上抛出；
  - 单测环境中支持传入直接执行器（`Runnable::run`），保证测试行为 100% 确定性与无竞态。
- **Hook 触点**：
  - 在 `SalesOpportunityServiceImpl` 的 `create`（新增商机）、`update`（修改商机）、`delete`（删除/关闭商机）处，注册事务提交后异步重嵌（`reingestById`）与删除（`deleteById`）。

### 3. 商机语义摄取服务（SalesOpportunityIngestionService）

- **富文本拼装（`SalesOpportunitySemanticTextSupport`）**：
  - 规范抽取商机名称、关联客户公司名称、主要联系人姓名、当前商机阶段（文字描述）、预估金额、机会来源、预计结单日期、**需求与跟进描述（`description`）**、跟进负责人姓名；
  - 拼装为高可读性语义文本，调用 `EmbeddingService.embed(text)` 生成 1024 维向量。
- **Payload 权限与关联元数据打标**：
  - `entityType`: `"SALES_OPPORTUNITY"`（固定值）
  - `resourceId`: 商机 ID
  - `ownerUserId`: 负责人 ID（`owner_id`，跟进销售）
  - `ownerDeptId`: 负责人所属部门 ID（反查 `sys_user.dept_id` 反范式缓存）
  - `companyId`: 关联客户公司 ID（可选）
  - `title`: 商机名称
- **存量回埋端点**：
  - 新增管理员全量回埋端点（`POST /sales/reingestAll`），挂既有权限注解 `@RequirePermission(PermissionOperates.SALES_CREATE_SALE_OPPORTUNITY)`。

### 4. 业务数据检索多实体融合扩展（BusinessRetrievalService）

- **权限闸同锚**：
  - 对接 `DataScopeTablePermissions` 中原生已有的 `sales_opportunity` 资源类型：
    - 调用 `dataScopeService.getHighestDataScopeLevel(user, "sales_opportunity")`；
    - 执行 NONE 零外发 / SELF / DEPT / DEPT_AND_CHILD / ALL 过滤与 TAGE/共享补充召回；
    - 纵深防御复核：SELF/TAGE 档合并前进行归属与授权清单复核，防止向量库过滤语义漂移。
- **多实体融合**：
  - `ENTITIES` 扩充为客户、商业活动、销售商机三实体同轮多路召回；
  - 按相似度降序截断 TopK，来源标注 `type=SALES_OPPORTUNITY`，统一注入 `【业务数据检索】` 上下文。

## 不做什么

- 不新增任何数据库表或修改已有表结构（**零 Flyway 迁移脚本**，严禁改动 `src/main/resources/db/`）。
- 不新增任何权限码常量（复用 `PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY` 与 `SALES_CREATE_SALE_OPPORTUNITY`）。
- 不修改前端业务代码（若新增端点引起 `frontend/openapi.yaml` 漂移，仅按后端 swagger 导出同步覆盖契约文件）。
- 零真跑模型外呼（单测与门禁全程 `DASHSCOPE_API_KEY` 置空，纯 JVM mock）。

## 验收

1. 商机新增、更新、删除操作后，HTTP 业务请求立即返回，向量异步后台同步更新，删除商机向量立即失效。
2. 权限隔离严格生效：四角色测试（归属人本人可查、本部门上级可查、跨部门同事绝不可查、管理员全可查）100% 锁定。
3. 单元测试覆盖摄取文本拼装、异步钩子非阻塞隔离、多实体权限闸融合与降级保护。
4. `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1314）。
5. 四静态门禁（checkstyle / spotbugs / spotless / pmd）与聚合门禁 `merge-gate.sh` 退出码 0。

## 红线

- 异步非阻塞执行：增量嵌入绝不阻塞业务主线程。
- 零迁移、零新权限常量、零真跑外呼、零破坏性改动。
- 严禁未经授权执行 `git push`。
