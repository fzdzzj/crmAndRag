# 派发词 — tighten-pmd-violations 分片 B（OnlyOneReturn，按模块一批一派）

> 本文件是给执行子 agent 的完整提示词。每派发一次执行**一个模块批次**，收尾后回传汇报；下一个 agent 用同一份派发词，从 tasks.md 2.1 批次表接续。分片 C（catch 收窄）不在此派发范围。

---

你是在 `d:\code\crmAndRag`（单仓 Maven 项目 `crm-platform`，master 直落、无 remote、不 push）里执行 PMD 存量收紧提案的分片 B 一个模块批次。授权来源：owner 已拍板（提案 `openspec/changes/tighten-pmd-violations/proposal.md`）。当前 PMD 基线 **1047**（台账 `scripts/tests/pmd-violation-baseline.txt`），其中 `OnlyOneReturn` **732** 处待清零。

## 第一步：按顺序读（读完再动手）

1. `AGENTS.md`（执行约束：建计划/禁预告句/结论须实测）
2. `openspec/changes/tighten-pmd-violations/proposal.md` + `tasks.md`（分片定义、批次表、勾选状态）
3. `scripts/tests/pmd-baseline-check.sh`（台账 --update 的行为：只写台账不改 pom；实测>登记拒绝写入）
4. `scripts/merge-gate.sh` 头部 30 行（子门禁清单）

本批模块 = **______**（派发者填：common / pojo / quality / knowledge / platform / server 之一；若是首派，先完成任务 2.1：从 `target/pmd.xml` 统计各模块 OnlyOneReturn 分布并写入 tasks.md 批次表，再做 common 批）。

## 第二步：修复（行为等价重构）

1. 从 `target/pmd.xml` 提取本模块 OnlyOneReturn 违规清单（文件:起始行）。PowerShell 统计示例（外层 shell 是 PowerShell，Unix 工具不可用）：
   `powershell: [xml]$x=Get-Content target/pmd.xml; $x.pmd.file.violation | Where-Object {$_.rule -eq 'OnlyOneReturn' -and $_.ParentNode.name -match 'com\\slz\\crm\\<模块>'} | ForEach-Object {"$($_.ParentNode.name):$($_.beginline)"}`
2. 逐文件把多 return 合并为单一出口：guard-clause 链改 if/else，或多分支结果变量 + 末尾单一 return。**铁律：不改任何可观察行为**（返回值、副作用顺序、异常路径、null 语义）；不动方法签名与 Javadoc 语义；`platform/contract` 下是冻结契约，record 方法的实现体可改、签名/字段绝不改。
3. 遇到无法等价合并的（try-with-resources 内 return、lambda/stream 内 return、状态机式早退等）→ **跳过并在汇报里列"未修清单+原因"**，不硬改。
4. 只修 OnlyOneReturn。看到其他规则违规（含 catch 通用异常——那是分片 C，前置 owner 拍板 Q6）一律不碰。

## 第三步：收尾链（顺序不可换，全部实测）

1. `mvn -B -ntp spotless:apply`（googleJavaFormat 会重排你的代码，必须跑）
2. `mvn -B -ntp pmd:check` → 记录总条数 前值→后值；确认本模块 OnlyOneReturn=0、下降值=本模块违规数。**若条数不降反升或冒出其他规则新违规，立即停下汇报现场，不许 --update。**
3. `& "D:\git\Git\bin\bash.exe" scripts/tests/pmd-baseline-check.sh --update`（PATH 里的 bash 指向损坏的 WSL，必须用绝对路径）
4. 把 `pom.xml` 里 `<maxAllowedViolations>` 改为台账新值（用 Edit 工具，grep -n 定位；两数必须相等）
5. `& "D:\git\Git\bin\bash.exe" scripts/merge-gate.sh` → 8 个子门禁全 PASS（输出大，从持久化 log 尾部抓 `PASS [` 行与总结行；[unit] surefire 计数必须不变）
6. `git checkout -- frontend/typed-router.d.ts`（vitest 行尾噪声，每次 merge-gate 都会产生）
7. 勾 `tasks.md` 本批行，行尾附实测证据（前值→后值、提交 hash）
8. 提交：提交信息含中文，**必须**写成文件再 `git commit -F`（Bash 命令行里直接写中文会 exit 127），信息文件放 `work/commit-msgs/`（不提交）。限路径 `git add`：本批改的 Java 文件 + `scripts/tests/pmd-violation-baseline.txt` + `pom.xml` + `tasks.md`。信息模板：
   `refactor(pmd): 分片 B-<模块>批收紧 OnlyOneReturn，基线 X→Y`（正文：本批 N 处合并、未修清单、merge-gate 8 PASS）

## 硬约束（违反即返工）

- surefire/failsafe 计数不变；台账只许下调；不 push；不动 `src/test/**`；不动迁移链与冻结契约签名。
- CRLF：Java/xml 是 CRLF 检出，一律用 Edit 工具改（禁 sed -i）；改完 `git diff --numstat` 确认无全文件重写。
- 结束 turn 前：计划清空、工作树干净（仅 `?? work/`）、汇报含（前值→后值 / 本批 N 处 / 未修清单 / 提交 hash / 下一批模块）。
- 任何一步红了修不动 → 停下写清现场（哪条命令、什么输出、卡在哪），不猜不改口径。
