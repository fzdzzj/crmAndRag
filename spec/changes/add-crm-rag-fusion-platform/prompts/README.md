# 子 Agent 提示词目录

每个文件是**一个子 agent 的完整提示词**，可整份复制粘贴给对应 agent。
所有 agent 开工前必须先读 `_common-rules.md`（含注释规范与工作纪律）。

## 分派顺序

1. **Agent-0 基座**（Wave 0，串行，最先）→ 完成后广播冻结契约、提交 base。
2. Wave 1 **并行**：`agent-a-datascope` + `agent-b-spike` + `agent-c-ai-assistant`(任务11) + `agent-d-governance`(任务12骨架)。
3. B 线内部串行：`agent-b-spike` 出结论 → `agent-b-persist` → `agent-b-ai`。
4. Wave 2 收敛：`agent-e-dynamic-config`、`agent-c`(任务10)。
5. **agent-integrator** 串行合并 A→B→C→D→E→任务16/17。

## 文件清单

| 文件 | Agent | 波次 | tasks |
|------|-------|------|-------|
| `_common-rules.md` | 全体必读 | — | — |
| `agent-0-foundation.md` | 基座与契约 | Wave0 串行 | 1,2,3,4 |
| `agent-a-datascope.md` | 组织数据权限 | Wave1 | 5,6 |
| `agent-b-spike.md` | Spring AI 可行性验证 | Wave1 前置闸 | B0 |
| `agent-b-persist.md` | RAG 持久化+授权迁移 | Wave1 | 7持久化,8 |
| `agent-b-ai.md` | RAG 模型/检索/流式迁移 | Wave1 | 7AI,9 |
| `agent-c-ai-assistant.md` | AI 助手统一与精进 | Wave1/2 | 11,10 |
| `agent-d-governance.md` | 平台治理与硬化 | Wave1/2 | 12,13,14 |
| `agent-e-dynamic-config.md` | 超管动态配置 | Wave2 | 15 |
| `agent-integrator.md` | 集成/质量基准/门禁 | Wave2/3 | 16,17 |

> worktree 布局与创建命令见 `../agent-execution-plan.md` §11。
