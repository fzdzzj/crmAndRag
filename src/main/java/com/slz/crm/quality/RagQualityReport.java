package com.slz.crm.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;

/**
 * RAG 质量与性能评估结果模型（task17：输出可比较的 JSON 报告）。
 *
 * <p>质量指标（召回/命中/引用精度/答案一致性）与性能指标（TTFT/总延迟/token/失败率）合一， 供 governance spec
 * 的"质量评估报告"与"性能预算/回归阈值"门禁比较历次运行。
 */
public final class RagQualityReport {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private RagQualityReport() {}

  /**
   * 单条用例的检索产出（由 {@code RetrievalFunction} 提供）。
   *
   * @param retrievedChunkIds 按排名序的召回片段 id
   * @param citations 答案实际引用的编号（1-based，指向 retrievedChunkIds）
   * @param answerPointCoverage 答案要点覆盖率 0..1（答案一致性；由答案检查器/LLM 判定给出）
   * @param ttftMs 首字延迟（毫秒）
   * @param totalLatencyMs 总延迟（毫秒）
   * @param tokens 本次消耗 token
   * @param success 是否成功（失败计入 failureRate）
   * @param refused 生成层是否诚实拒答（expected 为空的用例专用信号，expand-rag-eval-blind-spots 任务 3.1）：true =
   *     明确告知知识库无相关内容、未编造；false = 给出了知识性断言（可能借近邻内容编造）。expected 非空的用例该信号不参与判分 （答案一致性仍取 {@code
   *     answerPointCoverage}）。
   */
  public record CaseOutcome(
      List<String> retrievedChunkIds,
      List<Integer> citations,
      double answerPointCoverage,
      long ttftMs,
      long totalLatencyMs,
      int tokens,
      boolean success,
      boolean refused) {

    /**
     * 兼容旧构造：未填报拒答信号时按 {@code refused=false} 处理（适配层未接信号 = 未证明诚实拒答，expected 空用例按 0 分计，见 {@code
     * RagQualityEvaluator} 的 D16 收紧口径）。
     */
    public CaseOutcome(
        List<String> retrievedChunkIds,
        List<Integer> citations,
        double answerPointCoverage,
        long ttftMs,
        long totalLatencyMs,
        int tokens,
        boolean success) {
      this(
          retrievedChunkIds,
          citations,
          answerPointCoverage,
          ttftMs,
          totalLatencyMs,
          tokens,
          success,
          false);
    }
  }

  /** 单条用例评分。 */
  public record CaseScore(
      String id,
      String category,
      double recallAtK,
      double precisionAtK,
      double reciprocalRank,
      boolean hit,
      double citationPrecision,
      double answerConsistency,
      long ttftMs,
      long totalLatencyMs,
      int tokens,
      boolean success) {}

  /** 聚合指标（跨基准集）。 */
  public record Metrics(
      int caseCount,
      double meanRecallAtK,
      double meanPrecisionAtK,
      double mrr,
      double hitRate,
      double meanCitationPrecision,
      double meanAnswerConsistency,
      double meanTtftMs,
      double meanTotalLatencyMs,
      long totalTokens,
      double failureRate) {}

  /**
   * 完整报告 = 基准集版本 + 生成时间 + 聚合指标 + 逐条评分。
   *
   * <p>版本与时间戳是跨变更比较的前提（add-rag-quality-baseline）： 版本取自 {@code
   * RagBenchmarkSuite.SUITE_VERSION}，比较两份报告前先核对版本一致； 时间戳为 UTC ISO-8601 串，用于识别模型波动期的历史运行。
   */
  public record Report(
      String suiteVersion, String generatedAt, Metrics metrics, List<CaseScore> cases) {

    /**
     * @return 报告的 JSON 串（可归档、可跨次比较）；序列化失败返回 {@code {}}
     */
    public String toJson() {
      String result = "{}";
      try {
        result = MAPPER.writeValueAsString(this);
      } catch (JsonProcessingException e) {
        result = "{}";
      }
      return result;
    }
  }
}
