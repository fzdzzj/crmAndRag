# B0 Spring AI 可行性结论

## 结论
**总体 GO，但带运行时实现约束。** 思考流式、自定义思考参数、Qdrant 过滤与健康探测、视觉理解和向量维度均有可行路径；qwen3.8-flash 的思考流式不能只依赖默认 DashScopeChatModel 链路，B1 应按 provider 拆分：DashScope 原生可映射 enableThinking，qwen3.8-flash/compatible-mode 用 SSE 适配器。本结论已按 contracts-frozen.md §2/§4/§5 对齐。

## 1. 判定方法与样例设计
- 静态样例：直接构造 Spring AI/Alibaba 对象，反射调用内部请求转换和多模态转换，验证协议形态；Qdrant 用 Spring AI FilterExpressionBuilder 构造 AND/OR/NOT/IN 并交给转换器。
- 真实样例：用 WebClient 调 DashScope compatible-mode 的 chat/completions，请求 enable_thinking=true，统计 reasoning_content 增量；再用 DashScopeEmbeddingModel 请求 text-embedding-v3，检查实际向量维度。
- 新增防护：请求头设置 Accept: text/event-stream 和 X-DashScope-SSE: enable；响应按 ServerSentEvent<String> 解码；空帧和 [DONE] 不进 JSON 解析；业务 JSON 解析失败直接失败；只对 IOException、TimeoutException、connection reset/premature closed、stream disconnected before completion、error decoding response body 做 2 次指数退避重试。该策略在 spike/SpringAiRuntimeSpike.java 中实现。
- 本轮真实结果：compatible-mode 返回 53 个事件，其中 46 个含思考增量；text-embedding-v3 实际返回 1024 维；编译、静态样例、真实样例均 PASS。
- 另测 DashScopeChatModel.stream + qwen3.8-flash：返回 1 个空 generations 块，思考增量为 0。因此不能把 compatible-mode 的成功泛化到默认 DashScopeChatModel。

## 2. 五项结论
### 2.1 思考块流式透传：可行（需要 SSE 适配器）
- 证据：compatible-mode SSE 的 delta.reasoning_content 实际产出增量；Spring AI AssistantMessage 可通过 metadata.reasoningContent 承载思考与正文增量。
- 约束：DashScopeChatModel.stream 对 qwen3.8-flash 未在样例中返回思考增量；默认流内部仍使用 String 解码，是 stream disconnected/error decoding response body 的风险点。
- 降级/实现：B1 在 qwen3.8-flash 下用 compatible-mode SSE 适配器把 delta.reasoning_content 映射为 thinking 增量；正文 delta 和 thinking delta 分开发布到 SSE thinking/delta 事件。若该链路不可用，退化为快速模式并在 UI 明示无思考流。

### 2.2 自定义思考参数：可行（按 provider 拆分）
- 证据：DashScopeChatOptions.withEnableThinking(true) 经 createRequest 映射到 parameters.enableThinking；withIncrementalOutput(true) 映射到 parameters.incrementalOutput。compatible-mode 顶层 enable_thinking=true 也实测返回思考流。
- 约束：vLLM 的 chat_template_kwargs.enable_thinking 不被默认 ChatOptions 表达。
- 降级/实现：vLLM Provider 必须在请求装配层追加 chat_template_kwargs；不得把同一 options 直接复用到所有 provider。

### 2.3 Qdrant metadata filter：核心语义可行，类型边界必须收口
- 证据：Spring AI QdrantFilterExpressionConverter 对 OR(cat=contract OR cat=lead) 转换为 should=2；AND 组合下 must 条件可见；NOT 通过 mustNot 表达。Boolean EQ 实测抛出 Invalid value type for EQ. Can either be a string or Number；UUID 也应避免依赖。
- 结论：授权过滤（knowledgeBaseId、deleted 状态等）可用，但 metadata 值统一使用 String/Long/Integer；禁止 Boolean/UUID 作为等值过滤值。deleted 用 String("false"/"true") 或 Long(0/1)，入库与过滤同源。
- 当前工程注意：pom 刻意只引 io.qdrant:client，未引 Spring AI Qdrant starter；B1 的 VectorStore 抽象和实现要保留该回退设计。若选择引入 QdrantFilterExpressionConverter，需要走依赖变更申请。

### 2.4 视觉理解：可行
- 证据：Spring AI UserMessage 可携带 Media；DashScopeChatModel.convertMediaContent 将图片转换为 type=image 与 data:image/png;base64 URL，并保留文本内容。
- 实现建议：图片理解仍按 D13 解耦，理解文本恒注入 prompt；图片向量只在 KB ON 时懒生成。

### 2.5 向量维度统一值：1024
- 证据：text-embedding-v3 通过 DashScopeEmbeddingOptions.dimensions=1024 实测返回 1024 维。
- 结论：所有配置、Qdrant collection、内存回退校验统一使用 1024，禁止保留 2056/2560 默认值。

## 3. stream disconnected before completion 处理口径
- 优先使用 ServerSentEvent<String> 专用解码，不用裸 bodyToFlux(String.class) 直接解析 JSON。
- 网络中断允许有限重试，但必须只重试尚未确认的整段请求；已发出的 thinking/delta 不可静默重放，避免用户端重复正文。
- 业务 JSON 解析错误、HTTP 4xx/5xx 语义错误、鉴权失败不进入网络重试；应映射为 SSE error 事件并结束会话。
- 必须保留总时长/首字节/事件间隔超时和心跳；重试不能替代超时。

## 4. Qdrant 原生 gRPC 健康探测：可行
- 判定方法：使用现有 Qdrant Java client 1.13.0 的 gRPC 通道，不启用 HTTP 6333；通过 ManagedChannelBuilder 连接 6334，QdrantGrpc.newBlockingStub(channel).withDeadlineAfter(2s) 调用 qdrant.Qdrant/HealthCheck。样例见 spike/QdrantHealthSpike.java。
- 真实证据：一次性启动 Qdrant v1.19.1 后，6334 健康探测 PASS，响应 title=qdrant - vector search engine、version=1.19.1。说明 CrmVectorStoreHealth.probe() 可用原生 gRPC 健康接口实现，不需要为健康检查额外打开 HTTP 端口。
- 实现约束：probe() 只应做短超时健康判断；失败要区分 UNAVAILABLE/DEADLINE_EXCEEDED 与业务配置错误，并把结果交给 D 的 readiness 逻辑。inMemoryFallback() 为 true 时不做 Qdrant 探测。

## 5. 开工前置：必须先合并契约修正轮
- B-persist/B-ai 开工前 MUST 先执行 git merge spec/add-crm-rag-fusion-platform。截至本结论，feature/lane-b-knowledge 停在 e3f8230，spec 分支为 66a7e0a，领先 7 个提交；不合并会缺少 ModelCallOptions、ModelProviderImpl、ModelProviderProperties、CrmVectorStoreHealth、BypassTaskExecutor，且旧版 ModelProvider/AssistantChatRequest/SseEventName/PlatformErrorCode 无法支撑修正轮契约。
- 合并后调用方不得传 provider 专有 options（如 DashScopeChatOptions）；thinking/temperature/maxTokens/toolCallbacks/toolContext 一律通过 ModelCallOptions 下发，provider 差异和 Spring AI tool loop 由 ModelProviderImpl 翻译。
- 合并门禁：执行 mvn -q compile；通过后才允许开始 B-persist/B-ai 业务改动。
- 本次 B-spike 只保留 spike/ 样例与 B0-spike-结论.md，不代替 B-persist/B-ai 合并分支、不改动业务代码。

## 6. 广播与下一步
- B-ai：B1 按 provider 拆分请求装配；qwen3.8-flash 使用 compatible-mode SSE 适配器；DashScope 原生模型可使用 enableThinking 映射；thinking 只发 contracts-frozen.md §4 冻结的 {text,finished}。
- B-persist：向量维度和 Qdrant metadata 类型按本文收口；1024 作为唯一默认值；ID/类目用 String，布尔状态用 Long 0/1，禁 Boolean/UUID；Qdrant 实现并暴露 CrmVectorStoreHealth。
- C：SSE thinking 契约保留为流式增量事件；按 §4 发送 thinking/delta 分离；按 §5 实现事件 id、有界缓冲、Last-Event-ID 重放，且保证重放不重执行。
