# tasks — add-source-citation-and-dashboard

> 红线：**B 档零迁移**（零 DDL 零新表，迁移 V1-V40 禁改）；**零权限语义新增**（chunk 端点复用 803 + KB 授权链，生日端点随 Contact 既有口径，零新权限常量，若需 OPEN 登记停下重拍）；**零真跑外呼**（全部测试零外呼：DASHSCOPE_API_KEY 置空 + mock 桩，无重嵌入，SelfRag/CitationAligner/prompt 链路零改动）；**零冻结契约触碰**（SseEventName/SourceReference 文件零改动，SOURCES=已定义事件的实现补齐）；surefire 只增不减（基线 1236）；新代码中文 Javadoc 标注「add-source-citation-and-dashboard 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8；前端 CI（lint:check + type-check:check + build budget + unit 179 例）不回退。

## 任务组 1 · 后端来源链路接线

- [x] 1.1 SOURCES 事件发射：检索完成回调处发射 `SseEventName.SOURCES`（时序=检索后、答案生成前，冻结顺序 start→meta→sources?→thinking*→delta*→references?→title?→done），payload=`AiSsePayloadWriter#toSourcesJson`（双源合并 List<SourceReference>，知识库+业务 CUSTOMER 原样），业务事件带 SSE id 进重放缓冲（断线续传语义）；ai_message.payload 落库与 CitationAligner/references 链路零改动
      依据：`mvn -B -ntp test -Dtest=AiChatServiceImplTest` 绿；`AiChatServiceImpl.java:280` 改字面「sources」为 `SseEventName.SOURCES.wireName()`，`sendBufferedEvent` 带 id 进重放缓冲；emission 已在 master（85078d7）就位，本卡仅改契约常量引用
- [x] 1.2 SOURCES 单测：发射时序（sources 先于首个 delta 与 references）+ payload 结构（SourceReference 全字段序列化含 chunkIndex/pageNo/chunkId）+ 零命中不发 + 接管/取消流不发；全部零外呼（mock 桩）
      依据：`AiChatServiceImplTest#doStreamChat_sourcesEmittedAfterMetaBeforeDelta_withFullPayload` 断言 meta→sources→delta 顺序 + payload 含 chunkIndex/pageNo/rowIndex/chunkId/sourceType/relevanceScore；`zeroHit_doesNotEmitSources`、`cancelledStream_sourcesNotReemitted`；本类 35 用例全绿零外呼
- [x] 1.3 知识库 chunk 读取端点：新 controller `GET /knowledge/documents/{documentId}/chunks`（分页、chunkIndex 升序，返回含 chunkIndex/pageNo/rowIndex/chunkText），挂 `@RequirePermission(AI_CHAT_SESSION=803)` + 内部按 documentId 解析所属 KB 过 KB 检索授权链校验（无授权→PERMISSION_DENIED 12002）；documentId 为 CUSTOMER_COMPANY:{id} 业务源口径时返回参数错误（业务源不走此端点）
      依据：`KnowledgeChunkController.java` + `KnowledgeChunkService.java`（复用 `KnowledgeBaseAuthorizationService.authorizedKnowledgeBaseIds`，业务源/文档缺失/无授权抛 12002）；`KnowledgeChunkServiceTest` 7 用例绿
- [x] 1.4 权限覆盖与端点 IT：`PermissionCoverageScanner.CONTROLLER_REGISTRY` 登记新 controller（PermissionCoverageComparatorTest/ScannerTest 同步绿）；chunk 端点正反向 IT（授权用户 200 含定位字段 / 无 KB 授权用户 12002）
      依据：`PermissionCoverageScannerTest`+`PermissionCoverageComparatorTest` 绿（registry 现 31 controller）；`KnowledgeChunkControllerIT` 正反向（200 含 chunkIndex/pageNo → 12002），Docker 门禁（CI 真跑）

## 任务组 2 · 前端来源渲染与文档查看抽屉

- [x] 2.1 `useAiChat` sources 消费：sources case 实装（payload 数组入 currentAssistantMsg.sources），ChatMessage 模型扩展 sources 字段（unknown 安全类型）；references 既有链路零改动
      依据：`frontend/src/hooks/useAiChat.ts` 加 `sources?: unknown[]` + 实装 `case 'sources'`（数组入 `currentAssistantMsg.sources`）；references case 零改动
- [x] 2.2 `AiAssistantDrawer` 来源标签：知识库来源（sourceType≠CUSTOMER 且有 documentId）渲染来源标签（filename + pageNo/chunkIndex 徽标 + relevanceScore），点击打开文档查看抽屉并定位；CUSTOMER 源点击跳客户详情页（既有路由）；CONTRACT 业务引用保留既有跳转；答案内联 [n] 点击锚定对应来源（citations 编号已在 references payload）
      依据：`frontend/src/components/ai/AiAssistantDrawer.vue`：KB 源标签（filename+pageNo/chunkIndex+relevanceScore）→ 开抽屉；CUSTOMER_COMPANY: 源 → `/client/list?filters.id=`；CONTRACT 既有跳转保留；[n] 锚定 references[n-1]/sources[n-1]
- [x] 2.3 `KnowledgeDocumentDrawer` 组件：按 documentId 分页拉 chunk 列表（任务 1.3 端点）、chunkIndex 升序渲染、scrollIntoView 定位到命中块 + excerpt 段内匹配高亮 + 命中块标记（pageNo 有值显示页码）；抽屉关闭不销毁会话状态
      依据：`frontend/src/components/ai/KnowledgeDocumentDrawer.vue`（新，~300 行）：`GET /knowledge/documents/{id}/chunks` 分页拉取、升序、`scrollIntoView` 定位 + safe `<mark>` excerpt 高亮、v-model 开态独立于 AI 会话
- [x] 2.4 前端质量：pnpm lint:check + type-check:check 零错误（本地实测留痕）
      依据：`pnpm lint:check` exit 0（0 error 0 warning）；`pnpm type-check:check` exit 0

## 任务组 3 · 首页仪表盘与待办

- [x] 3.1 生日清单端点：`GET /customer/contact/birthdays`（当前用户数据权限口径下的联系人未来 N 天生日清单，N=BirthdayReminderProperties.days 默认），enabled=false 时与既有单联系人端点同语义（NOT_ENABLED）；权限随 CustomerContactController 既有口径；单测覆盖（范围计算/数据权限过滤/未启用）
      依据：`CustomerContactController` `GET /contact/birthdays` @RequirePermission(CUSTOMER_QUERY_CONTACT)（控制器基路径为 `/contact`，故端点路径为 /contact/birthdays，偏差已披露）；`CustomerContactServiceImpl#getUpcomingBirthdays` 复用 `search` 同款 `@Privacy`+`selectList(LambdaQueryWrapper)` 数据范围注入，未启用抛 NOT_ENABLED；`CustomerContactBirthdaysServiceTest` 3 用例绿（范围/N 天窗口/未启用）
- [x] 3.2 `(dashboard)/index.page.vue` 仪表盘：移除空壳跳转行为，改为真仪表盘——统计卡片区（POST /dataStatistics/summary 本月范围 6 指标 + GET /dataStatistics/opportunityStageDistribution echarts 图）+ 待办区三列表（我的待审批：GET /sales/stage approvalStatus=[0]+当前用户为审批人；我的任务：POST /contactTask/query 当前用户+未完成；联系人生日：任务 3.1 端点）；各卡片点击跳对应业务页；无 501 权限统计区隐藏（待办区不受影响）
      依据：`frontend/src/pages/(dashboard)/index.page.vue` 重写为真仪表盘（移除 redirect）；统计区 gate `report:REPORT_VIEW_REPORT`（501），待办区不受影响；三列表 + echarts pie（useEChart）
- [x] 3.3 前端质量：pnpm lint:check + type-check:check 零错误（本地实测留痕）
      依据：`pnpm lint:check` exit 0；`pnpm type-check:check` exit 0

## 任务组 4 · 门禁与收尾

- [ ] 4.1 全量 mvn -B -ntp test 0 失败（surefire 只增不减），checkstyle/spotbugs/spotless/pmd 顺序跑全 0
      依据：（执行留痕）
- [ ] 4.2 三守卫（pmd-baseline/bijection/test-baseline）+ merge-gate 8/8；基线 --update 仅当 surefire 净增（树净状态单次跑，MAJOR-1 教训：禁复跑覆盖戳）
      依据：（执行留痕）
- [ ] 4.3 tasks 全勾 + 每勾下一行 6 空格依据行 + §0 留痕（SOURCES 发射证据/chunk 端点正反向/生日端点/门禁实测数字/偏差披露），handoff 落 work/handoff-add-source-citation-and-dashboard.md
      依据：（执行留痕）
