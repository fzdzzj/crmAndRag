# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-25（image-slice-ingest-chain-reconciliation：图像切片**入链完整性对账**落地——七链被证明 1:1，五种静默丢弃语义被钉成断言，卡 26 的 5 个缺口改判 `INCONCLUSIVE`）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`977320a`（M1 合并）+ `deac5ce`（基线重锚）+ 本归档笔** = origin/master。分支 `feature/eval-port-route-reconciliation` **永久保留**（基点 `d812ed1` → **2 笔**：`0ffcd2c` 首轮 / `fe5c76a` fix1 代笔）
- **测试基线四元组**：`source-revision=977320a`（**树净，非 `-dirty`**）+ Surefire **报告 351 / 用例 2167 / F0 / E0 / S0** + Failsafe **报告 47 / 用例 149 / F0 / E0 / S16** + **CI = Run 40186225264（6/6 jobs 绿）**。上一卡 349/2159/0 + 47/149/16 → 本卡 surefire **+2 报告 +8 用例**（对账 3 + 守卫 5），failsafe **三值持平**
- **本卡规模**：对 `d812ed1` **7 文件 / +1766 −0**（全新增）；merge 树 == 分支 tip 树，树净
- **本卡结论（一句话）**：**72 例套件的分数不能当「文本路真分数分布」用。** `verdict=PARTIAL-OFF-PRODUCTION`，三条非生产腿：输入腿 `STUB`（`DeterministicEmbedding` 伪向量，`RagQualityRegressionTest:278-283`）、稀疏腿 `APPROXIMATION`（字符 bigram 顶替 `FULLTEXT(ngram)`，无授权 JOIN/软删，`SparseBenchmarkRecallService:33-63`）、度量腿 `SET-RELATIVE`（`DefaultWeightedReranker:41-42` 对每查询候选集做 min-max）；**装配腿是真生产类**（`RagBenchmarkPipelineFactory:53-75`）
- **平移不变性（本卡命门）**：同候选集跑三遍，向量分整体 `+100` 最大绝对差 **2.83e-14**、整体 `×2` 差 **0.0**；单点抬高对照名次 **1→0** ⇒ 测的是归一化、不是恒等函数。结论 `SET-RELATIVE-INVARIANT`：分数是集合内相对量，跨查询/跨语料比较无意义
- **对储备 3 的处置（Owner 决）**：**「从 72 例套件取分布」这条路已被关掉**；可行口径只剩 ① 同一查询内候选间**分差**的分布，② 生产库真跑（需库 + 外呼，另卡）
- **责任划分（不粉饰）**：复核 REJECT 判得对——唯一阻断项是锚 MD 尾部空行（`git diff --check` 报 `new blank line at EOF`），由 `buildAnchorMd` 末尾空串元素烤进提交；fix1 我代笔：生成器级删尾 + `assertFalse(md.endsWith("\n\n"))` 防回归 + 订正任务卡「锚内声明」措辞（该声明只在任务卡，锚 JSON 无此字段）
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复图像：档位 + 五份证据 + 两张入库面锚**：生产 `[0.982, 0.9944)` 三档降权 `×0.75/×0.6/×0.5`，内边界以 `BAND_REFERENCE_FLOOR=0.9887` 冻结（`0.982 | 0.9906 | 0.9925 | 0.9944`）；五件套**全部「测于 0.9887」**；签收 `near-dup-floor-signoff-v1`；入库面 = S0 闸门探针（6/6 过闸、18 次外呼、冻结投影）+ S1 NOT-RUN 负结论
4. **图像切片摄取链：已由断言守护**（本卡）：七链逐链对账（`image-slice-ingest-chain-v1`）+ 五种静默丢弃语义 + `embedText` 碰撞四因子条件（文件名∧类目∧页码∧描述）；`stageImageVector` 仍**在** `if (description != null)` 分支内（`PdfImageSliceIngestService:248`）⇒ 闸门拒图连带丢图像向量，**这是被测量的现象，不是待修 bug**
5. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
6. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh`（GO/NO-GO + 原因码），与观测面、回滚、热生效实证成套
7. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + 准入下限 0.982 + 带内基准 0.9887 冻结 + 重排后、融合前降权）
8. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述
9. **近重复消融：S0 零外呼 A/B 已实测**（卡 28）：正对照证明**装置有鉴别力**，冻结真实语料 7 查询 A/B 得 `verdict=NO-RANK-CHANGE`（分数位移 0.06、名次与集合零变化、gap ≫ 窗口）；锚 `near-dup-ablation-v1`（写模式 `-Dnear.dup.ablation.write=true`，默认逐字节复跑、零外呼、CI 真跑、failsafe 用例数 +1）；S1（非退化近重复语料，≤36 次外呼）**仅预注册未执行**
10. **评测端口口径已对账**（卡 29）：72 例套件 `verdict=PARTIAL-OFF-PRODUCTION`——装配腿生产、输入腿伪向量、稀疏腿 bigram 近似、度量腿 min-max 集合内归一；平移不变性实测（`+100` 差 `2.83e-14`、`×2` 差 `0.0`、单点对照名次 `1→0`）⇒ **其分数不得当「文本路真分数分布」用**；锚 `eval-port-route-v1`（写模式 `-Deval.port.route.anchor.write=true`，守卫 `EvalPortRouteGuardTest`）

---

## 3. 当前在途与候选队列（eval-port-route-reconciliation 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **S1 消融执行（本卡预注册，Owner 决）**：非退化近重复语料（原生分辨率重编码 / 轻度 JPEG / 整图微缩，短边仍 ≥64px），入库后**断言实测过闸数 == 预期数**，任何静默丢弃即红；成本算式 `6×3 + 6×2 = 30`，**报批 36 封顶**。触发条件已写进锚：本卡判 `NO-RANK-CHANGE` 且窗口算术显示真实语料**无窗内共现**（gap 0.3856 vs 窗口 0.15）
  2. **观测真实位移后再议 0.90（Owner 决）**：`0.90` 是 `R=94.17%` 膝盖点，但多压 6 张同文档图；再动 `NEAR_DUPLICATE_FLOOR` **必须新卡**
  3. ~~文本路真分数分布口径核验~~ **已由卡 29 结案（关闭）**：72 例套件的分数是集合内相对量（min-max）+ 伪向量 + bigram 近似稀疏腿 ⇒ 不可当分布。要分布只剩 ① 同一查询内**分差**分布 ② 生产库真跑（需库+外呼，另卡），由 Owner 定
  4. **用户意图级相关性标注（唯一能消掉 A1/A2 的路）**：须真实查询 + 人标；**严禁**用 agent 生成标签充数
  5. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`
  6. **前端小表随站点增长维护**：`uiEndpointPermissions.ts` 手写 57 项，编译期受契约约束
  7. 文档类存量：全域权限清单（`docs/permission-matrix-audit.md` 属**历史快照，遵裁决不改写**）；在线考试子域 `work/_online-exam-backlog.md`；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：RAG 质量线的**零外呼题已基本清完**——要么花 30 次外呼做储备 1（S1 消融，须 Owner 单独授权），要么转别的域（储备 6 前端小表 / 储备 7 文档存量）。**不要**为了「有产出」再开一张只读代码就能自答的取证卡

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**（证据锚的推导源码属例外，见第 21 条）
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件；执行与复核子 Agent **一律 Owner 亲派**；**任务要求与写集必须对齐**
4. **复核强度分档**（`docs/review-intensity-policy.md`）：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/**契约**）独立复核四判据
5. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等** → **干净树上 `mvn clean verify` + `--update`（只跑一次）重锚** → push → CI **run 级 `conclusion`** 全绿 → M2 归档
6. **「跑不了」结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接「接受」，须由 CI 对应 job 定案
7. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；**动手改造前先查契约/结果对象是否已带所需字段**；生成物必须由默认跑的校验测试守住
8. **证据纪律**：补勾依据每个数字必须能独立复算；基线 `source-revision` 必须无 `-dirty`；**预注册门槛必须锋利**；**「无一处 / 全部 / 默认一致」这类全称断言必须同回合附可复算清单**
9. **勾选只有三态且语义单一**：`[x]`=完成、`[-]`=按裁定不做、`[ ]`=未做；「不做」不许记 `[x]`；判据作废后须给指向已删文件的完成项补「已内联/已拆除 + 留档 SHA」追记
10. **数据质量先于结论**：「不可能事件」先怀疑生成管线，再谈统计
11. **量纲白名单**：任何「单调性/分布」结论必须写明**量纲 + 口径 + 来源表**
12. **判据即合同**：判据两支可重叠、或出现本方向无意义的词，当场消歧并附可执行断言；事后消歧只能朝**更保守**方向
13. **行尾即判据**：会做**逐字节比对**的锚/生成物，序列化必须钉死 LF（Jackson 默认缩进器走 `System.lineSeparator()`）
14. **复核 PASS ≠ 可交付**：`clean verify` 必须在**重新物化后的合并树**上跑
15. **环境陷阱**：中文 UTF-8 文件逐行计数用 `read`；中文 `sed/grep` pattern 进命令行易 127；bash 走 `D:\git\Git\bin\bash.exe -lc`；`Set-Content -Encoding utf8` 会写 BOM；GitHub 按 `needs` 完成才创建下游 job，闭包以 run 级 `conclusion` 为准；**`Get-Content -Raw` 在 PS5.1 按 GBK 解码 UTF-8 会让整篇乱码且不可逆**（卡 29 实证：改中文文档禁用 PowerShell 管道，只用 `edit` 工具）
16. **锚字段比较容差须与断言同容差**
17. **锚条目须可独立解读**：会被交叉引用的数组字段，每条自带判读所需的布尔/范围标志
18. **上界须注明被放缩的量及其值域**
19. **恒定值一改，先问「谁跟着它算」**：grep 出所有由它推导的公式与边界；把「准入面」与「计算基准」拆成两个常量让变更**纯加性**；证据侧一律冻结字面量
20. **跨树跑命令，先问它落在哪棵树**：忘带 `workdir` 会落到观察树 detached 旧提交，「命令不可复现」全是自己造的假象；跨树命令必须显式 `workdir`，复核结论前先 `git rev-parse --short HEAD` 自证落点
21. **判据之间要有适用性闭环（卡 27 新增）**：判据 A 判「机制能否复现」，判据 B 判「现象能否归因」，二者**不是同一件事**；凡在 B 里引用 A 的机制，必须用**本卡自己产出的前提条件**回头自检一次（卡 27 `r4` 的条件当场否证了 `r5` 的归因）。另一条同源：**改「由测量值机械推导写入」的证据锚的结论，必须改推导源，不能改成品**——只改成品红的是复算测试，极易被误判成「锚被人手改坏」
22. **判定标签不得对输入恒真（卡 28 新增）**：verdict / 结论字段必须能取到相反值——把「分数必然变」与「顺序可能变」并进同一个标签，标签就退化成常真式，下游只会读到假位移。派生结论**只由判定面推导**，数据面单列字段（卡 28 `scoreFace`）；写判据时先自问「什么输入会让它取反」，答不出就说明它不是判据
23. **引用要指到能 grep 的实体（卡 29 新增）**：凡写「锚内声明」「已登记」「见文档」这类引用，必须能在被指对象里 grep 到该字段/该行——卡 29 任务卡声称的「锚内声明」在锚 JSON 里根本不存在（复核探针 `decl-independentImpl=false` 当场证伪）。同源一条：**生成物类门禁（如 `git diff --check` 的尾部空行）必须由生成器自身断言守住**，事后检查只能抓一次，防不住回归

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**；最近两卡零迁移）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §179**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/eval-port-route-reconciliation/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **近重复图像六份锚**：`near-dup-exchange-rate-v1`（价目表）／`near-dup-fusion-impact-v1`（融合上界，`-Dnear.dup.fusion.write=true`）／`image-near-dup-truth-v1`（检索级真值，`-Dnear.dup.truth.write=true`）／`near-dup-displacement-evidence-v1`（位移实测，`-Dnear.dup.displacement.write=true`）／`image-variant-cross-matrix-v1`（代理有效性，`-Dimage.cross.matrix.write=true`）／`near-dup-floor-signoff-v1`（签收决议，守卫实算六锚 SHA）
- **入库面三锚**：`near-dup-ingest-gate-probe-v1`（S0 闸门探针，冻结投影）／`near-dup-ingest-s1-not-run-v1`（S1 NOT-RUN 预注册，守卫断言 S1 锚**必须不存在**）／`image-slice-ingest-chain-v1`（七链对账 + 五种丢弃语义 + 碰撞四因子，写模式 `-Dimage.slice.chain.anchor.write=true`）
- **消融锚（本卡）**：`near-dup-ablation-v1`（S0 A/B，写模式 `-Dnear.dup.ablation.write=true`，默认逐字节复跑 + 零外呼，守卫 `NearDupAblationGuardTest`，结论位 `verdict` 只由名次面+集合面推导、分数面单列 `scoreFace`）
- **真跑 opt-in**：`RAG_BENCHMARK_REAL=1` / `RAG_VISION_EMBED_REAL=1` / `RAG_NEAR_DUP_DISPLACEMENT_REAL=1` / `RAG_IMAGE_CROSS_MATRIX_REAL=1` / `RAG_NEAR_DUP_INGEST_REAL=1`；**跳过 ≠ 通过**
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json`；计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
