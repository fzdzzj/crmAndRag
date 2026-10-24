# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-24（query-relevance-truth 三合一卡：融合级代价边界 + 检索级真值 + 档位签收页，**B 档**）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`5549277`（M1 合并）+ `d92d368`（基线重锚）+ 本归档笔**，= origin/master。分支 `feature/query-relevance-truth` **永久保留**（基点 `c789e23` → **5 笔**：`8f2430a` 立项 / `24e2015` G1 / `93805d9` G2 / `52c4856` G3 / `64f5727` 基线）
- **测试基线四元组**：`source-revision=5549277`（**树净，非 `-dirty`**）+ Surefire **报告 341 / 用例 2109 / F0 / E0 / S0** + Failsafe **报告 44 / 用例 145 / F0 / E0 / S13** + `CI=#179`（Run 40057768964，**6/6 全绿**）。上一卡 337/2092/0 + 44/145/13 → 341/2109/0 + 44/145/13（+4 报告 +17 例，恰为本卡 5 个新测试类）
- **本卡规模**：**16 文件 / +3711 −4**；merge 树 == 分支 tip 树 == **`f426df7b5d786229d45fb7386d95b1cd9bbf5125`**。树等价只免「合并前再验」，**不免基线重锚**——本卡仍在干净合并树上跑了 `mvn clean verify` + `--update`
- **三组结论（按组判定，互不连坐）**：
  - **G1 融合级代价边界 → `INVISIBLE-IN-ROUTE-ONLY`**：源码实测两件事——① `downWeightNearDuplicates`（`:267`）在 `fuseRoutes`（`:278`）**之前**；② 融合是**分数制**不是 RRF（`DEFAULT_TEXT_ROUTE_WEIGHT=0.70`@L18 / `DEFAULT_IMAGE_ROUTE_WEIGHT=0.30`@L19，`mergeRoute` `:280` 乘权、`:285` 同键**累加**）。由此两条推论：**路由内 5002 点数值验证 0 反序**（三档带权重按 cosine 单调 + 带边界连续 ⇒ 只改绝对分、不改任何两两顺序）；**跨路位移上界 `0.3×(1−w)` = 0.075 / 0.12 / 0.15**（w=0.75/0.6/0.5）
  - **G1 可见性**：基线档带内候选 7 个、共现竞争者观测 **2 条**（同文档 gap `0.001327`、跨文档 gap `0.004665`），两条都落在三档窗口内 ⇒ 位移**数学可能**，但发生还须真实查询共现（**未测**）。`missingEvidence` 三项：文本路 rerank 分数分布 / 真实查询共现 / 变体↔第三图余弦
  - **G2 检索级真值 → T1–T5 全绿**：**396 例**（正例 120 = 24 base × 5 kind + 负例 A 176 跨文档 + 负例 B 100 同文档）；真值由语料 provenance 唯一生成（`goldenId(baseKey)`，零手写 id）；`RagQualityEvaluator` **零改动**（`contaminationAtK` 明写为既有 top-k 的**派生量**）；**T4 交叉校验 8 档 `mismatches=0`**——本次 `recallAtK` 与上一卡锚 `R(f)` 逐档相等（`0.583333`→`0.941667`）；`contaminationAtK` 全档恒 **1/176**（cross 余弦最高 `0.988736`、次高 `0.73`）
  - **G3 档位签收页 → D1–D4 全绿**：`docs/rag-quality/near-dup-floor-decision-request-v1.md` 一页 = 四锚 SHA（守卫实算）+ 三口径表（pair / 融合 / 检索）+ 「仍缺什么」4 条 + 四候选（维持 `0.9887` / `0.982` / `0.98` / `0.90`）+ 签收栏；禁「已决定/已采用/建议上线」，**不给推荐档**
- **本卡复核（B 档，Owner 亲派）= PASS**（G1 5/5、G2 5/5、G3 4/4；R2 由复核方独立复算 5002 点 0 反序、R3 自源码复算三档上界逐值吻合、T4 以第三路径回算 8 档零偏差）；主 Agent 审计 **PASS** 并新增 1 条观察项，5 条观察项**全部非阻断**
- **本卡最重要的一课（解释了一个悬置两卡的疑问）**：为什么 `S_same`「上界」在检索级**看不见**——因为降权发生在融合**之前**、融合是**分数制**，而路由内三档权重按 cosine 单调 ⇒ 降权只改绝对分、不改排序；跨路只留下 `0.3×(1−w)` 的位移窗。所以「泄漏」是**必要非充分**条件，**任何「降权导致召回损失」的说法都不成立**（文档与守卫双重禁用该措辞）
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**，`OpenApiPermissionContractParityTest` 守）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复图像已备三口径证据（生产常量零改动）**：`[NEAR_DUPLICATE_FLOOR=0.9887, threshold=0.9944)` 三档降权 `×0.75/×0.6/×0.5`；口径一 **pair 价目表**（`near-dup-exchange-rate-v1`，`EXCHANGE-RATE`）、口径二 **融合上界**（`near-dup-fusion-impact-v1`，`INVISIBLE-IN-ROUTE-ONLY`）、口径三 **检索级真值**（`image-near-dup-truth-v1`）；三者汇总成一页供签收（`near-dup-floor-decision-request-v1.md`）。**档位仍待 Owner 拍板**，`ImageSimilarityGate` 全常量零改动
4. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后，只开后者链路完全不动；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
5. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh` 把人工核对单变成可判定脚本（GO/NO-GO + 原因码），与观测面、回滚、热生效实证成套
6. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + 下限 0.9887 + 重排后降权，且降权在**融合前**）
7. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述

---

## 3. 当前在途与候选队列（query-relevance-truth 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **拍板近重复档位（Owner 决，非代码）**：签收页已备（`docs/rag-quality/near-dup-floor-decision-request-v1.md`）——`0.982` 最高效（+8.33pp 换 1 张同文档图）、`0.98`（+9.17pp / 2 张）、`0.90`（`R=94.17%` / 6 张）；**免费档不存在**；三口径同向，但跨路位移是否真发生**未定案**。动 `NEAR_DUPLICATE_FLOOR` **必须新卡**
  2. **补齐三处缺失证据（可零外呼做第 ③ 项）**：① 真实查询下文本路 rerank 分数分布（定案跨路位移）② 真实查询共现观测 ③ 变体↔第三图视觉余弦（R4 与 G2 的竞争者分数目前以 base 对为代理）
  3. **用户意图级相关性标注（唯一能消掉 A1/A2 假设的路）**：须真实查询 + 人标；本卡只做到 provenance 级检索真值，**严禁**用 agent 生成的标签充数
  4. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`，执行单 `docs/multimodal-gray-rollout.md` §9/§10/§11/§12/§13
  5. **前端小表随站点增长维护**：`uiEndpointPermissions.ts` 手写 57 项但编译期受契约约束；新增按钮走同一条派生链（生成物自动，小表手补）
  6. 文档类存量：全域权限清单（`docs/permission-matrix-audit.md` 属**历史快照，遵裁决不改写**）；在线考试子域 `work/_online-exam-backlog.md`；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：储备 2（补证据，③ 可零外呼）或 1（Owner 签收后另立卡改档），或由 Owner 另指定主题

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件；执行与复核子 Agent **一律 Owner 亲派**
4. **复核强度分档**（`docs/review-intensity-policy.md`）：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/**契约**）独立复核四判据
5. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等**（相等只免「合并后立刻再验」，**不免基线重锚**）→ **干净树上 `mvn clean verify` + `--update` 重锚** → push → CI 全绿 → M2 归档
6. **「跑不了」结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接"接受"，须由 CI 对应 job 定案
7. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；生成物必须由**默认跑的校验测试**守住
8. **证据纪律**：补勾依据每个数字必须能独立复算；基线 `source-revision` 必须无 `-dirty`（且**不可能**等于自身提交 sha，正确语义是「= 被测量的那个干净 commit」）；**预注册门槛必须锋利**
9. **数据质量先于结论**：「不可能事件」先怀疑生成管线，再谈统计
10. **量纲白名单**：任何"单调性/分布"结论必须写明**量纲 + 口径 + 来源表**
11. **判据即合同**：判据两支可重叠、或出现"或反之"这类本方向无意义的词，当场消歧并附可执行断言；事后消歧只能朝**更保守**方向
12. **行尾即判据**：会做**逐字节比对**的锚/生成物，序列化必须钉死 LF 并与 `.gitattributes` 的 `text eol=lf` 一致；否则「干净 checkout」与「刚跑过写模式」结论相反（Jackson 默认缩进器用 `System.lineSeparator()`）
13. **复核 PASS ≠ 可交付**：`clean verify` 必须在**重新物化后的合并树**上跑（checkout 会重写文件）；分支已绿、复核已绿都不能替代这一步
14. **环境陷阱**：中文 UTF-8 文件的**逐行计数不要信 PowerShell `Get-Content/Select-String`**，用 `read` 工具按物理行核；含中文的 `sed/grep` pattern 进命令行易 127，改 ASCII；bash 一律走 `D:\git\Git\bin\bash.exe -lc`；**`Set-Content -Encoding utf8` 在 Windows PowerShell 会写 BOM**（提交信息改用 `-m ...` 或 `[IO.File]::WriteAllText`，否则主题行首字节污染）
15. **锚字段比较容差须与断言同容差（本卡新增）**：锚里记录「是否一致」的布尔若用 `==` 精确比较，而门禁断言用 `1e-9` 容差，就会出现「锚记 mismatch 但测试绿」——决策文档与门禁互相打脸。比较口径只能有一处

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §158**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/query-relevance-truth/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **G1**：`docs/rag-quality/near-dup-fusion-impact-v1.{json,md}`（写入口 `NearDupFusionImpactDecisionTest`，`-Dnear.dup.fusion.write=true`；守卫 `…GuardTest` 9 测；复算 `bash scripts/rag-quality/near-dup-fusion-impact-report.sh`）
- **G2**：`docs/rag-quality/image-near-dup-truth-v1.{json,md}`（写入口 `ImageNearDuplicateTruthEvaluationTest`，`-Dnear.dup.truth.write=true`；用例集 `ImageNearDuplicateTruthSuite`）
- **G3**：`docs/rag-quality/near-dup-floor-decision-request-v1.md`（守卫 `NearDupFloorDecisionRequestGuardTest` 实算四锚 SHA）
- 口径一（pair 价目表）：`docs/rag-quality/near-dup-exchange-rate-v1.{json,md}`；上游校准锚 `image-threshold-calibration-v2.json`（SHA-256 `82d24204…`）
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`
- 计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
