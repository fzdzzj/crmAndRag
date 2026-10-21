# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-21（image-cross-modal-eval 图文跨模态召回质量评测，A 档真跑落锚全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@03e0f7b`（= origin/master；本卡闭环：基点 `6b5248b` → 分支 2 笔 `007ce1a` test / `5254dd1` docs（主 Agent 修文档缺陷）→ M1 `git merge --no-ff` **`90fc2cb`**（零冲突，分支 `feature/image-cross-modal-eval` **永久保留**）→ M1 基线收口 `03e0f7b`（重锚 source-revision）；M2 看板/归档笔随本笔持久化）
- **测试基线四元组**：`merge=90fc2cb` + Surefire **报告 314 / 用例 1946 / F0 / E0 / S0** + Failsafe **报告 38 / 用例 131 / F0 / E0 / S9** + `CI=#152`（M1 轮，Run 39633877359，= #151+1 预测，实测号以 `docs/main-agent-logbook.md` §145 为准）。只增不减：本卡前 313/1938/0 + 37/130/8 → +1 报告 +8 用例、failsafe +1 报告 +1 用例 +1 skipped（新增 opt-in 真跑 IT 默认无 key 会 skip）。`scripts/test-baseline.txt` = `source-revision=90fc2cb`、`measured-at=2026-10-21T16:19:04Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第十卡启用）**：`git rev-parse "90fc2cb^{tree}"` 与 `git rev-parse "feature/image-cross-modal-eval^{tree}"` 同为 **`6e99f10040d5cb6b15d79369012b38e592cf35b9`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1946 / failsafe 131 / 9 skip，四静态全绿）+ `merge-gate.sh` **10/10 PASS** + 真跑 `RAG_VISION_EMBED_REAL=1` EXIT=0 且 baseline 落盘 → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#152 已绿 = M1 验证闭环**）
- **复核**：**A 档 GO**（真跑 opt-in 落锚；零生产代码/零迁移/零权限/零新依赖）—— 独立复核四判据逐条通过（① 零迁移 + `src/main` 零 diff；② 零新 controller/端点/权限常量；③ 真跑独立复算 cosine 非硬编码、退化为文本降维必红；④ `RagBenchmarkSuite`/`SUITE_VERSION="6.0"`/`Category.IMAGE`/baseline-v1~v6 全未动）。**Owner 三项拍板**：独立 IMAGE-SUITE 套件（对齐 customer 先例）/ 授权真跑 + 独立 image-baseline-v1 落锚 / 同异图余弦判别。**真跑实测**：真视觉判别间隔 0.505、异图误召 0%、passRate 1.0 vs 文本降维 0.194/60%/0.4（真视觉增量 2.6×）。归档物料 = `openspec/changes/archive/image-cross-modal-eval/`（proposal + tasks）+ `work/archive/closed-cards/`（handoff + 任务卡 `add -f`）
- **前端披露**：本卡 `frontend/` 零 diff；零新依赖；`typed-router.d.ts` 生成物零提交
- **环境实测**：本卡走快车道，本地零 Maven 复跑；执行端 verify + 复核方独立复算真跑 IT 时 Docker 在线；CI 盯跑本机 REST 直连 403 限流，改用 harness `web_fetch` 独立出口（`/commits/<sha>/check-runs` 取 6 job conclusion）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **图文跨模态召回质量评测（image-cross-modal-eval，A 档真跑落锚，刚刚合入）**：承接 multimodal-shared-space，量化「真视觉 query-by-image」vs「文本降维描述召回」检索质量对照。独立 `ImageCrossModalSuite`（IMAGE-SUITE-1.0，独立编号 IM-01~IM-05）+ `ImageCrossModalFixture`（6 图确定性语料）+ `ImageCrossModalEvaluator`（余弦/阈值/同图召回率/异图误召率/判别间隔）+ `ImageCrossModalBenchmarkIT`（`RAG_VISION_EMBED_REAL=1` opt-in 真跑，真 embedImage → `KnowledgeRetrievalServiceImpl#recallImage` → image 集合，无 key 整类 Assume skip）+ `ImageCrossModalSuiteTest`（8 单测零外呼）。**实测**：真视觉判别间隔 0.505 / 异图误召 0% / passRate 1.0 vs 文本降维 0.194 / 60% / 0.4——**真视觉增量 2.6× 成立**。独立落锚 `docs/rag-quality/image-baseline-v1.json`（297 行，IMAGE-SUITE-1.0），零生产代码 / 零迁移 / 零新权限 / 零新依赖。
2. **多模态真共享空间升级（multimodal-shared-space，A 档）**：真视觉 embedding（`ModelProvider` 增 `default embedImage`）；`ImageEmbeddingSupport` 直连 DashScope `multimodal-embedding-v1`；`ImageVectorStore` 独立 image collection（维度可 ≠ 文本）；开关 `rag.retrieval.image-vector-ingest.enabled` 默认 false；查询侧真 query-by-image。本卡（image-cross-modal-eval）是它的质量评测闭环。
3. **图片摄取失败孤儿对象即时回滚（d8-image-asset-rollback，C 档）**：收口图片对象落库前已写存储、失败成孤儿。`STAGED_ASSET_KEYS` ThreadLocal + 三静态方法；ingest 失败 catch 先回滚再 markFailed。
4. **知识库图片切片摄取 + IMAGE 回显（add-kb-image-slice-retrieval，A 档）**：方案 B 文本降维；`SourceReference.sourceType` 扩 IMAGE；`GET /knowledge/images/{assetId}`（挂 803）。
5. **收尾小卡 ⑬⑭（fix-backlog-batch4，B 档）** / 6. **待办池四点收口（fix-backlog-batch3，B 档）** / 7. **待办池三点收口（fix-backlog-batch2，B 档）**。
8. **测试加固五点（harden-test-backlog-batch1，C 档）** / 9. **引用对齐三阈值动态化（B 档）** / 10. **RAG 评测三盲区补齐（B 档，SUITE_VERSION 6.0）**。
11. **五路语义同步 hook 收敛（B 档）** / 12. **ProjectFile 域收口（B 档）** / 13. **审批附件级联死边移除（A 档）** / 14. **循环写收口批 A-6/A-8/A-10**。
15. **工程债三合卡 A-7/A-9/A-1** / 16. **待办池 MINOR 家族合卡** / 17. **附件写权限+重置密码+发票纳管合卡** / 18. **知识库物理双清 + 门禁同源**。
19. **登录链路安全加固包** / 20. **商机阶段流转审批阻断 + 确认链路写权限审计** / 21. **已建未接线基建接线合卡** / 22. **公海竞态 CAS** / 23. **写端点校验收口**。
24. **向量运维端点统一收口** / 25. **客户流失预警 + 再激活** / 26. **CustomerInsightPanel + AI 转 Action** / 27. **销售线索漏斗**（V42）/ 28. **合同预警联动** / 29. **公海池流转**（V41）。
30. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF；5 大业务实体语义检索；四档数据权限闸。

---

## 3. 当前在途与候选队列（image-cross-modal-eval 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `03e0f7b`；物料归档至 `openspec/changes/archive/image-cross-modal-eval/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根本卡散落件已 SHA256 核对后删除）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡更新）**：
  - **真共享空间生产灰度真跑（授权留 Owner）**：真视觉 embedding 已真跑验证（`VisionEmbeddingRealIT` + 本卡 image-cross-modal-eval 评测），但**生产灰度真跑**（真实 KB 图文跨模态召回/成本基线，`RAG_VISION_EMBED_REAL` 生产 opt-in）需 Owner 另行授权
  - **PDF 摄取链纳入图文评测（候选，需 Docker 真库）**：本卡语料是程序化确定性图，「PDF 内嵌图 → 视觉召回」评测需 Docker 真库 + 真 PDF 资产（`ImageVectorSpaceIT` 已覆盖落库段，端到端评测属后续卡）
  - **v6.0 真跑落锚 `baseline-v7.json`（需另卡授权）**：主套件 72 例 v6.0 仍未真跑落锚（本卡只落了独立 IMAGE-SUITE 锚，不碰 v7）
  - **D8 后遗症治理（候选，低优先）**：`DocumentIngestionService` NcssCount 类级豁免（全仓 suppression 1→2 处）
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

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（上一卡 `embedImage` 契约解冻登记于 §修正轮4；本卡零契约改动）
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §145**）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只允许 `--update` 从一次真实运行写入）
- 图文跨模态基线：`docs/rag-quality/image-baseline-v1.json`（IMAGE-SUITE-1.0，真跑落锚）

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