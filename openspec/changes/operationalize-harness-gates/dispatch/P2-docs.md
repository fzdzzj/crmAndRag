# 派发词 P2 · 文档口径包（组 6 的 6.1 / 6.4 / 6.5部分 / 6.6）

你是 crmAndRag 仓的执行子 agent。目标：把"活文档把执行者路由到已经不存在的门禁 owner"这一类漂移一次收口。**这是文档任务，但验收是机器可判的 grep，不接受语义近似。**

## 必读（自包含）
- `openspec/changes/operationalize-harness-gates/proposal.md`（Why-5、What Changes 第 5 条）
- `openspec/changes/operationalize-harness-gates/tasks.md` 的 **组 6**，但**你只做 6.1、6.4、6.6，以及 6.5 中属于你的那半边**（6.2、6.3 归 P4，因为 `openspec/git-workflow.md` 不由你写）
- `openspec/changes/operationalize-harness-gates/specs/harness-gates/spec.md` 的 **R5**（注意最后那个"带日期且声明不作口径的快照引用被允许"场景——它是你的反向边界）

## 你独占的文件
- `HANDOFF.md`
- `openspec/project.md`
- `AGENTS.md`（**只改 Flyway 号段那一行**）
- `work/mailbox/tasks/GATE-P2/report.md`（新建）

## 禁止
- 不得改 `openspec/git-workflow.md`、`.github/workflows/ci.yml`、`pom.xml`、`frontend/**`（属其他包）。
- 不得改写**历史提案正文**里的旧数字：`openspec/changes/*/` 与 `openspec/changes/archive/` 下任何文件、`docs/harness-summary-2026-09-19.md`、`drift-disposition/tasks.md`、`add-frontend-ci/*`、`apply-permission-matrix/*` 等——那些是当时快照，保留原样。
- 不得动 `scripts/check-test-baseline.sh` 与 `scripts/test-baseline.txt`（权威源本身，任何"顺手改"都属越界）。
- 不得 `git commit` / `git push`；Bash 命令不得含中文（exit 127）。

## 权威事实（本轮实测，可直接引用）
- 阈值唯一持有者 = `scripts/test-baseline.txt`（第 1 行即"禁止手改"；当前 surefire 724 / failsafe 66，`source-revision=e2785b2`）。
- 判定入口 = `bash scripts/check-test-baseline.sh`（`:13` 重申不要手改，`:23` 才接受 `--update`，且含失败时拒写）。
- `ci.yml` 已不含任何硬编码阈值：`grep -c "check_baseline" .github/workflows/ci.yml` = 0。
- 正确口径的既有先例 = `docs/migration-runbook.md:72`（**照它的写法对齐，不要复制它的正文**）。
- Flyway 实测最高号：`ls src/main/resources/db/migration | sed 's/__.*//' | sort -V | tail -1` → `V28`（而 `AGENTS.md` 仍写 V27）。

## 任务
1. **先普查**：grep 出你这三份文件里所有"基线数字 / 权威指针 / 号段上界"的表述，逐条列 `文件:行号 + 现文 + 问题类型`；若发现清单外的同类漂移（例如别处还写着"以 ci.yml 为准"），一并列入并报告，不要漏。
2. `HANDOFF.md:12` 与 `openspec/project.md:42`：把"测试基线数字以 `.github/workflows/ci.yml` 为准（当前 surefire 657 / failsafe 13…）"整段换成指向 `scripts/test-baseline.txt` + `bash scripts/check-test-baseline.sh` 的引用式表述，**不内嵌任何具体数字**。
3. `AGENTS.md`：号段那一行改为给出实测命令、不写"当前最高 V27"这类上界数字。**只改这一行，正文其余部分一字不动。**
4. `openspec/project.md` 加一句历史口径声明：在途/已归档提案正文里的数字属当时快照，以 `scripts/test-baseline.txt` 与其 `git log -p` 为准。
5. **判别式实测**（贴进 report.md）：
   - `grep -n "check_baseline" HANDOFF.md openspec/project.md` → 0 命中；
   - 这两份 + `AGENTS.md` 内 grep 不到 `surefire <数字> / failsafe <数字>` 形式的验收口径数字；
   - `grep -n "V27" AGENTS.md` 的结果与你对该行的处置一致；
   - `bash scripts/check-test-baseline.sh` 的判定结论与你写进文档的指向一致（只读跑一次即可，不许 `--update`）。
6. 边界自查：`git diff --stat` 只应出现你独占的 3 个文件；`git diff` 里 `AGENTS.md` 的改动行数必须是 1。

## 回传短包（≤15 行）
```
【回传】GATE-P2
状态：通过/失败/阻塞
改了：HANDOFF.md / openspec/project.md / AGENTS.md（各<行数>行）
普查新增发现：<清单外的同类漂移，或"无">
判别式：check_baseline=0命中 / 数字内嵌=0 / AGENTS.md 改动=1行 / baseline 脚本结论=<一致|不一致>
git diff --stat 是否只含独占文件：是/否
遗留：<一句，或"无">
报告：work/mailbox/tasks/GATE-P2/report.md
```
