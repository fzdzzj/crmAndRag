# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-19（fix-engineering-debt-nplus1-loop-clone 工程债三合卡全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@ac7fa64`（本卡闭环：基点 `6e99443` → 分支 4 笔 + M0 勘误笔 → M1 merge --no-ff `c00e0e0`（5 笔 = 4+1）→ 基线笔 `ac7fa64` → M2 归档看板笔即本笔）
- **测试基线**：Surefire 279 份报告 / 1738 用例 / 0 失败（+4 套件 / +18 用例；基线 1720→1738）、Failsafe 31/118/7 skipped，`scripts/test-baseline.txt` 权威对账（source-revision=`c00e0e0`，measured-at=2026-10-19T09:08:21Z）
- **门禁状态**：`mvn -B -ntp clean verify` BUILD SUCCESS（17:30 min，surefire 1738/0/0/0、failsafe 118/0/0/7，Docker 真库在线）；checkstyle / spotless / PMD / SpotBugs 全过；聚合门禁 `merge-gate.sh` **10/10 PASS**（typed-router 生成物噪声 `git checkout --` 还原，树净）；下一可用迁移号仍 V43
- **CI**：M1 Run `39229508768` **6/6 全绿**（build-and-gate 1738/118/7、Frontend、OpenAPI 契约、static-analysis、fail-fast、thread-pool-metrics）；M2 CI 见 `docs/main-agent-logbook.md` §131
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone，刚刚合入）**：① **分页 N+1 收敛 3N+1→2**：`UserHandoverServiceImpl` 分页取回用户姓名由每页逐条 `selectById` 改为 `selectBatchIds` 单批（查询数 3N+1 → 2），`LinkedHashSet` 去重 + null 跳过守卫保留、单条路径零改动；② **双软删循环 2N→1**：`InvoiceInfoServiceImpl.deleteByIds` 与 `PaymentRecordServiceImpl.deleteByIds` 由逐条 `selectById` + `updateById` 收敛为单条 `LambdaUpdateWrapper` 条件 UPDATE（SET 状态列 + `IN idList`），`@Transactional` / `PARAM_EMPTY` 保留，重复 id 计数按实际行数（旧 2 / 新 1，论证见 handoff §2）；③ **双轨收敛单源**：`SQLExceptionHandler` 的 7 项「列名→中文名」映射从 switch 硬编码迁入 `application.yml` `slz.database-field-map`（yml 驱动注入、`parts.length<2` 守卫在、未知字段回退原文案），测试改读 yml 而非复制 switch；+4 测试类 18 用例，基线 1720→1738（279 报告）。
2. **待办池 MINOR 家族合卡（M-1 四形态收敛 / 摄取 96004 / 公海 assign 闸 / release 置空 / TokenBudget volatile / 近义码）**：`BaseException(String)` 四形态 95/95 全仓收敛至 `ErrorCode` 枚举 + 防回归门禁（严格正则 `matches` 全匹配 + 负向自证 6 样本 + 零豁免）；摄取 `ContentRiskBlockedException` 收口 96004 与 100MB 有界读；assign 目标用户存在/在职闸前置行锁（抽类 `CustomerPoolTargetUserGuard`）；release 置空显式落库；`TokenBudgetProperties` 三字段 volatile；4 处近义映射 `PARAM_FORMAT_ERROR`(50002) 待产品评估精确码；+23 测试，基线 1697→1720。
3. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传入口（uploadByActivity/uploadByOrder）前置 canWriteAttachments 属主鉴权 12002、清理 ApprovalAttachmentService 废弃方法、updateStage 复用私有保存；重置密码 DTO 校验收口 + 非超管禁改超管密码闸与审计日志；invoice_info 纳管 DataScope（MANAGED_TABLES + creator_id 映射）与 voidInvoice 创建人/超管属主闸、裸抛规范化 91001/92000；+26 专项测试，基线 1671→1697。
4. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`KnowledgeAdminService.deleteFile` 软删联动 `vectorStore.deleteByDocumentId`（知识库 @Primary 向量库）与 `chunkMapper.deletePhysicallyByDocumentId`（物理删除切片），消除已删文档向量残留与检索虚假引用，缺双清依赖 fail-closed；`BusinessRecordAccessService` 补齐 `assertCanReadContract`/`assertCanReadTask` 属主与数据范围断言，在合同详情、订单详情（随所属合同）与任务评论双入口落地拦截越权（12002 `PERMISSION_DENIED`），7 个 AI 查询工具执行器前置 `requireLoggedInRole` 登录身份守卫；聚合门禁 `scripts/merge-gate.sh` 引入 `checkstyle:check` 与 `spotless:check` 消除与 CI static-analysis job 同源差异（8 门→10 门），自测断言增至 106 全绿；NCSS 超限通过提取包私有协作类 `KnowledgeAdminVectorSupport` 与 `AiQueryToolSupport` 优雅消解；+24 专项测试，基线 1647→1671。
5. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）**：`UserController.updatePassword` 改收 `UserUpdatePasswordDTO` 并委托 `UserPasswordSupport`；通过 `PasswordHashUtil.matches` 严格校验旧密（失败抛 92028 `PASSWORD_ERROR` 拒绝，F-1 枚举构造器修复确保 92028 透传至客户端），拒绝新旧同密，BCrypt 加盐哈希落库并输出安全审计日志；`PrivacyAspect` 补齐无 `PRIVACY_PHONE_VIEW` 时的 `o.mobile()` 调用激活联系人手机号脱敏死代码；`SalesLeadVO` 实现 `Privacy` 覆写 `phone()`/`email()` 置 `***`，服务层挂 `@Privacy` 拦截且负责人本人查看（`isSelf`）正常放行；前端 `ChangePasswordModal.vue` 与 `useUser.ts` 同步补齐原密码表单项与类型，`error-code-map.ts` 契约镜像 92028 激活；+6 专项单测，基线 1641→1647。
6. **用户自服务防提权越权加固（B-1）**：`UserService.updateMyInfo` 专用防线对 `roleId`/`deptId`/`status` 特权字段实行 Fail-Closed 拦截（12002 `PERMISSION_DENIED`），SET 子句经 `UserSelfUpdateSupport` 严格限定 `realName`/`phone`/`email` 白名单，绑定令牌解出的 `currentUserId`；挂载 `@CacheEvict(value="userName", key="#currentUserId")` 消除脏读；+5 专项安全单测，基线 1636→1641。
7. **单副本单体部署契约与架构守卫硬化（I-4）**：`deploy/docker-compose.yml` 显式锁定 `deploy.mode=replicated / replicas: 1` 并注入 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA: "true"`；`.env.example` 与 `deploy/README.md` 建立 8 大单机状态组件与 6 大定时调度任务的单副本依赖权威文档；扩容 `SingleReplicaArchitectureAssumptionGuardTest`；+8 单测，基线 1628→1636。
8. **数据权限切面 Fail-Closed 治理（I-1）**：`QueryWrapperAspect` 彻底移除数据权限条件注入与重载方法转发异常时的日志吞咽与静默放行逻辑，统一包装重抛为 `BaseException(PERMISSION_DENIED, "数据权限过滤失败，拒绝查询")`（12002）；系统/后台任务无上下文用户及非托管表严格放行并记录 debug 审计日志；+8 靶向单测；基线 1620→1628。
9. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单精准收敛，`POST /user/logout` + Caffeine 本地黑名单与用户冻结/离职即时感知；`LoginAttemptService` 双维度防爆破限流（5次错锁15分）；单副本无状态契约零新增依赖；基线 1588→1620。
10. **商机阶段流转审批阻断（D4）**：`SalesOpportunityServiceImpl.update` 前置流转阻断，调用 `SalesStageApprovalSupport.isValidStageTransition` 判定合法正向需审批阶段流转；无审批记录阻断抛出 30003 `OPPORTUNITY_MUST_BE_CLOSED`；+4 四向单测；基线 1584→1588。
11. **确认链路写权限再审计测试加固（D6）**：`PendingActionConfirmPermissionAuditTest` 从 `PendingActionServiceImpl.confirm` 业务入口穿透实体归属校验与终态判定，无权限时 fail-closed；+1 单测；基线 1583→1584。
12. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；6 个死配置键彻底摘除，动态配置键总数 66→60；+10 单测，基线 1573→1583。
13. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE（谓词 `pool_status=1 AND is_deleted=0`）；50 上限以 sys_user 行锁串行化；锁序统一「先用户后客户」零死锁面；+3 用例；基线 1570→1573。
14. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller）；DTO 校验注解按契约 required 对表零超约束；基线 1484→1570。
15. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托返回计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)；surefire +8、前端 +9。
16. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → hasInProgressTask 幂等建高优待办 → 每日 4 点错峰调度。
17. **CustomerInsightPanel 双宿主接线**：客户详情弹窗与 AI 助手抽屉上下文诊断面板双宿主齐备；created 反馈与刷新双宿主联动。
18. **AI 诊断建议一键转 Action**：后端 InsightToActionService 纯结构化映射（零外呼），诊断建议一键转待办/转活动。
19. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点复用既有权限；单事务转化客户+联系人+商机并异步向量同步。
20. **合同履约到期预警与逾期应收催款待办联动**：ContractMapper 到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度。
21. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003；定时回收超时私海。
22. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合，父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（fix-engineering-debt-nplus1-loop-clone 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master；物料归档至 `openspec/changes/archive/fix-engineering-debt-nplus1-loop-clone/` 与 `work/archive/closed-cards/`）
- **本卡带出的待办池新增（两条，均为复核 MINOR 覆盖缺口）**：① **A-7 null id 显式用例**：`UserHandoverServiceImpl#collectUserId` 对 `fromUserId`/`toUserId`/`operatorId == null` 的跳过分支目前仅有代码评审证据，缺 1 条「页内含 null 三类 id → 不查库 / 姓名为 null」的显式用例，防将来误删守卫；② **`SQLExceptionHandler` 历史缺陷收口**：`:48` 裸索引名 `split("\\.")[1]` 无长度守卫越界风险（pre-existing，报文 key 不含 `.` 即 AIOOBE，本卡新增守卫覆盖不到该行）+ SQL 裸列名形态丢字段名（非 `表名.列名` 时回退原始报文）+ 「列名键与索引名键共用同一 Map」键空间隔离评估（现靠 `uk_*`/`idx_*` 命名约定而非结构隔离）。
- **下批推荐**：池账 `work/_paa-candidates-pool-v2.md` **#14 余项 A-6 / A-8 / A-10**（或按 Owner 指令插队）。
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
