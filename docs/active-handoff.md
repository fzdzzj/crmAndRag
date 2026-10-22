# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（image-retrieval-threshold-gate 图像路独立相似度硬闸门 + 近重复图三档分档，A 档，多模态检索闸门收官）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@db1970e`（= origin/master；本卡闭环：基点 `1c9cadd` → 分支 3 笔（`c0709c8` feat / `6c942f9` openspec 卡 / `f4c523b` docs 收口）→ M1 `git merge --no-ff` **`e824aa6`**（零冲突，分支 `feature/image-retrieval-threshold-gate` **永久保留**）→ M1 基线收口 `db1970e`；M2 看板/归档笔随本笔持久化）
- **测试基线四元组**：`merge=e824aa6` + Surefire **报告 320 / 用例 1991 / F0 / E0 / S0** + Failsafe **报告 41 / 用例 134 / F0 / E0 / S12** + `CI=#162`（M1 轮，Run 39762530502，实测号以 `docs/main-agent-logbook.md` §150 为准）。只增不减：本卡前 318/1978/0 + 41/134/12 → +2 报告 +13 用例、failsafe 持平。`scripts/test-baseline.txt` = `source-revision=e824aa6`、`measured-at=2026-10-22T10:21:19Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第十五卡启用）**：`git rev-parse "e824aa6^{tree}"` 与 `git rev-parse "feature/image-retrieval-threshold-gate^{tree}"` 同为 **`dd5cecdffb56f29cfe33d4f38b8a6c916d3b4e82`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1991 / failsafe 134 / 12 skip）+ `merge-gate.sh` **10/10 PASS** → **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底
- **复核**：**A 档 GO**（检索链路闸门语义变更）—— 独立复核四判据全过无 ACK（① 零迁移 ② OPERATIONAL 35→36 键复用 **608** 零新增权限 ③ 独立阈值 + 重排后降权 + 文本路解耦均有断言 ④ 冻结契约零改动）。复核方**阻断项**（openspec proposal/tasks 未入库）与四项非阻断建议（锚重标定三处同步留痕 / 台账键数谱系 63→67→68 / `recallImage` javadoc 纠偏 / `ProductionGrayRealRunIT` 锚漂移误导注释）已在分支内 `6c942f9`+`f4c523b` 一次收口。归档物料 = `openspec/changes/archive/image-retrieval-threshold-gate/`（proposal + tasks）+ `work/archive/closed-cards/task-card-image-retrieval-threshold-gate.md`（SHA256 一致）
- **前端披露**：本卡 `frontend/` 零 diff；零新依赖；`typed-router.d.ts` 生成物零提交
- **环境实测**：CI 盯跑本机 REST 直连 403 限流，改用 harness `web_fetch` 独立出口（`/actions/runs/<id>` 取 `run_number`）
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **图像路独立相似度硬闸门（image-retrieval-threshold-gate，A 档，刚刚合入）**：图像视觉路不再复用文本 `rag.retrieval.minScore`（0.20，比定标锚宽 5 倍）——新增 OPERATIONAL 键 `rag.retrieval.image-similarity-threshold`（默认 **0.9944**，越界回落，不入 per-KB 白名单，复用 608 权限）；`ImageSimilarityGate` 三档（SAME_IMAGE 正常 / NEAR_DUPLICATE 召回并打 `imageSimilarityTier` 标记 / CROSS_IMAGE 过滤），向量库按近似同图下限 **0.9887** 兜底检索，近重复降权 ×0.5 **在 reranker 之后**施加。文本路 minScore 语义零变化。
2. **多模态灰度授权 + 异图阈值定标 + embedding 计量口径（multimodal-gray-and-eval-threshold，A 档）**：运行期灰度路径登记 / 90 对真跑定标 0.9944 / `ai.token.record.calls` + `ai.token.usage.unavailable` 两指标。
3. **真共享空间生产灰度真跑（production-gray-real-run，A 档）**：真实 PDF 成本基线（≈1371 token/图，9.8s/图）。
4. **v6.0 主套件真跑落锚（anchor-v6-baseline-v7，A 档）** / 5. **PDF 摄取链纳入图文评测（pdf-ingest-image-eval，A 档）**。
6. **图文跨模态召回质量评测（image-cross-modal-eval，A 档）** / 7. **多模态真共享空间升级（multimodal-shared-space，A 档）**。
8. **图片摄取失败孤儿即时回滚（d8，C 档）** / 9. **知识库图片切片摄取 + IMAGE 回显（A 档）** / 10. **收尾小卡 ⑬⑭（B 档）**。
11. **待办池四点收口（B 档）** / 12. **待办池三点收口（B 档）** / 13. **测试加固五点（C 档）**。
14. **引用对齐三阈值动态化（B 档）** / 15. **RAG 评测三盲区补齐（B 档）** / 16. **五路语义 hook 收敛（B 档）**。
17. **ProjectFile 域收口（B 档）** / 18. **审批附件级联死边移除（A 档）** / 19. **循环写收口批 / 工程债三合卡 / 待办池 MINOR**。
20. **附件写权限+重置密码+发票纳管** / 21. **知识库物理双清 + 门禁同源** / 22. **登录链路安全加固包**。
23. **商机审批阻断 + 确认链路审计** / 24. **已建未接线基建接线** / 25. **公海竞态 CAS** / 26. **写端点校验收口**。
27. **向量运维端点收口** / 28. **客户流失预警 + 再激活** / 29. **CustomerInsightPanel + AI 转 Action**。
30. **销售线索漏斗**（V42）/ 31. **合同预警联动** / 32. **公海池流转**（V41）。
33. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF；5 大业务实体语义检索；四档数据权限闸。

> **NcssCount 豁免定论（Owner 2026-10-22 拍板，结论固化）**：全仓 2 处类级豁免（DocumentIngestionService / CustomerContactServiceImpl）
> 均为 documented tradeoff，维持不拆、不移除，全仓 suppression 维持 2 处。

---

## 3. 当前在途与候选队列（image-retrieval-threshold-gate 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `db1970e`；物料归档至 `openspec/changes/archive/image-retrieval-threshold-gate/`（proposal + tasks）与 `work/archive/closed-cards/`（任务卡）；`work/` 根本卡散落件已 SHA256 核对后删除）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡已消化两项、余项保留）**：
  - **近重复降权系数细化（后续卡候选，可选）**：当前 NEAR_DUPLICATE 统一 ×0.5 固定降权；真实语料「视觉近似同图」间隔可低至 +0.011，若 Owner 认为仍不够用再做分段系数
  - **生产开关真打开（Owner 决，非代码）**：登记「运行期灰度路径」（超管写 enabled=true + kb-allowlist），成本基线已备；是否真正打开由 Owner 按成本基线拍板（运行时动态配置，不需新卡）
  - **锚重标定（非代码，需手工三处同步）**：重跑定标须同步 `ImageSimilarityGate` 两常量 + 注册表默认串 `"0.9944"` + 台账/csv 文本（见 `docs/dynamic-config-keys.md` 该键节）
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

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（本卡零契约解冻）
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §150**）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`）
- 图像路闸门：`ImageSimilarityGate`（三档 + 降权）+ `docs/dynamic-config-keys.md` 该键节（锚重标定三处同步）
- 多模态灰度/阈值/计量：`docs/multimodal-gray-rollout.md` + `docs/token-metering.md` + `docs/rag-quality/image-threshold-calibration-v1.json` + `production-gray-cost-v1.json` + `baseline-v7.json`

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
