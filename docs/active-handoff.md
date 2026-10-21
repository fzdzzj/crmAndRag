# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-21（d8-image-asset-rollback 图片摄取失败孤儿对象即时回滚，C 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@91c9f47`（= origin/master；本卡闭环：基点 `fdf5db2` → 分支 2 笔 `a9be513` fix / `e7002f7` 基线 → M1 `git merge --no-ff` **`d10815c`**（零冲突，分支 `feature/d8-image-asset-rollback` **永久保留**）→ M1 基线收口 `91c9f47`（重锚 source-revision 到 merge tip）；M2 看板/归档笔随本笔持久化）
- **测试基线四元组**：`merge=d10815c` + Surefire **报告 309 / 用例 1924 / F0 / E0 / S0** + Failsafe **报告 35 / 用例 126 / F0 / E0 / S7** + `CI=#148`（M1 轮，Run 39599694836，= #147+1 预测，实测号以 `docs/main-agent-logbook.md` §143 为准）。只增不减：本卡前 308/1919/0 + 35/126/7 → +1 报告 +5 用例（failsafe 持平）。`scripts/test-baseline.txt` = `source-revision=91c9f47`、`measured-at=2026-10-21T12:25:14Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第八卡启用）**：`git rev-parse "d10815c^{tree}"` 与 `git rev-parse "e7002f7^{tree}"` 同为 **`7addcc82e7c71ec0ce136eead7c151fea130d6db`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1924 / failsafe 126 / 7 skip，四静态全绿）+ `merge-gate.sh` **10/10 PASS** + 基线 `EXIT=0` → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账（source-revision 与时间戳两行，计数零漂移）；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#148 已绿 = M1 验证闭环**）
- **复核**：**C 档 GO**（清收逻辑补偿 + 纯单测，主 Agent 亲审 diff，不派独立复核）—— 四判据逐条通过（① 零迁移：diff 无 `db/migration`；② 零权限/契约/真跑/依赖：零新权限常量、零契约字段、`RAG_VISION_PDF_REAL` 零新增、VLM/向量全 mock；③ 失败语义：回滚删除 WARN 不抛、原始异常 `isSameAs` 原样上抛；④ 成功路径零变化：`never().delete()` 断言 + `chunkCount==2` 锁死）。**Owner 已拍板接受 `DocumentIngestionService` NcssCount 类级豁免**（理由：拆分会切「存储→切片→嵌入→向量→状态回写」同一事务边界，收益远小于风险；全仓类级 suppression 由此 1→2 处）。归档物料 = `openspec/changes/archive/d8-image-asset-rollback/`（proposal + tasks）+ `work/archive/closed-cards/`（handoff + 任务卡 `add -f`）
- **前端披露**：本卡 `frontend/` 零 diff（零依赖变更；`typed-router.d.ts` 生成物零提交）
- **环境实测**：本卡走快车道，本地零 Maven 复跑（不依赖 Docker）；执行端 verify 时 Docker **29.6.2** 在线，failsafe 7 skipped 与基线口径一致；CI 盯跑本机 REST 直连 403 限流，改用 harness `web_fetch` 独立出口（`/commits/<sha>/check-runs` 取 6 job conclusion）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **图片摄取失败孤儿对象即时回滚（d8-image-asset-rollback，C 档，刚刚合入）**：收口上一卡 Add-kb-image-slice-retrieval 留痕的 D8 偏差——图片对象在切片行落库**之前**已写进 `FileStorageService`，当 `chunkEmbedAndWrite` 失败导致 DB 事务回滚时 storageKey 随事务「消失」，对象成无行引用、端点不可达的永久孤儿。**方案**：`PdfImageSliceIngestService` 加 `STAGED_ASSET_KEYS`（`ThreadLocal<Set>`）+ `beginAssetCollection/endAssetCollection/rollbackStagedAssets`，`store()` 成功且非空才登记；`DocumentIngestionService#ingest` 开作用域 → 两 catch 先回滚再 `markFailed` 原样上抛 → finally `endAssetCollection`。**关键语义**：成功路径零额外删除、回滚删除失败 WARN 不抛（不替换原始异常）、`never().delete(DOC_KEY)` 防误删文档原件。零迁移 / 零新权限 / 零契约 / 零真外呼 / 零依赖。`ImageSliceAssetRollbackTest` 304 行 / 5 用例。
2. **知识库图片切片摄取 + IMAGE 回显（add-kb-image-slice-retrieval，A 档）**：`src/main` 19 文件（7 新增 / 12 存量）+ 5 测试类 + `frontend/` 2 处，**零迁移 / 零新依赖 / 零新权限常量**。**Owner 2026-10-21 拍板方案 B（文本降维闭环）**——不建图文共享向量空间，走「图片对象提取 → PNG 资产落 `FileStorageService` → 每图 VLM 描述 → 描述文本切片入既有 chunk/embed 链路」，切片 `extra_metadata_json` 携资产引用（资产 id = 切片行主键，天然防悬空）：摄取侧 `PDResources#getXObjectNames` 取 `PDImageXObject`（非整页渲染、按 XObject 名排序可复现、stencil/非位图/最短边<64px 跳过、`scaleToMaxEdge(1600)` 转 PNG）；每图 `vision` → 质量闸门 → 落盘 → 生成描述切片；**单图失败只跳过该图**。开关 `rag.retrieval.image-slice-ingest.enabled` **默认 false**（关 = 零提取/零 VLM/零落盘，默认行为逐字节一致）、`kb-allowlist`（KB 灰度）、`max-images-per-document`（默认 5，1~20）；成本类键**不进** 12 键 `KbRetrievalStrategyWhitelist`（与 `vision-pdf.*` 同处置）。回显侧 `SourceReference.sourceType` 扩 `IMAGE`（受控解冻）；`SourceReferenceFactory` 抽唯一实现；**SSE `sources` 结构零改动**；新端点 `GET /knowledge/images/{assetId}`（挂 803，权限不宽于所属 KB，单图 1 MiB 有界读取）。删除/重建联动 `ImageAssetCleanup` 接 deleteFile C-11 双清链与 reingest。
3. **收尾小卡 ⑬⑭（fix-backlog-batch4，B 档）**：`src/main` 1 文件（`ChildHostAttachmentCleaner`）+ 2 测试类 + `frontend/src/constants/error-code-map.test.ts` 一处：⑬ 审批域附件清理补齐——公司/联系人两入口按 `opportunity_id` 查 `sales_stage_approval` 行再 `removeByAndIds`；商机入口不重复接线。⑭ Vitest 总数断言生成化——Node fs 读错误码枚举正则计数、删硬编码总数。
4. **待办池四点收口（fix-backlog-batch3，B 档）**：`src/main` 9 文件 + 12 controller 接入 + `frontend/` 3 处错误码镜像 + 6 测试类：⑤ duplicate-key 三出口精确码 92029；⑨ `BatchIdsGuard` 统一闸（16 批量端点）；⑩ `batchLogicalDeleteByIds` 单趟化；⑪ `removeByIds` 跨模型误删修复 + `ChildHostAttachmentCleaner`。
5. **待办池三点收口（fix-backlog-batch2，B 档）**：⑦ `SQLExceptionHandler` 四子项；⑧ 关联维护 5 DTO null-id 校验；⑫ `RuleSelfRagReflector` 阈值注入统一。
6. **测试加固五点（harden-test-backlog-batch1，C 档）**：空格形态纯前瞻扫描 / 客户公海回环 IT / `TokenBudget` volatile 三件套 / 9 处错误码双锁断言 / A-7 null id 五例。
7. **引用对齐三阈值动态化（fix-citation-threshold-config，B 档）**：`keepMin`/`remapMin`/`tieMargin` 入 `DynamicConfigKeyRegistry`。
8. **RAG 评测三盲区补齐（expand-rag-eval-blind-spots，B 档）**：多轮/时序/拒答；`SUITE_VERSION` 5.0→6.0、标准套件 63→72；v6.0 真跑锚 `baseline-v7.json` 缺席属另卡授权。
9. **五路语义同步 hook 收敛（fix-semantic-hook-support-unify，B 档）**：五 `*SemanticHookSupport` 收敛为「一个基类 + 五个薄门面」，`reingestById` 泛型化。
10. **ProjectFile 域收口四点合一（fix-projectfile-txn-and-batch-insert，B 档）**：三上传合一 + 磁盘 IO 出事务。
11. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档）**：删 `SALES_STAGE_APPROVAL_RELATIONS → ApprovalAttachmentEntity` 死边。
12. **循环写收口批 A-6/A-8/A-10（fix-engineering-debt-loop-write-batch）**：级联删 N 轮→1；关联维护 2N→2；用户批删单条化 + PII 清理修复。
13. **工程债三合卡 A-7/A-9/A-1（fix-engineering-debt-nplus1-loop-clone）**：分页 N+1 3N+1→2；双软删循环 2N→1；`SQLExceptionHandler` 列名映射迁 yml。
14. **待办池 MINOR 家族合卡**：`BaseException(String)` 四形态收敛至 `ErrorCode` + 防回归门禁；assign 闸前置行锁；`TokenBudgetProperties` 三字段 volatile。
15. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传前置 canWriteAttachments 属主鉴权 12002；非超管禁改超管密码；invoice_info 纳管 DataScope。
16. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`deleteFile` 软删联动向量与 chunk 物理删；`merge-gate.sh` 8 门→10 门。
17. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）** / 18. **用户自服务防提权越权加固（B-1）**：`updateMyInfo` 对 `roleId`/`deptId`/`status` Fail-Closed（12002），SET 白名单。
19. **单副本单体部署契约与架构守卫硬化（I-4）** / 20. **数据权限切面 Fail-Closed 治理（I-1）**：`QueryWrapperAspect` 异常重抛 12002 不静默放行。
21. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单收敛；`POST /user/logout` + Caffeine 黑名单；`LoginAttemptService` 双维度防爆破。
22. **商机阶段流转审批阻断（D4）** / 23. **确认链路写权限再审计（D6）**：无审批记录抛 30003。
24. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 双重拦截；动态配置键 66→60。
25. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE；锁序「先用户后客户」零死锁面。
26. **写端点校验收口**：86 处写端点 `@RequestBody` 100% 挂 `@Valid`（27 controller）。
27. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 委托计数映射（复用 900）。
28. **客户流失预警与自动化再激活**：确定性规则扫描 → 幂等建高优待办 → 每日 4 点错峰调度。
29. **CustomerInsightPanel 双宿主接线** / 30. **AI 诊断建议一键转 Action**：`InsightToActionService` 纯结构化映射（零外呼）。
31. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead`；`/sales/lead` 6 端点；单事务转化 + 异步向量同步。
32. **合同履约到期预警与逾期应收催款待办联动**：到期/超期与逾期未结回款统计；幂等高优 `ContactTask`；每天 3 点调度。
33. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log`；认领配额 50 上限拦截 20003。
34. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合、父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（d8-image-asset-rollback 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `91c9f47`；物料归档至 `openspec/changes/archive/d8-image-asset-rollback/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根本卡散落件已 SHA256 核对后删除）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡更新）**：
  - **多模态真共享空间升级卡（登记待立项，A 档大卡）**：图文共享向量空间 + collection 路由 + 资产独立生命周期/审计——本卡方案 B 是「文本降维」过渡形态，不预埋；触发条件 = 灰度真跑显示描述质量不足或图文跨模态召回有明确缺口（立项骨架 `work/_multimodal-shared-space-backlog.md`）
  - **D8 后遗症治理（候选，低优先）**：本卡接受 `DocumentIngestionService` NcssCount 类级豁免（全仓 suppression 1→2 处）；如需收敛，「编排类拆分」小卡可另立（与本卡解耦，但拆分会切事务边界，收益存疑）
  - **图片摄取生产灰度真跑（授权留 Owner）**：真 VLM 描述质量与成本基线（`RAG_VISION_PDF_REAL` opt-in），本卡全程 mock、零外呼
  - 在线考试子域（`work/_online-exam-backlog.md` 留档）、**v6.0 真跑落锚 `baseline-v7.json`（需另卡授权）**
- **下批推荐**：无强制项，按 Owner 指令插队
- **下批候选池总账**：`work/_paa-candidates-pool-v2.md`（观察树未跟踪留档，长期储备保留）

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责发现验证、方案架构、写任务卡、复核裁决；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权执行 `git push`、改写历史（reset/rebase/amend/force-push）、删分支、生产外呼、修改库结构或破坏性改动；**禁改已合入迁移脚本**
3. **任务卡派发标准**：严格采用 [`work/README.md`](../work/README.md) 的"目标约束型"短任务卡（< 40 行），严禁微观代码语法微操
4. **复核强度分档（`docs/review-intensity-policy.md`）**：**C 档**（文档/日志/纯测试加固）主 Agent 审 diff 不派复核；**B 档**（常规业务代码+测试）门禁退出码 0 + 主 Agent 审 diff 裁决；**A 档**（DB 迁移/权限变更/真跑/契约）必须独立复核 Agent 四判据
5. **子 Agent 派发纪律（Owner 亲发纪律）**：执行与复核子 Agent **均由 Owner 亲自派发**，严禁主 Agent 自行代派
6. **合并闭环纪律**：合入走 `git merge --no-ff` 且**保留分支**；合并后先做**树等价判等**（`M^{tree}` vs 分支 tip `^{tree}`），相等即走快车道（免本地复跑，CI `build-and-gate` 兜底），不等则回退全量 clean verify + merge-gate

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（上一卡 `IMAGE` 取值解冻登记于变更日志 2026-10-21 条）
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §143**）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只允许 `--update` 从一次真实运行写入）

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **当前主 Agent 结束会话前必须做**：
  1. 若有新卡合入或基线变动，更新本看板的 §1、§2、§3；
  2. 若产生新的任务卡，确保合入后归档至 `work/archive/closed-cards/`；
  3. 严禁在本文件下方追加流水账，保持全文在 120 行内整体覆盖保存。
- **新主 Agent 启动第一动作**：
  1. 读取 `AGENTS.md` + 本文件（1 分钟内完成背景建立）；
  2. 实测 `git status --short` 核实工作区清洁度；
  3. 立即响应用户需求，零冗余开工！