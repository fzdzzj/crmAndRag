# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（anchor-v6-baseline-v7 v6.0 主套件真跑落锚 + NcssCount 豁免结论固化，A 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@a5e7fce`（= origin/master；本卡闭环：基点 `e248e6e` → 分支 1 笔 `48311c4` test → M1 `git merge --no-ff` **`f74c5c9`**（零冲突，分支 `feature/anchor-v6-baseline-v7` **永久保留**）→ M1 基线收口 `a5e7fce`；M2 看板/归档笔随本笔持久化）
- **测试基线四元组**：`merge=f74c5c9` + Surefire **报告 316 / 用例 1964 / F0 / E0 / S0** + Failsafe **报告 39 / 用例 132 / F0 / E0 / S10** + `CI=#156`（M1 轮，Run 39714616733，实测号以 `docs/main-agent-logbook.md` §147 为准）。只增不减：本卡前 315/1953/0 + 39/132/10 → +1 报告 +11 用例（新增 v7 锚点对账单测）、failsafe 持平。`scripts/test-baseline.txt` = `source-revision=f74c5c9`、`measured-at=2026-10-22T03:00:46Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第十二卡启用）**：`git rev-parse "f74c5c9^{tree}"` 与 `git rev-parse "feature/anchor-v6-baseline-v7^{tree}"` 同为 **`9e15e35ddcd3bb2ba9dba2c4162afa260c59630a`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1964）+ 真跑 `RAG_BENCHMARK_REAL=1` 72 例（341.2s，totalTokens=72347）→ **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#156 已绿 = M1 验证闭环**）
- **复核**：**A 档 GO**（72 例全量真跑 opt-in 落锚；零生产代码/零迁移/零权限/零新依赖）—— 独立复核四判据全过无 ACK（① 零 src/main ② 零新权限 ③ 真跑 72/72 tokens>0 非假绿、复核方独立复算 hitRate 67/68=.9853/totalTokens 72347 严等 ④ 冻结 Suite/SUITE_VERSION/黄金片段/v1~v6 全未动）。归档物料 = `openspec/changes/archive/anchor-v6-baseline-v7/`（proposal + tasks）+ `work/archive/closed-cards/`（handoff + 任务卡 `add -f`）
- **前端披露**：本卡 `frontend/` 零 diff；零新依赖；`typed-router.d.ts` 生成物零提交
- **环境实测**：真跑 72 例（341.2s）走 DASHSCOPE_API_KEY opt-in；CI 盯跑本机 REST 直连 403 限流，改用 harness `web_fetch` 独立出口
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **v6.0 主套件真跑落锚（anchor-v6-baseline-v7，A 档真跑，刚刚合入）**：`RagBenchmarkSuite.SUITE_VERSION="6.0"`（72 例）首次真跑落锚 `docs/rag-quality/baseline-v7.json`（suiteVersion=6.0、caseCount=72、scoringVersion=v2、totalTokens=72347、hitRate 67/68=.9853、341.2s）。`RagRealRetrievalBenchmarkIT` 增 `V7_ANCHOR_FILE_NAME` + `RagBenchmarkV7AnchorValidationTest`（纯 JVM 零外发对账）。零生产代码 / 零迁移 / 零权限 / 零新依赖。
2. **PDF 摄取链纳入图文评测（pdf-ingest-image-eval，A 档）**：真 PDF → 真 embedImage → image collection → query-by-image 全链真跑，PDF-IMAGE-SUITE-1.0 落锚。
3. **图文跨模态召回质量评测（image-cross-modal-eval，A 档）**：真视觉 vs 文本降维对照（Delta 2.6×），IMAGE-SUITE-1.0 落锚。
4. **多模态真共享空间升级（multimodal-shared-space，A 档）**：真视觉 embedding + 独立 image collection + query-by-image。
5. **图片摄取失败孤儿对象即时回滚（d8-image-asset-rollback，C 档）** / 6. **知识库图片切片摄取 + IMAGE 回显（add-kb-image-slice-retrieval，A 档）**。
7. **收尾小卡 ⑬⑭（fix-backlog-batch4，B 档）** / 8. **待办池四点收口（fix-backlog-batch3，B 档）** / 9. **待办池三点收口（fix-backlog-batch2，B 档）**。
10. **测试加固五点（harden-test-backlog-batch1，C 档）** / 11. **引用对齐三阈值动态化（B 档）** / 12. **RAG 评测三盲区补齐（B 档）**。
13. **五路语义同步 hook 收敛（B 档）** / 14. **ProjectFile 域收口（B 档）** / 15. **审批附件级联死边移除（A 档）**。
16. **循环写收口批 A-6/A-8/A-10** / 17. **工程债三合卡 A-7/A-9/A-1** / 18. **待办池 MINOR 家族合卡**。
19. **附件写权限+重置密码+发票纳管合卡** / 20. **知识库物理双清 + 门禁同源** / 21. **登录链路安全加固包**。
22. **商机阶段流转审批阻断 + 确认链路写权限审计** / 23. **已建未接线基建接线合卡** / 24. **公海竞态 CAS** / 25. **写端点校验收口**。
26. **向量运维端点统一收口** / 27. **客户流失预警 + 再激活** / 28. **CustomerInsightPanel + AI 转 Action**。
29. **销售线索漏斗**（V42）/ 30. **合同预警联动** / 31. **公海池流转**（V41）。
32. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF；5 大业务实体语义检索；四档数据权限闸。

> **NcssCount 豁免定论（Owner 2026-10-22 拍板，结论固化）**：全仓 2 处 `@SuppressWarnings("PMD.NcssCount")` 类级豁免——
> `DocumentIngestionService`（拆分会切同一事务边界）与 `CustomerContactServiceImpl`（聚合 20+ 联系人方法，拆牺牲高内聚）——
> 均为 **documented tradeoff，维持不拆、不移除豁免**，全仓 suppression 维持 2 处。不再列长期储备候选。

---

## 3. 当前在途与候选队列（anchor-v6-baseline-v7 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `a5e7fce`；物料归档至 `openspec/changes/archive/anchor-v6-baseline-v7/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根本卡散落件已 SHA256 核对后删除）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡更新）**：
  - **真共享空间生产灰度真跑（授权留 Owner）**：真视觉 embedding 已真跑验证（VisionEmbeddingRealIT + image-cross-modal-eval + pdf-ingest-image-eval 三卡评测），但**生产灰度真跑**（真实 KB 真实 PDF 资产、成本基线，`RAG_VISION_EMBED_REAL` 生产 opt-in）需 Owner 另行授权
  - 在线考试子域（`work/_online-exam-backlog.md` 留档）
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

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（本卡群零契约改动）
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §147**）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`）
- RAG 评测锚点：`docs/rag-quality/baseline-v1~v7.json`（v7 = 主套件 6.0 真跑）+ `image-baseline-v1.json` + `pdf-ingest-image-baseline-v1.json`

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