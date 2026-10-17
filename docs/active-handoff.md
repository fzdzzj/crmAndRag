# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-17（D6 confirm 权限审计测试 + D4 商机阶段审批阻断 双卡闭环）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@2e114a6`（D6 merge `085076c` + D4 merge `5701978` + 基线 `2e114a6`，CI Run 39032399836 & 39034368027 均 6/6 全绿）
- **测试基线**：Surefire 257 份报告 / 1588 用例 / 0 失败（D6 +1 反向审计用例，D4 +4 审批阻断单测；基线 1583→1584→1588）、Failsafe 31/118/7 skipped，`scripts/test-baseline.txt` 权威对账（source-revision=5701978）
- **门禁状态**：四静态全 0 违规，聚合门禁 `merge-gate.sh` 8/8 全 PASS（surefire 1588 单测 / 前端 197 用例，Docker MySQL V1~V42 真库迁移全绿（下一可用号仍 V43），SchemaDrift / PermissionCoverage 双 0 CRITICAL）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **商机阶段流转审批阻断（D4，刚刚合入）**：`SalesOpportunityServiceImpl.update` 前置流转阻断，调用 `SalesStageApprovalSupport.isValidStageTransition` 判定合法正向需审批阶段流转，严格等价匹配审批记录 `targetStage`；无审批记录阻断抛出 30003 `OPPORTUNITY_MUST_BE_CLOSED`；实体不存在/阶段未变/倒流放行；+4 四向单测；基线 1584→1588。
2. **确认链路写权限再审计测试加固（D6，刚刚合入）**：`PendingActionConfirmPermissionAuditTest` 从 `PendingActionServiceImpl.confirm` 业务入口穿透实体归属校验与终态判定，无权限时 fail-closed——三重断言：12002 `PERMISSION_DENIED` + 实体状态保持 `PENDING` 且无 CAS 更新写 + 执行器/校验器零交互；变异测试验证非空洞；+1 单测；基线 1583→1584。
3. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口（`doStreamChat` 先查后扣，超限返回 SSE `TOKEN_BUDGET_EXCEEDED` 错误事件并 complete；默认 `enabled=false` 灰度旁路）；ContentSecurity 两处接线（对话入口改写问句 evaluate + wrap，BLOCK 抛异常转 SSE `CONTENT_BLOCKED` 不入库，DEGRADE 标签包装，ALLOW 原文；摄取入口全量缓冲 evaluate，BLOCK 零文件写零落库）；`ai.model.*` 6 个死配置键自注册表与三档封闭集彻底摘除，动态配置键总数 66→60，测试样例键换血，活代码 0 悬空；+10 单测，基线 1573→1583。
4. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE（谓词 `pool_status=1 AND is_deleted=0`，PK 定位 affected∈{0,1}）；50 上限以 sys_user 行锁串行化（`FOR UPDATE` 先锁后计数，quota+CAS+提交同事务）；锁序统一「先用户后客户」零死锁面；零迁移零 version 列；错误码复用 50002/20003；+3 用例；基线 1570→1573。
5. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller，域分组三笔 39+26+20）；DTO 校验注解按契约 required 对表零超约束；86 反向用例（28 测试类参数化）统一走全局 50002 handler；基线 1484→1570。
6. **OBS-1 机械核对脚本吞错修复**：`scripts/tests/review-mechanical-check.sh` 移除 3 处真吞错，核对失败经子 shell 临时文件累计真实传非 0 退出码；保留 2 处 `grep -c` 合法 0 匹配；维持非门禁定位；两向自测 0/2 留证；纯 scripts 修复 Java 零改动基线持平。
7. **queryUser 数据权限部门隔离**：AI 工具 queryUser 按当前操作用户 deptId 过滤（仅同部门可见，非豁免无部门 fail-closed 空列表）；豁免对齐既有口径（超管 roleId=1 全量）；工具描述同步防幻觉；零迁移/零权限常量/零冻结契约/零前端改动；surefire +4。
8. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托返回计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)；前端运维卡片与确认弹窗；单测全 mock 零真实重嵌入；surefire +8、前端 +9。
9. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → hasInProgressTask 幂等建高优待办 → 每日 4 点错峰调度；前端流失筛选与一键触发；零 LLM 零迁移。
10. **CustomerInsightPanel 双宿主接线**：客户详情弹窗与 AI 助手抽屉上下文诊断面板双宿主齐备；created 反馈与刷新双宿主联动；Insight → Action 最后一公里收口。
11. **AI 诊断建议一键转 Action**：后端 InsightToActionService 纯结构化映射（零外呼），诊断建议一键转待办/转活动，复用既有草稿 DTO；端点挂 CUSTOMER_VIEW_COMPANY(117)。
12. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点复用既有权限；单事务转化客户+联系人+商机并异步向量同步；前端列表与转化弹窗。
13. **合同履约到期预警与逾期应收催款待办联动**：ContractMapper 到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度；前端预警快捷筛选与一键生成待办。
14. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003；定时回收超时私海；商业活动联动刷新活跃度。
15. **AI 智能商业诊断与会话交互深化**：客户 360° 诊断多实体聚合规则评分（0~100）+ ModelProvider 商业画像与建议，双层安全降级杜绝 500；首问提炼标题与快捷指令药丸。
16. **RAG 检索与五业务实体语义检索完全体**：知识库混合检索（向量 + FULLTEXT ngram）+ RRF 融合，父子块切分；5 大业务实体向量语义检索汇入 Qdrant，事务后异步派发；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（D4/D6 双卡闭环刷新）

- **在途待合入卡**：**0 张！**（D4 `harden-oppo-stage-approval` 与 D6 `add-confirm-reaudit-coverage` 已全部全链合入并过 CI）
- **下批候选池总账**：`work/_paa-candidates-pool-v2.md`（104 发现 / 64 合并候选，A16/B35/C13；下批推荐立项序列：登录安全包 H-8/9/10 → I-1 数据权限 fail-open 拍板修复 → 暂缓池活化取舍）。
- **长期储备**：在线考试子域（`work/_online-exam-backlog.md` 留档）、多模态图文混合检索。

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责发现验证、方案架构、写任务卡、复核裁决；**不写业务代码**。
2. **硬红线**：严禁未经 Owner 授权执行 `git push`、改写历史、生产外呼、修改库结构或破坏性改动。
3. **任务卡派发标准**：严格采用 [`work/README.md`](../work/README.md) 的“目标约束型”短任务卡（< 40 行），严禁微观代码语法微操。
4. **复核强度分档（`docs/review-intensity-policy.md`）**：
   - **C 档**（文档/日志/纯测试加固）：主 Agent 审查 diff 与机械门禁，**不派复核 Agent**；
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
