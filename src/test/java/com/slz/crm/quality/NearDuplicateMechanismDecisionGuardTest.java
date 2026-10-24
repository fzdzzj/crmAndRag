package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 决策锚守卫（G1 任务 1.4.6，默认测试集内跑）：只读已提交锚，不生成语料、不重算余弦，秒级。
 *
 * <p>守六条：网格完整（200/25/325 且步长与端点精确）、{@code externalCalls==0}、{@code verdict} 与 C1–C3 规则重算一致、 {@code
 * criteria} 与 proposal §1.2 逐字相同、{@code productionConstants} 等于 {@code ImageSimilarityGate} 现值、
 * 每个网格点都带完整判据字段（禁止只报最优点）。
 */
class NearDuplicateMechanismDecisionGuardTest {

  private static final Path DECISION_FILE =
      Path.of("docs", "rag-quality", "near-dup-mechanism-decision-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** proposal §1.2 判据原文（逐字比对锚里的 criteria）。 */
  private static final Map<String, String> EXPECTED_CRITERIA = expectedCriteria();

  private static JsonNode decision;

  @BeforeAll
  static void loadDecision() throws IOException {
    assertTrue(Files.exists(DECISION_FILE), "决策锚缺失（G1 未产出）: " + DECISION_FILE.toAbsolutePath());
    decision = MAPPER.readTree(Files.readAllBytes(DECISION_FILE));
  }

  @Test
  @DisplayName("C6：floor 200 点、步长 0.0005、端点 0.90/0.9995；dHash 0..24 无缺；混合 325 组无重复")
  void gridIsCompleteAndStepped() {
    JsonNode m1 = points("M1");
    assertEquals(200, m1.size(), "M1 必须 200 点");
    double previous = -1D;
    for (JsonNode node : m1) {
      double floor = node.path("floor").asDouble();
      if (previous >= 0D) {
        assertEquals(0.0005D, round6(floor - previous), 1e-9D, "步长必须 0.0005，断点 " + floor);
      }
      previous = floor;
    }
    assertEquals(0.90D, m1.get(0).path("floor").asDouble(), 1e-12D);
    assertEquals(0.9995D, m1.get(m1.size() - 1).path("floor").asDouble(), 1e-12D);

    JsonNode m2 = points("M2");
    assertEquals(25, m2.size(), "M2 必须 25 点");
    Set<Integer> distances = new LinkedHashSet<>();
    m2.forEach(node -> distances.add(node.path("distance").asInt()));
    Set<Integer> expected = new LinkedHashSet<>();
    for (int distance = 0; distance <= 24; distance++) {
      expected.add(distance);
    }
    assertEquals(expected, distances, "dHash 距离必须 0..24 全覆盖");

    JsonNode m3 = points("M3");
    assertEquals(325, m3.size(), "M3 必须 325 组（D_low ≤ D_high ≤ 24 的全组合）");
    Set<String> combos = new LinkedHashSet<>();
    for (JsonNode node : m3) {
      int low = node.path("distanceLow").asInt();
      int high = node.path("distanceHigh").asInt();
      assertTrue(low <= high, "M3 必须满足 D_low ≤ D_high: " + low + "/" + high);
      assertTrue(low >= 0 && high <= 24, "M3 距离必须在 0..24: " + low + "/" + high);
      assertTrue(combos.add(low + ":" + high), "M3 组合重复: " + low + "/" + high);
    }
    assertEquals(325, combos.size());
  }

  @Test
  @DisplayName("C4：externalCalls == 0（锚自报）且每个网格点都带完整判据字段")
  void externalCallsAreZeroAndEveryPointCarriesOutcome() {
    assertEquals(0, decision.path("externalCalls").asInt(), "外呼次数必须 0");
    assertEquals(0, decision.path("selfChecks").path("externalCalls").asInt());
    for (String mechanism : List.of("M1", "M2", "M3")) {
      for (JsonNode node : points(mechanism)) {
        JsonNode outcome = node.path("outcome");
        assertFalse(outcome.isMissingNode(), mechanism + " 网格点缺 outcome（禁止只报最优点）");
        assertTrue(
            outcome.has("retainedVariant") && outcome.has("leakedNegative"),
            mechanism + " 网格点缺保留/泄漏计数");
        assertTrue(outcome.has("retention") && outcome.has("leakage"), mechanism + " 网格点缺比率");
        assertTrue(outcome.has("retainedByKind"), mechanism + " 网格点缺逐 kind 计数（C3 需要）");
      }
    }
  }

  @Test
  @DisplayName("C5：verdict 与锚内 C1/C2/C3 按四支重算一致，且只取三选一")
  void verdictAgreesWithCriteriaRecomputation() {
    boolean anyFull = false;
    boolean anyHalfPoint = false;
    boolean anyC1 = false;
    boolean anyC2 = false;
    for (String mechanism : List.of("M1", "M2", "M3")) {
      for (JsonNode node : points(mechanism)) {
        JsonNode outcome = node.path("outcome");
        boolean c1 = outcome.path("satisfiesC1").asBoolean();
        boolean c2 = outcome.path("satisfiesC2").asBoolean();
        boolean c3 = outcome.path("satisfiesC3").asBoolean();
        anyC1 = anyC1 || c1;
        anyC2 = anyC2 || c2;
        anyFull = anyFull || (c1 && c2 && c3);
        anyHalfPoint = anyHalfPoint || (c1 && c2);
        // 锚里的 satisfiesC1/C2 必须与比率自洽（防手写死值）
        assertEquals(
            outcome.path("retention").asDouble() >= 0.90D,
            outcome.path("satisfiesC1").asBoolean(),
            "satisfiesC1 与 retention 不自洽");
        assertEquals(
            outcome.path("leakage").asDouble() <= 0.02D,
            outcome.path("satisfiesC2").asBoolean(),
            "satisfiesC2 与 leakage 不自洽");
      }
    }
    String expected;
    if (anyFull) {
      expected = "GO";
    } else if (anyHalfPoint || anyC1 || anyC2) {
      expected = "UNDECIDABLE-BY-DATA";
    } else {
      expected = "NO-GO";
    }
    assertEquals(expected, decision.path("verdict").asText(), "verdict 必须与 C1–C3 四支重算一致");
    assertTrue(
        List.of("GO", "NO-GO", "UNDECIDABLE-BY-DATA").contains(decision.path("verdict").asText()),
        "verdict 只能三选一");
  }

  @Test
  @DisplayName("C1/C2/C3 门槛与判据文案：criteria 与 proposal §1.2 逐字相同")
  void criteriaAreCopiedVerbatimFromProposal() {
    JsonNode criteria = decision.path("criteria");
    for (Map.Entry<String, String> entry : EXPECTED_CRITERIA.entrySet()) {
      assertEquals(
          entry.getValue(),
          criteria.path(entry.getKey()).asText(),
          entry.getKey() + " 与 proposal §1.2 不一致（判据文案禁改）");
    }
  }

  @Test
  @DisplayName("生产常量回显：threshold/三档权重等于现值；floor 等于冻结带基准（锚测于 0.9887，不随生产准入下限 0.982 变动）")
  void productionConstantsMatchImageSimilarityGate() {
    JsonNode production = decision.path("productionConstants");
    assertEquals(
        ImageSimilarityGate.DEFAULT_THRESHOLD,
        production.path("threshold").asDouble(),
        1e-12D,
        "threshold 漂移：src/main 改了阈值但锚没重算");
    // 比对冻结带基准而非生产常量：锚是「测于 0.9887」的历史事实，生产准入下限已下探至 0.982（near-dup-floor-lowering）
    assertEquals(
        ImageSimilarityGate.BAND_REFERENCE_FLOOR,
        production.path("nearDuplicateFloor").asDouble(),
        1e-12D,
        "floor 漂移（应等于冻结带基准 0.9887）");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH,
        production.path("weightHigh").asDouble(),
        1e-12D,
        "高档权重漂移");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID,
        production.path("weightMid").asDouble(),
        1e-12D,
        "中档权重漂移");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW,
        production.path("weightLow").asDouble(),
        1e-12D,
        "低档权重漂移");
  }

  @Test
  @DisplayName("语料身份与自证①：24 base / 120 变体 / 144 图 / 540 对，144 同图对距离 0 且余弦 1")
  void corpusIdentityAndSameImageSelfProof() {
    JsonNode corpus = decision.path("corpus");
    assertEquals(24, corpus.path("bases").asInt());
    assertEquals(120, corpus.path("variants").asInt());
    assertEquals(144, corpus.path("images").asInt());
    assertEquals(540, corpus.path("pairs").asInt());
    assertEquals(288, corpus.path("imageEmbeddingCalls").asInt());
    assertEquals(144, corpus.path("strata").path("same-image").asInt());
    assertEquals(120, corpus.path("strata").path("near-duplicate").asInt());
    assertEquals(100, corpus.path("strata").path("same-class").asInt());
    assertEquals(176, corpus.path("strata").path("cross-class").asInt());
    assertEquals(64, corpus.path("anchorSha256").asText().length(), "锚 SHA-256 必须是 64 位十六进制");
    JsonNode selfChecks = decision.path("selfChecks");
    assertEquals(144, selfChecks.path("sameImagePairs").asInt());
    assertEquals(144, selfChecks.path("sameImageDistanceZero").asInt(), "自证①：144 对同图对必须距离 0");
    assertEquals(144, selfChecks.path("sameImageCosineOne").asInt(), "自证①：144 对同图对余弦必须为 1");
    assertEquals(0.0005D, selfChecks.path("floorGridStep").asDouble(), 1e-12D);
  }

  @Test
  @DisplayName("基线照判：R_keep=70/120、L=3/276，帕累托非空且含目标提升段落")
  void baselineAndParetoArePresent() {
    JsonNode baseline = decision.path("baseline").path("outcome");
    assertEquals(70, baseline.path("retainedVariant").asInt(), "基线保留数必须是 70");
    assertEquals(120, baseline.path("variantTotal").asInt());
    assertEquals(3, baseline.path("leakedNegative").asInt(), "基线泄漏数必须是 3");
    assertEquals(276, baseline.path("negativeTotal").asInt());
    for (String mechanism : List.of("M1", "M2", "M3")) {
      assertTrue(decision.path("pareto").path(mechanism).size() > 0, mechanism + " 的帕累托前沿不能为空");
    }
    assertTrue(decision.path("raisingTarget").has("byMechanism"), "缺 raisingTarget.byMechanism");
    assertFalse(decision.path("verdictReason").asText().isBlank(), "verdictReason 不能为空");
  }

  private static JsonNode points(String mechanism) {
    JsonNode points = decision.path("mechanisms").path(mechanism).path("points");
    assertTrue(points.isArray(), mechanism + " 缺 points 数组");
    return points;
  }

  private static double round6(double value) {
    return Math.round(value * 1_000_000D) / 1_000_000D;
  }

  private static Map<String, String> expectedCriteria() {
    Map<String, String> criteria = new LinkedHashMap<>();
    criteria.put("C1", "同源保留率 R_keep ≥ 0.90（基线 0.583）");
    criteria.put("C2", "负样本泄漏率 L = retained/276 ≤ 0.02（基线 0.0109）");
    criteria.put(
        "C3", "逐 kind 保留率单调 crop-12 ≤ crop-04 ≤ scale-070 ≤ scale-085（jpeg-q80 允许与 scale-085 互换）");
    criteria.put("C4", "外呼次数 = 0");
    criteria.put("C5", "决策 ∈ {GO, NO-GO, UNDECIDABLE-BY-DATA}，三选一，禁造第四种");
    criteria.put(
        "C6", "全网格披露：floor 0.90→0.9995 步长 0.0005（200 点）、dHash D 0..24、混合 (D_low,D_high) 全组合，逐点入锚");
    return criteria;
  }
}
