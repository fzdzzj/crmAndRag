# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-20（fix-approval-attachment-cascade-dead-edge 级联死边 A 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@aba21c0`（本卡闭环：基点 `5024d8e` → 分支 4 笔 `a688198` / `56ca735` / `5ec0793` / `a24c85d` + M0 勘误笔 `d092dd4` → M1 merge --no-ff `554f2a5`（零冲突，分支保留）→ 基线重锚笔 `aba21c0` → M2 归档看板笔即本笔）
- **测试基线四元组**：Surefire **报告 283 / 用例 1779 / F0 / E0 / S0**；Failsafe **报告 32 / 用例 119 / F0 / E0 / S7**（只增不减：本卡前为 283/1777/0、31/118/7；+2 单测、+1 IT）。`scripts/test-baseline.txt` = `source-revision=554f2a5`、`measured-at=2026-10-19T16:18:04Z`（树净单次 `--update` + 校验模式 `EXIT=0`）
- **门禁状态**：`mvn -B -ntp clean verify` **BUILD SUCCESS**（18:12 min，Docker 29.6.2 真库在线）；checkstyle / spotless / PMD / SpotBugs 四静态全过；聚合门禁 `merge-gate.sh` **10/10 PASS**（typed-router 生成物噪声 `git checkout --` 还原，树净）；本卡零迁移，下一可用迁移号仍 **V43**
- **CI**：M1 合并轮 run `39286482386`（head `aba21c0`）**6/6 全绿**；M2 看板轮 CI 号与结论见 `docs/main-agent-logbook.md` §133
- **复核**：A 档独立复核 **GO**（0 MAJOR / 5 MINOR / 4 知悉），报告归档 `work/archive/closed-cards/review-fix-approval-attachment-cascade-dead-edge-a.md`
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档 / 拍板形态 C，刚刚合入）**：① **死边移除**：`ForeignKeyConfig` 删掉 `SALES_STAGE_APPROVAL_RELATIONS → ApprovalAttachmentEntity(approvalId)` —— 真库只有多态列 `and_id` 配 `model_name` 判别、实体只有 `andId`，生产环境 MP `TableInfo` 已初始化时该边必抛 `BaseException(ID_NOT_EXISTS)` 并整事务回滚（删已关闭商机必炸），改为留「已移除 + 为什么」注释；`ALL_RELATIONS` 的空图条目 `APPROVAL_ATTACHMENT_RELATIONS` 与常量一并移除（实测全图零边指向该实体，与保留空图行为等价）；② **商机删除显式补删审批附件**：`SalesOpportunityServiceImpl#delete` 在 `deleteCascade` **之前**走 `selectAllApprovalsByOpportunityId → listEntitiesByAndIds(·, APPROVAL_ATTACHMENT) → removeByIds(·, APPROVAL_ATTACHMENT)`（顺序必要：级联会物理删审批行，附件必须提前按 `and_id ∈ approvalIds` 定位；磁盘清理 + DB 物理删走 canonical 入口，无 mapper 裸删；不逐附件鉴权的差异论证见 handoff §4）；③ **跨模型同 `and_id` 回归网**：新真库 IT `SalesOpportunityDeleteAttachmentCascadeIT` 断言审批行归零、目标附件 DB 行 + 磁盘文件双删、`model_name=business_activity` 且**同 `and_id`** 的对照附件行与文件全部存活；+3 用例（surefire 1777→1779、failsafe 31→32 / 118→119）。
2. **循环写收口批 A-6 / A-8 / A-10（fix-engineering-debt-loop-write-batch）**：① **级联删除 N 轮→1 轮**：`CustomerContactServiceImpl.batchLogicalDeleteByIds` 由「`for (id)` 自调用 `logicalDeleteById`」收敛为一次 `ForeignKeyDeleteUtil.logicalDeleteByCascadeBatch`（级联仍每层 1 查 1 删，主表改单条 `IN` update），SQL 条数与 id 个数解耦，`@Transactional` / 空入参 `PARAM_EMPTY` 保留；重复 id 计数改「按实际命中行数」；② **关联维护 2N→2**：`BusinessActivityRelationSupport` 的 8 个 add/delete 方法改为「一次 `IN` 查询 + 一次多值 `insert` / 单条 `IN` delete」（新增 6 个 mapper 方法 + XML），错误码、文案、null 跳过、请求内重复 id 语义逐路径保持；③ **用户批删单条化 + 置空修复**：`UserServiceImpl.deleteById` 收敛为单条 `LambdaUpdateWrapper.set(status,3).set(email,null).set(phone,null).in(id,idList)`，顺带修复 MP `NOT_NULL` 策略下 `setEmail(null)`/`setPhone(null)` 被整列丢弃的缺陷（PII 清理 + 释放 `uk_email`/`uk_phone`）；+4 测试类 39 用例，基线 1738→1777。
3. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone）**：① **分页 N+1 收敛 3N+1→2**：`UserHandoverServiceImpl` 分页取回用户姓名改为 `selectBatchIds` 单批（`LinkedHashSet` 去重 + null 跳过守卫保留）；② **双软删循环 2N→1**：`InvoiceInfoServiceImpl.deleteByIds` 与 `PaymentRecordServiceImpl.deleteByIds` 收敛为单条 `LambdaUpdateWrapper` 条件 UPDATE；③ **双轨收敛单源**：`SQLExceptionHandler` 的 7 项列名映射迁入 `application.yml` `slz.database-field-map`（`parts.length<2` 守卫在）；+4 测试类 18 用例，基线 1720→1738。
4. **待办池 MINOR 家族合卡（M-1 四形态收敛 / 摄取 96004 / 公海 assign 闸 / release 置空 / TokenBudget volatile / 近义码）**：`BaseException(String)` 四形态 95/95 收敛至 `ErrorCode` 枚举 + 防回归门禁（严格 `matches` 全匹配 + 负向自证 6 样本 + 零豁免）；摄取 `ContentRiskBlockedException` 收口 96004 与 100MB 有界读；assign 目标用户存在/在职闸前置行锁（`CustomerPoolTargetUserGuard`）；release 置空显式落库；`TokenBudgetProperties` 三字段 volatile；4 处近义映射 `PARAM_FORMAT_ERROR`(50002) 待产品评估；+23 测试，基线 1697→1720。
5. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传入口（uploadByActivity/uploadByOrder）前置 canWriteAttachments 属主鉴权 12002、清理 `ApprovalAttachmentService` 废弃方法；重置密码 DTO 校验收口 + 非超管禁改超管密码闸与审计日志；invoice_info 纳管 DataScope（MANAGED_TABLES + creator_id 映射）与 voidInvoice 创建人/超管属主闸、裸抛规范化 91001/92000；+26 专项测试，基线 1671→1697。
6. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`KnowledgeAdminService.deleteFile` 软删联动 `vectorStore.deleteByDocumentId` 与 `chunkMapper.deletePhysicallyByDocumentId`；`BusinessRecordAccessService` 补齐 `assertCanReadContract`/`assertCanReadTask`；7 个 AI 查询工具执行器前置 `requireLoggedInRole`；`merge-gate.sh` 引入 `checkstyle:check` 与 `spotless:check`（8 门→10 门）；+24 专项测试，基线 1647→1671。
7. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）**：`UserController.updatePassword` 改收 `UserUpdatePasswordDTO` 并委托 `UserPasswordSupport`；`PasswordHashUtil.matches` 严格校验旧密（92028）、拒绝新旧同密；`PrivacyAspect` 补齐无 `PRIVACY_PHONE_VIEW` 时脱敏死代码；`SalesLeadVO` 覆写 `phone()`/`email()`；前端 `ChangePasswordModal.vue` / `useUser.ts` / `error-code-map.ts` 同步；+6 单测，基线 1641→1647。
8. **用户自服务防提权越权加固（B-1）**：`UserService.updateMyInfo` 对 `roleId`/`deptId`/`status` 特权字段 Fail-Closed 拦截（12002），SET 子句经 `UserSelfUpdateSupport` 严格限定白名单；`@CacheEvict(value="userName")` 消除脏读；+5 安全单测，基线 1636→1641。
9. **单副本单体部署契约与架构守卫硬化（I-4）**：`deploy/docker-compose.yml` 锁定 `deploy.mode=replicated / replicas: 1` 并注入 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true`；`.env.example` 与 `deploy/README.md` 建立单机状态组件与定时调度任务权威文档；扩容 `SingleReplicaArchitectureAssumptionGuardTest`；+8 单测，基线 1628→1636。
10. **数据权限切面 Fail-Closed 治理（I-1）**：`QueryWrapperAspect` 移除异常时的日志吞咽/静默放行，统一重抛为 `BaseException(PERMISSION_DENIED, "数据权限过滤失败，拒绝查询")`（12002）；+8 靶向单测，基线 1620→1628。
11. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单精准收敛，`POST /user/logout` + Caffeine 本地黑名单与冻结/离职即时感知；`LoginAttemptService` 双维度防爆破限流；基线 1588→1620。
12. **商机阶段流转审批阻断（D4）**：`SalesOpportunityServiceImpl.update` 前置流转阻断，无审批记录抛 30003 `OPPORTUNITY_MUST_BE_CLOSED`；+4 四向单测；基线 1584→1588。
13. **确认链路写权限再审计测试加固（D6）**：`PendingActionConfirmPermissionAuditTest` 从业务入口穿透实体归属校验与终态判定，无权限即 fail-closed；+1 单测；基线 1583→1584。
14. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；6 个死配置键摘除，动态配置键 66→60；+10 单测，基线 1573→1583。
15. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE（`pool_status=1 AND is_deleted=0`）；50 上限以 sys_user 行锁串行化；锁序统一「先用户后客户」零死锁面；+3 用例；基线 1570→1573。
16. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller）；DTO 校验注解按契约 required 对表零超约束；基线 1484→1570。
17. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托返回计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)；surefire +8、前端 +9。
18. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → hasInProgressTask 幂等建高优待办 → 每日 4 点错峰调度。
19. **CustomerInsightPanel 双宿主接线**：客户详情弹窗与 AI 助手抽屉上下文诊断面板双宿主齐备；created 反馈与刷新双宿主联动。
20. **AI 诊断建议一键转 Action**：后端 InsightToActionService 纯结构化映射（零外呼），诊断建议一键转待办/转活动。
21. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点复用既有权限；单事务转化客户+联系人+商机并异步向量同步。
22. **合同履约到期预警与逾期应收催款待办联动**：ContractMapper 到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度。
23. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003；定时回收超时私海。
24. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合，父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（fix-approval-attachment-cascade-dead-edge 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master；物料归档至 `openspec/changes/archive/fix-approval-attachment-cascade-dead-edge/` 与 `work/archive/closed-cards/`：handoff + 任务卡 + A 档复核报告三件齐）
- **待办池累计十一条（原十条 + 本卡新增一条）**：
  1. `new BaseException (` 空格形态加固（格式化回滚风险）；
  2. release 置空真库回环用例；
  3. TokenBudget 跨线程用例区别力；
  4. `MessageConstant` 9 处码断言；
  5. 「已存在」精确码产品评估（50002 系近义）；
  6. A-7 null id 显式用例（`UserHandoverServiceImpl#collectUserId` 三类 null 跳过分支）；
  7. `SQLExceptionHandler` 历史缺陷收口（`:48` 裸索引名越界 + 裸列名丢字段名 + 列名/索引名共用 Map 的键空间隔离）；
  8. 关联维护 4 DTO null-id 校验：`BusinessActivityDTO` / `BusinessActivityUserDTO` / `BusinessActivityContactDTO` / `AddActivity{User,Contact}RequestDTO` 的 id 字段均无 `@NotNull`（多 null id 经 `updateList` 可达，补注解即可根治）；
  9. 批量 IN 长列表 controller 分批硬上限：级联/关联批量入口把 id 全量塞进 `IN`，宜在 controller 层加分批与 row 上限；
  10. **【复核 I4】`CustomerCompanyServiceImpl:303-309` afterCommit 型循环收口**：`batchLogicalDeleteByIds` 仍是 `for (id) logicalDeleteById(id)` 且无 `PARAM_EMPTY` 闸；该路径每条 id 挂 `runAfterCommit(deleteByCompanyId)`，不能直接套用本卡批量入口，需另立卡设计。
  11. **【本卡新增·复核 M2/A4】附件生命周期孤儿债**：`business_activity` / `contact_task` / `assist_request` 删除路径不删附件 —— 两类模型被父对象物理级联删时绕开各自服务的 canonical 附件清理（`BusinessActivityServiceImpl#deleteByIds` / `ContactTaskServiceImpl#deleteByIds` 才清），商机/公司/联系人删除后留 `model_name=business_activity|contact_task` 行 + 磁盘文件；`SalesStageApprovalService#removeByIds` 之外的级联物理删审批行同样留 `assist_request` 残留。附带硬化点：`ApprovalAttachmentServiceImpl#removeByIds:90-107` 按 `model_name` 选行却按**入参裸 id** `deleteBatchIds`，混模型 id 列表会跨模型删行且跳过磁盘清理 —— 选择与删行口径应对齐。（本卡只补商机根的阶段审批附件，其余属后续卡）
- **本卡相关的三处既有缺陷已归集**（详细论证见归档 handoff §6 F1/F2/F3）：F1 公司/联系人根审批附件未补删、F2 `ForeignKeyConfig:95` 把 `TaskCommentEntity(taskId)` 挂在 `CUSTOMER_CONTACT_RELATIONS` 下、F3 上述附件孤儿债；三者均不崩、不越权，只是残留行与文件。**原 §1 单列的 `ForeignKeyConfig:170-171` 生产缺陷（本卡 CI 首红暴露的那条）已随本卡闭合，不再挂账。**
- **下批推荐**：池账 `work/_paa-candidates-pool-v2.md` **#24 ProjectFile 域收口（A-5 / E-14 / E-16，B 档）**，或按 Owner 指令插队；池账 #30「batchLogicalDeleteByIds 级联批量下沉」已随循环写收口批闭合，本卡死边不占池账号（原 §1 单列挂账项，现闭合）。
- **下批候选池总账**：`work/_paa-candidates-pool-v2.md`（长期储备保留）。
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
5. **子 Agent 派发纪律（Owner 亲发纪律）**：执行与复核子 Agent **均由 Owner 亲自派发**，严禁主 Agent 自行代派；主 Agent 只产出《执行提示词》与《复核提示词》交付 Owner，复核报告回贴后由主 Agent 核验审计并提请合并授权。

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
