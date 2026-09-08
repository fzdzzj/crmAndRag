# 规范差异：ai-assistant（AI 助手：吸收 RAG 对话能力，唯一对话入口）

本文件包含对 `spec/specs/ai-assistant/spec.md` 的规范变更。
本能力域 = CRM 助手**吸收 RAG 对话能力**（思考模式 / 会话记忆 / 图文混合 / 流式接管），成为平台**唯一对话入口**；中和 CRM 源提案 `add-ai-assistant` 与 `enhance-ai-assistant`。
行为细节以实现依据见 `assistant-decision-tree.md`（请求树 §1 / 记忆树 §2 / 图片矩阵 §3 / 高亮契约 §4 / payload 契约 §6）。
对应决策：D4、D10、D11–D16。RAG 独立对话层（`chat_*`/`RagChatPipeline` 独立入口/匿名问答）丢弃，能力并入本域。

## ADDED 需求

### Requirement: AI 助手核心能力保留
系统 SHALL 保留 CRM AI 助手核心能力：SSE 流式对话、会话/消息管理、只读与草稿工具调用、草稿→确认两级执行（Human-in-the-Loop）状态机、SSE 心跳/超时、生成中新消息接管、部分回答保存、错误脱敏与 token 用量回写。

#### Scenario: 流式对话与工具调用
GIVEN 已登录用户发起自然语言请求
WHEN AI 助手处理
THEN 系统通过 SSE 增量返回回答
AND 按需调用只读工具或生成草稿，写操作走"草稿→确认"两级执行

#### Scenario: 确认执行错误脱敏
GIVEN 用户确认执行某草稿动作但执行失败
WHEN 系统处理失败
THEN 用户侧返回稳定提示（如"执行失败，请稍后重试"）
AND 持久化结果不含 SQL/类名/堆栈，完整异常仅写服务端日志

### Requirement: 知识库增强手动开关
系统 SHALL 由前端手动开关 `useKnowledgeBase` 控制是否检索知识库：ON 时每轮强制检索并注入上下文，OFF 时为纯助手（闲聊 + 业务工具 + 图片理解）；业务工具 MUST 仍由 LLM 自动调用，与该开关相互独立。

#### Scenario: KB 开启强制检索
GIVEN 请求 `useKnowledgeBase=true`
WHEN 助手处理该轮
THEN 系统每轮强制检索知识库并注入上下文后再作答

#### Scenario: KB 关闭纯助手
GIVEN 请求 `useKnowledgeBase=false`
WHEN 助手处理该轮
THEN 系统跳过知识库检索，直接进行闲聊/业务工具/图片理解

#### Scenario: 业务工具自动调用独立于开关
GIVEN 用户问题需要查询 CRM 业务数据
WHEN 助手处理（无论 `useKnowledgeBase` 取值）
THEN LLM 可自主调用只读业务工具
AND 工具触发不依赖知识库开关

### Requirement: 空匹配兜底修复
系统 MUST NOT 在 `useKnowledgeBase=false` 时返回"未检索到相关内容"兜底；KB 开启且零命中时 SHALL 注入未命中标记并诚实生成（或由 `strict-KB` 配置保留硬兜底）。

#### Scenario: KB 关闭不得返回未检索到
GIVEN 请求 `useKnowledgeBase=false` 且不带图片
WHEN 助手处理
THEN 系统进入正常生成（闲聊/工具）
AND MUST NOT 返回"未在已上传文档中检索到足够相关的内容"

#### Scenario: KB 开启零命中诚实生成
GIVEN 请求 `useKnowledgeBase=true` 但检索零命中且无图片
WHEN 助手处理
THEN 系统注入"(知识库未检索到相关内容)"标记并依诚实约束生成
AND 不硬编造文档内容

#### Scenario: strict-KB 硬兜底可配
GIVEN 动态配置开启 `strict-KB`
WHEN KB 开启且零命中
THEN 系统返回稳定的"未检索到"兜底文案

### Requirement: 思考模式
系统 SHALL 支持思考模式开关：开启时经 SSE `thinking` 事件透传思考块（前端折叠展示），写入记忆与历史的文本 MUST 恒定剥离思考块；关闭时为快速模式并剥离思考块。

#### Scenario: 思考模式透传
GIVEN 请求携带思考模式标志
WHEN 系统生成回答
THEN 思考块经 `thinking` 事件透传，正文经 `delta` 事件下发

#### Scenario: 写记忆前恒定剥离
GIVEN 回答含思考块
WHEN 系统写入 ai_message 或会话记忆
THEN 存储文本已剥离思考块
AND 避免思考块回灌导致提示词逐轮膨胀

### Requirement: 会话记忆与持久化
系统 SHALL 维护会话记忆（最近消息 / 历史摘要 / 已确认事实 / 当前意图）：`ai_message` 为唯一对话真相源，`recentMessages` 为其内存投影；`summary/facts/intent` MUST 持久化到 `ai_conversation_memory`（归属稳定 userId）；意图与摘要的 LLM 加工 MUST 走异步旁路（单飞去重 + 拒绝降级），不阻塞主问答。

#### Scenario: 记忆持久化与重启恢复
GIVEN 应用重启或用户请求落到另一实例
WHEN 助手装载会话记忆
THEN 系统从 `ai_message` 回灌最近消息、从 `ai_conversation_memory` 读取 summary/facts/intent
AND 记忆不因重启或换实例丢失

#### Scenario: 短问题规则改写
GIVEN 用户问题为指代/追问/短问题
WHEN 助手在检索前改写
THEN 系统按锚点优先级 intent > facts > 最近用户问 > summary 拼成自包含检索句
AND 该改写为纯规则、不调用模型

#### Scenario: 记忆旁路拒绝降级
GIVEN 记忆旁路线程池队列已满
WHEN 提交新的意图/摘要任务被拒绝
THEN 系统跳过本轮旁路任务（降级）并复位单飞标记
AND 不影响主问答流的正常返回

#### Scenario: 意图与摘要 Token 计量
GIVEN 意图提取或摘要压缩调用模型
WHEN 调用完成
THEN 系统记录其 token 消耗到统一计量（修复 RAG 现状 `chat(String)` 重载漏计）

### Requirement: 图文混合与图片解耦
系统 SHALL 解耦图片理解与知识库检索：图片理解文本（OCR/摘要/实体/问题聚焦）恒注入 prompt（与 KB 开关无关）；图片向量 MUST 仅在 `useKnowledgeBase=true` 时懒生成；MUST NOT 无差别回退最近图，改由 `imageRef` 显式引用；图片缓存 SHALL 分层（L1 按 hash 复用问题无关部分、L2 按 hash+问题 处理问题相关部分）并加每会话上限 + LRU。

#### Scenario: 不开 KB 也能看图
GIVEN 用户上传图片但 `useKnowledgeBase=false`
WHEN 助手处理
THEN 图片理解文本注入 prompt，用于闲聊或业务工具
AND 不生成图片向量、不触发图片路检索

#### Scenario: 同图换问题只复用问题无关部分
GIVEN 用户再次上传同一张图（hash 命中）但换了问题
WHEN 助手处理
THEN 系统复用 L1（OCR/摘要/实体）
AND 重算 L2（问题聚焦摘要，及 KB ON 时的图片向量）

#### Scenario: 纯文本追问不回退旧图
GIVEN 本轮不带图且未指定 `imageRef`
WHEN 助手处理
THEN 系统 MUST NOT 自动注入上一张图片上下文

### Requirement: 知识库检索工具与来源高亮
系统 SHALL 提供只读工具 `queryKnowledgeBase`，MUST 同时受 CRM 数据权限与知识库授权约束；SHALL 经 `sources` SSE 事件（检索后、答案前）下发来源，答案正文用内联 `[n]` 标记，`payload.citations` 记录实际引用编号；来源 MUST 含 `chunkIndex/pageNo/chunkId` 以供前端跳页高亮。

#### Scenario: 助手引用知识库作答
GIVEN 用户提问涉及知识库文档且 KB 开启
WHEN 助手调用 `queryKnowledgeBase`
THEN 系统返回授权范围内相关片段并先下发 `sources` 事件
AND 助手答案带内联 `[n]` 引用

#### Scenario: 越权知识库不可检索
GIVEN 用户对某知识库无访问授权
WHEN 助手尝试检索该知识库
THEN 系统不返回该知识库内容
AND 助手不能借此绕过知识库授权或 CRM 数据权限

#### Scenario: 来源高亮锚点与 citations
GIVEN 检索命中若干片段
WHEN 下发来源
THEN 每条来源含 `documentId/chunkIndex/pageNo/excerpt/score`
AND `payload.citations` 记录答案实际引用的来源编号，供前端只高亮承重来源

### Requirement: 流式接管与协作式中止
系统 SHALL 在嵌入前 / 检索后 / 重排后 / LLM 首包 / 每 delta 处设置 `shouldAbort` 检查点；同会话新请求到达时以"当前活跃生成被顶替"实现接管，旧任务在检查点退出并保存部分回答（`interrupted:true`）。

#### Scenario: 生成中新消息接管
GIVEN 助手正在生成回答
WHEN 用户在同一会话发送新消息
THEN 系统顶替当前活跃生成，旧任务在下一 `shouldAbort` 检查点退出
AND 保存旧流部分回答并向旧连接发 `stopped`，再按新消息重新生成

#### Scenario: 取消/超时/断连中止
GIVEN 用户取消、SSE 超时或客户端断连
WHEN 系统检测到
THEN 标记取消并在检查点协作式退出（不硬中断飞行中的模型/向量调用）
AND 下发 `cancelled` 并清理资源

### Requirement: 按用户限流
系统 SHALL 对 AI 流式对话入口按用户实施滑动窗口限流，超限 MUST 快速拒绝且不消耗 LLM 调用；确认/取消操作不受对话限流影响。

#### Scenario: 触发限流
GIVEN 某用户在 1 分钟内的 AI 请求数超过配置上限（默认 10 次/分钟）
WHEN 用户再次发起流式对话
THEN 系统返回 `error` 事件，code=`RATE_LIMITED`
AND 不调用 LLM、不产生 token 消耗

#### Scenario: 确认/取消不限流
GIVEN 用户对已有草稿执行确认或取消
WHEN 请求到达
THEN 系统不因对话限流而拒绝确认/取消操作

### Requirement: 结构化业务引用事件
系统 SHALL 将只读工具结果中的业务实体汇总为引用（references），并通过独立 SSE 事件结构化下发（type + id + name），不依赖 LLM 在文本中吐标记。

#### Scenario: 下发业务引用
GIVEN 只读工具返回了客户/合同/订单等实体
WHEN 助手组织回答
THEN 系统通过 `references` 事件下发 type + id + name 列表
AND 前端可据此渲染可点击跳转的引用条目

### Requirement: 主动洞察
系统 SHALL 支持基于业务统计与待办数据生成个人周期性洞察（业绩摘要、回款到期、商机停滞），并 SHALL 保证同周期幂等。

#### Scenario: 生成并拉取洞察
GIVEN 定时任务在某周期为用户生成洞察
WHEN 用户请求洞察列表
THEN 系统返回该周期洞察并支持标记已读
AND 同一周期重复触发不产生重复洞察

### Requirement: 统一 Token 计量
系统 SHALL 统一 AI 助手、知识库问答与记忆加工（意图/摘要）的 token 计量口径，按模型、用户、会话维度记录消耗。

#### Scenario: 计量落库
GIVEN 一次助手回答、知识库问答或记忆加工完成
WHEN 系统获得模型用量
THEN 系统按统一口径记录 token 消耗
AND 用量缺失/为空/为负时安全回退（记 0）而不失败

### Requirement: 模型 Provider 与动态提示词
系统 SHALL 使 AI 助手通过平台统一 `ModelProvider` 抽象调用大模型（默认 dashscope/qwen，可切 openai 兼容/vllm），并 SHALL 支持系统提示词与模型参数由超级管理员经动态配置运行期调整。

#### Scenario: 切换模型 Provider
GIVEN 平台配置或动态配置指定了模型 Provider 与模型名
WHEN AI 助手发起模型调用
THEN 系统使用指定的 Provider/模型（默认 dashscope）
AND 切换 Provider 不需要修改业务代码

#### Scenario: 提示词热更新生效
GIVEN 超级管理员经动态配置修改了助手系统提示词
WHEN 后续助手请求组装提示词
THEN 系统使用更新后的提示词，无需重启应用

### Requirement: ai_message 落库契约
系统 SHALL 将助手消息落 `ai_message`：`content` 为剥离 think 的正文；`payload` 按 `msgType`（text/chart/actionCard/draftProgress/system）承载结构化附加（references/sources/citations/interrupted/toolCalls/draft/thinking）；`tokenCount` 缺失或为负记 0。

#### Scenario: 助手答案落库
GIVEN 一次助手回答完成
WHEN 系统持久化
THEN `content` 存剥离 think 的正文，`payload.sources/citations` 存来源高亮锚点
AND `tokenCount` 取自统一计量（缺失记 0）

#### Scenario: 部分回答落库
GIVEN 回答被接管或取消
WHEN 系统持久化已有部分
THEN `payload.interrupted=true` 且 `content` 为已生成部分（think-stripped）

### Requirement: 安全护栏（后续迭代）
系统 SHOULD 为 AI 输入提供提示注入防护、越权防护与恶意输入边界，并 MAY 引入语义缓存占位以降低成本。

#### Scenario: 提示注入降级
GIVEN 用户输入或检索片段包含疑似提示注入
WHEN 系统组装提示词
THEN 系统对不可信内容进行隔离/包装或降级处理
AND 保留可审计的命中记录

---

## 备注

- 中和 CRM 源提案：`enhance-ai-assistant`（限流落地、references、洞察 `ai_insight`、评测执行、确定性层单测）、`add-ai-assistant`（工具链、安全/配额/预算/语义缓存后续迭代）。
- 对应决策 D4（Provider 抽象）、D10（提示词动态配置）、D11（吸收 RAG 对话能力）、D12（KB 手动开关）、D13（图片解耦）、D14（记忆持久化）、D15（来源高亮）、D16（空匹配修复）。
- 边界：**检索/嵌入/文档/图文双路召回/授权/存储/高亮锚点/意图类目在 `knowledge-rag`**（本域通过 `queryKnowledgeBase` 或 KB 管线调用）；**记忆旁路执行器/配额/Token 预算/traceId 在 `platform-governance`**；记忆持久化表 `ai_conversation_memory`、洞察表 `ai_insight` 见 `db-table-coordination.md`。
- 评测黄金用例集（原 19 条）沿用，执行机制（真实调用/离线回放）在实现阶段确认。
