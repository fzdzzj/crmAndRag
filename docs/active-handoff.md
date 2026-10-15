# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-15  
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！  
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@ecd308c`（已合入 `add-ai-customer-360-insight`）
- **测试基线**：Surefire 1415 / Failsafe 116（7 skipped），`scripts/test-baseline.txt` 权威对账
- **门禁状态**：四静态（checkstyle/spotbugs/spotless/pmd）全 0 违规，聚合门禁 `merge-gate.sh` 8 项全 PASS（前端 179 unit tests pass）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **RAG 检索与五业务实体语义检索完全体**：
   - 知识库文档：混合检索（向量 + MySQL FULLTEXT ngram 稀疏）+ RRF 融合，切分支持 parent_chunk_id 父子块；
   - 业务数据五实体语义检索：已全覆盖客户公司（`CUSTOMER`）、商业跟进活动（`BUSINESS_ACTIVITY`）、销售商机（`SALES_OPPORTUNITY`）、销售合同（`CONTRACT`，含订单明细与回款附属段）及跟进待办任务（`CONTACT_TASK`，含富文本详情），统一汇入 Qdrant `crm_entity` 集合；
   - 全站增量异步化统一：全仓 5 实体 6 个 ServiceImpl 全部升级为事务后异步非阻塞派发（`embeddingTaskExecutor`），消除主业务接口延迟；
   - 严格四档数据权限闸（NONE 零外发 / SELF / DEPT / DEPT_AND_CHILD / ALL）+ 结果合并前逐命中 owner/dept 纵深防御；
   - 问答引用来源高亮与跳转闭环：`AiAssistantDrawer` 覆盖 5 实体精准深度跳转路由，杜绝向 `/knowledge/chunk` 误请求 404。
2. **AI 智能商业诊断与会话交互深化（刚刚合入）**：
   - 客户 360° AI 诊断引擎：多实体数据聚合（客户/联系人/活动/商机/合同/回款/待办）；确定性规则评分（0~100 加权与四级映射 `EXCELLENT`/`HEALTHY`/`WARNING`/`CRITICAL`）+ 规则风险警示；
   - `ModelProvider` 智能商业画像摘要、流失风险点与拜访建议生成；双层安全降级，模型异常/未装配恒定回退规则文本，绝不抛 500；
   - 鉴权同锚：端点 `GET /ai/insight/customer/{companyId}` 与 AI Tool `getCustomerInsight` 同锚 `CUSTOMER_VIEW_COMPANY=117` + `DataScope` 跨部门 12002 越权拦截；
   - 会话体验：首问后异步自动提炼会话标题（<=12 字）；`AiAssistantDrawer.vue` 预置 4 枚高频业务快捷药丸指令。
3. **AI 助手工具面（已达 32 个，齐备就位）**：
   - 23 查询工具：涵盖客户诊断、商机、合同、订单、回款、发票、跟进任务、业务活动、审批、系统用户、组织架构、客户详情、综合与合同商机报表，全部权限同锚。
   - 9 草稿工具：客户、联系人、商机、合同、订单、回款、发票、跟进任务、业务活动，挂 null 起草 + 用户确认时强制校验权限。
   - 评测集：Surefire 1415 个测试全绿。
4. **权限与治理**：
   - 方法级 `@RequirePermission`，用户状态检查（冻结/离职）前置拦截，零注解端点 PENDING_DECISION 已清零；
   - Schema 漂移门禁：7 项已知豁免已注册 `KnownDriftRegistry`，CRITICAL=0。

---

## 3. 当前在途与候选队列 (Next Up Candidates)

- **刚闭环合入卡**：
  - `add-ai-customer-360-insight`（客户 360° AI 智能商业诊断与会话交互深化，master@ecd308c，基线 1415/116）
  - `add-unified-business-semantic-retrieval`（统一业务语义检索与全站增量异步化，master@993e91c）
  - `add-sales-opportunity-semantic-retrieval`（销售商机语义检索与异步增量保障，master@22e7397）
  - `add-business-activity-semantic-retrieval`（商业活动语义检索与权限过滤，master@323ad97）
- **候选池队列与三阶段路线图（详见 `work/_paa-candidates-pool.md` §6）**：
  - **阶段 1 · 敏捷治理批（即刻可做，低风险）**：
    1. OpenSpec 在途 5 提案目录归档至 `archive/`（`extend-ai-draft-and-eval`、`extend-ai-query-tools-c`、`add-business-activity-semantic-retrieval`、`add-sales-opportunity-semantic-retrieval`、`add-unified-business-semantic-retrieval` 以及刚完工的 `add-ai-customer-360-insight`）；
    2. `scripts/tests/review-mechanical-check.sh` §4 判据恒真修复（OBS-1，C 档治理小卡）；
    3. 统一向量运维同步端点收口（`POST /knowledge/admin/sync/{entity}` 与 `POST /knowledge/admin/sync/all`）；
  - **阶段 2 · AI 主动赋能（客户 360° 诊断已完工，后续深化）**：
    1. 智能销售跟进建议一键转 Action（与现有 AiAction 确认流打通）；
    2. 知识库与 CRM 多模态图文混合问答强化；
  - **阶段 3 · CRM 业务闭环扩充（功能完整度）**：
    1. 客户公海池与自动回收机制（超时无跟进自动释放私海客户至公海 + 认领配额/分派）；
    2. 合同履约到期预警与逾期应收账款催款待办联动；
  - **长期储备**：在线考试子域（`work/_online-exam-backlog.md` 留档）、用户数据隐私脱敏增强（MINOR-3 留档）。

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责发现验证、方案架构、写任务卡、复核裁决；**不写业务代码**。
2. **硬红线**：严禁未经 Owner 授权执行 `git push`、改写历史、生产外呼、修改库结构或破坏性改动。
3. **任务卡派发标准**：
   - 严格采用 [`work/README.md`](../work/README.md) 的“目标约束型”短任务卡（< 40 行）；
   - **严禁**在任务卡中编写微观代码替换字符串或单行 PowerShell 脚本（管目标约束，不管语法微操）。
4. **复核强度分档（`docs/review-intensity-policy.md`）**：
   - **C 档**（文档/日志/归档）：单遍核验即过，**绝对不派复核 Agent**；
   - **B 档**（常规业务代码+测试）：依靠门禁脚本退出码 0，主 Agent 审查 diff 裁决；
   - **A 档**（DB迁移/权限变更/真跑/契约）：才启动独立复核 Agent。
5. **子 Agent 派发纪律（Owner 亲发纪律）**：
   - 执行子 Agent 与复核子 Agent **均由 Owner 亲自派发**，严禁主 Agent 自行调用工具代派或绕过 Owner 发起；
   - **提示词提供机制**：主 Agent 在立项与执行停步阶段，负责生成清晰自足的《执行提示词》与《复核提示词》（Markdown 代码块）交付 Owner；
   - Owner 将提示词复制并派发给子 Agent，子 Agent 产出报告后再由 Owner 转回主 Agent 推进裁决与合并闭环。

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起）

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **当前主 Agent 结束会话前必须做**：
  1. 若有新卡合入或基线变动，更新本看板的 §1、§2、§3；
  2. 若产生新的任务卡，确保合入后归档至 `work/archive/closed-cards/`；
  3. 严禁在本文件下方追加流水账，保持全文在 120 行内整体覆盖保存。
- **新主 Agent 启动第一动作**：
  1. 读取 `AGENTS.md` + 本文件（1分钟内完成背景建立）；
  2. 实测 `git status --short` 核实工作区清洁度；
  3. 立即响应用户需求，零冗余开工！
