package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * mock 全量评测口径分离断言（fix-citation-grading-overlap 任务 2.2，红测先行）。
 *
 * <p>同一批确定性检索产出分别按 v1 口径（3 参 evaluate，citP 期望 = 主锚块）与 v2 口径（4 参 evaluate，citP 期望 =
 * 承载集）评分，机械锁三件事：承载集非空例 citP 不降且至少一例严格上升（承载机制实际生效）；recall/ansC 逐例不变 （口径分离失守——recall
 * 误用承载集虚升——在此先红）；整轮 citP 可复算（确定性）。
 */
class RagBenchmarkCarrierScoringTest {

  private static final long EVAL_KB_ID = 99001L;

  private static final int TOP_K = 5;

  private static float[] fakeEmbed(String text) {
    float[] vector = new float[64];
    for (int i = 0; i < text.length(); i++) {
      vector[(text.charAt(i) * 31 + i) % vector.length] += 1f;
    }
    if (text.isEmpty()) {
      vector[0] = 1f;
    }
    return vector;
  }

  /** 确定性检索产出：非边界用例召回 = 锚点块 + 首个承载邻块（若存在），引用全部编号；边界用例诚实空。 */
  private static Map<String, CaseOutcome> cannedOutcomes(
      List<RagBenchmarkCase> rewritten,
      Map<String, Set<String>> citationExpected,
      RagBenchmarkDataPreparer.Preparation preparation) {
    Map<String, CaseOutcome> canned = new LinkedHashMap<>();
    for (RagBenchmarkCase c : rewritten) {
      if (c.expectedChunkIds().isEmpty()) {
        canned.put(c.id(), new CaseOutcome(List.of(), List.of(), 1.0d, 0L, 0L, 0, true));
        continue;
      }
      List<String> retrieved = new ArrayList<>(c.expectedChunkIds());
      Set<String> carriers = citationExpected.get(c.id());
      for (String carrier : carriers) {
        if (!retrieved.contains(carrier)) {
          retrieved.add(carrier);
          break;
        }
      }
      List<Integer> citations = new ArrayList<>();
      for (int i = 1; i <= retrieved.size(); i++) {
        citations.add(i);
      }
      assertTrue(retrieved.size() <= TOP_K, "确定性召回须在 topK 内: " + c.id());
      canned.put(c.id(), new CaseOutcome(retrieved, citations, 0.5d, 0L, 0L, 0, true));
    }
    return canned;
  }

  @Test
  void carrierScoringRaisesCitpWithoutTouchingRecallOrAnswerConsistency() {
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            new InMemoryVectorStore(), RagBenchmarkCarrierScoringTest::fakeEmbed, EVAL_KB_ID);
    // 占位 id 基准集（rewriteSuite 前）→ 承载集期望；改写后套件仍按主锚块召回（口径分离）
    Map<String, Set<String>> citationExpected =
        RagBenchmarkDataPreparer.citationExpectedByCase(RagBenchmarkSuite.standard(), preparation);
    List<RagBenchmarkCase> rewritten =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
    assertEquals(63, citationExpected.size(), "每条用例都必须有 citP 期望集登记");
    Map<String, CaseOutcome> canned = cannedOutcomes(rewritten, citationExpected, preparation);

    Report v1 = RagQualityEvaluator.evaluate(rewritten, c -> canned.get(c.id()), TOP_K);
    Report v2 =
        RagQualityEvaluator.evaluate(rewritten, c -> canned.get(c.id()), TOP_K, citationExpected);

    Map<String, CaseScore> v1ById = byId(v1);
    Map<String, CaseScore> v2ById = byId(v2);
    int strictlyImproved = 0;
    for (RagBenchmarkCase c : rewritten) {
      CaseScore oldScore = v1ById.get(c.id());
      CaseScore newScore = v2ById.get(c.id());
      // 口径分离失守防线：recall/precision/MRR/ansC 逐例不变（若 recall 误用承载集，T-14 类例会在此虚升）
      assertEquals(oldScore.recallAtK(), newScore.recallAtK(), "recall 逐例不变: " + c.id());
      assertEquals(oldScore.precisionAtK(), newScore.precisionAtK(), "precision 逐例不变: " + c.id());
      assertEquals(oldScore.reciprocalRank(), newScore.reciprocalRank(), "MRR 逐例不变: " + c.id());
      assertEquals(
          oldScore.answerConsistency(), newScore.answerConsistency(), "ansC 逐例不变: " + c.id());
      // citP 方向：承载集 ⊇ 主锚块 → 非降；承载邻块被引用的用例严格上升
      assertTrue(
          newScore.citationPrecision() >= oldScore.citationPrecision(),
          "承载集非空例 citP 不降: "
              + c.id()
              + " v1="
              + oldScore.citationPrecision()
              + " v2="
              + newScore.citationPrecision());
      if (newScore.citationPrecision() > oldScore.citationPrecision()) {
        strictlyImproved++;
      }
    }
    assertTrue(strictlyImproved >= 1, "至少一例 citP 严格上升（承载机制实际生效），实际=" + strictlyImproved);
    Metrics m1 = v1.metrics();
    Metrics m2 = v2.metrics();
    assertTrue(m2.meanCitationPrecision() >= m1.meanCitationPrecision(), "整轮 citP 不降");
    assertTrue(
        m2.meanCitationPrecision() > m1.meanCitationPrecision(), "整轮 citP 严格上升（存在 B1/B3 形态）");
  }

  /** 确定性可复算（任务 2.2）：同输入双跑逐位一致（时间戳除外）。 */
  @Test
  void carrierScoringIsDeterministicAcrossRuns() {
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            new InMemoryVectorStore(), RagBenchmarkCarrierScoringTest::fakeEmbed, EVAL_KB_ID);
    Map<String, Set<String>> citationExpected =
        RagBenchmarkDataPreparer.citationExpectedByCase(RagBenchmarkSuite.standard(), preparation);
    List<RagBenchmarkCase> rewritten =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
    Map<String, CaseOutcome> canned = cannedOutcomes(rewritten, citationExpected, preparation);

    Report first =
        RagQualityEvaluator.evaluate(rewritten, c -> canned.get(c.id()), TOP_K, citationExpected);
    Report second =
        RagQualityEvaluator.evaluate(rewritten, c -> canned.get(c.id()), TOP_K, citationExpected);

    assertEquals(byId(first), byId(second), "双跑逐条评分必须逐位一致");
    Metrics m1 = first.metrics();
    Metrics m2 = second.metrics();
    assertEquals(m1.meanCitationPrecision(), m2.meanCitationPrecision(), "整轮 citP 逐位复算");
    assertEquals(m1.meanRecallAtK(), m2.meanRecallAtK(), "整轮 recall 逐位复算");
    assertEquals(m1.meanAnswerConsistency(), m2.meanAnswerConsistency(), "整轮 ansC 逐位复算");
  }

  private static Map<String, CaseScore> byId(Report report) {
    Map<String, CaseScore> byId = new LinkedHashMap<>();
    for (CaseScore score : report.cases()) {
      byId.put(score.id(), score);
    }
    return byId;
  }
}
