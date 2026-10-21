# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-21（multimodal-shared-space 多模态真共享空间升级，A 档大卡全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@71bfe27`（= origin/master；本卡闭环：基点 `8c3ac8f` → 分支 3 笔 `92f0424` feat / `b0bf75e` test+基线 / `bea5c5c` docs（主 Agent 补 commit，执行端漏提交）→ M1 `git merge --no-ff` **`69190fd`**（零冲突，分支 `feature/multimodal-shared-space` **永久保留**）→ M1 基线收口 `581d906`/`71bfe27`（修正 skipped 口径 7→8 + 重锚 source-revision）；M2 看板/归档笔随本笔持久化）
- **测试基线四元组**：`merge=69190fd` + Surefire **报告 313 / 用例 1938 / F0 / E0 / S0** + Failsafe **报告 37 / 用例 130 / F0 / E0 / S8** + `CI=#150`（M1 轮，Run 39620231453，= #149+1 预测，实测号以 `docs/main-agent-logbook.md` §144 为准）。只增不减：本卡前 309/1924/0 + 35/126/7 → +4 报告 +14 用例、failsafe +2 报告 +4 用例 +1 skipped（新增 opt-in 真跑 IT 默认无 key 会 skip）。`scripts/test-baseline.txt` = `source-revision=71bfe27`、`measured-at=2026-10-21T14:48:38Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第九卡启用）**：`git rev-parse "69190fd^{tree}"` 与 `git rev-parse "feature/multimodal-shared-space^{tree}"` 同为 **`e00201127383f588a82672ca204d81e226092819`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1938 / failsafe 130 / 8 skip，四静态全绿）+ `merge-gate.sh --with-verify` 全 PASS + 真跑 `RAG_VISION_EMBED_REAL=1` 同图余弦>0.9 vs <0.9 → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#150 已绿 = M1 验证闭环**）
- **复核**：**A 档 GO**（embedding 选型 + 向量库 schema + 契约受控解冻 + 真跑）—— 独立复核四判据逐条通过（① 零迁移：diff 无 `db/migration`；② 零新权限/端点：`embedImage` 仅内部链路；③ 真跑独立复跑 `Tests run:1 Skipped:0 Failures:0 3.869s` 非假绿；④ 冻结契约：`embedImage` default 方法非破坏 + contracts-frozen.md §修正轮4 解冻登记）。**Owner 四项拍板**：真视觉 CLIP 式 / 独立 image collection / 授权真跑 / DashScope 文本-图像联合 embedding 零新依赖。复核报告 `work/archive/closed-cards/review-multimodal-shared-space-A-review-report.md`。归档物料 = `openspec/changes/archive/multimodal-shared-space/`（proposal + tasks）+ `work/archive/closed-cards/`（handoff + 任务卡 + review `add -f`）
- **前端披露**：本卡 `frontend/` 零 diff（`typed-router.d.ts` 执行端触发 unplugin-vue-router 噪声，已按 Owner 拍板 `git checkout --` 还原零提交）
- **环境实测**：本卡走快车道，本地零 Maven 复跑；执行端 verify + 复核方独立复跑真跑 IT 时 Docker 在线；CI 盯跑本机 REST 直连 403 限流，改用 harness `web_fetch` 独立出口（`/commits/<sha>/check-runs` 取 6 job conclusion）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **多模态真共享空间升级（multimodal-shared-space，A 档，刚刚合入）**：本系统「图文」原为「图文双文本降维」半成品（检索侧 `imageVector` 路由 + `fuseRoutes` 图文融合接口已存在，但摄取侧零视觉向量、查询侧 imageVector 是文本拼接 embed、`ModelProvider` 无视觉 embed 接口）。本卡补齐真视觉：`ModelProvider` 增 `default embedImage(byte[],String)`（默认抛 UnsupportedOperationException 非破坏）+ `ImageEmbeddingSupport` 直连 DashScope `multimodal-embedding-v1`（RestClient 零新依赖）；`QdrantProperties` 增 `imageCollection`/`imageDimensions` + `ImageVectorStore` 包装 + `ImageVectorStoreConfiguration`（对齐 CrmEntityVectorStore 范式），视觉向量落独立 image collection（维度可 ≠ 文本）；`ImageVisualVectorSupport`/`ImageVectorFlushSupport`/`ImageVectorCleanupRegistry` 三件套（摄取生产视觉向量 + 静态钩子三处双清）；开关 `rag.retrieval.image-vector-ingest.enabled` 默认 false（COST 档，零外呼零向量落盘）；查询侧 `AiChatImageUnderstandingService` 图字节直入 + `recallImage` 查 image 集合失败降级 + `fuseRoutes` 异构不误合并（同 slice 才累加）。33 文件（21 改 + 13 新，+384/−97），零迁移 / 零新权限 / 零新依赖。
2. **图片摄取失败孤儿对象即时回滚（d8-image-asset-rollback，C 档）**：收口 add-kb-image-slice-retrieval 的 D8 偏差——图片对象在切片行落库前已写进 `FileStorageService`，`chunkEmbedAndWrite` 失败时 storageKey 随事务消失成孤儿。`PdfImageSliceIngestService` 加 `STAGED_ASSET_KEYS`（ThreadLocal）+ `beginAssetCollection/endAssetCollection/rollbackStagedAssets`；`DocumentIngestionService#ingest` 失败 catch 先回滚再 `markFailed`。成功路径零额外删除、回滚 WARN 不抛、防误删文档原件。
3. **知识库图片切片摄取 + IMAGE 回显（add-kb-image-slice-retrieval，A 档）**：方案 B 文本降维——图片对象提取 → PNG 资产落 `FileStorageService` → 每图 VLM 描述 → 描述文本切片入既有 chunk/embed 链路；`SourceReference.sourceType` 扩 `IMAGE` + `SourceReferenceFactory` 唯一实现 + `GET /knowledge/images/{assetId}`（挂 803）；开关默认 false。本卡（multimodal-shared-space）在此之上加真视觉路，两路并行。
4. **收尾小卡 ⑬⑭（fix-backlog-batch4，B 档）**：`ChildHostAttachmentCleaner` 审批域附件清理补齐 + Vitest 总数断言生成化。
5. **待办池四点收口（fix-backlog-batch3，B 档）**：⑤ duplicate-key 精确码 92029；⑨ `BatchIdsGuard` 统一闸；⑩ `batchLogicalDeleteByIds` 单趟化；⑪ `removeByIds` 跨模型误删修复。
6. **待办池三点收口（fix-backlog-batch2，B 档）**：⑦ `SQLExceptionHandler` 四子项；⑧ 关联 DTO null-id 校验；⑫ `RuleSelfRagReflector` 阈值注入统一。
7. **测试加固五点（harden-test-backlog-batch1，C 档）**：空格形态扫描 / 公海回环 IT / `TokenBudget` volatile / 错误码双锁 / A-7 null id。
8. **引用对齐三阈值动态化（fix-citation-threshold-config，B 档）** / 9. **RAG 评测三盲区补齐（expand-rag-eval-blind-spots，B 档）**：多轮/时序/拒答，`SUITE_VERSION` 6.0。
10. **五路语义同步 hook 收敛（fix-semantic-hook-support-unify，B 档）** / 11. **ProjectFile 域收口（fix-projectfile-txn-and-batch-insert，B 档）**。
12. **审批附件级联死边移除（fix-approval-attachment-cascade-dead-edge，A 档）** / 13. **循环写收口批 A-6/A-8/A-10**。
14. **工程债三合卡 A-7/A-9/A-1（fix-engineering-debt-nplus1-loop-clone）** / 15. **待办池 MINOR 家族合卡**。
16. **附件写权限、重置密码防提权与发票纳管安全合卡（H-2/B-10/H-4/I-5）** / 17. **知识库物理双清 + 门禁同源（C-11/B-4/B-5/G-6）**。
18. **登录链路安全加固包（H-8/H-9/H-10）** / 19. **商机阶段流转审批阻断（D4）** / 20. **确认链路写权限再审计（D6）**。
21. **已建未接线基建接线合卡（暗线①）** / 22. **公海认领/分派竞态 CAS 修复** / 23. **写端点校验收口**。
24. **向量运维端点统一收口** / 25. **客户流失预警与自动化再激活** / 26. **CustomerInsightPanel 双宿主 + AI 诊断转 Action**。
27. **销售线索管理与转化商机漏斗**（Flyway V42）/ 28. **合同履约到期预警与逾期催款联动** / 29. **CRM 公海池流转闭环**（Flyway V41）。
30. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF 融合；5 大业务实体向量语义检索；严格四档数据权限闸。

---

## 3. 当前在途与候选队列（multimodal-shared-space 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `71bfe27`；物料归档至 `openspec/changes/archive/multimodal-shared-space/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡 + review）；`work/` 根本卡散落件已 SHA256 核对后删除）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡更新）**：
  - **真共享空间生产灰度真跑（授权留 Owner）**：本卡视觉 embedding 已真跑验证（`VisionEmbeddingRealIT`），但**生产灰度真跑**（真实 KB 图文跨模态召回质量/成本基线，`RAG_VISION_EMBED_REAL` 生产 opt-in）需 Owner 另行授权
  - **图文跨模态召回质量评测卡（候选）**：真视觉向量路已落地，但「query-by-image 检索质量 vs 纯文本检索」的量化评测（baseline 锚定）尚未立项
  - **D8 后遗症治理（候选，低优先）**：`DocumentIngestionService` NcssCount 类级豁免（全仓 suppression 1→2 处），如需收敛「编排类拆分」小卡可另立
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

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（本卡 `embedImage` 契约解冻登记于 §修正轮4 + 变更日志 2026-10-21 条）
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §144**）
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