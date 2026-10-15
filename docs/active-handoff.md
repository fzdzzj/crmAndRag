# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-15  
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！  
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@c478857`（已合入 `add-customer-public-sea-pool`）
- **测试基线**：Surefire 1427 / Failsafe 117（7 skipped），`scripts/test-baseline.txt` 权威对账
- **门禁状态**：四静态全 0 违规，聚合门禁 `merge-gate.sh` 8 项全 PASS（前端 179 unit tests pass，Flyway V41 Docker 绿，SchemaDrift 0 漂移）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **CRM 客户公海池与全生命周期流转闭环（刚刚合入）**：
   - 数据基座：Flyway V41 增加 `pool_status/pool_release_time/pool_release_reason/last_activity_time` 组合索引，新建 `customer_pool_log` 流转记录表；
   - 业务流转：专用公海分页查询（避开私海切面），业务员自主认领（私海配额 50 个上限拦截 `CUSTOMER_POOL_QUOTA_EXCEEDED=20003`）、主动退回、主管分派，事务后异步同步向量；
   - 超时自动回收与活跃度联动：`CustomerPoolRecycleScheduler` 每天定时自动释放无跟进超期私海客户回公海；`BusinessActivity` 跟进记录新增联动刷新 `last_activity_time`；
   - 权限与界面：6 端点全部复用 113/116/117 既有权限常量；前端 `pool.page.vue` 公海池专属视图与退回/分派交互弹窗闭环。
2. **AI 智能商业诊断与会话交互深化**：
   - 客户 360° AI 诊断引擎：多实体数据聚合 + 确定性规则评分（0~100 加权与四级健康度映射）+ 规则风险警示；
   - `ModelProvider` 智能商业画像摘要、流失风险点与拜访建议生成；双层安全降级杜绝 500；端点与 AI Tool 同锚 `117`；
   - 会话首问后异步自动提炼会话标题（<=12 字）；`AiAssistantDrawer.vue` 预置 4 枚高频业务快捷药丸指令。
3. **RAG 检索与五业务实体语义检索完全体**：
   - 知识库文档混合检索（向量 + FULLTEXT ngram 稀疏）+ RRF 融合，支持父子块切分；
   - 5 大业务实体语义检索：客户公司、商业活动、销售商机、销售合同及跟进任务，统一汇入 Qdrant `crm_entity` 集合；
   - 全仓 5 实体 6 个 ServiceImpl 全部升级为事务后异步非阻塞派发（`embeddingTaskExecutor`），消除主业务接口延迟；
   - 严格四档数据权限闸 + 结果合并前逐命中 owner/dept 纵深防御；前端来源高亮与深度路由跳转。
4. **AI 助手工具面与权限治理**：
   - 32 个 AI 工具（23 查询 + 9 草稿）；方法级 `@RequirePermission`，用户状态检查前置，零注解端点 PENDING_DECISION 清零；
   - Schema 漂移门禁：`KnownDriftRegistry` 7 项已知豁免在册，真库 CRITICAL=0。

---

## 3. 当前在途与候选队列 (Next Up Candidates)

- **刚闭环合入卡**：
  - `add-customer-public-sea-pool`（客户公海池与全生命周期流转机制，master@c478857，基线 1427/117）
  - `add-ai-customer-360-insight`（客户 360° AI 智能商业诊断与会话交互深化，master@ecd308c）
  - `add-unified-business-semantic-retrieval`（统一业务语义检索与全站增量异步化，master@993e91c）
- **候选池队列与三阶段路线图（详见 `work/_paa-candidates-pool.md` §6）**：
  - **阶段 3 · CRM 核心业务闭环（首推下一卡）**：
    1. 合同履约到期预警与逾期应收账款催款待办联动；
    2. 轻量线索（Lead）转客户商机漏斗。
  - **阶段 1 · 敏捷治理批（可穿插，低风险）**：
    1. OpenSpec 在途 6 提案目录归档至 `archive/`；
    2. `scripts/tests/review-mechanical-check.sh` §4 判据恒真修复（OBS-1，C 档治理小卡）；
    3. 统一向量运维同步端点收口（`POST /knowledge/admin/sync/*`）。
  - **阶段 2 · AI 主动赋能（深化）**：
    1. 智能销售跟进建议一键转 Action（打通 AiAction 确认流）；
    2. 知识库与 CRM 多模态图文混合问答强化。
  - **长期储备**：在线考试子域（`work/_online-exam-backlog.md` 留档）、用户数据隐私脱敏增强（MINOR-3 留档）。

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责发现验证、方案架构、写任务卡、复核裁决；**不写业务代码**。
2. **硬红线**：严禁未经 Owner 授权执行 `git push`、改写历史、生产外呼、修改库结构或破坏性改动。
3. **任务卡派发标准**：严格采用 [`work/README.md`](../work/README.md) 的“目标约束型”短任务卡（< 40 行），严禁微观代码语法微操。
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
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V42）
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
