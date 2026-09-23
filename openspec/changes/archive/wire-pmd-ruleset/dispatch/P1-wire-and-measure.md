# 派发词 PMDC-P1 · 接线修尺 + 真实底数（`wire-pmd-ruleset` 组 2 → 组 1）

你是 crmAndRag 仓的执行子 agent。目标：把仓库**已声明但从未被加载**的 PMD 规则集真正架上，并测出它在本代码库上的真实违规底数。**本包不摘 `<skip>`、不写阈值**——那是下一包的事。

## 必读（自包含，按序读完再动手）
- `openspec/changes/wire-pmd-ruleset/proposal.md`（Why-1/2/3、Impact 的"已知风险量级"、拍板记录 Q1=A）
- `openspec/changes/wire-pmd-ruleset/tasks.md`：**组 2（2.1-2.5）+ 组 1（1.1-1.5）** ← 你的全部任务，顺序就是 2.1 前先做 1.1 普查，然后 2.1→2.5，再 1.2→1.5
- `openspec/changes/wire-pmd-ruleset/specs/static-analysis/spec.md` 的 **R1、R2**
- `docs/migration-runbook.md` **§6.7**（PMD 豁免登记，含三条理由与已实测的"内置 quickstart 122 条"数据）

## 你独占的文件
- `pom.xml`（**只许**改 `maven-pmd-plugin` 块：加 `<rulesets>` 与必要注释；该块内的 `<skip>` 一字不许动）
- `src/main/resources/pmd-rules.xml`（10 条不可解析 ref 的处置 + 文件名笔误修正）
- `work/mailbox/tasks/PMDC-P1/report.md`（新建，你的实测台账）
- `work/mailbox/tasks/PMDC-P1/`（测量产物落盘处，如逐规则命中清单、两把尺子差异表）

## 禁止（越界即判失败）
- **不得删除或注释 `<skip>true</skip>`**（`pom.xml` 内 pmd 块那一处）。测量需要它不生效时，只能**临时**注释、跑完**立刻放回**，且收尾必须用 `git diff pom.xml` 自证 skip 未被改动。
- 不得写 `maxAllowedViolations` 或任何阈值数字（Q1 已定为选项 A，但值必须由本包实测交下一包写）。
- **不得为了让违规数变好看而删除规则、放宽 `failurePriority`、或排除源文件**。你想减条数的唯一合法手段是"某条 ref 在 7.9.0 确已不存在"，且必须逐条给依据。
- 不得改任何 `src/main/**`、`src/test/**`（本包不修异味）、不得改迁移链、`spotbugs-exclude.xml`、`scripts/**`、`docs/**`、`ci.yml`、`frontend/**`。
- 不得 `git add` / `git commit` / `git push` / `git checkout` / `git stash`（提交由主 agent 统一处理）。
- 不得访问外网：一律 `mvn -o`（离线）。若离线仓库缺依赖导致跑不动，状态写"阻塞"并停下报告，**不要联网下载**。
- Bash 命令里**不得出现中文或非 ASCII 字符**（会直接 exit 127，本仓已踩过 6 次）；要按中文串检索一律用检索工具而不是 shell grep。

## 任务（严格按序，一次只做一个 step）
1. **1.1 普查（只读）**：`grep -rn "maxAllowedViolations\|rulesets\|failurePriority" pom.xml scripts/ docs/` 记录实际命中；结论必须是明确一句"PMD 侧现有条数读者 = 无 / 有（路径:行）"。目的：防止另造第二套阈值。
2. **2.1 可见性安全网（本包最重要的一步）**：临时把 `pmd-rules.xml` 任一 `ref` 改成一个确定不存在的规则名，跑 `mvn -o -B -ntp pmd:check`，判定它是**硬错**还是**静默跳过该条继续**。记录命令、退出码、关键错误行，然后还原该 ref。
   - 若是**静默跳过** → 立刻停止本包，状态写"阻塞"，在报告里写清证据。这意味着"失效规则会长期隐身"，本提案后续步骤的前提不成立，须先修可见性。
3. **2.2 / 2.3 修尺**：逐条处置 §6.7 列出的 9 条已不存在规则名 + 1 条分类文件名笔误（`codestyle.java/` → `codestyle.xml`）。每条在报告里写一行：`原 ref → 删除 | 替换为 <新规则全名> | 仅改路径`，加一句依据。"只是搬了分类"的 2 条必须改路径**不得删除**（删了等于偷偷缩小覆盖面）。
4. **2.4 接线**：在 pmd 块加 `<rulesets>src/main/resources/pmd-rules.xml</rulesets>`，跑 `mvn -o -B -ntp pmd:check`（仍带着 skip 时用临时旁路，跑完放回），要求：
   - 产出 `target/pmd.xml`；
   - 给出**逐规则命中数清单**：`pmd-rules.xml` 里声明的**每一条**规则都要出现在清单里（0 命中也算出现），任何"声明了但清单里没有"的规则都要单独点名 —— 那等价于 R1 说的"孤儿声明"。
5. **1.2 / 1.3 底数与差异表**：记录总条数、按 priority 分布；与 §6.7 已登记的"内置 quickstart 122 条 / 9 规则（6 个不在声明内）"做对照，列出：只在内置侧出现的规则、只在声明侧出现的规则、两侧都有但条数差多少。
6. **2.5 / 1.4 落盘归档**：把逐规则命中清单 + 差异表写成可 diff 的文本存进 `work/mailbox/tasks/PMDC-P1/`，供下一包写基线与"接线前后对照"引用。产物必须含生成命令与日期。
7. **1.5 净结果自证**（贴进报告）：`git diff pom.xml` 只应出现 `<rulesets>` 与注释相关改动，**`<skip>true</skip>` 那行必须在 diff 里完全不出现**；`git diff --stat` 只含你独占的两个文件。

## 判别式（缺一不可，写进报告并回填短包）
- 2.1 的注入实验：退出码 + 是硬错还是静默跳过（**这一条决定本提案成不成立**）
- 2.4 的逐规则命中清单覆盖率：`声明 N 条 / 清单出现 M 条 / 缺失清单 = <列出或"无">`
- 1.2 的真实底数：总条数与 priority 分布（下一包的基线值只能来自这里）
- 1.3 的差异表结论一句话
- skip 未被改动的 `git diff` 证据

## 回传短包（≤16 行，禁止粘贴日志正文）
```
【回传】PMDC-P1
状态：通过/失败/阻塞
2.1 可见性：注入后退出<码>，硬错|静默跳过（若静默跳过=本包停，后续全暂停）
处置：删<数> 换<数> 改路径<数>，逐条依据在报告 §<n>
接线：rulesets=<已加/未加>，声明<N>/清单出现<M>/缺失=<列出|无>
底数：总违规<数>，priority 分布=<p1/p2/p3/p4>，产物=<相对路径>
对照 quickstart：仅内置有的规则=<数>，仅声明有的=<数>
skip 未改动：<git diff 证据一行>
改了哪些文件：<清单>
遗留：<一句，或"无">
报告：work/mailbox/tasks/PMDC-P1/report.md
```
所有数字必须来自你本轮真实执行；跑不动的项写"跑不动+原因"，任何估算或沿用文档旧数字（如 122）当作本轮实测都算作失败。
