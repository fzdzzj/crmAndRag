# 规范差异：ai-assistant（AI 助手统一与精进）

本文件包含对 `spec/specs/ai-assistant/spec.md` 的规范变更。
本能力域在保留 CRM AI 助手既有能力的基础上，中和 `add-ai-assistant` 与 `enhance-ai-assistant` 两个源提案的未完成项。

## ADDED 需求

### Requirement: AI 助手核心能力保留
系统 SHALL 保留 CRM AI 助手的核心能力：SSE 流式对话、会话/消息管理、只读与草稿工具调用、草稿→确认两级执行（Human-in-the-Loop）状态机、SSE 心跳/超时、生成中新消息接管、部分回答保存、错误脱敏与 token 用量回写。

#### Scenario: 流式对话与工具调用
GIVEN 已登录用户发起自然语言请求
WHEN AI 助手处理
THEN 系统通过 SSE 增量返回回答
AND 按需调用只读工具或生成草稿，写操作走“草稿→确认”两级执行

#### Scenario: 生成中新消息接管
GIVEN 助手正在生成回答
WHEN 用户在同一会话发送新消息
THEN 系统取消旧流、保存旧流已有部分回答
AND 旧连接收到 `stopped` 事件后按新消息重新生成

#### Scenario: 确认执行错误脱敏
GIVEN 用户确认执行某草稿动作但执行失败
WHEN 系统处理失败
THEN 用户侧返回稳定提示（如“执行失败，请稍后重试”）
AND 持久化结果不含 SQL/类名/堆栈，完整异常仅写服务端日志

### Requirement: 知识库检索工具
系统 SHALL 为 AI 助手提供只读的知识库检索工具，使助手能基于知识库文档作答并给出来源引用；该工具 MUST 同时受 CRM 数据权限与知识库授权约束。

#### Scenario: 助手引用知识库作答
GIVEN 用户提问涉及知识库文档内容
WHEN AI 助手调用知识库检索工具
THEN 系统返回授权范围内的相关片段
AND 助手回答附带来源引用

#### Scenario: 越权知识库不可检索
GIVEN 用户对某知识库无访问授权
WHEN 助手尝试检索该知识库
THEN 系统不返回该知识库内容
AND 助手不能借此绕过知识库授权

### Requirement: 按用户限流
系统 SHALL 对 AI 流式对话入口按用户实施滑动窗口限流，超限 MUST 快速拒绝且不消耗 LLM 调用。

#### Scenario: 触发限流
GIVEN 某用户在 1 分钟内的 AI 请求数超过配置上限（默认 10 次/分钟）
WHEN 用户再次发起流式对话
THEN 系统返回 `error` 事件，code=`RATE_LIMITED`
AND 不调用 LLM、不产生 token 消耗

#### Scenario: 确认/取消不限流
GIVEN 用户对已有草稿执行确认或取消
WHEN 请求到达
THEN 系统不因对话限流而拒绝确认/取消操作

### Requirement: 结构化引用事件
系统 SHALL 将只读工具结果中的业务实体汇总为引用（references），并通过独立 SSE 事件结构化下发，不依赖 LLM 在文本中吐标记。

#### Scenario: 下发引用
GIVEN 只读工具返回了客户/合同/订单等实体
WHEN 助手组织回答
THEN 系统通过 `references` 事件下发 type + id + name 列表
AND 前端可据此渲染可点击跳转的引用条目

### Requirement: 主动洞察
系统 SHALL 支持基于业务统计与待办数据生成个人周期性洞察（如业绩摘要、回款到期、商机停滞），并 SHALL 保证同周期幂等。

#### Scenario: 生成并拉取洞察
GIVEN 定时任务在某周期为用户生成洞察
WHEN 用户请求洞察列表
THEN 系统返回该周期洞察并支持标记已读
AND 同一周期重复触发不产生重复洞察

### Requirement: 统一 Token 计量
系统 SHALL 统一 AI 助手与知识库问答的 token 计量口径，按模型、用户、会话（及知识库）维度记录消耗。

#### Scenario: 计量落库
GIVEN 一次助手回答或知识库问答完成
WHEN 系统获得模型用量
THEN 系统按统一口径记录 token 消耗
AND 用量缺失/为空/为负时安全回退（如记 0）而不失败

### Requirement: 模型 Provider 与动态提示词
系统 SHALL 使 AI 助手通过平台统一的模型 Provider 抽象调用大模型（默认 dashscope/qwen，可切换 openai 兼容/vllm），并 SHALL 支持系统提示词与模型参数由超级管理员经动态配置运行期调整。

#### Scenario: 切换模型 Provider
GIVEN 平台配置或动态配置指定了模型 Provider 与模型名
WHEN AI 助手发起模型调用
THEN 系统使用指定的 Provider/模型（默认 dashscope）
AND 切换 Provider 不需要修改业务代码

#### Scenario: 提示词热更新生效
GIVEN 超级管理员经动态配置修改了助手系统提示词
WHEN 后续助手请求组装提示词
THEN 系统使用更新后的提示词
AND 无需重启应用

### Requirement: 安全护栏（后续迭代）
系统 SHOULD 为 AI 输入提供提示注入防护、越权防护与恶意输入边界，并 MAY 引入语义缓存占位以降低成本。

#### Scenario: 提示注入降级
GIVEN 用户输入或检索片段包含疑似提示注入
WHEN 系统组装提示词
THEN 系统对不可信内容进行隔离/包装或降级处理
AND 保留可审计的命中记录

---

## 备注

- 本能力域中和 CRM 源提案：
  - `enhance-ai-assistant`：限流落地、references、主动洞察（`ai_insight`）、评测执行机制、确定性层单测。
  - `add-ai-assistant`：工具链增强（已实现部分沿用）、安全/配额/预算/语义缓存（后续迭代）。
- 对应决策 D4（模型 Provider 抽象，默认 dashscope）与 D10（提示词/参数动态配置，见 `dynamic-config`）。
- 评测黄金用例集（原 19 条）沿用，执行机制（真实调用/离线回放）在实现阶段确认。
- Token 计量与配额/预算的平台级执行见 `platform-governance` 能力域。
