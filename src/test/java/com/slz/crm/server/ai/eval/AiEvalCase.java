package com.slz.crm.server.ai.eval;

import java.util.List;
import java.util.Map;

/**
 * AI 工具选择评测用例。 expectedParams 只约束业务关键参数，不约束模型可能补充的可选参数。
 *
 * <p>多轮维度（expand-rag-eval-blind-spots 任务 1.3）：{@code history} 携带本轮话术之前的对话轮次，单轮用例为空表；
 * 多轮用例的话术是省略式（「它的…」「那第二笔呢」），只有结合 history 才能选出正确工具——该维度即多轮工具选择的评测面。
 */
public record AiEvalCase(
    String id,
    String utterance,
    String expectedTool,
    Map<String, Object> expectedParams,
    List<String> tags,
    List<AiEvalTurn> history) {

  /** 单轮用例兼容构造：history 视为空表（既有黄金用例全部按此形态构造）。 */
  public AiEvalCase(
      String id,
      String utterance,
      String expectedTool,
      Map<String, Object> expectedParams,
      List<String> tags) {
    this(id, utterance, expectedTool, expectedParams, tags, List.of());
  }

  /**
   * 一条对话轮次（多轮用例的上下文单元）。
   *
   * @param user 该轮用户话术
   * @param assistant 该轮助手回答
   */
  public record AiEvalTurn(String user, String assistant) {}
}
