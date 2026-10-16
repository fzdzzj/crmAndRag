# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-16（当日六卡）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@93e6c7f`（本卡 `fix-review-mechanical-check-true` merge 笔 `93e6c7f`，2+1 笔，C 档机械核对 GO）
- **测试基线**：Surefire 1484 / Failsafe 118（7 skipped），`scripts/test-baseline.txt` 权威对账（本卡 Java 侧零改动，基线持平）
- **门禁状态**：四静态全 0 违规，聚合门禁 `merge-gate.sh` 8/8 全 PASS（Docker 在线加跑 `--with-verify` 9/9：surefire 1484 单测 / 前端 197 用例，Docker MySQL V1~V42 真库迁移全绿（下一可用号仍 V43），SchemaDrift / PermissionCoverage 双 0 CRITICAL）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **OBS-1 机械核对脚本吞错修复（刚刚合入）**：`scripts/tests/review-mechanical-check.sh` 移除 3 处真吞错（`g()` 包装 `|| true` / master-tree 两行 / 末尾 `exit 0`），核对失败经子 shell 临时文件累计真实传非 0 退出码；保留 2 处 `grep -c` 合法 0 匹配不动；维持非门禁定位（不接 CI / merge-gate）；两向自测 0/2 留证；纯 scripts 修复 Java 侧零改动基线持平；「恒 exit 0」过期口径已同步 AGENTS.md 与 `docs/review-intensity-policy.md` §五。
2. **queryUser 数据权限部门隔离**：AI 工具 queryUser 按当前操作用户 deptId 过滤（仅同部门可见，非豁免无部门 fail-closed 空列表）；豁免对齐既有口径（超管 roleId=1 全量，三条依据 PermissionsInterceptor L97 / DataScopeLevelResolver L43-46 / DataScopeUserScopeGuard L26-27）；工具描述同步防幻觉；零迁移/零权限常量/零冻结契约/零前端改动；surefire +4（四向单测：同部门/跨部门/豁免/无部门，@AfterEach 防 ThreadLocal 泄漏）。
3. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验（customer/contract/contactTask/businessActivity/salesOpportunity，非法 400）+ `/sync/all` 逐实体委托返回计数映射；两端点复用 KNOWLEDGE_ADMIN_MANAGE(900)；前端知识管理页运维卡片（五实体入口 + 最近同步结果 + 确认弹窗）；单测全 mock 零真实重嵌入（首次真实调用须 Owner 授权）；旧散落 reingestAll 端点全保留零改动（diff 最小决策留痕）；Playwright 检查点 +4；surefire +8、前端 +9。
4. **客户流失预警与自动化再激活**：确定性规则（三源皆空：已完成跟进/商业活动/合同回款 + @Value 60 天窗口）扫描私海客户 → hasInProgressTask 幂等建高优待办（描述含诊断→Insight→Action 引导）→ 每日 4 点错峰调度；POST/GET /customer/churn/warning/* 复用 117/113；前端客户列表流失筛选 + 一键触发 + 3 Playwright 检查点；零 LLM 零迁移。
5. **CustomerInsightPanel 双宿主接线**：客户详情弹窗（CompanyEditModal 只读路径）嵌入「客户诊断」区 + AI 助手抽屉上下文诊断面板（路由 query filters.id 优先 / 对话内 CUSTOMER 来源兜底，📊 药丸联动），created 反馈与刷新双宿主齐备；Insight → Action 最后一公里收口；+4 Vitest 至 188 用例。
6. **AI 诊断建议一键转 Action**：后端 InsightToActionService 纯结构化映射（零外呼），诊断 actionSuggestions[index] → 复用既有草稿 DTO → PendingActionService.submitDraft 返回 pendingId；端点 POST /ai/insight/customer/{companyId}/to-action 挂 CUSTOMER_VIEW_COMPANY(117)，actionType 仅 CREATE_CONTACT_TASK / CREATE_BUSINESS_ACTIVITY；前端 CustomerInsightPanel.vue 转待办/转活动 + hook + 5 Vitest。
7. **销售线索管理与一键转化商机漏斗**：
   - 数据基座：Flyway V42 新建 `sales_lead` 线索表（意向等级/获取渠道/转化回填字段组）；
   - 业务闭环：`/sales/lead` 6 端点（录入/详情/分页/更新/删除/转化）复用既有 111/113/114/116/117 权限常量；
   - 一键转化：`convert` 单 `@Transactional` 内创建客户公司 + 首要联系人 + 可选商机并回填转化状态，事务后经 `runAfterCommitAsync` 异步派发向量同步；
   - 前端 `lead.page.vue`：线索分页列表、状态徽标筛选、录入与转化对话框闭环。
8. **合同履约到期预警与逾期应收催款待办联动**：
   - `ContractMapper` 扩展到期/超期与逾期未结回款统计；`ContractWarningService` 幂等生成高优先级 `ContactTask`；
   - `ContractWarningScheduler` 每天凌晨 3 点运行；端点 `/contract/warning/*` 挂载 215/214；前端 `contract.page.vue` 预警快捷筛选与一键生成待办。
9. **CRM 客户公海池与全生命周期流转闭环**：
   - Flyway V41 组合索引 + `customer_pool_log` 流转表；公海查询避开私海切面，认领配额 50 上限拦截 20003；
   - `CustomerPoolRecycleScheduler` 每天回收超时私海；`BusinessActivity` 联动刷新活跃度；前端 `pool.page.vue` 专属视图。
10. **AI 智能商业诊断与会话交互深化**：
    - 客户 360° AI 诊断引擎：多实体聚合 + 确定性规则评分（0~100 与四级健康度）+ 风险警示；
    - `ModelProvider` 商业画像摘要与拜访建议，双层安全降级杜绝 500；会话首问后异步提炼标题 + 4 枚快捷药丸指令。
11. **RAG 检索与五业务实体语义检索完全体**：
    - 知识库混合检索（向量 + FULLTEXT ngram）+ RRF 融合，支持父子块切分；
    - 5 大业务实体语义检索汇入 Qdrant `crm_entity`，事务后异步派发；严格四档数据权限闸 + 逐命中纵深防御。
12. **AI 助手工具面与权限治理**：
    - 32 个 AI 工具（23 查询 + 9 草稿）方法级 `@RequirePermission`，用户状态检查前置；
    - Schema 漂移门禁：`KnownDriftRegistry` 豁免在册，真库 CRITICAL=0。

---

## 3. 当前在途与候选队列（基于 2026-10-16 治理三卡收官刷新）

- **在途待合入卡**：无
- **治理三卡序列（本轮）**：**全部闭环清空**——
  1. `unify-vector-ops-endpoints`（✅ 已闭环合入 master@8d24c06）
  2. `add-queryuser-dept-isolation`（✅ 已闭环合入 master@51028c2）
  3. `fix-review-mechanical-check-true`（✅ 已闭环合入 master@93e6c7f）
- **核心待办队列**：下一批候选**待 Owner 拍板**（占位：主 Agent 立项建议已另行提请；调研笔记见 `work/_paa-candidates-pool.md` §7）：
  - **方向 1 · AI 主动赋能高价值闭环**：
  - **方向 2 · CRM 核心业务闭环**：
  - **方向 3 · 平台与运维治理（敏捷小卡）**：OpenSpec 归档批次。
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
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43）
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
