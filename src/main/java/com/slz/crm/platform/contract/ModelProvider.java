package com.slz.crm.platform.contract;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import reactor.core.publisher.Flux;

/**
 * AI 模型层统一出口（跨 lane 冻结契约，D4）。
 *
 * <p>职责：把 dashscope / openai 兼容端点 / vllm 的差异封在实现里， Lane B（知识库嵌入/视觉）与 Lane C（助手对话/意图/摘要）只面向本接口编程， 切换
 * Provider 时不改业务代码。
 *
 * <p>★usage 硬约束：所有非流式调用 MUST 走 {@link #chat(Prompt)} / {@link #embed(EmbeddingRequest)} / {@link
 * #vision(Prompt)}，它们返回 {@link ModelCallResult}（强制携带 {@code Usage}）。 不得再回到"{@code chat(String)}
 * 只拿文本、token 全部漏计"的旧口径（design-decisions.md D4/D14）。 流式调用因 Spring AI 语义限制无法强制返回 usage，实现方 MUST 在
 * {@code done} 事件前 把最后一次可得的 usage 上报 {@link TokenUsageRecorder}（Lane C 消费，Lane D 聚合）。
 *
 * <p>线程安全：实现 MUST是无状态单例；并发调用不同 Prompt 相互独立。
 *
 * <p>修正轮2：新增 {@link ModelCallOptions} 重载，调用方传中立参数（thinking/temperature）， 实现翻译到当前 provider；无 options
 * 版保留为向后兼容。
 */
public interface ModelProvider {

  /**
   * @return Provider 标识：{@code dashscope}（默认）| {@code openai-compatible} | {@code vllm}
   */
  String provider();

  /**
   * 同步对话调用（带 usage 计量）。
   *
   * @param prompt 完整 Prompt（含系统提示词/历史/工具定义）
   * @return 模型输出文本 + usage；调用失败抛业务异常而非返回 null
   */
  ModelCallResult<String> chat(Prompt prompt);

  /**
   * 带调用选项的同步对话调用（修正轮2：thinking/temperature/maxTokens 由调用方显式传入）。
   *
   * @param prompt 完整 Prompt
   * @param options 中立调用选项（null 等效 {@link #chat(Prompt)}）
   * @return 模型输出文本 + usage
   */
  default ModelCallResult<String> chat(Prompt prompt, ModelCallOptions options) {
    return chat(prompt);
  }

  /**
   * 流式对话调用（SSE 用）。
   *
   * <p>调用方负责在流结束时把 usage 上报 {@link TokenUsageRecorder}，并遵守 SSE 事件契约 （{@code delta} 增量 / {@code
   * done} 终态）。
   *
   * @param prompt 完整 Prompt
   * @return Spring AI 流式响应；取消订阅即取消生成（不得吞 {@code onError}）
   */
  Flux<ChatResponse> streamChat(Prompt prompt);

  /**
   * 带调用选项的流式对话调用（修正轮2：thinking 传递到 compatible-mode enable_thinking）。
   *
   * @param prompt 完整 Prompt
   * @param options 中立调用选项（null 等效 {@link #streamChat(Prompt)}）
   * @return Spring AI 流式响应
   */
  default Flux<ChatResponse> streamChat(Prompt prompt, ModelCallOptions options) {
    return streamChat(prompt);
  }

  /**
   * 文本向量（知识库入库/检索共用）。
   *
   * @param request 向量请求（模型/维度由实现按配置注入）
   * @return 向量值 + usage；调用失败抛业务异常
   */
  ModelCallResult<float[]> embed(EmbeddingRequest request);

  /**
   * 视觉/OCR 多模态调用（图文解耦 D13：理解恒注入，向量仅 KB ON 时懒生成）。
   *
   * @param prompt 多模态 Prompt（含 image media）
   * @return 模型输出文本 + usage
   */
  ModelCallResult<String> vision(Prompt prompt);

  /**
   * 带调用选项的视觉/OCR 多模态调用（修正轮2：模型可覆盖、thinking 可开启）。
   *
   * @param prompt 多模态 Prompt
   * @param options 中立调用选项（null 等效 {@link #vision(Prompt)}）
   * @return 模型输出文本 + usage
   */
  default ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
    return vision(prompt);
  }
}
