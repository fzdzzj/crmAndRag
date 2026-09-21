# 派发词 P3 · 静态分析包（组 3 的构建配置侧）

**P0 已回传并通过主 agent 复验（`tasks.md` 组 1 已勾选，含实测数字）。方案已由主 agent 定死，你不再做选型判断，只做落地与实证。**

你是 crmAndRag 仓的执行子 agent。目标：消除"CI 声明要跑静态检查、构建配置却把它关掉"这一处自相矛盾，并让 SpotBugs 成为**对新缺陷立即变红、对存量缺陷有到期账目**的真门禁。

## 主 agent 裁定（照此执行，不要改口径）
- **SpotBugs = 甲（启用）**：去掉 `pom.xml:418` 的字面量 `<skip>true</skip>`，用插件原生的 `<excludeFilterFile>` 承载当前存量 High 基线。
- **PMD = 乙（继续关闭并登记豁免）**：`pom.xml:452` 的 `<skip>` 保持不动，豁免账目登记进 `docs/migration-runbook.md` §6；修 ruleset（10/28 条 ref 在 PMD 7.9.0 已不存在）**越出你的文件面**，只写进报告的遗留项。
- 已复验事实：`-Dspotbugs.skip=false` / `-Dpmd.skip=false` **抬不动**这两处字面量，唯一路径是改 pom——不要试图用命令行绕过或"验证是否真需要改 pom"。

## 必读（自包含）
- `openspec/changes/operationalize-harness-gates/proposal.md`（Why-2、What Changes 第 2 条、风险第 1/2 条、拍板记录）
- `openspec/changes/operationalize-harness-gates/tasks.md`：组 1 的已勾选实测行（你的依据）+ **组 3**
- `openspec/changes/operationalize-harness-gates/specs/harness-gates/spec.md` 的 **R1**（三要素：有触发点 / 阈值有唯一读者 / 豁免含项+理由+复测命令+到期条件）

## 你独占的文件
- `pom.xml`（**只许动 spotbugs 与 pmd 两个 plugin 块**：`:409-436` 与 `:439-` 段）
- `src/main/resources/spotbugs-exclude.xml`（**新建**，位置与既有 `src/main/resources/pmd-rules.xml` 同级同惯例）
- `docs/migration-runbook.md`（只在 §6 下追加 PMD 豁免条目，不改 §6.1-§6.6 既有正文）
- `work/mailbox/tasks/GATE-P3/report.md`（新建）

## 禁止
- 不得改 `pom.xml` 的 surefire / failsafe / checkstyle / spotless 段，不得改任何 `<version>`，不得新增依赖。
- 不得改 `.github/workflows/ci.yml`（env 阈值由 P4 处置）、`frontend/**`、`HANDOFF.md`、`openspec/**`、根 `AGENTS.md`、`scripts/**`。
- 不得为"让 SpotBugs 变绿"去改任何 Java 业务源码，也不得申请现在改（那属另一条线，写进遗留）。
- 不得访问外网（Maven 只用本地已有缓存；若需联网下载才可跑，状态写"阻塞"并停下）。
- 不得 `git commit` / `git push`。Bash 命令不得含中文（exit 127），按中文串检索用检索工具。

## 任务
1. **先普查（硬要求）**：`grep` 出 `pom.xml` 内所有 skip / failOnViolation / threshold / failurePriority 类配置及其行号，确认除你要动的两处之外没有别的静默开关；并确认仓内没有已存在的 spotbugs exclude 文件可复用。有则扩展，无则新建。
2. **重测并落盘 SpotBugs 真实存量（P0 的 10 条不能直接当基线）**：P0 是在缺 auxclasspath 的旁路条件下测的**下界**且 `target/` 无产物。你必须先跑一次能产出报告的真实运行（`mvn -B -ntp compile` 后跑 spotbugs，或该插件文档口径要求的等价步骤），把 `target/spotbugsXml.xml` 落盘，再从**这份产物**提取 High 条目作为 exclude 清单的唯一来源。报告里写清：命令、总条数、按 类/bug 类型 的分布、与 P0 的"10"是否一致（不一致要给差异）。
3. 写 `src/main/resources/spotbugs-exclude.xml`：逐条 `Match` 覆盖第 2 步实测出的 High 条目，**按"类 + bug 类型 + 方法/字段"最小粒度**写，不许用包级或通配大范围吞掉未来同类缺陷；文件头注释写明"由 operationalize-harness-gates 于 <日期> 从一次真实运行生成，只允许由该运行更新"（对齐仓内 `scripts/test-baseline.txt` 与 `KnownDriftRegistry` 的既有登记惯例）。
4. 在 `pom.xml` 的 spotbugs configuration 内加 `<excludeFilterFile>` 指向该文件，并删除 `:418` 的 `<skip>`。指明阈值读者是谁：`threshold=High` + exclude 文件（P4 会据此处置 ci.yml 的 `SPOTBUGS_MAX_HIGH` env，你不要动）。
5. **豁免防呆（对齐 KnownDriftRegistry 的先例）**：核验 exclude 文件里每一条在本次运行中**确实命中**一条真实 High；把"登记了但本次不再命中"的条目单列为**过期豁免待清理**写进报告。这是防止白名单静默膨胀的唯一手段，不许省略。
6. **PMD 走乙**：在 `docs/migration-runbook.md` §6 追加一条：项=`maven-pmd-plugin`；关闭理由=`pom.xml:452` 字面量 skip + ruleset 10/28 条 ref 在 PMD 7.9.0 不存在导致无产物（复测命令与你的实测日期一并写）；复测命令；到期触发条件（例如"下一次 Java 侧变更触碰 pom 或 ruleset 时必须复测"）。同时在报告里点明 `pom.xml:438` 注释"阈值≤5"、`:446` `failurePriority=4`、ci.yml `PMD_MAX_VIOLATIONS=5` 三处口径互不相干，供 P4 与你各自收口。
7. **回归**：跑 `mvn -B -ntp verify` 的静态相关部分（至少 `mvn -B -ntp spotbugs:check`）确认**启用后为绿**；再跑 `mvn -B -ntp test` 确认 surefire 计数与 `scripts/test-baseline.txt` 一致（本提案不许增减 Java 用例）、JaCoCo 报告照常产出（`test-hygiene` 曾遇 `@{argLine}` 静默失效坑）。
8. **判别式（缺一不算完成，必须能变红才算证明）**：
   - 正向：`mvn -B -ntp spotbugs:check` 退出 0，且日志不再出现 `Spotbugs plugin skipped`；
   - **反向必证**：临时引入一条会被 High 捕获且**不在** exclude 清单里的缺陷（例如一个明确的空指针解引用），确认 `spotbugs:check` **非零退出并指名该类**，然后还原该临时文件并确认 `git status` 回到开工基线（仅 `?? openspec/changes/operationalize-harness-gates/` 与 `?? work/`）。做不到反向证明就状态写"失败"，不许以"配置已打开"代替。
9. 边界自查：`git diff --stat` 只含 `pom.xml`、`src/main/resources/spotbugs-exclude.xml`、（走乙时）`docs/migration-runbook.md`。

## 回传短包（≤16 行）
```
【回传】GATE-P3
状态：通过/失败/阻塞
真实存量：命令=<…>，High=<数>（vs P0 报的 10：<一致|差<数>，原因<…>），产物=target/spotbugsXml.xml
exclude：条目<数>条，最小粒度=类+型+成员，通配<有/无>
防呆：本次未命中的过期豁免=<列出 或 无>
正向判别：spotbugs:check 退出<码>，无 skipped=<是/否>
反向判别：注入缺陷后退出<码>，指名的类=<…>，已还原=<是/否>
PMD 乙：已登记 runbook §<节>；三处口径矛盾已点明=<是/否>
mvn test：全绿=<是/否>，surefire=<实测> vs 基线=724，JaCoCo 产出=<是/否>
diff 范围合规：是/否
遗留：<一句，含 pmd-rules.xml 修复待另立项>
报告：work/mailbox/tasks/GATE-P3/report.md
```
