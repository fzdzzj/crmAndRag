# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-15  
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！  
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@323ad97`（已合入 `add-business-activity-semantic-retrieval`）
- **测试基线**：Surefire 1314 / Failsafe 116（7 skipped），`scripts/test-baseline.txt` 权威对账
- **门禁状态**：四静态（checkstyle/spotbugs/spotless/pmd）全 0 违规，聚合门禁 `merge-gate.sh` 8 项全 PASS（前端 179 unit tests pass）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **RAG 检索与双业务实体语义检索**：
   - 知识库文档：混合检索（向量 + MySQL FULLTEXT ngram 稀疏）+ RRF 融合，切分支持 parent_chunk_id 父子块；
   - 业务数据双实体语义检索：已覆盖客户公司（`CUSTOMER`）与商业跟进活动（`BUSINESS_ACTIVITY`），独立落库 Qdrant `crm_entity` 集合；
   - 严格四档数据权限闸（NONE 零外发 / SELF / DEPT / DEPT_AND_CHILD / ALL）+ 结果合并前逐命中 owner/dept 纵深防御；
   - 问答引用来源高亮与跳转已闭环（`add-source-citation-and-dashboard`）。
2. **AI 助手工具面（已达 31 个，齐备就位）**：
   - 22 查询工具：涵盖客户、商机、合同、订单、回款、发票、跟进任务、业务活动、审批、系统用户、组织架构、客户详情、综合与合同商机报表，全部权限同锚。
   - 9 草稿工具：客户、联系人、商机、合同、订单、回款、发票、跟进任务、业务活动，挂 null 起草 + 用户确认时强制校验权限。
   - 评测集：AiEval 40 个用例（含边界辨析），全绿通过。
3. **权限与治理**：
   - 方法级 `@RequirePermission`，用户状态检查（冻结/离职）前置拦截，零注解端点 PENDING_DECISION 已清零；
   - Schema 漂移门禁：7 项已知豁免已注册 `KnownDriftRegistry`，CRITICAL=0。

---

## 3. 当前在途与候选队列 (Next Up Candidates)

- **刚闭环合入卡**：
  - `add-business-activity-semantic-retrieval`（商业活动语义检索与权限过滤，master@323ad97）
  - `extend-ai-query-tools-c`（C批4查询工具+40评测集，master@a34451f）
  - `extend-ai-draft-and-eval`（2草稿工具+34评测集，master@cd62301）
  - `extend-ai-query-tools`（B批6查询工具，master@3f754ac）
- **候选池队列（参考 `work/_paa-candidates-pool.md` §5）**：
  1. `scripts/tests/review-mechanical-check.sh` §4 判据恒真修复（OBS-1，C 档治理小卡，防止基线漂移被静默吞掉）；
  2. OpenSpec 在途归档批次（将已合入的 `extend-ai-draft-and-eval`、`extend-ai-query-tools-c`、`add-business-activity-semantic-retrieval` 移至 `archive/`）；
  3. 用户数据隐私增强（MINOR-3 留档：为 `findPage` 加 `@Privacy` 需 A 档并经 owner 拍板）；
  4. 销售商机（SalesOpportunity）语义检索接入（业务 RAG 第三实体扩展）；
  5. 在线考试子域（`work/_online-exam-backlog.md` 留档）。

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
