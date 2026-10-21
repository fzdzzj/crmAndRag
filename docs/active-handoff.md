# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-21（fix-backlog-batch4 收尾小卡 ⑬⑭，B 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@68d563c + 本笔（M2 归档看板笔③）`（本卡闭环：基点 `71a274b` → 分支 3 笔 `093e181` fix / `9856580` test+基线 / `31d083f` docs（openspec 卡）→ M1 `git merge --no-ff` `68d563c`（零冲突，分支 `feature/fix-backlog-batch4` **永久保留**）→ M2 三笔 `316196c`（openspec 归档 R100）/ `a8f509f`（交接件入 closed-cards）/ 本笔（看板 + 基线台账））
- **测试基线四元组**：`merge=68d563c` + Surefire **报告 304 / 用例 1894 / F0 / E0 / S0** + Failsafe **报告 34 / 用例 124 / F0 / E0 / S7** + `CI=#144`（M1 轮 6/6 全绿，Run 39553971436）与 `CI=#145`（M2 看板轮，= #144+1 预测，实测号以 `docs/main-agent-logbook.md` §141 为准）。只增不减：本卡前 303/1889/0 + 34/123/7 → +1 报告 +5 用例、failsafe +1 用例（报告数与 skipped 不变）。`scripts/test-baseline.txt` = `source-revision=68d563c`、`measured-at=2026-10-21T06:11:41Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第六卡启用）**：`git rev-parse "68d563c^{tree}"` 与 `git rev-parse "31d083f^{tree}"` 同为 `14f1b4e50456fd5ac559573467648fe61348c352`，**逐字节相等** → 判定树等价，执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS** + `merge-gate.sh` **10/10 PASS**（含 `[frontend-unit]`）+ 基线 `EXIT=0` → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#144 已绿 = M1 验证闭环**）
- **复核**：**B 档 GO**（业务代码 + 测试）—— 门禁脚本退出码 0 + 主 Agent 审 diff 裁决，不派独立复核；归档物料 = `openspec/changes/archive/fix-backlog-batch4/`（proposal + tasks，R100）+ `work/archive/closed-cards/`（handoff + 任务卡 `add -f`）
- **前端披露**：本卡 `frontend/` 仅 `src/constants/error-code-map.test.ts` **一处**测试工程改动（总数断言 fs 生成化，**已拍板**）；生产镜像 `error-code-map.ts` 零 diff，`typed-router.d.ts` 生成物零提交（两次运行均 `git checkout --` 还原，见 ⑮）
- **环境实测**：本卡走快车道，本地零 Maven 复跑（不依赖 Docker）；执行端 verify 时 Docker 在线，failsafe 124 用例 7 skipped 与基线口径一致
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **收尾小卡 ⑬⑭（fix-backlog-batch4，B 档，刚刚合入）**：`src/main` 1 文件（`ChildHostAttachmentCleaner`）+ 2 测试类 + `frontend/src/constants/error-code-map.test.ts` 一处（**已拍板**），零迁移 / 零新依赖 / 零新错误码：**⑬ 审批域附件清理补齐**——公司 / 联系人两入口在既有 `opportunities` 一级集之外，按 `opportunity_id` 查 `sales_stage_approval` 行再 `removeByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)`；**商机入口不重复接线**（先例 `deleteStageApprovalAttachments` 已覆盖，重复即双删）；IT 补两入口审批附件清零断言。**⑭ Vitest 总数断言生成化**——Node fs 读 `ErrorCode.java` + `PlatformErrorCode.java` 正则计数、删硬编码总数；`\s*` 容忍「`NAME(` 换行后才写码值」形态（否则漏 `BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS` 得 99 假红），`import.meta.url` 相对路径保证 Vitest / 构建双环境可用。
2. **待办池四点收口（fix-backlog-batch3，B 档，刚刚合入）**：`src/main` 9 文件（含 2 新增类 `BatchIdsGuard` / `ChildHostAttachmentCleaner`）+ 12 controller 接入 + `frontend/` 3 处错误码镜像 + 6 测试类，零迁移 / 零新依赖：
   - **⑤ duplicate-key 三出口精确码 92029**：`ErrorCode.DATA_ALREADY_EXISTS`(92029) 经 `SQLExceptionHandler` 三态收敛统一出口，**文案逐字节不变**、仅 `code` 0→92029；前端 `isApiSuccess` 只判 `code !== 1`，**零行为影响**
   - **⑨ `BatchIdsGuard` 统一闸**：空集 / 全 null → 50001（`PARAM_EMPTY`）、去重后 > 500 → 50007（`PARAM_OUT_OF_RANGE`，文案带上限与实际值）；**16 个批量端点接入**（12 controller），**不做服务端自动分批**（分批会把一次调用拆成多事务、产生半提交，事务边界留在调用方）
   - **⑩ `batchLogicalDeleteByIds` 单趟化**：级联 N 轮 → 1 轮 + 主表单条 `IN`；`afterCommit` 注册 N→1，派发语义与回滚行为不变
   - **⑪ `removeByIds` 跨模型误删修复 + 子宿主附件清理**：只删 `model_name` 过滤后行（原按裸 id 删跨模型行）；新增 `ChildHostAttachmentCleaner` 两级闭包子宿主附件清理（公司 / 联系人 / 商机三处物理级联删调用方，`deleteCascade` **前**、**同事务**）
3. **待办池三点收口（fix-backlog-batch2，B 档）**：`src/main` 3 文件 + `application.yml` + `frontend/openapi.yaml` 契约镜像，零迁移 / 零新错误码 / 零新依赖：⑦ `SQLExceptionHandler` 四子项（越界守卫 `bareIndexName()`、删恒真判断与死分支、键空间隔离 `slz.unique-index-field-map`、文案改善走 `getNameByIndex`）；⑧ 关联维护 5 DTO null-id 校验（`@NotNull` + `ValidationGroups.Update` 分组，创建路径零回归）；⑫ `RuleSelfRagReflector` 阈值注入统一（`CitationThresholdsResolver`，跨答案热生效）
4. **测试加固五点（harden-test-backlog-batch1，C 档）**：空格形态纯前瞻扫描 / `CustomerPoolReleaseRoundTripIT` 真库回环 / `TokenBudget` volatile 区别力三件套 / 9 处错误码双锁断言 / A-7 null id 五例
5. **引用对齐三阈值动态化（fix-citation-threshold-config，B 档）**：`rag.retrieval.citation.keepMin` / `remapMin` / `tieMargin` 入 `DynamicConfigKeyRegistry`（Double，OPERATIONAL，越界拒写）；`AiChatStreamFinalizer` 每流解析一次 → `CitationAligner.align(answer, sources, thresholds)`；核心零 Spring 零外呼，默认零漂移；定级封闭表 OPERATIONAL 32→35、census 60→63
6. **RAG 评测三盲区补齐（expand-rag-eval-blind-spots，B 档）**：多轮（`RagBenchmarkCase.history` + MT-01~05）/ 时序（`Category.TEMPORAL` + TP-01~04）/ 拒答（`CaseOutcome.refused`，D16 未填报不再默认放行）；`SUITE_VERSION` 5.0→6.0、标准套件 63→72；v6.0 真跑锚 `baseline-v7.json` 缺席属另卡授权
7. **五路语义同步 hook 收敛（fix-semantic-hook-support-unify，B 档）**：五个 `*SemanticHookSupport` 收敛为「一个基类 + 五个薄门面」，`reingestById` 泛型化为函数式注入，调用点零改动
8. **ProjectFile 域收口四点合一（fix-projectfile-txn-and-batch-insert，B 档）**：三上传合一 + 磁盘 IO 出事务（短事务 `insertBatch` + 失败全量清盘补偿）；附件 saveBatch 先盘后库；备注先校验后批量；活动创建双表批量
9. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档 / 拍板形态 C）**：删 `SALES_STAGE_APPROVAL_RELATIONS → ApprovalAttachmentEntity` 死边；商机删除显式补删审批附件
10. **循环写收口批 A-6 / A-8 / A-10（fix-engineering-debt-loop-write-batch）**：级联删 N 轮→1 轮；关联维护 2N→2；用户批删单条化并修复 MP `NOT_NULL` 策略下 `setEmail(null)`/`setPhone(null)` 被丢弃的 PII 清理缺陷
11. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone）**：分页 N+1 收敛 3N+1→2；双软删循环 2N→1；`SQLExceptionHandler` 7 项列名映射迁入 `application.yml`
12. **待办池 MINOR 家族合卡（M-1 四形态收敛 / 摄取 96004 / 公海 assign 闸 / release 置空 / TokenBudget volatile / 近义码）**：`BaseException(String)` 四形态 95/95 收敛至 `ErrorCode` + 防回归门禁；assign 目标用户存在/在职闸前置行锁；`TokenBudgetProperties` 三字段 volatile
13. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传前置 canWriteAttachments 属主鉴权 12002；重置密码 DTO 校验收口 + 非超管禁改超管密码闸；invoice_info 纳管 DataScope 与 voidInvoice 属主闸
14. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`deleteFile` 软删联动向量与 chunk 物理删；补齐 `assertCanReadContract`/`assertCanReadTask`；`merge-gate.sh` 8 门→10 门
15. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）**：`UserPasswordSupport` 严格校验旧密（92028）、拒绝新旧同密；`PrivacyAspect` 补齐脱敏路径
16. **用户自服务防提权越权加固（B-1）**：`updateMyInfo` 对 `roleId`/`deptId`/`status` Fail-Closed 拦截（12002），SET 子句走白名单
17. **单副本单体部署契约与架构守卫硬化（I-4）** / 18. **数据权限切面 Fail-Closed 治理（I-1）**：`replicas: 1` + `PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true`；`QueryWrapperAspect` 异常重抛 12002 不静默放行
19. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单收敛；`POST /user/logout` + Caffeine 本地黑名单与冻结/离职即时感知；`LoginAttemptService` 双维度防爆破
20. **商机阶段流转审批阻断（D4）** / 21. **确认链路写权限再审计（D6）**：无审批记录抛 30003；`PendingActionConfirmPermissionAuditTest` 从业务入口穿透归属校验与终态判定
22. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；动态配置键 66→60
23. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE；50 上限以 sys_user 行锁串行化；锁序「先用户后客户」零死锁面
24. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller）
25. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)
26. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → 幂等建高优待办 → 每日 4 点错峰调度
27. **CustomerInsightPanel 双宿主接线** / 28. **AI 诊断建议一键转 Action**：客户详情弹窗与 AI 助手抽屉双宿主；`InsightToActionService` 纯结构化映射（零外呼）
29. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点；单事务转化客户+联系人+商机并异步向量同步
30. **合同履约到期预警与逾期应收催款待办联动**：到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度
31. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003
32. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合、父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸

---

## 3. 当前在途与候选队列（fix-backlog-batch4 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `68d563c`；物料归档至 `openspec/changes/archive/fix-backlog-batch4/`（proposal + tasks，R100）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根无未跟踪散落件，证据日志留在 `.gitignore` 忽略区不入库）
- **待办池 2 → 0 清零（本卡消化 ⑬⑭，逐条划销）**：~~⑬`sales_stage_approval` 域附件残留（公司 / 联系人两入口）~~ / ~~⑭`error-code-map.test.ts` 硬编码总数改为从 Java 枚举生成~~（对应归档 handoff §1~§4）
- **新进 1 条（本卡执行中新发现，低优先，他人所有权）**：**⑮ `frontend/typed-router.d.ts` 生成物归属**——`unplugin-vue-router` 在本地跑 Vitest 会重写该文件（本卡两次运行均 `git checkout --` 还原、未纳入提交），当前靠人工还原收尾；宜纳入忽略或改 CI 生成，属 frontend 工程决策，**他人所有权，本线不擅动**
- **下批推荐**：无强制项，按 Owner 指令插队；⑮ 待前端 owner 定夺
- **下批候选池总账**：`work/_paa-candidates-pool-v2.md`（观察树未跟踪留档，长期储备保留）
- **长期储备**：在线考试子域（`work/_online-exam-backlog.md` 留档）、多模态图文混合检索、**v6.0 真跑落锚 `baseline-v7.json`（需另卡授权）**

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责发现验证、方案架构、写任务卡、复核裁决；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权执行 `git push`、改写历史（reset/rebase/amend/force-push）、删分支、生产外呼、修改库结构或破坏性改动
3. **任务卡派发标准**：严格采用 [`work/README.md`](../work/README.md) 的"目标约束型"短任务卡（< 40 行），严禁微观代码语法微操
4. **复核强度分档（`docs/review-intensity-policy.md`）**：**C 档**（文档/日志/纯测试加固）主 Agent 审查 diff 与机械门禁、不派复核；**B 档**（常规业务代码+测试）依靠门禁脚本退出码 0 + 主 Agent 审 diff 裁决；**A 档**（DB迁移/权限变更/真跑/契约）才启动独立复核 Agent
5. **子 Agent 派发纪律（Owner 亲发纪律）**：执行与复核子 Agent **均由 Owner 亲自派发**，严禁主 Agent 自行代派；主 Agent 只产出《执行提示词》与《复核提示词》交付 Owner
6. **合并闭环纪律**：合入走 `git merge --no-ff` 且**保留分支**；合并后先做**树等价判等**（`M^{tree}` vs 分支 tip `^{tree}`），相等即可走快车道（免本地复跑，CI `build-and-gate` 兜底），不等则回退全量 clean verify + merge-gate

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，最新 §141）
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
