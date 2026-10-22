# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（image-near-duplicate-band-weight 近重复图降权分段系数，B 档，图像检索闸门收官第二刀）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@75e35dd`（= origin/master；本卡闭环：基点 `0c09ad3` → 分支 5 笔（openspec / feat / test / docs / baseline）→ M1 `git merge --no-ff` **`323e667`**（零冲突，分支 `feature/image-near-duplicate-band-weight` **永久保留**）→ 基线收口 `75e35dd`；M2 归档/看板/勾选笔随下一笔持久化）
- **测试基线四元组**：`merge=323e667` + Surefire **报告 322 / 用例 2012 / F0 / E0 / S0** + Failsafe **报告 42 / 用例 143 / F0 / E0 / S12** + `CI=#167`（Run 39802029858，**6/6 全绿**，实测号以 `docs/main-agent-logbook.md` §152 为准）。只增不减：本卡前 2004 → 2012（+8 = 该类 9→17 例）、failsafe 128/143 持平。`scripts/test-baseline.txt` = `source-revision=323e667`（树净，非 `-dirty`）、`measured-at=2026-10-22T15:08:11Z`
- **验证路径 = M1 树等价快车道（连续第十七卡）**：`git rev-parse "323e667^{tree}"` 与分支 tip `^{tree}` 同为 **`c627d4d130f6a7589280c3c9182e654a5ca5ef22`** → 免本地 clean verify / merge-gate 复跑（执行端已在等价树跑过 BUILD SUCCESS + 10/10），由 CI `build-and-gate` 兜底（#167 绿）
- **复核**：**B 档，主 Agent 审 diff 裁决通过**（无独立复核，符合分档）。审计要点：段边界现算零新增同步点、三档权重严格单调且 LOW=旧 0.5、`tierOf`/`floor` 语义零改、文本路零 diff、`band` 缺失回落 0.5、幂等靠 `imageSimilarityBandWeightApplied`；**主 Agent 独立复算定标 90 对落带 = 18/0/0/1/71、`max_cross=0.9778706`**，与落带文档逐格一致。归档 = `openspec/changes/archive/image-near-duplicate-band-weight/` + `work/archive/closed-cards/task-card-image-near-duplicate-band-weight.md`（SHA256 `1ADFED17…` 一致）
- **本卡关键事实**：唯一落带样本是**跨出版物版式图（人判异图）** → 落 `NEAR_LOW` ×0.5 = 旧行为 ⇒ **本改动在 90 对定标数据上行为变化为零**，收益只在生产近重复图上体现；`NEAR_HIGH/MID` **零样本**，0.75/0.6 是"方向单调、未经本锚校准"的缺省值（文档 §6 明写）
- **前端披露**：本卡 `frontend/` 零 diff；零新依赖；`frontend/bundle-budget.json` 保持上一卡回填后的 app 201310B（#167 体积预算绿）
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **近重复图降权分段系数（image-near-duplicate-band-weight，B 档，刚刚合入）**：`ImageSimilarityGate` 把 `[floor=0.9887, 阈值=0.9944)` 三等分 —— `NEAR_HIGH`（≥ floor+2Δ/3）**×0.75** / `NEAR_MID` **×0.6** / `NEAR_LOW`（≥ floor）**×0.5**（= 旧统一值，不放松）；段边界由 `floor + Δ/3`、`floor + 2Δ/3` **现算**，锚重标定自动缩放、零新增配置键；降权仍在 rerank 之后，`band` 缺失回落 0.5，重复调用幂等。
2. **超管按用户查看 AI token 消耗（token-usage-admin-view，A 档）**：`GET /ai/token-usage/by-user` + `/summary`（只读、分页 ≤200、区间 ≤366 天、半开区间）；按 `user_id_ref` 聚合 token 三口径 + 调用数 + 失败数；权限 **810** 只授 `role_id=1`；前端「AI 用量」页明示「embedding token 恒 0 = 未知，不是免费」。
3. **图像路独立相似度硬闸门（image-retrieval-threshold-gate，A 档）**：独立键 0.9944 + 三档 tier + 下限 0.9887 + 重排后降权（本卡在此之上细分）。
4. **多模态灰度授权 + 异图阈值定标 + embedding 计量口径（A 档）** / 5. **真共享空间生产灰度真跑（A 档）**。
6. **v6.0 主套件真跑落锚** / 7. **PDF 摄取链纳入图文评测** / 8. **图文跨模态召回质量评测** / 9. **多模态真共享空间升级**（均 A 档）。
10. **图片摄取失败孤儿即时回滚（C 档）** / 11. **知识库图片切片摄取 + IMAGE 回显（A 档）** / 12. **收尾小卡 ⑬⑭（B 档）**。
13. **待办池四点收口 / 三点收口 / 测试加固五点** / 14. **引用对齐三阈值动态化** / 15. **RAG 评测三盲区补齐** / 16. **五路语义 hook 收敛**。
17. **ProjectFile 域收口** / 18. **审批附件级联死边移除** / 19. **循环写收口批 / 工程债三合卡** / 20. **附件写权限+重置密码+发票纳管**。
21. **知识库物理双清 + 门禁同源** / 22. **登录链路安全加固包** / 23. **商机审批阻断 + 确认链路审计** / 24. **已建未接线基建接线** / 25. **公海竞态 CAS** / 26. **写端点校验收口**。
27. **向量运维端点收口** / 28. **客户流失预警 + 再激活** / 29. **CustomerInsightPanel + AI 转 Action** / 30. **销售线索漏斗（V42）/ 合同预警 / 公海池流转（V41）**。
31. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF；5 大业务实体语义检索；四档数据权限闸。

> **NcssCount 豁免定论（Owner 2026-10-22 拍板）**：全仓 2 处类级豁免（DocumentIngestionService / CustomerContactServiceImpl）均为 documented tradeoff，维持不拆。

---

## 3. 当前在途与候选队列（image-near-duplicate-band-weight 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**（两张 A/B 卡均已闭环归档）
- **待办池**：**0 条**
- **长期储备（按优先级）**：
  - **近重复权重定标（新）**：`NEAR_HIGH`/`NEAR_MID` 零样本，0.75/0.6 未经校准 —— 需从生产灰度抽 query-image 对余弦，**每带 ≥30 样本**再调；`NEAR_LOW` 建议长期不动（见 `docs/rag-quality/image-near-duplicate-bands-v1.md` §7）
  - **权限模块分组未覆盖 800 段（存量缺口，建议单卡）**：`PermissionModuleResolver` 只认七个前缀，800–810 全落「其他模块权限」→ 授权页全量视图过滤掉，超管无法在角色授权页勾选任何 AI 权限
  - **生产开关真打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`，超管按成本基线运行期开
  - **前端权限类型与契约无编译期对账**：手写 TS 类型靠 `check-openapi-consistency.sh` 的后端侧对账
  - 全域权限清单文档（`docs/permission-matrix-audit.md` 属历史快照，遵裁决不改写）；在线考试子域（`work/_online-exam-backlog.md`）；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：`PermissionModuleResolver` 800 段分组（影响 AI 全模块授权页，价值高、改动面小）

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡（< 40 行）；每份提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件
4. **复核强度分档**：C 档审 diff；B 档门禁 0 + 审 diff（本卡）；**A 档（迁移/权限/真跑/契约）必须独立复核四判据**（上一卡 token-usage 走两轮复核并抓到 B1 死锁）
5. **子 Agent 派发纪律**：执行与复核子 Agent **一律由 Owner 亲自派发**
6. **合并闭环纪律**：`merge --no-ff` 保留分支 → 树等价判等 → 相等走快车道 → **在干净树上重锚基线**（否则留 `-dirty`）→ push → CI 6/6
7. **"跑不了"结论一律挂 CI 条件**：本地装不下的构建/真跑，不得直接"接受"，须由 CI 对应 job 定案（上一卡体积预算即由此抓到真红）

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（近两卡零解冻）
- 数据库迁移：`ls src/main/resources/db/migration`（实测量高 **V43**，下一可用 **V44**）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）；活动日志 `docs/main-agent-logbook.md`（**最新 §152**）
- 历史任务卡归档：`work/archive/closed-cards/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- 图像检索闸门与降权带：`ImageSimilarityGate` + `docs/dynamic-config-keys.md` 该键节 + `docs/rag-quality/image-near-duplicate-bands-v1.md`
- 计量与超管视图：`docs/token-metering.md`（§6 零 token 读法、§7 超管按用户查看）
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡确认归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
