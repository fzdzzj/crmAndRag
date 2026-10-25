# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-26（near-dup-ingest-s1-window-cooccurrence：S1 窗内共现消融**真跑 30 外呼**——原问题未被触及，但换回一条关于档位入口的实测事实与两条新板规）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`adb16d5`（M1 合并）+ `3e3aa68`（基线重锚）+ 本归档笔** = origin/master。分支 `feature/near-dup-ingest-s1-window-cooccurrence` **永久保留**（基点 `20b3d18` → **2 笔**：`3eae484` 首轮 / `5c05de0` fix1 代笔）
- **测试基线四元组**：`source-revision=adb16d5`（**树净，非 `-dirty`**）+ Surefire **报告 352 / 用例 2172 / F0 / E0 / S0** + Failsafe **报告 48 / 用例 150 / F0 / E0 / S17** + **CI = Run 40213813370（6/6 jobs 绿）**。上一卡 351/2167/0 + 47/149/16 → 本卡 surefire **+1 报告 +5 用例**（守卫五个 `@Test`）、failsafe **+1 报告 +1 用例 +1 skipped**（opt-in IT）
- **本卡规模**：对 `20b3d18` **8 文件 / +4365 −0**（全新增）；merge 树 == 分支 tip 树，树净
- **本卡结论（一句话）**：**S1 跑完了，但没答出原问题。** 6 变体（2 base × `jpeg-95`/`jpeg-85`/`scale-95`）对各自 base 的 `cos` 全在 **0.997653–0.999956**，全部落 `SAME_IMAGE` 档（≥0.9944）⇒ **0 次降权、`windowCheck` 空、名次面与集合面零变化**。「撞上了会怎样」在本构造参数域内**未被触及**（锚 MD 自带此限定，非事后辩解）
- **本卡真正的副产物（供 0.90/档位议题）**：**`[0.982, 0.9944)` 的实际填充者是几何编辑，不是质量退化**——三种质量型变形（重压/微缩）全部 ≥0.997653 落 `SAME_IMAGE`，而 S0 探针里**几何型** `crop-04` 的 `cos = 0.9934` 恰在带内（`near-dup-ingest-gate-probe-v1.md:38`）。本卡不据此提阈值主张
- **责任划分（不粉饰）**：**我的 D3 口径缺陷**——`downWeightedCount == 0` 时 `NO-RANK-CHANGE` 对**任何**落带外语料恒真，与卡 28 R6 同类病；正确取值应为 `INCONCLUSIVE`（缺带内样本）。锚不改（改只能重跑 30 次外呼或手改成品），以锚 MD 自带限定 + 锚内 `downWeightedCount=0`/`windowCheck.entries=0` 自证，并立板规 26 防复发。复核 MINOR① 采纳（`qwen3-vl-plus` 覆盖补记）、② **驳回**（guard 的 `≥` 是「含未入 topK 事件」的不同集合口径，其断言消息已写明）
- **模型覆盖事实**：真跑用 `qwen3-vl-plus`；生产默认 `qwen-vl-plus` 在本 key 返回 `model_not_found`（卡 26 同现象）⇒ **生产若按默认模型发描述请求会直接失败**，属另卡
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

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

---

## 3. 当前在途与候选队列（near-dup-ingest-s1-window-cooccurrence 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. ~~S1 消融执行~~ **已由卡 31 执行（构造失效面，关闭）**：30 外呼跑完，6 变体全落带外 ⇒ 原问题「撞上了会怎样」**未被触及**。要真答须**更强变形**（q70 / 更狠缩放 / 加噪），但风险明确：**会撞卡 26 的提取下限（短边 <64px）与描述质量闸门丢弃**，届时又回到「付了钱发现样本进不去」；若要做，**必须先做本地可选性预检**（尺寸/内容复杂度）、再小步试跑（≤6 次）确认能进带内，才谈 30 次。**须 Owner 单列授权，不许顺延本卡预算**
  2. **0.90 / 档位再议（Owner 决，现多一条实测事实）**：`0.90` 是 `R=94.17%` 膝盖点，但多压 6 张同文档图；本轮新增事实——`[0.982,0.9944)` 的填充者是**几何编辑**（crop 0.9934）而非**质量退化**（≥0.997653），故「轻压/微缩」类业务场景大概率根本不进档，档位设计可按「内容平移」重估。动 `NEAR_DUPLICATE_FLOOR` **必须新卡**
  3. **用户意图级相关性标注（唯一能消掉 A1/A2 的路）**：须真实查询 + 人标；**严禁**用 agent 生成标签充数
  4. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`
  5. ~~前端小表~~ / ~~文档类存量~~ **已由卡 30 双双结案（关闭）**：小表已门禁化；`docs/permission-matrix-audit.md` 维持历史快照；两 work 文件在观察树 `work/` 实存未跟踪（板已补限定）
- **下批推荐**：**RAG 质量线的外呼题已跑到边界**——S1 已执行且证明「质量型退化进不了档」，再往下只有两条路：① 更强变形的带内构造（须新授权，风险已知）② 0.90/档位议题（现在有实测事实支撑，零外呼可做设计题）。**不要**再开一张「只读代码就能自答」的取证卡，也**不要**把卡 31 的 `NO-RANK-CHANGE` 当「降权无效」引用（它 `downWeightedCount=0`）

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
15. **环境陷阱**：中文 UTF-8 文件逐行计数用 `read`；中文 `sed/grep` pattern 进命令行易 127；bash 走 `D:\git\Git\bin\bash.exe -lc`；`Set-Content -Encoding utf8` 会写 BOM；GitHub 按 `needs` 完成才创建下游 job，闭包以 run 级 `conclusion` 为准；**`Get-Content -Raw` 在 PS5.1 按 GBK 解码 UTF-8 会让整篇乱码且不可逆**（卡 29 实证）；**`Get-Content` 的行数计数同样不可信**（卡 30：同一文件 `git diff --stat` 报 308 行、它数成 296）
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

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**；最近四卡零迁移）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §185**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/near-dup-ingest-s1-window-cooccurrence/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **近重复图像六份锚**：`near-dup-exchange-rate-v1`／`near-dup-fusion-impact-v1`／`image-near-dup-truth-v1`／`near-dup-displacement-evidence-v1`／`image-variant-cross-matrix-v1`／`near-dup-floor-signoff-v1`（写模式见各自守卫）
- **入库面三锚**：`near-dup-ingest-gate-probe-v1`（S0 闸门探针）／`near-dup-ingest-s1-not-run-v1`（卡 26 的 NOT-RUN 负结论，**历史不可改写**）／`image-slice-ingest-chain-v1`（七链对账 + 五种丢弃语义）
- **消融锚**：`near-dup-ablation-v1`（S0 A/B，`-Dnear.dup.ablation.write=true`，`verdict` 只由名次面+集合面推导、分数面单列 `scoreFace`）
- **S1 消融锚（本卡）**：`docs/rag-quality/near-dup-ingest-s1-v1.json`（30 外呼台账 + 6 变体 `bandSide` + 逐查询 A/B + `constructionFailure`，写模式 `-Dnear.dup.ingest.s1.write=true`，守卫 `NearDupIngestS1GuardTest`；**`verdict` 需与 `downWeightedCount=0` 连读**）
- **评测端口锚**：`eval-port-route-v1`（`-Deval.port.route.anchor.write=true`，守卫 `EvalPortRouteGuardTest`）
- **前端权限锚**：`docs/ui-permission-coverage-v1.json`（`UI_PERMISSION_COVERAGE_UPDATE=1`，守卫 `endpointPermissionCoverage.test.ts`）
- **真跑 opt-in**：`RAG_BENCHMARK_REAL=1` / `RAG_VISION_EMBED_REAL=1` / `RAG_NEAR_DUP_DISPLACEMENT_REAL=1` / `RAG_IMAGE_CROSS_MATRIX_REAL=1` / `RAG_NEAR_DUP_INGEST_REAL=1`；**跳过 ≠ 通过**
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json`；计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
