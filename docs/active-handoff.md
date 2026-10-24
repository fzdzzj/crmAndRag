# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-24（near-dup-missing-evidence 三组卡：真实共现实测 + 变体↔第三图全矩阵 + 证据收口，**A 档**）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`d3a321f`（M1 合并）+ `e2e625c`（基线重锚）+ 本归档笔**，= origin/master。分支 `feature/near-dup-missing-evidence` **永久保留**（基点 `5519c43` → **3 笔**：`290a64b` 实现 / `ec7f388` 行尾稳定 / `58589da` 基线）
- **测试基线四元组**：`source-revision=d3a321f`（**树净，非 `-dirty`**）+ Surefire **报告 343 / 用例 2122 / F0 / E0 / S0** + Failsafe **报告 46 / 用例 147 / F0 / E0 / S15** + `CI=#181`（Run 40077285253，**6/6 全绿**）。上一卡 341/2109/0 + 44/145/13 → 343/2122/0 + 46/147/15，**可逐项对账**：surefire +2 报告 +13 测 = 两个新守卫（4+8）+ 签收页守卫新增 1 条；failsafe +2 报告 +2 测**皆 skip** = 两个新 opt-in IT 在无 opt-in 时不跑
- **本卡规模**：**14 文件 / +4831 −12**；merge 树 == 分支 tip 树 == **`a19633ce18cb52c6ac03899b59cb8d1044066d52`**。树等价只免「合并前再验」，**不免基线重锚**
- **三组结论（按组判定，互不连坐）**：
  - **G1 真实共现 → `POSSIBLY-DISPLACED`**：同一生产 `KnowledgeRetrievalServiceImpl` 同时装文本集合与图片集合，每条查询**一次** `retrieve(text, imageVector)`（禁止两次独立调用拼凑），读 `sources()` 的 `sourceType`+`relevanceScore`。7 条真实查询（3 份真实 PDF 语料）：**共现 6/7**、`minDownwardGap = 0.00941087658500056`、`W_max = 0.30×(1−0.5)×1.0 = 0.15` ⇒ 4 条共现查询的图像候选与下方竞争者分差 **全部落在降权窗口内**（`0.0094 / 0.0098 / 0.0319 / 0.0457`），最小者比窗口小一个数量级。`externalCalls=77`（vision 9 / embedImage 14 / embed 54，真调用点累加）
  - **G1 反事实重建（单独字段 + `assumption`，不作因果）**：若该图真是带内近重复，**1479/2160 = 68.5%** 的 `(cos, w)` 组合会把它压到实测竞争者之下（`0.75:365 / 0.6:502 / 0.5:612`）
  - **G2 代理有效性 → `PROXY-UNDERSTATES`**：144 图**单编码**（`externalCalls=144`=图数）建 144×144 矩阵；与 v2 定标锚 396 对（120+100+176）逐对比较 **偏差全 0**（单编码与定标卡 A 向量逐位一致 ⇒ `multimodal-embedding-v1` 对同字节输入**确定**、模型未漂移）；变体↔第三图 2760 值 `p50=0.2216 / p90=0.5024 / p99=0.9809 / max=0.9973`，**带内（≥0.9887）14 对**（全部来自 NIST 两封面簇 + DOE p4-b7/b8 连拍）⇒ **上一卡 base 对代理低估带内竞争者**，R4 进入带竞争者观测应从 2 条至少扩到 16 条
  - **G3 证据收口**：签收页**只改 §4**（4 删 4 增，§1–§3/§5 逐字未动，`git diff` 单 hunk 自证）；守卫 `NearDupFloorDecisionRequestGuardTest` 锚引用 **4→6（收紧）** + 新增「证据锚 `externalCalls>0` 否则即假绿」断言，**无一处放松**
- **本卡复核（A 档，Owner 亲派）= PASS**（四判据全过；复核方独立复算 396 对偏差、2760 值分位、带内 14 对逐位吻合，并自纠两处脚本笔误）。**主 Agent 审计 PASS + 3 条非阻断观察项**：① `withinWindow` 含 1 条非共现条目（queryIndex 1，IMAGE↔IMAGE）且锚条目未带 `cooccurred` 标志（md 已显式标注、签收页「4 条共现」正确）② `W_max=0.15` 的依据是「图像路 rerank 后分 `s_pre ≤ 1`」而非 `cos`（实测反推 `s_pre≈0.603`，按此收紧窗口为 0.0904 时 4 条 gap 仍在窗内，**verdict 不变**）③ 签收页 §3「cross-class 最高 0.988736」只对 base 对代理语料成立（变体↔跨文档实测最高 0.9973），但「跨档不变」仍成立（`0.9973 ≥ 0.9944` ⇒ 恒判 `SAME_IMAGE`，任何档都不过滤不降权）
- **本卡最重要的一句话**：上一卡只能给「跨路可见性缺文本路分数，无法定案」；现在定案了，方向是**它可能真的发生**——降权在真实语料上**已经站到悬崖边上**（最小分差 0.0094 vs 窗口 0.15），而且上一卡的代理是**低估**的。`INVISIBLE-IN-ROUTE-ONLY` 的「路由内不可见」仍然成立，但「无害」这个读法**不再有数据支持**
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复图像已有四份证据（生产常量仍零改动）**：`[NEAR_DUPLICATE_FLOOR=0.9887, threshold=0.9944)` 三档降权 `×0.75/×0.6/×0.5`；口径一 **pair 价目表**（`near-dup-exchange-rate-v1`，`EXCHANGE-RATE`）、口径二 **融合上界**（`near-dup-fusion-impact-v1`，`INVISIBLE-IN-ROUTE-ONLY`）、口径三 **检索级真值**（`image-near-dup-truth-v1`）、口径四 **位移实测**（`near-dup-displacement-evidence-v1`，`POSSIBLY-DISPLACED`）+ **代理有效性**（`image-variant-cross-matrix-v1`，`PROXY-UNDERSTATES`）；汇总签收页 `near-dup-floor-decision-request-v1.md`（§4 已更新到 2026-10-24）。**档位仍待 Owner 拍板**
4. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
5. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh`（GO/NO-GO + 原因码），与观测面、回滚、热生效实证成套
6. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + 下限 0.9887 + **重排后、融合前**降权）
7. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述

---

## 3. 当前在途与候选队列（near-dup-missing-evidence 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **拍板近重复档位（Owner 决，非代码）**：签收页三口径 + §4 已补齐——`0.982` 最高效（+8.33pp 换 1 张同文档图）、`0.98`（+9.17pp / 2 张）、`0.90`（`R=94.17%` / 6 张）；**免费档不存在**；新增两条关键事实：**位移在真实语料上已逼近发生**（4 条共现查询 gap 全落窗，最小 0.0094）、**带内竞争者被 base 对代理低估 14 对**。动 `NEAR_DUPLICATE_FLOOR` **必须新卡**
  2. **近重复图入库的真实共现实验（成本最高，本卡的直接后续）**：把确定性变体真正入库（每图需 VLM 描述），让 NEAR_DUPLICATE 候选与文本竞争者在**同一 top-k** 里竞争，把「反事实重建 68.5%」变成实测；须先估 VLM 调用成本
  3. **文本路真分数分布**：本卡只给 7 条查询的**存在性证据**，不是分布；要分布须先验 72 例基准套件的检索端口是否为生产路（`SparseBenchmarkRecallService` 的存在使其可疑，未验）
  4. **用户意图级相关性标注（唯一能消掉 A1/A2 的路）**：须真实查询 + 人标；**严禁**用 agent 生成的标签充数
  5. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`
  6. **前端小表随站点增长维护**：`uiEndpointPermissions.ts` 手写 57 项，编译期受契约约束
  7. 文档类存量：全域权限清单（`docs/permission-matrix-audit.md` 属**历史快照，遵裁决不改写**）；在线考试子域 `work/_online-exam-backlog.md`；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：储备 1（拍板后另立卡改档）或 2（真实共现实验，先出成本估算不实跑）

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件；执行与复核子 Agent **一律 Owner 亲派**
4. **复核强度分档**（`docs/review-intensity-policy.md`）：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/**契约**）独立复核四判据
5. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等**（相等只免「合并前再验」，**不免基线重锚**）→ **干净树上 `mvn clean verify` + `--update` 重锚** → push → CI **run 级 conclusion** 全绿 → M2 归档
6. **「跑不了」结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接"接受"，须由 CI 对应 job 定案
7. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；**动手改造前先查契约/结果对象是否已带所需字段**（本卡 `SourceReference` 早就有 `sourceType`+`relevanceScore`，上一卡却把同一缺口判成「需造近重复库」）；生成物必须由默认跑的校验测试守住
8. **证据纪律**：补勾依据每个数字必须能独立复算；基线 `source-revision` 必须无 `-dirty`（且**不可能**等于自身提交 sha，正确语义是「= 被测量的那个干净 commit」）；**预注册门槛必须锋利**
9. **数据质量先于结论**：「不可能事件」先怀疑生成管线，再谈统计
10. **量纲白名单**：任何"单调性/分布"结论必须写明**量纲 + 口径 + 来源表**
11. **判据即合同**：判据两支可重叠、或出现"或反之"这类本方向无意义的词，当场消歧并附可执行断言；事后消歧只能朝**更保守**方向
12. **行尾即判据**：会做**逐字节比对**的锚/生成物，序列化必须钉死 LF（Jackson 默认缩进器用 `System.lineSeparator()`）；本卡执行端又踩一次，靠 `ec7f388` 修
13. **复核 PASS ≠ 可交付**：`clean verify` 必须在**重新物化后的合并树**上跑（checkout 会重写文件）
14. **环境陷阱**：中文 UTF-8 文件逐行计数用 `read` 工具；含中文的 `sed/grep` pattern 进命令行易 127，改 ASCII；bash 一律走 `D:\git\Git\bin\bash.exe -lc`；**`Set-Content -Encoding utf8` 在 Windows PowerShell 会写 BOM**（提交信息用 `-m ...`）；**GitHub 按 `needs` 完成后才创建下游 job**，`jobs` API 的 `total_count` 会从 3 涨到 6，只看「当前全绿」会漏判，闭包以 run 级 `conclusion` 为准
15. **锚字段比较容差须与断言同容差**：锚里记录「是否一致」的布尔若用 `==` 而门禁断言用 `1e-9`，会出现「锚记 mismatch 但测试绿」
16. **锚条目须可独立解读（本卡新增）**：凡会被交叉引用的数组字段，每条必须自带判读所需的布尔/范围标志（本卡 `withinWindow` 未带 `cooccurred`，读者须 join `queries` 才知道哪条是跨路共现）
17. **上界须注明被放缩的量及其值域（本卡新增）**：写 `0.3×(1−w)` 这类上界，必须写清被放缩的是**哪个量**、为什么 ≤ 它的极值（本卡的量是图像路 rerank 后分，不是余弦；写成 `cos` 会让推理链看似成立实则换了对象）

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §159**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/near-dup-missing-evidence/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **近重复证据四件套**：`near-dup-exchange-rate-v1`（价目表）／`near-dup-fusion-impact-v1`（融合上界，写入口 `-Dnear.dup.fusion.write=true`）／`image-near-dup-truth-v1`（检索级真值，`-Dnear.dup.truth.write=true`）／`near-dup-displacement-evidence-v1`（位移实测，`-Dnear.dup.displacement.write=true`）+ `image-variant-cross-matrix-v1`（代理有效性，`-Dimage.cross.matrix.write=true`）
- **真跑 opt-in**：`RAG_BENCHMARK_REAL=1`（基准）/ `RAG_VISION_EMBED_REAL=1`（视觉）/ `RAG_NEAR_DUP_DISPLACEMENT_REAL=1`（位移）/ `RAG_IMAGE_CROSS_MATRIX_REAL=1`（矩阵）；**跳过 ≠ 通过**
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json`；计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
