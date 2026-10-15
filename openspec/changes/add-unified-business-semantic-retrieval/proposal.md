# add-unified-business-semantic-retrieval — 统一业务语义检索与全站增量异步化

## 背景与动机

- 在前期几张业务检索卡合入后，CRM 系统已实现了客户（`CUSTOMER`）、商业活动（`BUSINESS_ACTIVITY`）与销售商机（`SALES_OPPORTUNITY`）的向量检索；
- 但目前仍存在三大核心诉求与系统技术债：
  1. **剩余核心商业实体尚未打通**：销售合同（`contract`，含订单产品明细 `contract_order_item` 与回款记录 `payment_record`）及跟进待办任务（`contact_task`）沉淀了全站最核心的商业履约与执行数据，AI 助手目前无法检索合同条款、成交产品清单与待办任务；
  2. **增量更新异步化存在历史断层**：仅 `SalesOpportunity` 实现了真正的事务后异步派发，存量的 `Customer` 与 `BusinessActivity` 仍处于 `afterCommit` 同步等待模型嵌入状态，存在阻塞业务主线程的隐患；
  3. **前端引用跳转与误判断点**：`AiAssistantDrawer.vue` 硬编码了单一客户前缀，导致非客户业务实体被误当成知识库文档（向 `/knowledge/chunk` 请求触发 404/异常），且点击引用缺乏对应业务实体的深度路由跳转。
- 本卡将**剩余全部业务实体语义检索合并统一接入**，一揽子消灭增量同步技术债，并修复前端 RAG 引用高亮与跳转断点，实现全仓业务数据语义检索的完全体闭环。

## 变更内容

### 1. 契约受控解冻与多源标识

- 扩展 `SourceReference.sourceType` 语义：新增支持 `"CONTRACT"` 与 `"CONTACT_TASK"`；
- 保持 `SourceReference` 十字段 record 签名与 SSE `sources` 事件 payload 结构零变更；
- 产出卡内契约增量文档 `specs/knowledge-rag/spec-delta.md`，并在 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 追记解冻条目。

### 2. 全站增量向量化异步非阻塞统一（消灭技术债）

- **统一异步 Hook 范式**：
  - 推广 `runAfterCommitAsync(scene, executor, action)` 模式至全仓业务实体；
  - 升级 `CustomerSemanticHookSupport` 与 `BusinessActivitySemanticHookSupport`，注入 `@Qualifier("embeddingTaskExecutor")` 线程池，全面转为异步非阻塞执行；
  - 新增 `ContractSemanticHookSupport` 与 `ContactTaskSemanticHookSupport`，在合同与任务的增删改提交后异步派发向量重嵌与删除；
  - 严格隔离异常，失败记录 `WARN`，绝不阻断或回滚主业务事务；单测传入 `Runnable::run` 确保 100% 确定性。

### 3. 商业合同与跟进任务语义摄取服务

- **商业合同（`ContractIngestionService` + `ContractSemanticTextSupport`）**：
  - 拼装合同基本信息（编号/名称/总金额/状态/日期/客户/商机/负责人）；
  - **优雅嵌入附属段**：将订单产品明细（`contract_order_item` 产品名称/规格/单价/数量）与回款履约（`payment_record` 回款金额/时间/方式）作为附属语义段拼入合同文本中，防止 Qdrant 碎片化，同时支撑“按产品找合同”的语义召回；
  - Payload 键集：`entityType="CONTRACT"`, `resourceId=id`, `ownerUserId=owner_id`, `ownerDeptId=ownerDeptId`, `companyId=company_id`, `title=contract_name`。
- **跟进待办任务（`ContactTaskIngestionService` + `ContactTaskSemanticTextSupport`）**：
  - 拼装任务主题、任务类型、优先级、状态、起止时间、客户/商机/联系人、**任务富文本详细内容（`task_content`）**；
  - Payload 键集：`entityType="CONTACT_TASK"`, `resourceId=id`, `ownerUserId=assignee_id`, `ownerDeptId=assigneeDeptId`, `companyId=company_id`, `opportunityId=opportunity_id`, `title=task_title`。
- **全量回埋端点**：
  - `POST /contract/reingestAll`（挂 `@RequirePermission(PermissionOperates.SALES_VIEW_CONTRACT)` 或对应写权限）；
  - `POST /contactTask/reingestAll`（挂 `@RequirePermission(PermissionOperates.TASK_CREATE_TASK)`）；
  - 契约若漂移由 `scripts/check-openapi-consistency.sh --update` 同步 `frontend/openapi.yaml`。

### 4. 业务数据检索五实体全覆盖多路召回（BusinessRetrievalService）

- `ENTITIES` 扩充至全量 5 业务实体：`CUSTOMER_COMPANY`、`BUSINESS_ACTIVITY`、`SALES_OPPORTUNITY`、`CONTRACT`、`CONTACT_TASK`；
- 严格同锚 `DataScopeTablePermissions` 原生已有权限：
  - `contract` -> `SALES_VIEW_CONTRACT_*`（5 档）
  - `contact_task` -> `TASK_VIEW_TASK_*`（5 档）
- 统一 `BusinessRetrievalScopeSupport` 纵深防御复核，来源判源对齐五实体，`[n]` 统一顺延。

### 5. 前端 AI 助手引用交互与精准路由闭环（AiAssistantDrawer.vue）

- 改造业务来源判断：识别 `CUSTOMER`、`BUSINESS_ACTIVITY`、`SALES_OPPORTUNITY`、`CONTRACT`、`CONTACT_TASK` 均为业务实体来源（非知识库文档），防止错误调用 `/knowledge/chunk` 产生 404；
- 点击业务引用与行内 `[n]` 实现精准深度跳转：
  - 客户：`router.push('/client/list?filters.id=...')`；
  - 商机：`router.push('/sale/opportunity?id=...')`；
  - 合同：`router.push('/sale/contract-details/:id')`；
  - 活动与任务：跳转至对应活动/任务列表或打开详情弹窗；
  - 知识库文档：正常唤起文档高亮抽屉。

## 不做什么

- 不新增任何数据库表或修改已有表结构（**零 Flyway 迁移脚本**）。
- 不新增任何权限码常量（复用 `DataScopeTablePermissions` 中原生已有的 5 档枚举）。
- 零真跑模型外呼（单测全程 `DASHSCOPE_API_KEY` 置空，纯 JVM mock）。

## 验收

1. 合同、跟进任务增删改后，HTTP 业务请求立即返回，向量异步后台更新，删除立即失效；
2. 存量客户、商业活动增量更新全面升级为异步非阻塞；
3. 五实体数据权限隔离严格生效（归属人本人可查、本部门上级可查、跨部门同事绝不可查、管理员全可查）；
4. 前端 AI 助手所有业务实体引用能精准识别与跳转，不再误调知识库接口；
5. `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1342）；
6. 前端单元测试与类型检查全绿（`pnpm lint:check` + `pnpm type-check:check` + `pnpm vitest run`）；
7. 四静态门禁（checkstyle / spotbugs / spotless / pmd）与聚合门禁 `merge-gate.sh` 退出码 0。

## 红线

- 全站增量更新异步化，绝不阻塞主业务接口。
- 零迁移、零新权限常量、零破坏性变更、零未经授权 push。
