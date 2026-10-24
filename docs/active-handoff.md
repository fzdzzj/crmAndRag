# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-24（near-dup-floor-lowering：近重复下限 0.9887 → **0.982** 落地 + 三档内边界冻结，**A 档**）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`0d42809`（M1 合并）+ `a108926`（基线重锚）+ 本归档笔**，= origin/master。分支 `feature/near-dup-floor-lowering` **永久保留**（基点 `242dd4d` → **2 笔**：`e2f4279` 实现 / `0b36066` 复核后 fix1）
- **测试基线四元组**：`source-revision=0d42809`（**树净，非 `-dirty`**）+ Surefire **报告 344 / 用例 2133 / F0 / E0 / S0** + Failsafe **报告 46 / 用例 147 / F0 / E0 / S15** + `CI=#183`（Run 40096191583）。上一卡 343/2122/0 + 46/147/15 → 344/2133/0 + 46/147/15：surefire **+1 报告 +11 测** = 新守卫 `NearDupFloorSignoffGuardTest`（7 测）+ `ImageSimilarityThresholdGateTest` 新增 4 条（E1/E2/E3/E5）；failsafe **四值全持平**（本卡零新 IT）
- **本卡规模**：**25 文件 / +868 −87**；merge 树 == 分支 tip 树 == **`ff73f1e08c944ee468ab47209fda480f3bc470ac`**。树等价只免「合并前再验」，**不免基线重锚**
- **核心变更（一条常量 + 一条冻结）**：`NEAR_DUPLICATE_FLOOR` **0.9887 → 0.982**（Owner 2026-10-24 签收，依据签收页 `0.982` 行：`R 0.583333→0.666667`、`S_same 0.02→0.03`、`ΔR/ΔS_same=8.333333` 全表最高效、只多压 1 张同文档图）；新增 `BAND_REFERENCE_FLOOR = 0.9887` **冻结**三档内边界基准
- **本次真正的架构判断（不是改数字）**：三档内边界原本由 `effectiveFloor` 现算（`floor+Δ/3`、`floor+2Δ/3`）。只改常量会把 Δ 由 0.0057 拉到 0.0124、内边界滑到 `0.986133/0.990267`，**已在带内的候选被无声改档且降权变轻**（`cos=0.9910` 由 `×0.6` 变 `×0.75`）——那是签收范围外的、没人量过的降权放松，且破坏「越远离阈值 ⇒ 降权越重」的单调语义。因此拆两常量：**准入面下探、计算基准不动**。落地后两内边界**逐字仍 `0.9906 / 0.9925`**，变更对 `[0.9887, 0.9944)` **纯加性**，新准入区 `[0.982, 0.9887)` 全落 `NEAR_LOW×0.5`；**`bandOf` 方法体恰好零改动**（判空下沿仍 `effectiveFloor`，两内边界换 `bandReferenceFloor`）
- **证据锚零改动（本卡验收硬线）**：六份主锚 SHA-256 逐份等于改前（`82d24204…` / `43c54ba6…` / `62e43dfb…` / `d84838c2…` / `5444719a…` / `3adde4ec…319b`）；`docs/rag-quality/` 只多 `near-dup-floor-signoff-v1.{json,md}` + 签收页 §6 单 hunk。**语义：锚记录的是「测于 0.9887」的历史事实，不是生产现值；生产值改了就改锚 = 用新结论覆盖旧证据**
- **复核 → 审计**：A 档复核 **REJECT**（4 处注释仍把 0.9887 说成「生产现值/生产下限」）→ fix1 `0b36066`（仅注释 + 卡面，12+/6−）→ 主 Agent 审计 **PASS**。**责任划分记在 §4 第 18/3/8 条**：4 处里 3 处在写集之外（我的卡面任务与写集自相矛盾）、1 处属执行端漏网；另有一项复核方越界（`active-handoff.md` 不在写集）；`tasks.md` §0 第 6 行 63 位 SHA 是**我的转录笔误**（执行端发现并如实披露、未擅改基线表，处置正确）
- **本卡最重要的一句话**：档位下探执行完毕，但**「降权窗口是活的」这条证据仍然有效**（真实共现最小 gap 0.0094 ≪ `W_max=0.15`）——所以这次**没有**顺手把三等分一起缩放，新增降权面被压到最小（只多压 1 张同文档图）
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复图像：档位已落地 + 五份证据**：生产 `[NEAR_DUPLICATE_FLOOR=**0.982**, threshold=0.9944)` 三档降权 `×0.75/×0.6/×0.5`，**内边界以 `BAND_REFERENCE_FLOOR=0.9887` 为冻结基准**（`0.982 | 0.9906 | 0.9925 | 0.9944`）；口径一 **pair 价目表**（`near-dup-exchange-rate-v1`）、口径二 **融合上界**（`near-dup-fusion-impact-v1`，`INVISIBLE-IN-ROUTE-ONLY`）、口径三 **检索级真值**（`image-near-dup-truth-v1`）、口径四 **位移实测**（`near-dup-displacement-evidence-v1`，`POSSIBLY-DISPLACED`）+ **代理有效性**（`image-variant-cross-matrix-v1`，`PROXY-UNDERSTATES`）——**六份锚全部「测于 0.9887」**；签收落成 `near-dup-floor-signoff-v1`
4. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
5. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh`（GO/NO-GO + 原因码），与观测面、回滚、热生效实证成套
6. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + **准入下限 0.982** + **带内基准 0.9887（冻结，故意与准入下限不等）** + **重排后、融合前**降权）
7. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述

---

## 3. 当前在途与候选队列（near-dup-floor-lowering 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **观测真实位移后再议 0.90（Owner 决）**：本卡只走到 0.982。`0.90` 是 `R=94.17%` 的膝盖点，但多压 **6 张**同文档图（降权面翻 4 倍），必须在真实位移实测之后才谈；再动 `NEAR_DUPLICATE_FLOOR` **必须新卡**
  2. **近重复图入库的真实共现实验（成本最高，口径四的直接后续）**：把确定性变体真正入库（每图需 VLM 描述），让 NEAR_DUPLICATE 候选与文本竞争者在**同一 top-k** 竞争，把「反事实重建 68.5%」变成实测；**先出成本估算，不实跑**
  3. **文本路真分数分布**：口径四只给 7 条查询的**存在性证据**，不是分布；要分布须先验 72 例基准套件的检索端口是否为生产路（`SparseBenchmarkRecallService` 的存在使其可疑，未验）
  4. **用户意图级相关性标注（唯一能消掉 A1/A2 的路）**：须真实查询 + 人标；**严禁**用 agent 生成的标签充数
  5. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`
  6. **前端小表随站点增长维护**：`uiEndpointPermissions.ts` 手写 57 项，编译期受契约约束
  7. 文档类存量：全域权限清单（`docs/permission-matrix-audit.md` 属**历史快照，遵裁决不改写**）；在线考试子域 `work/_online-exam-backlog.md`；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：储备 1（观测后拍板 0.90）或 2（真实共现实验，先出成本估算）

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件；执行与复核子 Agent **一律 Owner 亲派**；**任务要求与写集必须对齐**——凡写「全仓 grep 后逐处处置」，写集必须覆盖可能的落点，或明写「需改但不在写集时停下要求扩写集」（本卡任务 15 与写集矛盾，导致 3 处失真注释无人可改）
4. **复核强度分档**（`docs/review-intensity-policy.md`）：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/**契约**）独立复核四判据
5. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等**（相等只免「合并前再验」，**不免基线重锚**）→ **干净树上 `mvn clean verify` + `--update` 重锚** → push → CI **run 级 conclusion** 全绿 → M2 归档
6. **「跑不了」结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接"接受"，须由 CI 对应 job 定案
7. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；**动手改造前先查契约/结果对象是否已带所需字段**；生成物必须由默认跑的校验测试守住
8. **证据纪律**：补勾依据每个数字必须能独立复算；基线 `source-revision` 必须无 `-dirty`（且**不可能**等于自身提交 sha，正确语义是「= 被测量的那个干净 commit」）；**预注册门槛必须锋利**；**「确认无一处…」这类全称断言必须附可复算的分类清单**，否则就是声明失真（本卡唯一真缺陷：写「无一处仍是生产现值」而实测有 4 处）
9. **数据质量先于结论**：「不可能事件」先怀疑生成管线，再谈统计
10. **量纲白名单**：任何"单调性/分布"结论必须写明**量纲 + 口径 + 来源表**
11. **判据即合同**：判据两支可重叠、或出现"或反之"这类本方向无意义的词，当场消歧并附可执行断言；事后消歧只能朝**更保守**方向
12. **行尾即判据**：会做**逐字节比对**的锚/生成物，序列化必须钉死 LF（Jackson 默认缩进器用 `System.lineSeparator()`）
13. **复核 PASS ≠ 可交付**：`clean verify` 必须在**重新物化后的合并树**上跑（checkout 会重写文件）
14. **环境陷阱**：中文 UTF-8 文件逐行计数用 `read` 工具；含中文的 `sed/grep` pattern 进命令行易 127，改 ASCII；bash 一律走 `D:\git\Git\bin\bash.exe -lc`；**`Set-Content -Encoding utf8` 在 Windows PowerShell 会写 BOM**（提交信息用 `-m ...`）；**GitHub 按 `needs` 完成后才创建下游 job**，`jobs` API 的 `total_count` 会从 3 涨到 6，闭包以 run 级 `conclusion` 为准
15. **锚字段比较容差须与断言同容差**：锚里记录「是否一致」的布尔若用 `==` 而门禁断言用 `1e-9`，会出现「锚记 mismatch 但测试绿」
16. **锚条目须可独立解读**：凡会被交叉引用的数组字段，每条必须自带判读所需的布尔/范围标志
17. **上界须注明被放缩的量及其值域**：写 `0.3×(1−w)` 这类上界，必须写清被放缩的是**哪个量**、为什么 ≤ 它的极值（本卡的量是图像路 rerank 后分，不是余弦）
18. **恒定值一改，先问「谁跟着它算」（本卡新增）**：改一个常量前，grep 出所有**由它推导**的公式与边界；把「业务准入面」与「计算基准」拆成两个常量，让变更**纯加性**（本卡若顺手缩放三等分，会让 `cos=0.9910` 的降权从 ×0.6 静默变 ×0.75 ⇒ 已带内候选被无声改档）。推论：**证据侧一律冻结字面量，禁止引用生产常量**，并把「故意的不相等」写成显式断言防后来者「顺手对齐」

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §162**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/near-dup-floor-lowering/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **近重复证据五件套 + 签收锚**：`near-dup-exchange-rate-v1`（价目表）／`near-dup-fusion-impact-v1`（融合上界，写入口 `-Dnear.dup.fusion.write=true`）／`image-near-dup-truth-v1`（检索级真值，`-Dnear.dup.truth.write=true`）／`near-dup-displacement-evidence-v1`（位移实测，`-Dnear.dup.displacement.write=true`）／`image-variant-cross-matrix-v1`（代理有效性，`-Dimage.cross.matrix.write=true`）＋ **`near-dup-floor-signoff-v1`（签收决议锚，守卫 `NearDupFloorSignoffGuardTest` 实算六锚 SHA）**
- **真跑 opt-in**：`RAG_BENCHMARK_REAL=1`（基准）/ `RAG_VISION_EMBED_REAL=1`（视觉）/ `RAG_NEAR_DUP_DISPLACEMENT_REAL=1`（位移）/ `RAG_IMAGE_CROSS_MATRIX_REAL=1`（矩阵）；**跳过 ≠ 通过**
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json`；计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
