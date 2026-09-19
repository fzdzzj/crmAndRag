package com.slz.crm.quality;

import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RAG 质量基准评估器（task17）。
 *
 * <p>驱动一个可插拔的 {@link RetrievalFunction} 跑完固定基准集，计算召回率/命中率/引用精度/答案一致性 与 TTFT/总延迟/token/失败率，产出可比较的
 * {@link Report}。
 *
 * <p>解耦设计：{@code RetrievalFunction} 由调用方注入——单测用确定性 fake（本地可跑、手算可验）， CI/生产用真 {@code
 * KnowledgeRetrievalPort} + 已入库向量（配真模型产出的答案要点覆盖率）。 这样"质量基准"逻辑本身无需外部依赖即可被测试与门禁使用。
 *
 * <p>边界语义（D16 诚实生成）：expected 为空的边界用例，<b>不召回、不伪造引用</b>记满分（recall/precision/ 引用精度=1），过度检索或伪造引用记
 * 0——把"零命中时诚实"也纳入质量度量。
 */
public final class RagQualityEvaluator {

  private RagQualityEvaluator() {}

  /** 检索函数：给定基准用例 → 检索产出（排名序 chunkIds、引用编号、要点覆盖、延迟、token、成败）。 */
  @FunctionalInterface
  public interface RetrievalFunction {
    CaseOutcome retrieve(RagBenchmarkCase benchmarkCase);
  }

  /**
   * 跑完基准集并聚合指标。
   *
   * @param suite 基准用例集
   * @param retrieval 检索函数（抛异常按失败用例计，不打断整轮评估）
   * @param k recall@k / precision@k / MRR 的截断排名
   * @return 聚合指标 + 逐条评分的报告
   */
  public static Report evaluate(List<RagBenchmarkCase> suite, RetrievalFunction retrieval, int k) {
    List<CaseScore> scores = new ArrayList<>();
    int n = 0;
    int hits = 0;
    int hitEligible = 0;
    int failures = 0;
    double sumRecall = 0;
    double sumPrecision = 0;
    double sumReciprocalRank = 0;
    double sumCitationPrecision = 0;
    double sumConsistency = 0;
    double sumTtft = 0;
    double sumLatency = 0;
    long totalTokens = 0;

    for (RagBenchmarkCase benchmarkCase : suite) {
      CaseOutcome outcome;
      try {
        outcome = retrieval.retrieve(benchmarkCase);
      } catch (RuntimeException exception) {
        // 检索异常按失败用例计（空结果 + success=false），整轮评估继续
        outcome = new CaseOutcome(List.of(), List.of(), 0d, 0L, 0L, 0, false);
      }
      CaseScore score = score(benchmarkCase, outcome, k);
      scores.add(score);
      n++;
      sumRecall += score.recallAtK();
      sumPrecision += score.precisionAtK();
      sumReciprocalRank += score.reciprocalRank();
      sumCitationPrecision += score.citationPrecision();
      sumConsistency += score.answerConsistency();
      sumTtft += score.ttftMs();
      sumLatency += score.totalLatencyMs();
      totalTokens += score.tokens();
      if (!benchmarkCase.expectedChunkIds().isEmpty()) {
        hitEligible++;
        if (score.hit()) {
          hits++;
        }
      }
      if (!score.success()) {
        failures++;
      }
    }

    Metrics metrics =
        n == 0
            ? new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            : new Metrics(
                n,
                sumRecall / n,
                sumPrecision / n,
                sumReciprocalRank / n,
                hitEligible == 0 ? 0 : (double) hits / hitEligible,
                sumCitationPrecision / n,
                sumConsistency / n,
                sumTtft / n,
                sumLatency / n,
                totalTokens,
                (double) failures / n);
    // 版本与时间戳随报告落盘：跨变更比较先核对版本，时间戳用于识别模型波动期的历史运行
    return new Report(
        RagBenchmarkSuite.SUITE_VERSION, java.time.Instant.now().toString(), metrics, scores);
  }

  private static CaseScore score(RagBenchmarkCase benchmarkCase, CaseOutcome outcome, int k) {
    Set<String> expected = benchmarkCase.expectedChunkIds();
    List<String> topK = outcome.retrievedChunkIds().stream().limit(k).toList();
    int relevant = countRelevant(topK, expected);

    double recall;
    double precision;
    double reciprocalRank;
    if (expected.isEmpty()) {
      boolean honestEmpty = topK.isEmpty();
      recall = honestEmpty ? 1d : 0d;
      precision = honestEmpty ? 1d : 0d;
      reciprocalRank = honestEmpty ? 1d : 0d;
    } else {
      recall = (double) relevant / expected.size();
      precision = topK.isEmpty() ? 0d : (double) relevant / topK.size();
      reciprocalRank = firstRelevantReciprocal(topK, expected);
    }
    boolean hit = !expected.isEmpty() && relevant > 0;
    double citationPrecision = citationPrecision(outcome, expected);
    return new CaseScore(
        benchmarkCase.id(),
        benchmarkCase.category().name(),
        recall,
        precision,
        reciprocalRank,
        hit,
        citationPrecision,
        outcome.answerPointCoverage(),
        outcome.ttftMs(),
        outcome.totalLatencyMs(),
        outcome.tokens(),
        outcome.success());
  }

  private static int countRelevant(List<String> retrieved, Set<String> expected) {
    int count = 0;
    for (String id : retrieved) {
      if (expected.contains(id)) {
        count++;
      }
    }
    return count;
  }

  private static double firstRelevantReciprocal(List<String> retrieved, Set<String> expected) {
    for (int i = 0; i < retrieved.size(); i++) {
      if (expected.contains(retrieved.get(i))) {
        return 1d / (i + 1);
      }
    }
    return 0d;
  }

  /** 引用精度：答案实际引用的来源里，属于黄金片段的比例（伪造/越界引用拉低精度）。 */
  private static double citationPrecision(CaseOutcome outcome, Set<String> expected) {
    List<Integer> citations = outcome.citations();
    if (citations == null || citations.isEmpty()) {
      // 无引用：边界用例（expected 空）视为诚实正确；有黄金却零引用视为漏引
      return expected.isEmpty() ? 1d : 0d;
    }
    List<String> retrieved = outcome.retrievedChunkIds();
    int gold = 0;
    int valid = 0;
    for (Integer citation : citations) {
      if (citation == null || citation < 1 || citation > retrieved.size()) {
        continue; // 越界引用编号忽略（不计入分母）
      }
      valid++;
      if (expected.contains(retrieved.get(citation - 1))) {
        gold++;
      }
    }
    return valid == 0 ? 0d : (double) gold / valid;
  }
}
