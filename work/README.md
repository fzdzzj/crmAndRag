# work/ 协作与任务流转规约

> 制定于 2026-10-14（主子 Agent 协作机制与文档治理优化）  
> 本文件为 `work/` 目录下的常驻指南，规范任务卡下发、执行交接与历史留痕归档。

---

## 一、目录结构与归档纪律

1. **当前在途区**：
   - 当前正在执行的单一任务卡：`work/task-card-<card-name>.md`
   - 当前正在执行的子 Agent 交接报告：`work/handoff-<card-name>.md`
   - `.gitignore` 仅豁免当前根目录下的 `task-card-*.md` 与 `handoff-*.md`，便于工作区视线聚焦。
2. **历史归档区**：
   - `work/archive/closed-cards/`：所有已完成 `--no-ff` 合入 master 的历史任务卡与交接报告，**必须在合入后移入此目录**，自动被 `work/*` 忽略，确保 `git status` 零污染。
3. **禁止行为**：
   - 禁止在 `work/` 根目录散落无前缀的临时文本、日志或未归档草稿；
   - 临时排查产物统一使用临时目录并在任务闭环后清理。

---

## 二、目标约束型任务卡标准模版（Task Card Template）

> **原则**：主 Agent 管目标、范围、禁区与验收标准；子 Agent 管具体编码实现。  
> **严禁反模式**：严禁在任务卡中编写字符级微操替换脚本或单行 PowerShell 字符串拼接。

```markdown
# 任务卡 <card-id>：<任务一句话简述>

## 1. 任务背景与目标
- **对应卡**：`openspec/changes/<card-id>/`（`proposal.md` + `tasks.md`）
- **变更档位**：A / B / C（依据 `docs/review-intensity-policy.md` 自 declare）
- **核心目标**：<明确指出需完成的业务或治理目标，2-3 句话>

## 2. 权威上下文与输入
- **必读规格**：`openspec/changes/<card-id>/{proposal.md,tasks.md}`
- **领域上下文**：<按需指定，如 docs/migration-runbook.md 或 frontend/AGENTS.md，杜绝无差别全量加载>

## 3. 边界与硬约束
- **代码范围**：仅允许修改 `<受影响模块/路径>`；他人工作树改动严禁碰触。
- **红线禁区**：
  1. 严格禁止未授权的 `git push`、`git reset` 或破坏性库操作；
  2. 权限常量与枚举必须复用现有定义（除非卡内有明确新增授权）；
  3. 冻结契约（contracts-frozen.md）未经解冻流程严禁修改；
  4. 保持分支在册，使用 `--no-ff` 合并。

## 4. 验收门禁要求
- **构建测试**：`mvn -B -ntp test` 必须全绿；
- **基线约定**：`scripts/test-baseline.txt` 遵循“只增不减”原则；
- **聚合门禁**：合并前真跑 `bash scripts/merge-gate.sh`（B/A档卡依据 runbook 决定 `--with-verify`）。

## 5. 产出与交接
- 产出交接快照：`work/handoff-<card-id>.md`（提交链、关键文件 diff、门禁实测输出、偏差披露）。
```

---

## 三、子 Agent 交接快照模版（Handoff Template）

子 Agent 完成任务后，统一输出至 `work/handoff-<card-id>.md`：

```markdown
# handoff — <card-id>

> 卡号：`openspec/changes/<card-id>/`
> 角色：<A/B/C 档执行子 agent>
> 完成时间：YYYY-MM-DD

## 1. 分支与提交
- 分支：`feature/<card-id>`（自 master@<commit-hash> 检出）
- 提交列表：
  | 序号 / 任务组 | 提交哈希 | 提交说明 |
  | --- | --- | --- |
  | 1 | <hash> | ... |

## 2. 关键文件变更清单
- 新增文件：...
- 修改文件：...
- 统计：X files changed, +Y / -Z

## 3. 门禁实测结论（客观命令输出）
- 单测：`mvn -B -ntp test`（Tests run=X, Failures=0, Errors=0, Skipped=0）
- 门禁脚本：`bash scripts/merge-gate.sh`（退出码 0，各子项 PASS）

## 4. 偏差披露（若无则填“无偏差”）
- 任何非预期的格式变动、环境兼容调整或实现微调在此如实列明。

## 5. 未决 / 待授权项
- 申请主 Agent 终审与 Owner 合并/推送授权。
```

---

## 四、分层上下文（Layered Context）指导

为避免子 Agent 冷启动上下文爆炸（数万 Token 浪费），按如下层级组装提示词：
- **L0 核心红线**（全员通用）：禁止未经授权 push、分支保留 `--no-ff`、结论必须有实测输出支撑。
- **L1 领域上下文**（按需指定）：改数据库看 `docs/migration-runbook.md`，改前端看 `frontend/AGENTS.md`，改评审看 `docs/review-intensity-policy.md`。
- **L2 任务差量**（当前卡独有）：本卡的 `proposal.md` + `tasks.md`。
- **L3 历史归档**（默认不读）：`docs/archive/` 与 `work/archive/` 仅供定向追溯，日常派发严禁作为必读前置。
