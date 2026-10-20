# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-20（expand-rag-eval-blind-spots 评测三盲区 B 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@546b7c7 + 本笔（M2 归档看板笔）`（本卡闭环：基点 `3480c0c` → 分支 3 笔 `41e1c3d` / `6782edd` / `d21ab0e` → M1 merge --no-ff `546b7c7`（零冲突，分支保留）→ M2 归档看板笔即本笔）
- **测试基线四元组**：Surefire **报告 292 / 用例 1827 / F0 / E0 / S0**；Failsafe **报告 32 / 用例 119 / F0 / E0 / S7**（只增不减：本卡前为 290/1818/0、32/119/7；+2 报告、+9 用例）。`scripts/test-baseline.txt` = `source-revision=546b7c7`、`measured-at=2026-10-20T09:01:37Z`（树净单次 `--update` + 校验模式 `EXIT=0`）
- **验证路径 = M1 树等价快车道（本轮首次启用）**：`git rev-parse "M^{tree}"` 与 `git rev-parse "d21ab0e^{tree}"` 同为 `543771530c10ad6b202cc848a70e1399a5268c90`，**逐字节相等** → 判定树等价，执行端（`work/archive/closed-cards/handoff-expand-rag-eval-blind-spots.md` §5/§7）已在该树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS** + `merge-gate.sh` **10/10 PASS** + 基线 `EXIT=0`，证据随树等价必然可迁移 → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账（`--update` 源修订=M + 校验 EXIT=0）。**本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底：CI 绿 = M1 验证闭环**
- **CI**：M1 合并轮 run `39397727448`（head `546b7c7`，`--exit-status` 结论见 `docs/main-agent-logbook.md` §136）；M2 看板轮 CI 号亦记于 §136（双 CI 号）
- **复核**：**B 档** —— 门禁退出码 0 + 主 Agent 审 diff 裁决，**不派独立复核**；归档三件 = proposal + tasks（`openspec/changes/archive/expand-rag-eval-blind-spots/`）+ handoff + 任务卡（`work/archive/closed-cards/`）
- **环境实测**：本卡执行端 `clean verify` 时 Docker 29.6.2 在线；M2 归档轮 `docker info` 实测**离线**（本卡 M1 快车道与 M2 全程不需 Docker，无影响）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **RAG 评测三盲区补齐（expand-rag-eval-blind-spots，B 档，刚刚合入）**：多轮 / 时序 / 拒答三点合一，零真外呼 / 零迁移 / 零依赖 / 零新错误码 / 前端零 diff，既有 63 例与指标公式零改动：
   - **多轮**：`RagBenchmarkCase` 增第 4 分量 `List<HistoryTurn> history`（6 参兼容重载 → 既有用例零改动）、`AiEvalCase` 增第 6 分量 `List<AiEvalTurn> history`（5 参重载 + loader 可选 `history` 解析）；新增标准套件多轮组 **MT-01~05**（指代消解 3 / 上下文追问 2 / 澄清触发 1）与 AI 工具选择多轮组 3 例（`ai-eval/cases.json` 40→43）；`AiEvalRunner` 摘要行追加 `multiTurnCases=N`
   - **时序**：`Category` 增 `TEMPORAL` + **TP-01~04**（锚定语料既有"每季度复核/次月/单月内/季度冲刺奖励"块）；`SUITE_VERSION` **5.0 → 6.0**，标准套件 **63 → 72**（TEXT 17→21 / LEXICAL 14→15 / TEMPORAL 0→4；TABLE 13 / IMAGE 6 / EDGE 4 / MISMATCH 9 不变）；黄金锚全复用既有 44 占位 id → `chunkCount=57` / `fixtureCount=15` 不变（语料零改动实证）
   - **拒答**：`RagQualityReport.CaseOutcome` 增第 8 分量 `boolean refused`（7 参重载默认 false）；D16 收紧 —— `expected` 空时 `ansC = refused ? 1 : 0`（诚实拒答得 1，借近邻编造得 0，**未填报不再默认放行**）；`recall/precision/MRR/citationPrecision` 公式与方法体零改动
   - **fixtureRegression 随版再生成**（唯一合法写入口 `-Drag.baseline.regen=true`）：`suiteVersion` 5.0→6.0、`caseCount` 63→72、`recallAt5` 0.8386→0.8310、`mrr` 0.7116→0.7095；下降来自 9 例省略式/时间限定问句在字符 n-gram 伪向量下的天然低分，非手改
   - **v6.0 真跑锚点 `baseline-v7.json` 缺席，属另卡授权**（沿 v3.0「暂未授权真跑落锚」先例）；若将来真跑，6.0 报告会与 v3~v6 锚点的 `suiteVersion` 失配而红 —— 这是「跨版本不可比」的预期行为，须由落锚卡处理
2. **五路语义同步 hook 收敛（fix-semantic-hook-support-unify，B 档）**：五个 `*SemanticHookSupport` 收敛为「一个基类 + 五个薄门面」，`runAfterCommitAsync` 仅一份实现，`reingestById` 泛型化为函数式注入（软删/物理删谓词由各域门面显式注入，基类不二次判空，谓词 null 安全契约进 Javadoc + 五域用例固定），调用点零改动。详见其归档 handoff。
3. **ProjectFile 域收口四点合一（fix-projectfile-txn-and-batch-insert，B 档）**：三上传合一 + 磁盘 IO 出事务（短事务 `insertBatch` + 失败全量清盘补偿）；附件 saveBatch 先盘后库 + 补偿；备注先校验后 `insertBatch`；活动创建双表批量。详见其归档 handoff。
4. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档 / 拍板形态 C）**：删 `SALES_STAGE_APPROVAL_RELATIONS → ApprovalAttachmentEntity` 死边；商机删除在 `deleteCascade` 前显式补删审批附件；跨模型同 `and_id` 回归网。
5. **循环写收口批 A-6 / A-8 / A-10（fix-engineering-debt-loop-write-batch）**：级联删 N 轮→1 轮；关联维护 2N→2；用户批删单条化并修复 MP `NOT_NULL` 策略下 `setEmail(null)`/`setPhone(null)` 被丢弃的 PII 清理缺陷。
6. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone）**：分页 N+1 收敛 3N+1→2；双软删循环 2N→1；`SQLExceptionHandler` 7 项列名映射迁入 `application.yml`。
7. **待办池 MINOR 家族合卡（M-1 四形态收敛 / 摄取 96004 / 公海 assign 闸 / release 置空 / TokenBudget volatile / 近义码）**：`BaseException(String)` 四形态 95/95 收敛至 `ErrorCode` + 防回归门禁；摄取 `ContentRiskBlockedException` 收口 96004 与 100MB 有界读；assign 目标用户存在/在职闸前置行锁；`TokenBudgetProperties` 三字段 volatile。
8. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传前置 canWriteAttachments 属主鉴权 12002；重置密码 DTO 校验收口 + 非超管禁改超管密码闸；invoice_info 纳管 DataScope 与 voidInvoice 属主闸。
9. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`deleteFile` 软删联动向量与 chunk 物理删；补齐 `assertCanReadContract`/`assertCanReadTask`；`merge-gate.sh` 8 门→10 门。
10. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）**：`UserPasswordSupport` 严格校验旧密（92028）、拒绝新旧同密；`PrivacyAspect` 补齐脱敏路径。
11. **用户自服务防提权越权加固（B-1）**：`updateMyInfo` 对 `roleId`/`deptId`/`status` Fail-Closed 拦截（12002），SET 子句走白名单。
12. **单副本单体部署契约与架构守卫硬化（I-4）** / 13. **数据权限切面 Fail-Closed 治理（I-1）**：`replicas: 1` + `PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true`；`QueryWrapperAspect` 异常重抛 12002 不静默放行。
14. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单收敛；`POST /user/logout` + Caffeine 本地黑名单与冻结/离职即时感知；`LoginAttemptService` 双维度防爆破。
15. **商机阶段流转审批阻断（D4）** / 16. **确认链路写权限再审计（D6）**：无审批记录抛 30003；`PendingActionConfirmPermissionAuditTest` 从业务入口穿透归属校验与终态判定。
17. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；动态配置键 66→60。
18. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE；50 上限以 sys_user 行锁串行化；锁序「先用户后客户」零死锁面。
19. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller）。
20. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)。
21. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → 幂等建高优待办 → 每日 4 点错峰调度。
22. **CustomerInsightPanel 双宿主接线** / 23. **AI 诊断建议一键转 Action**：客户详情弹窗与 AI 助手抽屉双宿主；`InsightToActionService` 纯结构化映射（零外呼）。
24. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点；单事务转化客户+联系人+商机并异步向量同步。
25. **合同履约到期预警与逾期应收催款待办联动**：到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度。
26. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003。
27. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合、父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（expand-rag-eval-blind-spots 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master；物料归档至 `openspec/changes/archive/expand-rag-eval-blind-spots/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡；B 档无独立复核报告））
- **待办池累计十一条（本卡不新增、不消化，条目不增不减）**：
  1. `new BaseException (` 空格形态加固（格式化回滚风险）；
  2. release 置空真库回环用例；
  3. TokenBudget 跨线程用例区别力；
  4. `MessageConstant` 9 处码断言；
  5. 「已存在」精确码产品评估（50002 系近义）；
  6. A-7 null id 显式用例（`UserHandoverServiceImpl#collectUserId` 三类 null 跳过分支）；
  7. `SQLExceptionHandler` 历史缺陷收口（`:48` 裸索引名越界 + 裸列名丢字段名 + 列名/索引名共用 Map 的键空间隔离）；
  8. 关联维护 4 DTO null-id 校验：`BusinessActivityDTO` / `BusinessActivityUserDTO` / `BusinessActivityContactDTO` / `AddActivity{User,Contact}RequestDTO` 的 id 字段均无 `@NotNull`；
  9. 批量 IN 长列表 controller 分批硬上限：级联/关联批量入口把 id 全量塞进 `IN`，宜在 controller 层加分批与 row 上限；
  10. **【复核 I4】`CustomerCompanyServiceImpl:303-309` afterCommit 型循环收口**：`batchLogicalDeleteByIds` 仍是 `for (id) logicalDeleteById(id)` 且无 `PARAM_EMPTY` 闸；
  11. **【附件孤儿债】**：`business_activity` / `contact_task` / `assist_request` 删除路径不删附件（父对象物理级联删绕开 canonical 附件清理），另含 `ApprovalAttachmentServiceImpl#removeByIds` 按 `model_name` 选行却按裸 id 删行的跨模型风险。
- **池账闭合登记**：**池账 #26（RAG 评测三盲区，C-3 多轮 / C-4 时序 / C-5 拒答三点合一）已随本卡闭环出账**；连同已成既往的 #14 / #24 / #25，池账口径继续以各卡任务卡「来源」字段为准。
- **本卡偏差与观察点**（论证见归档 handoff §10）：D1 多轮组**未新增类别**（卡只授权 `Category` 增 TEMPORAL，多轮维度由 `history` 组件 + `MT-` 编号表达，二者正交；独立 `MULTI_TURN` 类目属另卡）；D2 **未新增时效语料块**（TEMPORAL 复用既有含时效语义的块，以守住「既有 63 例与语料零改动」，故 chunkCount/fixtureCount 不变是有意选择）；D3 任务卡此前往 `work/` 根未跟踪、本轮随归档入库（`.gitignore` 对 `work/*` 有反向例外、`closed-cards/` 需 `git add -f`）；D4 `AiEvalRunner` 只做到「用例整体含 history 传入 predictor + 摘要留痕」，未改指标公式与报告结构（判别力由 `AiEvalMultiTurnTest` 双向锁死）。
- **下批推荐**：池账 **#27 引用对齐阈值动态键 + 评测口径对齐（C-2，B 档）**，或**待办池十一条消化**，或按 Owner 指令插队。
- **下批候选池总账**：`work/_paa-candidates-pool-v2.md`（观察树未跟踪留档，长期储备保留）。
- **长期储备**：在线考试子域（`work/_online-exam-backlog.md` 留档）、多模态图文混合检索、**v6.0 真跑落锚 `baseline-v7.json`（需另卡授权）**。

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责发现验证、方案架构、写任务卡、复核裁决；**不写业务代码**。
2. **硬红线**：严禁未经 Owner 授权执行 `git push`、改写历史（reset/rebase/amend/force-push）、删分支、生产外呼、修改库结构或破坏性改动。
3. **任务卡派发标准**：严格采用 [`work/README.md`](../work/README.md) 的"目标约束型"短任务卡（< 40 行），严禁微观代码语法微操。
4. **复核强度分档（`docs/review-intensity-policy.md`）**：**C 档**（文档/日志/纯测试加固）主 Agent 审查 diff 与机械门禁、不派复核；**B 档**（常规业务代码+测试）依靠门禁脚本退出码 0 + 主 Agent 审 diff 裁决；**A 档**（DB迁移/权限变更/真跑/契约）才启动独立复核 Agent。
5. **子 Agent 派发纪律（Owner 亲发纪律）**：执行与复核子 Agent **均由 Owner 亲自派发**，严禁主 Agent 自行代派；主 Agent 只产出《执行提示词》与《复核提示词》交付 Owner。
6. **合并闭环纪律**：合入走 `git merge --no-ff` 且**保留分支**；合并后先做**树等价判等**（`M^{tree}` vs 分支 tip `^{tree}`），相等即可走快车道（免本地复跑，CI `build-and-gate` 兜底），不等则回退全量 clean verify + merge-gate。

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，最新 §136）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只允许 `--update` 从一次真实运行写入）

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
