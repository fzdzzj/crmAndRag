# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（reserve-sweep-1 储备四项合一：授权页 UX + 权限类型生成 + 近重复定标管线 + 开关核对单，B 档）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@95615e2`（= origin/master；本卡闭环：基点 `1396c24` → 分支 3 笔（openspec / 实现 / 基线重锚+证据更正）→ M1 `git merge --no-ff` **`12cfb64`**（零冲突，分支 `feature/reserve-sweep-1` **永久保留**）→ 基线收口 `95615e2`；M2 归档/看板/勾选笔随下一笔持久化）
- **测试基线四元组**：`merge=12cfb64` + Surefire **报告 325 / 用例 2032 / F0 / E0 / S0** + Failsafe **报告 42 / 用例 143 / F0 / E0 / S12** + `CI=#171`（Run 39844500274，**6/6 全绿**）。本卡前 324/2030 → 325/2032（+1 报告 +2 例 = `PermissionTypeCatalogGenTest` 校验 + 重写两例）；failsafe 42/143/12 持平。`scripts/test-baseline.txt` = `source-revision=12cfb64`（**树净，非 `-dirty`**）、`measured-at=2026-10-22T19:55:57Z`
- **验证路径 = M1 树等价快车道（连续第十九卡）**：`git rev-parse "12cfb64^{tree}"` 与分支 tip `6435e4d^{tree}` 同为 **`d92680c1a8011096d15957714b15b2a7bb9c323b`** → 免本地 clean verify / merge-gate 复跑，由 CI `build-and-gate` 兜底（#171 绿）
- **复核**：**B 档**（零迁移 / 零权限语义 / 零新常量 / 零冻结契约），Owner 仍派了独立复核；结论「**阻断**」仅一项 = 基线版本印记（`source-revision=1e14a52-dirty` ≠ tip）→ 主 Agent 修：重跑 surefire（2032/0）→ 干净树 `--update`（`source-revision=99c6cbb`、无 `-dirty`）→ 合并笔 `6435e4d` → 再于合并树重锚 `12cfb64`。附带更正 tasks 证据行（sampling-v2 实测 106 行，原记 107）。归档 = `openspec/changes/archive/reserve-sweep-1/` + `work/archive/closed-cards/task-card-reserve-sweep-1.md`（SHA256 `2196C3BC…` 一致）
- **本卡内容（四段）**：**S1** 授权页 UX（关键词分词搜索 / 只看已选 / 面板「已选/总数」/ 模块级全选清空 / 无匹配空态；`permissionCatalog.ts` +136 行纯函数、vitest +133）；**S2** 权限字面量类型生成（`types/permission.gen.ts` 143 行，120 + 10 字面量、**只出 type 零运行时**；`PermissionTypeCatalogGenTest` 默认校验、`-Dpermission.types.update=true` 才重写）；**S3** 近重复定标管线（`scripts/rag-quality/near-duplicate-band-stats.mjs` 296 行，复跑必须命中 v1 落带 `18/0/0/1/71`）+ `image-near-duplicate-sampling-v2.md`（106 行扩样操作单）；**S4** `multimodal-gray-rollout.md` +80 行开/回滚核对单
- **四类零改动（本卡硬边界，均已自证空 diff）**：`ImageSimilarityGate` 权重常量、定标锚 `image-threshold-calibration-v1.json`、`image-near-duplicate-bands-v1.md`、`DynamicConfigKeyRegistry` / `ConfigKeyTierPolicy` 默认值；`src/main/java` **整域零 diff**
- **前端披露**：`frontend/openapi.yaml` 零 diff；`frontend/bundle-budget.json` 未动（#171 体积预算绿）；`pnpm build` 本地仍撞宿主 esbuild 删临时文件被拒（已知环境），CI `Frontend` job 绿
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域双收口（近两卡）**：`PermissionModule` 号段单一事实源（`/permission/list` 恒 10 桶、零丢弃，模块归属只许委托）+ 本卡授权页 UX（搜索 / 只看已选 / 模块计数 / 全选清空）与**权限字面量类型生成**（`permission.gen.ts` 由枚举生成，漂移即 surefire 红）
2. **近重复图降权分段系数（image-near-duplicate-band-weight，B 档）**：`[floor=0.9887, 阈值=0.9944)` 三等分 —— `NEAR_HIGH` ×0.75 / `NEAR_MID` ×0.6 / `NEAR_LOW` ×0.5；边界现算、零新增配置键。**本卡只加了统计管线与扩样操作单，权重仍未定标**
3. **超管按用户查看 AI token 消耗（token-usage-admin-view，A 档）**：`GET /ai/token-usage/by-user` + `/summary`；权限 **810** 只授 `role_id=1`；前端明示「embedding token 恒 0 = 未知，不是免费」
4. **图像路独立相似度硬闸门（image-retrieval-threshold-gate，A 档）**：独立键 0.9944 + 三档 tier + 下限 0.9887 + 重排后降权
5. **多模态灰度授权 + 异图阈值定标 + embedding 计量口径（A 档）** / 6. **真共享空间生产灰度真跑（A 档）** / 7. **v6.0 主套件真跑落锚** / 8. **PDF 摄取链纳入图文评测** / 9. **图文跨模态召回质量评测** / 10. **多模态真共享空间升级**
11. **图片摄取失败孤儿即时回滚（C 档）** / 12. **知识库图片切片摄取 + IMAGE 回显（A 档）** / 13. **收尾小卡 ⑬⑭（B 档）** / 14. **待办池四点/三点收口 + 测试加固五点**
15. **引用对齐三阈值动态化** / 16. **RAG 评测三盲区补齐** / 17. **五路语义 hook 收敛** / 18. **ProjectFile 域收口** / 19. **审批附件级联死边移除** / 20. **循环写收口批 / 工程债三合卡**
21. **附件写权限+重置密码+发票纳管** / 22. **知识库物理双清 + 门禁同源** / 23. **登录链路安全加固包** / 24. **商机审批阻断 + 确认链路审计** / 25. **已建未接线基建接线** / 26. **公海竞态 CAS**
27. **写端点校验收口** / 28. **向量运维端点收口** / 29. **客户流失预警 + 再激活** / 30. **CustomerInsightPanel + AI 转 Action** / 31. **销售线索漏斗（V42）/ 合同预警 / 公海池流转（V41）** / 32. **AI 商业诊断与 RAG 检索完全体**（混合检索 + RRF、5 大实体语义检索、四档数据权限闸）

> **NcssCount 豁免定论（Owner 2026-10-22 拍板）**：全仓 2 处类级豁免（DocumentIngestionService / CustomerContactServiceImpl）均为 documented tradeoff，维持不拆。

---

## 3. 当前在途与候选队列（reserve-sweep-1 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**
- **待办池**：**0 条**
- **长期储备（按优先级）**：
  - **近重复权重真跑定标（唯一带外部依赖项）**：操作单已备好（`docs/rag-quality/image-near-duplicate-sampling-v2.md`：每带 ≥30、四类同图变体 + 两类异图对照、≈120 张图、成本 = 图数 × 2 次视觉 embedding）——**要 Owner 供语料并授权外呼**；未跑前权重维持 0.75/0.6/0.5
  - **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；核对/回滚清单已落 `docs/multimodal-gray-rollout.md`
  - **前端权限类型与 `openapi.yaml` 的对账**：本卡已让「枚举 → TS 字面量」单向生成并加校验；仍未做「`openapi.yaml` 的 permission 相关 schema 也由同一生成器驱动」（当前契约侧无枚举，属可选增强）
  - 全域权限清单文档（`docs/permission-matrix-audit.md` 属历史快照，遵裁决不改写）；在线考试子域（`work/_online-exam-backlog.md`）；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：近重复权重真跑定标（唯一能真正兑现分段系数价值的一步），否则由 Owner 另指定主题

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡（< 40 行）；每份提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件
4. **复核强度分档**：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/契约）独立复核四判据。**B 档也可能被 Owner 派复核**（本卡即如此，且复核值回票价：抓到基线 `-dirty` 印记）
5. **子 Agent 派发纪律**：执行与复核子 Agent **一律由 Owner 亲自派发**
6. **合并闭环纪律**：`merge --no-ff` 保留分支 → 树等价判等 → 相等走快车道 → **在干净树上重锚基线**（`--update` 必须在 `git status` 干净的树上跑，否则 `source-revision` 带 `-dirty`）→ push → CI 6/6
7. **"跑不了"结论一律挂 CI 条件**：本地装不下的构建/真跑（宿主机 esbuild 删临时文件被拒）不得直接"接受"，须由 CI 对应 job 定案
8. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；生成物必须由**校验测试**守住（本卡 `PermissionTypeCatalogGenTest` 默认校验，篡改一个字即红）
9. **证据纪律（本卡教训）**：补勾依据里的每个数字都要能被独立复算（本次「107 行」实为 106 行，被复核抓出）；基线三连必须自查 `source-revision` 无 `-dirty` 且 = 被测量的干净 commit

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（近四卡零解冻）
- 数据库迁移：`ls src/main/resources/db/migration`（实测量高 **V43**，下一可用 **V44**）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）；活动日志 `docs/main-agent-logbook.md`（**最新 §154**）
- 历史任务卡归档：`work/archive/closed-cards/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- 权限模块归属：`docs/permission-module-catalog.md`；权限字面量类型：`frontend/src/types/permission.gen.ts`（生成物，勿手改）+ `PermissionTypeCatalogGenTest`
- 近重复定标：`scripts/rag-quality/near-duplicate-band-stats.mjs`（复跑 → 18/0/0/1/71）+ `docs/rag-quality/image-near-duplicate-sampling-v2.md`（扩样操作单）+ `_bands-v1.md`（现状结论）
- 计量与超管视图：`docs/token-metering.md`（§6 零 token 读法、§7 超管按用户查看）
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡确认归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
