# 任务卡 · near-dup-ingest-s1-window-cooccurrence（卡 31 · A 档 · 真跑）

## §1 目标（一句话）

**手工构造一批「窗内共现」的非退化近重复，让生产降权真正有机会搬动名次，然后如实记录搬没搬动。**

卡 28 的 S0 判 `NO-RANK-CHANGE` 但附了窗口算术：`gap=0.3856` ≫ 位移上限 `0.30×(1−w)≈0.15` ⇒ 真实语料里没有窗内共现，S0 的零位移**不能外推**。本卡把语料换成手工构造的窗内样本，回答「撞上了会怎样」。

## §2 背景（必读，决定你怎么避免卡 26 的死法）

- **卡 26 的死法**：48 图集真跑 **124 次外呼**，最后红在「过闸 41 vs 文本 embed 36」——钱花完了才发现对账不符，成因当时未定位，Owner 裁定停跑（`docs/rag-quality/near-dup-ingest-s1-not-run-v1.json`）。
- **已归因**：`COUNTER-SUPPRESSED`（`image-slice-ingest-chain-v1`）——链完好，是**测试侧缓存**抑制了计数，**分母必须是实测子块行数**，不是过闸图数。
- **本卡的解法**：预算硬帽 36 + **逐图 fail-fast**。每入库一张就校验等式，不成立立即停。最坏情况下你只花 3 次调用就知道这批语料走不通，而不是 124 次。
- **装置不用重造**：`NearDupIngestGateProbeIT` 已把「合成单图 PDF → 生产提取器 → VLM 描述 → 质量闸门 → 落盘 → 描述 embed + 图像 embed」跑通（6 图 6/6 过闸，18 外呼），`BudgetedTrackingProvider` / `MIN_EDGE_PX=64` / 合成 PDF 装配（该 IT `:749`）都可照抄结构。

## §3 写集（**只许写这 5 个文件**，无其他写入者）

| 路径 | 动作 |
| --- | --- |
| `src/test/java/com/slz/crm/quality/NearDupS1Corpus.java` | 新增：2 base × 3 变体（`jpeg-95`/`jpeg-85`/`scale-95`） |
| `src/test/java/com/slz/crm/quality/NearDupIngestS1IT.java` | 新增：真跑 IT（opt-in + 写锚） |
| `src/test/java/com/slz/crm/quality/NearDupIngestS1GuardTest.java` | 新增：零外呼守卫 |
| `docs/rag-quality/near-dup-ingest-s1-v1.json` | 新增（写模式生成） |
| `docs/rag-quality/near-dup-ingest-s1-v1.md` | 新增（≤40 行） |

## §4 禁改面（越界即判死）

- `src/main/**` 一行不改（B 跑用 `mockStatic(CALLS_REAL_METHODS)`，先例见卡 28）。
- **既有 8 锚零字节变动**：尤其 `near-dup-ingest-s1-not-run-v1.json`（卡 26 的负结论）与 `near-dup-ingest-gate-probe-v1.json`。
- **既有测试不得修改**：`NearDuplicateCorpus`（变体清单与顺序被 6 份冻结锚引用）、`NearDupIngestGateProbeIT`、`NearDupIngestGateProbeGuardTest`（它断言 `near-dup-ingest-cooccurrence-v1.json` 必须不存在——本卡锚换了新名字，负空间断言自然仍成立，**不要去改它**）。
- 不新增依赖、不改 `pom.xml`、不改 CI YAML。

## §5 成本铁律

- **目标 30 次外呼**（入库 6 图 × 3 = 18；查询 6 × 2 = 12），**硬帽 36**。
- 超帽由测试内 `BudgetedTrackingProvider` 抛错；**严禁**吞异常继续、严禁静默重试、严禁换图重跑。
- **查询向量只嵌一次、A/B 复用**（各嵌一遍是 24 次，总和 42 直接超帽）。
- 只看分数后回改变体参数 = 挑样本，**判死**。

## §6 验收（摘要，全文见 `openspec/changes/near-dup-ingest-s1-window-cooccurrence/tasks.md`）

1. 逐图等式：`提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片≥1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数`。
2. A/B 逐查询落 `candidates/cos/fusedA/fusedB/rankA/rankB`；`verdict ∈ {RANK-CHANGE, NO-RANK-CHANGE, INCONCLUSIVE}`，三值各有可达路径。
3. `NO-RANK-CHANGE` 必附 `windowCheck`（逐条 `gap` vs `0.30×(1−w)`）+ 不可外推声明。
4. 锚 LF、默认复跑逐字节一致（剔除 `runtime`）、`callLedger.total ≤ 36`。
5. 既有锚与既有测试零字节变动（`git diff master --numstat` 举证）。

## §7 停手条件（遇到就停，不许自行绕过）

- R2 等式不成立（含缓存抑制/闸门拒图/提取丢弃）⇒ **立即 abort**，判 `INCONCLUSIVE`，把缺口与已花调用写进锚。
- 变体短边 <64px 无法通过换参数救回 ⇒ 停手上报（不许弃图凑数）。
- 6 个变体全部落带外 ⇒ 如实判 `NO-RANK-CHANGE` + 记录构造失效，**不许**追加调用换参数。
- `mockStatic` 在本仓不可用（卡 28 D2 的预案）⇒ 停手上报，不得自行改 `src/main` 加开关。

## §8 回报（≤300 字，细节留本卡 §9）

提交 SHA / 改动文件数 / 实测外呼三分项与总数 / 6 变体 `bandSide` / `verdict` / 逐查询名次变化有无 / 未解决项 / 待决策项。

**「有位移 / 无位移」这类结论必须同回合附 `gap` 与 `windowCheck`；否则不许写。**

## §9 细节留档位

执行者在此节追加：语料参数与 base 选择实测、逐图对账表、逐查询候选与分数、abort/异常记录、任何偏离 proposal 的选择与理由。

### §9.1 执行留档（2026-10-25 执行子 agent）

**语料与 base 选择实测（零外呼本地钉死）**

- base（生成序前 2 个短边 ≥64px）：`nist-sp800-101r1-p1-b1`（490×225，页 1，页文本 214 字符）、`nist-sp800-101r1-p16-b2`（923×453，页 16，页文本 2538 字符）。
- 变体参数（先钉死后测分，D2）：jpeg-95=q0.95、jpeg-85=q0.85、scale-95=x0.95（两 base 的 0.95 落地边均 ≥64px，减缩梯子 0.96-0.98 未启用，`param` 落锚如实记 `q0.95/q0.85/x0.95`）。
- 查询（2 base × 3 形态，文本机械推导自该页 PDFBox 提取，零文本外呼）：单路断言 `ConstraintQuerySplitter.split(...).size()==1` 全过（英文文本不命中中文分隔符 ⇒ 每查询恰 1 次 embed）。

**逐图对账表（R2，6/6 全过，零 abort）**

| 变体 | 提取 | describe | 过闸 | 切片 | 子块行 | 文本embed | 图像embed | rowIndex |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| p1-b1@jpeg-95 | 1 | 1 | ✓ | 1 | 1 | 1 | 1 | null |
| p1-b1@jpeg-85 | 1 | 1 | ✓ | 1 | 1 | 1 | 1 | null |
| p1-b1@scale-95 | 1 | 1 | ✓ | 1 | 1 | 1 | 1 | null |
| p16-b2@jpeg-95 | 1 | 1 | ✓ | 1 | 1 | 1 | 1 | null |
| p16-b2@jpeg-85 | 1 | 1 | ✓ | 1 | 1 | 1 | 1 | null |
| p16-b2@scale-95 | 1 | 1 | ✓ | 1 | 1 | 1 | 1 | null |

分母全部为实测子块行数（生产 `chunkEmbedAndWrite` 返回行 vs provider 真调用点计数双侧对账，无推导量）。

**成本台账（计量点实测）**：vision 6 + embedImage 12（摄取 6 + 查询 6）+ embed 12（子块行 6 + 查询文本 6）= **30**（目标 30、硬帽 36 未触）；授权记录 `Owner 2026-10-25 报批 36` 落锚。

**bandSide 与 verdict（核心实测）**

- 6 变体对各自 base 的 3 查询 cos 恒等（同输入 embedImage 确定性）：p1-b1 jpeg-95 0.999956 / jpeg-85 0.999633 / scale-95 0.997653；p16-b2 jpeg-95 0.999501 / jpeg-85 0.999283 / scale-95 0.999794。
- **全部 `ABOVE`（SAME_IMAGE ≥0.9944）⇒ 0 次降权**：downWeightedCount=0、windowCheck.entries=0、名次面（anyImageRankChange=false）与集合面（全查询 chunkIdSetEqual=true）均无变化 ⇒ **verdict=NO-RANK-CHANGE + constructionFailure（D3 预注册路径：全部变体落带外，如实判、不追加调用换参数）**。无「有位移/无位移」结论可写——本跑未触及「撞上了」的窗口。
- 逐查询候选：30 条 IMAGE 候选（imageCandidateCount=30，即 6 查询 × 5 topK 全满），fusedA==fusedB 逐条相等（0 降权 ⇒ A/B 同构），rankA==rankB。

**真跑/复跑/守卫记录（日志留 %TEMP%，不入库）**

- 写锚真跑：%TEMP%\s1-write1.log（51.3s，`Tests run: 1, Failures: 0`，`[near-dup-s1] 锚已落盘 … externalCalls=30 verdict=NO-RANK-CHANGE`）。
- 默认复跑（opt-in 无 write）：%TEMP%\s1-replay1.log（`默认复跑逐字节一致 … 零外呼`，剔除 runtime 块逐字节比对）。
- 守卫（锚落盘后）：%TEMP%\s1-guard2.log（`Tests run: 5, Failures: 0`，锚对账分支激活）。

**偏离与选择记录**

- D1/D4 语义澄清按 proposal 落锚 `corpusRule.note`：三变体取 JPEG 重编码而非无损 PNG（无损必 cos≈1 白花调用）；查询文本取 base 所在页 PDFBox 提取（零外呼解读「已提取文本」），三形态推导规则全部机械冻结在 `NearDupS1Corpus` 并落锚 `corpusRule.queryRule`。
- scale 梯子方向定为「减缩」（0.95→0.98，只许少缩不许放大）：scaling 更小只会更低于 64px，唯一合规修参方向是少缩；本跑两 base 均未启用梯子。
- 守卫的「既有 8 锚 sha256 与 master 全等」拆两面：运行时面 = 锚内登记 sha vs 磁盘实算（守卫断言）；git 面 = `git diff master --numstat` 空输出（既有锚/测试零字节变动，本节留档举证）。
- 无 abort、无异常重试；修复次数 0/1（真跑一次全绿）。

**未解决项**：S1 的原始问题「撞上了会怎样」在本构造参数域内未被触及（三档变形强度均不足以把同源图推出 SAME_IMAGE 档，最接近者 0.997653 > 0.9944）。若要真观测窗内位移需更强变形（如 q70/更狠缩放/加噪），预计须新授权新卡。

**待决策项（Owner）**：是否立后续卡以更强变形构造带内样本（本卡 D3 禁止追加调用换参数，构造失效已如实落锚）。

---

## §10 M1 前代笔补记（主 agent，2026-10-25）

- **模型覆盖事实（复核 MINOR ①，采纳）**：真跑用的是 `qwen3-vl-plus`，不是生产默认 `qwen-vl-plus`——后者在本 key 返回 `model_not_found`，属**灰卡同款覆盖**（卡 26 的 S0 探针已登记同一现象）。该覆盖此前只在锚 `runtime` 块披露（`excludedFromReplay` 白名单，不参与字节复算），任务卡缺一行文字记录，今补齐。**运维含义**：这不是测试侧的临时手脚，而是「生产默认模型在本 key 不可用」的实测事实——生产若真按默认模型发描述请求，会直接失败，须另行处置（不属本卡范围）。
- **复核 MINOR ②（guard `windowCheck` 条目数用 `≥`、IT 侧用 `==`）：驳回，非缺陷**。守卫该断言的语义就写在它自己的消息里——「条目数 ≥ 降权事件数；**未入 topK 的事件也逐条在册**」，即守卫口径覆盖 IT 口径之外的候选，两者不是同一个集合，`≥` 是对的。另：本锚 `entries=0 / downWeightedCount=0`，两者都平凡成立，无鉴别力可言；真正的保护是锚逐字节复算 + `scoreFace.changed` 重算，不靠这一行。
- **verdict 口径修正（我方缺陷，已记 `proposal.md §8`）**：`downWeightedCount == 0` 时 `NO-RANK-CHANGE` 是恒真标签，正确取值应为 `INCONCLUSIVE`（缺带内样本）。锚不改（改则须重跑或手改成品），以 MD 自带限定 + 锚内计数自证；**后续引用本卡不得写成「降权无效」**，只有「这批构造没进档位」成立。
