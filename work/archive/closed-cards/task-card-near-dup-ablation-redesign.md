# 任务卡 · near-dup-ablation-redesign（卡 28）

> 权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`。分支 `feature/near-dup-ablation-redesign`（基点 = 当前 `master`）。
> 分档：**B 档**（复核对账式）。成本：**S0 零外呼**。
> 先读：`openspec/changes/near-dup-ablation-redesign/proposal.md`（R0–R6 / D1–D4）→ `.../tasks.md`（14 条）→ 本卡。

## §0 一句话

用 `mockStatic` 把「近重复降权」这一行变成可替换，**先在纯 JVM 上证明装置能测出位移（正对照必翻转）**，再在**冻结的真实语料**上跑 A/B，判定降权在真实检索顺序上到底动不动，并给出窗口算术。

## §1 写集（只许这 5 个新文件 + 1 处纯增）

| 文件 | 动作 |
| --- | --- |
| `src/test/java/com/slz/crm/knowledge/retrieval/NearDupAblationPositiveControlTest.java` | 新增 |
| `src/test/java/com/slz/crm/quality/NearDupAblationGuardTest.java` | 新增 |
| `src/test/java/com/slz/crm/quality/NearDupDisplacementEvidenceIT.java` | **纯增** 1 个 `@Test` + 把重放装配/循环抽成私有 helper（纯搬运，语义与断言不变） |
| `docs/rag-quality/near-dup-ablation-v1.json` | 新增（LF） |
| `docs/rag-quality/near-dup-ablation-v1.md` | 新增（≤40 行） |
| `openspec/changes/near-dup-ablation-redesign/{proposal,tasks}.md` | 已由主 agent 写就，**只许原样带进分支提交** |

**无其他写入者**：本卡写集内文件不与任何在途卡冲突（在途 0 张）。

## §2 硬边界

1. **`src/main` 零 diff**（`git diff master --stat -- src/main` 必须空）。
2. **零真实外呼**：不读 key、不构造真实 provider、不跑 opt-in；`externalCalls` 必须为 0 且由「未构造真实 provider」结构保证。
3. **既有锚零字节变动**：六锚 + 签收锚 + 入库面两锚 + 位移锚，SHA256 复算等值。
4. **`NearDupDisplacementEvidenceIT` 只许纯增**；允许把重放装配/循环抽成私有 helper 做**纯搬运**，不许改语义、不许改既有断言与写锚路径。抽取后其默认复跑**必须仍与磁盘锚逐字节一致**（改完必须仍绿）。
5. 不新增依赖、不改 pom、不加 `@Disabled`、不删既有断言。
6. 不得复用卡 26 的 48 图选集，不得下载新资产。

## §3 七个坑（前六个都踩过）

1. **`mockStatic` 必须 `CALLS_REAL_METHODS`**：`Mockito.mockStatic(ImageSimilarityGate.class, CALLS_REAL_METHODS)` 再 `when(...downWeightNearDuplicates(any())).thenAnswer(inv -> inv.getArgument(0))`。若用默认空 mock，`weightOfBand`/`tag` 之外的一切静态方法都会返回 null ⇒ A 跑也会被你打哑，A/B 变成「空 vs 空」，**必然假绿**。
2. **A/B 必须严格串行**：静态 mock 是进程级的。先跑完 A 并把结果落成不可变快照，再进 `try (MockedStatic ...)` 跑 B。不许交叉。
3. **B 跑「恒等」要验到分数字节相等**，不是验「没抛异常」。
4. **`downWeightNearDuplicates` 是幂等的**（已降权者带 `BAND_WEIGHT_METADATA_KEY`）⇒ 若你把 A 跑的结果对象又喂给 B 跑，第二次不会被再降权，看起来「两跑相同」是假的。**两跑必须各自从同一份原始候选重新开始**。
5. **分母不许推导**：`childRows` 数真实子块行（图片切片 + 文本块），`embedInvocations` 按未命中计。卡 26 的 `3×N` 公式**已作废**；用「融合分 ÷ w」反解出图像路分同样是推导值，**同样作废**（要分就实测，测不出就报 `INCONCLUSIVE`）。
6. **锚 JSON 必须纯 LF**：Windows + Jackson 默认缩进器会出 CRLF（`.gitattributes` 只强制 `*.json` 换行，不强制内容生成方式），用 `DefaultIndenter("  ", "\n")` 并断言 `!json.contains("\r")`。
7. **位移 IT 是 failsafe**：surefire 测试**不得**引用它；`mvn test` 阶段它不跑，别把「surefire 绿」当成「A/B 跑过」。A/B 的产物只能由 `mvn verify` 产生。

## §4 判定口径（写死，不许自创第四种）

| verdict | 条件 |
| --- | --- |
| `RANK-CHANGE` | 任一 `IMAGE` 候选 `fusedA != fusedB`（或名次变）或 `chunkIdSetEqual == false` |
| `NO-RANK-CHANGE` | `maxImageScoreDelta == 0` 且 `chunkIdSetEqual == true` ⇒ **必附窗口算术 + 不可外推声明** |
| `INCONCLUSIVE` | 正对照未翻转、或分母两数对不上且无法解释、或 B 跑隔离断言不过 |

## §5 验收（顺序不可颠倒）

1. `mvn -B -ntp test` 全绿，贴统计行（surefire 只增不减）。
2. `mvn -B -ntp verify`：位移 IT 复跑绿，**skipped 仍 16**（+1 用例合法，skipped 不得 +1）。
3. 变异三连红 → 还原 → 绿，留日志。
4. `git diff master -- src/main` 空；`git status` 只含写集；`git diff --check` 干净；临时文件已删。

## §6 回报（≤300 字，细节留本卡 §7）

提交 SHA / 改动文件数 / `mvn test` 统计行 / **正对照翻转与否** / **`childRows` 与 `embedInvocations` 两数** / **`maxImageScoreDelta` 与 `maxTextScoreDelta`** / `verdict` / `downWeightedCount` / 未解决项。

**「无位移 / 一致 / 全部」这类词，必须同回合附可复算数字清单；否则不许写。**

## §7 细节留档位

执行者把以下内容写在本节（追加，不改上文）：
- 正对照候选构造表（三候选的输入分、档位、两跑得分与名次）；
- 真语料逐查询 A/B 表（或指到锚内路径 + 行号）；
- 静态 mock 实际生效的证据（调用 1 次、返回恒等、A 跑带标记）；
- 任何偏离 proposal 的实现选择与理由。
## §7 执行者留档（2026-10-25，执行子 agent）

### 7.1 正对照候选构造表（surefire `NearDupAblationPositiveControlTest`，纯 JVM）

| 候选 | 输入分 s | 档位/带 | B 跑（不降权）融合分 | A 跑（降权）融合分 | B 名次 | A 名次 |
| --- | --- | --- | --- | --- | --- | --- |
| NEAR_HIGH 图像 | 1.0 | NEAR_DUPLICATE+NEAR_HIGH（w=0.75） | 0.3 | (1.0×0.75)×0.3=0.22499999999999998 | 2 | 3 |
| SAME_IMAGE 图像 | 0.98 | SAME_IMAGE | 0.294 | 0.294（逐字节不变） | 3 | 2 |
| 文本 | 0.5 | — | 0.35 | 0.35（逐字节不变） | 1 | 1 |

两跑图像候选顺序相反（B: NEAR_HIGH 先于 SAME_IMAGE；A: 反转）。翻转归因 = 降权损失 0.075 > B 跑领先 0.006；两跑融合分均以无容差 assertEquals 断言到乘法表达式逐字节（0.3s vs (s×0.75)×0.3）。窗口 0.30×(1−w) 三档 0.075/0.12/0.15 与融合影响锚 bounds 容差 1e-12 全过。

### 7.2 真语料 A/B 关键数（细节以锚内路径为准）

- 逐查询 chunkIdSetEqual 全 true；逐查询 IMAGE 候选数 = 2/5/4/2/4/3/3（queryIndex 0..6），合计 23。
- candidates[] 共 50 行；IMAGE 行 tier/band 分布：SAME_IMAGE×7（自切图，逐查询自匹配）、NEAR_DUPLICATE+NEAR_LOW×2（两张 NIST 封面互查）、无图像路候选（纯文本路描述块/正文块）×14。
- 降权事件 2 条（q0 IMAGE:9415 / q3 IMAGE:9400，w=0.5）：fusedA=0.76 / fusedB=0.82、rankA=rankB=2；其余 21 条 IMAGE 行与全部非 IMAGE 行两跑融合分逐字节相等。
- 窗口算术（windowCheck 逐事件）：gap=0.3856 > window=0.15（gapExceedsWindow=true），名次不变是窗口算术下的必然。
- 分母：childRows=50（7+43，逐条 chunkId）；embedInvocations=11（A/B 相等断言）；verdict=RANK-CHANGE（分数面）。
- 锚路径：docs/rag-quality/near-dup-ablation-v1.json（queries[].chunkIdSetEqual/imageCandidateCount、candidates[] 逐候选、windowCheck.entries[]、denominators、isolationEvidence）。

### 7.3 静态 mock 实际生效证据

1. 逐查询独立 mockStatic 作用域内 `gate.verify(times(1))`（14 次作用域全过）；捕获调用数 == 查询数 == 7（两跑各自）。
2. A 跑（CALLS_REAL_METHODS + `invocation.callRealMethod()`）出参带 BAND_WEIGHT_METADATA_KEY 恰 2 条、值 0.5，且被降权者分数 = 入参×w（融合分差 0.06 与 0.30×s×(1−w) 一致）。
3. B 跑返回与入参逐条 Double.compare==0（坑 3 验到分数，不是「没抛异常」）。
4. 两跑降权入参逐条 fusionKey + 分数 Double.compare 相等（重放确定性，坑 4 的「各自从原始候选重新开始」由全新装配 + 该断言共同保证）。
5. 非 IMAGE 来源两跑融合分逐字节相等（maxTextScoreDelta=0.0）+ embed 请求数两跑相等（11）⇒ 消融只动了降权一个方法。

### 7.4 实现选择与偏离 proposal 的说明

1. **A 跑观测方式**：mockStatic(CALLS_REAL_METHODS) 打开后仅对 downWeightNearDuplicates 做「捕获入参→callRealMethod→捕获返回」旁路观测。理由：R3 要求实测「A 跑返回带 BAND_WEIGHT_METADATA_KEY」，不拦截无法观测链内真实出参；callRealMethod 即真实生产方法（与默认 CALLS_REAL_METHODS 行为同机制），行为零改动由「位移锚 G1 复跑逐字节一致 + 消融锚逐字节复算一致」双重佐证。
2. **消融用例无 opt-in 门**（与同类 G1 的 opt-in-skip 不同）：验收要求 failsafe skipped 不得 +1 ⇒ 消融复跑必须默认真跑；零外呼由 StubEmbeddingProvider（embedImage/vision/chat 触发即抛）+ 锚冻结向量结构保证。
3. **隔离失败走硬断言红，不写 INCONCLUSIVE**：embed 数不等 / 非 IMAGE 分数不等 / 入参不等属于装置污染，产出绿锚比红更危险；INCONCLUSIVE 保留给 R1 明文的「正对照未翻转」。与任务卡 §4 表的第三行相比口径收紧，特此说明。
4. **锚增字段 imageDeltaSum**（逐行 delta 之和）：变异首轮实测暴露 max 聚合对「非最大行」盲区（两条对称 delta=0.06 行改其一行 max 不动），任一 fused 值手改必须可检出 ⇒ 加逐行和并由守卫重算（任务 11「与逐候选表自洽」的强化）。
5. **tier/band 为逐查询属性**：同一切片对不同查询 cos 不同 ⇒ 档位可不同（9400 在 q0 是 SAME_IMAGE、在 q3 是 NEAR_LOW）；首轮实现曾跨查询合并映射表导致表中出现矛盾组合（SAME_IMAGE 行挂 NEAR_LOW 带），已改为逐查询映射并重写锚。此坑不在任务卡七坑之列，属执行中新发现。
6. 纯搬运抽取的 helper 名单：`parseAnchorMaterial` / `assembleReplayService` / `replayAnchorQueries`（重建段、装配段、逐查询循环体原样移入，断言原样保留）；既有方法体仅改为调用 helper。
