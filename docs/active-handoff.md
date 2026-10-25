# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-25（ui-permission-coverage-stocktake：前端权限小表**覆盖率门禁**落地——漏登即红，不再靠人肉记得加；兼营板 §3 第 7 条存量双树核对）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`8172f80`（M1 合并）+ `74835f0`（基线重锚）+ 本归档笔** = origin/master。分支 `feature/ui-permission-coverage-stocktake` **永久保留**（基点 `2d77460` → **2 笔**：`6c885f9` 首轮 / `70d1f0c` fix1 代笔）
- **测试基线四元组**：`source-revision=8172f80`（**树净，非 `-dirty`**）+ Surefire **报告 351 / 用例 2167 / F0 / E0 / S0** + Failsafe **报告 47 / 用例 149 / F0 / E0 / S16** + **CI = Run 40201929885（6/6 jobs 绿）**。本卡**零 Java 改动** ⇒ 三组计数与上一卡**逐值持平**（`mvn clean verify` 16:11 min 实跑后 `--update` 只前移 `source-revision` 与 `measured-at`）
- **本卡规模**：对 `2d77460` **6 文件 / +1351 −0**（全新增；fix1 代笔 `+41 −18` 落在其中 3 个文件内）；merge 树 == 分支 tip 树，树净
- **本卡结论（一句话）**：**小表的真实风险不是「写错」，是「漏登」。** 编译期 `ContractPermission<K>` 只守「登记得对不对」，表类型 `Partial<{...}>` 让漏登**合法**，而运行期 `permissionOfEndpoint` 对未登记端点 **fail-closed** ⇒ 漏登的后果是**功能静默消失**（`Permission.vue:29` 是唯一生产调用点）。本卡把它做进门禁：`endpointPermissionCoverage.test.ts` 断言 `used ⊆ registered`，缺登记即红并打印端点 + 模板`行号`；CI 的 `frontend-quality` job 本就跑 Vitest（`ci.yml:246`）⇒ 零 YAML 改动即生效
- **三集合实测（结论锚 `ui-permission-coverage-v1`）**：`used` **56** 键 / 门控站点 **64** 处（**全部是 `:endpoint` 动态绑定字面量，静态 `endpoint="X"` 写法全仓 0 处**）+ `:allow` 逃生口 1 处（`sale/list.page.vue:37` 面外）；`registered` **57**；`contract` **279**（259 + OPEN 20）；**缺口 `used∖registered` = ∅ ⇒ `verdict=COVERAGE-CLOSED`**；契约外 ∅（运行期断言）；僵尸 1 = `DELETE /contract`（**只标注不删**，删除会改历史权限判断，属另卡）
- **责任划分（不粉饰）**：复核 APPROVE_WITH_MINOR 四条 MINOR 全部采纳；**我自己认领两条判据缺陷**——① R4 的 `GAPS-FIXED` 分支在「按复算差集推导」口径下**不可达**（补了也会写出 `COVERAGE-CLOSED`，已记 proposal §8，留记录不改生成器）；② tasks 13 条执行者证据齐全但**未落勾选**，我 M1 前代笔补勾（依据全部为本轮独立复测）。**自查第五条**：proposal 存量面写的「两文件实测都不存在」只对权威树成立，观察树 `work/` 实存 6592 / 70691 B，已勘误
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复图像：档位 + 五份证据 + 两张入库面锚**：生产 `[0.982, 0.9944)` 三档降权 `×0.75/×0.6/×0.5`，内边界以 `BAND_REFERENCE_FLOOR=0.9887` 冻结（`0.982 | 0.9906 | 0.9925 | 0.9944`）；五件套**全部「测于 0.9887」**；签收 `near-dup-floor-signoff-v1`；入库面 = S0 闸门探针（6/6 过闸、18 次外呼、冻结投影）+ S1 NOT-RUN 负结论
4. **图像切片摄取链：已由断言守护**（卡 26）：七链逐链对账（`image-slice-ingest-chain-v1`）+ 五种静默丢弃语义 + `embedText` 碰撞四因子条件（文件名∧类目∧页码∧描述）；`stageImageVector` 仍**在** `if (description != null)` 分支内（`PdfImageSliceIngestService:248`）⇒ 闸门拒图连带丢图像向量，**这是被测量的现象，不是待修 bug**
5. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
6. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh`（GO/NO-GO + 原因码），与观测面、回滚、热生效实证成套
7. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + 准入下限 0.982 + 带内基准 0.9887 冻结 + 重排后、融合前降权）
8. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述
9. **近重复消融：S0 零外呼 A/B 已实测**（卡 28）：正对照证明**装置有鉴别力**，冻结真实语料 7 查询 A/B 得 `verdict=NO-RANK-CHANGE`（分数位移 0.06、名次与集合零变化、gap ≫ 窗口）；锚 `near-dup-ablation-v1`（写模式 `-Dnear.dup.ablation.write=true`，默认逐字节复跑、零外呼、CI 真跑、failsafe 用例数 +1）；S1（非退化近重复语料，≤36 次外呼）**仅预注册未执行**
10. **评测端口口径已对账**（卡 29）：72 例套件 `verdict=PARTIAL-OFF-PRODUCTION`——装配腿生产、输入腿伪向量、稀疏腿 bigram 近似、度量腿 min-max 集合内归一；平移不变性实测（`+100` 差 `2.83e-14`、`×2` 差 `0.0`、单点对照名次 `1→0`）⇒ **其分数不得当「文本路真分数分布」用**；锚 `eval-port-route-v1`（写模式 `-Deval.port.route.anchor.write=true`，守卫 `EvalPortRouteGuardTest`）
11. **前端权限小表覆盖率已进门禁**（卡 30）：`endpointPermissionCoverage.test.ts` 断言 `used ⊆ registered`（模板 64 处动态站点 vs 小表 57 条），缺登记即红并打印端点 + 模板`行号`；三集合对账落锚 `ui-permission-coverage-v1`（写模式 `UI_PERMISSION_COVERAGE_UPDATE=1`，默认逐字节复算）；僵尸登记**只标注不删**。**先天的盲区仍在**：静态解析不了运行时拼接，（当前白名单 0 条）新增动态站点若解析不出，门禁会以「白名单过期/解析失败」而非静默放行报红

---

## 3. 当前在途与候选队列（ui-permission-coverage-stocktake 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **S1 消融执行（卡 28 预注册，Owner 决）**：非退化近重复语料（原生分辨率重编码 / 轻度 JPEG / 整图微缩，短边仍 ≥64px），入库后**断言实测过闸数 == 预期数**，任何静默丢弃即红；成本算式 `6×3 + 6×2 = 30`，**报批 36 封顶**。触发条件已写进锚：卡 28 判 `NO-RANK-CHANGE` 且窗口算术显示真实语料**无窗内共现**（gap 0.3856 vs 窗口 0.15）
  2. **观测真实位移后再议 0.90（Owner 决）**：`0.90` 是 `R=94.17%` 膝盖点，但多压 6 张同文档图；再动 `NEAR_DUPLICATE_FLOOR` **必须新卡**
  3. ~~文本路真分数分布口径核验~~ **已由卡 29 结案（关闭）**：72 例套件的分数是集合内相对量（min-max）+ 伪向量 + bigram 近似稀疏腿 ⇒ 不可当分布。要分布只剩 ① 同一查询内**分差**分布 ② 生产库真跑（需库+外呼，另卡），由 Owner 定
  4. **用户意图级相关性标注（唯一能消掉 A1/A2 的路）**：须真实查询 + 人标；**严禁**用 agent 生成标签充数
  5. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`
  6. ~~前端小表随站点增长维护~~ **已由卡 30 结案（关闭）**：57 条小表已门禁化，此后新增站点漏登 CI 即红（`endpointPermissionCoverage.test.ts`）；「漏登」这条静默失效路径**从人肉纪律变成机器判据**
  7. ~~文档类存量~~ **已由卡 30 结案（关闭）**：`docs/permission-matrix-audit.md` 维持**历史快照，遵裁决不改写**；`work/_online-exam-backlog.md`（6592 B）与 `work/_paa-candidates-pool-v2.md`（70691 B）**在本仓权威树不存在、在观察树 `D:\code\crmAndRag\work\` 实存未跟踪**——原板未写「观察树 work/」限定，是板子的表述问题，不是文件丢失
- **下批推荐**：RAG 质量线与前端权限线的**零外呼题已清完**（卡 29 / 卡 30 双双结案）⇒ 剩下的都不便宜：要么 Owner 单独授权 **S1 消融（30 次外呼，报批 36）**，要么走**人标**（储备 4），要么**换题**（新域）。**不要**为了「有产出」再开一张只读代码就能自答的取证卡；也不要为了凑数把同一批证据再翻一遍

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
15. **环境陷阱**：中文 UTF-8 文件逐行计数用 `read`；中文 `sed/grep` pattern 进命令行易 127；bash 走 `D:\git\Git\bin\bash.exe -lc`；`Set-Content -Encoding utf8` 会写 BOM；GitHub 按 `needs` 完成才创建下游 job，闭包以 run 级 `conclusion` 为准；**`Get-Content -Raw` 在 PS5.1 按 GBK 解码 UTF-8 会让整篇乱码且不可逆**（卡 29 实证：改中文文档禁用 PowerShell 管道，只用 `edit` 工具）；**`Get-Content` 的行数计数同样不可信**（卡 30 实测：同一文件 `git diff --stat` 报 308 行、`Get-Content` 数成 296；行数一律以 `read` 的 total 或 `git diff --stat` 为准）
16. **锚字段比较容差须与断言同容差**
17. **锚条目须可独立解读**：会被交叉引用的数组字段，每条自带判读所需的布尔/范围标志
18. **上界须注明被放缩的量及其值域**
19. **恒定值一改，先问「谁跟着它算」**：grep 出所有由它推导的公式与边界；把「准入面」与「计算基准」拆成两个常量让变更**纯加性**；证据侧一律冻结字面量
20. **跨树跑命令，先问它落在哪棵树**：忘带 `workdir` 会落到观察树 detached 旧提交，「命令不可复现」全是自己造的假象；跨树命令必须显式 `workdir`，复核结论前先 `git rev-parse --short HEAD` 自证落点
21. **判据之间要有适用性闭环（卡 27 新增）**：判据 A 判「机制能否复现」，判据 B 判「现象能否归因」，二者**不是同一件事**；凡在 B 里引用 A 的机制，必须用**本卡自己产出的前提条件**回头自检一次（卡 27 `r4` 的条件当场否证了 `r5` 的归因）。另一条同源：**改「由测量值机械推导写入」的证据锚的结论，必须改推导源，不能改成品**——只改成品红的是复算测试，极易被误判成「锚被人手改坏」
22. **判定标签不得对输入恒真（卡 28 新增）**：verdict / 结论字段必须能取到相反值——把「分数必然变」与「顺序可能变」并进同一个标签，标签就退化成常真式，下游只会读到假位移。派生结论**只由判定面推导**，数据面单列字段（卡 28 `scoreFace`）；写判据时先自问「什么输入会让它取反」，答不出就说明它不是判据
23. **引用要指到能 grep 的实体（卡 29 新增）**：凡写「锚内声明」「已登记」「见文档」这类引用，必须能在被指对象里 grep 到该字段/该行——卡 29 任务卡声称的「锚内声明」在锚 JSON 里根本不存在（复核探针 `decl-independentImpl=false` 当场证伪）。同源一条：**生成物类门禁（如 `git diff --check` 的尾部空行）必须由生成器自身断言守住**，事后检查只能抓一次，防不住回归
24. **枚举型判据每个取值都要有可达路径（卡 30 新增）**：R4 给了三个 verdict，但 `GAPS-FIXED` 在「按复算差集推导」的口径下**永远发不出去**（补齐后差集恒空）——写枚举时逐值自问「什么实测输入会让它出现」，答不出就是死值。这与第 22 条是对偶：**恒真和恒假一样没有鉴别力**，前者骗下游，后者骗自己
25. **路径类断言必须双树实测（卡 30 新增）**：`Test-Path` 只在权威树跑就得出「文件不存在」，而那两个文件就在观察树 `work/` 里（6592 / 70691 B）。写「不存在」之前两棵树都测，并在结论里写明**在哪棵树成立**；「不存在 / 没有 / 全都不」是最容易说过头的断言，代价是下一位读者按图索骥一场空。任务卡里出过这种事，判据文件里出过这种事，**归档后就没人再纠正了**

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**；最近三卡零迁移）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §182**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/ui-permission-coverage-stocktake/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **近重复图像六份锚**：`near-dup-exchange-rate-v1`（价目表）／`near-dup-fusion-impact-v1`（融合上界，`-Dnear.dup.fusion.write=true`）／`image-near-dup-truth-v1`（检索级真值，`-Dnear.dup.truth.write=true`）／`near-dup-displacement-evidence-v1`（位移实测，`-Dnear.dup.displacement.write=true`）／`image-variant-cross-matrix-v1`（代理有效性，`-Dimage.cross.matrix.write=true`）／`near-dup-floor-signoff-v1`（签收决议，守卫实算六锚 SHA）
- **入库面三锚**：`near-dup-ingest-gate-probe-v1`（S0 闸门探针，冻结投影）／`near-dup-ingest-s1-not-run-v1`（S1 NOT-RUN 预注册，守卫断言 S1 锚**必须不存在**）／`image-slice-ingest-chain-v1`（七链对账 + 五种丢弃语义 + 碰撞四因子，写模式 `-Dimage.slice.chain.anchor.write=true`）
- **消融锚**：`near-dup-ablation-v1`（S0 A/B，写模式 `-Dnear.dup.ablation.write=true`，默认逐字节复跑 + 零外呼，守卫 `NearDupAblationGuardTest`，结论位 `verdict` 只由名次面+集合面推导、分数面单列 `scoreFace`）
- **评测端口锚**：`eval-port-route-v1`（写模式 `-Deval.port.route.anchor.write=true`，默认逐字节复算，守卫 `EvalPortRouteGuardTest`）
- **前端权限锚（本卡）**：`docs/ui-permission-coverage-v1.json`（`used`/`registered`/`contractDigest` 三集合 + 三差集 + 64 处站点清单 + `dynamicBindingsResolved` + `zombieCandidates`，写模式 `UI_PERMISSION_COVERAGE_UPDATE=1`，守卫 `frontend/src/utils/__tests__/endpointPermissionCoverage.test.ts`）
- **真跑 opt-in**：`RAG_BENCHMARK_REAL=1` / `RAG_VISION_EMBED_REAL=1` / `RAG_NEAR_DUP_DISPLACEMENT_REAL=1` / `RAG_IMAGE_CROSS_MATRIX_REAL=1` / `RAG_NEAR_DUP_INGEST_REAL=1`；**跳过 ≠ 通过**
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json`；计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
