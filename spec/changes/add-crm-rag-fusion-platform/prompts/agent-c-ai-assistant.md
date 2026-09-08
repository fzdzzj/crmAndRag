# Agent-C 提示词 · AI 助手统一与精进（Wave 1 任务11先行 / Wave 2 任务10）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `specs/ai-assistant/spec-delta.md`、`tasks.json`（任务10、11）、`design-decisions.md`（D4/D10）、`agent-execution-plan.md`（§3 归属、§5 Flyway）。
> 源项目只读参考：CRM `D:\code\crm\back\crm-back\.worktrees\ai-createid`（`server/ai/*`、`service/impl/AiChatServiceImpl.java`、`AiMessageServiceImpl.java`、`PendingActionServiceImpl.java`、`controller/AiChatController.java`、`AI助手整合交接文档.md`）。

## 角色与目标
你是 **AI 助手 agent**。目标：精进 CRM AI 助手（限流/引用/洞察/评测），并接通知识库检索工具。任务 11 可先行（纯 CRM 内），任务 10 待 B 检索契约。

## 负责范围
tasks.json 任务 11（先行）、任务 10（依赖 B）。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-c-ai-assistant`
- 分支：`feature/lane-c-ai-assistant`
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成；`UserContext`/`ModelProvider`/SSE/`DataScope`/Token 契约已冻结。任务10 另需 B-ai 的检索契约（未就绪前用接口/mock）。

## 独占可改
`server.ai.**`、`AiChatServiceImpl`、`AiToolRegistry`、`ai_insight` 相关、Flyway `V4x__*`。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约、`com.slz.crm.knowledge.**`（B 的领地）。

## 要做（任务 11，先行）
1. `POST /ai/chat/stream` 落地按用户滑动窗口限流（内存实现），超限返回 `error` 事件 code=`RATE_LIMITED` 且不消耗 LLM；确认/取消不限流。
2. 只读工具结果实体汇总为 references，经 SSE `references` 事件结构化下发。
3. `ai_insight` 表 + 定时任务（基于统计/待办生成个人周报洞察，同周期幂等）+ `GET /ai/insights`；Flyway `V4x__*`。
4. 确定评测执行机制（真实调用/离线回放），接入黄金用例集回归。
5. 补齐确定性层单测（Validator/EntityResolver/PendingAction 状态机/限流器）。
## 要做（任务 10，待 B-ai 检索契约）
6. 新增只读工具 `queryKnowledgeBase`，接入 `AiToolRegistry` 与描述；面向 B 检索契约编程，B 未合入前用接口/mock。
7. 工具查询同时受 CRM 数据权限（`DataScope` 契约）+ 知识库授权约束。
8. 统一 Token 计量：`AiMessage.tokenCount` 接入 Token 计量契约。

## 关键坑
- SSE 事件契约与 B 共用，`thinking`/`references` 字段**不自改**（要改走契约申请）；关注 B-spike 对 `thinking` 的结论。
- 任务 10 强依赖 B，别在 B 未就绪时硬集成（先用 mock 打通工具选择/参数提取）。

## 注释重点（本 lane）
- **限流滑动窗口、生成中接管、草稿→确认状态机、洞察同周期幂等**属并发/状态逻辑，必须行内注释解释边界与时序。
- `references` 事件组装、Token 计量回写（用量缺失/为负回退 0）注释说明原因。
- `queryKnowledgeBase` 工具描述与"先查后答/来源引用"引导注释清楚（影响 LLM 工具选择）。
- Flyway `V4x`（`ai_insight`）头部注释写明字段含义与幂等键。

## 出口条件
限流/references/洞察/评测通过；`queryKnowledgeBase` 越权知识库不可检索、Token 计量落库。

## 产出
变更摘要 + 测试结果 + 契约/依赖变更申请（如有）。
