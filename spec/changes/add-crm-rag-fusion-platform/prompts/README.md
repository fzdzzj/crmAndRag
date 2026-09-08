# 子 Agent 提示词目录（重定位版，18 任务 / 6 lane）

每个文件是**一个子 agent 的完整提示词**，可整份复制粘贴给对应 agent。
所有 agent 开工前必须先读 `_common-rules.md`（注释规范 + 工作纪律 + 冻结契约 + Flyway 号段）。
架构重定位（D11）：CRM 单体 + 助手吸收 RAG 对话能力 + 知识库能力移植；详见 `../agent-execution-plan.md`、`../assistant-decision-tree.md`。

## 分派顺序

1. **Agent-0 基座**（Wave 0，串行，最先）→ 完成后广播冻结契约、提交 base。
2. Wave 1 **并行**：`agent-a-datascope`(5,6) + `agent-b-spike`(B0) + `agent-c-ai-assistant`(先任务10) + `agent-d-governance`(先任务13骨架)。
3. B 线内部串行：`agent-b-spike` 出结论 → `agent-b-persist`(7,8) → `agent-b-ai`(9)。
4. C 线内部串行：任务 10 → 11 → 12（10/11 部分等 B 检索契约）。
5. Wave 2 收敛：`agent-e-dynamic-config`(16)。
6. **agent-integrator** 串行合并 A→B→C→D→E→任务 17/18。

## 文件清单与任务映射（重写后新任务号）

| 文件 | Agent | 波次 | tasks（新） | 旧任务号 |
|------|-------|------|-------|-------|
| `_common-rules.md` | 全体必读 | — | — | — |
| `agent-0-foundation.md` | 基座与契约 | Wave0 串行 | 1,2,3,4 | 1,2,3,4 |
| `agent-a-datascope.md` | 组织数据权限 | Wave1 | 5,6 | 5,6 |
| `agent-b-spike.md` | Spring AI 可行性验证 | Wave1 前置闸 | B0 | B0 |
| `agent-b-persist.md` | 知识库建表+移植+授权（含移除对话层） | Wave1 | 7,8 | 7持久化,8 |
| `agent-b-ai.md` | 按页分块+高亮锚点+意图CRM化+检索 | Wave1 | 9 | 7AI,9 |
| `agent-c-ai-assistant.md` | 助手吸收对话能力+KB开关+高亮+限流洞察 | Wave1/2 | 10,11,12 | 11,10 |
| `agent-d-governance.md` | 平台治理+Actuator健康指标 | Wave1/2 | 13,14,15 | 12,13,14 |
| `agent-e-dynamic-config.md` | 超管动态配置（含意图类目） | Wave2 | 16 | 15 |
| `agent-integrator.md` | 集成/质量基准/门禁 | Wave2/3 | 17,18 | 16,17 |

> ★各 `agent-*.md` 内文若仍写旧任务号/旧架构（"RAG 迁移"），以本表与 `../agent-execution-plan.md` §3 为准：B=知识库能力新建（非全量迁 RAG）、C=助手吸收对话能力、RAG 独立对话层丢弃。
> worktree 布局与创建命令见 `../agent-execution-plan.md` §11。
