# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-22（close-permission-module-gap 权限模块归属改为号段单一事实源，A 档，授权页可用性收官）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@965f737`（= origin/master；本卡闭环：基点 `ad30fed` → 分支 6 笔（openspec / feat 后端 / feat 前端 / test / docs+tasks / docs 口径修正）→ M1 `git merge --no-ff` **`b3bfcab`**（零冲突，分支 `feature/close-permission-module-gap` **永久保留**）→ 基线收口 `965f737`；M2 归档/看板/勾选笔随下一笔持久化）
- **测试基线四元组**：`merge=b3bfcab` + Surefire **报告 324 / 用例 2030 / F0 / E0 / S0** + Failsafe **报告 42 / 用例 143 / F0 / E0 / S12** + `CI=#169`（Run 39822742594，**6/6 全绿**）。只增不减：本卡前 322/2012 → 324/2030（+2 报告 +18 例 = 两个新单测类 + 既有 IT 增量断言）；failsafe 42/143/12 持平（只加断言不增用例）。`scripts/test-baseline.txt` = `source-revision=b3bfcab`（**树净，非 `-dirty`**）、`measured-at=2026-10-22T17:26:11Z`
- **验证路径 = M1 树等价快车道（连续第十八卡）**：`git rev-parse "b3bfcab^{tree}"` 与分支 tip `699d35a^{tree}` 同为 **`b2af7680f50fa536f4cc972c68c6a3621e6827e8`** → 免本地 clean verify / merge-gate 复跑，由 CI `build-and-gate` 兜底（#169 绿）
- **复核**：**A 档，独立复核四判据全 PASS → ACK**。附 2 项非阻断条件，**均在合并前关闭**：① 执行端的 `--update` 落成 `source-revision=c5ae98e-dirty` → 由主 Agent 在*合并后干净树*重锚（`b3bfcab`，无 `-dirty`）；② `docs/permission-module-catalog.md` 中「真实库其他模块权限为空」与 IT 实测矛盾 → 主 Agent 合并前落 `699d35a` 修正（生产库由迁移+同步从零补齐时为真，IT 库因 `init_data.sql:195-205` 另种 id 1-10 非枚举行故非空）。归档 = `openspec/changes/archive/close-permission-module-gap/` + `work/archive/closed-cards/task-card-close-permission-module-gap.md`（SHA256 `35199500…` 一致）
- **本卡关键事实**：缺陷不是「漏 800 段」而是**全丢**——`permissions_name` 存枚举裸名（`PermissionSyncRunner:100-102/218-219`），而 `PermissionModuleResolver` 按 `customer:` 冒号前缀匹配 ⇒ 所有权限判「其他模块权限」，`moduleMap.get()` 为 null 时**静默丢弃** ⇒ `/permission/list` 恒返 7 个空模块；前端授权页又用 615 行**冻结响应快照**，V21 子权限 / 608 / 800-810 / 900 在授权页**勾不到**
- **前端披露**：候选权限 **109 → 120**；`frontend/src/constants/premission.ts` 以 `R100` 迁为 `utils/tutorial/mockPermissionCatalog.ts`（仅教程 mock）；`frontend/openapi.yaml` **零 diff**（响应键动态，`check-openapi-consistency.sh` EXIT=0）；`frontend/bundle-budget.json` 不动（#169 体积预算绿）
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限模块号段目录（close-permission-module-gap，A 档，刚刚合入）**：`PermissionModule` 枚举是模块归属**唯一事实源**（号段取权限 ID 十进制首位：`1xx/11xx` 客户、`2xx/20xx–22xx` 销售、`3xx/30xx` 财务、`4xx/40xx` 联络任务、`5xx` 报表、`6xx` 权限管理、`7xx` 隐私、`8xx` AI、`9xx` 知识库，未命中 ⇒「其他模块权限」）；`PermissionModuleResolver` 与 `PermissionSyncRunner` **只许委托**；`/permission/list` 恒 10 桶、零丢弃、顺序 = 枚举声明顺序；死代码 `getAllPermissionsGroupedByModule`（NPE 地雷）已删；防漂移单测遍历 `PermissionOperates.values()`，新增权限漏归段即红
2. **近重复图降权分段系数（image-near-duplicate-band-weight，B 档）**：`[floor=0.9887, 阈值=0.9944)` 三等分 —— `NEAR_HIGH` ×0.75 / `NEAR_MID` ×0.6 / `NEAR_LOW` ×0.5（= 旧统一值）；边界由 `floor + Δ/3`、`floor + 2Δ/3` **现算**，锚重标定自动缩放、零新增配置键
3. **超管按用户查看 AI token 消耗（token-usage-admin-view，A 档）**：`GET /ai/token-usage/by-user` + `/summary`（只读、分页 ≤200、区间 ≤366 天、半开区间）；权限 **810** 只授 `role_id=1`；前端「AI 用量」页明示「embedding token 恒 0 = 未知，不是免费」
4. **图像路独立相似度硬闸门（image-retrieval-threshold-gate，A 档）**：独立键 0.9944 + 三档 tier + 下限 0.9887 + 重排后降权。
5. **多模态灰度授权 + 异图阈值定标 + embedding 计量口径（A 档）** / 6. **真共享空间生产灰度真跑（A 档）** / 7. **v6.0 主套件真跑落锚** / 8. **PDF 摄取链纳入图文评测** / 9. **图文跨模态召回质量评测** / 10. **多模态真共享空间升级**。
11. **图片摄取失败孤儿即时回滚（C 档）** / 12. **知识库图片切片摄取 + IMAGE 回显（A 档）** / 13. **收尾小卡 ⑬⑭（B 档）** / 14. **待办池四点/三点收口 + 测试加固五点**。
15. **引用对齐三阈值动态化** / 16. **RAG 评测三盲区补齐** / 17. **五路语义 hook 收敛** / 18. **ProjectFile 域收口** / 19. **审批附件级联死边移除** / 20. **循环写收口批 / 工程债三合卡**。
21. **附件写权限+重置密码+发票纳管** / 22. **知识库物理双清 + 门禁同源** / 23. **登录链路安全加固包** / 24. **商机审批阻断 + 确认链路审计** / 25. **已建未接线基建接线** / 26. **公海竞态 CAS**。
27. **写端点校验收口** / 28. **向量运维端点收口** / 29. **客户流失预警 + 再激活** / 30. **CustomerInsightPanel + AI 转 Action** / 31. **销售线索漏斗（V42）/ 合同预警 / 公海池流转（V41）** / 32. **AI 商业诊断与 RAG 检索完全体**（混合检索 + RRF、5 大实体语义检索、四档数据权限闸）。

> **NcssCount 豁免定论（Owner 2026-10-22 拍板）**：全仓 2 处类级豁免（DocumentIngestionService / CustomerContactServiceImpl）均为 documented tradeoff，维持不拆。

---

## 3. 当前在途与候选队列（close-permission-module-gap 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**
- **待办池**：**0 条**
- **长期储备（按优先级）**：
  - **授权页 UX（新）**：目录从 109 条涨到 120 条 / 10 个折叠面板，父子勾选联动、按名搜索、批量全选在长列表下的手感未验证 —— 建议单卡，Owner 可先肉眼验收再决定是否值得做
  - **近重复权重定标**：`NEAR_HIGH`/`NEAR_MID` 零样本，0.75/0.6 未经校准 —— 需从生产灰度抽 query-image 对余弦，**每带 ≥30 样本**再调（`docs/rag-quality/image-near-duplicate-bands-v1.md` §7）
  - **前端权限字面量类型（新）**：本卡把 `PermissionName` 由「冻结快照推导」改为 `string`（快照已降级为 tutorial fixture）；若要恢复编译期收窄，应由 `openapi.yaml` 或 `PermissionOperates` **生成**，不再手抄
  - **生产开关真打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`，超管按成本基线运行期开
  - 全域权限清单文档（`docs/permission-matrix-audit.md` 属历史快照，遵裁决不改写）；在线考试子域（`work/_online-exam-backlog.md`）；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：授权页 UX（价值取决于 Owner 在真机上的手感判断），其次近重复权重生产采样

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡（< 40 行）；每份提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件
4. **复核强度分档**：C 档审 diff；B 档门禁 0 + 审 diff；**A 档（迁移/权限/真跑/契约）必须独立复核四判据**（本卡 A 档复核抓到 2 处口径问题，均为文档/基线元数据，代码判据全 PASS）
5. **子 Agent 派发纪律**：执行与复核子 Agent **一律由 Owner 亲自派发**
6. **合并闭环纪律**：`merge --no-ff` 保留分支 → 树等价判等 → 相等走快车道 → **在干净树上重锚基线**（否则留 `-dirty`，本卡执行端已复犯一次，靠 M1 重锚修正）→ push → CI 6/6
7. **"跑不了"结论一律挂 CI 条件**：本地装不下的构建/真跑（如宿主机 esbuild 删临时文件被拒）不得直接"接受"，须由 CI 对应 job 定案
8. **映射类改动铁律（本卡教训）**：任何「按名字/前缀分类」的逻辑，必须先证明**数据侧实际形态**与其一致（本卡前缀映射与库内裸名不符多年，导致授权页长期空目录且无人发现）

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（近三卡零解冻）
- 数据库迁移：`ls src/main/resources/db/migration`（实测量高 **V43**，下一可用 **V44**）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）；活动日志 `docs/main-agent-logbook.md`（**最新 §153**）
- 历史任务卡归档：`work/archive/closed-cards/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- 权限模块归属：`docs/permission-module-catalog.md`（号段表 + 单一事实源 + 防漂移测试名）
- 图像检索闸门与降权带：`ImageSimilarityGate` + `docs/dynamic-config-keys.md` 该键节 + `docs/rag-quality/image-near-duplicate-bands-v1.md`
- 计量与超管视图：`docs/token-metering.md`（§6 零 token 读法、§7 超管按用户查看）
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡确认归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
