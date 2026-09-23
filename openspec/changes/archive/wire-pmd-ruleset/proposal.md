# 提案：把 PMD 那把尺子真正架上（wire-pmd-ruleset）

> 变更 ID：`wire-pmd-ruleset` ｜ 能力域：`static-analysis` ｜ 序列：`operationalize-harness-gates` 的后续提案（组 3 遗留②）
> 触发条件已成立：`docs/migration-runbook.md` §6.7 的到期条件第①条写明"下一次任何 Java 侧变更触碰 `pom.xml` 的 pmd 块或 `pmd-rules.xml` 时必须复跑并更新"，本提案即该次变更。
> 所有数字为本轮实测或 §6.7 已登记实测，非估算。

## Why

1. **仓库声明的那把尺子从未被加载。** 本轮实测 `grep -c ruleset pom.xml` = **0** —— `maven-pmd-plugin` 块里根本没有 `<rulesets>`，`src/main/resources/pmd-rules.xml` 是**孤儿配置文件**，存在但没有任何读者。摘掉 `<skip>` 后 PMD 会跑，但跑的是插件内置 `rulesets/java/quickstart.xml`：**122 条违规 / 9 个规则**，其中 6 个规则（`UnnecessaryFullyQualifiedName` 62、`UnusedPrivateMethod` 32、`CollapsibleIfStatements` 5、`UselessParentheses` 4、`UnnecessarySemicolon` 2、`UnnecessaryModifier` 1）**不在仓库声明的 28 条之内**。也就是说：一旦有人"顺手打开 PMD"，得到的结论与被审代码的约定无关。
2. **声明的 28 条里有 10 条在 PMD 7.9.0 不可解析。** 28 条去重 `ref` 与本地缓存 `pmd-java-7.9.0.jar` 内 7 个 `category/java/*.xml` 比对：9 条规则名已不存在（`codestyle/MultipleStringLiterals`、`codestyle/UnusedImports`、`bestpractices/MethodReturnsNewArray`、`security/HardCodedCrypto`、`security/InsecureCryptoWithIV`、`performance/SimplifyStartsWith`、`errorprone/AvoidCatchingGenericException`、`errorprone/DetectedEmptyClause`、`errorprone/CheckResultSet`，其中 2 条只是搬了分类），1 条是分类文件名笔误（`category/java/codestyle.java/LocalVariableNamingConventions` 应为 `codestyle.xml`）。**接上 `<rulesets>` 而不修这 10 条 = 直接构建硬错**，这就是为什么"接线"不能单独做。
3. **阈值口径仍是两处不相干的说法。** `pom.xml:443` 注释写"代码异味检测（阈值≤5）"，真读者却是 `:446` 的 `<failurePriority>4</failurePriority>`（优先级，与"条数"不可换算）。原第三处 `ci.yml` 的 `PMD_MAX_VIOLATIONS: 5` 已作为无读者的装饰阈值在上一提案中删除。结果：**没人知道 PMD 打开后"多少条算失败"**，而这正是它上次被关掉后又再没人打开的原因。
4. **SpotBugs 已经趟出可复制的路径，PMD 是同一类缺陷的另一半。** 上一提案（`operationalize-harness-gates` 组 3）把 `spotbugs` 从"skip + 无读者阈值"改成"`threshold=High` + 12 条存量基线台账 + 双射防呆 + 可红自测"，一次真实运行即可复现结论。PMD 现状与当时的 SpotBugs 完全同构，却没有同样处理——`docs/migration-runbook.md` §6.7 末尾的"对照记录"已把这半边的存在写明为不对称。
5. **豁免登记本身是有寿命的。** §6.7 用"临时跳过、后续启用"的话术登记在提交正文里活了 2 天，进被跟踪文档才 1 天。若无本提案，它会长期停留在"记着但没人接"的状态——这正是本系列提案要消灭的形态。

## 执行期实测补正（PMDC-P1 回传并经主 agent 复算，2026-09-21）

> 本节只改**事实表述**，不改本提案的范围与口径。凡与上文 Why/风险 冲突处，以本节为准。

1. **Why-2 的"10 条不可解析"成立，但另一半数字是错的。** `docs/migration-runbook.md` §6.7 理由 2 写"**19** 条可解析 / 10 条不可解析"——19+10=29 > 声明的 28 条，算术就漏了。本轮用 `pmd-java-7.9.0.jar` 内 7 个 `category/java/*.xml` 逐条比对 HEAD 版 `pmd-rules.xml` 的 28 条 `ref`：**18 条可解析 / 10 条不可解析**，且那 10 条与 §6.7 列出的清单一一对应（含 1 条 `.java` 文件名笔误）。
2. **失效的不止 `ref`，还有规则「属性名」——这是本提案起草时没料到的第二把锁。** `CognitiveComplexity`/`CyclomaticComplexity`/`NcssCount` 的 `classMax`/`methodMax`、`TooManyMethods` 的 `maxMethods`、`CommentSize` 的 `minLines` 在 7.9.0 全部不存在，只修 ref 时 `pmd:check` 依旧硬错、依旧产不出 `target/pmd.xml`。更阴的是**同一个 `<properties>` 块里首个错误会吞掉后续报文**：一次运行只报 5 条，逐条别名探针才逼出全部。→ 判别式更正："数日志里的错误行数 = 坏条数"不成立，必须逐条二分。
3. **处置后的尺子是 24 条，覆盖面变化如实登记**（删 4 / 换 3 / 仅改分类路径 2 / 文件名笔误 1）：
   - 删除项中 3 条在 7.9.0 **确已不存在且无同名搬家**（`MultipleStringLiterals`、`performance/SimplifyStartsWith`、`errorprone/DetectedEmptyClause`），1 条是**去重**（`codestyle/UnusedImports` 与已声明的 `UnnecessaryImport` 是 7.x 改名前后同名规则）。
   - **未擅自补** `codestyle/EmptyControlStatement`（`DetectedEmptyClause` 的意图近似者）——补它属于"换/加规则"而非"修失效引用"，越出 P1 授权，见 Q4。
   - `CognitiveComplexity` 的类级阈值 `classMax=75` 是**被平台逼着丢弃**的：7.9.0 该规则只剩 `reportLevel` 一个属性且只 visit 方法/构造子，类级无处可挂。方法级 `25` 原值保留。
4. **Why-3 的行号与"两处"表述都要改。** 真读者是 `pom.xml:451`（`:446` 是 `artifactId`）；接线后 `failurePriority` 在 `:458`、`<skip>` 在 `:464`。而且装饰性"条数阈值"其实有**第三处**：`src/main/resources/pmd-rules.xml:4` 的头部注释"阈值≤5 violations 通过"——组 1.1 给的普查面（`pom.xml scripts/ docs/`）按定义扫不到它。→ 验收第 3 条的普查面必须扩到 `src/main/resources/*.xml`，否则"全仓不存在第二处条数阈值表述"会假绿。
5. **"未知量级"这个风险已闭合，取值为 1329。** 声明尺子下的真实底数 = **1329 条**（priority：p1=39 / p3=1290 / p2=p4=0），24 条声明规则全部加载，其中 12 条有命中、12 条 0 命中；内置 quickstart 侧本轮**复现**为 122 条（与 §6.7 登记值一致，非沿用）。分布：86.4% 集中在 3 条规则（`OnlyOneReturn` 732、`CommentSize` 282、`AvoidCatchingGenericException` 134）。两个必须在登记处写明的口径：① PMD 默认源目录**不含 `src/test/java`**（241 个 main 文件，报告里 0 条来自测试代码），与 SpotBugs 那 12 条不是同一口径，不许拿它类比；② 基线取 1329 时，这道门禁短期只拦"新增第 4 种异味"——这正是「风险」第 1 条预告的形态，按 Q2 如实登记，不回头调松规则集。
6. **本提案的地基判别式已证实：** 注入不存在的规则名 → `mvn -o -B -ntp pmd:check` **exit 1 硬错**（`Unable to find referenced rule ...` + `The ruleset could not be loaded`，且日志里 `PMD Failure` 行数 = 0，即一条源码都没分析）。"失效规则静默隐身"的前提不成立，验收第 2 条已过。
7. **"无 Docker 机器"这个前提在本机是假的**（P2 实测纠正）：`docker info` → Server 29.6.2 / Docker Desktop，`mvn -o -B -ntp verify` 真起了 `mysql:8.0.36` 并交出新鲜 failsafe 结论。派发简报与「风险」末条按"failsafe 新鲜度=否"预期，实测为**是**；§6.2 那句"无 Docker 分支尚未在无 Docker 机器上实测"的悬置**不因本轮而闭合**（有 Docker 证明不了没 Docker 的行为）。
8. **新暴露一处"没有自动执行点"的口径，必须如实登记：** `maxAllowedViolations` 是**严格大于才红**（实测=登记 → 绿），所以"基线只许下调"**在构建层没有任何自动判别**——修掉一条异味后 `pmd:check` 依旧绿，只有 `pmd-baseline-check.sh`（登记 > 实测 → 红）会报，而它目前只挂在 `merge-gate` 里、靠人跑。这与当初 SpotBugs"记着但没人接"是同一种形态，处置见组 4 落地的 `[pmd-baseline]` 与「风险」新增条目：**不假装它自动执行**，而是把"跑一次全量聚合门禁"写成合入前置。


## What Changes

按"先接线、再修尺、后定口径、最后回接门禁"的依赖顺序：

1. **接上声明的尺子并让失效引用可见**：在 pmd 块加 `<rulesets>` 指向 `src/main/resources/pmd-rules.xml`；逐条处置 10 条不可解析 ref（改名 / 换 7.9.0 等价规则 / 删除，每条留理由），修正 `codestyle.java/` 笔误。**验收不是"能跑"，而是"每条声明的规则都真的在跑"**——要求一条规则引用失效时构建硬错，而不是静默跳过该规则。
2. **定阈值口径（本提案唯一需拍板项，见 Q1）**：推荐采用插件原生的 `maxAllowedViolations` 作为**唯一读者**，值由一次真实运行写入、**只许下调不许上调**（语义对齐 `scripts/test-baseline.txt`），并配一个"登记值高于当前实测即要求下调"的过期防呆脚本；删除 `pom.xml:443` 的"阈值≤5"注释或改为与真读者一致。
3. **建 PMD 存量基线台账与自测**：与 SpotBugs 侧同构（`scripts/tests/` 下），含"能变红"的反向判别。
4. **撤销豁免、回接门禁**：删除 pmd 块 `<skip>`；`ci.yml` 静态检查步骤把 `pmd:check` 加回并注明判定依据；`scripts/merge-gate.sh` 增加 `[pmd]` 子门禁与 `[pmd-baseline]` 存在性判别，自测同步扩展；`docs/migration-runbook.md` §6.7 由"豁免登记"改写为"已启用 + 基线指针"（保留历史措辞为对照，不抹除）。
5. **补一次真跑记录**：按 §6.7 到期条件③的要求，补"`mvn verify` 真跑 PMD"的实测记录到 runbook，注明机器与是否需 Docker。

## Impact

- **修改文件**：`pom.xml`（pmd 插件块 + 该行注释）、`src/main/resources/pmd-rules.xml`（10 条 ref 处置 + 1 处笔误）、`.github/workflows/ci.yml`（静态检查步骤）、`scripts/merge-gate.sh`、`scripts/tests/merge-gate-selftest.sh`、`docs/migration-runbook.md` §6.7；可能新增 `scripts/tests/pmd-baseline-check.sh` 与 `scripts/tests/pmd-violation-baseline.txt`。
- **不改**：任何 `src/main/**` 业务代码与 `src/test/**`（本提案不修 PMD 报出的具体异味，只把尺子与阈值定下来）、迁移链、`spotbugs-exclude.xml`、`check-test-baseline.sh` 与 Java 用例数（surefire/failsafe 计数必须不变）。
- **上游依赖**：`operationalize-harness-gates` 已合入（`merge-gate.sh`、`scripts/tests/` 家族与 §6.7 登记是其产物）。
- **已知风险量级**：接线后真实违规数**未知**（可能是 122 的数倍或零头，取决于那 28 条规则的严格度）。因此第 2 步的基线值**必须先测再定**，不许在测出来之前把 `<skip>` 摘掉——那会让 `mvn verify` 与 `merge-gate` 当场红死。
- **用户可见影响**：本提案完成后，Java 侧提交/合并会多一道会变红的静态检查；改 Java 代码的人会首次遇到"违反仓库声明的规则集"而被拦。

## 拍板记录

- **Q1（2026-09-21 用户认同推荐）= 选项 A**：`maxAllowedViolations` 作为**唯一条数读者**，值由接线后的**第一次真实运行**写入，之后**只许下调不许上调**；配 `scripts/tests/pmd-baseline-check.sh` 做过期校验（登记值 > 实测值 → 非零退出并要求下调）。理由：插件原生读者，且与 `test-baseline.txt` / `spotbugs-exclude.xml` 的既有 ratchet 惯例同构，不新增依赖。
  - 未变的硬约束：选项 B/C 不采用；`pom.xml:443` 那句与真读者不符的"阈值≤5"注释仍必须删除或改写（A 也要做）。
  - **执行顺序不因拍板而变**：组 1 先测底数 → 组 2 先证明"失效引用会硬错" → 组 3 才写基线值 → 组 4 才摘 `<skip>`。基线数字必须来自组 1 的真实运行，不许先拍一个"看起来合理"的值。
- **Q2（沿用 `harness-gates` R5 口径）**：不许为了把条数压低而回头调松规则集；若实测条数大到短期拦不住什么，如实登记并在汇报里写明，收紧留给后续按规则分片的独立提案。
- **Q4（P1 暴露，待 owner 点头，不阻塞组 3/4）**：尺子修好后仍有两处**覆盖面留白**，都不是"修失效引用"而是"要不要换/加规则"，故 P1 未擅动：
  1. 被删的 `errorprone/DetectedEmptyClause` 从未在任何 PMD 版本存在（名字本身是笔误级别的产物）；7.9.0 里承载同一意图的是 `codestyle/EmptyControlStatement`（实测**它住在 codestyle 而非 errorprone**）。补 = 覆盖面 +1 规则（本轮实测 0 命中，不会推高 1329）。**推荐补**，因为它才是不删该条时原作者想要的检查。
  2. `SimplifyStartsWith`（7.x 已移除）与 `MultipleStringLiterals`（7.0 移除）无等价物，接受覆盖面缩窄，不回补。
  → 未拍板前按现状（24 条）落基线；若采纳 1，只把基线重跑一次取新数，不改阈值口径。
  → **已拍板（2026-09-21 owner 采纳 1）**：补回 `codestyle/EmptyControlStatement`（24→25 条），实测 0 命中、底数不变，台账经 `--update` 重写一次，阈值口径未动；2 维持不回补。

## 风险

- **测出的基线过大导致门禁形同虚设**：若 `maxAllowedViolations` 落在几百条，这道门禁短期内拦不住什么。处置：如实写进登记与 runbook，并把"只许下调"作为硬约束交给后续每一次真实运行；不为了好看去偷偷加强规则集（那会把 `mvn verify` 弄成不可用的红）。
- **规则替换改变语义**：10 条失效 ref 若就近换成 7.9.0 的"看似同名"规则，可能悄悄放宽或收紧标准。处置：每条处置必须写明"删/换/改名"三选一与依据，且接线后跑一次**逐规则命中数**清单作为前后对照。
- **`mvn verify` 与 `[it]` 的耦合**：pmd 绑在 `verify` 阶段，`merge-gate.sh` 默认不跑 `[it]`。若 `[pmd]` 也挂在 verify 上，默认序列将不覆盖它——必须像 `[spotbugs]` 那样单独 `mvn -B -ntp pmd:check` 调用，不能指望 `[it]` 顺带。
- **无 Docker 机器上"真跑记录"的可得性**：§6.7 到期条件③要求补 `mvn verify` 真跑记录；无 Docker 时 failsafe 侧只跳不证。处置：记录里明确区分"PMD 真跑=是"与"failsafe 新鲜度=否"。（P2 实测更正：本机 Docker **在线**，本轮记录的新鲜度为"是"，见补正 7。）
- **"只许下调"缺少自动执行点**（P2 实测发现，见补正 8）：`pmd:check` 只在实测**严格大于**登记值时红，因此变好不会触发任何构建层信号；抓"该下调了"的只有 `pmd-baseline-check.sh`，而它的生效位置是**人跑的** `merge-gate`。处置：不假装自动执行——`openspec/git-workflow.md` 的合入前置写明"合入前必须跑一次 `bash scripts/merge-gate.sh`（含 `[pmd-baseline]`）"，并把这条口径连同边界语义写进台账头部，防止后来人以为 pom 那道墙会自己收紧。

## Non-Goals

- 不修 PMD 报出的任何具体异味（那是后续按规则分片的工作）。
- 不新增第三方静态分析工具，不换 checkstyle/PMD 选型，不引入 `p3c-pmd` 等外部规则集。
- 不动 SpotBugs 侧、checkstyle 侧、spotless 侧配置与基线。
- 不接远端、不改 CI 触发条件；不把 pmd 挂进前端 job。
- 不删除 `docs/migration-runbook.md` §6.7 的历史措辞（改写为"已启用"时保留对照段）。

## 验收

- **逐条判别式**（命令 + 实际输出，缺一不算完成）：
  1. `grep -c ruleset pom.xml` ≥ 1 且指向 `src/main/resources/pmd-rules.xml`；接线后一次真跑的**逐规则命中数**清单里，声明的每条规则要么有命中记录、要么显式为 0 命中，**不得出现"该规则未生效/被跳过"**。
  2. 失效引用可见性：临时把某条 ref 改成不存在的规则名 → `mvn -B -ntp pmd:check` **必须硬错**（而不是静默忽略），随后还原。
  3. 阈值唯一读者：Q1 选定口径在 pom 里可指到具体配置项；全仓 grep 不到第二处"条数阈值"表述；`pom.xml` 注释与真读者一致。**普查面必须覆盖 `pom.xml scripts/ docs/ openspec/ src/main/resources/*.xml`**——`src/main/resources/pmd-rules.xml:4` 的"阈值≤5 violations 通过"就是原普查面（只含前三者）漏掉的第三处，见补正 4。
  4. 基线 ratchet：登记值高于实测值时防呆脚本非零退出；且能证明"新增一条违规 → `pmd:check` 红"。
  5. 门禁回接：`bash scripts/merge-gate.sh` 含 `[pmd]` 且全绿；`ci.yml` 静态步骤含 `pmd:check` 并注明判定依据行号；§6.7 已改写为"已启用"；`mvn -B -ntp test` 计数与 `scripts/test-baseline.txt` 一致（不增减 Java 用例）。
- **Git 收尾**：分支 `feature/wire-pmd-ruleset`；提案三件套随首个提交入库；`bash scripts/merge-gate.sh` 全绿后 `--no-ff` 合入；汇报带 commit hash + 上述 5 条输出摘录 + 接线前后逐规则命中数对照；**不 push**。
