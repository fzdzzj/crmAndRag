# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-15  
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！  
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@8ff54e5`（已合入 `add-contract-warning-and-overdue-reminder`）
- **测试基线**：Surefire 1434 / Failsafe 117（7 skipped），`scripts/test-baseline.txt` 权威对账
- **门禁状态**：四静态全 0 违规，聚合门禁 `merge-gate.sh` 8 项全 PASS（前端 179 unit tests pass，基线 1434 份单测全绿）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **合同履约到期预警与逾期应收催款待办联动（刚刚合入）**：
   - 规则与统计模型：`ContractMapper` 扩展到期/超期（`contract_status=1` 且 30 天内或已超期）与逾期未结回款（`totalAmount > paidAmount`）统计；
   - 待办自动化联动与幂等防重：`ContractWarningService` 为合同负责人自动创建高优先级 `ContactTask`；`hasInProgressTask` 校验未结任务，彻底杜绝定时任务重复轰炸；
   - 定时调度与 API 端点：`ContractWarningScheduler` 每天凌晨 3 点自动运行；端点 `/contract/warning/*` 挂载 `SALES_VIEW_CONTRACT(215)` / `SALES_UPDATE_CONTRACT(214)`，`CONTROLLER_REGISTRY` 闭环；
   - 前端集成：`contract.page.vue` 增设到期/欠款预警快捷筛选与一键触发生成待办按钮。
2. **CRM 客户公海池与全生命周期流转闭环**：
   - 数据基座：Flyway V41 增加 `pool_status/pool_release_time/pool_release_reason/last_activity_time` 组合索引，新建 `customer_pool_log` 流转记录表；
   - 业务流转：专用公海分页查询（避开私海切面），业务员自主认领（私海配额 50 个上限拦截 20003）、主动退回、主管分派，事务后异步同步向量；
   - 超时自动回收与活跃度联动：`CustomerPoolRecycleScheduler` 每天定时自动释放超时私海客户回公海；`BusinessActivity` 新增联动刷新 `last_activity_time`；
   - 权限与界面：6 端点全部复用 113/116/117 既有权限常量；前端 `pool.page.vue` 公海池专属视图与交互弹窗闭环。
3. **AI 智能商业诊断与会话交互深化**：
   - 客户 360° AI 诊断引擎：多实体数据聚合 + 确定性规则评分（0~100 加权与四级健康度映射）+ 规则风险警示；
   - `ModelProvider` 智能商业画像摘要、流失风险点与拜访建议生成；双层安全降级杜绝 500；端点与 AI Tool 同锚 `117`；
   - 会话首问后异步自动提炼会话标题（<=12 字）；`AiAssistantDrawer.vue` 预置 4 枚高频业务快捷药丸指令。
4. **RAG 检索与五业务实体语义检索完全体**：
   - 知识库文档混合检索（向量 + FULLTEXT ngram 稀疏）+ RRF 融合，支持父子块切分；
   - 5 大业务实体语义检索：客户公司、商业活动、销售商机、销售合同及跟进任务，统一汇入 Qdrant `crm_entity` 集合；全站增量更新事务后异步非阻塞派发（`embeddingTaskExecutor`）；
   - 严格四档数据权限闸 + 结果合并前逐命中 owner/dept 纵深防御；前端来源高亮与深度路由跳转。
5. **AI 助手工具面与权限治理**：
   - 32 个 AI 工具（23 查询 + 9 草稿）；方法级 `@RequirePermission`，用户状态检查前置，零注解端点 PENDING_DECISION 清零；
   - Schema 漂移门禁：`KnownDriftRegistry` 7 项已知豁免在册，真库 CRITICAL=0。

---

## 3. 当前在途与候选队列（基于 2026-10-15 架构调研重整）

- **在途待合入卡**：
  - `add-lead-management-and-conversion-funnel`（销售线索管理与一键转化商机漏斗，已立项两件套与任务卡就绪，待执行/合入，A 档 Flyway V42）
- **核心待办队列（详见 `work/_paa-candidates-pool.md` §7 最新调研笔记）**：
  - **方向 1 · AI 主动赋能高价值闭环（强烈推荐下一卡）**：
    - `add-ai-insight-to-action-conversion`：打通 `Insight → Suggestion → PendingAction → Confirm`，将客户 360° 诊断给出的拜访与催款建议一键转化为待办/活动 Action，消费现成 11 个执行器；
  - **方向 2 · CRM 核心业务闭环**：
    - 销售线索转化漏斗（在途推进中）；客户流失预警与再激活自动化任务流；
  - **方向 3 · 平台与运维治理（敏捷小卡）**：
    - 统一向量运维中心收口（`POST /knowledge/admin/sync/*`）；
    - OpenSpec 在途 7 目录归档批次；
    - `scripts/tests/review-mechanical-check.sh` §4 判据修复（OBS-1）；
    - `queryUser` 数据权限纵深防御（保护企业内部人员 PII）。
- **长期储备**：在线考试子域（`work/_online-exam-backlog.md` 留档）、多模态图文混合检索。

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
