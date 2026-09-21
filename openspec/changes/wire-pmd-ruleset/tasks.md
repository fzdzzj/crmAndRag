# Tasks — wire-pmd-ruleset

> 执行契约见 `openspec/git-workflow.md`（分支 / 提交 / 合并序列、本机坑：无 remote 禁 push）。基线口径：本提案**不增减 Java 用例**，`scripts/test-baseline.txt` 的 surefire/failsafe 计数必须不变；变了即越界。
> **顺序修正（2026-09-21，派发前自查）**：原写"组 1 先测底数、组 2 再接线"是**跑不通的**——在 pom 接上 `<rulesets>` 并修好 10 条失效 ref 之前，`pmd:check` 只能跑到插件内置 quickstart（那不是仓库的尺子），或直接因不可解析引用而硬错。因此真实顺序是：**先接线修尺（组 2）→ 才测得到声明尺子的底数（组 1）→ 定基线（组 3）→ 撤销 skip 并回接门禁（组 4）**。下面两组的标题已按此重排，step 编号保持不变以便追溯。
> 另一个现实约束：组 1/组 2/组 3/组 4 **都要改 `pom.xml`**，因此本提案**不可并行派发**，只能串行分包。派发方案见 `dispatch/`。
> 每次只处理 1 个 step，做完立刻把 `[ ]` 改 `[x]` 并在行尾补实测证据摘要。

## 2. 接线与修尺（第一棒：让声明的尺子真的被加载；`<skip>` 保持在位）

开工前先做下面 1.1 的只读普查（零风险），再动本组。

- [ ] 2.1 先建"接线即硬错"的可见性判别（本组的安全网）：临时把任一 ref 改成不存在的规则名，跑 `mvn -o -B -ntp pmd:check` 确认**是硬错而不是静默跳过**，记录退出码与错误行，然后还原。**若这里是静默跳过，本提案后续全部暂停**——那意味着失效规则会长期隐身，先修可见性
- [ ] 2.2 逐条处置 9 条已不存在的规则名：每条明确写"删除 / 换成 7.9.0 等价规则（给规则名与分类） / 仅改分类路径"，并给一句依据；2 条"只是搬了分类"的按新路径改，不改语义
- [ ] 2.3 修 `category/java/codestyle.java/LocalVariableNamingConventions` 的文件名笔误为 `codestyle.xml`
- [ ] 2.4 在 `pom.xml` pmd 块加 `<rulesets>src/main/resources/pmd-rules.xml</rulesets>`，`mvn -o -B -ntp pmd:check` 能完整跑通且**逐规则命中数清单里每条声明的规则都出现**（0 命中也算出现；未出现=未加载）
- [ ] 2.5 把 2.4 的逐规则命中清单与组 1.3 的差异表一并归档，作为后续"规则被悄悄放宽/收紧"的对照基线

## 1. 声明尺子的真实底数（第二棒：必须在组 2 之后才测得到；step 编号保留以便追溯）

- [ ] 1.1 普查并回报"仓内是否已有同职责实现"：`grep -rn "maxAllowedViolations\|rulesets\|failurePriority" pom.xml scripts/ docs/` 的实际结果；确认 PMD 侧没有任何已存在的条数阈值读者（防重复造）。**本条在组 2 之前做**（只读、零风险）
- [ ] 1.2 在组 2.4 接线完成后、`<skip>` 仍在位的前提下，用一次临时旁路（`-Dpmd.skip=false` 抬不动字面量，只能临时注释该行并**立刻放回**）跑出**声明的 28 条尺子**下的真实违规分布：`mvn -o -B -ntp pmd:check`，记录总条数、按规则命中数、按 priority 分布
- [ ] 1.3 对照 §6.7 已登记的"内置 quickstart 122 条"结果，给出**两把尺子的差异表**（哪些规则只在一侧、条数差多少），结论写进行尾
- [ ] 1.4 把 1.2/1.3 产物落盘为可 diff 的文本（如 `target/pmd.xml` 摘要或 `work/` 下临时件），供组 3 写基线与组 6 写对照记录引用；确认结束时 `<skip>` 已原样放回
- [ ] 1.5 组 1 与组 2 合起来的净结果必须是：`pom.xml` 只多了 `<rulesets>`（+ 必要注释），`<skip>` 一字未动，违规数已有实测值 —— 交给组 3 定基线，不许在本组顺手摘 skip

## 3. 定阈值口径与存量基线（Q1 已裁定=选项 A）

- [ ] 3.1 按选项 A 落配置：pmd 块加 `<maxAllowedViolations>`，值取组 1.2 的**实测条数**（不许预估、不许沿用 122 那个内置 quickstart 的数），并加注释写明"值由一次真实运行写入、只许下调"
- [ ] 3.2 删除或改写 `pom.xml:443` 的"代码异味检测（阈值≤5）"注释，使其与真读者一致；全仓 grep 确认不存在第二处"条数阈值"表述
- [ ] 3.3 建 `scripts/tests/pmd-baseline-check.sh`：登记值 > 当前实测值时非零退出并提示下调（对齐 `spotbugs-exclude-staleness-check.sh` 的双射防呆思路），独立可跑、退出码有意义
- [ ] 3.4 基线条目只允许由真实运行写入：加 `--update` 类入口或明确"改值必须附一次运行输出"，不许手敲一个"看起来合理"的数字

## 4. 撤销豁免、回接门禁

- [ ] 4.1 删除 pmd 块的 `<skip>true</skip>`，`mvn -B -ntp pmd:check` 在基线内为绿
- [ ] 4.2 `ci.yml` 静态检查步骤把 `pmd:check` 加回，并按现有注释风格写明"判定依据=pom 第 N 行的 maxAllowedViolations/failurePriority + 基线指针"
- [ ] 4.3 `scripts/merge-gate.sh` 增加 `[pmd]` 子门禁（`mvn -B -ntp pmd:check`，**不依赖 `[it]`**，因 pmd 绑 verify 而默认序列不跑 verify）与 `[pmd-baseline]` 存在性/过期判别；`scripts/tests/merge-gate-selftest.sh` 同步补场景：`[pmd]` 失败→聚合非零且指名、基线过期→非零
- [ ] 4.4 **反向判别**：新增一条必然违反声明规则集的临时 Java 文件 → `mvn -B -ntp pmd:check` 红 → `bash scripts/merge-gate.sh` 非零且指名 `[pmd]` → 还原并复绿。红-proof 缺失则本组不算完成

## 5. 登记改写与真跑记录

- [ ] 5.1 `docs/migration-runbook.md` §6.7 由"已登记豁免"改写为"已启用 + 基线指针 + 阈值口径"，**保留原三条理由作历史对照**（标为"已于 <日期> 消除"），不抹除措辞
- [ ] 5.2 补到期条件③要求的"`mvn verify` 真跑 PMD"记录到 §6.7/§6.2 口径处，明确区分"PMD 真跑=是"与"failsafe 新鲜度=否（本机无 Docker）"
- [ ] 5.3 同步 `openspec/project.md` 与 `AGENTS.md` 的静态分析路由（只指路，不复制阈值数字）

## 6. 回归与 Git 收尾

- [ ] 6.1 `mvn -B -ntp test` 全绿且计数与 `scripts/test-baseline.txt` 一致（不增减 Java 用例）
- [ ] 6.2 三份自测全绿：`check-test-baseline-selftest.sh`、`merge-gate-selftest.sh`（含 4.3 新增场景）、`pmd-baseline-check.sh`
- [ ] 6.3 逐项复核 proposal.md「验收」5 条判别式，每条附命令与输出摘录
- [ ] 6.4 分支 `feature/wire-pmd-ruleset`，按任务组提交 `type(scope): 中文描述`（三件套随首提）；`git status` 干净（`work/`、`target/` 与并行 lane 的产物不提交不删除）后 `--no-ff` 合入；汇报带 commit hash + 接线前后逐规则命中数对照 + 5 条判别式摘录；**不 push**
