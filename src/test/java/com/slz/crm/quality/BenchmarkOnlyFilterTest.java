package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.quality.RagBenchmarkCase.Category;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.quality.RagRealRetrievalBenchmarkIT.ExcerptTrace;
import com.slz.crm.quality.RagRealRetrievalBenchmarkIT.ForensicCase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * only 过滤与取证旁路 JSON 单测（trace-i05-citation-forensics 任务 2.1–2.2）。
 *
 * <p>¥0、不外呼；验证：only=I-05 留 1 条、空属性不滤、未知 id 失败； 旁路 JSON 假数据含
 * rawAnswer/alignedAnswer/citationsBefore|After/excerpts。
 */
class BenchmarkOnlyFilterTest {

  @AfterEach
  void clearProps() {
    System.clearProperty(RagRealRetrievalBenchmarkIT.ONLY_PROP);
    System.clearProperty(RagRealRetrievalBenchmarkIT.TRACE_OUT_PROP);
  }

  @Test
  void onlyI05_keepsSingleCase() {
    List<RagBenchmarkCase> suite = sampleSuite();
    List<RagBenchmarkCase> filtered = RagRealRetrievalBenchmarkIT.applyOnlyFilter(suite, "I-05");
    assertEquals(1, filtered.size());
    assertEquals("I-05", filtered.get(0).id());
  }

  @Test
  void blankOnly_doesNotFilter() {
    List<RagBenchmarkCase> suite = sampleSuite();
    assertEquals(suite.size(), RagRealRetrievalBenchmarkIT.applyOnlyFilter(suite, null).size());
    assertEquals(suite.size(), RagRealRetrievalBenchmarkIT.applyOnlyFilter(suite, "  ").size());
    assertEquals(suite.size(), RagRealRetrievalBenchmarkIT.applyOnlyFilter(suite, "").size());
  }

  @Test
  void unknownId_failsExplicitly() {
    List<RagBenchmarkCase> suite = sampleSuite();
    AssertionFailedError error =
        assertThrows(
            AssertionFailedError.class,
            () -> RagRealRetrievalBenchmarkIT.applyOnlyFilter(suite, "I-05,NO-SUCH"));
    assertTrue(
        error.getMessage().contains("NO-SUCH") || error.getMessage().contains("未知"),
        "失败信息应点明未知 id: " + error.getMessage());
  }

  @Test
  void forensicsJson_containsRequiredFields() throws Exception {
    ForensicCase fake =
        new ForensicCase(
            "I-05",
            "服务台架构图注分了几层，数据层是什么",
            List.of("gold-chunk"),
            List.of("r1", "r2", "gold-chunk"),
            List.of(
                new ExcerptTrace(1, "r1", "无关 excerpt A"),
                new ExcerptTrace(2, "r2", "无关 excerpt B"),
                new ExcerptTrace(3, "gold-chunk", "四层 台账与预警引擎")),
            "答案原文无编号或错号[1]",
            "对齐后答案[3]",
            List.of(1),
            List.of(3),
            1.0,
            1.0,
            1.0);

    String json = RagRealRetrievalBenchmarkIT.forensicsToJson(List.of(fake));
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(json);
    assertEquals(1, root.path("caseCount").asInt());
    JsonNode c = root.path("cases").get(0);
    assertEquals("I-05", c.path("id").asText());
    assertTrue(c.hasNonNull("rawAnswer"), "rawAnswer");
    assertTrue(c.hasNonNull("alignedAnswer"), "alignedAnswer");
    assertTrue(c.path("citationsBefore").isArray(), "citationsBefore");
    assertTrue(c.path("citationsAfter").isArray(), "citationsAfter");
    assertTrue(c.path("retrievedChunkIds").isArray(), "retrievedChunkIds");
    assertTrue(c.path("excerpts").isArray() && c.path("excerpts").size() == 3, "excerpts");
    assertEquals(1, c.path("excerpts").get(0).path("n").asInt());
    assertEquals("gold-chunk", c.path("excerpts").get(2).path("chunkId").asText());
    assertTrue(c.path("excerpts").get(2).path("excerpt").asText().contains("台账"));
    assertEquals("答案原文无编号或错号[1]", c.path("rawAnswer").asText());
    assertEquals("对齐后答案[3]", c.path("alignedAnswer").asText());
  }

  @Test
  void enrichForensics_backfillsScoresFromReport() {
    ForensicCase bare =
        new ForensicCase(
            "I-05",
            "q",
            List.of("g"),
            List.of("a", "g"),
            List.of(),
            "raw",
            "aligned",
            List.of(),
            List.of(),
            null,
            null,
            null);
    CaseScore score =
        new CaseScore("I-05", "IMAGE", 1.0, 0.2, 0.5, true, 0.0, 1.0, 10L, 20L, 100, true);
    Metrics metrics = new Metrics(1, 1, 0.2, 0.5, 1, 0, 1, 10, 20, 100, 0);
    Report report = new Report("2.0", "t", metrics, List.of(score));

    List<ForensicCase> enriched =
        RagRealRetrievalBenchmarkIT.enrichForensicsWithScores(List.of(bare), report);
    assertEquals(1, enriched.size());
    assertEquals(0.0, enriched.get(0).citationPrecision());
    assertEquals(1.0, enriched.get(0).answerConsistency());
    assertEquals(1.0, enriched.get(0).recallAtK());
    assertEquals("raw", enriched.get(0).rawAnswer());
  }

  private static List<RagBenchmarkCase> sampleSuite() {
    return List.of(
        new RagBenchmarkCase("I-04", Category.IMAGE, "q4", Set.of("g4"), List.of("p"), true),
        new RagBenchmarkCase("I-05", Category.IMAGE, "q5", Set.of("g5"), List.of("p"), true),
        new RagBenchmarkCase("I-06", Category.IMAGE, "q6", Set.of("g6"), List.of("p"), true));
  }
}
