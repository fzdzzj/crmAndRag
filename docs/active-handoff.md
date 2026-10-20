# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-20（fix-backlog-batch2 待办池三点收口 ⑦⑧⑫，B 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新（Rolling Overwrite），严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@36e729b + 本笔（M2 归档看板笔）`（本卡闭环：基点 `9cbf55e` → 分支 3 笔 `0dbae59` / `1234f86` / `10e7797` → M1 merge --no-ff `36e729b`（零冲突，分支 `feature/fix-backlog-batch2` 保留）→ M2 归档看板笔即本笔）
- **测试基线四元组**：`merge=36e729b` + Surefire **报告 299 / 用例 1871 / F0 / E0 / S0** + Failsafe **报告 33 / 用例 122 / F0 / E0 / S7** + `CI=#140`（只增不减：本卡前 297/1848/0、33/122/7；+2 报告 +23 用例、failsafe 零漂移）。`scripts/test-baseline.txt` = `source-revision=36e729b`、`measured-at=2026-10-20T16:35:04Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随 M2 笔持久化）
- **验证路径 = M1 树等价快车道（连续第四卡启用）**：`git rev-parse "36e729b^{tree}"` 与 `git rev-parse "10e7797^{tree}"` 同为 `b18594982cf52a13dfc2cc2e6bedb398d5e1f4b6`，**逐字节相等** → 判定树等价，执行端已在该树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS** + `merge-gate.sh` **10/10 PASS** + 基线 `EXIT=0` → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账（`--update` 源修订=M + 校验 EXIT=0）。**本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底：CI 绿 = M1 验证闭环**
- **CI**：M1 合并轮 CI **#140**（run id `39458811517`，head `36e729b`，6/6 全绿）；M2 看板轮 CI **#141**（= #140+1 预测，实测号以 `docs/main-agent-logbook.md` §139 为准）
- **复核**：**B 档 GO**（业务代码 + 测试）—— 门禁脚本退出码 0 + 主 Agent 审 diff 裁决，不派独立复核；归档物料 = `openspec/changes/archive/fix-backlog-batch2/`（proposal + tasks，R100）+ `work/archive/closed-cards/`（handoff R100 + 任务卡 `add -f`）
- **环境实测**：`docker info` = `29.6.2 / Docker Desktop` **在线**（快车道不依赖 Docker，留痕备真跑；执行端首轮未起，`docker desktop start` 拉起后 failsafe 恢复 122/7）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **待办池三点收口（fix-backlog-batch2，B 档，刚刚合入）**：`src/main` 3 文件 + `application.yml` + `frontend/openapi.yaml` 契约镜像，零迁移 / 零新错误码 / 零新依赖：
   - **⑦ `SQLExceptionHandler` 四子项**：① 越界守卫——`indexName.split("\\.")[1]` 裸下标改 `bareIndexName()`（`lastIndexOf('.')` + substring/trim，`.` / `""` / `null` 一律回落），`doesn't have a default value` 路径的 `split("`")` 同步改长度判断；② 删 `:50` 恒真判断（`split.length > 0`）与死分支，duplicate-key 收敛为 `duplicateKeyResult` 一条三态；③ **键空间隔离**——索引名映射迁入新键段 `slz.unique-index-field-map`（7 键 `uk_phone`/`uk_email`/`uk_company_name`/`idx_mobile`/`uk_contract_no`/`uk_payment_no`/`uk_invoice_no`，值与改前逐项一致），`slz.database-field-map` 只留 20 个**列名**键；④ **文案改善**——缺省值路径改走 `getNameByIndex`（列名映射），`缺少必填字段：数据` → `客户ID`，未知列 → `未知字段`；13 例逐态锁三态 + 6 入参形态
   - **⑧ 关联维护 5 DTO null-id 校验（拍板行为变化）**：4 个关联 DTO 身份字段（`BusinessActivityUserDTO.userId` / `BusinessActivityContactDTO.contactId` / `AddActivityUserRequestDTO.userId` / `AddActivityContactRequestDTO.contactId`）挂 `@NotNull`（Default 组），`BusinessActivityDTO.id` 挂新增 `pojo/dto/ValidationGroups.Update` 分组 → **创建路径零回归**（创建请求本不带 id）；controller **零 diff**，边界由 Bean Validation 前置拦截（`50002` + `参数校验失败`，HTTP 仍 200），改前「深层撞库 500 / null 项静默跳过但报成功」两类行为消除；**`frontend/openapi.yaml` 契约镜像 4 个 `required` 块**（+8 行，走 `bash scripts/check-openapi-consistency.sh --update`，前例 `47ad58b`）；新增 13 例（MockMvc 6 + 打包校验器 7）
   - **⑫ `RuleSelfRagReflector` 阈值注入统一**：原读编译期 `CitationAligner.KEEP_MIN`（0.12）改 `ObjectProvider<DynamicConfigService>` → `CitationThresholdsResolver.resolve()` → `Supplier<CitationThresholds>`（`reflect()` 每答案解析一次，跨答案热生效），三构造器（无参=默认 / 包内 Supplier=单测 / `@Autowired`=生产），与 `AiChatStreamFinalizer` 同一范式，`SelfRagReflector` 冻结契约零改动、无配置时与改前逐字节一致；用例 5→11（升降向各一 + 默认回归 + 生产路径回落）
2. **测试加固五点（harden-test-backlog-batch1，C 档）**：
   - **① 空格形态纯前瞻**：`BaseExceptionConvergenceTest` 由字面 `indexOf("new BaseException(")` 改 `Pattern("new\\s+BaseException\\s*\\(")` 前瞻；负向自证 4 断言；扫描点下限 `>300` 与零违规断言未动
   - **② release 真库回环 IT +3**：`CustomerPoolReleaseRoundTripIT`（Testcontainers MySQL）锁 `owner_id` NULL + `pool_status=1` + 释放时间/原因 + `customer_pool_log` 成对；前置自检堵 `NOT_NULL` 策略下 `updateById` 空操作假绿
   - **③ volatile 区别力三件套 + mutation 实证**：反射 `Modifier.isVolatile` 逐字段结构断言 + 负向自证 + 2s 有界自旋可见性冒烟；mutation 删 volatile → `Failures 1` 红后还原（可见性用例定位语义冒烟、不做唯一判据）
   - **④ 9 处码断言（实际增量 5 处 + 邻接 1）**：`PermissionServiceImplMessageCodeTest` 补权限域零覆盖（50001/13001/92008）；`UserServiceImplTest` 两处补 13002；每处「字面码 + `ErrorCode.X.getCode()`」双锁
   - **⑤ A-7 null id 五例**：`UserHandoverServiceImplTest` 3→8，`selectBatchIds` 零调用断言 + `ArgumentCaptor` 锁 id 集合
3. **引用对齐三阈值动态化（fix-citation-threshold-config，B 档）**：`rag.retrieval.citation.keepMin` / `remapMin` / `tieMargin` 入 `DynamicConfigKeyRegistry`（Double，OPERATIONAL，越界拒写）；`AiChatStreamFinalizer` 每流解析一次 → `CitationAligner.align(answer, sources, thresholds)`；核心零 Spring 零外呼，默认零漂移；定级封闭表 OPERATIONAL 32→35、census 60→63
4. **RAG 评测三盲区补齐（expand-rag-eval-blind-spots，B 档）**：多轮（`RagBenchmarkCase.history` + MT-01~05）/ 时序（`Category.TEMPORAL` + TP-01~04）/ 拒答（`CaseOutcome.refused`，D16 未填报不再默认放行）；`SUITE_VERSION` 5.0→6.0、标准套件 63→72；v6.0 真跑锚 `baseline-v7.json` 缺席属另卡授权
5. **五路语义同步 hook 收敛（fix-semantic-hook-support-unify，B 档）**：五个 `*SemanticHookSupport` 收敛为「一个基类 + 五个薄门面」，`reingestById` 泛型化为函数式注入，调用点零改动
6. **ProjectFile 域收口四点合一（fix-projectfile-txn-and-batch-insert，B 档）**：三上传合一 + 磁盘 IO 出事务（短事务 `insertBatch` + 失败全量清盘补偿）；附件 saveBatch 先盘后库；备注先校验后批量；活动创建双表批量
7. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档 / 拍板形态 C）**：删 `SALES_STAGE_APPROVAL_RELATIONS → ApprovalAttachmentEntity` 死边；商机删除显式补删审批附件
8. **循环写收口批 A-6 / A-8 / A-10（fix-engineering-debt-loop-write-batch）**：级联删 N 轮→1 轮；关联维护 2N→2；用户批删单条化并修复 MP `NOT_NULL` 策略下 `setEmail(null)`/`setPhone(null)` 被丢弃的 PII 清理缺陷
9. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone）**：分页 N+1 收敛 3N+1→2；双软删循环 2N→1；`SQLExceptionHandler` 7 项列名映射迁入 `application.yml`
10. **待办池 MINOR 家族合卡（M-1 四形态收敛 / 摄取 96004 / 公海 assign 闸 / release 置空 / TokenBudget volatile / 近义码）**：`BaseException(String)` 四形态 95/95 收敛至 `ErrorCode` + 防回归门禁；assign 目标用户存在/在职闸前置行锁；`TokenBudgetProperties` 三字段 volatile
11. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传前置 canWriteAttachments 属主鉴权 12002；重置密码 DTO 校验收口 + 非超管禁改超管密码闸；invoice_info 纳管 DataScope 与 voidInvoice 属主闸
12. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`deleteFile` 软删联动向量与 chunk 物理删；补齐 `assertCanReadContract`/`assertCanReadTask`；`merge-gate.sh` 8 门→10 门
13. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）**：`UserPasswordSupport` 严格校验旧密（92028）、拒绝新旧同密；`PrivacyAspect` 补齐脱敏路径
14. **用户自服务防提权越权加固（B-1）**：`updateMyInfo` 对 `roleId`/`deptId`/`status` Fail-Closed 拦截（12002），SET 子句走白名单
15. **单副本单体部署契约与架构守卫硬化（I-4）** / 16. **数据权限切面 Fail-Closed 治理（I-1）**：`replicas: 1` + `PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true`；`QueryWrapperAspect` 异常重抛 12002 不静默放行
17. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单收敛；`POST /user/logout` + Caffeine 本地黑名单与冻结/离职即时感知；`LoginAttemptService` 双维度防爆破
18. **商机阶段流转审批阻断（D4）** / 19. **确认链路写权限再审计（D6）**：无审批记录抛 30003；`PendingActionConfirmPermissionAuditTest` 从业务入口穿透归属校验与终态判定
20. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；动态配置键 66→60
21. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE；50 上限以 sys_user 行锁串行化；锁序「先用户后客户」零死锁面
22. **写端点校验收口**：86 处写端点 `@RequestBody` 参数 100% 挂 `@Valid`（27 controller）
23. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 逐实体委托计数映射；复用 KNOWLEDGE_ADMIN_MANAGE(900)
24. **客户流失预警与自动化再激活**：确定性规则（三源皆空 + 60 天窗口）扫描私海客户 → 幂等建高优待办 → 每日 4 点错峰调度
25. **CustomerInsightPanel 双宿主接线** / 26. **AI 诊断建议一键转 Action**：客户详情弹窗与 AI 助手抽屉双宿主；`InsightToActionService` 纯结构化映射（零外呼）
27. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead` 表；`/sales/lead` 6 端点；单事务转化客户+联系人+商机并异步向量同步
28. **合同履约到期预警与逾期应收催款待办联动**：到期/超期与逾期未结回款统计；幂等生成高优 ContactTask；每天 3 点调度
29. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log` 流转表；认领配额 50 上限拦截 20003
30. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合、父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸

---

## 3. 当前在途与候选队列（fix-backlog-batch2 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `36e729b`；物料归档至 `openspec/changes/archive/fix-backlog-batch2/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根无未跟踪散落件，证据日志留在 `.gitignore` 忽略区不入库）
- **待办池 7 → 4（本卡消化 ⑦⑧⑫，逐条划销）**：~~⑦`SQLExceptionHandler` 历史缺陷收口~~ / ~~⑧关联维护 5 DTO null-id 校验~~ / ~~⑫`RuleSelfRagReflector` 阈值分叉~~（对应归档 handoff §1~§3；D1 契约镜像裁决、D3 卡面假设纠偏、D4 Update 分组取舍见 handoff §8）
- **剩余四条（原样保留）**：
  1. ⑤「已存在」精确码产品评估（50002 系近义）；
  2. ⑨批量 IN 长列表 controller 分批硬上限：级联/关联批量入口把 id 全量塞进 `IN`，宜在 controller 层加分批与 row 上限；
  3. ⑩【复核 I4】`CustomerCompanyServiceImpl:303-309` afterCommit 型循环收口：`batchLogicalDeleteByIds` 仍是 `for (id) logicalDeleteById(id)` 且无 `PARAM_EMPTY` 闸；
  4. ⑪【附件孤儿债】`business_activity` / `contact_task` / `assist_request` 删除路径不删附件（父对象物理级联删绕开 canonical 附件清理），另含 `ApprovalAttachmentServiceImpl#removeByIds` 按 `model_name` 选行却按裸 id 删行的跨模型风险。
- **下批推荐**：**⑤「已存在」精确码产品评估卡**（需产品/ Owner 定码值口径，改动面最小）或按 Owner 指令插队；**⑨⑩⑪ 属大改类留池**（批量上限 / afterCommit 循环 / 附件孤儿债均牵动跨域语义，宜单卡单做）
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
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，最新 §139）
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
