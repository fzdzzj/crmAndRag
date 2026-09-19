package com.slz.crm.platform.contract;

/**
 * 一次模型调用的统一返回（强制携带 usage 计量）。
 *
 * <p>为什么不能用裸 {@code String}：RAG 原实现 {@code chat(String)} 把文本直接返回， token
 * 消耗无处可记，导致意图/摘要等旁路调用成为计量盲区（D14 明确要求补齐）。 本类型把 usage 变成返回值的一部分，调用方“想丢都丢不掉”。
 *
 * @param content 模型输出文本（向量调用为 {@code null}，见 {@code vector}）
 * @param vector 向量值（仅 {@link ModelProvider#embed} 填充，其余为 {@code null}）
 * @param promptTokens 输入 token 数，未知为 {@code null}（不同 Provider 口径不一，允许缺失但不允许整条丢失）
 * @param completionTokens 输出 token 数，未知为 {@code null}
 * @param totalTokens 总 token 数，未知为 {@code null}
 * @param model 实际使用的模型名（可能与配置的“默认模型”不同，例如降级到备用模型）
 * @param <T> 内容类型（{@code String} / {@code float[]}）
 */
public record ModelCallResult<T>(
    T content,
    float[] vector,
    Long promptTokens,
    Long completionTokens,
    Long totalTokens,
    String model) {

  /**
   * @return true 表示拿到了 usage（prompt/completion/total 至少一项非空）
   */
  public boolean hasUsage() {
    return promptTokens != null || completionTokens != null || totalTokens != null;
  }

  /**
   * 构造文本型结果。
   *
   * @param content 模型输出文本
   * @param model 实际模型名
   * @param promptTokens 输入 token 数
   * @param completionTokens 输出 token 数
   * @param totalTokens 总 token 数
   * @param <T> 固定为 String
   * @return 携带 usage 的结果
   */
  public static ModelCallResult<String> ofText(
      String content, String model, Long promptTokens, Long completionTokens, Long totalTokens) {
    return new ModelCallResult<>(content, null, promptTokens, completionTokens, totalTokens, model);
  }

  /**
   * 构造向量型结果。
   *
   * @param vector 向量值
   * @param model 实际模型名
   * @param promptTokens 输入 token 数（文本长度折算）
   * @param totalTokens 总 token 数
   * @return 携带 usage 的结果
   */
  public static ModelCallResult<float[]> ofVector(
      float[] vector, String model, Long promptTokens, Long totalTokens) {
    return new ModelCallResult<>(null, vector, promptTokens, null, totalTokens, model);
  }
}
