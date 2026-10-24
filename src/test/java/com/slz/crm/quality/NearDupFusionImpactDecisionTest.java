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
import java.io.IOException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 近重复融合影响决策锚的唯一生成/断言入口（query-relevance-truth G1 任务 1.2.1~1.2.2）。
 *
 * <p><b>唯一写入口</b>：只有 {@code -Dnear.dup.fusion.write=true} 时才写 {@code
 * docs/rag-quality/near-dup-fusion-impact-v1.json}；默认模式复算后<b>只断言不写</b>，且与已提交锚<b>逐字节相等</b>（锚内无时间戳等
 * volatile 字段，字节比对可行）。
 *
 * <p><b>R1 源码行实测</b>：链路顺序与融合权重不以注释为据——本类读 {@code KnowledgeRetrievalServiceImpl} / {@code
 * RetrievalConfigResolver} 源文件，定位调用点行号（断言「降权在融合之前」的源码顺序）并正则提取权重字面值（断言 0.70/0.30），
 * 实测行号与权重入锚；源码一改，锚重生成即不同，默认模式逐字节校验即红。
 *
 * <p><b>零外呼</b>：余弦取自已提交校准锚（历史真跑产物），本类不调用任何模型接口、不读环境变量、不读 {@code .env}， {@code externalCalls} 恒为 0。
 */
class NearDupFusionImpactDecisionTest {

  /** 余弦校准锚（数据真相源，本卡只读）。 */
  private static final Path CORPUS_FILE =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** corpusAnchorSha256 的历史登记值（任务 0.4 复算一致）。 */
  private static final String CORPUS_SHA256_REGISTERED =
      "82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65";

  /** 上一卡交换率锚（sourceAnchors 身份登记，本卡只读）。 */
  private static final Path EXCHANGE_FILE =
      Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json");

  /** 融合影响决策锚（本卡唯一写入口的产物）。 */
  private static final Path DECISION_FILE =
      Path.of("docs", "rag-quality", "near-dup-fusion-impact-v1.json");

  /** 写模式开关（默认 false = 只断言不写）。 */
  private static final String WRITE_FLAG = "near.dup.fusion.write";

  /** 生产主链源文件（R1 行号实测对象，本卡只读）。 */
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

  /** 融合权重源文件（R1 权重实测对象，本卡只读）。 */
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

  /** 判据原文（proposal §1.2 逐字回显；守卫测试逐字比对）。 */
  static final Map<String, String> CRITERIA = criteria();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("融合级代价边界复算并产出/核对决策锚（默认只断言不写，逐字节校验）")
  void recomputeFusionImpactAndEmitDecision() throws Exception {
    byte[] corpusBytes = Files.readAllBytes(CORPUS_FILE);
    JsonNode corpus = MAPPER.readTree(corpusBytes);
    String corpusSha = sha256(corpusBytes);
    assertEquals(CORPUS_SHA256_REGISTERED, corpusSha, "校准锚 SHA-256 与登记值不一致（语料被改？）");
    String exchangeSha = sha256(Files.readAllBytes(EXCHANGE_FILE));

    List<NearDupFusionImpactAnalyzer.Pair> pairs = pairs(corpus);
    NearDupFusionImpactAnalyzer.requireStrata(pairs);
    assertEquals(540, pairs.size(), "校准锚 pairs 必须 540 对");

    SourceFacts service = sourceFacts(Files.readAllLines(SERVICE_SOURCE, StandardCharsets.UTF_8));
    SourceFacts resolver = sourceFacts(Files.readAllLines(RESOLVER_SOURCE, StandardCharsets.UTF_8));
    printPipeline(service, resolver);

    // R1：融合权重以源码字面值为证，不以注释为据
    assertEquals(0.70D, resolver.textRouteWeight(), "文本路融合权重源码实测不是 0.70（按实测重算并记录偏差）");
    assertEquals(0.30D, resolver.imageRouteWeight(), "图像路融合权重源码实测不是 0.30（按实测重算并记录偏差）");
    assertTrue(
        service.downWeightLine() < service.fuseLine(),
        "R1 链路顺序被破坏：downWeightNearDuplicates 必须在 fuseRoutes 之前（源码行）");

    // R2：路由内单调性（5000 点等距 + 边界点；反序即推论 1 被推翻，如实上报不改判据）
    NearDupFusionImpactAnalyzer.MonotonicityReport monotonicity =
        NearDupFusionImpactAnalyzer.checkRouteInternalMonotonicity(
            ImageSimilarityGate.DEFAULT_THRESHOLD);
    assertTrue(monotonicity.samplePoints() >= 5000, "R2 采样点不足 5000");
    assertEquals(0L, monotonicity.inversions(), "R2 路由内出现反序（推论 1 被数据推翻）：如实上报，不改判据");
    printMonotonicity(monotonicity);

    // R3：位移上界逐档常量断言（融合权重一改这里即红）
    List<NearDupFusionImpactAnalyzer.Bound> bounds =
        NearDupFusionImpactAnalyzer.bounds(resolver.imageRouteWeight());
    assertEquals(0.075D, bounds.get(0).bound(), "高档位移上界必须是 0.075");
    assertEquals(0.12D, bounds.get(1).bound(), "中档位移上界必须是 0.12");
    assertEquals(0.15D, bounds.get(2).bound(), "低档位移上界必须是 0.15");
    printBounds(bounds);

    // R4：基线档 gap 分布（同文档 vs 跨文档竞争者，代理口径见分析器类注）
    // 基线档测于 0.9887（冻结带基准），不随生产准入下限（0.982）变动——锚逐字节校验依赖此口径
    List<NearDupFusionImpactAnalyzer.BandCandidate> bandCandidates =
        NearDupFusionImpactAnalyzer.bandCandidates(
            pairs, ImageSimilarityGate.BAND_REFERENCE_FLOOR, ImageSimilarityGate.DEFAULT_THRESHOLD);
    List<NearDupFusionImpactAnalyzer.CompetitorObservation> observations =
        NearDupFusionImpactAnalyzer.competitorObservations(
            pairs, bandCandidates, ImageSimilarityGate.BAND_REFERENCE_FLOOR);
    NearDupFusionImpactAnalyzer.GapDistribution sameDoc =
        NearDupFusionImpactAnalyzer.gapDistribution(
            observations, true, resolver.imageRouteWeight());
    NearDupFusionImpactAnalyzer.GapDistribution crossDoc =
        NearDupFusionImpactAnalyzer.gapDistribution(
            observations, false, resolver.imageRouteWeight());
    printGapDistribution(bandCandidates, observations, sameDoc, crossDoc);

    // R5：三值判定（本卡跨路可见性不可定案：缺文本路分数分布）
    String verdict = NearDupFusionImpactAnalyzer.verdict(monotonicity, false);
    printCriteria(verdict, monotonicity);

    ObjectNode decision = MAPPER.createObjectNode();
    decision.put("card", "query-relevance-truth");
    decision.put("group", "G1");
    decision.put("decisionVersion", "NEAR-DUP-FUSION-IMPACT-1.0");
    decision.set("criteria", stringNode(CRITERIA));
    decision.put("decisionOwner", "OWNER");
    // decisionScope 是锚内冻结的历史串（逐字节校验，禁改）：0.9887 是锚生成时的测档（「锚测于 0.9887」口径），
    // 生产下限已由 near-dup-floor-lowering 卡下探至 0.982；本串描述的是 G1 卡当时的事实，改串即破锚字节等值。
    decision.put(
        "decisionScope",
        "本卡不改任何生产常量与融合权重（threshold=0.9944 / NEAR_DUPLICATE_FLOOR=0.9887 / 权重 0.75|0.6|0.5 /"
            + " 融合 0.7|0.3 逐字不动）、不拍板档位；位移上界与可见条件交 OWNER 决策，改档/改权重须新卡。");
    ObjectNode sourceAnchors = decision.putObject("sourceAnchors");
    sourceAnchors.put("corpusAnchor", CORPUS_FILE.toString().replace('\\', '/'));
    sourceAnchors.put("corpusAnchorSha256", corpusSha);
    sourceAnchors.put("exchangeRateAnchor", EXCHANGE_FILE.toString().replace('\\', '/'));
    sourceAnchors.put("exchangeRateAnchorSha256", exchangeSha);
    decision.set("productionConstants", productionNode());
    decision.set("weights", weightsNode(service, resolver));
    decision.set("pipeline", pipelineNode(service));
    decision.set("routeInternalMonotonicity", monotonicityNode(monotonicity));
    decision.set("bounds", boundsNode(bounds));
    decision.put(
        "boundsFormula",
        "bound(w) = imageRouteWeight × (1 − w)；图像候选融合贡献从 imageRouteWeight×c 降到"
            + " imageRouteWeight×w×c，位移上界为融合分口径；可见条件：竞争对手融合分与该候选之差 ≤ bound"
            + " 时位移才可能发生。");
    decision.set(
        "gapDistribution", gapDistributionNode(bandCandidates, observations, sameDoc, crossDoc));
    decision.put("verdict", verdict);
    decision.put("verdictReason", verdictReason(verdict, monotonicity));
    decision.set(
        "missingEvidence",
        arrayNode(
            List.of(
                "真实查询的文本路 rerank 分数分布——判定融合分差 ≤ bound 的竞争者是否存在（跨路可见性定案的前提）",
                "真实查询的图像候选与文本候选共现观测（上一卡假设 A2 的共现面）",
                "变体↔第三图视觉余弦——R4 竞争者分数以 base 对为代理，未经验证")));
    decision.put("externalCalls", 0);

    if (Boolean.parseBoolean(System.getProperty(WRITE_FLAG, "false"))) {
      write(decision);
      System.out.println("[QRT1] 写模式：融合影响锚已写入 " + DECISION_FILE);
    } else {
      assertMatchesCommitted(decision);
      System.out.println("[QRT1] 默认模式：只断言不写，融合影响锚与复算结果逐字节一致");
    }
  }

  // ==================== R1 源码实测 ====================

  /** R1 源码实测事实（行号 1 起；权重从 {@code RetrievalConfigResolver} 字面值正则提取）。 */
  record SourceFacts(
      long textRecallLine,
      long textRerankLine,
      long downWeightLine,
      long fuseLine,
      long sortLine,
      long imageGateLine,
      long imageFloorLine,
      double textRouteWeight,
      double imageRouteWeight,
      long textRouteWeightLine,
      long imageRouteWeightLine,
      long fuseRoutesJavadocLine,
      long fuseRoutesDeclLine,
      long mergeRouteFirstPutLine,
      long mergeRouteAccumulateLine) {}

  /**
   * 从源码行定位链路调用点与融合权重（fail-fast：任何标记缺失即抛）。
   *
   * @param lines 源文件逐行（UTF-8）
   * @return 实测事实
   */
  private static SourceFacts sourceFacts(List<String> lines) throws IOException {
    boolean service = lines.stream().anyMatch(line -> line.contains("retrieveFromKnowledgeBases("));
    Long textRecall = firstLine(lines, "recallAllTextRoutes(");
    Long textRerank = firstLine(lines, "reranker.rerank(");
    Long downWeight = firstLine(lines, "ImageSimilarityGate.downWeightNearDuplicates(");
    Long fuse = firstLine(lines, "configResolver.fuseRoutes(");
    Long sort =
        firstLine(lines, "Comparator.comparingDouble(RetrievalCandidate::rerankScore).reversed()");
    Long imageGate = firstLine(lines, "ImageSimilarityGate.gate(");
    Long imageFloor = firstLine(lines, "ImageSimilarityGate.effectiveFloor(similarityThreshold)");
    Long textWeightLine = firstLine(lines, "DEFAULT_TEXT_ROUTE_WEIGHT = ");
    Long imageWeightLine = firstLine(lines, "DEFAULT_IMAGE_ROUTE_WEIGHT = ");
    Long fuseJavadoc = firstLine(lines, "文本 0.7 + 图片 0.3");
    Long fuseDecl = firstLine(lines, "List<RetrievalCandidate> fuseRoutes(");
    Long mergeFirst = firstLine(lines, "candidate.rerankScore() * routeWeight");
    Long mergeAccumulate =
        firstLine(lines, "existing.rerankScore() + candidate.rerankScore() * routeWeight");
    if (service) {
      assertNotNull(textRecall, "源码缺 recallAllTextRoutes 调用（主链被改？）");
      assertNotNull(textRerank, "源码缺文本重排调用（主链被改？）");
      assertNotNull(downWeight, "源码缺 downWeightNearDuplicates 调用（主链被改？）");
      assertNotNull(fuse, "源码缺 fuseRoutes 调用（主链被改？）");
      assertNotNull(sort, "源码缺 rerankScore 降序排序（主链被改？）");
      assertNotNull(imageGate, "源码缺 ImageSimilarityGate.gate 调用（图像路被改？）");
      assertNotNull(imageFloor, "源码缺 effectiveFloor 检索下限（图像路被改？）");
      return new SourceFacts(
          textRecall,
          textRerank,
          downWeight,
          fuse,
          sort,
          imageGate,
          imageFloor,
          0D,
          0D,
          0L,
          0L,
          0L,
          0L,
          0L,
          0L);
    }
    assertNotNull(textWeightLine, "源码缺 DEFAULT_TEXT_ROUTE_WEIGHT（融合权重被改？）");
    assertNotNull(imageWeightLine, "源码缺 DEFAULT_IMAGE_ROUTE_WEIGHT（融合权重被改？）");
    assertNotNull(fuseJavadoc, "源码缺 fuseRoutes 语义注释（融合语义被改？）");
    assertNotNull(fuseDecl, "源码缺 fuseRoutes 声明（融合语义被改？）");
    assertNotNull(mergeFirst, "源码缺 mergeRoute 首插乘权重（融合语义被改？）");
    assertNotNull(mergeAccumulate, "源码缺 mergeRoute 同键累加（融合语义被改？）");
    double textWeight = extractWeight(lines.get(textWeightLine.intValue() - 1));
    double imageWeight = extractWeight(lines.get(imageWeightLine.intValue() - 1));
    return new SourceFacts(
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        textWeight,
        imageWeight,
        textWeightLine,
        imageWeightLine,
        fuseJavadoc,
        fuseDecl,
        mergeFirst,
        mergeAccumulate);
  }

  /** 首个包含标记的行号（1 起；无则 null）。 */
  private static Long firstLine(List<String> lines, String marker) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(marker)) {
        return (long) (i + 1);
      }
    }
    return null;
  }

  /** 从 {@code DEFAULT_X_WEIGHT = 0.70;} 形态的行提取权重字面值。 */
  private static double extractWeight(String line) {
    Matcher matcher = Pattern.compile("=\\s*(0\\.\\d+)\\s*;").matcher(line);
    assertTrue(matcher.find(), "权重行形态异常: " + line);
    return Double.parseDouble(matcher.group(1));
  }

  // ==================== 输入解析 ====================

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

  private static ObjectNode weightsNode(SourceFacts service, SourceFacts resolver) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("textRouteWeight", resolver.textRouteWeight());
    node.put("imageRouteWeight", resolver.imageRouteWeight());
    node.put("weightSource", "RetrievalConfigResolver.java 字面值正则实测（R1：不以注释为据）");
    node.put("defaultTextRouteWeightLine", resolver.textRouteWeightLine());
    node.put("defaultImageRouteWeightLine", resolver.imageRouteWeightLine());
    node.put("fuseRoutesJavadocLine", resolver.fuseRoutesJavadocLine());
    node.put("fuseRoutesDeclLine", resolver.fuseRoutesDeclLine());
    node.put("mergeRouteFirstPutLine", resolver.mergeRouteFirstPutLine());
    node.put("mergeRouteAccumulateLine", resolver.mergeRouteAccumulateLine());
    node.put("downWeightCallLine", service.downWeightLine());
    node.put("fuseCallLine", service.fuseLine());
    node.put(
        "perKbOverrideNote",
        "resolveTextRouteWeight/resolveImageRouteWeight 有 per-KB 覆盖重载（:179-182/:190-193 附近）；"
            + "本卡上界按默认权重 0.3 计，per-KB 覆盖值域同为 [0,1]，上界公式不变、数值随覆盖变化。");
    return node;
  }

  private static ObjectNode pipelineNode(SourceFacts service) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put(
        "order",
        "文本多路召回 → 文本重排 → 图像召回(minScore=effectiveFloor) + gate → 图像重排 →"
            + " downWeightNearDuplicates → fuseRoutes(0.7/0.3 分数制同切片累加) → 按 rerankScore 降序取 top-k");
    node.put("textRecallLine", service.textRecallLine());
    node.put("textRerankLine", service.textRerankLine());
    node.put("downWeightCallLine", service.downWeightLine());
    node.put("fuseCallLine", service.fuseLine());
    node.put("sortDescTopKLine", service.sortLine());
    node.put("imageGateLine", service.imageGateLine());
    node.put("imageFloorLine", service.imageFloorLine());
    node.put("downWeightBeforeFuse", service.downWeightLine() < service.fuseLine());
    return node;
  }

  private static ObjectNode monotonicityNode(
      NearDupFusionImpactAnalyzer.MonotonicityReport report) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put(
        "domain",
        "["
            + ImageSimilarityGate.BAND_REFERENCE_FLOOR
            + ", "
            + ImageSimilarityGate.DEFAULT_THRESHOLD
            + "]");
    node.put("samplePoints", report.samplePoints());
    node.put("inversions", report.inversions());
    node.put("maxDrop", report.maxDrop());
    node.put("strictlyIncreasing", report.strictlyIncreasing());
    node.put("verdict", report.verdict());
    node.put(
        "note",
        "score(c) = c × w(tier(c))，tier/band/weight 取 ImageSimilarityGate 现值（同图档 ×1、"
            + "近似带按 band 降权）；5000 点整数插值等距 + 低/高档边界点，R2 判据=同序（允许并列）。");
    return node;
  }

  private static ArrayNode boundsNode(List<NearDupFusionImpactAnalyzer.Bound> bounds) {
    ArrayNode array = MAPPER.createArrayNode();
    for (NearDupFusionImpactAnalyzer.Bound bound : bounds) {
      ObjectNode node = array.addObject();
      node.put("band", bound.band());
      node.put("w", bound.w());
      node.put("bound", bound.bound());
    }
    return array;
  }

  private static ObjectNode gapDistributionNode(
      List<NearDupFusionImpactAnalyzer.BandCandidate> bandCandidates,
      List<NearDupFusionImpactAnalyzer.CompetitorObservation> observations,
      NearDupFusionImpactAnalyzer.GapDistribution sameDoc,
      NearDupFusionImpactAnalyzer.GapDistribution crossDoc) {
    ObjectNode node = MAPPER.createObjectNode();
    // gapDistribution.floor 记录测档 0.9887（冻结带基准），非生产现值
    node.put("floor", ImageSimilarityGate.BAND_REFERENCE_FLOOR);
    node.put(
        "population",
        "位移主体 = near-duplicate 对中 cosine ∈ [floor, threshold) 的候选（查询=变体，被降权方=其 base 黄金切片）；"
            + "竞争者 = base 两两余弦中涉及其 base 且 ≥ floor 者（代理口径：锚内无变体↔第三图对）");
    node.put("bandCandidateCount", bandCandidates.size());
    ArrayNode candidates = node.putArray("bandCandidates");
    for (NearDupFusionImpactAnalyzer.BandCandidate candidate : bandCandidates) {
      ObjectNode item = candidates.addObject();
      item.put("baseKey", candidate.baseKey());
      item.put("kind", candidate.kind());
      item.put("cosine", candidate.cosine());
    }
    node.put("competitorObservations", observations.size());
    ArrayNode obsNode = node.putArray("competitorObservationDetails");
    for (NearDupFusionImpactAnalyzer.CompetitorObservation observation : observations) {
      ObjectNode item = obsNode.addObject();
      item.put("baseKey", observation.baseKey());
      item.put("competitorKey", observation.competitorKey());
      item.put("sameDoc", observation.sameDoc());
      item.put("candidateCosine", observation.candidateCosine());
      item.put("competitorCosine", observation.competitorCosine());
      item.put("gap", NearDupFusionImpactAnalyzer.round6(observation.gap()));
    }
    node.set("sameDoc", distributionNode(sameDoc));
    node.set("crossDoc", distributionNode(crossDoc));
    node.put(
        "necessaryNotSufficient",
        "本分布是位移「数学上可能」的必要条件（竞争者落在降权窗口内），非充分：位移发生还需真实查询共现"
            + "（A2 未测）与变体真实分数（代理未验证）；位移 ≠ 召回损失，本分布更不是召回率。");
    return node;
  }

  private static ObjectNode distributionNode(NearDupFusionImpactAnalyzer.GapDistribution dist) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("observations", dist.observations());
    ObjectNode possible = node.putObject("displacementPossibleByW");
    dist.displacementPossibleByW().forEach(possible::put);
    ObjectNode quantiles = node.putObject("gapQuantiles");
    quantiles.put("p50", dist.p50());
    quantiles.put("p90", dist.p90());
    quantiles.put("p99", dist.p99());
    node.put("maxGap", dist.maxGap());
    node.put("maxFusedGap", dist.maxFusedGap());
    return node;
  }

  private static ArrayNode arrayNode(List<String> values) {
    ArrayNode array = MAPPER.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  private static String verdictReason(
      String verdict, NearDupFusionImpactAnalyzer.MonotonicityReport monotonicity) {
    StringBuilder reason = new StringBuilder();
    if (NearDupFusionImpactAnalyzer.VERDICT_VISIBLE_BOUNDED.equals(verdict)
        && monotonicity.inversions() > 0L) {
      reason
          .append("R5 第①支：R2 出现 ")
          .append(monotonicity.inversions())
          .append(" 处反序，§1.1 推论 1 被数据推翻——降权在图像路由内即可见；跨路位移上界仍为 bounds 三档。");
    } else if (NearDupFusionImpactAnalyzer.VERDICT_INVISIBLE_IN_ROUTE_ONLY.equals(verdict)) {
      reason
          .append("R5 第②支：R2 全程同序（")
          .append(monotonicity.samplePoints())
          .append(
              " 点反序 0 处）⇒ 降权在图像路由内不改任何两两顺序；跨路可见性缺文本路 rerank 分数分布，无法定案 ⇒"
                  + " INVISIBLE-IN-ROUTE-ONLY。仍需数据见 missingEvidence。");
    } else {
      reason.append("R5 第③支：跨路可见条件已量化 ⇒ VISIBLE-BOUNDED。");
    }
    return reason.toString();
  }

  // ==================== 断言 / 写出 ====================

  private static void assertMatchesCommitted(ObjectNode computed) throws Exception {
    assertTrue(Files.exists(DECISION_FILE), "融合影响锚不存在（首轮应跑写模式）: " + DECISION_FILE);
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
   * .gitattributes} 的 {@code *.json text eol=lf} 让仓库里存的是 LF。不钉死会让「干净 checkout」与「本机刚跑过写模式」得出相反结论。
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
        return "融合影响锚与复算不一致，首个差异在第 "
            + (i + 1)
            + " 行：\n  期望: "
            + want
            + "\n  实际: "
            + got
            + "\n（重写：bash scripts/rag-quality/near-dup-fusion-impact-report.sh）";
      }
    }
    return "融合影响锚与复算不一致（行内容全同但字节不同，检查行尾/编码）";
  }

  // ==================== stdout 固定表格 ====================

  private static void printPipeline(SourceFacts service, SourceFacts resolver) {
    System.out.println("[QRT1][表 1/4 链路与权重（R1 源码实测）]");
    System.out.println(
        "service: textRecall="
            + service.textRecallLine()
            + " textRerank="
            + service.textRerankLine()
            + " downWeight="
            + service.downWeightLine()
            + " fuse="
            + service.fuseLine()
            + " sortTopK="
            + service.sortLine()
            + " imageGate="
            + service.imageGateLine()
            + " imageFloor="
            + service.imageFloorLine());
    System.out.println(
        "resolver: textWeight="
            + resolver.textRouteWeight()
            + "@L"
            + resolver.textRouteWeightLine()
            + " imageWeight="
            + resolver.imageRouteWeight()
            + "@L"
            + resolver.imageRouteWeightLine()
            + " fuseJavadoc@L"
            + resolver.fuseRoutesJavadocLine()
            + " fuseDecl@L"
            + resolver.fuseRoutesDeclLine()
            + " mergeFirstPut@L"
            + resolver.mergeRouteFirstPutLine()
            + " mergeAccumulate@L"
            + resolver.mergeRouteAccumulateLine());
    System.out.println(
        "downWeightBeforeFuse=" + (service.downWeightLine() < service.fuseLine()) + "（降权在融合之前）");
  }

  private static void printMonotonicity(NearDupFusionImpactAnalyzer.MonotonicityReport report) {
    System.out.println(
        "[QRT1][表 2/4 R2 路由内单调性] points="
            + report.samplePoints()
            + " inversions="
            + report.inversions()
            + " maxDrop="
            + report.maxDrop()
            + " strictlyIncreasing="
            + report.strictlyIncreasing()
            + " verdict="
            + report.verdict());
  }

  private static void printBounds(List<NearDupFusionImpactAnalyzer.Bound> bounds) {
    System.out.println("[QRT1][表 2/4 R3 位移上界] bound(w)=imageRouteWeight×(1−w)");
    for (NearDupFusionImpactAnalyzer.Bound bound : bounds) {
      System.out.println("band=" + bound.band() + " w=" + bound.w() + " bound=" + bound.bound());
    }
  }

  private static void printGapDistribution(
      List<NearDupFusionImpactAnalyzer.BandCandidate> bandCandidates,
      List<NearDupFusionImpactAnalyzer.CompetitorObservation> observations,
      NearDupFusionImpactAnalyzer.GapDistribution sameDoc,
      NearDupFusionImpactAnalyzer.GapDistribution crossDoc) {
    System.out.println("[QRT1][表 3/4 R4 基线档 gap 分布（必要非充分，不是召回率）]");
    System.out.println(
        "bandCandidates="
            + bandCandidates.size()
            + " competitorObservations="
            + observations.size());
    for (NearDupFusionImpactAnalyzer.CompetitorObservation observation : observations) {
      System.out.println(
          "obs base="
              + observation.baseKey()
              + " competitor="
              + observation.competitorKey()
              + " sameDoc="
              + observation.sameDoc()
              + " candidateCosine="
              + observation.candidateCosine()
              + " competitorCosine="
              + observation.competitorCosine()
              + " gap="
              + NearDupFusionImpactAnalyzer.round6(observation.gap()));
    }
    System.out.println(
        "sameDoc: obs="
            + sameDoc.observations()
            + " possibleByW="
            + sameDoc.displacementPossibleByW()
            + " p50="
            + sameDoc.p50()
            + " p90="
            + sameDoc.p90()
            + " p99="
            + sameDoc.p99()
            + " maxGap="
            + sameDoc.maxGap()
            + " maxFusedGap="
            + sameDoc.maxFusedGap());
    System.out.println(
        "crossDoc: obs="
            + crossDoc.observations()
            + " possibleByW="
            + crossDoc.displacementPossibleByW()
            + " p50="
            + crossDoc.p50()
            + " p90="
            + crossDoc.p90()
            + " p99="
            + crossDoc.p99()
            + " maxGap="
            + crossDoc.maxGap()
            + " maxFusedGap="
            + crossDoc.maxFusedGap());
  }

  private static void printCriteria(
      String verdict, NearDupFusionImpactAnalyzer.MonotonicityReport monotonicity) {
    System.out.println("[QRT1][表 4/4 逐判据照判] verdict=" + verdict);
    System.out.println("R1 公式核对：权重与链路顺序以源码行实测为证（表 1/4），非注释");
    System.out.println(
        "R2 路由内单调性：" + monotonicity.samplePoints() + " 点反序 " + monotonicity.inversions() + " 处");
    System.out.println("R3 位移上界：0.075/0.12/0.15 逐档常量断言（表 2/4）");
    System.out.println("R4 gap 分布：同文档/跨文档分列 + 分位/最大 + 必要非充分声明（表 3/4）");
    System.out.println(
        "R5 结论三选一：verdict=" + verdict + "（禁造第四种）；missingEvidence 已登记；externalCalls=0");
  }

  // ==================== 工具 ====================

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
        "R1",
        "公式核对：融合权重与累加语义必须以源码行为证（RetrievalConfigResolver:233、resolveTextRouteWeight/ImageRouteWeight"
            + " 的默认值）；若实测权重不是 0.7/0.3，按实测值重算并记录偏差，不得沿用注释。");
    criteria.put(
        "R2",
        "路由内单调性：对 [0.9887, 0.9944] 上 5000 点等距采样 + 三档边界点，断言 rank(score_gated) == rank(cosine)"
            + "（严格：同序，允许并列）；任一处反序即红（这说明 §1.1 推论 1 错了，必须上报而不是改判据）。");
    criteria.put(
        "R3", "位移上界：对 w ∈ {0.75,0.6,0.5} 逐档计算 0.3×(1−w)（0.075/0.12/0.15），并落成常量断言（融合权重改了这条会红）。");
    criteria.put(
        "R4",
        "图像路 gap 分布：从完整 24×24 矩阵算「同文档候选 vs 跨文档候选」的余弦 gap 分布（分位 + 最大），并换算成融合分 gap 上界"
            + "（0.3×Δcosine），给出「在多少比例的同文档候选上，位移在数学上可能」这一必要非充分结论；必须写明它不是召回率。");
    criteria.put(
        "R5",
        "结论三选一：VISIBLE-BOUNDED（速度上界 > 0 且可见条件已量化）/ INVISIBLE-IN-ROUTE-ONLY"
            + "（只能证明路由内不可见，跨路可见性缺文本路分数，无法定案）/ UNDECIDABLE-BY-DATA。禁造第四种，"
            + "且必须写明「仍需什么数据才可定案」。");
    return criteria;
  }
}
