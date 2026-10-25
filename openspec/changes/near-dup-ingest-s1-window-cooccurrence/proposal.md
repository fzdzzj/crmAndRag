# proposal · near-dup-ingest-s1-window-cooccurrence（S1 窗内共现消融，真跑 30 外呼）

> 卡 31。**A 档（真跑 + 外呼）**。零迁移 / 零契约 / 零权限 / `src/main` 零 diff / 既有锚零字节变动。
> 预算：**30 次外呼目标，36 封顶**（硬帽在测试内实现，超帽即抛）。执行前须 Owner 单独授权外呼。

## §1 问题陈述（这张卡要回答的唯一问题）

卡 28 的 S0 消融判了 `NO-RANK-CHANGE`，但它**诚实地附了窗口算术**：被降权图的融合分与最近竞争对手的差 `gap = 0.3856`，而降权最多只能位移 `0.30×(1−w) ≈ 0.15` ⇒ **真实冻结语料里根本没有「窗内共现」**，S0 的零位移不是「降权无害」的证据，只是「没撞上」。

⇒ S1 的唯一任务是：**手工构造一批非退化近重复，让变体与 base 的图像相似度落进生产档位窗口，再看降权能不能把名次搬动。**

构造失败也是一种答案（说明这套降权在设计上搬不动名次），但必须**先把构造规则钉死再花钱**，否则事后挑样本就变成自证。

## §2 侦察已得事实（行号实测于 `20b3d18`，执行者只需复核）

- **预注册来自卡 28**（`openspec/changes/archive/near-dup-ablation-redesign/proposal.md:54-58`）：语料改为「真实切片 + 原生分辨率重编码 / 轻度 JPEG 重压 / 整图微缩，短边仍 ≥64px，**每 base 3 个变体**」；成本 变体 6×(vision 1+image embed 1+文本 embed 1)=18 + 查询 6×(文本 embed 1+image embed 1)=12 ⇒ **≤30，报批 36**。
- **卡 26 的 S1 是怎么废掉的**（`docs/rag-quality/near-dup-ingest-s1-not-run-v1.json`）：48 图集真跑 **124 次外呼**，红在「过闸 41 vs 文本 embed 36」⇒ 那 5 个缺口当时无法归因，钱已花完。**归因已由卡 27 结案**：`COUNTER-SUPPRESSED`——链完好，是测试侧缓存抑制了计数，**分母必须用实测子块行数**，不是过闸图数（`image-slice-ingest-chain-v1`）。本卡把这条直接做进 R2 的逐图等式。
- **装置现成**：`NearDupIngestGateProbeIT`（S0，18 外呼，6 图 6/6 过闸）已把「合成单图 PDF → 生产提取器 → VLM 描述 → 质量闸门 → 落盘 → 描述 embed + 图像 embed」整条链跑通，并内联了原 `NearDupIngestPdfBuilder`（该 IT `:749`）；`BudgetedTrackingProvider`（超帽即抛，`:339`）、`S0_CALL_CAP`（`:135`）、`MIN_EDGE_PX=64`（`:170`）可直接照抄结构。**S1 是它的加长版，不是新装置。**
- **A/B 装置现成**：卡 28 的 B 跑用 `mockStatic(CALLS_REAL_METHODS)` 只替换降权那一步（`src/main` 零 diff），先例在案；verdict 口径用卡 28 fix1 后的 **名次面 + 集合面**判，分数面单列。
- **语料是本地资产**：`RealPdfAssets.DIR = src/test/resources/rag-quality/real-pdf`，`sources.json` 登记公有领域 PDF，`MAX_IMAGES_PER_DOCUMENT=3`、`MIN_EDGE_PX=64`——**不下载新资产**（卡 28 D3）。

## §3 判据（R0–R8，执行与复核均以此为准）

- **R0 预算硬帽 + 逐图 fail-fast（本卡第一判据）**：`S1_CALL_CAP = 36` 在测试内实现（复用 `BudgetedTrackingProvider` 结构），超帽即抛、不吞。**每入库一张变体就断言 R2 等式，不成立立即 abort**——卡 26 的教训是「124 次之后才发现不对」，本卡要求「**3 次之后就知道**」。abort 时把已花调用落锚，不许静默重试、不许换图重跑。
- **R1 语料构造规则（先钉规则后花钱，禁人工挑选）**：取 `nist-sp800-101r1` 语料生成序前 2 个「短边 ≥64px 且非纯色/低色数」的 base（沿用 `NearDuplicateCorpus` 的 `ExcludedBase` 排除口径），每 base 造 **3 个非退化变体**：
  - `jpeg-95`：原生分辨率、PNG→JPEG q95 重编码；`jpeg-85`：同上 q85；`scale-95`：原生分辨率整体缩到 95%。
  - **短边 ≥64px 用本地像素断言**（不花调用），不足即换参数重造，不许弃图（弃图会让分母与预期不符）。
  - **禁**往 `NearDuplicateCorpus` 里加变体种类：该类的变体清单与顺序被 6 份冻结锚的 `selectionRule` 引用，改动会破它们的逐字节复跑。新种类另立 builder（新文件）。
- **R2 入库对账（逐图等式，任何静默丢弃即红）**：每张变体入库后断言 `提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片≥1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数`；图片切片 `rowIndex` 必须为 `null`（卡 27 R4）。**分母一律用实测子块行数**，禁用「过闸图数×常数」等推导量。
- **R3 A/B 检索（6 查询 × 2 跑）**：查询构造规则（同样先钉死）：**2 base × 3 形态**——①页面标题式短查询 ②正文首句 ③表格/图注关键词组；文本取自该页**已提取文本**（不新增花销），图像分量 = 该 base 位图 ⇒ 每查询 2 次 embed、共 12 次。A 跑 = 生产降权；B 跑 = `w=1`（`mockStatic(CALLS_REAL_METHODS)`，`src/main` 零 diff）。逐查询落 `{候选集, cos, fusedA, fusedB, rankA, rankB, downWeightedKeys}`。**两跑必须复用同一批查询向量**（A/B 各嵌一遍 = 24 次 ⇒ 总 42，直接超帽），复用由测试侧缓存保证。
- **R4 verdict 三选一（卡 28 v2 口径）**：`RANK-CHANGE`（名次面或集合面有变化）/ `NO-RANK-CHANGE`（两者均无变化）/ `INCONCLUSIVE`（附缺哪条证据）。**三值都必须可达**：`RANK-CHANGE` 靠窗内共现，`NO-RANK-CHANGE` 靠带外，`INCONCLUSIVE` 靠 abort 或变体落带外到无法判定。`NO-RANK-CHANGE` 必附 `windowCheck`（逐条被降权候选 `gap` vs `0.30×(1−w)`）+「不可外推为生产无位移」声明。
- **R5 带外样本必须诚实记录**：逐变体记 `bandSide`（`IN-BAND` / `ABOVE` / `BELOW`，边界读生产常量不抄数字）；`verdict` 推导**只用带内变体**，带外样本不得用来凑 `RANK-CHANGE`，也不得因其存在就改判 `INCONCLUSIVE`。
- **R6 默认零外呼可复认证**：无 opt-in 时一律走 stub provider，默认复跑按锚逐字节比对（剔除顶层 `runtime` 块，卡 26 NF-1 冻结投影先例）；`constants` 只落编译期常量。
- **R7 边界**：`src/main` 零 diff；既有 8 锚（含 `near-dup-ingest-s1-not-run-v1.json`、`near-dup-ingest-gate-probe-v1.json`）**零字节变动**；`NearDuplicateCorpus` / `NearDupIngestGateProbeIT` / `NearDupIngestGateProbeGuardTest` **不得修改**（后者断言 `cooccurrence` 锚必须不存在——本卡锚用新文件名，负空间断言自然仍成立，**不许去改它**）。
- **R8 成本台账**：锚内 `callLedger{vision, embedImage, embed, total, cap}`，`total = 三分项之和` 且 `total ≤ 36`；另记 `authorization = "Owner 2026-10-25 报批 36"`。卡 26 的累计帽 162 属其一次性裁定，本卡以 36 为本次上限（**不改写历史台账**）。

## §4 交付物

- 新语料构造器：`src/test/java/com/slz/crm/quality/NearDupS1Corpus.java`（2 base × 3 变体，本地像素断言）
- 新 IT：`src/test/java/com/slz/crm/quality/NearDupIngestS1IT.java`（opt-in `RAG_NEAR_DUP_INGEST_REAL=1`，写锚 `-Dnear.dup.ingest.s1.write=true`，A/B 检索 + 逐图对账）
- 新守卫：`src/test/java/com/slz/crm/quality/NearDupIngestS1GuardTest.java`（零外呼：锚逐字节复算 + 台账等式 + verdict 可达性 + 既有锚零变动）
- 结论锚：`docs/rag-quality/near-dup-ingest-s1-v1.json`（LF）+ `.md`（≤40 行）

## §5 成本

**30 次外呼目标，36 封顶**（18 入库 + 12 查询）；0 迁移、0 契约、0 权限、0 新依赖；surefire 期望 ≤60 s，failsafe `.IT` 需显式 opt-in。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1 变体的「重编码」实现为 JPEG，而非无损 PNG 重编码**：PNG→PNG 是无损的，像素全同 ⇒ 图像 embedding 必然 `cos≈1` ⇒ 必然落带外（> 0.9944），花 3 次调用换一个已知答案。故 3 种变体取 `jpeg-95` / `jpeg-85` / `scale-95`，对应预注册的「原生分辨率重编码 / 轻度 JPEG 重压 / 整图微缩」三项语义。**这是对预注册的语义澄清，须写进锚的 `corpusRule.note`。**
- **D2 变形强度先定后测**：三个质量/缩放参数**先写死在锚里**，看到分数后不得回改参数重跑（那是挑样本）。
- **D3 若 6 个变体全部落带外**：如实判 `NO-RANK-CHANGE` + 记录构造失效，**不许**为了「有位移」追加调用换参数——追加即超帽，须新授权新卡。
- **D4 查询文本只取已提取文本**，不新增文本外呼；查询图像分量用 base 位图（不重新提取）。
- **D5 若 R2 逐图等式不成立**：立即 abort，判 `INCONCLUSIVE` 并把缺口落锚；**不许**「先跑完再解释」（卡 26 的死法）。

## §7 与卡 26/27/28 的关系

- 卡 26 建了装置、花 124 次撞上对账缺口，Owner 裁定 A′ 停跑；卡 27 把缺口归因为 `COUNTER-SUPPRESSED` 并给出正确分母；卡 28 用冻结语料做了零外呼 S0，判 `NO-RANK-CHANGE` 并诚实标注「真实语料无窗内共现」。
- 本卡是这条线的**最后一块**：把「没撞上」换成「撞上了会怎样」。撞上了没有位移 ⇒ 降权在整个生产参数域内对名次无效（**这是可外推的强结论**）；撞上了有位移 ⇒ 需要一个阈值/档位的新议题（另卡）。

## §8 判据修订记（M1 前代笔，2026-10-25）

- **D3 的 verdict 取值有缺陷（我的口径）**：D3 把「6 变体全落带外」判成 `NO-RANK-CHANGE`，但此时 `downWeightedCount == 0`——**降权一次都没发生**，名次面「无变化」是必然的，这个标签对任何落带外的语料恒真（与卡 28 R6 同类病：恒真标签没有鉴别力）。**正确取值应为 `INCONCLUSIVE`**（缺的证据 = 带内样本）；`NO-RANK-CHANGE` 必须额外要求 `downWeightedCount > 0`。
- **为什么不改锚**：锚由真跑写模式生成，改 `verdict` 只能重跑（再花 30 次外呼）或手改成品（违反「改推导源不改成品」，板规 21）。故 `verdict` 字段保留机械值 `NO-RANK-CHANGE`，**但锚 MD 已自带「构造失效面」限定与「该问题仍开放」声明**（`near-dup-ingest-s1-v1.md:7,9`），锚内 `downWeightedCount=0`、`windowCheck.entries=0` 与 `constructionFailure` 逐项在册 ⇒ 读者可自行证伪该标签。
- **后续卡片强制口径**：凡「降权消融」类判定，`verdict` 必须与 `downWeightedCount` 联动——`downWeightedCount == 0` 一律判 `INCONCLUSIVE`。本卡作为**历史例外**保留；任何引用本卡锚的地方必须同时引 `downWeightedCount=0`。
- **实测附带事实（供档位议题，不提阈值主张）**：三种质量型变形全部落 `SAME_IMAGE` 档（cos `0.997653–0.999956`，最低者 `p1-b1@scale-95`），而 S0 探针里**几何型**变形 `crop-04` 的 `cos = 0.9934` 恰在 `[0.982, 0.9944)` 带内（`near-dup-ingest-gate-probe-v1.md:38`）⇒ **该档的实际填充者是内容平移（裁切），不是质量退化（重压/微缩）**。这是 0.90/档位议题的新事实输入，本卡不据此提阈值主张。
