package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 档位决策请求守卫（query-relevance-truth G3 任务 3.2~3.4，判据 D1–D4，默认测试集内跑，只读）。
 *
 * <ul>
 *   <li><b>D1 引用可核</b>：文档引用的每个锚 SHA-256 由本守卫实算比对（防手抄）；三份口径锚路径存在且 {@code externalCalls=0}；
 *   <li><b>D2 数字可复算</b>：四张表的每个逐档数字必须能在对应锚里找到同值字段（pair 表 ↔ exchange 锚、融合表 ↔ fusion 锚、检索表 ↔ truth
 *       锚、候选表 ↔ 前两者），禁手写推导值；
 *   <li><b>D3 无越权措辞</b>：不得出现「已决定/已采用/建议上线」，只允许「候选 + 代价 + 待签收」；
 *   <li><b>D4 覆盖性</b>：三口径 + 「仍缺什么」+ 三候选与维持项 + 签收栏必须齐，MISSING 项必须显式声明状态。
 * </ul>
 */
class NearDupFloorDecisionRequestGuardTest {

  private static final Path REQUEST_FILE =
      Path.of("docs", "rag-quality", "near-dup-floor-decision-request-v1.md");

  private static final Path CORPUS_FILE =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  private static final Path EXCHANGE_FILE =
      Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json");

  private static final Path FUSION_FILE =
      Path.of("docs", "rag-quality", "near-dup-fusion-impact-v1.json");

  private static final Path TRUTH_FILE =
      Path.of("docs", "rag-quality", "image-near-dup-truth-v1.json");

  /** D1 引用格式：{@code docs/rag-quality/<name>.json（sha256:<64hex>）}。 */
  private static final Pattern CITATION =
      Pattern.compile("docs/rag-quality/([a-z0-9.\\-]+\\.json)（sha256:([0-9a-f]{64})）");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String request;
  private static JsonNode exchange;
  private static JsonNode fusion;
  private static JsonNode truth;

  @BeforeAll
  static void loadRequest() throws Exception {
    assertTrue(Files.exists(REQUEST_FILE), "决策请求缺失（G3 未产出）: " + REQUEST_FILE.toAbsolutePath());
    request = Files.readString(REQUEST_FILE, StandardCharsets.UTF_8);
    exchange = MAPPER.readTree(Files.readAllBytes(EXCHANGE_FILE));
    fusion = MAPPER.readTree(Files.readAllBytes(FUSION_FILE));
    truth = MAPPER.readTree(Files.readAllBytes(TRUTH_FILE));
  }

  @Test
  @DisplayName("D1：文档引用的每个锚 SHA-256 实算一致；三份口径锚 externalCalls=0")
  void citationsRecomputedAndExternalCallsZero() throws Exception {
    Map<String, String> citations = new LinkedHashMap<>();
    Matcher matcher = CITATION.matcher(request);
    while (matcher.find()) {
      citations.put(matcher.group(1), matcher.group(2));
    }
    assertEquals(4, citations.size(), "决策请求必须引用 4 份锚（数据源 + 三口径），实测: " + citations.keySet());
    for (Map.Entry<String, String> citation : citations.entrySet()) {
      Path file = Path.of("docs", "rag-quality", citation.getKey());
      assertTrue(Files.exists(file), "引用的锚不存在: " + file);
      assertEquals(
          sha256(Files.readAllBytes(file)),
          citation.getValue(),
          "引用 SHA-256 与实算不一致（手抄或文件漂移）: " + citation.getKey());
    }
    assertTrue(citations.containsKey(CORPUS_FILE.getFileName().toString()), "必须引用数据真相源（校准锚）");
    for (Path caliber : List.of(EXCHANGE_FILE, FUSION_FILE, TRUTH_FILE)) {
      assertTrue(citations.containsKey(caliber.getFileName().toString()), "必须引用口径锚: " + caliber);
      JsonNode anchor = MAPPER.readTree(Files.readAllBytes(caliber));
      assertEquals(0, anchor.path("externalCalls").asInt(-1), "口径锚必须零外呼: " + caliber);
      assertEquals(
          "OWNER",
          anchor.path("decisionOwner").asText(),
          "口径锚 decisionOwner 必须是 OWNER: " + caliber);
    }
  }

  @Test
  @DisplayName("D2：pair 表逐格与 exchange 锚同值字段相等（基线档 + 200 点网格档）")
  void pairTableMatchesExchangeAnchor() {
    for (List<String> row : tableRows("## 1. 口径一")) {
      double floor = floorOf(row.get(0));
      JsonNode point = exchangePoint(floor);
      assertEquals(round6(point.path("R").asDouble()), parse(row.get(1)), "pair 表 R 不符: " + row);
      assertEquals(
          round6(point.path("S_same").asDouble()), parse(row.get(2)), "pair 表 S_same 不符: " + row);
      assertEquals(
          round6(point.path("C_cross").asDouble()), parse(row.get(3)), "pair 表 C_cross 不符: " + row);
      if (isBaselineRow(row.get(0))) {
        assertEquals(9887L, floorKey(floor), "「基线」行必须是 0.9887");
        continue;
      }
      assertEquals(
          round6(point.path("deltaR").asDouble()), parse(row.get(4)), "pair 表 ΔR 不符: " + row);
      assertEquals(
          round6(point.path("deltaSSame").asDouble()),
          parse(row.get(5)),
          "pair 表 ΔS_same 不符: " + row);
      assertEquals(
          point.path("extraSuppressedSameDoc").asInt(),
          (int) parse(row.get(6)),
          "pair 表 多压同文档图数 不符: " + row);
      assertEquals(
          round6(point.path("exchangeRate").asDouble()),
          parse(row.get(7)),
          "pair 表 ΔR/ΔS_same 不符: " + row);
    }
  }

  @Test
  @DisplayName("D2：融合表逐格与 fusion 锚 bounds 相等（band/w/bound 三行）")
  void fusionTableMatchesFusionAnchor() {
    List<List<String>> rows = tableRows("## 2. 口径二");
    assertEquals(3, rows.size(), "融合表必须三档（NEAR_HIGH/NEAR_MID/NEAR_LOW）");
    Map<String, JsonNode> bounds = new LinkedHashMap<>();
    for (JsonNode bound : fusion.path("bounds")) {
      bounds.put(bound.path("band").asText(), bound);
    }
    for (List<String> row : rows) {
      JsonNode bound = bounds.get(row.get(0));
      assertTrue(bound != null, "fusion 锚缺 band: " + row.get(0));
      assertEquals(bound.path("w").asDouble(), parse(row.get(1)), 1e-12D, "融合表 w 不符: " + row);
      assertEquals(bound.path("bound").asDouble(), parse(row.get(2)), "融合表 bound 不符: " + row);
    }
  }

  @Test
  @DisplayName("D2：检索表逐格与 truth 锚 floors 相等（recall/污染/竞争率/上一卡 R + T4 一致）")
  void retrievalTableMatchesTruthAnchor() {
    for (List<String> row : tableRows("## 3. 口径三")) {
      JsonNode point = truthPoint(floorOf(row.get(0)));
      assertEquals(
          round6(point.path("positiveRecallAtK").asDouble()),
          parse(row.get(1)),
          "检索表 recallAtK 不符: " + row);
      assertEquals(
          round6(point.path("negativeAContaminationAtK").asDouble()),
          parse(row.get(2)),
          "检索表 contaminationAtK 不符: " + row);
      assertEquals(
          round6(point.path("negativeBSameDocCompetitorRate").asDouble()),
          parse(row.get(3)),
          "检索表 同文档竞争率 不符: " + row);
      assertEquals(
          round6(point.path("previousCardR").asDouble()),
          parse(row.get(4)),
          "检索表 上一卡 R(f) 不符: " + row);
      assertTrue(point.path("t4Match").asBoolean(), "truth 锚 t4Match 必须为 true: " + row.get(0));
      assertEquals("一致", row.get(5).trim(), "检索表 T4 列必须标「一致」: " + row);
    }
  }

  @Test
  @DisplayName("D2：候选对照表逐格与两份口径锚同值（无手写推导值）")
  void candidateTableMatchesAnchors() {
    for (List<String> row : tableRows("## 5. 候选与代价对照")) {
      double floor = floorOf(row.get(0));
      JsonNode pairPoint = exchangePoint(floor);
      JsonNode truthPoint = truthPoint(floor);
      assertEquals(
          round6(pairPoint.path("R").asDouble()), parse(row.get(1)), "候选表 pair R 不符: " + row);
      assertEquals(
          round6(pairPoint.path("S_same").asDouble()),
          parse(row.get(2)),
          "候选表 pair S_same 不符: " + row);
      assertEquals(
          round6(truthPoint.path("positiveRecallAtK").asDouble()),
          parse(row.get(3)),
          "候选表 检索 recallAtK 不符: " + row);
      assertEquals(
          round6(truthPoint.path("negativeAContaminationAtK").asDouble()),
          parse(row.get(4)),
          "候选表 检索 contaminationAtK 不符: " + row);
      assertEquals(
          round6(truthPoint.path("negativeBSameDocCompetitorRate").asDouble()),
          parse(row.get(5)),
          "候选表 检索同文档竞争率 不符: " + row);
    }
  }

  @Test
  @DisplayName("D3：无越权措辞（已决定/已采用/建议上线禁出）；D4：三口径 + 缺什么 + 候选 + 签收栏齐")
  void wordingAndCoverage() {
    for (String forbidden : List.of("已决定", "已采用", "建议上线")) {
      assertFalse(request.contains(forbidden), "D3 越权措辞:「" + forbidden + "」不得出现（只允许候选+代价+待签收）");
    }
    for (String required :
        List.of(
            "## 1. 口径一",
            "## 2. 口径二",
            "## 3. 口径三",
            "## 4. 仍缺什么",
            "## 6. 签收栏",
            "缺项（MISSING）：无",
            "维持 0.9887",
            "下探 0.982",
            "下探 0.98",
            "下探 0.90",
            "改档属新卡")) {
      assertTrue(request.contains(required), "D4 覆盖性缺失:「" + required + "」必须出现");
    }
    assertTrue(request.contains("不是召回率") || request.contains("必要非充分"), "口径二必须保留「必要非充分/不是召回率」声明");
    assertTrue(request.contains("签名："), "签收栏必须含签名栏");
  }

  // ==================== 解析辅助 ====================

  /** 取指定小节的数据行（跳过表头与分隔行；每行按 {@code |} 切格并 trim）。 */
  private static List<List<String>> tableRows(String sectionHeading) {
    List<String> lines = request.lines().toList();
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith(sectionHeading)) {
        start = i;
        break;
      }
    }
    assertTrue(start >= 0, "文档缺小节: " + sectionHeading);
    List<List<String>> rows = new ArrayList<>();
    for (int i = start + 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith("## ")) {
        break;
      }
      if (!line.startsWith("|")) {
        continue;
      }
      String body = line.substring(line.indexOf('|') + 1, line.lastIndexOf('|'));
      List<String> cells = new ArrayList<>();
      for (String cell : body.split("\\|", -1)) {
        cells.add(cell.trim());
      }
      if (cells.size() < 3
          || cells.get(1).startsWith("---")
          || cells.get(0).equals("floor")
          || cells.get(0).equals("band")
          || cells.get(0).equals("候选")) {
        continue;
      }
      rows.add(cells);
    }
    return rows;
  }

  /** 行首格里的 floor（0.9887（基线） / 维持 0.9887（现状） / 下探 0.982 → 数值）。 */
  private static double floorOf(String cell) {
    Matcher matcher = Pattern.compile("(0\\.\\d+)").matcher(cell);
    assertTrue(matcher.find(), "行首格缺 floor 数值: " + cell);
    return Double.parseDouble(matcher.group(1));
  }

  private static boolean isBaselineRow(String cell) {
    return cell.contains("基线") || cell.contains("维持");
  }

  private static JsonNode exchangePoint(double floor) {
    if (floorKey(floor) == 9887L) {
      return exchange.path("baseline");
    }
    for (JsonNode item : exchange.path("grid").path("items")) {
      if (floorKey(item.path("floor").asDouble()) == floorKey(floor)) {
        return item;
      }
    }
    throw new IllegalStateException("exchange 锚缺 floor: " + floor);
  }

  private static JsonNode truthPoint(double floor) {
    for (JsonNode item : truth.path("floors")) {
      if (floorKey(item.path("floor").asDouble()) == floorKey(floor)) {
        return item;
      }
    }
    throw new IllegalStateException("truth 锚缺 floor: " + floor);
  }

  private static long floorKey(double floor) {
    return Math.round(floor * 10000D);
  }

  private static double parse(String cell) {
    return Double.parseDouble(cell.replace("+", "").trim());
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
