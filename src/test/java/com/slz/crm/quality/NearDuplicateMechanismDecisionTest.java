package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 近重复机制决策锚的唯一生成/断言入口（G1 任务 1.4.1~1.4.5）。
 *
 * <p><b>唯一写入口</b>：只有 {@code -Dnear.dup.decision.write=true} 时才写 {@code
 * docs/rag-quality/near-dup-mechanism-decision-v1.json}；默认模式 复算全网格后<b>只断言不写</b>（与已提交锚逐字段相等，volatile
 * 字段除外）。
 *
 * <p><b>禁止二次栅格化</b>：位图一律取 {@link NearDuplicateCorpus#generate()} 已产出的 PNG 字节（该语料由生产提取器 +
 * 内存变体一次性产出）， 本类只做 PNG 解码 → dHash，不重跑 PDF 提取、不重造变体、不改语料定义；逐图解码后立刻丢弃位图，故内存占用与图数无关。
 *
 * <p><b>零外呼</b>：余弦来自已提交锚 {@code
 * docs/rag-quality/image-threshold-calibration-v2.json}（真跑结论的历史产物），本类<b>不调用</b> 任何模型接口、不读环境变量、不读
 * {@code .env}，{@code externalCalls} 恒为 0。
 */
class NearDuplicateMechanismDecisionTest {

  /** 余弦锚（真相源，本卡只读）。 */
  private static final Path ANCHOR_FILE =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** 决策锚（本卡唯一写入口的产物）。 */
  private static final Path DECISION_FILE =
      Path.of("docs", "rag-quality", "near-dup-mechanism-decision-v1.json");

  /** 写模式开关（默认 false = 只断言不写）。 */
  private static final String WRITE_FLAG = "near.dup.decision.write";

  /** 判据原文（proposal §1.2 逐字回显；守卫测试逐字比对）。 */
  static final Map<String, String> CRITERIA = criteria();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("三机制全网格复算并产出/核对决策锚（默认只断言不写）")
  void recomputeMechanismsAndEmitDecision() throws IOException {
    long startedNanos = System.nanoTime();
    assertTrue(Files.exists(ANCHOR_FILE), "余弦锚缺失: " + ANCHOR_FILE.toAbsolutePath());
    byte[] anchorBytes = Files.readAllBytes(ANCHOR_FILE);
    JsonNode anchor = MAPPER.readTree(anchorBytes);
    List<NearDuplicateMechanismAnalyzer.Pair> pairs = pairs(anchor);
    Map<String, Integer> strata = strataOf(pairs);

    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    assertEquals(24, corpus.bases().size(), "base 数必须与锚 corpus.bases=24 一致");
    assertEquals(120, corpus.variants().size(), "变体数必须与锚 corpus.variants=120 一致");
    assertEquals(144, corpus.imageCount(), "图数必须与锚 corpus.images=144 一致");
    assertEquals(0, corpus.skips().size(), "锚 corpus.skips=0；跳变体即语料口径变了");
    assertEquals(540, pairs.size(), "锚 pairs=540");
    assertEquals(144, strata.getOrDefault(NearDuplicateMechanismAnalyzer.STRATUM_SAME_IMAGE, 0));
    assertEquals(
        120, strata.getOrDefault(NearDuplicateMechanismAnalyzer.STRATUM_NEAR_DUPLICATE, 0));
    assertEquals(100, strata.getOrDefault(NearDuplicateMechanismAnalyzer.STRATUM_SAME_CLASS, 0));
    assertEquals(176, strata.getOrDefault(NearDuplicateMechanismAnalyzer.STRATUM_CROSS_CLASS, 0));

    Map<String, Long> hashes = hashesByKey(corpus, pairs);
    NearDuplicateMechanismAnalyzer.requireAligned(pairs, hashes.keySet());

    int sameImagePairs = 0;
    int sameImageDistanceZero = 0;
    int sameImageCosineOne = 0;
    for (NearDuplicateMechanismAnalyzer.Pair pair : pairs) {
      if (NearDuplicateMechanismAnalyzer.STRATUM_SAME_IMAGE.equals(pair.stratum())
          && pair.leftKey().equals(pair.rightKey())) {
        sameImagePairs++;
        if (NearDuplicateMechanismAnalyzer.hamming(hashes, pair.leftKey(), pair.rightKey()) == 0) {
          sameImageDistanceZero++;
        }
        if (Math.abs(pair.cosine() - 1D) <= 1e-9D) {
          sameImageCosineOne++;
        }
      }
    }
    assertEquals(144, sameImagePairs, "同图对必须 144 对");
    assertEquals(144, sameImageDistanceZero, "自证①：144 对同图对 dHash 距离必须全为 0");
    assertEquals(144, sameImageCosineOne, "自证①：144 对同图对余弦回读必须为 1");

    NearDuplicateMechanismAnalyzer.Outcome baseline =
        NearDuplicateMechanismAnalyzer.evaluate(
            pairs,
            hashes,
            NearDuplicateMechanismAnalyzer.Mechanism.FLOOR,
            NearDuplicateMechanismAnalyzer.COSINE_FLOOR,
            0,
            0);
    assertEquals(70, baseline.retainedVariant(), "基线保留数必须仍是 70/120（上一卡实测）");
    assertEquals(3, baseline.leakedNegative(), "基线泄漏数必须仍是 3/276（上一卡实测）");

    List<NearDuplicateMechanismAnalyzer.FloorPoint> floors =
        NearDuplicateMechanismAnalyzer.scanFloors(pairs);
    List<NearDuplicateMechanismAnalyzer.DHashPoint> dhashPoints =
        NearDuplicateMechanismAnalyzer.scanDHash(pairs, hashes);
    List<NearDuplicateMechanismAnalyzer.HybridPoint> hybridPoints =
        NearDuplicateMechanismAnalyzer.scanHybrid(pairs, hashes);
    assertEquals(200, floors.size());
    assertEquals(25, dhashPoints.size());
    assertEquals(325, hybridPoints.size());
    String verdict = NearDuplicateMechanismAnalyzer.verdict(floors, dhashPoints, hybridPoints);
    List<Scored> floorScored = scoredFloors(floors);
    List<Scored> dhashScored = scoredDhash(dhashPoints);
    List<Scored> hybridScored = scoredHybrid(hybridPoints);

    printBaseline(baseline);
    printFloors(floors);
    printDhash(dhashPoints);
    printPareto("M1", frontier(floorScored));
    printPareto("M2", frontier(dhashScored));
    printPareto("M3", frontier(hybridScored));
    printCriteria(baseline, floors, dhashPoints, hybridPoints, verdict);

    ObjectNode decision = MAPPER.createObjectNode();
    decision.put("card", "retrieval-and-contract-hardening");
    decision.put("group", "G1");
    decision.put("decisionVersion", "NEAR-DUP-MECHANISM-DECISION-1.0");
    decision.set("criteria", criteriaNode());
    decision.set("corpus", corpusNode(anchor, anchorBytes, pairs, strata, corpus));
    decision.set("productionConstants", productionNode());
    decision.set("baseline", baselineNode(baseline));
    decision.set(
        "selfChecks",
        selfCheckNode(
            sameImagePairs,
            sameImageDistanceZero,
            sameImageCosineOne,
            floors.size(),
            dhashPoints.size(),
            hybridPoints.size()));
    ObjectNode mechanisms = decision.putObject("mechanisms");
    mechanisms.set("M1", floorNodes(floors));
    mechanisms.set("M2", dhashNodes(dhashPoints));
    mechanisms.set("M3", hybridNodes(hybridPoints));
    ObjectNode pareto = decision.putObject("pareto");
    pareto.set("M1", frontier(floorScored));
    pareto.set("M2", frontier(dhashScored));
    pareto.set("M3", frontier(hybridScored));
    decision.set("raisingTarget", raisingTarget(floorScored, dhashScored, hybridScored));
    decision.put("externalCalls", 0);
    decision.put("verdict", verdict);
    decision.put("verdictReason", verdictReason(verdict, floorScored, dhashScored, hybridScored));
    decision.put("elapsedMillis", (System.nanoTime() - startedNanos) / 1_000_000L);

    if (Boolean.parseBoolean(System.getProperty(WRITE_FLAG, "false"))) {
      decision.put("generatedAt", Instant.now().toString());
      write(decision);
      System.out.println("[G1] 写模式：决策锚已写入 " + DECISION_FILE);
    } else {
      assertMatchesCommitted(decision);
      System.out.println("[G1] 默认模式：只断言不写，决策锚与复算结果一致");
    }
  }

  // ==================== 输入解析 ====================

  private static List<NearDuplicateMechanismAnalyzer.Pair> pairs(JsonNode anchor) {
    JsonNode pairsNode = anchor.path("pairs");
    assertTrue(pairsNode.isArray(), "锚缺少 pairs 数组");
    List<NearDuplicateMechanismAnalyzer.Pair> pairs = new ArrayList<>(pairsNode.size());
    for (JsonNode node : pairsNode) {
      pairs.add(
          new NearDuplicateMechanismAnalyzer.Pair(
              node.path("leftKey").asText(),
              node.path("rightKey").asText(),
              node.path("stratum").asText(),
              node.path("kind").isNull() ? null : node.path("kind").asText(),
              node.path("cosine").asDouble()));
    }
    return List.copyOf(pairs);
  }

  private static Map<String, Integer> strataOf(List<NearDuplicateMechanismAnalyzer.Pair> pairs) {
    Map<String, Integer> strata = new LinkedHashMap<>();
    for (NearDuplicateMechanismAnalyzer.Pair pair : pairs) {
      strata.merge(pair.stratum(), 1, Integer::sum);
    }
    return strata;
  }

  /**
   * 逐图解码 corpus 已产出的 PNG 字节并立即算 dHash，位图用完即丢（内存与图数无关）。
   *
   * <p>只处理样本对实际引用到的图键（146 个），未引用图不参与计算。
   */
  private static Map<String, Long> hashesByKey(
      NearDuplicateCorpus.Corpus corpus, List<NearDuplicateMechanismAnalyzer.Pair> pairs) {
    Set<String> needed = new LinkedHashSet<>();
    for (NearDuplicateMechanismAnalyzer.Pair pair : pairs) {
      needed.add(pair.leftKey());
      needed.add(pair.rightKey());
    }
    Map<String, byte[]> bytesByKey = new LinkedHashMap<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      bytesByKey.put(base.key(), base.png());
    }
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      bytesByKey.put(variant.key(), variant.png());
    }
    Map<String, Long> hashes = new LinkedHashMap<>();
    for (String key : needed) {
      hashes.put(key, PerceptualHashSupport.dHash(decode(bytesByKey.get(key), key)));
    }
    return hashes;
  }

  private static BufferedImage decode(byte[] png, String key) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
      if (image == null) {
        throw new IllegalStateException("PNG 解码失败: " + key);
      }
      return image;
    } catch (IOException exception) {
      throw new IllegalStateException("PNG 解码失败: " + key, exception);
    }
  }

  // ==================== 节点构造 ====================

  private static ObjectNode criteriaNode() {
    ObjectNode node = MAPPER.createObjectNode();
    CRITERIA.forEach(node::put);
    return node;
  }

  private static ObjectNode corpusNode(
      JsonNode anchor,
      byte[] anchorBytes,
      List<NearDuplicateMechanismAnalyzer.Pair> pairs,
      Map<String, Integer> strata,
      NearDuplicateCorpus.Corpus corpus) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("anchorFile", ANCHOR_FILE.toString().replace('\\', '/'));
    node.put("anchorSha256", RealPdfAssets.sha256(anchorBytes));
    node.put("anchorCalibrationVersion", anchor.path("calibrationVersion").asText());
    node.put("bases", corpus.bases().size());
    node.put("variants", corpus.variants().size());
    node.put("images", corpus.imageCount());
    node.put("skips", corpus.skips().size());
    node.put("pairs", pairs.size());
    node.put("imageEmbeddingCalls", anchor.path("corpus").path("imageEmbeddingCalls").asInt());
    ObjectNode strataNode = node.putObject("strata");
    strata.forEach(strataNode::put);
    ObjectNode kindNode = node.putObject("nearDuplicateByKind");
    Map<String, Integer> kindCounts = new LinkedHashMap<>();
    for (NearDuplicateMechanismAnalyzer.Pair pair : pairs) {
      if (NearDuplicateMechanismAnalyzer.STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
        kindCounts.merge(pair.kind(), 1, Integer::sum);
      }
    }
    kindCounts.forEach(kindNode::put);
    return node;
  }

  private static ObjectNode productionNode() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("threshold", NearDuplicateMechanismAnalyzer.THRESHOLD);
    node.put("nearDuplicateFloor", NearDuplicateMechanismAnalyzer.COSINE_FLOOR);
    node.put(
        "weightHigh",
        com.slz.crm.knowledge.retrieval.ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH);
    node.put(
        "weightMid", com.slz.crm.knowledge.retrieval.ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID);
    node.put(
        "weightLow", com.slz.crm.knowledge.retrieval.ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW);
    return node;
  }

  private static ObjectNode baselineNode(NearDuplicateMechanismAnalyzer.Outcome baseline) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("floor", NearDuplicateMechanismAnalyzer.COSINE_FLOOR);
    node.set("outcome", outcomeNode(baseline));
    return node;
  }

  private static ObjectNode outcomeNode(NearDuplicateMechanismAnalyzer.Outcome outcome) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("retainedVariant", outcome.retainedVariant());
    node.put("variantTotal", outcome.variantTotal());
    node.put("retention", round6(outcome.retention()));
    node.put("leakedNegative", outcome.leakedNegative());
    node.put("negativeTotal", outcome.negativeTotal());
    node.put("leakage", round6(outcome.leakage()));
    node.put("sameImageClassified", outcome.sameImageClassified());
    ObjectNode kinds = node.putObject("retainedByKind");
    outcome.retainedByKind().forEach(kinds::put);
    node.put("satisfiesC1", outcome.satisfiesC1());
    node.put("satisfiesC2", outcome.satisfiesC2());
    node.put("satisfiesC3", outcome.satisfiesC3());
    return node;
  }

  private static ObjectNode selfCheckNode(
      int samePairs, int distanceZero, int cosineOne, int floors, int dh, int hybrid) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("sameImagePairs", samePairs);
    node.put("sameImageDistanceZero", distanceZero);
    node.put("sameImageCosineOne", cosineOne);
    node.put("floorGridPoints", floors);
    node.put("floorGridStep", NearDuplicateMechanismAnalyzer.FLOOR_GRID_STEP);
    node.put("dhashPoints", dh);
    node.put("hybridPoints", hybrid);
    node.put("externalCalls", 0);
    return node;
  }

  private static ObjectNode floorNodes(List<NearDuplicateMechanismAnalyzer.FloorPoint> points) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("gridPoints", points.size());
    node.put("mechanism", "M1：threshold 固定 0.9944，仅下调 floor");
    ArrayNode array = node.putArray("points");
    for (NearDuplicateMechanismAnalyzer.FloorPoint point : points) {
      ObjectNode item = array.addObject();
      item.put("floor", round6(point.floor()));
      item.set("outcome", outcomeNode(point.outcome()));
    }
    return node;
  }

  private static ObjectNode dhashNodes(List<NearDuplicateMechanismAnalyzer.DHashPoint> points) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("gridPoints", points.size());
    node.put("mechanism", "M2：dHash-64 单阈值 D（0..24）");
    ArrayNode array = node.putArray("points");
    for (NearDuplicateMechanismAnalyzer.DHashPoint point : points) {
      ObjectNode item = array.addObject();
      item.put("distance", point.distance());
      item.set("outcome", outcomeNode(point.outcome()));
    }
    return node;
  }

  private static ObjectNode hybridNodes(List<NearDuplicateMechanismAnalyzer.HybridPoint> points) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("gridPoints", points.size());
    node.put("mechanism", "M3：dHash<=D_low ⇒ 同图；否则 dHash<=D_high 或 cosine>=0.9887 ⇒ 近似带；否则丢弃");
    ArrayNode array = node.putArray("points");
    for (NearDuplicateMechanismAnalyzer.HybridPoint point : points) {
      ObjectNode item = array.addObject();
      item.put("distanceLow", point.distanceLow());
      item.put("distanceHigh", point.distanceHigh());
      item.set("outcome", outcomeNode(point.outcome()));
    }
    return node;
  }

  // ==================== 帕累托与目标 ====================

  private record Scored(ObjectNode params, NearDuplicateMechanismAnalyzer.Outcome outcome) {}

  private static List<Scored> scoredFloors(List<NearDuplicateMechanismAnalyzer.FloorPoint> points) {
    List<Scored> scored = new ArrayList<>(points.size());
    for (NearDuplicateMechanismAnalyzer.FloorPoint point : points) {
      ObjectNode params = MAPPER.createObjectNode();
      params.put("floor", round6(point.floor()));
      scored.add(new Scored(params, point.outcome()));
    }
    return scored;
  }

  private static List<Scored> scoredDhash(List<NearDuplicateMechanismAnalyzer.DHashPoint> points) {
    List<Scored> scored = new ArrayList<>(points.size());
    for (NearDuplicateMechanismAnalyzer.DHashPoint point : points) {
      ObjectNode params = MAPPER.createObjectNode();
      params.put("distance", point.distance());
      scored.add(new Scored(params, point.outcome()));
    }
    return scored;
  }

  private static List<Scored> scoredHybrid(
      List<NearDuplicateMechanismAnalyzer.HybridPoint> points) {
    List<Scored> scored = new ArrayList<>(points.size());
    for (NearDuplicateMechanismAnalyzer.HybridPoint point : points) {
      ObjectNode params = MAPPER.createObjectNode();
      params.put("distanceLow", point.distanceLow());
      params.put("distanceHigh", point.distanceHigh());
      scored.add(new Scored(params, point.outcome()));
    }
    return scored;
  }

  /** 帕累托前沿：每个泄漏档（6 位小数）保留最大 R_keep 的点，再按泄漏升序剪掉被支配点。 */
  private static ArrayNode frontier(List<Scored> scored) {
    Map<Long, Scored> bestByLeakage = new LinkedHashMap<>();
    for (Scored candidate : scored) {
      long key = Math.round(candidate.outcome().leakage() * 1_000_000D);
      Scored existing = bestByLeakage.get(key);
      if (existing == null || candidate.outcome().retention() > existing.outcome().retention()) {
        bestByLeakage.put(key, candidate);
      }
    }
    List<Scored> ordered =
        bestByLeakage.values().stream()
            .sorted(
                (left, right) -> {
                  int byLeakage =
                      Double.compare(left.outcome().leakage(), right.outcome().leakage());
                  return byLeakage != 0
                      ? byLeakage
                      : Double.compare(right.outcome().retention(), left.outcome().retention());
                })
            .toList();
    ArrayNode array = MAPPER.createArrayNode();
    double bestRetention = -1D;
    for (Scored candidate : ordered) {
      if (candidate.outcome().retention() <= bestRetention) {
        continue;
      }
      bestRetention = candidate.outcome().retention();
      ObjectNode item = array.addObject();
      item.setAll(candidate.params());
      item.put("retainedVariant", candidate.outcome().retainedVariant());
      item.put("retention", round6(candidate.outcome().retention()));
      item.put("leakedNegative", candidate.outcome().leakedNegative());
      item.put("leakage", round6(candidate.outcome().leakage()));
    }
    return array;
  }

  /** 把 R_keep 从基线提到 0.90 的最小泄漏代价（逐机制 + 全体最小）。 */
  private static ObjectNode raisingTarget(
      List<Scored> floors, List<Scored> dh, List<Scored> hybrid) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("targetRetention", NearDuplicateMechanismAnalyzer.C1_MIN_RETENTION);
    node.put("baselineRetention", 70D / 120D);
    node.put("baselineLeakage", 3D / 276D);
    ObjectNode byMechanism = node.putObject("byMechanism");
    Scored best = null;
    String bestMechanism = null;
    String[] names = {"M1", "M2", "M3"};
    List<List<Scored>> all = List.of(floors, dh, hybrid);
    for (int index = 0; index < names.length; index++) {
      Scored candidate = cheapestAtTarget(all.get(index));
      ObjectNode item = byMechanism.putObject(names[index]);
      if (candidate == null) {
        item.put("reachable", false);
        item.put("maxRetention", round6(maxRetention(all.get(index))));
        item.put("minLeakageAtMaxRetention", round6(minLeakageAtMaxRetention(all.get(index))));
      } else {
        item.put("reachable", true);
        item.setAll(candidate.params());
        item.put("retention", round6(candidate.outcome().retention()));
        item.put("leakage", round6(candidate.outcome().leakage()));
        item.put("leakedNegative", candidate.outcome().leakedNegative());
        if (best == null || candidate.outcome().leakage() < best.outcome().leakage()) {
          best = candidate;
          bestMechanism = names[index];
        }
      }
    }
    if (best == null) {
      node.put("answer", "R_keep 提到 0.90 在三机制全网格上不可达");
    } else {
      node.put("answerMechanism", bestMechanism);
      node.set("answerParams", best.params());
      node.put("answerLeakage", round6(best.outcome().leakage()));
      node.put("answerRetention", round6(best.outcome().retention()));
      node.put(
          "answer",
          "把 R_keep 从 0.583 提到 0.90 的最小泄漏代价 = "
              + bestMechanism
              + " "
              + best.params()
              + " 下 L="
              + round6(best.outcome().leakage()));
    }
    return node;
  }

  private static Scored cheapestAtTarget(List<Scored> scored) {
    Scored best = null;
    for (Scored candidate : scored) {
      if (candidate.outcome().retention() >= NearDuplicateMechanismAnalyzer.C1_MIN_RETENTION
          && (best == null || candidate.outcome().leakage() < best.outcome().leakage())) {
        best = candidate;
      }
    }
    return best;
  }

  private static double maxRetention(List<Scored> scored) {
    return scored.stream()
        .mapToDouble(candidate -> candidate.outcome().retention())
        .max()
        .orElse(0D);
  }

  private static double minLeakageAtMaxRetention(List<Scored> scored) {
    double max = maxRetention(scored);
    return scored.stream()
        .filter(candidate -> candidate.outcome().retention() >= max)
        .mapToDouble(candidate -> candidate.outcome().leakage())
        .min()
        .orElse(0D);
  }

  private static String verdictReason(
      String verdict, List<Scored> floors, List<Scored> dh, List<Scored> hybrid) {
    String reason;
    if (NearDuplicateMechanismAnalyzer.VERDICT_NO_GO.equals(verdict)) {
      reason =
          "三机制全网格无一点同时满足 C1(≥0.90)∧C2(≤0.02)；各机制最高 R_keep 与对应 L 见 raisingTarget，"
              + "结论为合法 NO-GO（不调门槛），交付帕累托前沿与结案声明";
    } else if (NearDuplicateMechanismAnalyzer.VERDICT_GO.equals(verdict)) {
      reason = "存在机制同时满足 C1∧C2∧C3 ⇒ GO；本卡只交机制重构规格，不实施任何 src/main 变更";
    } else {
      reason = "存在点满足 C1∧C2 但 C3 单调性不成立 ⇒ UNDECIDABLE-BY-DATA；翻案需补 crop/scale/jpeg 三段样本量";
    }
    return reason + "（M1 点数 " + floors.size() + "、M2 " + dh.size() + "、M3 " + hybrid.size() + "）";
  }

  // ==================== 断言 / 写出 ====================

  private static void assertMatchesCommitted(ObjectNode computed) throws IOException {
    assertTrue(Files.exists(DECISION_FILE), "决策锚不存在（首轮应跑写模式）: " + DECISION_FILE);
    JsonNode committed = MAPPER.readTree(Files.readAllBytes(DECISION_FILE));
    ObjectNode expected = computed.deepCopy();
    expected.remove("generatedAt");
    expected.remove("elapsedMillis");
    ObjectNode actual = committed.deepCopy();
    actual.remove("generatedAt");
    actual.remove("elapsedMillis");
    assertEquals(
        expected, actual, "决策锚与本次复算不一致：请用 scripts/rag-quality/near-dup-mechanism-report.sh 重算");
  }

  private static void write(ObjectNode decision) throws IOException {
    Files.createDirectories(DECISION_FILE.getParent());
    ObjectMapper writer = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    String json = writer.writeValueAsString(decision) + "\n";
    Files.writeString(DECISION_FILE, json, StandardCharsets.UTF_8);
  }

  // ==================== stdout 固定表格 ====================

  private static void printBaseline(NearDuplicateMechanismAnalyzer.Outcome baseline) {
    System.out.println(
        "[G1][表 1/5 基线] floor="
            + NearDuplicateMechanismAnalyzer.COSINE_FLOOR
            + " R_keep="
            + baseline.retainedVariant()
            + "/"
            + baseline.variantTotal()
            + "="
            + round6(baseline.retention())
            + " L="
            + baseline.leakedNegative()
            + "/"
            + baseline.negativeTotal()
            + "="
            + round6(baseline.leakage())
            + " 逐kind="
            + baseline.retainedByKind());
  }

  private static void printFloors(List<NearDuplicateMechanismAnalyzer.FloorPoint> points) {
    System.out.println("[G1][表 2/5 M1 全网格] points=" + points.size());
    for (NearDuplicateMechanismAnalyzer.FloorPoint point : points) {
      System.out.println(
          "M1 floor="
              + round6(point.floor())
              + " retained="
              + point.outcome().retainedVariant()
              + " R="
              + round6(point.outcome().retention())
              + " leaked="
              + point.outcome().leakedNegative()
              + " L="
              + round6(point.outcome().leakage())
              + " kinds="
              + point.outcome().retainedByKind());
    }
  }

  private static void printDhash(List<NearDuplicateMechanismAnalyzer.DHashPoint> points) {
    System.out.println("[G1][表 3/5 M2 全网格] points=" + points.size());
    for (NearDuplicateMechanismAnalyzer.DHashPoint point : points) {
      System.out.println(
          "M2 D="
              + point.distance()
              + " retained="
              + point.outcome().retainedVariant()
              + " R="
              + round6(point.outcome().retention())
              + " leaked="
              + point.outcome().leakedNegative()
              + " L="
              + round6(point.outcome().leakage())
              + " kinds="
              + point.outcome().retainedByKind());
    }
  }

  private static void printPareto(String mechanism, ArrayNode frontier) {
    System.out.println("[G1][表 4/5 " + mechanism + " 帕累托前沿] points=" + frontier.size());
    for (JsonNode node : frontier) {
      System.out.println(mechanism + " " + node.toPrettyString().replace('\n', ' '));
    }
  }

  private static void printCriteria(
      NearDuplicateMechanismAnalyzer.Outcome baseline,
      List<NearDuplicateMechanismAnalyzer.FloorPoint> floors,
      List<NearDuplicateMechanismAnalyzer.DHashPoint> dh,
      List<NearDuplicateMechanismAnalyzer.HybridPoint> hybrid,
      String verdict) {
    System.out.println("[G1][表 5/5 逐判据照判] verdict=" + verdict);
    System.out.println(
        "C1 基线 R_keep="
            + round6(baseline.retention())
            + " 门槛 "
            + NearDuplicateMechanismAnalyzer.C1_MIN_RETENTION);
    System.out.println(
        "C2 基线 L="
            + round6(baseline.leakage())
            + " 门槛 "
            + NearDuplicateMechanismAnalyzer.C2_MAX_LEAKAGE);
    System.out.println("C3 基线逐kind=" + baseline.retainedByKind() + " 单调=" + baseline.satisfiesC3());
    System.out.println("C4 externalCalls=0");
    System.out.println("C5 verdict=" + verdict);
    System.out.println(
        "C6 M1="
            + floors.size()
            + " 点 / M2="
            + dh.size()
            + " 点 / M3="
            + hybrid.size()
            + " 点（全网格逐点入锚）");
  }

  // ==================== 工具 ====================

  private static double round6(double value) {
    return Math.round(value * 1_000_000D) / 1_000_000D;
  }

  private static Map<String, String> criteria() {
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
