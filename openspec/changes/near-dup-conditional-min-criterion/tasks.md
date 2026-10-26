# tasks · near-dup-conditional-min-criterion（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（命令 + 实测值 + 可 grep 的符号/字段名）。三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 / 决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束

- **零外呼**：不设 `*_REAL`、不启动 Spring、不调 provider；输入是既有锚 + 生产纯函数 + 源码字面。
- 只读输入：卡 32/33/34/35 四张锚 + 其余既有锚（**全部不可改**）。
- `src/main` / `pom.xml` / `.github/**` 零 diff；禁改 `NearDup*` 类只读；**不改任何生产常量**。
- 锚写模式 `-Dnear.dup.conditional.min.write=true`。

## A · 腿 A · 判据条件化（R1 / R2 / R3）

- [x] 1. 落 `criterion.branches` 三分支：bm25 全 0 ⇒ `r=0 ⟺ normVec=0`；全 1 ⇒ `r ≥ wb` 且 `r=0` 不可能；两分支非退化 ⇒ 原判据（特例）。每条给可执行判定式
      补勾依据：`grep -c '"predicate"' docs/rag-quality/near-dup-conditional-min-criterion-v1.json` → `criterion.branches` 3 条，predicate 分别含 `max(normBm25[q]) <= 0.0` / `== min` / `> min`；`mvn -o -B -ntp test -Dtest=NearDupConditionalMinCriterionGuardTest` Tests run: 1, Failures: 0。
- [x] 2. 断言三分支**互斥且穷尽**（用卡 35 锚 6 条查询的分支状态逐条归类，合计 = 6）
      补勾依据：`criterion.exhaustiveness` 实测 `queriesTotal=6`、`queriesCoveredSum=6`、`queriesByBranch` = B0 4 / B1 2 / B2 0（4+2+0=6）；守卫 `assertCriterion` 重算 `byBranch` 之和并断言等于 6。
- [x] 3. 落 `verdict`（四值）+ `verdictReason`；四值各写**可达路径**（板规 24），其中 `PROVEN`（无条件）须写明「可达但本语料未出现」
      补勾依据：`conditionalVerdict.verdict = PROVEN-CONDITIONAL`；`verdictReachability` 四键 PROVEN / PROVEN-CONDITIONAL / REFUTED / UNDETERMINED 各 >20 字；`unconditionalProvenNote` 含「可达但本语料未出现」；守卫 `assertReachability` 断言四键齐备。
- [x] 4. 逐 12 条事件判「所属分支 + 是否满足该分支充要条件」，落 `conditionalVerdict.rows`；断言 4 条零位移全落 bm25 全 0 桶且 `normVec = 0`
      补勾依据：`conditionalVerdict.rows` 长度 12（`grep -c '"branch"'` 于该块），`summary.zeroDisplacement=4`、`summary.zeroInB0=4`、`summary.satisfied=12`；守卫 `assertConditionalVerdict` 逐条按分支充要条件重算，绿。
- [x] 5. 反向断言（板规 30）：`PROVEN` 档文本不得含「末位 / 两分支同时」等未在本语料成立的措辞；`PROVEN-CONDITIONAL` 必须带 `applicabilityDomain`
      补勾依据：守卫 `assertReachability` 断言 `verdictReachability.PROVEN` 与 `unconditionalProvenNote` 不含「末位」「两分支同时」，且 `conditionalVerdict.applicabilityDomain` 同时含 `B0` 与 `B2`；两锚 grep 「末位」= 0 命中。

## B · 腿 B · 0.90 影响清单（R5）

- [x] 6. 逐张核对既有锚「是否把 `tier`/`band`/`bandSide` 分类结果写死」：给出**字段路径 + 值样例**；指不出的锚记 `UNDETERMINED`（D3）
      补勾依据：`impact.perAnchor` 16 行；`classificationFrozen=true` 7 张、`classificationFields[]` 合计 22 条（ingest-s1 5 / band-entry 6 / window-coupling 2 / oq1 3 / rerank 2 / fusion-impact 1 / ablation 3）；守卫 `assertImpactProvenance` 用 `resolvePath` 把每条 `path` 解析回源锚并比对 `sample`，16/16 通过。
- [x] 7. 逐张判「降门槛到 0.90 后是否自相矛盾」（若锚内冻了分类结果则为是），落 `impact.perAnchor`
      补勾依据：`summary.contradictionYes=9`、`contradictionNo=6`、`contradictionUndetermined=1`；逐行 `contradictionUnder090` 与 `contradictionCause` 取值落在守卫 `CONTRADICTIONS` / `KNOWN_CAUSES` 白名单内。
- [x] 8. 逐张判「重算是否可零外呼」：以「锚内是否冻了 `cos` 或向量」为依据；可零外呼者注明依据字段，不可者给出**最小外呼量 + 为什么非跑不可**
      补勾依据：`summary.zeroCallRecomputeYes=9`、`summary.minExternalCallsTotal=18`；`zeroCallBasis` 逐行指回 `cos` 路径；守卫 `containsCosPayload`（独立递归，不调生成器 helper）对 16 张源锚实算，与 `row.cosFrozen` 16/16 相等。
- [x] 9. 落 `reanchorPlan`：必须重锚的锚清单 + 重建方式 + 是否花钱 + 依赖顺序；并落「不受影响」清单（同样逐张核对）；**`executed = false`**
      补勾依据：`reanchorPlan.executed=false`、`orderedCount=9`、`unaffectedCount=6`、`unresolvedCount=1`；`order` 连续 1..9；`orderRule` 含「被引用者先」「重写」「重签」；守卫 `assertOrderFollowsShaReferences` 对 9 张两两实算 sha256 引用一致性，绿。实测签收锚 `near-dup-floor-signoff-v1` 排第 5 而非第 1——其 `signedDigests` 引用 exchange-rate / cross-matrix / displacement / fusion-impact 四张，且无任何锚引用签收锚的 sha256（`grep -c dbe79947…` 于 8 张锚 = 0），故顺序由引用关系现算。

## C · 锚与守卫（R4 / R7）

- [x] 10. 锚 `docs/rag-quality/near-dup-conditional-min-criterion-v1.json`（纯 LF）+ `.md`（≤40 行）；默认复跑逐字节一致（剔除顶层 `runtime`）
      补勾依据：md 实测 34 行（≤40）、单尾 LF；两锚 CR 计数 = 0；写模式后默认模式复跑 BUILD SUCCESS，raw sha256 前后一致：json `aacbc0e63f2bf2175e6ef1b0deb72201238ad586a1030fbd58ec58bb4bbec27a`、md `bdcf24ac5c0ed616a6dc48a1ed1c743964065f921c3671787b58c96e0c85d938`。
- [x] 11. 守卫：R1 三分支互斥穷尽、R2 四值可达路径、R3 逐条复判、R4 反向断言、R5/R6 清单逐字段可指回、上游四锚 SHA 实算、md ≤40 行、`executed=false`
      补勾依据：`NearDupConditionalMinCriterionGuardTest` Tests run: 1, Failures: 0；`upstreamAnchors` 实测 5 张（卡 32/33/34/35 + 签收锚，逐张 `sha256` 实算比对）；md 34 行；`reanchorPlan.executed=false`。
- [x] 12. 边界：`git diff master --stat -- src/main pom.xml .github` 空；既有锚零 `M`（只新增本卡 2 文件）；禁改类零变动；`git diff --check` 干净
      补勾依据：`git diff master --stat -- src/main pom.xml .github` 输出空；`git status --porcelain -- docs/rag-quality` 仅本卡两锚 `M`（既有 16 张源锚无 `M`）；`git diff --check` 退出码 0。
- [x] 13. 断言腿 B 清单**无推荐措辞**（`推荐` / `建议降` / `建议不降` / `应下调` / `recommend*`），双面 grep
      补勾依据：守卫 `FORBIDDEN_TENDENCY_WORDS` 对 `anchor.toString() + md` 全量断言；`Select-String -SimpleMatch` 两锚 6 词各 0 命中（`推荐=0 建议降=0 建议不降=0 应下调=0 recommend=0 Recommend=0`）。
- [-] 14. 若三分支出现第四种状态或某锚无法判定 ⇒ 记 `UNDETERMINED` 并写 `proposal §8`；**不许**放宽 R2 四值。未触发记 `[-]`
      补勾依据：第四种分支状态未出现（`criterion.branches` 恰 3 条、`queriesByBranch` 三键合计 6）；`near-dup-ingest-gate-probe-v1` 记 `UNDETERMINED`（`contradictionCause=AGGREGATE-ONLY`），但 `proposal.md` 不在本卡写集（只许 4 路径），§8 追记未发生 ⇒ 交 Owner 决定是否补记。
