# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（production-gray-real-run 真共享空间生产灰度真跑，多模态系列收官，A 档全链闭环）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@98a4a91`（= origin/master；本卡闭环：基点 `1ae1de1` → 分支 1 笔 `14c18b5` test → M1 `git merge --no-ff` **`51416d7`**（零冲突，分支 `feature/production-gray-real-run` **永久保留**）→ M1 基线收口 `98a4a91`；M2 看板/归档笔随本笔持久化）
- **测试基线四元组**：`merge=51416d7` + Surefire **报告 317 / 用例 1969 / F0 / E0 / S0** + Failsafe **报告 40 / 用例 133 / F0 / E0 / S11** + `CI=#158`（M1 轮，Run 39724547528，实测号以 `docs/main-agent-logbook.md` §148 为准）。只增不减：本卡前 316/1964/0 + 39/132/10 → +1 报告 +5 用例、failsafe +1 报告 +1 用例 +1 skipped（新增 opt-in IT 默认无 key 会 skip）。`scripts/test-baseline.txt` = `source-revision=51416d7`、`measured-at=2026-10-22T04:48:41Z`（树净单次 `--update` + 校验模式 `EXIT=0`，随本笔持久化）
- **验证路径 = M1 树等价快车道（连续第十三卡启用）**：`git rev-parse "51416d7^{tree}"` 与 `git rev-parse "feature/production-gray-real-run^{tree}"` 同为 **`de8b68f3c4b0ca6d1825f4c6ca74ad34785e09e1`**，逐字节相等 → 判定树等价；执行端已在该等价树完成 `mvn -B -ntp clean verify` **BUILD SUCCESS**（surefire 1969）+ 真跑 `RAG_VISION_EMBED_REAL=1`（真实 PDF，qwen3-vl-plus）→ **本地免 clean verify / 免 merge-gate 复跑**，仅补基线台账；本地复跑职责由远端 CI `build-and-gate` 同源门禁兜底（**#158 已绿 = M1 验证闭环**）
- **复核**：**A 档 GO**（真实 PDF 全链路真跑 opt-in 落锚；零生产代码/零迁移/零权限/零新依赖）—— 独立复核四判据全过无 ACK（① 零 src/main ② 零新权限 ③ 真跑痕迹可验：vision 9600 token 真回流、imageEmbedding 14 次 0 回流不对称 mock 造不出、textEmbed 32≠28 证非公式推导 ④ 冻结契约零改动、开关默认 false 原样）。**Owner 拍板 DOE 逐图版权「保留 + 如实标注」**。归档物料 = `openspec/changes/archive/production-gray-real-run/`（proposal + tasks）+ `work/archive/closed-cards/`（handoff + 任务卡 `add -f`）
- **前端披露**：本卡 `frontend/` 零 diff；零新依赖；`typed-router.d.ts` 生成物零提交
- **环境实测**：真跑 qwen3-vl-plus（生产默认 qwen-vl-plus 返回 404 model_not_found，用 -Drag.pdf.image.vision-model 覆盖）；真实 PDF 6.86MB/157 页入库 test resources；CI 盯跑本机 REST 直连 403 限流，改用 harness `web_fetch` 独立出口
- **工作树拓扑**：
  - 观察树：`D:\code\crmAndRag`（承载执行日志 `docs/`、任务卡与交接 `work/`，未跟踪）
  - 权威树（主干）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出、门禁运行、代码合并唯一法定树）

---

## 2. 核心架构与现行能力面

1. **真共享空间生产灰度真跑（production-gray-real-run，A 档，刚刚合入，多模态系列收官）**：第一次用**真实公有领域政府出版物 PDF**（NIST SP 800-101r1/800-183 + DOE，6.86MB/157 页，`src/test/resources/rag-quality/real-pdf/` + `sources.json` URL/SHA-256/17 U.S.C. §105 授权）跑完「摄取 → 视觉/文本双集合 → query-by-image」完整链路，落盘成本基线 `production-gray-cost-v1.json`。实测：提取 9 图→入库 7、vision 9 次 9 600 token（**实测回流纠正「恒 0」口径**）、视觉 embed 14/文本 embed 32、65.8s、**≈1 371 token + 9.8s/图**。零生产代码 / 零迁移 / 零权限 / 零新依赖。**多模态系列全线闭环**（multimodal → image-cross-modal → pdf-ingest-image → baseline-v7 → 生产灰度）。
2. **v6.0 主套件真跑落锚（anchor-v6-baseline-v7，A 档）**：72 例首次真跑落锚 baseline-v7.json（totalTokens 72347）。
3. **PDF 摄取链纳入图文评测（pdf-ingest-image-eval，A 档）** / 4. **图文跨模态召回质量评测（image-cross-modal-eval，A 档）**。
5. **多模态真共享空间升级（multimodal-shared-space，A 档）** / 6. **图片摄取失败孤儿即时回滚（d8，C 档）**。
7. **知识库图片切片摄取 + IMAGE 回显（add-kb-image-slice-retrieval，A 档）** / 8. **收尾小卡 ⑬⑭（B 档）** / 9. **待办池四点收口（B 档）** / 10. **待办池三点收口（B 档）**。
11. **测试加固五点（C 档）** / 12. **引用对齐三阈值动态化（B 档）** / 13. **RAG 评测三盲区补齐（B 档）**。
14. **五路语义 hook 收敛（B 档）** / 15. **ProjectFile 域收口（B 档）** / 16. **审批附件级联死边移除（A 档）**。
17. **循环写收口批 / 工程债三合卡 / 待办池 MINOR** / 18. **附件写权限+重置密码+发票纳管** / 19. **知识库物理双清 + 门禁同源**。
20. **登录链路安全加固包** / 21. **商机审批阻断 + 确认链路审计** / 22. **已建未接线基建接线** / 23. **公海竞态 CAS** / 24. **写端点校验收口**。
25. **向量运维端点收口** / 26. **客户流失预警 + 再激活** / 27. **CustomerInsightPanel + AI 转 Action**。
28. **销售线索漏斗**（V42）/ 29. **合同预警联动** / 30. **公海池流转**（V41）。
31. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF；5 大业务实体语义检索；四档数据权限闸。

> **NcssCount 豁免定论（Owner 2026-10-22 拍板，结论固化）**：全仓 2 处 `@SuppressWarnings("PMD.NcssCount")` 类级豁免——
> `DocumentIngestionService`（拆分会切同一事务边界）与 `CustomerContactServiceImpl`（聚合 20+ 联系人方法，拆牺牲高内聚）——
> 均为 **documented tradeoff，维持不拆、不移除豁免**，全仓 suppression 维持 2 处。

---

## 3. 当前在途与候选队列（production-gray-real-run 闭环刷新）

- **在途待合入卡**：**0 张！**（本卡已全链合入 master `98a4a91`；物料归档至 `openspec/changes/archive/production-gray-real-run/`（proposal + tasks）与 `work/archive/closed-cards/`（handoff + 任务卡）；`work/` 根本卡散落件已 SHA256 核对后删除）
- **待办池**：**0 条**（本卡未消化待办池项，也未新进；⑮ `frontend/typed-router.d.ts` 生成物归属仍属 frontend 工程决策，**他人所有权，本线不擅动**）
- **长期储备（本卡更新，多模态系列已全线闭环）**：
  - **生产开关打开授权 → 已登记灰度路径（`multimodal-gray-and-eval-threshold` 任务组 1，在途分支）**：owner 2026-10-22 拍板「只登记运行期灰度路径、不改代码」，打开顺序 / KB 白名单 / 生产图帽 5 / 回滚 / 观察指标 / 成本基线引用见 `docs/multimodal-gray-rollout.md`；**默认值仍 false，开关本身仍待 owner 拍板**
  - **异图硬阈值定标 → 已定标落锚（本卡任务组 2）**：真实语料同图下限 0.9999999999999998 / 异图上限 0.9887359400788237 / 判别阈值 **0.9943679700394117**，锚 `docs/rag-quality/image-threshold-calibration-v1.json`（+ `.md`）；程序化套件 0.90 原样未动
  - **embedding token 计量 → 已诚实化（本卡任务组 3）**：`ai.token.record.calls`（调用次数已计）+ `ai.token.usage.unavailable`（token 不可得声明），零伪造 token、未解冻冻结契约，口径见 `docs/token-metering.md`
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
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §148**）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`）
- 真跑成本基线：`docs/rag-quality/production-gray-cost-v1.json`（真实 PDF 灰度）+ `baseline-v7.json`（主套件）+ image/pdf 锚

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