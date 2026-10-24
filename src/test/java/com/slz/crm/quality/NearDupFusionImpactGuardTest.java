package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 融合影响决策锚守卫（query-relevance-truth G1 任务 1.2.3，默认测试集内跑）：只读已提交锚，不重算语料，秒级。
 *
 * <p>守六道：源锚 SHA-256 无漂移、{@code externalCalls==0}、{@code decisionOwner=="OWNER"}、{@code verdict}
 * 只取三选一且与单调性重算一致、{@code bounds} 与生产常量自洽（改融合权重或三档权重必红）、{@code criteria}（R1–R5） 逐字在锚。另做 R1
 * 行号回访（源码标记仍在校准行号上）与 R2 单调性现场复算比对。
 */
class NearDupFusionImpactGuardTest {

  private static final Path DECISION_FILE =
      Path.of("docs", "rag-quality", "near-dup-fusion-impact-v1.json");

  private static final Path CORPUS_FILE =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  private static final Path EXCHANGE_FILE =
      Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json");

  /** proposal §1.1 登记的语料锚 SHA-256（历史真跑产物的身份指纹）。 */
  private static final String CORPUS_SHA256_REGISTERED =
      "82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65";

  private static final Path SERVICE_SOURCE =
      Path.of(
          "src",
          "main",
          "java",
          "com",
          "slz",
          "crm",
          "knowledge",
          "retrieval",
          "KnowledgeRetrievalServiceImpl.java");

  private static final Path RESOLVER_SOURCE =
      Path.of(
          "src",
          "main",
          "java",
          "com",
          "slz",
          "crm",
          "knowledge",
          "retrieval",
          "RetrievalConfigResolver.java");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode decision;

  @BeforeAll
  static void loadDecision() throws Exception {
    assertTrue(Files.exists(DECISION_FILE), "融合影响锚缺失（G1 未产出）: " + DECISION_FILE.toAbsolutePath());
    decision = MAPPER.readTree(Files.readAllBytes(DECISION_FILE));
  }

  @Test
  @DisplayName("源锚身份：语料锚/交换率锚 SHA-256 与已提交文件实算一致，语料锚等于历史登记值")
  void anchorIdentitiesMatchCommittedFiles() throws Exception {
    String corpusSha = sha256(Files.readAllBytes(CORPUS_FILE));
    assertEquals(CORPUS_SHA256_REGISTERED, corpusSha, "校准锚 SHA-256 与登记值不一致（语料被改？）");
    JsonNode sources = decision.path("sourceAnchors");
    assertEquals(corpusSha, sources.path("corpusAnchorSha256").asText(), "校准锚已漂移：语料被改但融合锚没重算");
    assertEquals(
        sha256(Files.readAllBytes(EXCHANGE_FILE)),
        sources.path("exchangeRateAnchorSha256").asText(),
        "上一卡交换率锚已漂移（被重算过？）：融合锚必须跟随重锚");
    assertEquals(CORPUS_FILE.toString().replace('\\', '/'), sources.path("corpusAnchor").asText());
    assertEquals(
        EXCHANGE_FILE.toString().replace('\\', '/'), sources.path("exchangeRateAnchor").asText());
  }

  @Test
  @DisplayName("R5 底线：externalCalls==0、decisionOwner==OWNER、missingEvidence 非空")
  void externalCallsZeroOwnerAndMissingEvidence() {
    assertEquals(0, decision.path("externalCalls").asInt(), "融合影响锚必须零外呼");
    assertEquals("OWNER", decision.path("decisionOwner").asText(), "decisionOwner 必须是 OWNER");
    assertFalse(decision.path("decisionScope").asText().isBlank(), "decisionScope（不改常量、不拍板）不能为空");
    assertTrue(
        decision.path("missingEvidence").isArray() && decision.path("missingEvidence").size() > 0,
        "R5 要求写明「仍需什么数据才可定案」（missingEvidence 非空）");
    assertFalse(decision.path("verdictReason").asText().isBlank(), "verdictReason 不能为空");
  }

  @Test
  @DisplayName("R5：verdict 三选一，且与单调性重算一致（反序⇒VISIBLE-BOUNDED；无反序⇒非 UNDECIDABLE）")
  void verdictInRangeAndConsistent() {
    String verdict = decision.path("verdict").asText();
    assertTrue(
        NearDupFusionImpactAnalyzer.VERDICTS.contains(verdict), "verdict 只能三选一，实测: " + verdict);
    long inversions = decision.path("routeInternalMonotonicity").path("inversions").asLong(-1L);
    assertTrue(inversions >= 0L, "routeInternalMonotonicity.inversions 缺失");
    if (inversions > 0L) {
      assertEquals(
          NearDupFusionImpactAnalyzer.VERDICT_VISIBLE_BOUNDED,
          verdict,
          "路由内出现反序时 verdict 必须落 VISIBLE-BOUNDED（推论 1 被推翻的合法落点）");
    } else {
      assertTrue(
          !NearDupFusionImpactAnalyzer.VERDICT_UNDECIDABLE.equals(verdict),
          "路由内已证不改序时不得落 UNDECIDABLE-BY-DATA（至少能定案路由内不可见）");
    }
  }

  @Test
  @DisplayName("R2：单调性现场复算（5000 点 + 边界点）与锚记录一致")
  void monotonicityRecomputeMatchesAnchor() {
    NearDupFusionImpactAnalyzer.MonotonicityReport report =
        NearDupFusionImpactAnalyzer.checkRouteInternalMonotonicity(
            ImageSimilarityGate.DEFAULT_THRESHOLD);
    JsonNode recorded = decision.path("routeInternalMonotonicity");
    assertEquals(report.samplePoints(), recorded.path("samplePoints").asInt(), "R2 采样点数与锚不一致");
    assertEquals(
        report.inversions(), recorded.path("inversions").asLong(), "R2 反序数与锚不一致（判据或源码被改？）");
    assertEquals(report.maxDrop(), recorded.path("maxDrop").asDouble(), 1e-9D, "R2 maxDrop 与锚不一致");
    assertEquals(report.verdict(), recorded.path("verdict").asText(), "R2 verdict 与锚不一致");
    assertTrue(report.samplePoints() >= 5000, "R2 采样点不足 5000");
  }

  @Test
  @DisplayName("R3：bounds 与生产常量自洽（融合权重从源码正则实测，三档权重取 ImageSimilarityGate 现值）")
  void boundsMatchProductionConstants() throws Exception {
    double imageWeight = imageRouteWeightFromSource();
    JsonNode weights = decision.path("weights");
    assertEquals(
        imageWeight,
        weights.path("imageRouteWeight").asDouble(),
        1e-12D,
        "锚内图像路融合权重与源码字面值不一致（权重被改但锚没重算）");
    double textWeight = textRouteWeightFromSource();
    assertEquals(
        textWeight, weights.path("textRouteWeight").asDouble(), 1e-12D, "锚内文本路融合权重与源码字面值不一致");

    JsonNode bounds = decision.path("bounds");
    assertTrue(bounds.isArray() && bounds.size() == 3, "bounds 必须三档");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_HIGH, bounds.get(0).path("band").asText(), "高档 band 名漂移");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_MID, bounds.get(1).path("band").asText(), "中档 band 名漂移");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_LOW, bounds.get(2).path("band").asText(), "低档 band 名漂移");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH,
        bounds.get(0).path("w").asDouble(),
        1e-12D,
        "高档权重漂移：src/main 改了三档权重但锚没重算");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID,
        bounds.get(1).path("w").asDouble(),
        1e-12D,
        "中档权重漂移");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW,
        bounds.get(2).path("w").asDouble(),
        1e-12D,
        "低档权重漂移");
    assertEquals(
        NearDupFusionImpactAnalyzer.round6(
            imageWeight * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH)),
        bounds.get(0).path("bound").asDouble(),
        "高档位移上界与公式不自洽（手改死值？）");
    assertEquals(
        NearDupFusionImpactAnalyzer.round6(
            imageWeight * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID)),
        bounds.get(1).path("bound").asDouble(),
        "中档位移上界与公式不自洽（手改死值？）");
    assertEquals(
        NearDupFusionImpactAnalyzer.round6(
            imageWeight * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW)),
        bounds.get(2).path("bound").asDouble(),
        "低档位移上界与公式不自洽（手改死值？）");
  }

  @Test
  @DisplayName("R1 行号回访：锚内记录的源码行号仍落在对应标记上（生产链路被改即红）")
  void sourceLinesStillOnMarkers() throws Exception {
    List<String> service = Files.readAllLines(SERVICE_SOURCE, StandardCharsets.UTF_8);
    List<String> resolver = Files.readAllLines(RESOLVER_SOURCE, StandardCharsets.UTF_8);
    JsonNode pipeline = decision.path("pipeline");
    JsonNode weights = decision.path("weights");
    lineMatches(
        service,
        pipeline.path("downWeightCallLine").asLong(),
        "ImageSimilarityGate.downWeightNearDuplicates(",
        "pipeline.downWeightCallLine");
    lineMatches(
        service,
        pipeline.path("fuseCallLine").asLong(),
        "configResolver.fuseRoutes(",
        "pipeline.fuseCallLine");
    lineMatches(
        service,
        pipeline.path("textRecallLine").asLong(),
        "recallAllTextRoutes(",
        "pipeline.textRecallLine");
    lineMatches(
        service,
        pipeline.path("textRerankLine").asLong(),
        "reranker.rerank(",
        "pipeline.textRerankLine");
    lineMatches(
        service,
        pipeline.path("sortDescTopKLine").asLong(),
        "Comparator.comparingDouble(RetrievalCandidate::rerankScore).reversed()",
        "pipeline.sortDescTopKLine");
    lineMatches(
        resolver,
        weights.path("defaultTextRouteWeightLine").asLong(),
        "DEFAULT_TEXT_ROUTE_WEIGHT = ",
        "weights.defaultTextRouteWeightLine");
    lineMatches(
        resolver,
        weights.path("defaultImageRouteWeightLine").asLong(),
        "DEFAULT_IMAGE_ROUTE_WEIGHT = ",
        "weights.defaultImageRouteWeightLine");
    lineMatches(
        resolver,
        weights.path("mergeRouteFirstPutLine").asLong(),
        "candidate.rerankScore() * routeWeight",
        "weights.mergeRouteFirstPutLine");
    assertTrue(
        pipeline.path("downWeightCallLine").asLong() < pipeline.path("fuseCallLine").asLong(),
        "降权必须在融合之前（R1 链路顺序语义）");
    assertTrue(
        pipeline.path("textRecallLine").asLong() < pipeline.path("downWeightCallLine").asLong()
            && pipeline.path("downWeightCallLine").asLong()
                < pipeline.path("sortDescTopKLine").asLong(),
        "主链顺序漂移（召回→降权→排序）");
  }

  @Test
  @DisplayName("R4：gap 分布自洽（观测数守恒、possible ≤ observations、maxFusedGap=权重×maxGap）")
  void gapDistributionSelfConsistent() {
    JsonNode gap = decision.path("gapDistribution");
    JsonNode sameDoc = gap.path("sameDoc");
    JsonNode crossDoc = gap.path("crossDoc");
    int sameObs = sameDoc.path("observations").asInt(-1);
    int crossObs = crossDoc.path("observations").asInt(-1);
    assertTrue(sameObs >= 0 && crossObs >= 0, "gap 分布缺 observations");
    assertEquals(sameObs + crossObs, gap.path("competitorObservations").asInt(), "同文档+跨文档观测数必须守恒");
    assertPossibleWithin(sameDoc, "sameDoc");
    assertPossibleWithin(crossDoc, "crossDoc");
    double imageWeight = decision.path("weights").path("imageRouteWeight").asDouble();
    assertEquals(
        NearDupFusionImpactAnalyzer.round6(imageWeight * sameDoc.path("maxGap").asDouble()),
        sameDoc.path("maxFusedGap").asDouble(),
        "sameDoc.maxFusedGap 与 imageWeight×maxGap 不自洽（手改死值？）");
    assertEquals(
        NearDupFusionImpactAnalyzer.round6(imageWeight * crossDoc.path("maxGap").asDouble()),
        crossDoc.path("maxFusedGap").asDouble(),
        "crossDoc.maxFusedGap 与 imageWeight×maxGap 不自洽（手改死值？）");
    assertFalse(gap.path("necessaryNotSufficient").asText().isBlank(), "R4 要求「必要非充分」声明（不得写成召回损失）");
  }

  @Test
  @DisplayName("R1–R5 判据：锚内逐字回显，禁改写")
  void criteriaVerbatim() {
    JsonNode criteria = decision.path("criteria");
    NearDupFusionImpactDecisionTest.CRITERIA.forEach(
        (key, value) ->
            assertEquals(value, criteria.path(key).asText(), key + " 与 proposal §1.2 不一致（判据文案禁改）"));
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

  private static void assertPossibleWithin(JsonNode dist, String label) {
    JsonNode possible = dist.path("displacementPossibleByW");
    assertTrue(
        possible.isObject() && possible.size() == 3, label + " 缺 displacementPossibleByW 三档");
    possible.forEach(
        item ->
            assertTrue(
                item.asInt() >= 0 && item.asInt() <= dist.path("observations").asInt(),
                label + " 位移可行数超出观测数"));
  }

  private static void lineMatches(List<String> lines, long lineNo, String marker, String field) {
    assertTrue(lineNo >= 1 && lineNo <= lines.size(), field + " 行号越界: " + lineNo);
    assertTrue(
        lines.get((int) lineNo - 1).contains(marker),
        field
            + " 行号漂移：源码第 "
            + lineNo
            + " 行已不含「"
            + marker
            + "」（生产链路被改动？更新融合锚 pipeline/weights 行号并重锚）");
  }

  private static double imageRouteWeightFromSource() throws Exception {
    return weightFromSource(
        Files.readAllLines(RESOLVER_SOURCE, StandardCharsets.UTF_8),
        "DEFAULT_IMAGE_ROUTE_WEIGHT = ");
  }

  private static double textRouteWeightFromSource() throws Exception {
    return weightFromSource(
        Files.readAllLines(RESOLVER_SOURCE, StandardCharsets.UTF_8),
        "DEFAULT_TEXT_ROUTE_WEIGHT = ");
  }

  private static double weightFromSource(List<String> lines, String marker) {
    for (String line : lines) {
      if (line.contains(marker)) {
        Matcher matcher = Pattern.compile("=\\s*(0\\.\\d+)\\s*;").matcher(line);
        assertTrue(matcher.find(), "权重行形态异常: " + line);
        return Double.parseDouble(matcher.group(1));
      }
    }
    throw new IllegalStateException("源码缺权重字面值: " + marker);
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
