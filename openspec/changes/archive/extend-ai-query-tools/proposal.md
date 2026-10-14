# extend-ai-query-tools — AI 查询工具面扩展（B 批 6 工具）

## 背景与动机

- Wave 0（e3f8230 基座）起 AI 助手具备 19 工具（12 查询 + 7 草稿），经 `AiToolRegistry#getPermittedToolCallbacks(RoleAO)` 按用户角色权限动态注入，`AiToolCallLog` 全调用审计。
- owner 方向拍板（2026-10-14）：AI tool 查询面扩展立项（低优先）；在线考试子域搁置（清单存档于 `work/_online-exam-backlog.md`）；通用 SQL 直查缓议。
- 对照权威树 30 个 controller 全量实测，销售跟进动线上 6 个高/中价值业务查询面尚未接入工具面：跟进任务、任务评论、业务活动、合同详情、商机详情、商机阶段审批。

## 变更内容

### 1. 新增 6 查询工具（全部挂既有权限常量，零权限语义新增）

| 工具名 | 数据面（controller 实测端点） | 权限锚（既有常量） |
|--------|------------------------------|-------------------|
| queryContactTask | 跟进任务（/contactTask：分页查询、按负责人/客户/联系人/商机） | TASK_VIEW_TASK |
| queryTaskComment | 任务评论（/taskComment/task/{taskId}(/page)） | TASK_VIEW_TASK |
| queryBusinessActivity | 业务活动（/business/activity：列表/详情） | SALES_VIEW_BUSINESS_ACTIVITY |
| getContractDetail | 合同详情（GET /contract/{id}） | SALES_VIEW_CONTRACT |
| getOpportunityDetail | 商机详情（/sales/detail/{opportunityId}） | SALES_VIEW_SALE_OPPORTUNITY |
| queryStageApproval | 商机阶段审批列表（GET /sales/stage） | SALES_VIEW_SALE_OPPORTUNITY_STAGE |

权限同锚原则不变：挂在工具上的权限即页面查看权限，「页面看得到的 AI 才拿得到」。

### 2. 实现载体（不新增 controller 端点）

- `AiToolRegistry`：`getPermittedToolCallbacks` 追加 6 个 `add()` 注册；若触发 PMD 超长规约，按域拆私有方法（注册逻辑等价搬移，行为零变化）。
- `AiQueryToolExecutors`：追加 6 个 `executeXxx`，全部调用既有 service 层方法；查空放宽/模糊消除引导（AMBIGUITY_GUIDANCE）与名称双通道等既有模式按需对齐。
- 单测：`AiToolRegistryContextTest` 补权限注入断言（有权限用户可见新工具、无权限不可见）；新增查询执行器行为测试（纯 JVM mock service）。

### 3. 拍板修正记录（立项实测）

B 批拍板时含「queryPayment/queryInvoice 按合同参数扩展」项，立项实测发现两工具 Wave 0 起已具备 `contractId` 参数（queryInvoice 另有 `invoiceNo`），**该参数扩展项无需实施**，本卡聚焦 6 新工具。

### 4. 顺带收口（owner 拍板随卡带上）

观察树 `docs/main-agent-execution.md` 存在 tracked 未提交修改（上卡遗留，§96 续 4 门禁分层条目），随本卡经权威树提交收口；收口后观察树工作树恢复干净。

## 不做什么

- 不新增/不修改任何 controller 端点与路由（`PermissionCoverageScanner` 注册表零改动）。
- 不碰草稿工具域（AiDraftToolExecutors）与 AI 管理面（Assist/AiChat/AiAction）。
- 不碰知识库/平台治理面（KnowledgeAdmin、Permission、Role、ProjectFile、PromptTemplate、UserHandover、Health）。
- C 批候选（用户/部门/报表/客户详情查询）留后续按需立项。
- huh 卡两件套归档不在本卡范围。
- 零数据库迁移、零新依赖、禁碰 frontend/。

## 验收

1. 6 新工具按权限动态注入：无权限用户不可见，有权限用户可见且可调（单测断言覆盖）。
2. 每个新工具有 mock service 行为单测（参数解析、查空提示、正常返回路径）。
3. `mvn -B -ntp test` 全绿，surefire 数量只增不减（当前基线 1252，revision=276f4ae）。
4. 四静态门禁（checkstyle / spotbugs / spotless / pmd）顺序全绿，pmd-baseline / spotbugs-staleness 校验通过。
5. 新代码中文 Javadoc 标注「extend-ai-query-tools 任务 x.x」。
6. execution.md 遗留收口完成：修改内容进入 master 历史，观察树工作树净。
7. 纯 JVM 测试，零真跑外呼（DASHSCOPE_API_KEY 置空）。

## 红线

- 零迁移（不碰 `db/migration`）、零权限语义新增（只挂既有常量）、零真跑外呼、零冻结契约触碰。
- 禁 `git push`（合并与推送由 owner 另行授权合并子 agent 执行）。
- 已合入 master 的迁移脚本禁改；测试基线只许 `--update` 树净单次写入且只增不减。
