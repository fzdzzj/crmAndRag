# tasks — archive-closed-changes

## 任务组 1 · 前置实测与归档

- [x] 1.1 权威树自证：`git rev-parse --abbrev-ref HEAD` 在 `feature/archive-closed-changes`（自 master @ 05a0003 检出）；`git status --short` 净（仅 work/ 除外）
- [x] 1.2 前置实测：`ls openspec/changes/archive/` 确认无 resolve-dual-track-and-ledger / add-self-rag-reflection / expand-rag-benchmark-mismatch 同名目录；`git grep -n -E "openspec/changes/(add-self-rag-reflection|expand-rag-benchmark-mismatch|resolve-dual-track-and-ledger)" -- .` 确认命中**仅限三目录自身文件内部**（P-ai HANDOFF 自引用先例），tracked 其它文件零引用
- [x] 1.3 `git mv` ×3 至 `openspec/changes/archive/`，`git status --short` 确认 R（rename）记录；三件套复制入 `openspec/changes/archive-closed-changes/`
- [x] 1.4 门禁：`$env:DASHSCOPE_API_KEY=''` 后 `bash scripts/merge-gate.sh` 全 PASS（surefire 计数与 scripts/test-baseline.txt 一致、无新违规）

## 任务组 2 · 提交与停步

- [x] 2.1 单笔中文提交 `chore(openspec): 归档三张已闭合卡至 archive（P-ag/P-ah/P-ai，archive-closed-changes 任务 1.3-1.4）`；**git commit 单独执行并用 `2>&1 | Out-String` 包裹**（PS 链式命令坑）
- [x] 2.2 停步回报：原样粘贴 `git log --oneline --graph -6` / `git status --short` / `git show --stat HEAD` / merge-gate 八子门禁 raw 关键行 / 1.2 前置实测输出；不合并不 push；任何未实际执行的命令不得出现在回报中

## §0 执行记录

（执行方按任务组留痕：命令原样 + 输出关键行 + 时间）

### 2026-10-10 执行（权威树 D:\code\crmAndRag-merge-add-knowledge-admin-api）

- 1.1（23:24）：初始实测 `git rev-parse --abbrev-ref HEAD` → `master`、`git rev-parse --short HEAD` → `05a0003`、`git status --short` 仅 `?? work/`（净，仅 work/ 除外）；`git checkout -b feature/archive-closed-changes` 后复测 → `feature/archive-closed-changes` @ `05a0003`。
- 1.2（23:24）：
  - `ls openspec/changes/archive/ | wc -l` → `54`（归档前，与提案口径一致）；三同名 grep → `NO_COLLISION`（零撞名）。
  - `ls openspec/changes/` → `add-self-rag-reflection` / `archive` / `expand-rag-benchmark-mismatch` / `resolve-dual-track-and-ledger`。
  - `git grep -n -E "openspec/changes/(add-self-rag-reflection|expand-rag-benchmark-mismatch|resolve-dual-track-and-ledger)" -- .` → 全仓唯一命中 `openspec/changes/expand-rag-benchmark-mismatch/HANDOFF.md:11`（自引用，随目录迁移无害；tracked 其它文件零引用）。
- 1.3（23:25）：`git mv` ×3 → `git status --short` 共 10 条 `R ` 记录（add-self-rag-reflection 3 + expand-rag-benchmark-mismatch 4 + resolve-dual-track-and-ledger 3）+ 3 条 `A `（本卡三件套）；`ls openspec/changes/archive/ | wc -l` → `57`；changes/ 剩 `archive` / `archive-closed-changes`。三件套自主树 `work/tmp-archive-closed/` `cp` 而来，`file` 验 UTF-8 / LF（无 CRLF）。
- 1.4（23:26–23:32）：`env -u DASHSCOPE_API_KEY bash scripts/merge-gate.sh`（Git Bash 等价 `$env:DASHSCOPE_API_KEY=''` 清空语义，退出码 0）→ 八子门禁全 PASS，汇总行 `== merge-gate 通过：所有已执行的子门禁绿。==`：
  - `PASS [unit]`：`Tests run: 1089, Failures: 0, Errors: 0, Skipped: 0`（与 design.md「surefire 不变 1089」及基线一致）
  - `PASS [spotbugs]`：`BugInstance size is 0`
  - `PASS [pmd]`：`BUILD SUCCESS`
  - `[it]`：未执行（默认跳过，加 `--with-verify` 才跑）
  - `PASS [baseline]`：`surefire 测试：报告=184（默认口径），Tests run=1089，Failures=0，Errors=0，Skipped=0｜基线 reports>=184 tests>=1089 skipped<=0`；`failsafe 测试：报告=27（默认口径），Tests run=98，Failures=0，Errors=0，Skipped=5｜基线 reports>=27 tests>=98 skipped<=6`；`回归基线门禁通过`
  - `PASS [frontend-unit]`：`Test Files 17 passed (17)` / `Tests 179 passed (179)`
  - `PASS [hook]`：`提交期 pre-commit 转发器在位`
  - `PASS [bijection]`：`RESULT=BIJECTION_OK（12 条豁免与 12 条 High 一一对应）`
  - `PASS [pmd-baseline]`：`RESULT=PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）`
- 2.1（23:57）：中文提交信息写入仓库外临时文件后 `git commit -F` 单独执行（Git Bash 下等价满足卡面「单独执行、不链式」要求，并绕开中文命令行 127 坑）；提交对象 = 10 `R` + 3 `A`。后经 `--amend --no-edit` 修正本节时间戳（仍单笔，分支未 push）。
- 2.2（23:57）：`git log --oneline --graph -6` / `git status --short` / `git show --stat HEAD` 输出以紧随其后的停步回报为准（未 merge、未 push；本卡自身目录的归档留待后续收尾）。

## 边界与禁令

- 禁碰 src/、frontend/、pom.xml、scripts/、db/migration/、docs/rag-quality/（真跑锚点区）
- 本卡不含真跑节点（¥0）
- 本卡自身目录的归档留待后续收尾，不自行处理
