# 派发词 P0 · 探测包（组 1：定夺前实测，零文件写入）

你是 crmAndRag 仓的执行子 agent。本包**只测不改**，产出的是后续两个包（静态分析、前端测试接线）的决策依据。没拿到你的实测结论，那两个包不许开工。

## 必读（自包含，按序读完再动手）
- `openspec/changes/operationalize-harness-gates/proposal.md`（看 Why-2、Why-4 与 What Changes 第 2/4 条）
- `openspec/changes/operationalize-harness-gates/tasks.md` 的 **组 1（1.1-1.5）** ← 这是你的任务范围
- `openspec/changes/operationalize-harness-gates/specs/harness-gates/spec.md` 的 R1、R3

## 允许写的文件（只有这些）
- `work/mailbox/tasks/GATE-P0/report.md`（新建，写你的实测台账）
除此之外**不得创建、修改、删除任何文件**。

## 禁止
- 不得 `git add` / `git commit` / `git push` / `git checkout` / `git stash`（提交由主 agent 统一拍板）。
- 不得改 `pom.xml`、`ci.yml`、`frontend/package.json`、任何 Java/Vue 源码或测试。
- 不得访问外网、不得跑真外发模型调用（`RAG_BENCHMARK_REAL` 不许设置）；不得跑 54 条 RAG 基准。
- Bash 命令里**不得出现中文**（会直接 exit 127）；需要按中文串检索时用检索工具而非 shell。

## 任务
1. **先普查再下结论（硬要求）**：分别报告"仓内是否已有同职责实现"——
   - 静态分析侧：除 `checkstyle:check`/`spotbugs:check`/`pmd:check`/`spotless:check` 外，`pom.xml` 与 `scripts/` 里是否已有别的违规计数/阈值判定逻辑（给出文件:行号）。
   - 前端测试侧：除 `frontend/package.json` 现有脚本外，是否已有可执行 vitest 的入口（含 `frontend/vite.config.ts` 的 `test` 段、`frontend/tests/`、`docs/test-checkpoints.md` 的批量验证约定），给出文件:行号。
   结论若为"已有"，写明它为何没被自动执行，不要另建第二套。
2. **1.1** 跑 `mvn -B -ntp spotbugs:check`：记录退出码、是否被 configuration 级 `<skip>` 吸收、违规数、耗时。
3. **1.2** 跑 `mvn -B -ntp pmd:check`：同上，并给出报告产物路径。
4. **1.3** 在 `frontend/` 跑 `pnpm exec vitest run`：记录用例数 / 通过 / 失败清单 / 耗时；明确回答"能否在无浏览器、无后端、无 Docker 条件下独立跑通"（这决定它能否进 CI 的 ubuntu runner）。若 `node_modules` 缺失，先报告缺失，**不要自行 install**（装依赖需单独授权）。
5. **1.4** 跑 `git config --show-origin --get core.hooksPath` 与 `ls .git/hooks`，复验 proposal 的 Why-1 两条事实此刻是否仍成立（可能被 IDE 改写过）。
6. **1.5** 把 2-5 的**命令原文 + 退出码 + 关键输出摘录**写进 report.md，并给出两组明确建议：
   - 静态分析走「甲：去 `<skip>` 启用」还是「乙：登记豁免」，理由必须引用你 2/3 步的实测数字；
   - 前端单元轨是否可安全接入 CI 默认路径（若某些用例依赖网络/浏览器，点名它们）。

## 回传短包（≤15 行，禁止粘贴日志正文）
```
【回传】GATE-P0
状态：通过/失败/阻塞
spotbugs：退出<码>，<可否判定>，违规<数>
pmd：退出<码>，违规<数>，报告=<相对路径>
vitest：<用例>/<通过>/<失败>，<耗时>，可无网跑=是/否
既有实现：静态=<有/无>(路径:行) 前端=<有/无>(路径:行)
建议：静态=<甲|乙> 前端接CI=<可|不可+原因>
Why-1 复验：仍成立/已变化(<实际值>)
报告：work/mailbox/tasks/GATE-P0/report.md
遗留：<一句，或"无">
```
每条数字必须来自你本轮真实执行；跑不动的项目写"跑不动+原因"，不许估算。
