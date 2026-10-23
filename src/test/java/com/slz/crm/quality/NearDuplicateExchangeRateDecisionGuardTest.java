package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 交换率决策锚守卫（evidence-to-operations G1 任务 1.2.3，默认测试集内跑）：只读已提交锚，不重算语料，秒级。
 *
 * <p>守六条：源锚/语料锚 SHA-256 无漂移、{@code externalCalls==0}、{@code decisionOwner=="OWNER"}、{@code verdict}
 * 只取三选一且与网格重算一致、{@code assumptions}（A1–A3）与 {@code criteria}（E1–E6）逐字在锚、 {@code
 * productionConstants} 等于 {@code ImageSimilarityGate} 现值。另对每个网格点做比率-计数自洽校验（防手写死值）。
 */
class NearDuplicateExchangeRateDecisionGuardTest {

  private static final Path DECISION_FILE =
      Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json");

  private static final Path SOURCE_ANCHOR_FILE =
      Path.of("docs", "rag-quality", "near-dup-mechanism-decision-v1.json");

  private static final Path CORPUS_FILE =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** proposal §1.1 登记的语料锚 SHA-256（历史真跑产物的身份指纹）。 */
  private static final String CORPUS_SHA256_REGISTERED =
      "82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode decision;

  @BeforeAll
  static void loadDecision() throws Exception {
    assertTrue(Files.exists(DECISION_FILE), "交换率锚缺失（G1 未产出）: " + DECISION_FILE.toAbsolutePath());
    decision = MAPPER.readTree(Files.readAllBytes(DECISION_FILE));
  }

  @Test
  @DisplayName("源锚/语料锚身份：SHA-256 与两份已提交文件实算一致，语料锚等于历史登记值")
  void anchorIdentitiesMatchCommittedFiles() throws Exception {
    assertEquals(
        sha256(Files.readAllBytes(SOURCE_ANCHOR_FILE)),
        decision.path("sourceAnchorSha256").asText(),
        "上一卡机制决策锚已漂移（被重算过？）：交换率锚必须跟随重锚");
    assertEquals(
        SOURCE_ANCHOR_FILE.toString().replace('\\', '/'), decision.path("sourceAnchor").asText());
    String corpusSha = sha256(Files.readAllBytes(CORPUS_FILE));
    assertEquals(corpusSha, decision.path("corpusAnchorSha256").asText(), "校准锚已漂移：语料被改但交换率锚没重算");
    assertEquals(CORPUS_SHA256_REGISTERED, corpusSha, "校准锚 SHA-256 与 proposal §1.1 登记值不一致（语料被改？）");
  }

  @Test
  @DisplayName("C4/E6 底线：externalCalls==0 且 decisionOwner==OWNER")
  void externalCallsZeroAndOwnerIsOwner() {
    assertEquals(0, decision.path("externalCalls").asInt(), "交换率锚必须零外呼");
    assertEquals("OWNER", decision.path("decisionOwner").asText(), "decisionOwner 必须是 OWNER");
    assertFalse(decision.path("decisionScope").asText().isBlank(), "decisionScope（不改常量、不拍板）不能为空");
  }

  @Test
  @DisplayName("E6：verdict 三选一，且与网格重算分支一致（免费档⇔freeTier 非空）")
  void verdictInRangeAndConsistentWithGrid() {
    String verdict = decision.path("verdict").asText();
    assertTrue(
        NearDuplicateExchangeRateAnalyzer.VERDICTS.contains(verdict),
        "verdict 只能三选一，实测: " + verdict);
    JsonNode baseline = decision.path("baseline");
    boolean anyImprovement = false;
    boolean freeTierByRecompute = false;
    double bestFreeRetention = -1D;
    double bestFreeFloor = -1D;
    for (JsonNode point : gridItems()) {
      double floor = point.path("floor").asDouble();
      double retention = point.path("R").asDouble();
      double sameSuppression = point.path("S_same").asDouble();
      if (floor < baseline.path("floor").asDouble() && retention > baseline.path("R").asDouble()) {
        anyImprovement = true;
        if (sameSuppression <= baseline.path("S_same").asDouble()) {
          if (retention > bestFreeRetention
              || (retention == bestFreeRetention && floor > bestFreeFloor)) {
            bestFreeRetention = retention;
            bestFreeFloor = floor;
            freeTierByRecompute = true;
          }
        }
      }
    }
    JsonNode freeTier = decision.path("freeTier");
    if (NearDuplicateExchangeRateAnalyzer.VERDICT_FREE_TIER.equals(verdict)) {
      assertFalse(freeTier.isMissingNode() || freeTier.isNull(), "FREE-TIER-FOUND 必须给出免费档");
      assertEquals(
          freeTier.path("floor").asDouble(),
          bestFreeFloor,
          1e-9D,
          "免费档必须是网格重算出的 R 最大（并列取最高 floor）那一点");
    } else {
      assertTrue(
          freeTier.isMissingNode() || freeTier.isNull(),
          "verdict 不是 FREE-TIER-FOUND 时 freeTier 必须为空");
      if (NearDuplicateExchangeRateAnalyzer.VERDICT_EXCHANGE_RATE.equals(verdict)) {
        assertTrue(anyImprovement, "EXCHANGE-RATE 要求网格上存在 R 提升档（重算未找到）");
      } else {
        assertFalse(anyImprovement, "UNDECIDABLE-BY-DATA 要求网格上不存在 R 提升档（重算却找到了）");
      }
    }
    assertFalse(decision.path("verdictReason").asText().isBlank(), "verdictReason 不能为空");
  }

  @Test
  @DisplayName("E1/E2：每个网格点恒等式与比率-计数自洽（防手写死值）")
  void everyPointSelfConsistent() {
    JsonNode baseline = decision.path("baseline");
    for (JsonNode point : gridItems()) {
      int keptSame = point.path("keptSame").asInt();
      int keptCross = point.path("keptCross").asInt();
      int dropped = point.path("dropped").asInt();
      assertEquals(
          276,
          keptSame + keptCross + dropped,
          "E2 恒等式被破坏: floor=" + point.path("floor").asDouble());
      assertEquals(
          round6(point.path("retainedNearDup").asInt() / 120D),
          point.path("R").asDouble(),
          "R 与计数不自洽: floor=" + point.path("floor").asDouble());
      assertEquals(
          round6(point.path("bandSame").asInt() / 100D),
          point.path("S_same").asDouble(),
          "S_same 与计数不自洽: floor=" + point.path("floor").asDouble());
      assertEquals(
          round6(point.path("bandCross").asInt() / 176D),
          point.path("C_cross").asDouble(),
          "C_cross 与计数不自洽: floor=" + point.path("floor").asDouble());
      assertEquals(
          point.path("bandSame").asInt() - baseline.path("bandSame").asInt(),
          point.path("extraSuppressedSameDoc").asInt(),
          "extraSuppressedSameDoc 与 bandSame 差不自洽: floor=" + point.path("floor").asDouble());
      assertTrue(
          point.has("retainedByKind"), "E5 要求逐 kind 保留数: floor=" + point.path("floor").asDouble());
    }
  }

  @Test
  @DisplayName("E5：网格 200 点、步长 0.0005、端点 0.90/0.9995；基线档=0.9887 且与上一卡总数相等")
  void gridCompleteAndBaselinePinned() {
    JsonNode grid = decision.path("grid");
    assertEquals(200, grid.path("points").asInt(), "必须 200 点");
    assertEquals(0.0005D, grid.path("step").asDouble(), 1e-12D);
    assertEquals(200, gridItems().size(), "grid.items 必须逐点落齐");
    double previous = -1D;
    for (JsonNode point : gridItems()) {
      double floor = point.path("floor").asDouble();
      if (previous >= 0D) {
        assertEquals(0.0005D, round6(floor - previous), 1e-9D, "步长必须 0.0005，断点 " + floor);
      }
      previous = floor;
    }
    assertEquals(0.90D, gridItems().get(0).path("floor").asDouble(), 1e-12D);
    assertEquals(0.9995D, gridItems().get(199).path("floor").asDouble(), 1e-12D);

    JsonNode baseline = decision.path("baseline");
    assertEquals(0.9887D, baseline.path("floor").asDouble(), 1e-12D, "基线档必须是精确 0.9887");
    assertEquals(70, baseline.path("retainedNearDup").asInt(), "基线保留数必须 70/120");
    assertEquals(
        3,
        baseline.path("keptSame").asInt() + baseline.path("keptCross").asInt(),
        "基线负样本泄漏必须 3（与上一卡锚 leakedNegative 相等）");
    assertEquals(273, baseline.path("dropped").asInt());
    assertEquals(2, baseline.path("bandSame").asInt(), "基线有害半边必须 2（same-class）");
    assertEquals(1, baseline.path("bandCross").asInt(), "基线无害半边必须 1（cross-class）");
  }

  @Test
  @DisplayName("E1–E6 判据与 A1–A3 假设：锚内逐字回显，禁改写")
  void criteriaAndAssumptionsVerbatim() {
    JsonNode criteria = decision.path("criteria");
    NearDuplicateExchangeRateDecisionTest.CRITERIA.forEach(
        (key, value) ->
            assertEquals(value, criteria.path(key).asText(), key + " 与 proposal §1.2 不一致（判据文案禁改）"));
    JsonNode assumptions = decision.path("assumptions");
    NearDuplicateExchangeRateDecisionTest.ASSUMPTIONS.forEach(
        (key, value) -> {
          assertFalse(assumptions.path(key).asText().isBlank(), "缺假设 " + key + "（A1–A3 必须入锚）");
          assertEquals(value, assumptions.path(key).asText(), key + " 与 proposal §1.3 不一致（禁改写）");
        });
  }

  @Test
  @DisplayName("生产常量回显：锚内 threshold/floor/三档权重等于 ImageSimilarityGate 现值")
  void productionConstantsMatchImageSimilarityGate() {
    JsonNode production = decision.path("productionConstants");
    assertEquals(
        ImageSimilarityGate.DEFAULT_THRESHOLD,
        production.path("threshold").asDouble(),
        1e-12D,
        "threshold 漂移：src/main 改了阈值但锚没重算");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_FLOOR,
        production.path("nearDuplicateFloor").asDouble(),
        1e-12D,
        "floor 漂移");
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

  private static JsonNode gridItems() {
    JsonNode items = decision.path("grid").path("items");
    assertTrue(items.isArray(), "锚缺 grid.items 数组");
    return items;
  }

  private static double round6(double value) {
    return Math.round(value * 1_000_000D) / 1_000_000D;
  }

  private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    StringBuilder out = new StringBuilder();
    for (byte item : digest.digest(bytes)) {
      out.append(Character.forDigit((item >> 4) & 0xF, 16));
      out.append(Character.forDigit(item & 0xF, 16));
    }
    return out.toString();
  }
}
