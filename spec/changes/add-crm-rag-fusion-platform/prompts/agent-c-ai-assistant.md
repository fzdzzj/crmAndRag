# Agent-C 提示词 · AI 助手增强：吸收 RAG 对话能力（Wave 1 任务10先行 / 11 / 12）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `specs/ai-assistant/spec-delta.md`、**`assistant-decision-tree.md`（请求树§1/记忆树§2/图片矩阵§3/高亮§4/payload§6，是你的实现蓝图）**、`tasks.json`（任务 10、11、12）、`design-decisions.md`（D4/D10/D11–D16）、`agent-execution-plan.md`（§2 契约、§3 归属、§6 C 拆分）。
> 源项目只读参考：CRM `D:\code\crm\back\crm-back\.worktrees\ai-createid`（`server/ai/*`、`AiChatServiceImpl`、`AiMessageServiceImpl`、`PendingActionServiceImpl`、`AiChatController`、`AI助手整合交接文档.md`）；RAG `D:\code\rag\back\RAG`（`rag/service/rag/RagChatPipeline`、`RagStreamSessionManager`、`RagMemoryOrchestrator`、`ConversationMemoryService`、`RagContextAssembler`、`ImageConversationCacheService`、`chat/config/ChatConfig`——**移植其对话能力，不移植独立入口**）。

## 角色与目标
你是 **AI 助手增强 agent**。目标：让 CRM 助手**吸收 RAG 的对话能力**（思考模式/会话记忆/图文混合/流式接管），成为唯一对话入口；并按手动 KB 开关接通知识库检索、来源高亮、限流/洞察/评测。★RAG 独立对话层已丢弃，你把其**能力**并进助手管线。

## 负责范围
tasks.json 任务 10（吸收对话能力，先行）、11（KB开关/工具/高亮/空匹配修复/payload）、12（限流/references/洞察/评测/Token）。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-c-ai-assistant`
- 分支：`feature/lane-c-ai-assistant`；禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成；`UserContext`/`ModelProvider`/SSE/`DataScope`/Token/来源引用契约冻结。任务 10/11 的检索部分依赖 B 的检索契约（未合入前用接口/mock 打通）。

## 独占可改
`server.ai.**`、`AiChatServiceImpl`、`AiToolRegistry`、记忆（吸收 RAG）、`ai_conversation_memory`/`ai_insight`、Flyway `V4x`。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约（SSE 事件名/`SourceReference`/请求契约）、`com.slz.crm.knowledge.**`（B 领地）、`com.slz.crm.platform.**`（D 领地，记忆旁路执行器复用 D 的、不自建）。

## 要做（任务 10：吸收对话能力，先行）
1. **思考模式**：SSE `thinking` 事件透传（前端折叠）+ 写记忆/历史前**恒定剥离** think + prompt 预算闸门（`promptMaxChars`）。
2. **记忆持久化**：`ai_message` 为唯一真相源，`recentMessages` 改其内存投影（`restoreMemoryIfAbsent` 回灌）；`summary/facts/intent` 落新表 `ai_conversation_memory`（1:1 `ai_session`，乐观锁 `version`，归属 `userId`）；编写 `V4__ai_memory.sql`。
3. **记忆 9 套措施**接入（见决策树§2）：滑窗/溢出沉淀/摘要 LLM 重压缩/事实 top1 抽取/意图 LLM 抽取/规则改写(锚点 intent>facts>最近问>summary)/prompt 注入/回灌认领/TTL；意图摘要走 **D 的旁路执行器**（单飞+拒绝降级，不自建线程池）。
4. **图文解耦**（决策树§3）：图片理解文本(OCR/摘要/实体/问题聚焦)**恒注入** prompt（与 KB 开关无关）；图片向量**仅 KB ON 懒生成**；**取消无差别最近图回退**改 `imageRef` 显式引用；缓存分层 L1(hash)/L2(hash+问题)+每会话上限 LRU。
5. **流式接管**：`shouldAbort` 检查点埋在 嵌入前/检索后/重排后/LLM首包/每 delta；接管=活跃生成被顶替，旧任务检查点退出并存部分回答 `{interrupted:true}`；连接型零输出自动重试(≤2,指数退避)。

## 要做（任务 11：KB 开关/工具/高亮/修复）
6. 请求契约增 `useKnowledgeBase`(手动)/`thinking`/`imageRef`；ON=每轮强制检索注入，OFF=纯助手（闲聊+工具+图片理解）；业务工具仍 LLM 自动。
7. **空匹配兜底修复（D16）**：guard 加 `useKnowledgeBase &&` → **KB OFF 绝不返回"未检索到"**；KB ON 零命中改注入未命中标记+诚实生成；`strict-KB` 硬兜底由 DynamicConfig 可配。
8. 新增只读工具 `queryKnowledgeBase` 接 `AiToolRegistry`，受 **CRM 数据权限 + 知识库授权双约束**；面向 B 检索契约编程（未合入用 mock 打通工具选择/参数提取）。
9. **来源高亮（档 B）**：`sources` SSE 事件（检索后、答案前）+ 答案内联 `[n]` 标记 + `payload.citations`（实际引用编号）；来源含 `chunkIndex/pageNo/chunkId`（B 提供），供前端跳页高亮。
10. **ai_message 落库契约**（决策树§6）：`content`=剥离 think 正文；`payload` 按 `msgType`(text/chart/actionCard/draftProgress/system) 承载 references/sources/citations/interrupted/toolCalls/draft/thinking；`tokenCount` 缺失记 0。

## 要做（任务 12：限流/引用/洞察/评测/Token）
11. `POST /ai/chat/stream` 按用户滑动窗口限流（内存），超限返回 `error` code=`RATE_LIMITED` 且不消耗 LLM；确认/取消不限流。
12. 只读工具业务实体汇总 `references` SSE 事件（type+id+name）。
13. `ai_insight` 表 + 定时任务（周报洞察，同周期幂等）+ `GET /ai/insights`；Flyway `V4x`。
14. **统一 Token 计量**：`ai_message.tokenCount` + 知识库问答 + **意图/摘要（补 RAG `chat(String)` 漏计盲点，改用返回 usage 的重载）**。
15. 评测执行机制（真实调用/离线回放）+ 黄金用例集（19 条）；补齐确定性层单测（Validator/EntityResolver/PendingAction 状态机/限流器）。

## 关键坑
- **SSE 事件契约与 B/D 共用**：`thinking`/`sources`/`references` 字段不自改（走契约申请）；关注 B-spike 对 `thinking` 的结论。
- **记忆旁路执行器复用 D 的**，别自建线程池；意图/摘要是 LLM 调用，记得补 Token 计量。
- `content` **必须 think-stripped**（否则回灌 prompt 逐轮膨胀 → 网关断连，这是 RAG 踩过的坑）。
- **KB OFF 不得吐"未检索到"**（D16 负向场景，必测）。
- 任务 10/11 强依赖 B 检索契约，未就绪用 mock，别硬集成。

## 注释重点（本 lane）
- **并发/状态逻辑必须行内注释**：限流滑窗、生成中接管、`shouldAbort` 检查点、草稿→确认状态机、洞察同周期幂等、记忆旁路降级。
- 图片解耦/缓存分层/同图换问题只复用问题无关部分——注释说明原因。
- `payload` 契约、`citations` 用途、Token 计量回写（缺失/为负记 0）注释。
- Flyway `V4x`（`ai_conversation_memory`/`ai_insight`）头部注释写明字段含义、幂等键、乐观锁。

## 出口条件
思考/记忆持久化(重启恢复)/图文解耦/接管/KB开关/**空匹配修复(KB OFF 不吐兜底)**/高亮 sources+citations/限流/references/洞察/评测/Token 通过；`queryKnowledgeBase` 越权知识库不可检索。

## 产出
变更摘要 + 测试结果 + 契约/依赖变更申请（如有）。
