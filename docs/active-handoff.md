# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（token-usage-admin-view 超管按用户查看 AI token 消耗，A 档，计量从"记账"到"对账"）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@3ed1b07`（= origin/master；本卡闭环：基点 `13be738` → 分支 8 笔（openspec / feat 后端 / feat 前端 / test / docs / baseline / **B1 修复** / baseline）→ M1 `git merge --no-ff` **`10e8152`**（零冲突，分支 `feature/token-usage-admin-view` **永久保留**）→ 归档笔 `163ada3` → 基线收口 `1241ee5` → **体积预算修正 `3ed1b07`**）
- **测试基线四元组**：`merge=10e8152` + Surefire **报告 322 / 用例 2004 / F0 / E0 / S0** + Failsafe **报告 42 / 用例 143 / F0 / E0 / S12** + `CI=#165`（Run 39786449547，**6/6 全绿**，实测号以 `docs/main-agent-logbook.md` §151 为准）。只增不减：本卡前 318/1991/0 + 41/134/12 → +2 报告 +13 用例、failsafe +1 报告 +9 用例。`scripts/test-baseline.txt` = `source-revision=163ada3`（树净 `--update`，非 `-dirty`）、`measured-at=2026-10-22T13:06:12Z`
- **验证路径 = M1 树等价快车道（连续第十六卡启用）**：`git rev-parse "10e8152^{tree}"` 与 `git rev-parse "feature/token-usage-admin-view^{tree}"` 同为 **`b9158665f179cb568086e4c5742d3ca91bd2cf84`**，逐字节相等 → 免本地 clean verify / merge-gate 复跑（执行端已在等价树跑过 BUILD SUCCESS + 10/10），本地复跑职责由远端 CI `build-and-gate` 兜底（#165 已绿）
- **复核**：**A 档 GO**（迁移 + 权限 + 前端页）—— 独立复核两轮：首轮 ①②③ PASS、④ 判 **B1 阻断**（前端菜单门控读 `role_permissions`，而 810 零授权、超管直通只在拦截器 → 菜单恒不可见）→ `V43` 补授 `role_id=1` 并加「超管 `getMyPermissionGrouped` 含 `AI_TOKEN_USAGE_VIEW`」自证断言 → 增量复核 **④ PASS，最终无条件 ACK**。归档物料 = `openspec/changes/archive/token-usage-admin-view/` + `work/archive/closed-cards/task-card-token-usage-admin-view.md`（SHA256 `0E63584F…` 一致）
- **本卡两个真事件（都要记住）**：① **B1 死锁**教训 = 「后端超管直通」≠「前端菜单可见」，前端可见性读的是 `role_permissions` 数据面；② **体积预算**：CI 首轮红（`app gzip 192.72 KiB` 超 `192.48 KiB` +0.24 KiB），因本机 esbuild 临时二进制删除被宿主拒绝（`ESBUILD_BINARY_PATH` 亦无效）无法本地 `--update`，Owner 授权后按 **CI 实测 ×1.02** 回填 `frontend/bundle-budget.json` app 桶 197104→**201310B**（$comment 内留痕），#165 复绿
- **前端披露**：`frontend/openapi.yaml` +175 行（由 `scripts/check-openapi-consistency.sh` 强制逐字对账，主 Agent 实测 EXIT=0）；`typed-router.d.ts` 等生成物零提交；零新依赖
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支检出 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **超管按用户查看 AI token 消耗（token-usage-admin-view，A 档，刚刚合入）**：`GET /ai/token-usage/by-user` + `/summary`（只读、分页 ≤200、区间 ≤366 天、半开区间 `[start, end)`）；按 `user_id_ref` 聚合 token 三口径 + 调用数 + 失败数 + 占比，还原 `系统`/`未知来源`/`已删除用户(#id)`；新权限 **810 AI_TOKEN_USAGE_VIEW**（只授 `role_id=1`，业务角色零授权）；前端「AI 用量」页显式警示「embedding 端点 token 恒 0 = 未知，不是免费」。
2. **图像路独立相似度硬闸门（image-retrieval-threshold-gate，A 档）**：`rag.retrieval.image-similarity-threshold`（默认 0.9944）+ `ImageSimilarityGate` 三档 + 近似同图下限 0.9887 + 重排后 ×0.5 降权。
3. **多模态灰度授权 + 异图阈值定标 + embedding 计量口径（A 档）** / 4. **真共享空间生产灰度真跑（A 档）**。
5. **v6.0 主套件真跑落锚** / 6. **PDF 摄取链纳入图文评测** / 7. **图文跨模态召回质量评测** / 8. **多模态真共享空间升级**（均 A 档）。
9. **图片摄取失败孤儿即时回滚（d8，C 档）** / 10. **知识库图片切片摄取 + IMAGE 回显（A 档）** / 11. **收尾小卡 ⑬⑭（B 档）**。
12. **待办池四点收口 / 三点收口 / 测试加固五点** / 13. **引用对齐三阈值动态化** / 14. **RAG 评测三盲区补齐** / 15. **五路语义 hook 收敛**。
16. **ProjectFile 域收口** / 17. **审批附件级联死边移除** / 18. **循环写收口批 / 工程债三合卡** / 19. **附件写权限+重置密码+发票纳管**。
20. **知识库物理双清 + 门禁同源** / 21. **登录链路安全加固包** / 22. **商机审批阻断 + 确认链路审计** / 23. **已建未接线基建接线** / 24. **公海竞态 CAS** / 25. **写端点校验收口**。
26. **向量运维端点收口** / 27. **客户流失预警 + 再激活** / 28. **CustomerInsightPanel + AI 转 Action** / 29. **销售线索漏斗（V42）/ 合同预警 / 公海池流转（V41）**。
30. **AI 商业诊断与 RAG 检索完全体**：知识库混合检索 + RRF；5 大业务实体语义检索；四档数据权限闸。

> **NcssCount 豁免定论（Owner 2026-10-22 拍板）**：全仓 2 处类级豁免（DocumentIngestionService / CustomerContactServiceImpl）均为 documented tradeoff，维持不拆。

---

## 3. 当前在途与候选队列（token-usage-admin-view 闭环刷新）

- **在途待合入卡**：**0 张**（本卡已全链合入 `3ed1b07` 并归档）。
- **已立卡待 Owner 派发**：**`image-near-duplicate-band-weight`**（B 档，近重复图降权分段系数）—— 任务卡 `work/task-card-image-near-duplicate-band-weight.md` + `openspec/changes/image-near-duplicate-band-weight/`（权威树未跟踪）。**要点**：间隔带三等分推导（不新增常量/配置键）、权重 0.75/0.6/0.5、metadata 新增 `imageSimilarityBand` 而保留 `NEAR_DUPLICATE`、降权仍在 rerank 之后。
- **待办池**：**0 条**
- **长期储备（本卡新增 3 条）**：
  - **生产开关真打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 仍默认 `false`，运行期由超管按成本基线开
  - **权限模块分组未覆盖 800 段（新发现，存量缺口）**：`PermissionModuleResolver` 只认 `customer:/sales:/finance:/task:/report:/system:/project:` 前缀，800–810 全落「其他模块权限」→ `getAllPermissionsGroupedByModuleGrouped`（授权页全量视图）直接过滤掉，超管无法在角色授权页勾选任何 AI 权限
  - **前端权限类型与契约无编译期对账**：`useTokenUsage` 走 `axiosInstance`（生成 SDK 为 gitignore 生成物），手写 TS 类型与 `openapi.yaml` 仅靠 `check-openapi-consistency.sh` 的后端侧对账
  - **全域权限清单文档**（若需要）：`docs/permission-matrix-audit.md` 是历史审计快照，本卡遵裁决不改写
  - 在线考试子域（`work/_online-exam-backlog.md`）；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：先派发 `image-near-duplicate-band-weight`（不与其他卡争基线）；800 段权限模块分组缺口建议单独立卡（影响面横跨 AI 全模块）

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发标准**：`work/README.md` 的「目标约束型」短卡（< 40 行），禁微观语法微操；每份提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件
4. **复核强度分档**：C 档主 Agent 审 diff；B 档门禁退出码 0 + 审 diff；**A 档（迁移 / 权限 / 真跑 / 契约）必须独立复核四判据**
5. **子 Agent 派发纪律**：执行与复核子 Agent **一律由 Owner 亲自派发**
6. **合并闭环纪律**：`git merge --no-ff` 且保留分支 → 树等价判等（`M^{tree}` vs 分支 tip `^{tree}`）→ 相等走快车道、不等回退全量 verify + merge-gate → **基线必须在干净树上重锚**（否则留 `-dirty`）→ push → CI 6/6

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（本卡零解冻）
- 数据库迁移：`ls src/main/resources/db/migration`（实测量高 V43，下一可用 **V44**）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）
- 历史任务卡归档：`work/archive/closed-cards/`
- 现行活动日志：`docs/main-agent-logbook.md`（§86 起，**最新 §151**）
- 回归基线门禁：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`）
- 前端体积预算：`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`（`--update` 由真实 build 写入；本机 esbuild 受限，数值来自 CI 时须在 $comment 留痕）
- 契约对账：`bash scripts/check-openapi-consistency.sh`（后端 swagger ↔ `frontend/openapi.yaml` 逐字比对）
- 图像路闸门 / 计量口径：`ImageSimilarityGate` + `docs/dynamic-config-keys.md` + `docs/token-metering.md`（§7 超管按用户查看）

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 产生的任务卡确认归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树清洁度；③ 立即响应需求。
