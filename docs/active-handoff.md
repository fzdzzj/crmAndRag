# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（multimodal-gray-and-eval-threshold 多模态灰度授权 + 异图阈值定标 + embedding 计量口径，A 档，多模态专题彻底收官）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@3334c08`（= origin/master；本卡闭环：基点 `0e1c367` → 分支 5 笔（feat/test/docs/基线/台账）→ M1 `git merge --no-ff` **`7ffcba5`**（零冲突，分支 `feature/multimodal-gray-and-eval-threshold` **永久保留**）→ M1 基线收口 `3334c08`；M2 看板/归档笔随本笔持久化）
- **测试基线四元组**：`merge=7ffcba5` + Surefire **报告 318 / 用例 1978 / F0 / E0 / S0** + Failsafe **报告 41 / 用例 134 / F0 / E0 / S12** + `CI=#160`（M1 轮，Run 39731182595，实测号以 `docs/main-agent-logbook.md` §149 为准）。只增不减：本卡前 317/1969/0 + 40/133/11 → +1 报告 +9 用例、failsafe +1 报告 +1 用例 +1 skipped。`scripts/test-baseline.txt` = `source-revision=7ffcba5`、`measured-at=2026-10-22T05:58:29Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第十四卡启用）**：`git rev-parse "7ffcba5^{tree}"` 与 `git rev-parse "feature/multimodal-gray-and-eval-threshold^{tree}"` 同为 **`8deb2d3cf654a5a47c19acb4294284bf562a2148`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1978 / failsafe 134 / 12 skip）+ `merge-gate.sh` **10/10 PASS** + 真跑 `RAG_VISION_EMBED_REAL=1`（90 对定标）→ **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#160 已绿 = M1 验证闭环**）
- **复核**：**A 档 GO**（异图定标真跑 + embedding 计量；生产开关登记为纯文档）—— 独立复核四判据全过无 ACK（① src/main 仅 2 文件零迁移 ② 零权限 ③ 90 对真跑非 hardcode、不可分必红 ④ 冻结契约零解冻、开关默认 false 原样）。**Owner 两项拍板**：生产开关只登记运行期灰度路径不改代码、三卡硬合一张。归档物料 = `openspec/changes/archive/multimodal-gray-and-eval-threshold/`（proposal + tasks）+ `work/archive/closed-cards/`（handoff + 任务卡 `add -f`）
- **前端披露**：本卡 `frontend/` 零 diff；零新依赖；`typed-router.d.ts` 生成物零提交
- **环境实测**：CI 盯跑本机 REST 直连 403 限流，改用 harness `web_fetch` 独立出口
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **多模态灰度授权 + 异图阈值定标 + embedding 计量口径（multimodal-gray-and-eval-threshold，A 档，刚刚合入）**：多模态收尾三合一——① 生产开关运行期灰度路径登记（纯文档 `docs/multimodal-gray-rollout.md`，零代码，真视觉路嵌 image-slice-ingest.enabled+kb-allowlist 门控）；② 异图硬阈值定标（90 对真跑，判别阈值 **0.9944**，替代程序化 0.90，结论 0.90 偏松）；③ embedding 计量诚实化（`ai.token.record.calls` + `ai.token.usage.unavailable` 两指标，零伪造 token 零解冻契约）。**多模态专题彻底收官**。
2. **真共享空间生产灰度真跑（production-gray-real-run，A 档）**：真实 PDF 成本基线（≈1371 token/图，9.8s/图）。
3. **v6.0 主套件真跑落锚（anchor-v6-baseline-v7，A 档）** / 4. **PDF 摄取链纳入图文评测（pdf-ingest-image-eval，A 档）**。
5. **图文跨模态召回质量评测（image-cross-modal-eval，A 档）** / 6. **多模态真共享空间升级（multimodal-shared-space，A 档）**。
7. **图片摄取失败孤儿即时回滚（d8，C 档）** / 8. **知识库图片切片摄取 + IMAGE 回显（A 档）** / 9. **收尾小卡 ⑬⑭（B 档）**。
10. **待办池四点收口（B 档）** / 11. **待办池三点收口（B 档）** / 12. **测试加固五点（C 档）**。
13. **引用对齐三阈值动态化（B 档）** / 14. **RAG 评测三盲区补齐（B 档）** / 15. **五路语义 hook 收敛（B 档）**。
16. **ProjectFile 域收口（B 档）** / 17. **审批附件级联死边移除（A 档）** / 18. **循环写收口批 / 工程债三合卡 / 待办池 MINOR**。
19. **附件写权限+重置密码+发票纳管** / 20. **知识库物理双清 + 门禁同源** / 21. **登录链路安全加固包**。
22. **商机审批阻断 + 确认链路审计** / 23. **已建未接线基建接线** / 24. **公海竞态 CAS** / 25. **写端点校验收口**。
26. **向量运维端点收口** / 27. **客户流失预警 + 再激活** / 28. **CustomerInsightPanel + AI 转 Action**。
29. **销售线索漏斗**（V42）/ 30. **合同预警联动** / 31. **公海池流转**（V41）。
32. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF；5 大业务实体语义检索；四档数据权限闸。

> **NcssCount 豁免定论（Owner 2026-10-22 拍板，结论固化）**：全仓 2 处类级豁免（DocumentIngestionService / CustomerContactServiceImpl）
> 均为 documented tradeoff，维持不拆、不移除，全仓 suppression 维持 2 处。

---

## 3. 当前在途与候选队列（multimodal-gray-and-eval-threshold 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `3334c08`；物料归档至 `openspec/changes/archive/multimodal-gray-and-eval-threshold/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根本卡散落件已 SHA256 核对后删除）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡更新，多模态专题已彻底收官）**：
  - **生产开关真打开（Owner 决，非代码）**：本卡已登记「运行期灰度路径」（超管写 enabled=true + kb-allowlist），成本基线已备；是否真正打开由 Owner 按成本基线拍板（运行时动态配置，不需新卡）
  - **异图阈值 0.9944 进检索硬闸门（后续卡候选，src/main 行为变更）**：定标已产出阈值，但「进检索硬闸门」属行为变更需另卡
  - **近重复图分档（后续卡候选）**：真实语料「视觉近似同图」单阈值无法区分，需多档/规则，属另卡
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

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（本卡群零契约解冻）
- 数据库迁移号段：`ls src/main/resources/db/migration`（实测下一可用号：V43；本卡零迁移）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §149**）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`）
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