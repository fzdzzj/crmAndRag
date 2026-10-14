# §0 留痕 —— add-source-citation-and-dashboard（已随收尾笔提交 tracked）

> 分支 `feature/add-source-citation-and-dashboard`（自 master@bd3fdd4 检出，树净实测 master==origin/master==bd3fdd4 后开工）。两件套正版首笔 c583c2d 落入
> `openspec/changes/add-source-citation-and-dashboard/`，此后每任务组一提交。tasks 全勾与依据见
> `openspec/changes/add-source-citation-and-dashboard/tasks.md`（本分支头注）。费用约束 ¥0 零外部外呼：全部测试零真跑外呼
> （DASHSCOPE_API_KEY 置空 + mock 桩），无重嵌入，SelfRag/CitationAligner/prompt 链路零改动。

## 1. 分支笔数清单（自 master，`git log --oneline master..HEAD`）

```
c583c2d docs(openspec): 落地 add-source-citation-and-dashboard 两件套正版（proposal+tasks 头注红线）
70db4f0 chore(ai-source): 任务组1 后端来源链路接线——SOURCES 契约常量 + chunk 端点 + 单测/IT + CONTROLLER_REGISTRY
ac78c10 chore(contact-birthday): 任务组3.1 联系人生日清单端点 + 单测 3 例 + OpenAPI 契约刷新
05c6886 feat(ai-frontend): 任务组2 来源渲染与文档查看抽屉
f6e4cb1 feat(dashboard-frontend): 任务组3.2/3.3 首页仪表盘改造
2c17f98 fix(ai-source-it): KnowledgeChunkControllerIT 种子补 dept_id（修复 10001）
7b993db test(baseline): 收紧回归基线 1236→1248 / failsafe 114→116
```

笔数与任务组对齐：openspec 首笔 → 任务组1 → 任务组3.1 → 任务组2 → 任务组3.2/3.3 → IT 修正 → 门禁基线收尾。

## 2. 任务组 1 · 后端来源链路接线

- **1.1 SOURCES 事件发射**：`AiChatServiceImpl#dispatchChatStream` 检索完成后、答案生成前发射 `SseEventName.SOURCES`（时序 start→meta→sources?），
  payload=`AiSsePayloadWriter#toSourcesJson(retrieval.sources())`（知识库+业务 CUSTOMER 双源合并列表原样），`sendBufferedEvent` 带 SSE id 进重放缓冲
  （断线续传语义）。冻结契约 `SseEventName.java`/`SourceReference.java` **零 diff**（仅将字面 `"sources"` 改为 `SseEventName.SOURCES.wireName()` 引用常量，契约文件未动）。
  ai_message.payload 落库与 CitationAligner/references 链路零改动。发射点位实现已随上游 master（85078d7）就位，本卡补齐契约常量引用。
- **1.2 SOURCES 单测**：`AiChatServiceImplTest` 新增 3 例——`doStreamChat_sourcesEmittedAfterMetaBeforeDelta_withFullPayload`（断言 meta→sources→delta 顺序 + payload 含
  chunkIndex/pageNo/rowIndex/chunkId/sourceType/relevanceScore）、`zeroHit_doesNotEmitSources`（零命中不发）、`cancelledStream_sourcesNotReemitted`（取消不补发）。全 mock 桩零外呼。
- **1.3 知识库 chunk 读取端点**：新 `KnowledgeChunkController#GET /knowledge/documents/{documentId}/chunks`（挂 @@RequirePermission(AI_CHAT_SESSION=803)）
  + `KnowledgeChunkService`（documentId→uploaded_file→所属 KB→`authorizedKnowledgeBaseIds` 复用知识库检索授权链双保险：无授权→12002 `PERMISSION_DENIED`；
  documentId 业务源 `CUSTOMER_COMPANY:{id}` 返回 `PARAM_FORMAT_ERROR`；chunkIndex 升序分页返回含 chunkIndex/pageNo/rowIndex/chunkText）。零新权限常量。
- **1.4 权限覆盖与端点 IT**：`PermissionCoverageScanner.CONTROLLER_REGISTRY` 登记 `KnowledgeChunkController`（现 31 controller，
  PermissionCoverageScannerTest/ComparatorTest 同步绿）；`KnowledgeChunkControllerIT` 正反向——授权成员 200 含定位字段 / 非授权成员 12002，
  Docker 真跑 **Tests run: 2, Failures: 0, Errors: 0**（详见 §7）。

## 3. 任务组 2 · 前端来源渲染与文档查看抽屉

- **2.1** `frontend/src/hooks/useAiChat.ts`：`ChatMessage` 加 `sources?: unknown[]`，SSE `case 'sources'` 实装（payload 数组入 `currentAssistantMsg.sources`）；references 链路零改动。
- **2.2** `AiAssistantDrawer.vue`：知识库来源标签行（filename + pageNo/chunkIndex 徽标 + 分数 relevanceScore）点击开文档抽屉；CUSTOMER 业务源点击跳客户详情
  （`/client/list?filters.id=`）；CONTRACT 业务引用保留既有跳转；答案内联 `[n]` 点击经事件委派锚定 `references[n-1]`/`sources[n-1]`。
- **2.3** 新 `KnowledgeDocumentDrawer.vue`：按 documentId 分页拉 `GET /knowledge/documents/{documentId}/chunks`（pageSize 100 循环），chunkIndex 升序渲染；
  `scrollIntoView` 定位命中块 + excerpt 段内 `<mark>` 高亮（indexOf 精确匹配无 regex 崩溃）+ 命中标记；`v-model:open` 独立于 AI 会话，关闭不销毁会话状态。
- **2.4 / 3.3** `pnpm lint:check` exit 0、`pnpm type-check:check` exit 0（本地实测）。

## 4. 任务组 3 · 首页仪表盘与待办

- **3.1 生日清单端点**：`CustomerContactController#GET /contact/birthdays`（`@RequirePermission(CUSTOMER_QUERY_CONTACT)` 随联系人既有读取口径）；
  `CustomerContactServiceImpl#getUpcomingBirthdays` 复用 `search` 同款数据范围注入（`@Privacy` + `selectList(LambdaQueryWrapper)` 触发 QueryWrapperAspect 只取当前用户数据权限范围内联系人），
  N 天窗口 = `BirthdayReminderProperties.days`，未启用抛 `NOT_ENABLED`（与既有单联系人端点同语义）；单测 `CustomerContactBirthdaysServiceTest` 3 例绿（范围计算/数据权限过滤/未启用）。
- **3.2** `(dashboard)/index.page.vue` 重写为真仪表盘：移除空壳 redirect；统计卡片区（`POST /dataStatistics/summary` 本月范围 6 指标 + `GET /dataStatistics/opportunityStageDistribution` echarts pie via `useEChart`）；
  待办三列表（待我审批 `GET /sales/stage approvalStatus=[0]+approverId=me` / 我的未完成任务 `POST /contactTask/query assigneeId=me` status 0+1 并行 / 联系人生日 `GET /contact/birthdays`）；
  各卡片点击跳对应业务页；无 501（报表查看）权限统计区隐藏（`report:REPORT_VIEW_REPORT`），待办区不受影响——复用 `useQueryMyPermissions` 既有权限态，零新增权限查询端点。

## 5. 任务组 4 · 门禁实测数字（2026-10-14 00:0x-01:07）

- **全量 surefire** `mvn -B -ntp test`：**Tests run: 1248, Failures: 0, Errors: 0, Skipped: 0**（↑ 自基线 1236，净增 12 = SOURCES 3 + KnowledgeChunkServiceTest 6 + 生日 3；只增不减）。
- **四静态（顺序自跑）**：checkstyle **0 violations**；spotless **975 files clean / 0 needs changes**；spotbugs **BugInstance size is 0**；pmd:check **通过（0 违规）**。
- **全量 verify/failsafe IT** `mvn -B -ntp verify`（Docker 真跑）：**failsafe Tests run: 116, Failures: 0, Errors: 0, Skipped: 7**（↑ 自 114，+2 = KnowledgeChunkControllerIT；含全链 FlywayMigrationIT、SchemaDriftAuditIT、KB 成员边界等）；BUILD SUCCESS。
- **三守卫**：PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）/ BIJECTION_OK（12 条豁免 == 12 条 High）/ check-test-baseline 通过。
- **基线 --update**（surefire+failsafe 净增触发，树净状态单次跑，MAJOR-1 教训禁复跑覆盖戳）：**surefire 1236→1248 / failsafe 114→116**，source-revision=2c17f98（树净单次重打戳）。
- **merge-gate 8/8**：unit / spotbugs / pmd / baseline / frontend-unit（179/179）/ hook / bijection / pmd-baseline 全 PASS（结论「以上输出可作为合入证据」）。

## 6. SOURCES 发射证据（§0 留痕）

- 代码点位：`src/main/java/.../service/impl/AiChatServiceImpl.java:279-283`（`sendBufferedEvent(activeStream, SseEventName.SOURCES.wireName(), eventWriter.toSourcesJson(retrieval.sources()))`，置于入流订阅前）。
- payload 结构：`AiSsePayloadWriter#toSourceItems` 逐字段序 sourceType/route/filename/documentId/chunkId/chunkIndex/pageNo/rowIndex/excerpt/relevanceScore（冻结 SourceReference 原样序列化）。
- 冻结顺序合规：start→meta→sources?（检索后、答案生成前）→thinking*→delta*→references?→title?→done，sources 在订阅回调（delta/references 发射主体）之前在同一线程同步发射。

## 7. 偏差披露

- A. **生日端点路径**：tasks 文本写 `GET /customer/contact/birthdays`，但既有 `CustomerContactController` 基路径为 `/contact`，故实际端点路径为
  `GET /contact/birthdays`（本卡按「随 CustomerContactController 既有口径」实现，Javadoc 注明卡面路径；child-frontend 消费同路径）。
- B. **OpenAPI 契约刷新时机**：`OpenApiContractExportTest` 因后端新增端点（TG1 chunk + TG3 birthdays）与前端 openapi.yaml 快照不一致而红，按
  `scripts/check-openapi-consistency.sh --update` 单次刷新契约 + `pnpm gen:api` 重建 SDK；契约快照刷新落在 TG3.1 提交（ac78c10），覆盖 TG1+TG3 两批新端点。
- C. **KnowledgeChunkControllerIT 首跑 10001**：初版种子 sys_user 缺 dept_id，`KnowledgeChunkController.currentUser` 的 UserContext 构造要求 deptId>0，
  回退桥抛 TOKEN_ERROR(10001)；补 `dept_id=1` 后 200/12002 全绿（修正笔 2c17f98）。
- D. **前端默认路径假定**：dashboard 任务「未完成」= ContactTask status 0（未开始）+ 1（进行中）并行两查询合并；客户详情跳转复用 `/client/list?filters.id=`；
  `/contact/birthdays` 尚未入生成的 SDK，前端以 `axiosInstance` 防御性消费。501 权限名 `report:REPORT_VIEW_REPORT`（既有常量）。均未新增依赖/权限查询端点/状态枚举。
- E. **零红线触碰**：零迁移（V1-V40 禁改）；零新权限常量（chunk 挂 803、生日挂 CUSTOMER_QUERY_CONTACT）；`SseEventName.java`/`SourceReference.java` 零 diff；前端 zero 新增依赖、`frontend/typed-router.d.ts` 零改动（仅 `pnpm gen:api` 重建 SDK 已确认 typed-router 未变）。

## 8. 收尾状态

- 分支 7 笔，树净（仅 work/ 未跟踪）。严禁 merge/push——已完成本卡实现与门禁自证，待 owner 复核合入。