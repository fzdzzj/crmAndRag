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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 近重复位移证据 G1 守卫（near-dup-missing-evidence 任务 1.8，默认测试集内跑，只读）。
 *
 * <ul>
 *   <li><b>锚引用可核</b>：四份上游锚 SHA-256 由本守卫实算比对（上游锚字节漂移即红）；
 *   <li><b>verdict 越界即红</b>：三值之外、与 cooccurrenceQueryCount / minDownwardGap / withinWindow 不自洽即红；
 *   <li><b>externalCalls=0 即红</b>：真跑锚不可能零外呼；
 *   <li><b>W_max 不自洽即红</b>：必须恒等于 0.30×(1−0.5)；
 *   <li><b>判别函数引用了 route 即红</b>：读本卡 IT 源码，{@code isImageSource} 方法体内禁止出现 route；
 *   <li><b>queryCount 缺失即红</b>：查询级记录逐条与 sources() 口径自洽。
 * </ul>
 */
class NearDupDisplacementEvidenceGuardTest {

  private static final Path ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json");

  private static final Path IT_SOURCE =
      Path.of(
          "src",
          "test",
          "java",
          "com",
          "slz",
          "crm",
          "quality",
          "NearDupDisplacementEvidenceIT.java");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR), "G1 锚缺失（未真跑或被删）: " + ANCHOR.toAbsolutePath());
    anchor = MAPPER.readTree(Files.readAllBytes(ANCHOR));
  }

  @Test
  @DisplayName("上游四锚 SHA-256 实算比对（字节漂移即红）+ 锚自述字段齐")
  void upstreamAnchorShasRecomputed() throws Exception {
    JsonNode sourceAnchors = anchor.path("sourceAnchors");
    for (String field :
        List.of("corpusAnchor", "fusionImpactAnchor", "exchangeRateAnchor", "truthAnchor")) {
      String relative = sourceAnchors.path(field).asText();
      assertFalse(relative.isBlank(), "锚缺 sourceAnchors." + field);
      Path file = Path.of(relative);
      assertTrue(Files.exists(file), "上游锚不存在: " + file);
      String expected = sourceAnchors.path(field + "Sha256").asText();
      assertEquals(
          sha256(Files.readAllBytes(file)), expected, "上游锚 SHA-256 与实算不一致（手抄或文件漂移）: " + relative);
    }
    assertEquals("OWNER", anchor.path("decisionOwner").asText(), "decisionOwner 必须是 OWNER");
    for (String criterion : List.of("D1", "D2", "D3", "D4", "D5")) {
      assertFalse(
          anchor.path("criteria").path(criterion).asText().isBlank(),
          "锚缺判据 " + criterion + "（判据原文必须落锚）");
    }
    assertTrue(anchor.path("scopeNote").asText().contains("禁止外推"), "D5 的存在性/量级边界声明必须落锚（scopeNote）");
  }

  @Test
  @DisplayName("externalCalls 必须是实测正数且与分项计数一致（写 0 即假绿）")
  void externalCallsMeasuredPositive() {
    int externalCalls = anchor.path("externalCalls").asInt(-1);
    assertTrue(externalCalls > 0, "externalCalls 必须是实测正数（写 0 即假绿），实测 " + externalCalls);
    JsonNode calls = anchor.path("callBreakdown");
    int sum =
        calls.path("visionCalls").asInt(-1)
            + calls.path("embedImageCalls").asInt(-1)
            + calls.path("embedCalls").asInt(-1);
    assertEquals(externalCalls, sum, "callBreakdown 三分项之和必须等于 externalCalls");
    assertTrue(calls.path("embedImageCalls").asInt() > 0, "混合查询必有真视觉编码（embedImageCalls=0 即装配错）");
  }

  @Test
  @DisplayName("W_max 与 0.30×(1−0.5) 不自洽即红；生产常量与网关同值")
  void wMaxConsistentWithFormula() {
    JsonNode constants = anchor.path("productionConstants");
    double wMax = constants.path("wMax").asDouble();
    assertEquals(0.30D * (1D - 0.5D) * 1.0D, wMax, 1e-12D, "W_max 必须恒等于 0.30×(1−0.5)（判据冻结，改窗口即红）");
    assertEquals(0.15D, wMax, 1e-12D, "W_max 必须 = 0.15");
    assertEquals(0.5D, constants.path("weightLow").asDouble(), 1e-12D, "w 最小档必须 0.5");
    assertEquals(0.3D, constants.path("imageRouteWeight").asDouble(), 1e-12D, "图像路权重必须 0.30");
    assertEquals(0.9944D, constants.path("threshold").asDouble(), 1e-12D, "网关阈值必须 0.9944");
    assertEquals(
        0.9887D, constants.path("nearDuplicateFloor").asDouble(), 1e-12D, "近似同图下限必须 0.9887");
    assertTrue(constants.path("routeIsNotModality").asBoolean(), "routeIsNotModality 必须为 true");
    assertEquals(
        "vector|keyword|hybrid|ocr", constants.path("routeDomain").asText(), "route 取值域自述漂移");
    assertFalse(
        constants.path("routeDomain").asText().toUpperCase().contains("IMAGE"),
        "route 取值域不得含 IMAGE（route 不是模态位）");
  }

  @Test
  @DisplayName("判别函数引用了 route 即红（读 IT 源码断言 isImageSource 方法体只认 sourceType）")
  void discriminatorMustNotUseRoute() throws Exception {
    assertTrue(Files.exists(IT_SOURCE), "IT 源码缺失: " + IT_SOURCE);
    String source = Files.readString(IT_SOURCE, StandardCharsets.UTF_8);
    List<String> bodies = methodBodies(source, "boolean isImageSource(");
    assertFalse(bodies.isEmpty(), "IT 源码找不到 isImageSource 判别函数");
    for (String body : bodies) {
      assertFalse(
          body.matches("(?s).*\\broute\\b.*"),
          "模态判别函数引用了 route（route 是 vector|keyword|hybrid|ocr，不是图文路由）——G1 归因将全错");
      assertTrue(body.contains("sourceType"), "模态判别函数必须使用 sourceType");
    }
  }

  @Test
  @DisplayName("queryCount 缺失即红；查询级记录与共现/分差口径逐条自洽")
  void queryRecordsConsistent() {
    int queryCount = anchor.path("queryCount").asInt(0);
    assertTrue(queryCount > 0, "queryCount 缺失或为 0（D5 强制落锚）");
    JsonNode queries = anchor.path("queries");
    assertEquals(queryCount, queries.size(), "queries 数组长度必须等于 queryCount");
    int cooccurrence = 0;
    double wMax = anchor.path("productionConstants").path("wMax").asDouble();
    List<Double> gaps = new ArrayList<>();
    for (JsonNode query : queries) {
      List<JsonNode> sources = new ArrayList<>();
      query.path("sources").forEach(sources::add);
      assertFalse(
          sources.isEmpty(), "queryIndex=" + query.path("queryIndex").asInt() + " 无 sources 记录");
      boolean hasImage = false;
      boolean hasNonImage = false;
      for (JsonNode source : sources) {
        if ("IMAGE".equals(source.path("sourceType").asText())) {
          hasImage = true;
        } else {
          hasNonImage = true;
        }
      }
      boolean cooccurred = hasImage && hasNonImage;
      assertEquals(
          cooccurred,
          query.path("cooccurred").asBoolean(),
          "queryIndex=" + query.path("queryIndex").asInt() + " 的 cooccurred 与 sources 口径不符");
      if (cooccurred) {
        cooccurrence++;
      }
      JsonNode gapNode = query.path("downwardGap");
      if (!gapNode.isMissingNode() && !gapNode.isNull()) {
        double gap = gapNode.asDouble();
        gaps.add(gap);
        assertTrue(query.path("imageCandidateCount").asInt() > 0, "有 gap 的查询必须有图像候选");
      } else {
        assertTrue(
            query.path("imageCandidateIsLast").asBoolean()
                || query.path("imageCandidateCount").asInt() == 0,
            "gap=null 的查询必须标 imageCandidateIsLast 或本就没有图像候选（D2 口径）");
      }
      if (query.path("imageCandidateIsLast").asBoolean()) {
        assertTrue(
            gapNode.isNull() || gapNode.isMissingNode(),
            "imageCandidateIsLast=true 的查询不应有 downwardGap");
      }
    }
    assertEquals(
        cooccurrence,
        anchor.path("cooccurrenceQueryCount").asInt(),
        "cooccurrenceQueryCount 与查询级重算不符");
    assertTrue(cooccurrence <= queryCount, "共现数不得超过查询数");

    JsonNode minNode = anchor.path("minDownwardGap");
    if (gaps.isEmpty()) {
      assertTrue(minNode.isNull() || minNode.isMissingNode(), "全轮无 gap 时 minDownwardGap 必须为 null");
    } else {
      double recomputedMin = gaps.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
      assertEquals(recomputedMin, minNode.asDouble(), "minDownwardGap 与查询级 gap 重算不符（手改锚字段即红）");
    }
  }

  @Test
  @DisplayName("verdict 三值之外即红；与 minDownwardGap / withinWindow 不自洽即红")
  void verdictConsistent() {
    String verdict = anchor.path("verdict").asText();
    double wMax = anchor.path("productionConstants").path("wMax").asDouble();
    int cooccurrence = anchor.path("cooccurrenceQueryCount").asInt();
    JsonNode minNode = anchor.path("minDownwardGap");
    Double minGap = minNode.isNull() || minNode.isMissingNode() ? null : minNode.asDouble();
    JsonNode withinWindow = anchor.path("withinWindow");
    switch (verdict) {
      case "NO-COOCCURRENCE" ->
          assertEquals(0, cooccurrence, "NO-COOCCURRENCE 必须 cooccurrenceQueryCount=0");
      case "NOT-DISPLACED-IN-CORPUS" -> {
        assertTrue(cooccurrence > 0, "有共现才能判 NOT-DISPLACED（否则是 NO-COOCCURRENCE）");
        assertTrue(
            minGap == null || minGap > wMax,
            "NOT-DISPLACED 要求 minDownwardGap 缺失（图像候选全末位）或 > W_max，实测 " + minGap);
        assertEquals(0, withinWindow.size(), "NOT-DISPLACED 不得有窗口内条目");
      }
      case "POSSIBLY-DISPLACED" -> {
        assertTrue(cooccurrence > 0, "有共现才能判 POSSIBLY-DISPLACED");
        assertTrue(minGap != null, "POSSIBLY-DISPLACED 必须有实测 minDownwardGap");
        assertTrue(minGap <= wMax, "POSSIBLY-DISPLACED 要求 minDownwardGap ≤ W_max");
        assertTrue(withinWindow.size() > 0, "POSSIBLY-DISPLACED 必须逐条列出 (query, gap)");
        for (JsonNode entry : withinWindow) {
          assertTrue(entry.path("gap").asDouble() <= wMax, "withinWindow 条目 gap 超 W_max: " + entry);
        }
      }
      default -> throw new AssertionError("verdict 越界（只允许三值）: " + verdict);
    }
  }

  @Test
  @DisplayName("反事实重建：assumption 在位、perWeight 与总数自洽（禁与实测并表）")
  void counterfactualConsistent() {
    JsonNode counterfactual = anchor.path("counterfactual");
    assertFalse(counterfactual.isMissingNode(), "反事实重建节缺失（D4）");
    String assumption = counterfactual.path("assumption").asText();
    assertTrue(assumption.contains("迁移"), "assumption 必须写死「竞争者分差跨查询可迁移」是假设");
    assertTrue(assumption.contains("不是实测"), "assumption 必须声明反事实不是实测");
    int displacing = counterfactual.path("displacingPairs").asInt();
    int comparisons = counterfactual.path("comparisons").asInt();
    assertTrue(
        displacing >= 0 && displacing <= comparisons, "displacingPairs 必须在 [0, comparisons] 内");
    int perWeightSum = 0;
    JsonNode perWeight = counterfactual.path("perWeight");
    for (String tier : List.of("0.75", "0.6", "0.5")) {
      assertTrue(perWeight.has(tier), "perWeight 缺档 " + tier);
      perWeightSum += perWeight.path(tier).asInt();
    }
    assertEquals(displacing, perWeightSum, "perWeight 三档之和必须等于 displacingPairs");
    JsonNode tiers = counterfactual.path("weightTiers");
    assertEquals(3, tiers.size(), "weightTiers 必须三档");
    assertEquals(0.75D, tiers.get(0).asDouble(), 1e-12D, "第一档 0.75");
    assertEquals(0.6D, tiers.get(1).asDouble(), 1e-12D, "第二档 0.6");
    assertEquals(0.5D, tiers.get(2).asDouble(), 1e-12D, "第三档 0.5");
  }

  @Test
  @DisplayName("corpusAssets 三份登记 PDF 的 sha256 实算一致（语料零替换）")
  void corpusAssetsMatchRegisteredFiles() throws Exception {
    JsonNode assets = anchor.path("corpusAssets");
    assertEquals(3, assets.size(), "语料基座必须是 3 份登记 PDF");
    for (JsonNode asset : assets) {
      Path file =
          Path.of(
              "src", "test", "resources", "rag-quality", "real-pdf", asset.path("file").asText());
      assertTrue(Files.exists(file), "语料 PDF 缺失: " + file);
      assertEquals(
          asset.path("sha256").asText(),
          sha256(Files.readAllBytes(file)),
          "语料 PDF SHA-256 与登记不符: " + asset.path("file").asText());
    }
  }

  // ==================== 解析辅助 ====================

  /** 取指定签名前缀起的方法体（花括号配平；用于源码级断言）。 */
  private static List<String> methodBodies(String source, String signaturePrefix) {
    List<String> bodies = new ArrayList<>();
    int searchFrom = 0;
    while (true) {
      int declaration = source.indexOf(signaturePrefix, searchFrom);
      if (declaration < 0) {
        return bodies;
      }
      int open = source.indexOf('{', declaration);
      if (open < 0) {
        return bodies;
      }
      int depth = 0;
      for (int i = open; i < source.length(); i++) {
        char current = source.charAt(i);
        if (current == '{') {
          depth++;
        } else if (current == '}') {
          depth--;
          if (depth == 0) {
            bodies.add(source.substring(open, i + 1));
            searchFrom = i + 1;
            break;
          }
        }
      }
      if (depth != 0) {
        return bodies;
      }
    }
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
