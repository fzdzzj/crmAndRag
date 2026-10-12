package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RagReal 域 v6 锚点校验离线锁测（clear-onboard-2-left-fragment 任务 2.2，照 RagBenchmarkV5AnchorValidationTest
 * 先例，红测先行）。
 *
 * <p>纯 JVM、零外发、零模型调用，日常 {@code mvn test} 与 CI 阶段 1 真跑。v6 锚点 = SUITE_VERSION=5.0 重锚产物 {@code
 * baseline-v6.json}，与 v3/v4/v5 四锚并存：v3/v4/v5 依然是从未受影响的历史产物。与 v5 同构的两形态：在场时按 scoringVersion=v2（多块承载）
 * 口径对账 citP，recall/ansC 沿用同文件登记值，下限 = 记录值 × 95%；缺席/非 v6 产物时整体静默跳过。另锁写侧：v6 产物落盘登记
 * scoringVersion=v2（紧跟 suiteVersion），其他产物文件永不获得该键。
 */
class RagBenchmarkV6AnchorValidationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** 与被测实现同源的 v6 锚点文件名（避免测试里出现第二个字符串真相源）。 */
  private static final String V6_NAME = RagRealRetrievalBenchmarkIT.V6_ANCHOR_FILE_NAME;

  private static String anchorJson(
      String scoringVersion,
      String suiteVersion,
      int caseCount,
      double citP,
      double recall,
      double ansC) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("suiteVersion", suiteVersion);
    if (scoringVersion != null) {
      root.put("scoringVersion", scoringVersion);
    }
    root.put("generatedAt", "2026-10-12T00:00:00Z");
    ObjectNode metrics = root.putObject("metrics");
    metrics.put("caseCount", caseCount);
    metrics.put("meanRecallAtK", recall);
    metrics.put("meanCitationPrecision", citP);
    metrics.put("meanAnswerConsistency", ansC);
    return root.toString();
  }

  private static Report report(int caseCount, double citP, double recall, double ansC) {
    Metrics metrics =
        new Metrics(caseCount, recall, 0.26d, 0.92d, 1.0d, citP, ansC, 400L, 4000L, 60000L, 0.0d);
    List<CaseScore> cases = new ArrayList<>();
    for (int i = 0; i < caseCount; i++) {
      cases.add(
          new CaseScore(
              "C-" + i, "TEXT", 1.0d, 0.4d, 1.0d, true, citP, ansC, 300L, 3000L, 900, true));
    }
    // SUITE_VERSION=5.0 是 v6 锚点的版本认定；代码里引用套件常量而非硬编码，避免双真相源
    return new Report(RagBenchmarkSuite.SUITE_VERSION, "2026-10-12T00:00:00Z", metrics, cases);
  }

  private static Path writeAnchor(Path dir, String fileName, String json) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(fileName);
    Files.writeString(file, json, StandardCharsets.UTF_8);
    return file;
  }

  /** clear-onboard-2-left-fragment 任务 2.2：升版后 SUITE_VERSION 必须为 5.0（v6 锚点版本认定）。 */
  @Test
  void suiteVersionIsFiveZeroAfterClearOnb2() {
    assertEquals(
        "5.0",
        RagBenchmarkSuite.SUITE_VERSION,
        "clear-onboard-2-left-fragment：SUITE_VERSION 必须为 5.0");
  }

  @Test
  void absentV6AnchorSkipsValidation(@TempDir Path dir) {
    Path anchor = dir.resolve(V6_NAME);
    assertFalse(Files.exists(anchor), "前置：v6 锚点必须缺席");
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                anchor, report(2, 0.1d, 0.1d, 0.1d)),
        "v6 锚点缺席必须整体跳过校验");
    assertFalse(Files.exists(anchor), "缺席跳过不得顺手创建锚点文件");
  }

  @Test
  void nonV6TargetSkipsValidation(@TempDir Path dir) throws Exception {
    Path other =
        writeAnchor(dir, "baseline-v5.json", anchorJson("v2", "4.0", 2, 0.9d, 0.95d, 0.95d));
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(other, report(2, 0.1d, 0.1d, 0.1d)),
        "非 v6 产物不得与 v6 锚点对账");
  }

  @Test
  void presentV6AnchorRejectsCitPFallingBelowFloor(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V6_NAME, anchorJson("v2", "5.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    anchor, report(2, 0.60d, 0.95d, 0.95d)),
            "citP 跌破 0.80×0.95=0.76 下限必须红");
    assertTrue(
        error.getMessage().contains("meanCitationPrecision"),
        "报文要指名 citP 对账，实际=" + error.getMessage());
  }

  @Test
  void presentV6AnchorAcceptsMetricsWithinFloor(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V6_NAME, anchorJson("v2", "5.0", 2, 0.80d, 0.95d, 0.95d));
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                anchor, report(2, 0.79d, 0.93d, 0.93d)),
        "三项均在下限之上必须放行");
  }

  @Test
  void presentV6AnchorRejectsRecallAndAnsCFromSameFile(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V6_NAME, anchorJson("v2", "5.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError recallError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    anchor, report(2, 0.80d, 0.90d, 0.95d)),
            "recall 0.90 < 0.95×0.95=0.9025 必须红");
    assertTrue(recallError.getMessage().contains("meanRecallAtK"));
    AssertionError anscError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.90d)),
            "ansC 0.90 < 0.9025 必须红");
    assertTrue(anscError.getMessage().contains("meanAnswerConsistency"));
  }

  @Test
  void presentV6AnchorRejectsMissingOrNonV2ScoringVersion(@TempDir Path dir) throws Exception {
    Path missing =
        writeAnchor(
            dir.resolve("missing"), V6_NAME, anchorJson(null, "5.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError missingError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    missing, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion 整键缺失必须拒绝对账");
    assertTrue(missingError.getMessage().contains("scoringVersion"));
    Path v1Tagged =
        writeAnchor(dir.resolve("v1tag"), V6_NAME, anchorJson("v1", "5.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError v1Error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    v1Tagged, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion=v1 不得按 v2 口径对账");
    assertTrue(v1Error.getMessage().contains("scoringVersion"));
  }

  @Test
  void presentV6AnchorRejectsSuiteVersionOrCaseCountMismatch(@TempDir Path dir) throws Exception {
    Path suiteMismatch =
        writeAnchor(dir.resolve("suite"), V6_NAME, anchorJson("v2", "4.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError suiteError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    suiteMismatch, report(2, 0.80d, 0.95d, 0.95d)),
            "套件版本不一致禁止跨版本对账");
    assertTrue(suiteError.getMessage().contains("suiteVersion"));
    Path countMismatch =
        writeAnchor(
            dir.resolve("count"), V6_NAME, anchorJson("v2", "5.0", 63, 0.80d, 0.95d, 0.95d));
    AssertionError countError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    countMismatch, report(2, 0.80d, 0.95d, 0.95d)),
            "caseCount 不一致的过滤跑产物禁当锚点");
    assertTrue(countError.getMessage().contains("caseCount"));
  }

  @Test
  void presentV6AnchorRejectsZeroRecordedMetric(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V6_NAME, anchorJson("v2", "5.0", 2, 0.0d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV6Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.95d)),
            "锚点 citP 记录 0 必须拒绝");
    assertTrue(error.getMessage().contains("meanCitationPrecision"));
  }

  @Test
  void v6TargetWriteRegistersScoringVersionAfterSuiteVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve(V6_NAME);
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertEquals(
        RagRealRetrievalBenchmarkIT.V4_SCORING_VERSION,
        root.path("scoringVersion").asText(""),
        "v6 产物必须登记 scoringVersion=v2");
    List<String> names = new ArrayList<>();
    root.fieldNames().forEachRemaining(names::add);
    assertEquals(
        1,
        names.indexOf("scoringVersion") - names.indexOf("suiteVersion"),
        "scoringVersion 必须紧跟 suiteVersion，实际键序=" + names);
  }

  @Test
  void nonV6TargetWriteDoesNotRegisterScoringVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve("baseline-v1.json");
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertFalse(root.has("scoringVersion"), "非 v6 产物不得带 scoringVersion 键");
  }
}
