# proposal — add-source-citation-and-dashboard

> RAG 回答来源可视化（SOURCES 事件下发 + 知识库文档查看抽屉高亮跳转）+ 首页仪表盘（统计卡片 + 审批/任务/生日待办）合卡。

## 1. 背景（2026-10-13 master bd3fdd4 取证实测）

四方向队列③：原「回答来源高亮/跳转」与「首页仪表盘/待办提醒」合卡（owner 流程拍板，前端同域攒批，预判 B 档）。

**来源链路现状——契约与组装面就绪、下发与消费面断裂**：

1. `SourceReference` 契约字段全齐（sourceType/route/filename/documentId/chunkId/chunkIndex/pageNo/rowIndex/excerpt/relevanceScore，冻结契约，javadoc 已写明「档 B 页级高亮依赖」「excerpt 原样返回，前端做段内匹配高亮」）；检索组装已填充定位字段（`KnowledgeRetrievalServiceImpl#toSourceReference` 从向量 metadata 取 chunkIndex/pageNo/rowIndex）。
2. sources 全链传递已就位：`AiChatKnowledgeRetrievalService.RetrievalOutcome`（知识库 + 业务 CUSTOMER 双源合并）→ `AiStreamRegistry.setSources` → `AiChatStreamFinalizer`（`CitationAligner` 对齐 `[n]`、extractCitations）→ `ai_message.payload` 落库（toAuditJson 含 sources）。
3. **断点一（后端发射）**：`SseEventName.SOURCES("sources")` 冻结事件零发射调用（grep 实测）；序列化器 `AiSsePayloadWriter#toSourcesJson` 就绪但流中从未发出。前端 `useAiChat.ts` sources case 空实现（注释「当前合并到 references」）。
4. **断点二（前端消费）**：`AiAssistantDrawer.vue` 只渲染业务实体引用（`AiReferenceCollector` 的 {type,id,name}），`jumpReference` 仅 CONTRACT 真跳转，其余弹 message.info；SourceReference 结构（sourceType/documentId/chunkIndex/pageNo/excerpt）无渲染、无跳转。
5. **断点三（查看目标）**：知识库前端页 `(dashboard)/knowledge/index.page.vue` 仅 KB 管理（上传/删除/重建/检索测试），无文档内容查看页；后端无普通用户可用的按 documentId 列 chunk 读取端点（`KnowledgeAdminController` 7 端点全挂 KNOWLEDGE_ADMIN_MANAGE=900 admin 权限）。
6. 总纲组 3 的「PDF/视觉按页分块打 pageNo（当前 mergedText 页边界丢失需重构）」属摄取重构 + 存量重嵌入（真跑成本），本卡不做（D1 拍板留后卡）。

**仪表盘现状——首页空壳、数据源基本齐备**：

1. `(dashboard)/index.page.vue` 为空壳（onMounted 直接 `router.replace('/client/list')`）；`MainLayout`（SideBarNav + Header + router-view）就位。
2. 统计面现成：`POST /dataStatistics/summary`（时间范围必填，501）返回 6 指标（商机总数/新增商机/签约合同数/签约总额/回款总额/回款笔数）+ `GET /dataStatistics/opportunityStageDistribution`（501）；echarts ^6.0.0 已在依赖。
3. 待办面：审批 `GET /sales/stage`（SalesStageApprovalDTO 支持 approvalStatus=[0 待审批] + approverId 过滤）、任务 `POST /contactTask/query`（assigneeId + status 过滤）均现成；**生日提醒仅有按单联系人端点**（`GET /customer/contact/{id}/birthdayReminder`），无「我的联系人未来 N 天生日清单」查询。
4. 无任何 dashboard/overview 聚合端点（摸底实测）。

## 2. owner 拍板记录（2026-10-13）

- **D1 定位粒度：chunk 级先行**——chunkIndex 定位 + excerpt 段内匹配高亮，pageNo 有值则展示页码；PDF 按页分块摄取重构 + 存量全量重嵌入（真跑成本）留后卡。零重嵌入零真跑外呼，守住 B 档红线。
- **D2 跳转形态：文档查看抽屉**——新建知识库文档内容查看组件（按 documentId 拉 chunk 列表、滚动定位到命中块并高亮 excerpt）；需新增普通用户可用的 chunk 读取端点（复用 KB 检索授权链，非 admin 900）。
- **D3 数据源：前端聚合**——首页并行调既有端点（统计 summary/阶段分布 + 审批待审 + 我的任务），仅新增「我的联系人未来 N 天生日清单」小端点；零新权限语义，各端点既有注解原样生效。

## 3. 范围（四个任务组）

- **任务组 1 · 后端来源链路接线**：SOURCES 事件发射（检索完成后、答案生成前，冻结顺序 start→meta→sources?→thinking*→delta*→references?→title?→done，payload=toSourcesJson 双源合并列表，业务事件带 SSE id 进重放缓冲）；知识库 chunk 读取端点（GET /knowledge/documents/{documentId}/chunks：挂 `@RequirePermission(AI_CHAT_SESSION=803)` + 内部按 documentId 解析所属 KB 过检索授权链校验双保险，分页防大文档全量）；`PermissionCoverageScanner.CONTROLLER_REGISTRY` 登记；单测/IT 覆盖（发射时序、payload 结构、无 KB 授权反向 12002）。
- **任务组 2 · 前端来源渲染与文档查看抽屉**：`useAiChat` sources case 实装（payload 数组入消息模型）；`AiAssistantDrawer` 知识库来源标签渲染（filename/pageNo/chunkIndex/relevanceScore，与业务实体引用区分）+ 点击分流（CUSTOMER→客户详情、知识库文档→查看抽屉、CONTRACT 保留既有）+ 答案内联 `[n]` 点击锚定对应来源；新建 `KnowledgeDocumentDrawer` 组件（chunk 列表渲染 + scrollIntoView 定位 + excerpt 段内高亮 + 命中块标记）；pnpm lint/type-check 绿。
- **任务组 3 · 首页仪表盘与待办**：生日清单端点（GET /customer/contact/birthdays，我的联系人未来 N 天生日，days 取 BirthdayReminderProperties 默认，权限随 CustomerContactController 既有口径）；`(dashboard)/index.page.vue` 改造为真仪表盘——统计卡片区（本月 summary 6 指标 + 商机阶段分布 echarts）+ 待办三列表（我的待审批/我的未完成任务/联系人生日）+ 无 501 权限降级；pnpm lint/type-check 绿。
- **任务组 4 · 门禁与收尾**：全量 test（surefire 只增不减）/四静态/三守卫/merge-gate；基线 --update 仅当用例数净增（树净单次，MAJOR-1 教训禁复跑覆盖戳）；§0 留痕 + handoff。

## 4. 红线

- **B 档零迁移**：零 DDL 零新表（chunk 读取用现有 document_vector_chunk，生日清单用现有 customer_contact），已合入迁移 V1-V40 禁改。
- **零权限语义新增**：chunk 读取端点复用既有权限号 803（AI_CHAT_SESSION，「能发起 AI 会话即可看来源原文」）+ 内部 KB 检索授权校验（复用检索授权链非新语义）；生日端点随 Contact 既有权限口径；零新权限常量、零 OpenEndpointRegistry 新登记（若执行中发现需新登记，停下重拍）；CONTROLLER_REGISTRY 登记为新 controller 的门禁义务。
- **零真跑外呼**：全部测试零外呼（DASHSCOPE_API_KEY 置空 + mock 桩），无重嵌入，LLM 链路（SelfRag/CitationAligner/prompt）零改动。
- **零冻结契约触碰**：SseEventName/SourceReference 契约文件零改动（SOURCES 是契约已定义事件的实现补齐，payload 用冻结 SourceReference 原样序列化）；contracts-frozen.md 不解冻。
- **surefire 只增不减**（基线 1236）；新代码中文 Javadoc 标注「add-source-citation-and-dashboard 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8；前端 CI（lint:check + type-check:check + build budget + unit 179 例）不回退。

## 5. 裁决假设（owner 可否决）

1. chunk 读取端点权限锚 = 803（AI_CHAT_SESSION）；内部再过 KB 检索授权链（kbScope 语义）防越权读未授权库文档。
2. 抽屉按 chunkIndex 定位（scrollIntoView + 高亮），不做页级跳转；excerpt 段内匹配高亮按 SourceReference javadoc 既有设计意图。
3. 仪表盘统计时间范围默认本月（1 日起至今）；无 501 权限用户统计区隐藏——实现按前端既有权限态模式，若无可复用模式则接口失败时隐藏卡片（不新增权限查询端点）；待办区不受 501 影响。
4. 生日清单范围 = 当前用户数据权限口径下的联系人，days 默认取 BirthdayReminderProperties.days；enabled=false 时端点行为与既有单联系人端点一致（NOT_ENABLED）。
5. CUSTOMER 业务源点击跳客户详情页（前端既有路由）；知识库文档源开抽屉；CONTRACT 引用保留既有跳转行为不动。
6. 侧边栏/菜单零改动（首页仪表盘替换原空壳跳转，/client/list 仍可达）。
7. 任务「未完成」的 status 值语义以 ContactTaskDTO/VO 实测为准，不新增状态枚举。

## 6. 验收

- 任务组 1-4 全勾且每勾留痕；SOURCES 事件发射证据在案（事件名、时序=检索后答案前、payload=SourceReference 列表结构）；chunk 读取端点正反向测试在案（授权用户 200 含定位字段 / 无 KB 授权 12002）；生日清单端点测试在案；门禁全绿（全量 test 0 失败 + 四静态 0 + 三守卫 + merge-gate 8/8 + 前端 CI 四件不回退）；surefire 只增不减；零 DDL / 零新权限常量 / 零契约文件改动（diff 实证）。
