package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * RagReal 域 v7 锚点校验离线锁测（anchor-v6-baseline-v7 任务 2.1，照 {@code RagBenchmarkV6AnchorValidationTest}
 * 先例）。
 *
 * <p>纯 JVM、零外发、零模型调用，日常 {@code mvn test} 与 CI 阶段 1 真跑。v7 锚点 = SUITE_VERSION=6.0 重锚产物 {@code
 * baseline-v7.json}（72 例），与 v1~v6 六锚并存：v1~v6 依然是从未受影响的历史产物。与 v6 同构的两形态：在场时按 scoringVersion=v2（多块承载）
 * 口径对账 citP，recall/ansC 沿用同文件登记值，下限 = 记录值 × 95%；缺席/非 v7 产物时整体静默跳过。另锁写侧：v7 产物落盘登记
 * scoringVersion=v2（紧跟 suiteVersion），其他产物文件永不获得该键。
 *
 * <p><b>与 v6 不可比</b>：v6 锚 = SUITE_VERSION 5.0 / 63 例，v7 锚 = 6.0 / 72 例。二者是不同套件版本的产物，禁止跨版本对账——
 * suiteVersion 与 caseCount 双闸都在本类里被显式压红过，不做折算、不做跨锚比较。
 */
class RagBenchmarkV7AnchorValidationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** 与被测实现同源的 v7 锚点文件名（避免测试里出现第二个字符串真相源）。 */
  private static final String V7_NAME = RagRealRetrievalBenchmarkIT.V7_ANCHOR_FILE_NAME;

  /** v7 锚点自身登记的套件版本（与 live 常量同为 6.0；锚点与其产物版本仍写成一对历史常量）。 */
  private static final String V7_ANCHOR_SUITE_VERSION = "6.0";

  /** v7 锚点登记的用例数（72 例全量；过滤跑产物禁止当锚点）。 */
  private static final int V7_ANCHOR_CASE_COUNT = 72;

  /** v6 历史锚点的版本与用例数——仅用于声明「与 v7 不可比」。 */
  private static final String V6_ANCHOR_SUITE_VERSION = "5.0";

  private static final int V6_ANCHOR_CASE_COUNT = 63;

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
    root.put("generatedAt", "2026-10-22T00:00:00Z");
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
    // v7 锚点是 SUITE_VERSION=6.0 的产物：对账的"本轮报告"按 6.0 建模
    return new Report(V7_ANCHOR_SUITE_VERSION, "2026-10-22T00:00:00Z", metrics, cases);
  }

  /** 本轮 v6.0 全量跑（72 例）的代表报告。 */
  private static Report report() {
    return report(V7_ANCHOR_CASE_COUNT, 0.80d, 0.95d, 0.95d);
  }

  private static Path writeAnchor(Path dir, String fileName, String json) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(fileName);
    Files.writeString(file, json, StandardCharsets.UTF_8);
    return file;
  }

  /** SUITE_VERSION 必须为 6.0，v7 锚点登记 6.0 / 72 例，且与 v6（5.0 / 63 例）不可比。 */
  @Test
  void suiteVersionIsSixZeroAndAnchorIsNotComparableWithV6() {
    assertEquals("6.0", RagBenchmarkSuite.SUITE_VERSION, "RagBenchmarkSuite.SUITE_VERSION 必须为 6.0");
    assertEquals("6.0", V7_ANCHOR_SUITE_VERSION, "v7 锚点是 SUITE_VERSION=6.0 的产物");
    assertEquals(V7_ANCHOR_CASE_COUNT, RagBenchmarkSuite.standard().size(), "v6.0 套件必须是 72 例全量");
    assertNotEquals(V6_ANCHOR_SUITE_VERSION, V7_ANCHOR_SUITE_VERSION, "v7 锚与 v6 锚跨版本，禁止当作可比基线");
    assertNotEquals(V6_ANCHOR_CASE_COUNT, V7_ANCHOR_CASE_COUNT, "v7 锚与 v6 锚用例数不同，跨用例数不可比");
  }

  @Test
  void absentV7AnchorSkipsValidation(@TempDir Path dir) {
    Path anchor = dir.resolve(V7_NAME);
    assertFalse(Files.exists(anchor), "前置：v7 锚点必须缺席");
    assertDoesNotThrow(
        () -> RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(anchor, report()),
        "v7 锚点缺席必须整体跳过校验");
    assertFalse(Files.exists(anchor), "缺席跳过不得顺手创建锚点文件");
  }

  @Test
  void nonV7TargetSkipsValidation(@TempDir Path dir) throws Exception {
    Path other =
        writeAnchor(
            dir,
            "baseline-v6.json",
            anchorJson("v2", V6_ANCHOR_SUITE_VERSION, 2, 0.9d, 0.95d, 0.95d));
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(other, report(2, 0.1d, 0.1d, 0.1d)),
        "非 v7 产物不得与 v7 锚点对账");
  }

  @Test
  void presentV7AnchorRejectsCitPFallingBelowFloor(@TempDir Path dir) throws Exception {
    Path anchor =
        writeAnchor(
            dir, V7_NAME, anchorJson("v2", V7_ANCHOR_SUITE_VERSION, 2, 0.80d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(
                    anchor, report(2, 0.60d, 0.95d, 0.95d)),
            "citP 跌破 0.80×0.95=0.76 下限必须红");
    assertTrue(
        error.getMessage().contains("meanCitationPrecision"),
        "报文要指名 citP 对账，实际=" + error.getMessage());
  }

  @Test
  void presentV7AnchorAcceptsMetricsWithinFloor(@TempDir Path dir) throws Exception {
    Path anchor =
        writeAnchor(
            dir, V7_NAME, anchorJson("v2", V7_ANCHOR_SUITE_VERSION, 2, 0.80d, 0.95d, 0.95d));
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(
                anchor, report(2, 0.79d, 0.93d, 0.93d)),
        "三项均在下限之上必须放行");
  }

  @Test
  void presentV7AnchorRejectsRecallAndAnsCFromSameFile(@TempDir Path dir) throws Exception {
    Path anchor =
        writeAnchor(
            dir, V7_NAME, anchorJson("v2", V7_ANCHOR_SUITE_VERSION, 2, 0.80d, 0.95d, 0.95d));
    AssertionError recallError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(
                    anchor, report(2, 0.80d, 0.90d, 0.95d)),
            "recall 0.90 < 0.95×0.95=0.9025 必须红");
    assertTrue(recallError.getMessage().contains("meanRecallAtK"));
    AssertionError anscError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.90d)),
            "ansC 0.90 < 0.9025 必须红");
    assertTrue(anscError.getMessage().contains("meanAnswerConsistency"));
  }

  @Test
  void presentV7AnchorRejectsMissingOrNonV2ScoringVersion(@TempDir Path dir) throws Exception {
    Path missing =
        writeAnchor(
            dir.resolve("missing"),
            V7_NAME,
            anchorJson(null, V7_ANCHOR_SUITE_VERSION, 2, 0.80d, 0.95d, 0.95d));
    AssertionError missingError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(
                    missing, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion 整键缺失必须拒绝对账");
    assertTrue(missingError.getMessage().contains("scoringVersion"));
    Path v1Tagged =
        writeAnchor(
            dir.resolve("v1tag"),
            V7_NAME,
            anchorJson("v1", V7_ANCHOR_SUITE_VERSION, 2, 0.80d, 0.95d, 0.95d));
    AssertionError v1Error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(
                    v1Tagged, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion=v1 不得按 v2 口径对账");
    assertTrue(v1Error.getMessage().contains("scoringVersion"));
  }

  /** 与 v6 锚（5.0 / 63 例）不可比：套件版本或用例数不一致一律压红，不做折算。 */
  @Test
  void presentV7AnchorRejectsSuiteVersionOrCaseCountMismatch(@TempDir Path dir) throws Exception {
    Path suiteMismatch =
        writeAnchor(
            dir.resolve("suite"),
            V7_NAME,
            anchorJson("v2", V6_ANCHOR_SUITE_VERSION, V7_ANCHOR_CASE_COUNT, 0.80d, 0.95d, 0.95d));
    AssertionError suiteError =
        assertThrows(
            AssertionError.class,
            () -> RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(suiteMismatch, report()),
            "套件版本不一致禁止跨版本对账（v6=5.0 产物不得当 v7 锚）");
    assertTrue(suiteError.getMessage().contains("suiteVersion"));
    Path countMismatch =
        writeAnchor(
            dir.resolve("count"),
            V7_NAME,
            anchorJson("v2", V7_ANCHOR_SUITE_VERSION, V6_ANCHOR_CASE_COUNT, 0.80d, 0.95d, 0.95d));
    AssertionError countError =
        assertThrows(
            AssertionError.class,
            () -> RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(countMismatch, report()),
            "caseCount 不一致（63 ≠ 72）的过滤跑/旧锚产物禁当锚点");
    assertTrue(countError.getMessage().contains("caseCount"));
  }

  @Test
  void presentV7AnchorRejectsZeroRecordedMetric(@TempDir Path dir) throws Exception {
    Path anchor =
        writeAnchor(dir, V7_NAME, anchorJson("v2", V7_ANCHOR_SUITE_VERSION, 2, 0.0d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV7Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.95d)),
            "锚点 citP 记录 0 必须拒绝");
    assertTrue(error.getMessage().contains("meanCitationPrecision"));
  }

  @Test
  void v7TargetWriteRegistersScoringVersionAfterSuiteVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve(V7_NAME);
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertEquals(
        RagRealRetrievalBenchmarkIT.V4_SCORING_VERSION,
        root.path("scoringVersion").asText(""),
        "v7 产物必须登记 scoringVersion=v2");
    List<String> names = new ArrayList<>();
    root.fieldNames().forEachRemaining(names::add);
    assertEquals(
        1,
        names.indexOf("scoringVersion") - names.indexOf("suiteVersion"),
        "scoringVersion 必须紧跟 suiteVersion，实际键序=" + names);
  }

  @Test
  void nonV7TargetWriteDoesNotRegisterScoringVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve("baseline-v1.json");
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertFalse(root.has("scoringVersion"), "非 v7 产物不得带 scoringVersion 键");
  }
}
