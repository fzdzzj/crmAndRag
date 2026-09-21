# 派发词 P1 · 钩子包（组 2：让提交期门禁真的会阻断，Q1 已裁定=选项 A）

你是 crmAndRag 仓的执行子 agent。目标：把"前端 pre-commit 从不执行"这一条修成可实证的阻断门禁。**方向已定死为选项 A，不要重新权衡、不要改 `core.hooksPath` 的值。**

## 必读（自包含）
- `openspec/changes/operationalize-harness-gates/proposal.md`（Why-1、What Changes 第 1 条、拍板记录 Q1）
- `openspec/changes/operationalize-harness-gates/tasks.md` 的 **组 2（2.1-2.5）** ← 你的任务范围
- `openspec/changes/operationalize-harness-gates/specs/harness-gates/spec.md` 的 **R2**（含全部 4 个场景，尤其"配置存在但未真正阻断"）

## 你独占的文件（其他包不会碰，你也不许碰别人的）
- `frontend/scripts/setup-hooks.mjs`
- `frontend/AGENTS.md`（**只改 Git Hook Policy / Commit Workflow 相关段**，其余段不动）
- `work/mailbox/tasks/GATE-P1/report.md`（新建）
- `<仓根>/.git/hooks/pre-commit`（新增转发器；该目录不入版本控制，属预期）

## 禁止
- 不得 `git commit` / `git push` / `git add`（判别式实测用临时提交后**还原**，不许留下暂存改动）。
- 不得删除、改写 `.git/hooks/post-commit`、`.git/hooks/post-checkout` 里 Qoder 遥测的内容与路径；不得改动 `frontend/.githooks/pre-commit` 的检查逻辑（它是被转发方）。
- 不得改 `pom.xml`、`.github/workflows/ci.yml`、`frontend/package.json`、HANDOFF/openspec/AGENTS.md（属其他包）。
- Bash 命令不得含中文（触发 exit 127）；按中文串检索用检索工具。

## 已实测前提（可直接依赖，别重复考证）
- `git config --show-origin --get core.hooksPath` = 写在 `.git/config` 的 `<仓根>\.git\hooks`；该目录内只有上述两个遥测钩子。
- `git worktree list` = 17 个，全是链接工作树（`.git` 指回 `.git/worktrees/<name>`，common dir = `.git`）→ 装一次即全部生效，仅全新 clone 需重装。
- `frontend/.githooks/pre-commit:9-12` 已自带"无 staged 前端文件即 exit 0"的短路，转发器不要重复实现这套判断。

## 任务
1. **先普查后写码（硬要求）**：报告仓内是否已有同职责的钩子安装/转发实现（至少查 `frontend/scripts/`、根 `scripts/`、`package.json` 的 `prepare`），给出文件:行号；若已有可复用点，扩展它而不是新写一份。
2. 扩展 `frontend/scripts/setup-hooks.mjs`：由"写 `core.hooksPath`"改为"解析当前生效 hooks 目录 → 在其中安装 `pre-commit` 转发器"。要求：幂等（重复跑结果一致）、不覆盖非本安装器产生的同名文件（存在则报错并说明如何处置，不静默改）、目标钩子缺失时静默跳过、**不硬编码 Qoder 钩子内的版本化绝对路径**。
3. 写转发器本体：定位仓根后 `exec` 调用 `frontend/.githooks/pre-commit`，并把其退出码原样传出（这是阻断能力的全部来源）。
4. 更新 `frontend/AGENTS.md` 的 Git Hook Policy：期望值改为"生效 hooks 目录内存在能追踪到 `frontend/.githooks/pre-commit` 的 `pre-commit`"，给出安装 / 复验 / 卸载三条命令与判别式；卸载不得连带删掉别人的钩子。
5. **判别式实测（本包的成立条件，缺此不算完成）**：
   - 造一个必定被 `pnpm precommit:check` 拒绝的临时前端改动（如带 lint 错误的 `frontend/src/` 文件）；
   - `git commit` → 必须非零退出且能看到 pre-commit 的检查输出；记录命令与退出码；
   - 还原临时改动，确认 `git status` 回到你开工前状态（**只还原你自己造的东西**）；
   - 再提交一次"无前端 staged 文件"的空验证，确认转发器不误伤后端提交。
   - 禁止用 `--no-verify` 绕过；若做不到阻断，状态写"失败"并给出根因，不要改口宣称可用。
6. 回传里必须回答："现在哪些门禁会自己变红、哪些仍靠人跑"（本包范围内一句结论即可）。

## 回传短包（≤15 行）
```
【回传】GATE-P1
状态：通过/失败/阻塞
改了：<文件清单，含新增转发器路径>
既有实现普查：<有/无>(路径:行) + 你复用了什么
阻断实测：commit 退出码=<码>，触发检查=<lint|type-check>
后端提交不受影响：是/否（证据一行）
幂等复跑：是/否
哪些门禁现在会自动红：<一句>
遗留：<一句，或"无">
报告：work/mailbox/tasks/GATE-P1/report.md
```
