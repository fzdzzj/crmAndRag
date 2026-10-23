# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-23（calibrate-near-dup-bands 近重复三档定标真跑 v2，A 档 · 真跑 + 外呼）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master@fb658a7`（= origin/master；本卡闭环：基点 `d94877c` → 分支 **12 笔**（openspec / 语料+分析器 / IT+守卫 / 首跑锚 / v1.1 修订 / 17 项补勾 / 白底+退化登记+重跑 / 文档重写 / 基线溯源）→ M1 `git merge --no-ff` **`1941c08`**（零冲突，分支 `feature/calibrate-near-dup-bands` **永久保留**）→ 基线收口 `fb658a7`；M2 归档/看板/勾选笔随本笔持久化）
- **测试基线四元组**：`merge=1941c08` + Surefire **报告 328 / 用例 2059 / F0 / E0 / S0** + Failsafe **报告 43 / 用例 144 / F0 / E0 / S13** + `CI=#173`（Run 39937876101，**6/6 全绿**）。本卡前 325/2032 → 328/2059（+3 报告 +27 例 = 语料/分析器/锚守卫三个 surefire 类）；failsafe 42/143/12 → 43/144/13（+1 IT，默认 opt-in 跳过）。`scripts/test-baseline.txt` = `source-revision=1941c08`（**树净，非 `-dirty`**）、`measured-at=2026-10-23T10:15:05Z`
- **验证路径 = M1 树等价快车道（连续第二十卡）**：merge `1941c08` 树 == 分支 tip `e9598db` 树 == **`e218ea72fc8ab97b1587cb04241b26e580c15001`**（`git show -s --format=%T` 逐字节相等）→ 免本地 clean verify / merge-gate 复跑，由 CI `build-and-gate` 兜底（#173 绿）
- **本卡真跑**：两轮真跑合计外呼 **720 次**（首轮 288 有效 + 首轮作废 144 限流 + 首轮 288 配对 bug 作废 + 第二轮 288 有效），落锚 `image-threshold-calibration-v2.json`（24 base × 5 变体 = 120 变体 / 144 图 / 288 次 / 469.1 s / 0 失败）。首轮结论已作废，见下条
- **本卡两轮复核（A 档，Owner 亲派）**：首轮四判据 PASS 但**主 Agent 裁决退回**——24 个 base 中 6 个的五类变体余弦逐 15 位全等（`toRgb` 用 `TYPE_INT_RGB` 不铺底色 ⇒ alpha 源被合成成黑 ⇒ 五变体坍缩成同图），致 F1 依据 `67/120=55.8%` 里 30 对来自坏数据、`candidateFloor=0.4199` 纯属误读；修订 v1.1（白底合成 + 退化登记 + 四条防退化守卫 + 判定树分母明确）后第二轮复核 PASS，`文件=proposal §9 / tasks §9`
- **本卡结论（判定树 F0–F5 照判）**：**仅 F4 命中**——三近似带落带 `3 / 1 / 6` 全部 < 30 ⇒ 三档权重 `0.75 / 0.6 / 0.5` **维持现值**（缺样本不是调整理由）；F1 未命中（`50/120=41.7% < 50%`）⇒ `candidateFloor = null`；F0/F2/F3/F5 未命中。**生产常量零改动**：`ImageSimilarityGate` 全常量、v1 锚、bands-v1、`sources.json`、三份 PDF、`RealPdfAssets`、`ImageThresholdCalibration*`、`pom.xml`、`frontend/` 全空 diff，`src/main/**` 整域零 diff
- **实质发现（观察项，未预注册、不作决策依据）**：120 对同源变体里 **63 对（52.5%）≥ 阈值**（缩放/重压缩被当"同图"⇒ 完全不降权，冗余照样挤占 top-k）、**50 对（41.7%）< floor**（裁切型被当"异图"⇒ 直接过滤，`crop-12` 24/24 全灭）、**仅 7 对（5.8%）落进三档带**；`jpeg-q80` 中位 `0.9981` < `scale-085` 中位 `0.9996`（二次压缩比缩小更伤，单调性反序 1 处已如实记录）
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域双收口**：`PermissionModule` 号段单一事实源（`/permission/list` 恒 10 桶、零丢弃，模块归属只许委托）+ 授权页 UX（搜索 / 只看已选 / 模块计数 / 全选清空）+ **权限字面量类型生成**（`permission.gen.ts` 由枚举生成，漂移即 surefire 红）
2. **近重复图分段降权（已真跑定标 v2）**：`[floor=0.9887, 阈值=0.9944)` 三等分 → `NEAR_HIGH ×0.75 / NEAR_MID ×0.6 / NEAR_LOW ×0.5`，边界现算零新增配置键；**v2 定标已跑完**：12 个同源变体只是"多数绕开近似带"而非"带内装异图"——三带样本 3/1/6 远不足 30 ⇒ 权重**维持**，`candidateFloor` 为 `null`
3. **超管按用户查看 AI token 消耗**：`GET /ai/token-usage/by-user` + `/summary`；权限 **810** 只授 `role_id=1`；前端明示「embedding token 恒 0 = 未知，不是免费」
4. **图像路独立相似度硬闸门**：独立键 0.9944 + 三档 tier + 下限 0.9887 + 重排后降权
5. **多模态灰度授权 + 异图阈值定标 + embedding 计量口径（A 档）** / 6. **真共享空间生产灰度真跑（A 档）** / 7. **v6.0 主套件真跑落锚** / 8. **PDF 摄取链纳入图文评测** / 9. **图文跨模态召回质量评测** / 10. **多模态真共享空间升级**
11. **图片摄取失败孤儿即时回滚（C 档）** / 12. **知识库图片切片摄取 + IMAGE 回显（A 档）** / 13. **收尾小卡 ⑬⑭（B 档）** / 14. **待办池四点/三点收口 + 测试加固五点**
15. **引用对齐三阈值动态化** / 16. **RAG 评测三盲区补齐** / 17. **五路语义 hook 收敛** / 18. **ProjectFile 域收口** / 19. **审批附件级联死边移除** / 20. **循环写收口批 / 工程债三合卡**
21. **附件写权限+重置密码+发票纳管** / 22. **知识库物理双清 + 门禁同源** / 23. **登录链路安全加固包** / 24. **商机审批阻断 + 确认链路审计** / 25. **已建未接线基建接线** / 26. **公海竞态 CAS**
27. **写端点校验收口** / 28. **向量运维端点收口** / 29. **客户流失预警 + 再激活** / 30. **CustomerInsightPanel + AI 转 Action** / 31. **销售线索漏斗（V42）/ 合同预警 / 公海池流转（V41）** / 32. **AI 商业诊断与 RAG 检索完全体**（混合检索 + RRF、5 大实体语义检索、四档数据权限闸）

> **NcssCount 豁免定论（Owner 2026-10-22 拍板）**：全仓 2 处类级豁免（DocumentIngestionService / CustomerContactServiceImpl）均为 documented tradeoff，维持不拆。

---

## 3. 当前在途与候选队列（calibrate-near-dup-bands 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**
- **待办池**：**0 条**
- **长期储备（按优先级）**：
  - **近重复机制是否该重构（唯一有新实测数据支撑项）**：v2 定标显示近似带只覆盖 **5.8%** 同源变体（52.5% 被判同图不降权、41.7% 被判异图直接过滤，`crop-12` 全灭）。若 Owner 要动，必须**先预注册"带内覆盖率"门槛**（v1 那张 F1 用 below-floor 占比当替身，锋利度不够），再谈 floor 取值从 `crossImageCeiling=0.9887` 改为 `nearDuplicate.p05`；**权重数字仍不可由分布定出**（需查询-相关性真值，属更大一张卡）
  - **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；核对/回滚清单已落 `docs/multimodal-gray-rollout.md`
  - **`openapi.yaml` 的 permission schema 由同一生成器驱动**（可选增强，当前契约侧无枚举）
  - 全域权限清单文档（`docs/permission-matrix-audit.md` 属历史快照，遵裁决不改写）；在线考试子域（`work/_online-exam-backlog.md`）；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：近重复机制重构（带覆盖率门槛先行），或由 Owner 另指定主题

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；每份提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件
4. **复核强度分档**：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/契约）独立复核四判据。**A 档真跑卡必须重跑「真跑真实性」判据**（本卡两轮真跑 = 一次修订前后对照）
5. **子 Agent 派发纪律**：执行与复核子 Agent **一律由 Owner 亲自派发**
6. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等**（`git show -s --format=%T`）→ 相等走快车道 → **在干净树上重锚基线** → push → CI 6/6
7. **"跑不了"结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接"接受"，须由 CI 对应 job 定案
8. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；生成物必须由**默认跑的校验测试**守住（篡改一个字即红）
9. **证据纪律**：补勾依据里每个数字必须能独立复算；基线三连必须自查 `source-revision` 无 `-dirty` 且 = 被测量的干净 commit；**预注册门槛必须锋利**（用代理比例当替身会让真结论漏网——本卡 F1 即教训）
10. **数据质量先于结论**：「五种不同变换给出同一余弦」这类**不可能事件**一旦出现，先怀疑生成管线（底色 / alpha / 尺寸），再谈统计

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（近五卡零解冻）
- 数据库迁移：`ls src/main/resources/db/migration`（实测量高 **V43**，下一可用 **V44**）
- 历史阶段裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）；活动日志 `docs/main-agent-logbook.md`（**最新 §155**）
- 历史任务卡归档：`work/archive/closed-cards/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- 权限模块归属：`docs/permission-module-catalog.md`；权限字面量类型：`frontend/src/types/permission.gen.ts`（生成物，勿手改）+ `PermissionTypeCatalogGenTest`
- **近重复定标（三件套）**：锚 `docs/rag-quality/image-threshold-calibration-v2.json`（唯一写入口 `ImageNearDuplicateCalibrationIT`，opt-in `RAG_VISION_EMBED_REAL=1`）+ 结论 `image-near-duplicate-bands-v2.md`（双视图落带 / 判定树 / 逐 kind 分布）+ 复算 `node scripts/rag-quality/near-duplicate-band-stats.mjs --anchor … --expect 207,3,1,6,323`（无参数仍守 v1 `18/0/0/1/71`）
- 计量与超管视图：`docs/token-metering.md`（§6 零 token 读法、§7 超管按用户查看）
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡确认归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
