# proposal — add-customer-semantic-retrieval（customer 业务数据接入 RAG 语义检索）

> 定位：把 CRM 业务数据（首版仅 `customer_company`）接入助手语义检索——向量化落 Qdrant 新 collection、检索与知识库双源并行召回合并、权限闸全程收口、检索结果槽位化注入。
> 背景：§91 功能审视 A 层①（助手检索只查知识库文档，customer embedding 全库零命中）；slots 机制（add-prompt-template-slots D6）已预留给本卡，零机制改动。
> owner 拍板（2026-10-13）：D1 向量语义检索 / D2 全量回埋+增量 hook / D3 解冻+槽位化 / D4 IT mock 验证；并特别强调**业务数据权限问题**。

## Why

- 现状（实测）：`AiChatKnowledgeRetrievalService` L42-76 只走 `KnowledgeRetrievalPort`（knowledge 域，kbScope 授权收敛→改写→多路召回→重排）；助手回答对 `customer_company`（company_name/industry/description/address/customer_type/belong_group/dept/source 等丰富语义字段，V1__baseline L92-120）零感知——问「上海的制造业客户」只能靠 LLM 参数记忆，无法召回业务库。
- 向量化链全复用：`EmbeddingService`（ModelProvider→DashScope embedding 模型）→ `CrmVectorStore.upsertAll`（Qdrant 主 / InMemory 兜底）；Qdrant metadata 类型约定已冻结（String keyword / Long 布尔，禁 Boolean/UUID，contracts-frozen §2 B4）。
- 权限是本卡第一关切（owner 指示）：customer 有完整数据权限语义（owner_id/creator_id 归属 + DataScope ALL/DEPT_AND_CHILD/DEPT/TAGE/SELF + 共享/tage 绑定超集不变量），**无权 customer 绝不允许出现在检索结果**——权限组件全部现成：`DataScopeUserScopeGuard`（级别判定/可见部门集合/单条读判定，冻结契约 DataScope 三方法）+ `DataScopeService.getTageResourceIds`/`getSharedResourceIds`（TAGE 与共享资源 ID 解析）。

## What Changes

- **摄取侧**：`CustomerIngestionService`（server 域）把单条 customer 拼装语义文本（非空字段「字段名: 值」标签化拼接）→ `EmbeddingService.embed` → `CrmVectorStore.upsert` 进新 collection `crm_entity`；payload 携带 `entityType`(String keyword "CUSTOMER_COMPANY") / `resourceId`(Long) / `ownerUserId`(Long) / `ownerDeptId`(Long，摄取时经 owner_id 反查 sys_user.dept_id 反范式缓存)；软删（is_deleted=1）连带 `delete` 向量。
- **同步双通道**（D2）：① 全量回埋管理端点（手动触发，成本可授权后执行，复用 `KNOWLEDGE_ADMIN_MANAGE(900)` 读写同权单码，走 606/808 权限复用先例不新增常量）；② customer 增删改 Service 层 hook 同步 upsert/delete（hook 失败 try-catch WARN 不阻断业务主流程，回埋端点兜底修复陈旧）。
- **检索侧**：新 lane-local 端口 `BusinessRetrievalPort`（对齐 KnowledgeRetrievalPort 先例，server.ai.port 定义、server 域提供实现），`BusinessRetrievalService` 实现权限闸 + 向量检索：
  - **权限闸**（安全红线，四档分支 + 补充召回）：SELF → Qdrant filter `ownerUserId match 当前用户`；DEPT → `ownerDeptId match 本部门`；DEPT_AND_CHILD → `ownerDeptId match 可见部门集合`（子树解析经 DataScopeDepartmentResolver）；ALL → 无 filter；NONE → 业务检索直接返回空（不外发）。TAGE 级与共享资源经 `getTageResourceIds`/`getSharedResourceIds` SQL 解析出 resourceIds → Qdrant `resourceId match 多值` 补充召回 union 进结果（超集不变量「共享/tage 不收窄」在业务检索侧同样成立）。
  - **效果语义（owner 口径锚定，2026-10-13 补充拍板）**：一条业务数据，可检索到它的只有——**归属人自己**（SELF 档覆盖自己）、**上司**（DEPT/DEPT_AND_CHILD 档覆盖下属部门数据）、**管理员**（ALL 档/超管）；无档位覆盖的同事检索不到。检索权限与现有页面/SQL 数据权限（QueryWrapperAspect 同源）严格一致——页面看得到的检索得到，页面看不到的检索不到，杜绝「检索面比页面权限更宽」的泄漏面。
  - topK 按召回域取值，filter 后不足不迭代放大（诚实返回实际命中）。
- **AiChat 双源合并**：`AiChatServiceImpl` 检索调用处并行/串行调用知识库检索（既有）+ 业务检索（新），context 分节合并、sources 合并发 SSE `sources` 事件；业务检索任何异常 WARN + 空业务段降级（**不阻断主答**，对齐模板回退链先例）；业务检索跟随既有 `useKnowledgeBase` 开关（语义=检索增强总开关，不新增请求字段/契约面）。
- **槽位化注入**（D3，slots D6 预留兑现）：V38 迁移 UPDATE aichat-system-prompt content 加 `{{#slot:retrievedContext}}` 标记（version+1 + history 快照，V37 先例同款）；`PromptSystemPromptSegments.buildSlots` 增产 `retrievedContext`（值=分节组装的检索上下文成品，空检索时置空串）；无标记/回退态走既有旧路径逐字节不变（回归锁）。
- **SourceReference 受控解冻**（D3）：platform.contract `SourceReference` 的 sourceType 语义扩展支持业务来源（type=CUSTOMER、id=customer id、name=company_name），**卡内 spec-delta 登记**（fix-citation-grading-overlap B1 先例），contracts-frozen.md 仅追加解冻记录条目、SSE payload 结构零改动。
- **验证**（D4）：全链 mock embedding（EmbeddingService 桩）单测 + IT（InMemory 向量桩），权限矩阵用例全覆盖（四档 + TAGE/共享 union + NONE 空返回）；真跑评测（benchmark 加 customer fixture + RAG_BENCHMARK_REAL=1）与生产全量回埋（真实 embedding 成本）均为**另授权节点**，本卡不执行。

## 指导侧裁决假设（已定，owner 可否决）

1. 首版只接 `customer_company` 单实体；`customer_contact` 留后续卡（避免范围膨胀）。
2. 业务检索开关跟随 `useKnowledgeBase`（不新增请求字段）；meta SSE 事件零改动。
3. 回埋端点权限复用 900（不新增常量）；PermissionCoverageScanner 新端点登记同步。
4. ownerDeptId 摄取时反范式缓存进 payload（检索零回查，负责人变更/调岗时 hook 刷新；陈旧兜底=回埋端点）。
5. 前端零改动（references items 结构 {type,id,name} 不变，type=CUSTOMER 新值由前端现有字符串渲染容忍）。
6. 分段 section 元数据（块数量/顺序结构化另一半）不并入本卡——本卡只做检索内容槽位化（§86 预拍板范围中与检索装载直接相关的一半）。

## 不做（边界）

- `customer_contact` 及其他业务实体接入（后续卡）、真跑评测与生产回埋执行（另授权节点）、前端页面
- `KnowledgeRetrievalPort`/知识库检索链任何改动（业务检索是**旁挂新链**，不动既有链）、`{{var}}`/`{{> code}}`/既有槽位语义任何变更
- 既有迁移 V1-V37 禁改；contracts-frozen.md 除解冻记录条目外零触碰；零新增 ErrorCode（业务检索异常走既有 WARN+降级）
- 熔断/限流新增配置（embedding 调用走既有 resilience 通道）；数据权限语义零新增（全复用现有组件）

## 影响面

- 写集预估：V38 迁移 + QdrantProperties(collection 配置) + CustomerIngestionService + 回埋端点 Controller/Service + BusinessRetrievalPort/Service + AiChatServiceImpl/AiChatPromptService/PromptSystemPromptSegments + SourceReference + PermissionCoverageScanner 登记 + FlywayMigrationIT + 单测/IT ≈ 15-18 tracked 文件
- 复核档位预判 **A 档**（DB 迁移 + 冻结契约解冻 + 聊天检索主链路 + 权限语义）；surefire 只增不减；零外呼（DASHSCOPE_API_KEY 置空 + mock embedding）；顺带责任 = 基线 `--update` 收紧（1151↔1185 陈旧漂移历卡累积，本卡门禁环节真实运行收紧）
- 最大风险面 = 权限闸（无权数据泄漏）与 AiChat 主链路（检索失败阻断主答）：红测先行（权限反向用例）+ 权限矩阵全档用例 + 降级链用例三重兜底
