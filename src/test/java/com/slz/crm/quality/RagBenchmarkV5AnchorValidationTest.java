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
 * RagReal 域 v5 锚点校验离线锁测（b2-suite-recalibration 任务 2.1，照█V4AnchorValidationTest 先例，红测先行）。
 *
 * <p>纯 JVM、零外发、零模型调用，日常 {@code mvn test} 与 CI 阶段 1 真跑。v5 锚点 = SUITE_VERSION=4.0 重锚产物 {@code
 * baseline-v5.json}，与 v3/v4 三锚并存：v3/v4 依然从未受影响的历史产物。与 v4 同构的两形态：在场时按 scoringVersion=v2（多块承载）口径对账
 * citP，recall/ansC 沿用同文件登记值，下限 = 记录值× 95%；缺席/非 v5 产物时整体静默跳过。 另锁写侧：v5 产物落盘登记 scoringVersion=v2（紧跟
 * suiteVersion），其他产物文件永不获得该键。
 */
class RagBenchmarkV5AnchorValidationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private static final String V5_NAME = RagRealRetrievalBenchmarkIT.V5_ANCHOR_FILE_NAME;

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
    // v5 锚点版本认定为 4.0（历史产物）：本测锁定 suite=4.0 域对 v5 锚点的校验语义。
    // clear-onboard-2-left-fragment 升版 5.0 后套件常量已变，故此处硬编码 v5 锚点自身版本，避免跨版本误配。
    return new Report("4.0", "2026-10-12T00:00:00Z", metrics, cases);
  }

  private static Path writeAnchor(Path dir, String fileName, String json) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(fileName);
    Files.writeString(file, json, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void absentV5AnchorSkipsValidation(@TempDir Path dir) {
    Path anchor = dir.resolve(V5_NAME);
    assertFalse(Files.exists(anchor), "前置：v5 锚点必须缺席");
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                anchor, report(2, 0.1d, 0.1d, 0.1d)),
        "v5 锚点缺席必须整体跳过校验");
    assertFalse(Files.exists(anchor), "缺席跳过不得顺手创建锚点文件");
  }

  @Test
  void nonV5TargetSkipsValidation(@TempDir Path dir) throws Exception {
    Path other =
        writeAnchor(dir, "baseline-v4.json", anchorJson("v2", "4.0", 2, 0.9d, 0.95d, 0.95d));
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(other, report(2, 0.1d, 0.1d, 0.1d)),
        "非 v5 产物不得与 v5 锚点对账");
  }

  @Test
  void presentV5AnchorRejectsCitPFallingBelowFloor(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V5_NAME, anchorJson("v2", "4.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    anchor, report(2, 0.60d, 0.95d, 0.95d)),
            "citP 跌破 0.80×0.95=0.76 下限必须红");
    assertTrue(
        error.getMessage().contains("meanCitationPrecision"),
        "报文要指名 citP 对账，实际=" + error.getMessage());
  }

  @Test
  void presentV5AnchorAcceptsMetricsWithinFloor(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V5_NAME, anchorJson("v2", "4.0", 2, 0.80d, 0.95d, 0.95d));
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                anchor, report(2, 0.79d, 0.93d, 0.93d)),
        "三项均在下限之上必须放行");
  }

  @Test
  void presentV5AnchorRejectsRecallAndAnsCFromSameFile(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V5_NAME, anchorJson("v2", "4.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError recallError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    anchor, report(2, 0.80d, 0.90d, 0.95d)),
            "recall 0.90 < 0.95×0.95=0.9025 必须红");
    assertTrue(recallError.getMessage().contains("meanRecallAtK"));
    AssertionError anscError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.90d)),
            "ansC 0.90 < 0.9025 必须红");
    assertTrue(anscError.getMessage().contains("meanAnswerConsistency"));
  }

  @Test
  void presentV5AnchorRejectsMissingOrNonV2ScoringVersion(@TempDir Path dir) throws Exception {
    Path missing =
        writeAnchor(
            dir.resolve("missing"), V5_NAME, anchorJson(null, "4.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError missingError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    missing, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion 整键缺失必须拒绝对账");
    assertTrue(missingError.getMessage().contains("scoringVersion"));
    Path v1Tagged =
        writeAnchor(dir.resolve("v1tag"), V5_NAME, anchorJson("v1", "4.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError v1Error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    v1Tagged, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion=v1 不得按 v2 口径对账");
    assertTrue(v1Error.getMessage().contains("scoringVersion"));
  }

  @Test
  void presentV5AnchorRejectsSuiteVersionOrCaseCountMismatch(@TempDir Path dir) throws Exception {
    Path suiteMismatch =
        writeAnchor(dir.resolve("suite"), V5_NAME, anchorJson("v2", "3.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError suiteError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    suiteMismatch, report(2, 0.80d, 0.95d, 0.95d)),
            "套件版本不一致禁止跨版本对账");
    assertTrue(suiteError.getMessage().contains("suiteVersion"));
    Path countMismatch =
        writeAnchor(
            dir.resolve("count"), V5_NAME, anchorJson("v2", "4.0", 63, 0.80d, 0.95d, 0.95d));
    AssertionError countError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    countMismatch, report(2, 0.80d, 0.95d, 0.95d)),
            "caseCount 不一致的过滤跑产物禁当锚点");
    assertTrue(countError.getMessage().contains("caseCount"));
  }

  @Test
  void presentV5AnchorRejectsZeroRecordedMetric(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V5_NAME, anchorJson("v2", "4.0", 2, 0.0d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV5Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.95d)),
            "锚点 citP 记录 0 必须拒绝");
    assertTrue(error.getMessage().contains("meanCitationPrecision"));
  }

  @Test
  void v5TargetWriteRegistersScoringVersionAfterSuiteVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve(V5_NAME);
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertEquals(
        RagRealRetrievalBenchmarkIT.V4_SCORING_VERSION,
        root.path("scoringVersion").asText(""),
        "v5 产物必须登记 scoringVersion=v2");
    List<String> names = new ArrayList<>();
    root.fieldNames().forEachRemaining(names::add);
    assertEquals(
        1,
        names.indexOf("scoringVersion") - names.indexOf("suiteVersion"),
        "scoringVersion 必须紧跟 suiteVersion，实际键序=" + names);
  }

  @Test
  void nonV5TargetWriteDoesNotRegisterScoringVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve("baseline-v1.json");
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertFalse(root.has("scoringVersion"), "非 v5 产物不得带 scoringVersion 键");
  }
}
