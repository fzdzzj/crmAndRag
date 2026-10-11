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
 * RagReal 域 v4 锚点校验离线锁测（fix-citation-grading-overlap 任务 4.2，红测先行）。
 *
 * <p>纯 JVM、零外发、零模型调用，日常 {@code mvn test} 与 CI 阶段 1 真跑。锁 design D3 的两形态： <b>在场</b>——{@code
 * baseline-v4.json} 在场时本轮报告按 scoringVersion=v2（多块承载）口径对账 citP， recall/ansC 沿用<b>同一锚点文件</b>登记值，下限 =
 * 记录值 × 95%（RagQualityRegressionTest FLOOR_RATIO 房风）； <b>缺席</b>——v4 不在场（首次真跑产出前）或产物路径不是 v4
 * 锚点时校验整体静默跳过， 锚点校验不因本卡代码变红。另锁写侧：v4 产物落盘时登记 scoringVersion=v2（紧跟 suiteVersion）， 其他产物文件永不获得该键（旧锚点
 * SHA256 零漂移的前提之一）。
 */
class RagBenchmarkV4AnchorValidationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** 与被测实现同源的 v4 锚点文件名（避免测试里出现第二个字符串真相源）。 */
  private static final String V4_NAME = RagRealRetrievalBenchmarkIT.V4_ANCHOR_FILE_NAME;

  /**
   * 锚点 JSON 样例（结构对齐真跑产物顶层：suiteVersion + scoringVersion + metrics；cases 段不参与对账，省略）。
   *
   * @param scoringVersion 传 {@code null} 表示整键缺失（登记缺失形态）
   */
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
    root.put("generatedAt", "2026-10-11T00:00:00Z");
    ObjectNode metrics = root.putObject("metrics");
    metrics.put("caseCount", caseCount);
    metrics.put("meanRecallAtK", recall);
    metrics.put("meanCitationPrecision", citP);
    metrics.put("meanAnswerConsistency", ansC);
    return root.toString();
  }

  /** 本轮报告样例（数值随意、能驱动对账即可）。 */
  private static Report report(int caseCount, double citP, double recall, double ansC) {
    Metrics metrics =
        new Metrics(caseCount, recall, 0.26d, 0.92d, 1.0d, citP, ansC, 400L, 4000L, 60000L, 0.0d);
    List<CaseScore> cases = new ArrayList<>();
    for (int i = 0; i < caseCount; i++) {
      cases.add(
          new CaseScore(
              "C-" + i, "TEXT", 1.0d, 0.4d, 1.0d, true, citP, ansC, 300L, 3000L, 900, true));
    }
    return new Report("3.0", "2026-10-11T00:00:00Z", metrics, cases);
  }

  /** 在指定目录落一个锚点样例文件（自动建父目录）。 */
  private static Path writeAnchor(Path dir, String fileName, String json) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(fileName);
    Files.writeString(file, json, StandardCharsets.UTF_8);
    return file;
  }

  /** 形态二（缺席不红）：v4 文件不在场（首次真跑产出前）时校验整体跳过——指标再离谱也不因锚点校验变红。 */
  @Test
  void absentV4AnchorSkipsValidationEvenWithDeviatingMetrics(@TempDir Path dir) {
    Path anchor = dir.resolve(V4_NAME);
    assertFalse(Files.exists(anchor), "前置：v4 锚点必须缺席");
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                anchor, report(2, 0.1d, 0.1d, 0.1d)),
        "v4 锚点缺席必须整体跳过校验（锚点校验不因本卡变红）");
    assertFalse(Files.exists(anchor), "缺席跳过不得顺手创建锚点文件");
  }

  /** 形态二（非 v4 产物不校验）：产物路径不是 baseline-v4.json 时与 v4 锚点对账无意义，跳过。 */
  @Test
  void nonV4TargetSkipsValidation(@TempDir Path dir) throws Exception {
    Path other =
        writeAnchor(dir, "baseline-v1.json", anchorJson("v2", "3.0", 2, 0.9d, 0.95d, 0.95d));
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(other, report(2, 0.1d, 0.1d, 0.1d)),
        "非 v4 产物不得与 v4 锚点对账（其他 profile 产物与 v4 锚点不可比）");
  }

  /** 形态一（在场按 v2 校验 citP）：本轮 citP 跌破锚点 95% 下限 → 红且报文指名 citP 对账。 */
  @Test
  void presentV4AnchorRejectsCitPFallingBelowFloor(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V4_NAME, anchorJson("v2", "3.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    anchor, report(2, 0.60d, 0.95d, 0.95d)),
            "citP 跌破 0.80×0.95=0.76 下限必须红");
    assertTrue(
        error.getMessage().contains("meanCitationPrecision"),
        "报文要指名 citP 对账，实际=" + error.getMessage());
  }

  /** 形态一（下限内放行）：三项均值都在锚点 ×0.95 之上 → 静默通过、不抛不写。 */
  @Test
  void presentV4AnchorAcceptsMetricsWithinFloor(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V4_NAME, anchorJson("v2", "3.0", 2, 0.80d, 0.95d, 0.95d));
    // 0.79 / 0.93 / 0.93 分别 ≥ 0.76 / 0.9025 / 0.9025
    assertDoesNotThrow(
        () ->
            RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                anchor, report(2, 0.79d, 0.93d, 0.93d)),
        "三项均在下限之上必须放行");
  }

  /** 形态一（recall/ansC 沿用同文件值对账）：任一跌破下限 → 红且报文指名对应指标。 */
  @Test
  void presentV4AnchorRejectsRecallAndAnsCFromSameFile(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V4_NAME, anchorJson("v2", "3.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError recallError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    anchor, report(2, 0.80d, 0.90d, 0.95d)),
            "recall 0.90 < 0.95×0.95=0.9025 必须红");
    assertTrue(
        recallError.getMessage().contains("meanRecallAtK"),
        "报文要指名 recall 对账，实际=" + recallError.getMessage());
    AssertionError anscError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.90d)),
            "ansC 0.90 < 0.95×0.95=0.9025 必须红");
    assertTrue(
        anscError.getMessage().contains("meanAnswerConsistency"),
        "报文要指名 answerConsistency 对账，实际=" + anscError.getMessage());
  }

  /** 形态一（结构登记）：scoringVersion 缺失或不是 v2 → 拒绝对账（多块承载口径锚点必须带登记字段）。 */
  @Test
  void presentV4AnchorRejectsMissingOrNonV2ScoringVersion(@TempDir Path dir) throws Exception {
    Path missing =
        writeAnchor(
            dir.resolve("missing"), V4_NAME, anchorJson(null, "3.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError missingError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    missing, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion 整键缺失必须拒绝对账");
    assertTrue(
        missingError.getMessage().contains("scoringVersion"),
        "报文要指名 scoringVersion，实际=" + missingError.getMessage());
    Path v1Tagged =
        writeAnchor(dir.resolve("v1tag"), V4_NAME, anchorJson("v1", "3.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError v1Error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    v1Tagged, report(2, 0.80d, 0.95d, 0.95d)),
            "scoringVersion=v1 的锚点不得按 v2 口径对账");
    assertTrue(
        v1Error.getMessage().contains("scoringVersion"),
        "报文要指名 scoringVersion，实际=" + v1Error.getMessage());
  }

  /** 形态一（套件与规模）：suiteVersion 不一致 / caseCount 不一致（过滤跑产物）→ 拒绝对账。 */
  @Test
  void presentV4AnchorRejectsSuiteVersionOrCaseCountMismatch(@TempDir Path dir) throws Exception {
    Path suiteMismatch =
        writeAnchor(dir.resolve("suite"), V4_NAME, anchorJson("v2", "2.0", 2, 0.80d, 0.95d, 0.95d));
    AssertionError suiteError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    suiteMismatch, report(2, 0.80d, 0.95d, 0.95d)),
            "套件版本不一致禁止跨版本对账");
    assertTrue(
        suiteError.getMessage().contains("suiteVersion"),
        "报文要指名 suiteVersion，实际=" + suiteError.getMessage());
    Path countMismatch =
        writeAnchor(
            dir.resolve("count"), V4_NAME, anchorJson("v2", "3.0", 63, 0.80d, 0.95d, 0.95d));
    AssertionError countError =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    countMismatch, report(2, 0.80d, 0.95d, 0.95d)),
            "caseCount 不一致（过滤跑产物）禁止当锚点覆盖");
    assertTrue(
        countError.getMessage().contains("caseCount"),
        "报文要指名 caseCount，实际=" + countError.getMessage());
  }

  /** 形态一（零值锚点防恒真）：锚点记录 0 会让 95% 下限恒真、对账形同虚设——拒绝对账（RagQualityRegressionTest 同风格守卫）。 */
  @Test
  void presentV4AnchorRejectsZeroRecordedMetric(@TempDir Path dir) throws Exception {
    Path anchor = writeAnchor(dir, V4_NAME, anchorJson("v2", "3.0", 2, 0.0d, 0.95d, 0.95d));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                RagRealRetrievalBenchmarkIT.validateAgainstV4Anchor(
                    anchor, report(2, 0.80d, 0.95d, 0.95d)),
            "锚点 citP 记录 0 必须拒绝（下限恒真守卫）");
    assertTrue(
        error.getMessage().contains("meanCitationPrecision"), "报文要指名越界字段，实际=" + error.getMessage());
  }

  /** 写侧登记：v4 产物落盘登记 scoringVersion=v2，且插入位置紧跟 suiteVersion（与 v3 既有键序对齐）。 */
  @Test
  void v4TargetWriteRegistersScoringVersionAfterSuiteVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve(V4_NAME);
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertEquals(
        RagRealRetrievalBenchmarkIT.V4_SCORING_VERSION,
        root.path("scoringVersion").asText(""),
        "v4 产物必须登记 scoringVersion=v2");
    List<String> names = new ArrayList<>();
    root.fieldNames().forEachRemaining(names::add);
    assertEquals(
        1,
        names.indexOf("scoringVersion") - names.indexOf("suiteVersion"),
        "scoringVersion 必须紧跟 suiteVersion，实际键序=" + names);
  }

  /** 写侧边界：非 v4 产物不登记 scoringVersion——旧锚点文件被重写也永不获得该键（锚面 SHA256 零噪声）。 */
  @Test
  void nonV4TargetWriteDoesNotRegisterScoringVersion(@TempDir Path dir) throws Exception {
    Path out = dir.resolve("baseline-v1.json");
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        out, report(2, 0.80d, 0.95d, 0.95d).toJson(), RagBenchmarkRun.V1);

    JsonNode root = MAPPER.readTree(out.toFile());
    assertFalse(root.has("scoringVersion"), "非 v4 产物不得带 scoringVersion 键");
  }
}
