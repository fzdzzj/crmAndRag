# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-20（fix-semantic-hook-support-unify 五路 hook 泛型化 B 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@99affa6 + 本笔（M2 归档看板笔）`（本卡闭环：基点 `c17cc18` → 分支 3 笔 `a423041` / `2392260` / `559bb23` → M1 merge --no-ff `f11833a`（零冲突，分支保留）→ 基线重锚笔 `99affa6` → M2 归档看板笔即本笔）
- **测试基线四元组**：Surefire **报告 290 / 用例 1818 / F0 / E0 / S0**；Failsafe **报告 32 / 用例 119 / F0 / E0 / S7**（只增不减：本卡前为 287/1802/0、32/119/7；+3 报告、+16 用例）。`scripts/test-baseline.txt` = `source-revision=f11833a`、`measured-at=2026-10-20T06:34:23Z`（树净单次 `--update` + 校验模式 `EXIT=0`）
- **门禁状态**：`mvn -B -ntp clean verify` **BUILD SUCCESS**（18:39 min，Docker 29.6.2 真库在线）；checkstyle（0 违规）/ spotless（1145 文件 0 待改）/ PMD / SpotBugs（BugInstance 0）四静态全过；聚合门禁 `merge-gate.sh` **10/10 PASS**（typed-router 生成物噪声 `git checkout --` 还原，树净）；本卡零迁移，下一可用迁移号仍 **V43**
- **CI**：M1 合并轮 run `39382801886`（head `99affa6`）**6/6 全绿**（`--exit-status` EXIT=0）；M2 看板轮 CI 号与结论见 `docs/main-agent-logbook.md` §135
- **复核**：**B 档** —— 门禁退出码 0 + 主 Agent 审 diff 裁决，**不派独立复核**（本卡无独立复核报告）；归档三件 = proposal/tasks（`openspec/changes/archive/`）+ handoff + 任务卡（`work/archive/closed-cards/`）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **五路语义同步 hook 收敛（fix-semantic-hook-support-unify，B 档，刚刚合入）**：五个 `*SemanticHookSupport`（合同 / 客户 / 跟进任务 / 销售商机 / 商业活动）由「五份逐字同形克隆」收敛为「**一个基类 `SemanticHookSupport` + 五个薄门面**」：① `runAfterCommitAsync` 现在**只有一份实现**（基类 `static` 承接全部事务同步逻辑，注册时机 / 回滚不派发 / 异常 WARN 兜底三轴逐字等价，日志改参数化 `domainLabel` 后**输出字节不变**）；② `reingestById` 泛型化为函数式注入 `(entityLoader, needsVectorDelete, ingestAction, deleteAction)`，**软删 / 物理删谓词分叉由各域门面显式注入**（客户 / 商机带 `isDeleted` 判、合同 / 跟进任务 / 商业活动为 `null` 即删），基类刻意**不二次判空**以免掩盖分叉，谓词 null 安全契约写进基类 Javadoc 并由五域「实体缺失」用例逐一固定；③ **调用点零改动**（diff 未触及任何 ServiceImpl 调用方），ContactTask 四个服务层便捷闸与五个 `deleteById` 全部保留（实测均有调用，无「无调用可删」候选）。新增 4 个测试类（基类四象限 7 + 客户薄门面 5 + 活动薄门面 4），既有 3 专项断言零改动；+3 报告 / +16 用例。零迁移 / 零新依赖 / 零新错误码 / 不动前端。
2. **ProjectFile 域收口四点合一（fix-projectfile-txn-and-batch-insert，B 档）**：① **A-5+A-16 三上传合一 + 磁盘 IO 出事务**：`ProjectFileServiceImpl` 三个上传方法（活动 / 订单项 / 独立上传，原约 90% 克隆且方法级 `@Transactional` 连写盘一起包）抽公共流程进 `uploadInternal` + `UploadHost` 注入点（宿主闸 / 写盘前校验 / 实体装饰各留注入），**写盘移出事务**、落库改短事务 `TransactionTemplate` 内一次 `insertBatch`（先盘后库 + 任一失败全量清盘补偿）；宿主闸与权限闸文案、空列表/分类校验顺序逐字不变，独立上传的 `opportunityId` 校验仍留在「包裹为 `FILE_READ_FAILED`」的位置；② **E-15 附件 saveBatch 先盘后库 + 补偿**：`ApprovalAttachmentServiceImpl#saveBatch` 由「循环内 writeFile + 逐条 insert、无事务无补偿」改为「先全部 `IOUtils.writeFile` 落盘 → 短事务一次 `insertBatch` → 两侧失败均清盘并**原异常上抛**」（旧为半提交残留），null/空早返回，对外签名与异常类型不变；③ **E-14 备注先校验后 insertBatch**：`CustomerContactRemarkValidator` 由「逐条校验 + 逐条 insert」改为「先全量 `validateRemarkType`（任一非法零写入）→ 构建实体（`create_time` 显式填充）→ 一次 `mapper.insertBatch`」，`replaceRemarks` 的 delete 前置语义保留、事务归属仍由调用方 `updateList/insert` 的 `@Transactional` 界定；④ **E-16 活动创建双表 batchInsert**：`BusinessActivityServiceImpl#create` 两处 `forEach` 逐条 insert 改为 `BusinessActivityRelationSupport` 两个静态批量方法各调一次（复用 A-8 既有 `batchInsert` XML，**零新增 SQL**）。配套新增 `ProjectFileMapper#insertBatch` / `ApprovalAttachmentMapper#insertBatch` + 两个 XML（前者原为仅 namespace 空壳），**零迁移 / 零新依赖 / 零新错误码**；+4 测试类 23 用例（surefire 1779→1802、报告 283→287；failsafe 不变 32/119/7）。
3. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档 / 拍板形态 C）**：① `ForeignKeyConfig` 删掉 `SALES_STAGE_APPROVAL_RELATIONS → ApprovalAttachmentEntity(approvalId)` 死边（真库只有多态列 `and_id` + `model_name`，该边必抛 `ID_NOT_EXISTS` 并整事务回滚）；② 商机删除在 `deleteCascade` **之前**显式补删审批附件（`and_id ∈ approvalIds` 定位，磁盘 + DB 走 canonical 入口）；③ 跨模型同 `and_id` 回归网 `SalesOpportunityDeleteAttachmentCascadeIT`。
4. **循环写收口批 A-6 / A-8 / A-10（fix-engineering-debt-loop-write-batch）**：级联删 N 轮→1 轮（`logicalDeleteByCascadeBatch`）；关联维护 2N→2（一次 `IN` + 一次多值 insert）；用户批删单条化并修复 MP `NOT_NULL` 策略下 `setEmail(null)`/`setPhone(null)` 被丢弃的 PII 清理缺陷。
5. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone）**：分页 N+1 收敛 3N+1→2；双软删循环 2N→1；`SQLExceptionHandler` 7 项列名映射迁入 `application.yml` `slz.database-field-map`。
6. **待办池 MINOR 家族合卡（M-1 四形态收敛 / 摄取 96004 / 公海 assign 闸 / release 置空 / TokenBudget volatile / 近义码）**：`BaseException(String)` 四形态 95/95 收敛至 `ErrorCode` + 防回归门禁；摄取 `ContentRiskBlockedException` 收口 96004 与 100MB 有界读；assign 目标用户存在/在职闸前置行锁；release 置空显式落库；`TokenBudgetProperties` 三字段 volatile。
7. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传前置 canWriteAttachments 属主鉴权 12002；重置密码 DTO 校验收口 + 非超管禁改超管密码闸；invoice_info 纳管 DataScope 与 voidInvoice 属主闸。
8. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`deleteFile` 软删联动向量与 chunk 物理删；补齐 `assertCanReadContract`/`assertCanReadTask`；`merge-gate.sh` 8 门→10 门。
9. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）**：`UserPasswordSupport` 严格校验旧密（92028）、拒绝新旧同密；`PrivacyAspect` 补齐脱敏路径。
10. **用户自服务防提权越权加固（B-1）**：`updateMyInfo` 对 `roleId`/`deptId`/`status` Fail-Closed 拦截（12002），SET 子句走白名单。
11. **单副本单体部署契约与架构守卫硬化（I-4）**：`deploy/docker-compose.yml` 锁 `replicas: 1` + `PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true`。
12. **数据权限切面 Fail-Closed 治理（I-1）**：`QueryWrapperAspect` 异常重抛 `PERMISSION_DENIED`（12002），不静默放行。
13. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单精准收敛；`POST /user/logout` + Caffeine 本地黑名单与冻结/离职即时感知；`LoginAttemptService` 双维度防爆破。
14. **商机阶段流转审批阻断（D4）**：无审批记录抛 30003 `OPPORTUNITY_MUST_BE_CLOSED`。
15. **确认链路写权限再审计测试加固（D6）**：`PendingActionConfirmPermissionAuditTest` 从业务入口穿透归属校验与终态判定。
16. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；动态配置键 66→60。
17. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE；50 上限以 sys_user 行锁串行化；锁序「先用户后客户」零死锁面。
18. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller）。
19. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)。
20. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → 幂等建高优待办 → 每日 4 点错峰调度。
21. **CustomerInsightPanel 双宿主接线**：客户详情弹窗与 AI 助手抽屉上下文诊断面板双宿主齐备。
22. **AI 诊断建议一键转 Action**：`InsightToActionService` 纯结构化映射（零外呼）。
23. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点；单事务转化客户+联系人+商机并异步向量同步。
24. **合同履约到期预警与逾期应收催款待办联动**：到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度。
25. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003。
26. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合、父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（fix-semantic-hook-support-unify 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master；物料归档至 `openspec/changes/archive/fix-semantic-hook-support-unify/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡两件；B 档无独立复核报告））
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
  10. **【复核 I4】`CustomerCompanyServiceImpl:303-309` afterCommit 型循环收口**：`batchLogicalDeleteByIds` 仍是 `for (id) logicalDeleteById(id)` 且无 `PARAM_EMPTY` 闸；该路径每条 id 挂 `runAfterCommit(deleteByCompanyId)`，需另立卡设计；
  11. **【附件孤儿债】**：`business_activity` / `contact_task` / `assist_request` 删除路径不删附件（父对象物理级联删绕开 canonical 附件清理），另含 `ApprovalAttachmentServiceImpl#removeByIds` 按 `model_name` 选行却按裸 id 删行的跨模型风险。
- **池账闭合登记**：**池账 #25（`SemanticHookSupport` 五路泛型化，正身 A-15）已随本卡闭环出账**；连同已成既往的 #14 / #24，池账口径继续以各卡任务卡「来源」字段为准，旧勘误不再重述。
- **本卡偏差与观察点**（论证见归档 handoff §11/§12）：D1 唯一代码外改动是既有 `CustomerContactServiceImplTest` 一行失效 javadoc 链接修正（`runAfterCommit` → `runAfterCommitAsync`，非断言、不影响行为）；D2 `reingestById` 的谓词承担「是否需要删向量」的含 `null` 完整判定（基类刻意不二次判空，故谓词必须 null 安全——已由基类 Javadoc 声明并由五域「实体缺失」用例固定）；D3 ContactTask 四个服务层便捷闸与五个 `deleteById` 按域保留、未上提基类；后续卡若删改本卡新增测试须同步 `--update` 基线。
- **下批推荐**：池账 **#26 评测盲区补齐：多轮 / 时序 / 拒答断言（C-3,4,5，B 档）**，或 **#27 引用对齐阈值动态键 + 评测口径对齐（C-2，B 档）**，或按 Owner 指令插队。
- **下批候选池总账**：`work/_paa-candidates-pool-v2.md`（观察树未跟踪留档，长期储备保留）。
- **长期储备**：在线考试子域（`work/_online-exam-backlog.md` 留档）、多模态图文混合检索。
---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责发现验证、方案架构、写任务卡、复核裁决；**不写业务代码**。
2. **硬红线**：严禁未经 Owner 授权执行 `git push`、改写历史（reset/rebase/amend/force-push）、删分支、生产外呼、修改库结构或破坏性改动。
3. **任务卡派发标准**：严格采用 [`work/README.md`](../work/README.md) 的“目标约束型”短任务卡（< 40 行），严禁微观代码语法微操。
4. **复核强度分档（`docs/review-intensity-policy.md`）**：
   - **C 档**（文档/日志/纯测试加固）：主 Agent 审查 diff 与机械门禁，**不派复核 Agent**；
   - **B 档**（常规业务代码+测试）：依靠门禁脚本退出码 0，主 Agent 审查 diff 裁决；
   - **A 档**（DB迁移/权限变更/真跑/契约）：才启动独立复核 Agent。
5. **子 Agent 派发纪律（Owner 亲发纪律）**：执行与复核子 Agent **均由 Owner 亲自派发**，严禁主 Agent 自行代派；主 Agent 只产出《执行提示词》与《复核提示词》交付 Owner。

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
