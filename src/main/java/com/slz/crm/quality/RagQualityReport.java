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
   */
  public record CaseOutcome(
      List<String> retrievedChunkIds,
      List<Integer> citations,
      double answerPointCoverage,
      long ttftMs,
      long totalLatencyMs,
      int tokens,
      boolean success) {}

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
      try {
        return MAPPER.writeValueAsString(this);
      } catch (JsonProcessingException e) {
        return "{}";
      }
    }
  }
}
