# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-19（fix-engineering-debt-loop-write-batch 循环写收口批 A 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@be231e5`（本卡闭环：基点 `8d61f89` → 分支 3 笔 + M0 勘误笔 `b993c3d` → M1 merge --no-ff `04d667f`（零冲突，分支保留）→ 基线笔 `e91e88d` → CI 红修复 + M2 归档合笔 `be231e5` → M2 看板笔即本笔）
- **测试基线**：Surefire 283 报告 / 1777 用例 / 0 失败（+4 套件 / +39 用例；基线 1738→1777）、Failsafe 31/118/7 skipped，`scripts/test-baseline.txt` 权威对账（source-revision=`04d667f`，measured-at=2026-10-19T12:44:31Z；修复笔仅改测试确定性、用例集合不变，故未二次 `--update`）
- **门禁状态**：`mvn -B -ntp clean verify` BUILD SUCCESS（surefire 283/1777/0/0、failsafe 31/118/0/0/7 skipped，Docker 真库在线）；checkstyle / spotless / PMD / SpotBugs 全过；聚合门禁 `merge-gate.sh` **10/10 PASS**（typed-router 生成物噪声 `git checkout --` 还原，树净）；本卡零迁移，下一可用迁移号仍 **V43**
- **CI**：M1 首轮 `39256459141` **红**（build-and-gate surefire 4 error：新用例依赖 MP `TableInfo` 初始化顺序）；修复笔 `be231e5` 复跑见 `docs/main-agent-logbook.md` §132；M2 CI 同见 §132
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **循环写收口批 A-6 / A-8 / A-10（fix-engineering-debt-loop-write-batch，刚刚合入）**：① **级联删除 N 轮→1 轮**：`CustomerContactServiceImpl.batchLogicalDeleteByIds` 由「`for (id)` 自调用 `logicalDeleteById`」（N 轮级联 + N 条 `updateById`）收敛为一次 `ForeignKeyDeleteUtil.logicalDeleteByCascadeBatch`（级联仍每层 1 查 1 删，主表改单条 `IN` update），SQL 条数与 id 个数解耦，`@Transactional` / 空入参 `PARAM_EMPTY` 保留；重复 id 计数改「按实际命中行数」（旧 2 / 新 1，已单列论证）；② **关联维护 2N→2**：`BusinessActivityRelationSupport` 的 8 个 add/delete 方法由逐条 `exists` + `insert`/`delete` 改为「一次 `IN` 查询 + 一次多值 `insert` / 单条 `IN` delete」（新增 6 个 mapper 方法 + XML），错误码、文案、null 跳过、请求内重复 id 语义逐路径保持；旧单条 mapper 方法未删（`existsByActivityIdAndUserId` 仍有 3 处调用方）；③ **用户批删单条化 + 置空修复**：`UserServiceImpl.deleteById` 的 `for (user) updateById` 收敛为单条 `LambdaUpdateWrapper.set(status,3).set(email,null).set(phone,null).in(id,idList)`，顺带修复旧实现在 MP `NOT_NULL` 策略下 `setEmail(null)`/`setPhone(null)` 被整列丢弃的空操作缺陷（PII 清理 + 释放 `uk_email`/`uk_phone` 占用，已拍板为行为修复，定向用例断言 SET 三列与恰 2 个 null 绑定）；+4 测试类 39 用例，基线 1738→1777（283 报告）。
2. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone）**：① **分页 N+1 收敛 3N+1→2**：`UserHandoverServiceImpl` 分页取回用户姓名由每页逐条 `selectById` 改为 `selectBatchIds` 单批（`LinkedHashSet` 去重 + null 跳过守卫保留、单条路径零改动）；② **双软删循环 2N→1**：`InvoiceInfoServiceImpl.deleteByIds` 与 `PaymentRecordServiceImpl.deleteByIds` 收敛为单条 `LambdaUpdateWrapper` 条件 UPDATE（SET 状态列 + `IN idList`）；③ **双轨收敛单源**：`SQLExceptionHandler` 的 7 项列名映射迁入 `application.yml` `slz.database-field-map`（yml 驱动注入、`parts.length<2` 守卫在）；+4 测试类 18 用例，基线 1720→1738。
3. **待办池 MINOR 家族合卡（M-1 四形态收敛 / 摄取 96004 / 公海 assign 闸 / release 置空 / TokenBudget volatile / 近义码）**：`BaseException(String)` 四形态 95/95 全仓收敛至 `ErrorCode` 枚举 + 防回归门禁（严格正则 `matches` 全匹配 + 负向自证 6 样本 + 零豁免）；摄取 `ContentRiskBlockedException` 收口 96004 与 100MB 有界读；assign 目标用户存在/在职闸前置行锁（抽类 `CustomerPoolTargetUserGuard`）；release 置空显式落库；`TokenBudgetProperties` 三字段 volatile；4 处近义映射 `PARAM_FORMAT_ERROR`(50002) 待产品评估精确码；+23 测试，基线 1697→1720。
4. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传入口（uploadByActivity/uploadByOrder）前置 canWriteAttachments 属主鉴权 12002、清理 `ApprovalAttachmentService` 废弃方法、updateStage 复用私有保存；重置密码 DTO 校验收口 + 非超管禁改超管密码闸与审计日志；invoice_info 纳管 DataScope（MANAGED_TABLES + creator_id 映射）与 voidInvoice 创建人/超管属主闸、裸抛规范化 91001/92000；+26 专项测试，基线 1671→1697。
5. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`KnowledgeAdminService.deleteFile` 软删联动 `vectorStore.deleteByDocumentId` 与 `chunkMapper.deletePhysicallyByDocumentId`（消除已删文档向量残留与检索虚假引用，缺依赖 fail-closed）；`BusinessRecordAccessService` 补齐 `assertCanReadContract`/`assertCanReadTask`，在合同详情、订单详情与任务评论双入口拦截越权（12002），7 个 AI 查询工具执行器前置 `requireLoggedInRole`；聚合门禁 `scripts/merge-gate.sh` 引入 `checkstyle:check` 与 `spotless:check`（8 门→10 门）；NCSS 超限通过提取 `KnowledgeAdminVectorSupport` 与 `AiQueryToolSupport` 消解；+24 专项测试，基线 1647→1671。
6. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）**：`UserController.updatePassword` 改收 `UserUpdatePasswordDTO` 并委托 `UserPasswordSupport`；`PasswordHashUtil.matches` 严格校验旧密（失败抛 92028 `PASSWORD_ERROR`，F-1 枚举构造器修复确保透传），拒绝新旧同密；`PrivacyAspect` 补齐无 `PRIVACY_PHONE_VIEW` 时的脱敏死代码；`SalesLeadVO` 覆写 `phone()`/`email()` 置 `***` 且负责人本人放行；前端 `ChangePasswordModal.vue` 与 `useUser.ts` 同步原密码项，`error-code-map.ts` 镜像 92028；+6 专项单测，基线 1641→1647。
7. **用户自服务防提权越权加固（B-1）**：`UserService.updateMyInfo` 对 `roleId`/`deptId`/`status` 特权字段 Fail-Closed 拦截（12002），SET 子句经 `UserSelfUpdateSupport` 严格限定 `realName`/`phone`/`email` 白名单，绑定令牌解出的 `currentUserId`；挂载 `@CacheEvict(value="userName", key="#currentUserId")` 消除脏读；+5 安全单测，基线 1636→1641。
8. **单副本单体部署契约与架构守卫硬化（I-4）**：`deploy/docker-compose.yml` 锁定 `deploy.mode=replicated / replicas: 1` 并注入 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA: "true"`；`.env.example` 与 `deploy/README.md` 建立 8 大单机状态组件与 6 大定时调度任务的单副本依赖权威文档；扩容 `SingleReplicaArchitectureAssumptionGuardTest`；+8 单测，基线 1628→1636。
9. **数据权限切面 Fail-Closed 治理（I-1）**：`QueryWrapperAspect` 移除数据权限条件注入与重载转发异常时的日志吞咽/静默放行，统一包装重抛为 `BaseException(PERMISSION_DENIED, "数据权限过滤失败，拒绝查询")`（12002）；系统/后台任务无上下文用户及非托管表严格放行并记 debug 审计；+8 靶向单测，基线 1620→1628。
10. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单精准收敛，`POST /user/logout` + Caffeine 本地黑名单与用户冻结/离职即时感知；`LoginAttemptService` 双维度防爆破限流（5 次错锁 15 分）；单副本无状态契约零新增依赖；基线 1588→1620。
11. **商机阶段流转审批阻断（D4）**：`SalesOpportunityServiceImpl.update` 前置流转阻断，`SalesStageApprovalSupport.isValidStageTransition` 判定合法正向需审批流转；无审批记录阻断抛 30003 `OPPORTUNITY_MUST_BE_CLOSED`；+4 四向单测；基线 1584→1588。
12. **确认链路写权限再审计测试加固（D6）**：`PendingActionConfirmPermissionAuditTest` 从 `PendingActionServiceImpl.confirm` 业务入口穿透实体归属校验与终态判定，无权限即 fail-closed；+1 单测；基线 1583→1584。
13. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；6 个死配置键彻底摘除，动态配置键总数 66→60；+10 单测，基线 1573→1583。
14. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE（谓词 `pool_status=1 AND is_deleted=0`）；50 上限以 sys_user 行锁串行化；锁序统一「先用户后客户」零死锁面；+3 用例；基线 1570→1573。
15. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller）；DTO 校验注解按契约 required 对表零超约束；基线 1484→1570。
16. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托返回计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)；surefire +8、前端 +9。
17. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → hasInProgressTask 幂等建高优待办 → 每日 4 点错峰调度。
18. **CustomerInsightPanel 双宿主接线**：客户详情弹窗与 AI 助手抽屉上下文诊断面板双宿主齐备；created 反馈与刷新双宿主联动。
19. **AI 诊断建议一键转 Action**：后端 InsightToActionService 纯结构化映射（零外呼），诊断建议一键转待办/转活动。
20. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点复用既有权限；单事务转化客户+联系人+商机并异步向量同步。
21. **合同履约到期预警与逾期应收催款待办联动**：ContractMapper 到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度。
22. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003；定时回收超时私海。
23. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合，父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（fix-engineering-debt-loop-write-batch 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master；物料归档至 `openspec/changes/archive/fix-engineering-debt-loop-write-batch/` 与 `work/archive/closed-cards/`）
- **待办池累计十条（原七条 + 本卡新增三条）**：
  1. `new BaseException (` 空格形态加固（格式化回滚风险）；
  2. release 置空真库回环用例；
  3. TokenBudget 跨线程用例区别力；
  4. `MessageConstant` 9 处码断言；
  5. 「已存在」精确码产品评估（50002 系近义）；
  6. A-7 null id 显式用例（`UserHandoverServiceImpl#collectUserId` 三类 null 跳过分支）；
  7. `SQLExceptionHandler` 历史缺陷收口（`:48` 裸索引名越界 + 裸列名丢字段名 + 列名/索引名共用 Map 的键空间隔离）；
  8. **【本卡新增】关联维护 4 DTO null-id 校验**：`BusinessActivityDTO` / `BusinessActivityUserDTO` / `BusinessActivityContactDTO` / `AddActivity{User,Contact}RequestDTO` 的 id 字段均无 `@NotNull`（controller 的 `@Valid` 无约束可执行，多 null id 经 `updateList` 可达）；旧=DB NOT NULL 500、新=50002「重复关联」（两形态均零写入），补注解即可根治，已由 handoff §8.2 勘误笔如实记录；
  9. **【本卡新增】批量 IN 长列表 controller 分批硬上限**：本卡新增的级联/关联批量入口把 id 全量塞进 `IN`，前端勾选粒度下安全但缺硬上限；宜在 controller 层加分批与 row 上限；
  10. **【本卡新增·复核 I4】`CustomerCompanyServiceImpl:303-309` afterCommit 型循环收口**：`batchLogicalDeleteByIds` 仍是 `for (id) logicalDeleteById(id)` 且无 `PARAM_EMPTY` 闸；该路径每条 id 挂 `runAfterCommit(deleteByCompanyId)`，不能直接套用本卡批量入口，需另立卡设计。
- **本卡 CI 红暴露的 pre-existing 生产缺陷（单列，不计入池账十条，待 Owner 定夺）**：`ForeignKeyConfig:170-171` 把 `ApprovalAttachmentEntity` 的外键写成 `approvalId`，但 V1 基座 DDL 只有 `and_id`、实体亦无 `approvalId` 属性——该条自 Wave 0 起不可解析。生产环境 MP `TableInfo` 已初始化，`SalesOpportunityServiceImpl:156` 删除销售机会 → 阶段审批 → 附件这条级联会抛 `ID_NOT_EXISTS`。修法有语义分歧（`and_id` 是「关联表 ID」泛字段，直接改列名会在多 model 间误删，需加 `model_name` 过滤），故本卡只做测试确定性收口，未改生产映射。
- **下批推荐**：池账 `work/_paa-candidates-pool-v2.md` **#24 ProjectFile 域收口（A-5 / E-14 / E-16，B 档）**，或按 Owner 指令插队；**池账 #30「batchLogicalDeleteByIds 级联批量下沉」已随本卡闭合**。
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
