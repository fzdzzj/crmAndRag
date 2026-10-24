package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 近重复交换率决策锚的唯一生成/断言入口（evidence-to-operations G1 任务 1.2.1~1.2.2）。
 *
 * <p><b>唯一写入口</b>：只有 {@code -Dnear.dup.exchange.write=true} 时才写 {@code
 * docs/rag-quality/near-dup-exchange-rate-v1.json}；默认模式复算全网格后<b>只断言不写</b>，且与已提交锚<b>逐字节相等</b>
 * （锚内无时间戳等 volatile 字段，字节比对可行）。
 *
 * <p><b>E2 跨锚一致性</b>：{@code R(f)} 与上一卡锚 {@code near-dup-mechanism-decision-v1.json} 的 M1
 * 网格<b>逐点相等</b>， 任一不等即失败（防两份锚各说各话）。
 *
 * <p><b>零外呼</b>：余弦取自已提交校准锚（历史真跑产物），本类不调用任何模型接口、不读环境变量、不读 {@code .env}， {@code externalCalls} 恒为 0。
 */
class NearDuplicateExchangeRateDecisionTest {

  /** 余弦校准锚（数据真相源，本卡只读）。 */
  private static final Path CORPUS_FILE =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** corpusAnchorSha256 的历史登记值（proposal §1.1，任务 0.4 复算一致）。 */
  private static final String CORPUS_SHA256_REGISTERED =
      "82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65";

  /** 上一卡机制决策锚（E2 跨锚比对面 + sourceAnchor，本卡只读）。 */
  private static final Path SOURCE_ANCHOR_FILE =
      Path.of("docs", "rag-quality", "near-dup-mechanism-decision-v1.json");

  /** 交换率决策锚（本卡唯一写入口的产物）。 */
  private static final Path DECISION_FILE =
      Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json");

  /** 写模式开关（默认 false = 只断言不写）。 */
  private static final String WRITE_FLAG = "near.dup.exchange.write";

  /** 判据原文（proposal §1.2 逐字回显；守卫测试逐字比对）。 */
  static final Map<String, String> CRITERIA = criteria();

  /** 假设登记原文（proposal §1.3 逐字回显；锚内必带，禁隐含）。 */
  static final Map<String, String> ASSUMPTIONS = assumptions();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("交换率全网格复算并产出/核对决策锚（默认只断言不写，逐字节校验）")
  void recomputeExchangeRateAndEmitDecision() throws Exception {
    byte[] corpusBytes = Files.readAllBytes(CORPUS_FILE);
    JsonNode corpus = MAPPER.readTree(corpusBytes);
    String corpusSha = sha256(corpusBytes);
    assertEquals(CORPUS_SHA256_REGISTERED, corpusSha, "校准锚 SHA-256 与 proposal §1.1 登记值不一致（语料被改？）");

    byte[] sourceBytes = Files.readAllBytes(SOURCE_ANCHOR_FILE);
    JsonNode sourceAnchor = MAPPER.readTree(sourceBytes);
    String sourceSha = sha256(sourceBytes);
    Map<Long, Double> previousRetention = m1RetentionByFloor(sourceAnchor);
    assertEquals(
        NearDuplicateExchangeRateAnalyzer.GRID_POINTS,
        previousRetention.size(),
        "上一卡锚 M1 必须 200 点（跨锚比对面缺失）");
    int previousBaselineLeak =
        sourceAnchor.path("baseline").path("outcome").path("leakedNegative").asInt(-1);
    double previousBaselineRetention =
        sourceAnchor.path("baseline").path("outcome").path("retention").asDouble(-1D);

    List<NearDuplicateExchangeRateAnalyzer.Pair> pairs = pairs(corpus);
    NearDuplicateExchangeRateAnalyzer.requireStrata(pairs);
    assertEquals(540, pairs.size(), "校准锚 pairs 必须 540 对");

    NearDuplicateExchangeRateAnalyzer.FloorStat baseline =
        NearDuplicateExchangeRateAnalyzer.baselineOf(pairs);
    assertEquals(70, baseline.retainedNearDup(), "基线保留数必须仍是 70/120（上一卡实测）");
    assertEquals(
        3,
        baseline.keptSame() + baseline.keptCross(),
        "基线负样本泄漏 3 对必须与上一卡锚 baseline.leakedNegative 相等（拆分不改变总量）");
    assertEquals(previousBaselineLeak, baseline.keptSame() + baseline.keptCross());
    assertEquals(previousBaselineRetention, round6(baseline.retention()), 1e-9D);

    List<NearDuplicateExchangeRateAnalyzer.FloorStat> grid =
        NearDuplicateExchangeRateAnalyzer.scanGrid(pairs);
    assertEquals(200, grid.size());
    for (NearDuplicateExchangeRateAnalyzer.FloorStat point : grid) {
      Double previous = previousRetention.get(floorKey(point.floor()));
      assertNotNull(previous, "floor " + point.floor() + " 在上一卡锚 M1 网格中不存在");
      assertEquals(
          previous,
          round6(point.retention()),
          "E2 跨锚一致性被破坏：floor="
              + point.floor()
              + " 本卡 R="
              + round6(point.retention())
              + " 上一卡 R="
              + previous);
    }

    String verdict = NearDuplicateExchangeRateAnalyzer.verdict(grid, baseline);
    NearDuplicateExchangeRateAnalyzer.FloorStat freeTier =
        NearDuplicateExchangeRateAnalyzer.freeTier(grid, baseline).orElse(null);
    NearDuplicateExchangeRateAnalyzer.FloorStat firstImproving = firstImprovingTier(grid, baseline);
    NearDuplicateExchangeRateAnalyzer.FloorStat bestValue = bestValueTier(grid, baseline);

    printBaseline(baseline);
    printGrid(grid, baseline);
    printDecision(verdict, freeTier, firstImproving, bestValue);
    printCriteria(verdict, grid, freeTier);

    ObjectNode decision = MAPPER.createObjectNode();
    decision.put("card", "evidence-to-operations");
    decision.put("group", "G1");
    decision.put("decisionVersion", "NEAR-DUP-EXCHANGE-RATE-1.0");
    decision.set("criteria", stringNode(CRITERIA));
    decision.set("assumptions", stringNode(ASSUMPTIONS));
    decision.put("decisionOwner", "OWNER");
    // decisionScope 是锚内冻结的历史串（逐字节校验，禁改）：0.9887 是锚生成时的测档（「锚测于 0.9887」口径），
    // 生产下限已由 near-dup-floor-lowering 卡下探至 0.982；本串描述的是该卡当时的事实，改串即破锚字节等值。
    decision.put(
        "decisionScope",
        "本卡不改任何生产常量（threshold=0.9944 / NEAR_DUPLICATE_FLOOR=0.9887 / 权重 0.75|0.6|0.5 逐字不动）、"
            + "不拍板档位；价目表与免费档判据交 OWNER 决策，改档须新卡。");
    decision.put("sourceAnchor", SOURCE_ANCHOR_FILE.toString().replace('\\', '/'));
    decision.put("sourceAnchorSha256", sourceSha);
    decision.put("corpusAnchor", CORPUS_FILE.toString().replace('\\', '/'));
    decision.put("corpusAnchorSha256", corpusSha);
    decision.set("productionConstants", productionNode());
    decision.set("baseline", floorNode(baseline, null, null));
    decision.set("grid", gridNode(grid, baseline));
    decision.set("freeTier", freeTier == null ? null : floorNode(freeTier, baseline, null));
    decision.set("exchangeSummary", exchangeSummary(verdict, firstImproving, bestValue));
    decision.put("verdict", verdict);
    decision.put("verdictReason", verdictReason(verdict, freeTier, firstImproving, bestValue));
    decision.put("externalCalls", 0);

    if (Boolean.parseBoolean(System.getProperty(WRITE_FLAG, "false"))) {
      write(decision);
      System.out.println("[G1E] 写模式：交换率锚已写入 " + DECISION_FILE);
    } else {
      assertMatchesCommitted(decision);
      System.out.println("[G1E] 默认模式：只断言不写，交换率锚与复算结果逐字节一致");
    }
  }

  // ==================== 输入解析 ====================

  private static List<NearDuplicateExchangeRateAnalyzer.Pair> pairs(JsonNode corpus) {
    JsonNode pairsNode = corpus.path("pairs");
    assertTrue(pairsNode.isArray(), "校准锚缺少 pairs 数组");
    List<NearDuplicateExchangeRateAnalyzer.Pair> pairs = new ArrayList<>(pairsNode.size());
    for (JsonNode node : pairsNode) {
      pairs.add(
          new NearDuplicateExchangeRateAnalyzer.Pair(
              node.path("stratum").asText(),
              node.path("kind").isNull() ? null : node.path("kind").asText(),
              node.path("cosine").asDouble()));
    }
    return List.copyOf(pairs);
  }

  /** 上一卡锚 M1 网格：floor（1e-4 定点键）→ retention（6 位小数）。 */
  private static Map<Long, Double> m1RetentionByFloor(JsonNode sourceAnchor) {
    JsonNode points = sourceAnchor.path("mechanisms").path("M1").path("points");
    assertTrue(points.isArray(), "上一卡锚缺 mechanisms.M1.points");
    Map<Long, Double> retention = new TreeMap<>();
    for (JsonNode node : points) {
      double floor = node.path("floor").asDouble();
      retention.put(floorKey(floor), round6(node.path("outcome").path("retention").asDouble()));
    }
    return retention;
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

  private static ObjectNode productionNode() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("threshold", ImageSimilarityGate.DEFAULT_THRESHOLD);
    // 锚内 nearDuplicateFloor 记录的是测档 0.9887（冻结带基准），非生产现值 0.982
    node.put("nearDuplicateFloor", ImageSimilarityGate.BAND_REFERENCE_FLOOR);
    node.put("weightHigh", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH);
    node.put("weightMid", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID);
    node.put("weightLow", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW);
    return node;
  }

  /** 单档节点；baseline 与 freeTier 不带增量字段，grid 点带（delta 用 {@code delta} 非空判断）。 */
  private static ObjectNode floorNode(
      NearDuplicateExchangeRateAnalyzer.FloorStat stat,
      NearDuplicateExchangeRateAnalyzer.FloorStat baseline,
      NearDuplicateExchangeRateAnalyzer.Delta delta) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("floor", round6(stat.floor()));
    node.put("retainedNearDup", stat.retainedNearDup());
    node.put("keptSame", stat.keptSame());
    node.put("keptCross", stat.keptCross());
    node.put("dropped", stat.dropped());
    node.put("bandSame", stat.bandSame());
    node.put("bandCross", stat.bandCross());
    ObjectNode kinds = node.putObject("retainedByKind");
    stat.retainedByKind().forEach(kinds::put);
    node.put("R", round6(stat.retention()));
    node.put("S_same", round6(stat.sameSuppression()));
    node.put("C_cross", round6(stat.crossSuppression()));
    if (delta != null) {
      node.put("deltaR", delta.deltaR());
      node.put("deltaSSame", delta.deltaSSame());
      node.put("extraSuppressedSameDoc", delta.extraSuppressedSameDoc());
      if (delta.exchangeRate() == null) {
        node.putNull("exchangeRate");
      } else {
        node.put("exchangeRate", delta.exchangeRate());
      }
    }
    return node;
  }

  private static ObjectNode gridNode(
      List<NearDuplicateExchangeRateAnalyzer.FloorStat> grid,
      NearDuplicateExchangeRateAnalyzer.FloorStat baseline) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("points", grid.size());
    node.put("step", NearDuplicateExchangeRateAnalyzer.GRID_STEP);
    node.put("start", round6(NearDuplicateExchangeRateAnalyzer.floorAt(0)));
    node.put(
        "end",
        round6(
            NearDuplicateExchangeRateAnalyzer.floorAt(
                NearDuplicateExchangeRateAnalyzer.GRID_POINTS - 1)));
    node.put("mechanism", "M1 同源口径：threshold 固定 0.9944，仅扫 floor；R(f)=cosine>=f 的同源变体占比（与上一卡逐点相等）");
    ArrayNode items = node.putArray("items");
    for (NearDuplicateExchangeRateAnalyzer.FloorStat point : grid) {
      NearDuplicateExchangeRateAnalyzer.Delta delta =
          NearDuplicateExchangeRateAnalyzer.deltaOf(point, baseline);
      items.add(floorNode(point, baseline, delta));
    }
    return node;
  }

  private static ObjectNode exchangeSummary(
      String verdict,
      NearDuplicateExchangeRateAnalyzer.FloorStat firstImproving,
      NearDuplicateExchangeRateAnalyzer.FloorStat bestValue) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("verdict", verdict);
    if (NearDuplicateExchangeRateAnalyzer.VERDICT_FREE_TIER.equals(verdict)) {
      node.put("note", "免费档存在，E4 价目表仍逐档披露（见 grid.items 的增量字段）");
      return node;
    }
    if (firstImproving == null) {
      node.putNull("firstImprovingFloor");
      node.putNull("bestValueFloor");
      node.put("note", "网格上不存在 R(f) > R(0.9887) 的档位：降低 floor 只会流失同源变体且没有保留收益可定价");
      return node;
    }
    node.put("firstImprovingFloor", round6(firstImproving.floor()));
    if (bestValue == null) {
      node.putNull("bestValueFloor");
    } else {
      node.put("bestValueFloor", round6(bestValue.floor()));
    }
    node.put(
        "note",
        "增量逐档见 grid.items 的 deltaR/deltaSSame/extraSuppressedSameDoc/exchangeRate；档位由 OWNER 拍板");
    return node;
  }

  private static String verdictReason(
      String verdict,
      NearDuplicateExchangeRateAnalyzer.FloorStat freeTier,
      NearDuplicateExchangeRateAnalyzer.FloorStat firstImproving,
      NearDuplicateExchangeRateAnalyzer.FloorStat bestValue) {
    StringBuilder reason = new StringBuilder();
    if (NearDuplicateExchangeRateAnalyzer.VERDICT_FREE_TIER.equals(verdict)) {
      reason
          .append("E6 第①支：免费档存在（floor=")
          .append(round6(freeTier.floor()))
          .append("，R=")
          .append(round6(freeTier.retention()))
          .append(" > 基线且 S_same 未升高）⇒ FREE-TIER-FOUND；推荐档即该免费档，改常量须新卡。");
    } else if (NearDuplicateExchangeRateAnalyzer.VERDICT_EXCHANGE_RATE.equals(verdict)) {
      reason
          .append("E6 第②支：免费档不存在（网格上所有 f<0.9887 的点 S_same 均高于基线），但存在 R 提升档（最低价 floor=")
          .append(round6(firstImproving.floor()))
          .append("）⇒ EXCHANGE-RATE；ΔR/ΔS_same 逐档见锚 grid.items，档位由 OWNER 拍板，本卡不改任何生产常量。");
      if (bestValue != null) {
        reason.append(" 累计交换率最高档（披露非推荐）：floor=").append(round6(bestValue.floor())).append("。");
      }
    } else {
      reason.append(
          "E6 第③支：网格上不存在 R(f) > R(0.9887) 的档位，数据无法定价任何有益变动 ⇒ UNDECIDABLE-BY-DATA；"
              + "档位维持基线 0.9887，定夺需查询-相关性真值（另立卡）。");
    }
    return reason.toString();
  }

  // ==================== 断言 / 写出 ====================

  private static void assertMatchesCommitted(ObjectNode computed) throws Exception {
    assertTrue(Files.exists(DECISION_FILE), "交换率锚不存在（首轮应跑写模式）: " + DECISION_FILE);
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
   * <p><b>行尾钉死 LF</b>：Jackson 默认缩进器用 {@code System.lineSeparator()}，Windows 上产出 CRLF；而 {@code
   * .gitattributes} 的 {@code *.json text eol=lf} 让仓库里存的是 LF。两者不钉死，会让「干净 checkout 上跑 verify」（读到
   * LF）与「本机刚跑过写模式」（写成 CRLF）得出相反结论——本卡实测：合并树上 verify 红、跑一次写模式后绿。
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
        return "交换率锚与复算不一致，首个差异在第 "
            + (i + 1)
            + " 行：\n  期望: "
            + want
            + "\n  实际: "
            + got
            + "\n（重写：bash scripts/rag-quality/near-dup-exchange-rate-report.sh）";
      }
    }
    return "交换率锚与复算不一致（行内容全同但字节不同，检查行尾/编码）";
  }

  // ==================== 网格判定辅助 ====================

  /** 最靠近基线的 R 提升档（最高 floor 且 R 严格大于基线）；不存在返回 null。 */
  private static NearDuplicateExchangeRateAnalyzer.FloorStat firstImprovingTier(
      List<NearDuplicateExchangeRateAnalyzer.FloorStat> grid,
      NearDuplicateExchangeRateAnalyzer.FloorStat baseline) {
    NearDuplicateExchangeRateAnalyzer.FloorStat best = null;
    for (NearDuplicateExchangeRateAnalyzer.FloorStat point : grid) {
      if (point.floor() < baseline.floor() && point.retention() > baseline.retention()) {
        if (best == null || point.floor() > best.floor()) {
          best = point;
        }
      }
    }
    return best;
  }

  /** 累计交换率最高档（ΔR/ΔS_same 最大，ΔS_same>0 才有定义；并列取最高 floor）。披露用，非推荐。 */
  private static NearDuplicateExchangeRateAnalyzer.FloorStat bestValueTier(
      List<NearDuplicateExchangeRateAnalyzer.FloorStat> grid,
      NearDuplicateExchangeRateAnalyzer.FloorStat baseline) {
    NearDuplicateExchangeRateAnalyzer.FloorStat best = null;
    Double bestRate = null;
    for (NearDuplicateExchangeRateAnalyzer.FloorStat point : grid) {
      if (point.floor() >= baseline.floor()) {
        continue;
      }
      NearDuplicateExchangeRateAnalyzer.Delta delta =
          NearDuplicateExchangeRateAnalyzer.deltaOf(point, baseline);
      if (delta.exchangeRate() == null) {
        continue;
      }
      if (best == null
          || delta.exchangeRate() > bestRate
          || (delta.exchangeRate().equals(bestRate) && point.floor() > best.floor())) {
        best = point;
        bestRate = delta.exchangeRate();
      }
    }
    return best;
  }

  // ==================== stdout 固定表格 ====================

  private static void printBaseline(NearDuplicateExchangeRateAnalyzer.FloorStat baseline) {
    System.out.println(
        "[G1E][表 1/4 基线] floor="
            + round6(baseline.floor())
            + " R="
            + baseline.retainedNearDup()
            + "/"
            + NearDuplicateExchangeRateAnalyzer.NEAR_DUP_TOTAL
            + "="
            + round6(baseline.retention())
            + " keptSame="
            + baseline.keptSame()
            + " keptCross="
            + baseline.keptCross()
            + " dropped="
            + baseline.dropped()
            + " S_same="
            + baseline.bandSame()
            + "/"
            + NearDuplicateExchangeRateAnalyzer.SAME_CLASS_TOTAL
            + "="
            + round6(baseline.sameSuppression())
            + " C_cross="
            + baseline.bandCross()
            + "/"
            + NearDuplicateExchangeRateAnalyzer.CROSS_CLASS_TOTAL
            + "="
            + round6(baseline.crossSuppression()));
  }

  private static void printGrid(
      List<NearDuplicateExchangeRateAnalyzer.FloorStat> grid,
      NearDuplicateExchangeRateAnalyzer.FloorStat baseline) {
    System.out.println("[G1E][表 2/4 交换率全网格] points=" + grid.size());
    for (NearDuplicateExchangeRateAnalyzer.FloorStat point : grid) {
      NearDuplicateExchangeRateAnalyzer.Delta delta =
          NearDuplicateExchangeRateAnalyzer.deltaOf(point, baseline);
      System.out.println(
          "f="
              + round6(point.floor())
              + " R="
              + point.retainedNearDup()
              + "/"
              + NearDuplicateExchangeRateAnalyzer.NEAR_DUP_TOTAL
              + "="
              + round6(point.retention())
              + " S_same="
              + point.bandSame()
              + "/"
              + NearDuplicateExchangeRateAnalyzer.SAME_CLASS_TOTAL
              + "="
              + round6(point.sameSuppression())
              + " C_cross="
              + point.bandCross()
              + "/"
              + NearDuplicateExchangeRateAnalyzer.CROSS_CLASS_TOTAL
              + "="
              + round6(point.crossSuppression())
              + " keptSame="
              + point.keptSame()
              + " keptCross="
              + point.keptCross()
              + " dropped="
              + point.dropped()
              + " deltaR="
              + delta.deltaR()
              + " deltaSSame="
              + delta.deltaSSame()
              + " extraSameDoc="
              + delta.extraSuppressedSameDoc()
              + " exchangeRate="
              + (delta.exchangeRate() == null ? "n/a" : delta.exchangeRate())
              + " kinds="
              + point.retainedByKind());
    }
  }

  private static void printDecision(
      String verdict,
      NearDuplicateExchangeRateAnalyzer.FloorStat freeTier,
      NearDuplicateExchangeRateAnalyzer.FloorStat firstImproving,
      NearDuplicateExchangeRateAnalyzer.FloorStat bestValue) {
    System.out.println("[G1E][表 3/4 免费档与判定] verdict=" + verdict);
    System.out.println(
        "freeTier=" + (freeTier == null ? "none" : "floor=" + round6(freeTier.floor())));
    System.out.println(
        "firstImproving="
            + (firstImproving == null ? "none" : "floor=" + round6(firstImproving.floor())));
    System.out.println(
        "bestValueTier="
            + (bestValue == null ? "none" : "floor=" + round6(bestValue.floor()) + "（披露非推荐）"));
  }

  private static void printCriteria(
      String verdict,
      List<NearDuplicateExchangeRateAnalyzer.FloorStat> grid,
      NearDuplicateExchangeRateAnalyzer.FloorStat freeTier) {
    System.out.println("[G1E][表 4/4 逐判据照判] verdict=" + verdict);
    System.out.println("E1 拆分度量：R/S_same/C_cross 已逐点计算（分母 120/100/176）");
    System.out.println(
        "E2 恒等式与跨锚一致性：200 点 kept(S_same)+kept(C_cross)+dropped=276 全过；R(f) 与上一卡 M1 逐点相等");
    System.out.println(
        "E3 免费档判据："
            + (freeTier == null ? "不满足（网格上无免费档）" : "满足（floor=" + round6(freeTier.floor()) + "）"));
    System.out.println("E4 交换率：逐档 ΔR/ΔS_same 与多压掉的同文档图数见表 2/4 增量列");
    System.out.println("E5 全网格披露：200 点逐点入锚（R/S_same/C_cross/逐 kind 保留数）");
    System.out.println(
        "E6 结论三选一：verdict=" + verdict + "（禁造第四种）；decisionOwner=OWNER；externalCalls=0");
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
        "E1",
        "拆分度量：对每个 floor f：S_same(f) = same-class 对中 cosine ∈ [f, 0.9944) 的数量 ÷ 100"
            + "（被 ×0.5–0.75 降权的同文档图占比）；C_cross(f) = cross-class 同区间数量 ÷ 176；"
            + "R(f) = 同源保留率（与上一卡同源、同一 200 点网格）");
    criteria.put(
        "E2",
        "恒等式与跨锚一致性：逐 f，kept(S_same) + kept(C_cross) + dropped = 276；"
            + "且 R(f) 与上一卡锚逐点相等（任一不等即红，防两份锚各说各话）");
    criteria.put(
        "E3",
        "免费档判据：若 ∃f* < 0.9887 使 R(f*) > R(0.9887) 且 S_same(f*) ≤ S_same(0.9887)，"
            + "则称 f* 为免费档，取其中 R 最大者为推荐档");
    criteria.put("E4", "交换率：无免费档时，逐档给出 ΔR / ΔS_same 与「这一档多压掉的同文档图数」，不得只给两端");
    criteria.put("E5", "全网格披露：200 点 floor 网格逐点入锚（R / S_same / C_cross / 逐 kind 保留数）");
    criteria.put(
        "E6",
        "结论三选一：FREE-TIER-FOUND / EXCHANGE-RATE / UNDECIDABLE-BY-DATA，禁造第四种；"
            + "锚内必须带 decisionOwner = \"OWNER\" 与「本卡不改常量、不拍板」字段");
    return criteria;
  }

  private static Map<String, String> assumptions() {
    Map<String, String> assumptions = new LinkedHashMap<>();
    assumptions.put("A1", "同文档 ⇒ 可能相关。这是假设，不是真值；真值需查询-相关性标注，本卡不做。");
    assumptions.put("A2", "被降权的同文档图只有与查询同时进入同一 top-k 才有害；本卡不测共现，故 S_same 是有害量上界，必须如实标注。");
    assumptions.put("A3", "跨文档降权视为无害（噪声抑制），只计数、不记代价。");
    return assumptions;
  }
}
