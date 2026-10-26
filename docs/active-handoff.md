# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-26（near-dup-conditional-min-criterion：**判据从「不可满足」改成条件式**——三分支 + 第四判定词 `PROVEN-CONDITIONAL`（必须带适用域）；同卡交付 0.90 影响与重锚清单，**执行 0 次重建**）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`91c8b1a`（M1 合并）+ `725e8b9`（基线重锚）+ 本归档笔** = origin/master。分支 `feature/near-dup-conditional-min-criterion` **永久保留**（基点 `7f630a4` → **3 笔**：`bb5889f` 首轮 / `2e09693` fix1 / `4e258d6` fix2）
- **测试基线四元组**：`source-revision=91c8b1a`（**树净，非 `-dirty`**）+ Surefire **报告 362 / 用例 2199 / F0 / E0 / S0** + Failsafe **报告 49 / 用例 151 / F0 / E0 / S18** + **CI = Run 40357285028（6/6 jobs 绿）**。上一卡 360/2197/0 + 49/151/18 → 本卡 surefire **+2 报告 +2 用例**（`NearDupConditionalMinCriterionTest` 1 + Guard 1）、failsafe **三值不变**
- **本卡规模**：对 `7f630a4` **6 文件 / +3818 −0**（2 测试类 + 2 锚 + 卡面 2 件）；merge 树 == 分支 tip 树，树净
- **本卡结论（一句话）**：**判据是可满足的，前提是把它条件化。** 卡 35 卡住的「两分支同时末位」在本语料必然落空（bm25 在图像描述文本上全命中或零命中）；改成三分支后：全 0 支 `r=0 ⇔ normVec=0`、全 1 支 `r ≥ wb`（`r=0` 不可能）、原判据退化为非退化支的**特例**。`verdict=PROVEN-CONDITIONAL` 带 `applicabilityDomain`；`PROVEN`（无条件）档明写「可达但本语料未出现」
- **本卡真正的副产物**：① **重锚清单**（9 张必需 / 6 张不受影响 / 1 张未判定；方式 + 是否花钱 + 依赖序，`executed=false`）② **外呼边界写清**：`221` 只算已判定 9 张，含未判定 1 张为 `239` ③ **0.90 决策输入齐了**：逐锚「是否冻了分类（字段路径 + 样例）」「能否零外呼重算（按是否冻 `cos`/向量）」
- **两处「陈述与源锚不符」（我的账，都记全）**：① 复核报告称签收锚 sha「被多张引用」，实为**零张引用**、是签收锚自己 `evidenceAnchors` 记 6 张——我照抄进 fix1 指令，被执行者实测推翻（它对）② fix1 反过来写成 `signedDigests`（**该字段不存在**）「四张」，实为 `evidenceAnchors` **6 键 / 清单内 4 张** ⇒ fix2 改正并加反向断言（`orderRule` 不得含 `signedDigests`、必须含 `evidenceAnchors`；6 键 / 交集 4 / 4 张 order 更小，三条机器可验）
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）。**坑**：`pnpm -C frontend test` 会重写 `frontend/typed-router.d.ts`（生成物 +32 行），跑完门禁须 `git checkout --` 复原，否则基线 `source-revision` 会挂 `-dirty`（本卡 M1 已再中一次）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复图像：档位 + 五份证据 + 两张入库面锚**：生产 `[0.982, 0.9944)` 三档降权 `×0.75/×0.6/×0.5`，内边界以 `BAND_REFERENCE_FLOOR=0.9887` 冻结（`0.982 | 0.9906 | 0.9925 | 0.9944`）；五件套**全部「测于 0.9887」**；签收 `near-dup-floor-signoff-v1`；入库面 = S0 闸门探针（6/6 过闸、18 次外呼、冻结投影）+ S1 结论（见第 12 条）
4. **图像切片摄取链：已由断言守护**（卡 26/27）：七链逐链对账（`image-slice-ingest-chain-v1`）+ 五种静默丢弃语义 + `embedText` 碰撞四因子条件（文件名∧类目∧页码∧描述）；`stageImageVector` 仍**在** `if (description != null)` 分支内（`PdfImageSliceIngestService:248`）⇒ 闸门拒图连带丢图像向量，**这是被测量的现象，不是待修 bug**
5. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
6. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh`（GO/NO-GO + 原因码），与观测面、回滚、热生效实证成套
7. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + 准入下限 0.982 + 带内基准 0.9887 冻结 + 重排后、融合前降权）
8. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述
9. **近重复消融：S0 零外呼 A/B 已实测**（卡 28）：正对照证明**装置有鉴别力**，冻结真实语料 7 查询 A/B 得 `verdict=NO-RANK-CHANGE`（分数位移 0.06、名次与集合零变化、gap ≫ 窗口）；锚 `near-dup-ablation-v1`（写模式 `-Dnear.dup.ablation.write=true`，默认逐字节复跑、零外呼、CI 真跑、failsafe 用例数 +1）
10. **评测端口口径已对账**（卡 29）：72 例套件 `verdict=PARTIAL-OFF-PRODUCTION`——装配腿生产、输入腿伪向量、稀疏腿 bigram 近似、度量腿 min-max 集合内归一；平移不变性实测（`+100` 差 `2.83e-14`、`×2` 差 `0.0`、单点对照名次 `1→0`）⇒ **其分数不得当「文本路真分数分布」用**
11. **前端权限小表覆盖率已进门禁**（卡 30）：`endpointPermissionCoverage.test.ts` 断言 `used ⊆ registered`（模板 64 处动态站点 vs 小表 57 条），缺登记即红并打印端点 + 模板`行号`；三集合对账落锚 `ui-permission-coverage-v1`（写模式 `UI_PERMISSION_COVERAGE_UPDATE=1`），僵尸登记**只标注不删**
12. **S1 窗内共现消融已真跑（卡 31，30 外呼）**：构造 2 base × 3 非退化变体（`jpeg-95`/`jpeg-85`/`scale-95`），逐图七链等式 **6/6 全过**（分母 = 实测子块行数，卡 27 `COUNTER-SUPPRESSED` 口径），A/B **复用同一批查询向量**（各嵌一遍会 42 次、直接超帽）；结果 6 变体全 `ABOVE`（`cos ≥ 0.997653`）⇒ **0 次降权** ⇒ `verdict` 字段 `NO-RANK-CHANGE` 是**恒真标签**，按 `proposal §8` 正确读法是 `INCONCLUSIVE`（缺带内样本）。锚 `near-dup-ingest-s1-v1`（写模式 `-Dnear.dup.ingest.s1.write=true`，守卫 `NearDupIngestS1GuardTest`）
13. **带内入口已实测（卡 32，30 外呼）**：**纯几何裁切是唯一被实测撞进降权带的构造**——2 base × 3 边距里 4/6 带内（`cos 0.988904–0.993401`，`crop-04` 复现 S0 的 `0.9934`；带外 2 个照记：`SAME_IMAGE 0.994625` / 未召回）。`downWeightedCount=12` 后名次面与集合面仍零变化 ⇒ `NO-RANK-CHANGE`；`windowCheck` 12 条全录（4 条 `gap ≤ window` 未翻转 = 竞争者同步下沉）。锚 `near-dup-band-entry-v1`（写模式 `-Dnear.dup.band.entry.write=true`，守卫 `NearDupBandEntryGuardTest`；同锚 `legB` 承载零外呼档位填充表）
14. **窗口判据已升级为精确耦合式（卡 33，零外呼）**：`margin_A(e,r) = (fusedB_e − δ_e) − (fusedB_r − δ_r)`，`flipRisk = margin_A ≤ 1e-12`；**现行窗口 `gap > 0.30×(1−w)` 只是它的保守特例**（`δ_e ≤ window`、`δ_r ≥ 0` ⇒ 不漏报，但说不出「不会翻」）。12 条事件 `flipRisk=true` **0 条**；4 条 naive 假阳性 = 3×竞争者同步下沉 + 1×事件本身无位移。**`downWeightedCount` 要拆成 `markedCount`(12) / `displacedCount`(8)，触发量只能是后者**。锚 `near-dup-window-coupling-v1`（写模式 `-Dnear.dup.window.coupling.write=true`，守卫 `NearDupWindowCouplingGuardTest`；`delta=0` 的 4 条成因仍缺（`openQuestions.OQ1`），**不得引用任何未实测解释**）

15. **OQ1 已收口到「能证/不能证」分界（卡 34，零外呼）**：`δ = 0.30(1−w)r ⇒ δ=0 ⇔ r=0`（12/12）；`r=0` ⇒ 融合里图像贡献 0 ⇒ **降权是结构空操作**（**可证**，与标记无关）；成因（`normalizeScores` 的 min-max 末位归 0）**只到 `CONSISTENT-NOT-PROVEN`**，`verdict=NEEDS-INSTRUMENTED-RUN`，缺 4 字段 + `rerank` 两权重的运行时值。锚 `near-dup-oq1-attribution-v1`（写模式 `-Dnear.dup.oq1.attribution.write=true`，守卫 `NearDupOq1AttributionGuardTest`）。**引用纪律**：只能说「no-op 可证 + 成因待埋点」，不得说「已证明是 min-max 造成的」

16. **`r` 已本地拆开、OQ1 定案为 `UNDETERMINED`（卡 35，零外呼）**：用生产口径（`InMemoryVectorStore` → `ImageSimilarityGate` → `Bm25Scorer` → `DefaultWeightedReranker`）重建 6 查询候选集（30 行全重建），反解权重 `wv=0.6 / wb=0.4`（受控探针 + `0.9/0.1` 负对照），`r` 与卡 32 反算 **12/12 一致**（`maxAbsDiff 5.55e-16`）。**卡住的是判据不是数据**：4 条零位移 `normVec=0 ∧ normBm25=0` 成立，但 **bm25 分支 6/6 查询退化**（q0/q2 全 1.0、q1/q3/q4/q5 全 0.0）⇒ R3 的「全在非退化查询」不成立。结构发现：`r=0` 只可能出现在 bm25 全 0 的查询（否则 `r ≥ wb`），实测 4 条恰全在那里、q0/q2 最小 `r` 恰 `0.400000=wb`。埋点 `executed=false`、`budgetExternalCalls=0`（退化来自数据）。锚 `near-dup-rerank-decomposition-v1`（写模式 `-Dnear.dup.rerank.decomposition.write=true`，守卫 `NearDupRerankDecompositionGuardTest`），**同锚 `floorOptions` 承载 0.90 决策页**
17. **末位判据已条件化、0.90 影响与重锚清单已交付（卡 36，零外呼）**：三分支判定式互斥穷尽（实测 B0=4 / B1=2 / B2=0），`r=0` 的充要条件按分支给出；12 事件与卡 35 `routes` 逐字段 **12/12 零差异**、与卡 32 `windowCheck` **12/12 同名**；腿 B 逐锚核对 16 张（**矛盾 9 / 不受影响 6 / 未判定 1**——`near-dup-ingest-gate-probe-v1` 只记聚合值，按 R8 不猜）+ 重锚清单（9 张，依赖序按 sha256 引用实算、签收锚第 5）+ `221↔239` 外呼边界。**本卡不动常量、不重建、不给倾向值**。锚 `near-dup-conditional-min-criterion-v1`（写模式 `-Dnear.dup.conditional.min.write=true`，守卫 `NearDupConditionalMinCriterionGuardTest`）

---

## 3. 当前在途与候选队列（near-dup-rerank-decomposition 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. ~~S1 消融 / 更强变形带内构造~~ / ~~窗口判据升级~~ / ~~`delta=0` 成因（OQ1）~~ **四项已由卡 32/33/34/35 全部收口**
  2. ~~另卡改 R2/R3~~ **已由卡 36 收口（判据条件化，未放宽）**：三分支 + `PROVEN-CONDITIONAL` 已把「单分支末位 + 另一分支退化并列」写进适用域内的条件式；`PROVEN`（无条件）仍标「可达但本语料未出现」，不许拿它冒充结论
  3. **0.90 / 档位拍板（Owner 决，输入已齐）**：`floorOptions`（卡 35 效果面：own `70/120↔113/120`、third `13/2760↔93/2760`、same-doc `2/100↔8/100`、`R=94.17%`）+ **重锚清单（卡 36：9 张必需 / 6 张不受影响 / 1 张未判定；外呼 `221`，含未判定 `239`；依赖序实算）**。**两卡都不给推荐值**；动 `NEAR_DUPLICATE_FLOOR` 必须另卡，拍板后照清单执行重锚（多数零外呼）
  4. **用户意图级相关性标注（唯一能消掉 A1/A2 的路）**：须真实查询 + 人标；**严禁**用 agent 生成标签充数
  5. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`
- **下批推荐**：近重复线**入口 / 判据 / 计数 / 归因 / 分解 / 条件化**六题全部收口，零外呼能榨的已榨干（卡 36 是最后一滴）。往下只剩**一件**：**0.90 拍板（Owner）**——它现在有三样输入（效果面、重锚清单、外呼边界），拍了之后要做的是**重锚执行卡**（照清单走，多数零外呼）。**不要**再开「只读就能自答」的卡

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
15. **环境陷阱**：中文 UTF-8 文件逐行计数用 `read`；中文 `sed/grep` pattern 进命令行易 127；bash 走 `D:\git\Git\bin\bash.exe -lc`；`Set-Content -Encoding utf8` 会写 BOM；GitHub 按 `needs` 完成才创建下游 job，闭包以 run 级 `conclusion` 为准；**`Get-Content -Raw` 在 PS5.1 按 GBK 解码 UTF-8 会让整篇乱码且不可逆**（卡 29 实证）；**`Get-Content` 的行数计数同样不可信**（卡 30：同一文件 `git diff --stat` 报 308 行、它数成 296）；**仓库根 `grep -rn` 会扫进 `node_modules`**（卡 32：一次吐 200KB 无关正则表，把上下文挤爆）⇒ 检索一律限定路径，或用尊重 `.gitignore` 的 ripgrep；**`pnpm -C frontend test` 会重写 `frontend/typed-router.d.ts`**（生成物，+32 行）⇒ 跑完前端测试先 `git checkout --` 复原，否则基线重锚会记 `-dirty`
16. **锚字段比较容差须与断言同容差**
17. **锚条目须可独立解读**：会被交叉引用的数组字段，每条自带判读所需的布尔/范围标志
18. **上界须注明被放缩的量及其值域**
19. **恒定值一改，先问「谁跟着它算」**：grep 出所有由它推导的公式与边界；把「准入面」与「计算基准」拆成两个常量让变更**纯加性**；证据侧一律冻结字面量
20. **跨树跑命令，先问它落在哪棵树**：忘带 `workdir` 会落到观察树 detached 旧提交；跨树命令必须显式 `workdir`，复核结论前先 `git rev-parse --short HEAD` 自证落点
21. **判据之间要有适用性闭环（卡 27 新增）**：判据 A 判「机制能否复现」，判据 B 判「现象能否归因」，二者**不是同一件事**；凡在 B 里引用 A 的机制，必须用**本卡自己产出的前提条件**回头自检一次。另一条同源：**改「由测量值机械推导写入」的证据锚的结论，必须改推导源，不能改成品**——只改成品红的是复算测试，极易被误判成「锚被人手改坏」
22. **判定标签不得对输入恒真（卡 28 新增）**：verdict 必须能取到相反值；派生结论**只由判定面推导**，数据面单列字段；写判据时先自问「什么输入会让它取反」，答不出就说明它不是判据
23. **引用要指到能 grep 的实体（卡 29 新增）**：凡写「锚内声明」「已登记」「见文档」，必须能在被指对象里 grep 到该字段/该行；**生成物类门禁必须由生成器自身断言守住**，事后检查只能抓一次
24. **枚举型判据每个取值都要有可达路径（卡 30 新增）**：写枚举时逐值自问「什么实测输入会让它出现」，答不出就是死值。与第 22 条对偶：**恒真和恒假一样没有鉴别力**，前者骗下游，后者骗自己
25. **路径类断言必须双树实测（卡 30 新增）**：`Test-Path` 只在权威树跑就得出「文件不存在」，而文件就在观察树 `work/` 里。写「不存在」之前两棵树都测，并写明**在哪棵树成立**
26. **判定标签必须与触发量联动（卡 31 新增）**：`NO-RANK-CHANGE` 在「降权发生 2 次、名次没动」（卡 28）与「降权一次没发生」（卡 31）两种情形下**同名**，信息量天差地别——后者对任何落带外语料**恒真**。凡「A 导致 B」型判据，必须同时落 **A 发生了多少次**；`A==0` 时结论只能是 `INCONCLUSIVE`，不是「无影响」。这是第 22 条的**补丁**：光要求「能取反」不够，还要要求「事件真发生过」
27. **花钱的实验把 fail-fast 放在最贵调用之前（卡 31 新增）**：卡 26 是 **124 次**外呼之后才发现对账不符；卡 31 改成逐图入库后立即校验七链等式 + 硬帽 36，**最坏 3 次就能诊断**。预算不是上限数字，是「最早可诊断点」——写花钱卡时先问：第一笔钱花完，我能知道什么
28. **记录面的数字要从锚里抄，不能推算（卡 32 新增）**：`补勾依据` 写 `embedInvocationsA=12`（锚是 **6**）是把「查询嵌入 12 次」与「每跑检索链 6 次」**两个计量面混写**；`candidates 36 行` 是拿 `6×6` 猜的（实为 **30**）。凡写数字必须指到锚内**同名**字段并抄其值——**推算出来的数字就是下一个 MINOR**，而且它污染的是下一位读者对整张表的信任
29. **判据的文本与实现必须同容差，并由守卫断言（卡 33 新增）**：`flipRisk ≤ 0` 写在三处文本、实现却是 `≤ 1e-12`——平时看不出，可一旦 `margin_A ∈ (0,1e-12]` 且 `gap > window`，「正向断言」就会和实现互相打脸；同理 `r2.forwardHolds` 硬写 `true` 等于一个有字段名的恒真（第 22/24 条的记录面版本）。**判据只要带浮点比较，文本、锚字段、实现三处必须写同一个常量，且有一条断言把三处钉在一起**；能由数据推出的布尔，不许字面写 `true`
30. **分档结论必须被守卫反向钉住（卡 34 新增）**：把 `PROVEN` / `CONSISTENT-NOT-PROVEN` / `MISSING-EVIDENCE` 写进锚只是「声明分档」，不等于「分档守得住」——卡 34 把「末位」注入 `PROVEN` 后，守卫 7/7 仍绿，只有复算比对兜着。**凡分档结论，必须有一条反向断言：`PROVEN` 的文本里不许出现未证解释的关键词**（本卡禁式：`末位` / `min-max 把` / `归 0 造成` / `成因是`）。正向断言证明「我写了什么」，反向断言才证明「我没偷偷多写」
31. **判据的可满足性要在写卡时自证一遍（卡 35 新增）**：卡 35 的 R3 要求「两分支同时非退化」，可 bm25 在图像描述文本上**必然**退化（全命中或零命中）⇒ **这条判据在本语料上不可满足**，执行者只能判 `UNDETERMINED`。写判据时先问一句：**「什么数据能让它取到这个值？本语料里有没有这样的数据？」** 若答案是「理论上有、本语料里没有」，那不是判据，是把 `UNDETERMINED` 写成了宿命；正确做法是**写条件化判据**（在 X 分支退化为全 0 时等价于单分支判据），并在卡里写明适用域
32. **能被源锚证伪的陈述，落笔那一步就证一次（卡 36 新增）**：本卡两处翻车同源——复核报告说「签收锚 sha 被多张引用」，我照抄进指令；fix1 又把依据写成 `signedDigests`「四张」，而该字段**压根不存在**（实为 `evidenceAnchors` 6 键、清单内 4 张）。**凡是「某锚里有 X 字段 / 引用了 N 张 / 冻了 Y」这类下次 grep 就能推翻的话，先 grep 一次源锚再落笔**；能写成反向断言的（不含错误字段名、键数 = N、交集 = M）就写进守卫，别留在散文里当装饰

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**；最近四卡零迁移）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §198**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/near-dup-conditional-min-criterion/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **近重复图像六份锚**：`near-dup-exchange-rate-v1`／`near-dup-fusion-impact-v1`／`image-near-dup-truth-v1`／`near-dup-displacement-evidence-v1`／`image-variant-cross-matrix-v1`／`near-dup-floor-signoff-v1`（写模式见各自守卫）
- **入库面三锚**：`near-dup-ingest-gate-probe-v1`（S0 闸门探针）／`near-dup-ingest-s1-not-run-v1`（卡 26 的 NOT-RUN 负结论，**历史不可改写**）／`image-slice-ingest-chain-v1`（七链对账 + 五种丢弃语义）
- **消融锚**：`near-dup-ablation-v1`（S0 A/B，`-Dnear.dup.ablation.write=true`，`verdict` 只由名次面+集合面推导、分数面单列 `scoreFace`）
- **S1 消融锚**：`docs/rag-quality/near-dup-ingest-s1-v1.json`（30 外呼台账 + 6 变体 `bandSide` + 逐查询 A/B + `constructionFailure`，写模式 `-Dnear.dup.ingest.s1.write=true`，守卫 `NearDupIngestS1GuardTest`；**`verdict` 需与 `downWeightedCount=0` 连读**）
- **带内入口锚**：`docs/rag-quality/near-dup-band-entry-v1.json`（30 外呼台账 + 6 变体 `bandSide` + 逐查询 A/B + `windowCheck` 12 条 + 零外呼 `legB.tierFill`／`thresholdOptions`，写模式 `-Dnear.dup.band.entry.write=true`，守卫 `NearDupBandEntryGuardTest`；**`dwc=12`，`NO-RANK-CHANGE` 只对本构造语料成立**）
- **窗口判据锚**：`docs/rag-quality/near-dup-window-coupling-v1.json`（12 条事件 `marginA`/`flipRisk` + 4 条假阳性两桶归因 + `marked`/`displaced` 计数 + 三组构造对照 + 上游 4 项 SHA，写模式 `-Dnear.dup.window.coupling.write=true`，守卫 `NearDupWindowCouplingGuardTest`；**`flipRisk=true` 0 条只对卡 32 构造域成立**）
- **OQ1 归因锚**：`docs/rag-quality/near-dup-oq1-attribution-v1.json`（12 条 `r` 反算 + 4 条 no-op 算式 + 三档 `claimClass`（`PROVEN` 3 / `CONSISTENT-NOT-PROVEN` 2 / `MISSING-EVIDENCE` 4）+ 一致性检查 6 查询 + `instrumentedPlan`（`executed=false`，30 外呼），写模式 `-Dnear.dup.oq1.attribution.write=true`，守卫 `NearDupOq1AttributionGuardTest`）
- **r 分解锚（本卡）**：`docs/rag-quality/near-dup-rerank-decomposition-v1.json`（权重 `0.6/0.4` + 30 行候选分解 + `r` 12/12 对账 + 桶 + `vectorNormalizationIndependence` 独立路径 + `floorOptions` 0.90 决策页，写模式 `-Dnear.dup.rerank.decomposition.write=true`，守卫 `NearDupRerankDecompositionGuardTest`；**`verdict=UNDETERMINED` 是「判据不可满足」不是「机制不存在」**）
- **末位判据锚（本卡）**：`docs/rag-quality/near-dup-conditional-min-criterion-v1.json`（三分支判定式 + 12 事件条件复判 + 16 张影响清单 + 重锚清单 `executed=false`，写模式 `-Dnear.dup.conditional.min.write=true`，守卫 `NearDupConditionalMinCriterionGuardTest`；**`PROVEN-CONDITIONAL` 必须连 `applicabilityDomain` 读，`PROVEN` 档只是可达性说明**）
- **评测端口 / 前端权限锚**：`eval-port-route-v1`（`-Deval.port.route.anchor.write=true`）／`docs/ui-permission-coverage-v1.json`（`UI_PERMISSION_COVERAGE_UPDATE=1`）
- **真跑 opt-in**：`RAG_BENCHMARK_REAL=1` / `RAG_VISION_EMBED_REAL=1` / `RAG_NEAR_DUP_DISPLACEMENT_REAL=1` / `RAG_IMAGE_CROSS_MATRIX_REAL=1` / `RAG_NEAR_DUP_INGEST_REAL=1`；**跳过 ≠ 通过**
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json`；计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
