package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 近重复位移证据 G2 守卫（near-dup-missing-evidence 任务 2.7，默认测试集内跑，只读）。
 *
 * <ul>
 *   <li><b>矩阵对称性</b>：方阵、逐元素对称（精确相等）、对角与 selfCosines 一致；
 *   <li><b>对账自洽</b>：maxAbsDeviation 与 396 对逐对重算同值、超阈对数与「是否停组」自洽、≤1e-3 才允许发布；
 *   <li><b>proxyVerdict 三选一</b>：与 hasCosAtOrAboveFloor / maxVariantVsThird / distanceToFloor 互锁
 *       （SUFFICIENT 时 max 必须低于 floor——把 max 改到 ≥0.9887 仍写 SUFFICIENT 即红）；
 *   <li><b>外呼次数</b>：≥ 图数（144）且分项一致（不许小于图数）。
 * </ul>
 */
class ImageVariantCrossMatrixGuardTest {

  private static final Path ANCHOR =
      Path.of("docs", "rag-quality", "image-variant-cross-matrix-v1.json");

  private static final Path CALIBRATION =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  private static final double DEVIATION_LIMIT = 1e-3D;

  private static final double NEAR_DUPLICATE_FLOOR = 0.9887D;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  private static JsonNode calibration;

  private static double[][] matrix;

  private static List<String> keys;

  private static Map<String, Integer> index;

  @BeforeAll
  static void loadAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR), "G2 锚缺失（未真跑或被删）: " + ANCHOR.toAbsolutePath());
    anchor = MAPPER.readTree(Files.readAllBytes(ANCHOR));
    assertTrue(Files.exists(CALIBRATION), "定标锚缺失: " + CALIBRATION);
    calibration = MAPPER.readTree(Files.readAllBytes(CALIBRATION));
    keys = new ArrayList<>();
    anchor.path("keys").forEach(key -> keys.add(key.asText()));
    matrix = new double[keys.size()][keys.size()];
    JsonNode rows = anchor.path("matrix").path("rows");
    assertEquals(keys.size(), rows.size(), "矩阵行数必须等于键数");
    for (int i = 0; i < keys.size(); i++) {
      JsonNode row = rows.get(i);
      assertEquals(keys.size(), row.size(), "矩阵第 " + i + " 行列数必须等于键数");
      for (int j = 0; j < keys.size(); j++) {
        matrix[i][j] = row.get(j).asDouble();
      }
    }
    index = new LinkedHashMap<>();
    for (int i = 0; i < keys.size(); i++) {
      index.put(keys.get(i), i);
    }
  }

  @Test
  @DisplayName("矩阵对称性：方阵、逐元素对称、对角与 selfCosines 一致（不硬写 1.0 而实测）")
  void matrixSymmetricAndDiagonalMatchesSelfCosines() {
    int n = keys.size();
    JsonNode selfCosines = anchor.path("selfCosines");
    assertEquals(n, selfCosines.size(), "selfCosines 长度必须等于键数");
    for (int i = 0; i < n; i++) {
      assertEquals(
          selfCosines.get(i).asDouble(),
          matrix[i][i],
          "对角元素必须与 selfCosines[" + i + "] 一致（实测自余弦）: " + keys.get(i));
      for (int j = i + 1; j < n; j++) {
        assertEquals(matrix[i][j], matrix[j][i], "矩阵必须对称: " + keys.get(i) + " vs " + keys.get(j));
      }
    }
    assertEquals(anchor.path("matrix").path("dimension").asInt(), n, "matrix.dimension 必须等于键数");
  }

  @Test
  @DisplayName("M2 对账重算：396 对逐对偏差与锚登记同值；≤1e-3 才允许发布；超阈对数自洽")
  void deviationReconciliationRecomputed() {
    int pairs = 0;
    double maxDeviation = 0D;
    int overThreshold = 0;
    double nearDupMax = 0D;
    double sameClassMax = 0D;
    double crossClassMax = 0D;
    for (JsonNode pair : calibration.path("pairs")) {
      String stratum = pair.path("stratum").asText();
      if (!"near-duplicate".equals(stratum)
          && !"same-class".equals(stratum)
          && !"cross-class".equals(stratum)) {
        continue;
      }
      Integer left = index.get(pair.path("leftKey").asText());
      Integer right = index.get(pair.path("rightKey").asText());
      assertTrue(left != null && right != null, "定标锚对不在矩阵键集: " + pair);
      double deviation = Math.abs(matrix[left][right] - pair.path("cosine").asDouble());
      pairs++;
      maxDeviation = Math.max(maxDeviation, deviation);
      if (deviation > DEVIATION_LIMIT) {
        overThreshold++;
      }
      if ("near-duplicate".equals(stratum)) {
        nearDupMax = Math.max(nearDupMax, deviation);
      } else if ("same-class".equals(stratum)) {
        sameClassMax = Math.max(sameClassMax, deviation);
      } else {
        crossClassMax = Math.max(crossClassMax, deviation);
      }
    }
    assertEquals(396, pairs, "对账对数必须 396（禁换分母）");
    JsonNode deviation = anchor.path("deviationReconciliation");
    assertEquals(pairs, deviation.path("pairs").asInt(), "对账对数与锚登记不符");
    assertEquals(
        maxDeviation, deviation.path("maxAbsDeviation").asDouble(), "maxAbsDeviation 重算不符（手改即红）");
    assertEquals(overThreshold, deviation.path("overThresholdCount").asInt(), "超阈对数重算不符");
    assertEquals(
        nearDupMax,
        deviation.path("nearDuplicateMaxDeviation").asDouble(),
        1e-15D,
        "near-duplicate 档偏差重算不符");
    assertEquals(
        sameClassMax,
        deviation.path("sameClassMaxDeviation").asDouble(),
        1e-15D,
        "same-class 档偏差重算不符");
    assertEquals(
        crossClassMax,
        deviation.path("crossClassMaxDeviation").asDouble(),
        1e-15D,
        "cross-class 档偏差重算不符");
    assertEquals(DEVIATION_LIMIT, deviation.path("limit").asDouble(), 1e-15D, "偏差阈值必须 1e-3（判据冻结）");
    assertTrue(
        maxDeviation <= DEVIATION_LIMIT,
        "maxAbsDeviation 超阈值（" + maxDeviation + " > " + DEVIATION_LIMIT + "）：锚不允许发布（应停 G2）");
    assertEquals(
        "PASS", deviation.path("verdict").asText(), "对账 verdict 必须与 maxAbsDeviation ≤ limit 自洽");
  }

  @Test
  @DisplayName("M3 重算：variant↔第三图分位与带内存在性逐值复算；proxyVerdict 三选一且互锁")
  void variantVsThirdRecomputedAndVerdictLocked() {
    List<String> baseKeys = keys.stream().filter(key -> !key.contains("@")).toList();
    List<String> variantKeys = keys.stream().filter(key -> key.contains("@")).toList();
    JsonNode corpus = anchor.path("corpus");
    assertEquals(baseKeys.size(), corpus.path("bases").asInt(), "base 数与键集不符");
    assertEquals(variantKeys.size(), corpus.path("variants").asInt(), "变体数与键集不符");
    assertEquals(
        corpus.path("bases").asInt() + corpus.path("variants").asInt(),
        corpus.path("images").asInt(),
        "images = bases + variants");

    List<Double> third = new ArrayList<>();
    for (String variant : variantKeys) {
      String ownBase = variant.substring(0, variant.indexOf('@'));
      for (String base : baseKeys) {
        if (base.equals(ownBase)) {
          continue;
        }
        third.add(matrix[index.get(variant)][index.get(base)]);
      }
    }
    int expectedCount = variantKeys.size() * (baseKeys.size() - 1);
    JsonNode recorded = anchor.path("variantVsThirdImage");
    assertEquals(expectedCount, recorded.path("count").asInt(), "variant↔第三图取值数与登记不符");
    assertEquals(expectedCount, third.size(), "variant↔第三图重算取值数不符");

    double[] sorted = third.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    assertEquals(nearestRank(sorted, 0.50D), recorded.path("p50").asDouble(), 1e-15D, "p50 重算不符");
    assertEquals(nearestRank(sorted, 0.90D), recorded.path("p90").asDouble(), 1e-15D, "p90 重算不符");
    assertEquals(nearestRank(sorted, 0.99D), recorded.path("p99").asDouble(), 1e-15D, "p99 重算不符");
    double max = sorted[sorted.length - 1];
    assertEquals(
        max, recorded.path("maxVariantVsThird").asDouble(), 1e-15D, "maxVariantVsThird 重算不符（手改即红）");
    boolean hasInBand = max >= NEAR_DUPLICATE_FLOOR;
    assertEquals(
        hasInBand,
        recorded.path("hasCosAtOrAboveFloor").asBoolean(),
        "hasCosAtOrAboveFloor 与矩阵重算不符");

    String verdict = anchor.path("proxyVerdict").asText();
    switch (verdict) {
      case "PROXY-UNDERSTATES" -> {
        assertTrue(hasInBand, "PROXY-UNDERSTATES 必须存在 cos ≥ 0.9887 的变体↔第三图对");
        assertTrue(
            recorded.path("distanceToFloor").isNull(), "UNDERSTATES 时 distanceToFloor 必须为 null");
      }
      case "PROXY-SUFFICIENT" -> {
        assertFalse(hasInBand, "PROXY-SUFFICIENT 要求全部变体↔第三图低于 0.9887（实测 max=" + max + "）");
        assertEquals(
            NEAR_DUPLICATE_FLOOR - max,
            recorded.path("distanceToFloor").asDouble(),
            1e-15D,
            "SUFFICIENT 必须附 distanceToFloor = floor − max");
      }
      case "PROXY-OVERSTATES" ->
          throw new AssertionError("PROXY-OVERSTATES 的触发判据未在锚内定义（M3 只定义两支），出现即视为口径漂移");
      default -> throw new AssertionError("proxyVerdict 三选一越界: " + verdict);
    }
  }

  @Test
  @DisplayName("外呼次数 ≥ 图数且分项一致；上游锚 SHA 实算比对；自述字段齐")
  void externalCallsAndAnchors() throws Exception {
    int externalCalls = anchor.path("externalCalls").asInt(-1);
    int images = anchor.path("corpus").path("images").asInt();
    assertTrue(images > 0, "corpus.images 缺失");
    assertTrue(externalCalls >= images, "外呼次数必须 ≥ 图数（每图至少单编码），实测 " + externalCalls);
    JsonNode calls = anchor.path("callBreakdown");
    int sum =
        calls.path("visionCalls").asInt(-1)
            + calls.path("embedImageCalls").asInt(-1)
            + calls.path("embedCalls").asInt(-1);
    assertEquals(externalCalls, sum, "callBreakdown 三分项之和必须等于 externalCalls");
    assertEquals(images, calls.path("embedImageCalls").asInt(), "G2 只有视觉编码：embedImageCalls 必须等于图数");

    JsonNode sourceAnchors = anchor.path("sourceAnchors");
    for (String field :
        List.of("corpusAnchor", "fusionImpactAnchor", "exchangeRateAnchor", "truthAnchor")) {
      Path file = Path.of(sourceAnchors.path(field).asText());
      assertTrue(Files.exists(file), "上游锚不存在: " + file);
      assertEquals(
          sha256(Files.readAllBytes(file)),
          sourceAnchors.path(field + "Sha256").asText(),
          "上游锚 SHA-256 与实算不一致: " + file);
    }
    assertEquals("OWNER", anchor.path("decisionOwner").asText(), "decisionOwner 必须是 OWNER");
    for (String criterion : List.of("M1", "M2", "M3", "M4")) {
      assertFalse(anchor.path("criteria").path(criterion).asText().isBlank(), "锚缺判据 " + criterion);
    }
    assertEquals(
        "NearDuplicateCorpus.generate()（确定性，未改生成逻辑、未新增语料）",
        anchor.path("corpus").path("source").asText(),
        "语料来源自述漂移（禁改生成逻辑）");
  }

  /** 最近秩分位（与 IT 同一实现：升序第 ⌈q·n⌉ 个）。 */
  private static double nearestRank(double[] sorted, double q) {
    int rank = (int) Math.ceil(q * sorted.length);
    rank = Math.min(Math.max(rank, 1), sorted.length);
    return sorted[rank - 1];
  }

  private static String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    StringBuilder out = new StringBuilder();
    for (byte item : digest.digest(bytes)) {
      out.append(Character.forDigit((item >> 4) & 0xF, 16));
      out.append(Character.forDigit(item & 0xF, 16));
    }
    return out.toString();
  }
}
