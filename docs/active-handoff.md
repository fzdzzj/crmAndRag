# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-25（near-dup-ingest-cooccurrence：**收窄为 S0-only 收卡**——S1 消融未授权、装置拆除、负结论入库）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`56e215a`（M1 合并）+ `00cf2b4`（基线重锚）+ 本归档笔** = origin/master。分支 `feature/near-dup-ingest-cooccurrence` **永久保留**（基点 `cdf3382` → **3 笔**：`d7abc37` 首轮 / `ade8189` fix1 / `6746140` fix1b 代笔）
- **测试基线四元组**：`source-revision=56e215a`（**树净，非 `-dirty`**）+ Surefire **报告 345 / 用例 2137 / F0 / E0 / S0** + Failsafe **报告 47 / 用例 148 / F0 / E0 / S16** + **CI = Run 40121260478（6/6 jobs 绿）**。上一卡 344/2133/0 + 46/147/15 → 本卡 surefire **+1 报告 +4 测**（探针守卫 +1 新增、S1 守卫 −3、首轮 +6 的净额），failsafe **+1 报告 +1 测 +1 skip**（`NearDupIngestGateProbeIT`，`RAG_NEAR_DUP_INGEST_REAL` opt-in 的设计性 skip——**skip 上限抬格必须在基线笔点名换来的是什么**）
- **本卡规模**：对 `cdf3382` **8 文件 / +2229 −0**（全新增、零删除）；merge 树 == 分支 tip 树 == **`060ac617a183d7899cec7e7669797efd5e2d12ba`**
- **本卡实际产出（一句话定性）**：把「近重复图入库的真实共现实验」推进到**闸门探针**就停住，并把停住的理由变成三条**可独立复认证**的事实——**这是交付，不是失败交付**
- **S0 已交付（18 次外呼，实测非估算）**：6 图（3 base + 3 最差变体 crop-04/scale-070/crop-12）走真实 ingest，**过闸 6/6**；锚 `near-dup-ingest-gate-probe-v1.json` 逐图落 VLM token（合计 **2886**）、`gatePassRate=1.0`、`externalCalls=18=3×6`；**默认参数零覆盖复跑逐字节一致**（fix1 后成立）
- **S1 未跑（Owner 裁定 A′）**：首轮 **124 次外呼**后红于对账恒等式；重跑需 ~120 次必破 **162 累计帽**（已用 153）。装置 1859+229+103 行拆除，骨架留 `d7abc37`；负结论预注册 `near-dup-ingest-s1-not-run-v1.json`（`s1Status=NOT-RUN`，守卫断言 **S1 锚必须不存在**）
- **三条残留事实（本卡真正的结论）**：**F1** 48 图选集恰 1 张被生产提取器丢弃（`doe-p1-b2@scale-070` = 218×58，短边 58 < 64px 下限）⇒ 实提取 47；**F2** 47 图里 ≥6 张过不了描述质量闸门（page=7..12 连续）；**F3** 过闸 41 图、文本 embed 实计 **36** ⇒ 5 张图的描述文本未走到 embed 调用点，**成因未定位**（候选：每文档图数上限 / KB allowlist / 去重键）。**F3 打掉「入一图 = 文本 chunk + 图像向量各一」这条地基**，故消融不在此状态下跑
- **架构事实（D3 明写本卡禁改）**：`PdfImageSliceIngestService.stageImageVector`（`:253`）**位于 `if (description != null)` 分支内**（`:248`）⇒ **闸门拒图会连带丢掉图像向量**。这是**被测量的现象**，不是待修 bug；解耦属另一张卡
- **NF-1（本卡最贵的教训）**：S0 锚原把 `constants.visionModel` 落成**运行时活值**（`qwen3-vl-plus`），而 committed 默认是 `qwen-vl-plus` ⇒ 默认参数 replay **必然不等**、锚不可复认证；首次交付还把带**未记录 `-D` 属性**的 replay2 记成「默认复跑一致」。fix1 改为**冻结投影**：`constants` 只落编译期常量，活值移入顶层 `runtime` 块（`excludedFromReplay: ["visionModel"]` 白名单 + 守卫锁死 + 活值≠常量时 `overrideReason` 必填）
- **责任划分（不粉饰）**：S1 失败的三重根因是**我的卡面规格缺陷**（R4「带内缺一个即 FAIL」与已实证的 64px 必丢互相矛盾；计数恒等式没跑通就写成断言；tripwire 在预算挡住时不可满足）；执行端唯一实质缺陷是 NF-1 那句全称措辞
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪，已 detach 同步 `00cf2b4`）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复图像：档位 + 五份证据 + 一张「实验停在哪」的负结论**：生产 `[0.982, 0.9944)` 三档降权 `×0.75/×0.6/×0.5`，**内边界以 `BAND_REFERENCE_FLOOR=0.9887` 为冻结基准**（`0.982 | 0.9906 | 0.9925 | 0.9944`）；口径一至四 + 代理有效性五件套**全部「测于 0.9887」**；签收 `near-dup-floor-signoff-v1`。**新增实测面**：真实 ingest 逐图 **3 次外呼/过闸图、1 次/拒图**（vision 恒 1，过闸再加文本 embed + 图像 embed）；**退化变体在真实 48 图集上会被 64px 提取下限与描述闸门成批丢弃**（F1/F2），且过闸图的文本 embed 计数**不保证等于过闸图数**（F3，未定位）
4. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
5. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh`（GO/NO-GO + 原因码），与观测面、回滚、热生效实证成套
6. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + **准入下限 0.982** + **带内基准 0.9887 冻结、故意与准入下限不等** + 重排后、融合前降权）
7. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述

---

## 3. 当前在途与候选队列（near-dup-ingest-cooccurrence 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **图像入库完整性定位（F3 的五项缺口，零外呼）**：为何 41 张过闸图只产生 36 次文本 embed——先查每文档图数上限（`DEFAULT_MAX_IMAGES=5` 与合成 PDF 20/20/8 分片的耦合）、KB allowlist、去重键。**这是下面第 3 条的前置**
  2. **观测真实位移后再议 0.90（Owner 决）**：`0.90` 是 `R=94.17%` 膝盖点，但多压 6 张同文档图；再动 `NEAR_DUPLICATE_FLOOR` **必须新卡**
  3. **消融重设计（原储备②的续，不得沿用旧受控集）**：48 图受控集已被证明行不通（退化变体正是被丢弃的那批）；出路二选一——「只观测真能入库的近重复」或「先解耦 `stageImageVector` 与描述闸门」（后者要新卡且动 `src/main`）
  4. **文本路真分数分布**：口径四只给 7 条查询的**存在性证据**；要分布须先验 72 例基准套件的检索端口是否生产路（`SparseBenchmarkRecallService` 的存在使其可疑，未验）
  5. **用户意图级相关性标注（唯一能消掉 A1/A2 的路）**：须真实查询 + 人标；**严禁**用 agent 生成标签充数
  6. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`
  7. **前端小表随站点增长维护**：`uiEndpointPermissions.ts` 手写 57 项，编译期受契约约束
  8. 文档类存量：全域权限清单（`docs/permission-matrix-audit.md` 属**历史快照，遵裁决不改写**）；在线考试子域 `work/_online-exam-backlog.md`；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：储备 1（零外呼、零迁移、且它是储备 3 的前置；不做它去重设计消融等于在未知残缺上测量）

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件；执行与复核子 Agent **一律 Owner 亲派**；**任务要求与写集必须对齐**——凡写「全仓 grep 后逐处处置」，写集必须覆盖可能的落点，或明写「需改但不在写集时停下要求扩写集」
4. **复核强度分档**（`docs/review-intensity-policy.md`）：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/**契约**）独立复核四判据
5. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等**（相等只免「合并前再验」，**不免基线重锚**）→ **干净树上 `mvn clean verify` + `--update`（只跑一次）重锚** → push → CI **run 级 `conclusion`** 全绿 → M2 归档
6. **「跑不了」结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接「接受」，须由 CI 对应 job 定案
7. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；**动手改造前先查契约/结果对象是否已带所需字段**；生成物必须由默认跑的校验测试守住
8. **证据纪律**：补勾依据每个数字必须能独立复算；基线 `source-revision` 必须无 `-dirty`（正确语义是「= 被测量的那个干净 commit」）；**预注册门槛必须锋利**；**「确认无一处…」「默认复跑一致」这类全称断言必须同回合附可复算清单或命令输出**，否则就是声明失真
9. **勾选只有三态且语义单一（本卡新增）**：`[x]`=完成、`[-]`=按裁定不做、`[ ]`=未做；**「不做」不许用 `[x]`+备注**——否则 `grep -c '^- \[x\]'` 这个机器可读面会撒谎（本卡首例：29 项里 14 项不做却全记 `[x]`）。推论：**判据被裁定作废后，指向已删文件的完成项必须追加「已内联/已拆除 + 留档 SHA」的追记**，否则后人按路径查会看到空
10. **数据质量先于结论**：「不可能事件」先怀疑生成管线，再谈统计
11. **量纲白名单**：任何「单调性/分布」结论必须写明**量纲 + 口径 + 来源表**
12. **判据即合同**：判据两支可重叠、或出现「或反之」这类本方向无意义的词，当场消歧并附可执行断言；事后消歧只能朝**更保守**方向
13. **行尾即判据**：会做**逐字节比对**的锚/生成物，序列化必须钉死 LF（Jackson 默认缩进器用 `System.lineSeparator()`）
14. **复核 PASS ≠ 可交付**：`clean verify` 必须在**重新物化后的合并树**上跑（checkout 会重写文件）
15. **环境陷阱**：中文 UTF-8 文件逐行计数用 `read` 工具；含中文的 `sed/grep` pattern 进命令行易 127，改 ASCII；bash 一律走 `D:\git\Git\bin\bash.exe -lc`；**`Set-Content -Encoding utf8` 在 Windows PowerShell 会写 BOM**（提交信息用 `-m ...`）；**GitHub 按 `needs` 完成后才创建下游 job**，`jobs` API 的 `total_count` 会从 3 涨到 6，闭包以 run 级 `conclusion` 为准
16. **锚字段比较容差须与断言同容差**：锚里记录「是否一致」的布尔若用 `==` 而门禁断言用 `1e-9`，会出现「锚记 mismatch 但测试绿」
17. **锚条目须可独立解读**：凡会被交叉引用的数组字段，每条必须自带判读所需的布尔/范围标志
18. **上界须注明被放缩的量及其值域**：写 `0.3×(1−w)` 这类上界，必须写清被放缩的是**哪个量**、为什么 ≤ 它的极值
19. **恒定值一改，先问「谁跟着它算」**：改一个常量前 grep 出所有**由它推导**的公式与边界，把「业务准入面」与「计算基准」拆成两个常量让变更**纯加性**；推论：**证据侧一律冻结字面量，禁引用生产常量**，并把「故意的不相等」写成显式断言
20. **跨树跑命令，先问它落在哪棵树（本卡新增）**：两棵树的 HEAD 可以差几十个提交、类甚至不存在；`mvn`/`grep`/脚本忘了带 `workdir` 会落在观察树（detached 旧提交），于是「命令不可复现」「类找不到」全是**自己造的假象**——本卡审计差点据此误判执行者造假。**任何跨树命令必须显式 `workdir`，且复核结论前先 `pwd` + `git rev-parse --short HEAD` 自证落点**

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**；本卡零迁移）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §167**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/near-dup-ingest-cooccurrence/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **近重复证据五件套 + 签收锚**：`near-dup-exchange-rate-v1`（价目表）／`near-dup-fusion-impact-v1`（融合上界，写入口 `-Dnear.dup.fusion.write=true`）／`image-near-dup-truth-v1`（检索级真值，`-Dnear.dup.truth.write=true`）／`near-dup-displacement-evidence-v1`（位移实测，`-Dnear.dup.displacement.write=true`）／`image-variant-cross-matrix-v1`（代理有效性，`-Dimage.cross.matrix.write=true`）＋ `near-dup-floor-signoff-v1`（签收决议锚，守卫 `NearDupFloorSignoffGuardTest` 实算六锚 SHA）
- **入库面两锚（本卡）**：`near-dup-ingest-gate-probe-v1`（S0 闸门探针，**冻结投影**：`constants` 只放可复算量、活值在 `runtime`，守卫 `NearDupIngestGateProbeGuardTest`）＋ `near-dup-ingest-s1-not-run-v1`（S1 负结论预注册，守卫断言 S1 锚**必须不存在**）
- **真跑 opt-in**：`RAG_BENCHMARK_REAL=1`（基准）/ `RAG_VISION_EMBED_REAL=1`（视觉）/ `RAG_NEAR_DUP_DISPLACEMENT_REAL=1`（位移）/ `RAG_IMAGE_CROSS_MATRIX_REAL=1`（矩阵）/ **`RAG_NEAR_DUP_INGEST_REAL=1`（闸门探针；其**默认复跑**路径结构上零外呼）**；**跳过 ≠ 通过**
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json`；计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
