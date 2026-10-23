package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Report;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图像近重复真值评测的唯一生成/断言入口（query-relevance-truth G2 任务 2.2.1~2.3.3）。
 *
 * <p><b>唯一写入口</b>：只有 {@code -Dnear.dup.truth.write=true} 时才写 {@code
 * docs/rag-quality/image-near-dup-truth-v1.json}；默认模式复算后<b>只断言不写</b>，且与已提交锚<b>逐字节相等</b>。
 *
 * <p><b>口径复用（T2）</b>：逐 floor 把三类用例喂给既有 {@code RagQualityEvaluator}（零改动），recall 全部出自其既有公式； {@code
 * contaminationAtK = 1 − mean(recallAtK over 负例A)}——expected 为空时 evaluator 的诚实空判（top-k
 * 空=1/非空=0）即正确拒答率， 污染率是其补数，<b>纯派生量</b>，不是新评测口径。
 *
 * <p><b>T4 结构性自检</b>：本套件 120 个正例的 {@code recallAtK} 必须与上一卡锚同 floor 的 {@code R(f)} 相等（同一 120 对、同一
 * floor 判定的两条独立路径），任一不等即红并报告两侧数字，<b>禁「取其一」</b>（proposal §5：停 G2 并上报）。
 *
 * <p><b>零外呼</b>：得分取自已提交校准锚的逐对余弦（历史真跑产物），不重新 embedding、不调模型接口、不读环境变量、不读 {@code .env}。
 */
class ImageNearDuplicateTruthEvaluationTest {

  /** 余弦校准锚（数据真相源，本卡只读）。 */
  private static final Path CORPUS_FILE =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** corpusAnchorSha256 的历史登记值（任务 0.4 复算一致）。 */
  private static final String CORPUS_SHA256_REGISTERED =
      "82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65";

  /** 上一卡交换率锚（T4 的 R(f) 对表面 + sourceAnchor，本卡只读）。 */
  private static final Path PREV_EXCHANGE_FILE =
      Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json");

  /** 真值锚（本卡唯一写入口的产物）。 */
  private static final Path DECISION_FILE =
      Path.of("docs", "rag-quality", "image-near-dup-truth-v1.json");

  /** 写模式开关（默认 false = 只断言不写）。 */
  private static final String WRITE_FLAG = "near.dup.truth.write";

  /** T3 逐 floor 表（≥8 档：0.9887 基线 + 0.985/0.982/0.98/0.9785/0.975/0.95/0.90）。 */
  static final List<Double> FLOORS =
      List.of(0.9887D, 0.985D, 0.982D, 0.98D, 0.9785D, 0.975D, 0.95D, 0.90D);

  /** 判据原文（proposal §2.3 逐字回显；守卫测试逐字比对）。 */
  static final Map<String, String> CRITERIA = criteria();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("图像近重复真值逐 floor 复算并产出/核对锚（默认只断言不写，逐字节校验）")
  void recomputeTruthSuiteAndEmitAnchor() throws Exception {
    byte[] corpusBytes = Files.readAllBytes(CORPUS_FILE);
    JsonNode corpus = MAPPER.readTree(corpusBytes);
    String corpusSha = sha256(corpusBytes);
    assertEquals(CORPUS_SHA256_REGISTERED, corpusSha, "校准锚 SHA-256 与登记值不一致（语料被改？）");
    String prevSha = sha256(Files.readAllBytes(PREV_EXCHANGE_FILE));
    JsonNode prevAnchor = MAPPER.readTree(Files.readAllBytes(PREV_EXCHANGE_FILE));

    List<NearDupFusionImpactAnalyzer.Pair> pairs = pairs(corpus);
    NearDupFusionImpactAnalyzer.requireStrata(pairs);
    assertEquals(540, pairs.size(), "校准锚 pairs 必须 540 对");

    // T1：真值可复算——用例由 provenance 键唯一生成，数量逐数相等
    List<RagBenchmarkCase> positives = ImageNearDuplicateTruthSuite.positiveCases(pairs);
    List<RagBenchmarkCase> negativesA = ImageNearDuplicateTruthSuite.negativeACases(pairs);
    List<RagBenchmarkCase> negativesB = ImageNearDuplicateTruthSuite.negativeBCases(pairs);
    assertEquals(
        ImageNearDuplicateTruthSuite.POSITIVE_TOTAL,
        positives.size(),
        "正例必须 24 base × 5 kind = 120");
    assertEquals(
        ImageNearDuplicateTruthSuite.NEGATIVE_A_TOTAL,
        negativesA.size(),
        "负例 A（跨文档近似）必须 = cross-class 对总数 176");
    assertEquals(
        ImageNearDuplicateTruthSuite.NEGATIVE_B_TOTAL,
        negativesB.size(),
        "负例 B（同文档 hard negative）必须 = same-class 对总数 100");
    assertPositiveCompleteness(pairs);
    Map<String, String> kindByCaseId = kindByCaseId(pairs);
    printSuite(positives, negativesA, negativesB);

    Set<String> allCaseIds = new LinkedHashSet<>();
    positives.forEach(item -> allCaseIds.add(item.id()));
    negativesA.forEach(item -> allCaseIds.add(item.id()));
    negativesB.forEach(item -> allCaseIds.add(item.id()));
    assertEquals(396, allCaseIds.size(), "三类用例合计必须 396 条且 id 唯一");

    Map<Long, double[]> prevR = prevRetentionByFloor(prevAnchor);
    ArrayNode floorsNode = MAPPER.createArrayNode();
    int t4Mismatches = 0;
    for (double floor : FLOORS) {
      Map<String, List<String>> retrieved =
          ImageNearDuplicateTruthSuite.retrievedByCaseId(pairs, floor);
      var retrieval = ImageNearDuplicateTruthSuite.retrieval(retrieved, allCaseIds);
      Report positiveReport =
          RagQualityEvaluator.evaluate(positives, retrieval, ImageNearDuplicateTruthSuite.TOP_K);
      Report negativeAReport =
          RagQualityEvaluator.evaluate(negativesA, retrieval, ImageNearDuplicateTruthSuite.TOP_K);
      Report negativeBReport =
          RagQualityEvaluator.evaluate(negativesB, retrieval, ImageNearDuplicateTruthSuite.TOP_K);

      double positiveRecall = round6(positiveReport.metrics().meanRecallAtK());
      int retained = 0;
      Map<String, Integer> byKind = new LinkedHashMap<>();
      for (CaseScore score : positiveReport.cases()) {
        if (score.hit()) {
          retained++;
          byKind.merge(kindByCaseId.get(score.id()), 1, Integer::sum);
        }
      }
      double negativeARecall = round6(negativeAReport.metrics().meanRecallAtK());
      double contamination = round6(1D - negativeAReport.metrics().meanRecallAtK());
      double negativeBRecall = round6(negativeBReport.metrics().meanRecallAtK());
      int competitorCases = 0;
      for (CaseScore score : negativeBReport.cases()) {
        if (score.precisionAtK() < 1.0D) {
          competitorCases++;
        }
      }
      double competitorRate =
          round6((double) competitorCases / ImageNearDuplicateTruthSuite.NEGATIVE_B_TOTAL);

      // T4：两条独立路径必须算出同一个数（不等即红，禁取其一）
      double[] previous = prevR.get(floorKey(floor));
      assertNotNull(previous, "floor " + floor + " 在上一卡锚中没有 R(f) 对表面");
      boolean t4Match = positiveRecall == previous[0] && retained == (int) previous[1];
      if (!t4Match) {
        t4Mismatches++;
      }
      String t4Message =
          String.format(
              "T4 交叉校验失败（禁取其一，停 G2 上报）: floor=%s 本套件 recallAtK=%s vs 上一卡 R(f)=%s；"
                  + "本套件 retained=%d vs 上一卡 retainedNearDup=%d",
              floor, positiveRecall, previous[0], retained, (int) previous[1]);
      assertEquals(previous[0], positiveRecall, 1e-9D, t4Message);
      assertEquals((int) previous[1], retained, "T4 保留数不一致: floor=" + floor);

      printFloorRow(
          floor,
          positiveRecall,
          retained,
          byKind,
          contamination,
          negativeARecall,
          negativeBRecall,
          competitorCases,
          competitorRate,
          previous[0]);
      floorsNode.add(
          floorNode(
              floor,
              positiveRecall,
              retained,
              byKind,
              contamination,
              negativeARecall,
              negativeBRecall,
              competitorCases,
              competitorRate,
              previous[0],
              (int) previous[1]));
    }
    assertTrue(t4Mismatches == 0, "T4 逐档必须全一致");
    System.out.println(
        "[QRT2][表 3/3 T4 交叉校验] floorsChecked="
            + FLOORS.size()
            + " mismatches="
            + t4Mismatches
            + "（两条独立路径：本套件 evaluator 判卷 vs 上一卡锚 R(f) 逐档相等）");

    ObjectNode decision = MAPPER.createObjectNode();
    decision.put("card", "query-relevance-truth");
    decision.put("group", "G2");
    decision.put("decisionVersion", "IMAGE-NEAR-DUP-TRUTH-1.0");
    decision.set("criteria", stringNode(CRITERIA));
    decision.put("decisionOwner", "OWNER");
    decision.put(
        "decisionScope",
        "本卡不改任何生产常量、不改既有评测口径（RagQualityEvaluator/RagBenchmarkCase/ImageCrossModalSuite/"
            + "PdfIngestImageFixture 零 diff）、不拍板档位；检索级真值结果交 OWNER 结合 pair 级与融合级证据决策。");
    ObjectNode truthSource = decision.putObject("truthSource");
    truthSource.put(
        "mechanism",
        "语料 provenance：baseKey → goldenId（golden:<baseKey>，唯一生成点 ImageNearDuplicateTruthSuite.goldenId）。"
            + "正例期望来自 near-duplicate 对左键；负例 A 期望空；负例 B 期望 = 查询 base 自身。无手写字面量 id。");
    truthSource.put("corpusAnchor", CORPUS_FILE.toString().replace('\\', '/'));
    truthSource.put("corpusAnchorSha256", corpusSha);
    truthSource.put("prevExchangeAnchor", PREV_EXCHANGE_FILE.toString().replace('\\', '/'));
    truthSource.put("prevExchangeAnchorSha256", prevSha);
    ObjectNode caseCounts = decision.putObject("caseCounts");
    caseCounts.put("positive", positives.size());
    caseCounts.put("negativeACrossDoc", negativesA.size());
    caseCounts.put("negativeBSameDocHard", negativesB.size());
    caseCounts.put("total", positives.size() + negativesA.size() + negativesB.size());
    ObjectNode evaluator = decision.putObject("evaluator");
    evaluator.put("class", "com.slz.crm.quality.RagQualityEvaluator");
    evaluator.put("modified", false);
    evaluator.put("topK", ImageNearDuplicateTruthSuite.TOP_K);
    evaluator.put("runsPerFloor", 3);
    evaluator.put(
        "note",
        "recall/precision/MRR 公式零改动（T2，git 状态自证见任务 2.4.2）；contaminationAtK 与"
            + " sameDocCompetitorRate 只读其逐例结果派生。");
    decision.set("floors", floorsNode);
    ObjectNode t4 = decision.putObject("t4CrossCheck");
    t4.put("floorsChecked", FLOORS.size());
    t4.put("mismatches", t4Mismatches);
    t4.put("prevAnchor", PREV_EXCHANGE_FILE.toString().replace('\\', '/'));
    t4.put("note", "本套件 120 正例的 recallAtK 与上一卡锚同 floor 的 R(f) 两条独立路径逐档相等（含基线 0.9887 单独评测档）。");
    decision.put(
        "contaminationDerivation",
        "contaminationAtK = 1 − mean(recallAtK over 负例A)：expected 为空时 RagQualityEvaluator 的诚实空判"
            + "（top-k 空=1/非空=0）即正确拒答率，污染率是其补数——既有 top-k 结果的派生量，不是新评测口径。");
    decision.put(
        "sameDocCompetitorDerivation",
        "negativeBSameDocCompetitorRate = 负例B中 precisionAtK < 1 的用例占比：自身（same-image）恒在 top-k 内，"
            + "同文档竞争者（same-class 对 ≥ floor）出现即把 precision 从 1 拉低——同样是从 evaluator 逐例结果派生。");
    decision.set(
        "scopeLimits",
        arrayNode(
            List.of(
                "只覆盖图像路内检索级（pair 级重建），不含文本路、不含融合后排序、不覆盖生产重排器行为。",
                "负例查询 = base 再编码（校准锚 540 对中不存在变体↔第三图余弦，不做外推）；正例查询 = 变体（唯一有逐对余弦的判别面）。",
                "真值 = 语料 provenance（同图/同源/同文档键关系），不是用户意图标注——上一卡 A1（同文档 ⇒ 可能相关）仍是假设。",
                "contaminationAtK 是既有 top-k 结果的派生量，不是新评测口径；负例 B 的同文档竞争者按「相关但不必须」处理，不计污染。")));
    decision.set(
        "deviations",
        deviationsNode(
            List.of(
                "proposal §2.2 负例 A/B 的「query = base_i 变体」落地为「query = base_i 再编码」：校准锚 540 对"
                    + "（144 同图 + 120 近重复 + 276 base 两两）已穷尽，不存在变体↔异 base 对，零编造优先于字面形状；"
                    + "正例不受影响（变体↔自身 base 对恰是锚内 near-duplicate 档）。")));
    decision.put("externalCalls", 0);

    if (Boolean.parseBoolean(System.getProperty(WRITE_FLAG, "false"))) {
      write(decision);
      System.out.println("[QRT2] 写模式：真值锚已写入 " + DECISION_FILE);
    } else {
      assertMatchesCommitted(decision);
      System.out.println("[QRT2] 默认模式：只断言不写，真值锚与复算结果逐字节一致");
    }
  }

  // ==================== T1 完整性与输入解析 ====================

  /** T1 完整性：24 base × 5 kind 每格恰一例（禁缺格、禁重复）。 */
  private static void assertPositiveCompleteness(List<NearDupFusionImpactAnalyzer.Pair> pairs) {
    Map<String, Set<String>> kindsByBase = new LinkedHashMap<>();
    for (NearDupFusionImpactAnalyzer.Pair pair : pairs) {
      if (NearDupFusionImpactAnalyzer.STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
        kindsByBase.computeIfAbsent(pair.leftKey(), key -> new LinkedHashSet<>()).add(pair.kind());
      }
    }
    assertEquals(24, kindsByBase.size(), "T1：near-duplicate 对必须覆盖 24 个 base");
    kindsByBase.forEach(
        (base, kinds) ->
            assertEquals(
                ImageNearDuplicateTruthSuite.KINDS_PER_BASE,
                kinds.size(),
                "T1：base "
                    + base
                    + " 的变体 kind 必须恰 "
                    + ImageNearDuplicateTruthSuite.KINDS_PER_BASE
                    + " 种"));
  }

  private static List<NearDupFusionImpactAnalyzer.Pair> pairs(JsonNode corpus) {
    JsonNode pairsNode = corpus.path("pairs");
    assertTrue(pairsNode.isArray(), "校准锚缺少 pairs 数组");
    List<NearDupFusionImpactAnalyzer.Pair> pairs = new ArrayList<>(pairsNode.size());
    for (JsonNode node : pairsNode) {
      pairs.add(
          new NearDupFusionImpactAnalyzer.Pair(
              node.path("stratum").asText(),
              node.path("leftKey").asText(),
              node.path("rightKey").asText(),
              node.path("kind").isNull() ? null : node.path("kind").asText(),
              node.path("cosine").asDouble()));
    }
    return List.copyOf(pairs);
  }

  private static Map<String, String> kindByCaseId(List<NearDupFusionImpactAnalyzer.Pair> pairs) {
    Map<String, String> kindByCaseId = new LinkedHashMap<>();
    for (NearDupFusionImpactAnalyzer.Pair pair : pairs) {
      if (NearDupFusionImpactAnalyzer.STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
        kindByCaseId.put(ImageNearDuplicateTruthSuite.caseId(pair), pair.kind());
      }
    }
    return Map.copyOf(kindByCaseId);
  }

  /** 上一卡锚 R(f) 对表面：floorKey → [R, retainedNearDup]（基线 0.9887 单独评测 + 200 点网格）。 */
  private static Map<Long, double[]> prevRetentionByFloor(JsonNode prevAnchor) {
    Map<Long, double[]> table = new LinkedHashMap<>();
    JsonNode baseline = prevAnchor.path("baseline");
    table.put(
        floorKey(baseline.path("floor").asDouble()),
        new double[] {baseline.path("R").asDouble(), baseline.path("retainedNearDup").asInt()});
    JsonNode items = prevAnchor.path("grid").path("items");
    assertTrue(items.isArray() && items.size() == 200, "上一卡锚 grid.items 必须 200 点");
    for (JsonNode item : items) {
      table.put(
          floorKey(item.path("floor").asDouble()),
          new double[] {item.path("R").asDouble(), item.path("retainedNearDup").asInt()});
    }
    return Map.copyOf(table);
  }

  private static long floorKey(double floor) {
    return Math.round(floor * 10000D);
  }

  // ==================== 节点构造 ====================

  private static ObjectNode stringNode(Map<String, String> values) {
    ObjectNode node = MAPPER.createObjectNode();
    values.forEach(node::put);
    return node;
  }

  private static ObjectNode floorNode(
      double floor,
      double positiveRecall,
      int retained,
      Map<String, Integer> byKind,
      double contamination,
      double negativeARecall,
      double negativeBRecall,
      int competitorCases,
      double competitorRate,
      double previousR,
      int previousRetained) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("floor", floor);
    node.put("positiveRecallAtK", positiveRecall);
    node.put("retained", retained);
    ObjectNode kinds = node.putObject("retainedByKind");
    byKind.forEach(kinds::put);
    node.put("negativeAContaminationAtK", contamination);
    node.put("negativeARecallHonestEmpty", negativeARecall);
    node.put("negativeBRecallAtK", negativeBRecall);
    node.put("negativeBSameDocCompetitorRate", competitorRate);
    node.put("negativeBSameDocCompetitorCases", competitorCases);
    node.put("previousCardR", previousR);
    node.put("previousCardRetained", previousRetained);
    node.put("t4Match", true);
    return node;
  }

  private static ArrayNode arrayNode(List<String> values) {
    ArrayNode array = MAPPER.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  private static ArrayNode deviationsNode(List<String> values) {
    ArrayNode array = MAPPER.createArrayNode();
    for (String value : values) {
      ObjectNode node = array.addObject();
      node.put("proposalSection", "§2.2 负例 A/B");
      node.put("deviation", value);
    }
    return array;
  }

  // ==================== 断言 / 写出 ====================

  private static void assertMatchesCommitted(ObjectNode computed) throws Exception {
    assertTrue(Files.exists(DECISION_FILE), "真值锚不存在（首轮应跑写模式）: " + DECISION_FILE);
    String committed = Files.readString(DECISION_FILE, StandardCharsets.UTF_8);
    String expected = serialize(computed);
    assertEquals(expected, committed, () -> firstDifference(expected, committed));
  }

  private static void write(ObjectNode decision) throws Exception {
    Files.createDirectories(DECISION_FILE.getParent());
    Files.writeString(DECISION_FILE, serialize(decision), StandardCharsets.UTF_8);
  }

  /**
   * 序列化（缩进 + 末尾换行）；锚内无 volatile 字段，默认模式可做逐字节校验。
   *
   * <p><b>行尾钉死 LF</b>：Jackson 默认缩进器在 Windows 上产出 CRLF，而 {@code .gitattributes} 的 {@code *.json text
   * eol=lf} 让仓库里存的是 LF；不钉死会让「干净 checkout」与「本机刚跑过写模式」得出相反结论。
   */
  private static String serialize(ObjectNode decision) throws Exception {
    ObjectMapper writer = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    return writer.writeValueAsString(decision).replace("\r\n", "\n") + "\n";
  }

  private static String firstDifference(String expected, String actual) {
    String[] wantLines = expected.split("\n", -1);
    String[] gotLines = actual.split("\n", -1);
    int max = Math.max(wantLines.length, gotLines.length);
    for (int i = 0; i < max; i++) {
      String want = i < wantLines.length ? wantLines[i] : "<文件已结束>";
      String got = i < gotLines.length ? gotLines[i] : "<文件已结束>";
      if (!want.equals(got)) {
        return "真值锚与复算不一致，首个差异在第 "
            + (i + 1)
            + " 行：\n  期望: "
            + want
            + "\n  实际: "
            + got
            + "\n（重写：mvn test -Dtest=ImageNearDuplicateTruthEvaluationTest -Dnear.dup.truth.write=true）";
      }
    }
    return "真值锚与复算不一致（行内容全同但字节不同，检查行尾/编码）";
  }

  // ==================== stdout 固定表格 ====================

  private static void printSuite(
      List<RagBenchmarkCase> positives,
      List<RagBenchmarkCase> negativesA,
      List<RagBenchmarkCase> negativesB) {
    System.out.println(
        "[QRT2][表 1/3 用例集] positive="
            + positives.size()
            + " negativeA="
            + negativesA.size()
            + " negativeB="
            + negativesB.size()
            + " total="
            + (positives.size() + negativesA.size() + negativesB.size())
            + " goldenId=golden:<baseKey>（provenance 唯一导出，无手写字面量）");
  }

  private static void printFloorRow(
      double floor,
      double positiveRecall,
      int retained,
      Map<String, Integer> byKind,
      double contamination,
      double negativeARecall,
      double negativeBRecall,
      int competitorCases,
      double competitorRate,
      double previousR) {
    System.out.println(
        "[QRT2][表 2/3 逐 floor] f="
            + floor
            + " recallAtK="
            + positiveRecall
            + " retained="
            + retained
            + "/120 kinds="
            + byKind
            + " contaminationAtK="
            + contamination
            + " negA_recall="
            + negativeARecall
            + " negB_recall="
            + negativeBRecall
            + " negB_competitor="
            + competitorCases
            + "/100="
            + competitorRate
            + " prevR="
            + previousR
            + " t4Match=true");
  }

  // ==================== 工具 ====================

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

  private static Map<String, String> criteria() {
    Map<String, String> criteria = new LinkedHashMap<>();
    criteria.put(
        "T1",
        "真值可复算：每条用例的 expectedChunkIds 必须能由 NearDuplicateCorpus 的键唯一定出（baseKey → goldenId），"
            + "不得出现手写字面量 id；用例数与语料（24 base × 5 kind = 120 正例）逐数相等。");
    criteria.put(
        "T2",
        "口径复用：RagQualityEvaluator 的既有公式零改动（git diff 证明）；contaminationAtK 只读取其结果，" + "不改任何既有指标。");
    criteria.put(
        "T3",
        "逐 floor 表：至少 8 档（含 0.9887 基线与 0.982/0.98/0.975/0.95/0.90）逐档给 recallAtK / contaminationAtK /"
            + " 保留数。");
    criteria.put(
        "T4",
        "结构性自检：recallAtK + drop 必须与上一卡 R(f) 一致（同一 120 个正例、同一 floor 判定）——两条独立路径算出" + "同一个数，不一致即红。");
    criteria.put("T5", "零外呼：不读 .env、不设 RAG_*_REAL、不访问网络；锚内 externalCalls=0。");
    return criteria;
  }
}
