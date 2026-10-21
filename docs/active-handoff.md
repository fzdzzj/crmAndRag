# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-21（add-kb-image-slice-retrieval 图片切片检索 MVP，A 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@352bec6 + 本笔（M2 归档看板笔③）`（本卡闭环：基点 `b7b750f` → 分支 3 笔 `2cb8707` feat / `0bc1a27` test+基线 / `45ef9ec` docs（openspec 卡）→ M1 `git merge --no-ff` **`352bec6`**（零冲突，分支 `feature/add-kb-image-slice-retrieval` **永久保留**）→ M2 三笔 `4b1e6f9`（openspec 归档 R100）/ `3ff8650`（交接件入 closed-cards）/ 本笔（看板 + 基线台账））
- **测试基线四元组**：`merge=352bec6` + Surefire **报告 308 / 用例 1919 / F0 / E0 / S0** + Failsafe **报告 35 / 用例 126 / F0 / E0 / S7** + `CI=#146`（M1 轮，Run 39577985426）与 `CI=#147`（M2 看板轮，= #146+1 预测，实测号以 `docs/main-agent-logbook.md` §142 为准）。只增不减：本卡前 304/1894/0 + 34/124/7 → +4 报告 +25 用例、failsafe +1 报告 +2 用例（skipped 不变）。`scripts/test-baseline.txt` = `source-revision=352bec6`、`measured-at=2026-10-21T09:33:34Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第七卡启用）**：`git rev-parse "352bec6^{tree}"` 与 `git rev-parse "45ef9ec^{tree}"` 同为 **`120323a6819ae92f3a7bedfbfab231b6ed2c94d1`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1919 / failsafe 126 / 7 skip，四静态全绿）+ `merge-gate.sh` **10/10 PASS** + 基线 `EXIT=0` → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账（source-revision 与时间戳两行，计数零漂移）；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#146 已绿 = M1 验证闭环**）
- **复核**：**A 档 GO**（业务代码 + 契约受控解冻 + 权限面）—— 四判据逐条通过（① 零迁移：`git show 2cb8707 --stat` 无 `db/migration`，`extra_metadata_json` 为 V3 既有列；② 权限不宽于所属 KB：`GET /knowledge/images/{assetId}` 挂既有 803 + 服务内 KB 授权链，零新权限常量；③ 无真外呼：`RAG_VISION_PDF_REAL` / `RAG_BENCHMARK_REAL` 零新增，`ModelProvider` 全 mock；④ 冻结契约单行追加：`contracts-frozen.md` 仅 +1 行、`SourceReference` record 签名零改动仅 javadoc）；归档物料 = `openspec/changes/archive/add-kb-image-slice-retrieval/`（proposal + tasks + spec-delta，R100）+ `work/archive/closed-cards/`（handoff + 任务卡 `add -f`）
- **前端披露**：本卡 `frontend/` 两处——`src/components/ai/AiAssistantDrawer.vue`（KB 来源 Tag 内 `.source-thumb` 缩略图，`isImageSource` 判 `sourceType=IMAGE`，**用带 token 的 `axiosInstance` 拉 blob + `createObjectURL` 缓存/`onUnmounted` revoke**，裸 `<img src>` 会 401）与 `frontend/openapi.yaml`（契约生成物 +18 行，经 `check-openapi-consistency.sh --update` 唯一入口）；零依赖变更；`typed-router.d.ts` 生成物零提交
- **环境实测**：本卡走快车道，本地零 Maven 复跑（不依赖 Docker）；执行端 verify 时 Docker **29.6.2** 在线，failsafe 7 skipped 与基线口径一致
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **知识库图片切片摄取 + IMAGE 回显（add-kb-image-slice-retrieval，A 档，刚刚合入）**：`src/main` 19 文件（7 新增 / 12 存量：新增 `PdfImageExtractor` / `PdfImageSliceIngestService` / `VisionIngestSupport` / `ImageSliceIngestSupport` / `ImageAssetCleanup` / `SourceReferenceFactory` / `KnowledgeImageAssetService`）+ 5 测试类 + `frontend/` 2 处，**零迁移 / 零新依赖 / 零新权限常量**。**Owner 2026-10-21 拍板方案 B（文本降维闭环）**——不建图文共享向量空间，走「图片对象提取 → PNG 资产落 `FileStorageService` → 每图 VLM 描述 → 描述文本切片入既有 chunk/embed 链路」，切片 `extra_metadata_json` 携资产引用（资产 id = 切片行主键，天然防悬空）：
   - **摄取侧**：`PDResources#getXObjectNames` 取 `PDImageXObject`（**非整页渲染**、按 XObject 名排序可复现、stencil / 非位图 / 最短边 <64px 跳过、`scaleToMaxEdge(1600)` 转 PNG）；每图 `vision(Prompt, ModelCallOptions.defaults())` → 质量闸门（空白 / 去空白 <40 字 / 「（截图不清）」÷汉字 >0.4）→ 落盘 → 生成描述切片；**单图任一环节失败只跳过该图**，绝不断整篇摄取
   - **开关（动态配置 COST 档，超管专写）**：`rag.retrieval.image-slice-ingest.enabled` **默认 false**（关 = 零提取 / 零 VLM / 零落盘，默认行为与升级前逐字节一致）、`kb-allowlist`（空 = 全库，非空 = KB 灰度）、`max-images-per-document`（默认 5，1~20）；判定顺序「存储装配 → 是否 PDF → 总开关 → KB 灰度 → 帽」。成本类键按既有口径**不进** 12 键 `KbRetrievalStrategyWhitelist`（与 `vision-pdf.*` 同处置）
   - **回显侧（D3 受控解冻）**：`SourceReference.sourceType` 取值集扩 `IMAGE`（`chunkId=IMAGE:{资产id}`、`filename`=描述摘要 60 字截断、`pageNo`=页码）；`SourceReferenceFactory` 抽为**唯一映射实现**，文档类分支逐字节保持旧行为；**SSE `sources` 结构零改动**（`AiSsePayloadWriter#toSourceItems` 与 `SseContractTest` 10/10 绿）；新端点 `GET /knowledge/images/{assetId}`（挂 803，服务内走「切片行 → documentId → `uploaded_file` KB → `authorizedKnowledgeBaseIds`」，**权限不宽于所属 KB**，单图上限 1 MiB 有界读取）
   - **删除/重建联动**：`ImageAssetCleanup` 接入 `deleteFile`（C-11 双清链：软删 → 清向量 → **删图片对象** → 物理删切片行）与 reingest（读回原始字节 → 合并 → 删旧资产 → 物理删旧行 → 重写），顺序不可颠倒
   - **偏差留痕 D1~D8**：D1 合并/清理逻辑抽独立类 `ImageSliceIngestSupport`（`DocumentIngestionService` 151 / `DocumentIngestionSupport` 160 顶破 PMD `NcssCount` 150）；D8 **孤儿对象边界**——ingest 失败时已落盘对象残留（无行引用、端点不可达），已由 reingest/delete 清理，即时回滚建议随灰度真跑另立小卡
   - **未做（留后续）**：真多模态升级（图文共享向量空间 + collection 路由）、**生产灰度真跑**（真实 VLM 描述质量 / 成本基线）——均需 Owner 另行授权/立项
2. **收尾小卡 ⑬⑭（fix-backlog-batch4，B 档）**：`src/main` 1 文件（`ChildHostAttachmentCleaner`）+ 2 测试类 + `frontend/src/constants/error-code-map.test.ts` 一处：**⑬ 审批域附件清理补齐**——公司 / 联系人两入口在既有 `opportunities` 一级集之外，按 `opportunity_id` 查 `sales_stage_approval` 行再 `removeByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)`；**商机入口不重复接线**。**⑭ Vitest 总数断言生成化**——Node fs 读错误码枚举正则计数、删硬编码总数
3. **待办池四点收口（fix-backlog-batch3，B 档）**：`src/main` 9 文件 + 12 controller 接入 + `frontend/` 3 处错误码镜像 + 6 测试类：⑤ duplicate-key 三出口精确码 92029；⑨ `BatchIdsGuard` 统一闸（16 批量端点，不做服务端自动分批）；⑩ `batchLogicalDeleteByIds` 单趟化（`afterCommit` N→1）；⑪ `removeByIds` 跨模型误删修复 + `ChildHostAttachmentCleaner` 子宿主附件清理
4. **待办池三点收口（fix-backlog-batch2，B 档）**：⑦ `SQLExceptionHandler` 四子项（越界守卫 / 键空间隔离 / 文案改善）；⑧ 关联维护 5 DTO null-id 校验；⑫ `RuleSelfRagReflector` 阈值注入统一（跨答案热生效）
5. **测试加固五点（harden-test-backlog-batch1，C 档）**：空格形态纯前瞻扫描 / 客户公海回环 IT / `TokenBudget` volatile 三件套 / 9 处错误码双锁断言 / A-7 null id 五例
6. **引用对齐三阈值动态化（fix-citation-threshold-config，B 档）**：`keepMin` / `remapMin` / `tieMargin` 入 `DynamicConfigKeyRegistry`（Double，OPERATIONAL，越界拒写），`AiChatStreamFinalizer` 每流解析一次 → `CitationAligner.align`
7. **RAG 评测三盲区补齐（expand-rag-eval-blind-spots，B 档）**：多轮 / 时序 / 拒答；`SUITE_VERSION` 5.0→6.0、标准套件 63→72；v6.0 真跑锚 `baseline-v7.json` 缺席属另卡授权
8. **五路语义同步 hook 收敛（fix-semantic-hook-support-unify，B 档）**：五 `*SemanticHookSupport` 收敛为「一个基类 + 五个薄门面」，`reingestById` 泛型化
9. **ProjectFile 域收口四点合一（fix-projectfile-txn-and-batch-insert，B 档）**：三上传合一 + 磁盘 IO 出事务（短事务 `insertBatch` + 失败全量清盘补偿）
10. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档）**：删 `SALES_STAGE_APPROVAL_RELATIONS → ApprovalAttachmentEntity` 死边；商机删除显式补删审批附件
11. **循环写收口批 A-6 / A-8 / A-10（fix-engineering-debt-loop-write-batch）**：级联删 N 轮→1；关联维护 2N→2；用户批删单条化并修复 PII 清理缺陷
12. **工程债三合卡 A-7 / A-9 / A-1（fix-engineering-debt-nplus1-loop-clone）**：分页 N+1 3N+1→2；双软删循环 2N→1；`SQLExceptionHandler` 列名映射迁 `application.yml`
13. **待办池 MINOR 家族合卡**：`BaseException(String)` 四形态收敛至 `ErrorCode` + 防回归门禁；assign 闸前置行锁；`TokenBudgetProperties` 三字段 volatile
14. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）**：附件上传前置 canWriteAttachments 属主鉴权 12002；非超管禁改超管密码；invoice_info 纳管 DataScope
15. **知识库物理双清、详情属主数据权限收敛与聚合门禁同源（C-11/B-4/B-5/G-6）**：`deleteFile` 软删联动向量与 chunk 物理删；`merge-gate.sh` 8 门→10 门
16. **自服务改密旧密核验与隐私脱敏加固（B-2/B-6/B-7）** / 17. **用户自服务防提权越权加固（B-1）**：`updateMyInfo` 对 `roleId`/`deptId`/`status` Fail-Closed（12002），SET 白名单
18. **单副本单体部署契约与架构守卫硬化（I-4）** / 19. **数据权限切面 Fail-Closed 治理（I-1）**：`QueryWrapperAspect` 异常重抛 12002 不静默放行
20. **登录链路安全加固包（H-8/H-9/H-10）**：`JWTInterceptor` 白名单收敛；`POST /user/logout` + Caffeine 黑名单；`LoginAttemptService` 双维度防爆破
21. **商机阶段流转审批阻断（D4）** / 22. **确认链路写权限再审计（D6）**：无审批记录抛 30003
23. **已建未接线基建接线合卡（暗线①闭环）**：TokenBudget 预算闸接入聊天流入口；ContentSecurity 问句与摄取双重拦截；动态配置键 66→60
24. **公海认领/分派竞态 CAS 修复**：读-判-写压缩为单条条件 UPDATE；锁序「先用户后客户」零死锁面
25. **写端点校验收口**：86 处写端点 `@RequestBody` 100% 挂 `@Valid`（27 controller）
26. **向量运维端点统一收口**：`POST /knowledge/admin/sync/{entity}` 五实体枚举校验 + `/sync/all` 委托计数映射（复用 900）
27. **客户流失预警与自动化再激活**：确定性规则扫描 → 幂等建高优待办 → 每日 4 点错峰调度
28. **CustomerInsightPanel 双宿主接线** / 29. **AI 诊断建议一键转 Action**：`InsightToActionService` 纯结构化映射（零外呼）
30. **销售线索管理与一键转化商机漏斗**：Flyway V42 `sales_lead`；`/sales/lead` 6 端点；单事务转化 + 异步向量同步
31. **合同履约到期预警与逾期应收催款待办联动**：到期/超期与逾期未结回款统计；幂等高优 `ContactTask`；每天 3 点调度
32. **CRM 客户公海池与全生命周期流转闭环**：Flyway V41 组合索引 + `customer_pool_log`；认领配额 50 上限拦截 20003
33. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合、父子块切分；5 大业务实体向量语义检索汇入 Qdrant；严格四档数据权限闸

---

## 3. 当前在途与候选队列（add-kb-image-slice-retrieval 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `352bec6`；物料归档至 `openspec/changes/archive/add-kb-image-slice-retrieval/`（proposal + tasks + spec-delta，R100）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根无未跟踪散落件，证据日志留在 `.gitignore` 忽略区不入库）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡更新）**：
  - **多模态真共享空间升级卡（登记待立项）**：图文共享向量空间 + collection 路由 + 资产独立生命周期/审计——本卡方案 B 是「文本降维」过渡形态，不预埋；触发条件 = 灰度真跑显示描述质量不足或图文跨模态召回有明确缺口
  - **本卡生产灰度真跑（授权留 Owner）**：真 VLM 描述质量与成本基线（`RAG_VISION_PDF_REAL` opt-in），本卡全程 mock、零外呼
  - **D8 孤儿对象即时回滚小卡（候选，低优先）**：ingest 失败即时回滚已落盘资产；建议随灰度真跑一并定
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

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（本卡 `IMAGE` 取值解冻登记于变更日志 2026-10-21 条）
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §142**）
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
