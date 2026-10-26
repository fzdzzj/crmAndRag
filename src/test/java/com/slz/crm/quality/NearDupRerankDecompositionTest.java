package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 35 · near-dup-rerank-decomposition（腿 A：{@code r} 的分解本地复算；腿 B：0.90 决策页）。
 *
 * <p><b>零外呼</b>（R0）：输入只有卡 32/33/34 三张冻结锚；候选集由锚内 {@code corpusMaterial} 的 {@code imageRecords} /
 * {@code queryImageVectors} 重建，复算链全部是<b>生产实现</b>—— {@link InMemoryVectorStore#search} → {@link
 * ImageSimilarityGate#gate} → {@link DefaultWeightedReranker#rerank}（内含生产 {@link Bm25Scorer}）。不启动
 * Spring、不 instance provider、不调用任何模型、不设任何 {@code *_REAL}。
 *
 * <p><b>口径同源</b>（D2）：本类<b>不重写 min-max</b>。两个归一值由生产 {@code rerank} 自身反解：
 *
 * <ul>
 *   <li>空文本探针（候选文本全置空 ⇒ 生产 {@code Bm25Scorer} 给全 0 ⇒ bm25 分支 {@code max <= 0} 退化 ⇒ 归一值全 0）⇒ {@code
 *       r = wv · normVec}；
 *   <li>常数分探针（候选向量分全置 1.0 ⇒ {@code max == min} ⇒ 归一值全 1）⇒ {@code r = wv + wb · normBm25}。
 * </ul>
 *
 * <p>两次探针都直调生产 {@code rerank}，因此 {@code normVec} / {@code normBm25} 是生产归一化的输出，不是第二把尺子。
 *
 * <p><b>判据</b>（R1–R5，见 {@code openspec/changes/near-dup-rerank-decomposition/proposal.md}）：复算
 * {@code r} 与卡 32 锚 {@code fusedB − fusedA} 反算的 {@code r} 逐条比对（容差 1e-9）；退化分支按生产 min-max 的三分支 （最小值→0
 * / {@code max <= 0} → 全 0 / {@code max == min} → 全 1）分桶；结论三值且三值的实测可达路径写明；腿 B 只给两栏对照，不给倾向值、不改任何常量。
 *
 * <p><b>写模式</b> {@code -Dnear.dup.rerank.decomposition.write=true}；默认模式按本次全量重算<b>逐字节</b>比对 （JSON
 * 走树规范化并剔除顶层 {@code runtime}；md 走真字节）。
 */
class NearDupRerankDecompositionTest {

  private static final Path BAND_ENTRY =
      Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  private static final Path WINDOW_COUPLING =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.json");

  private static final Path OQ1_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-oq1-attribution-v1.json");

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-rerank-decomposition-v1.json");

  private static final Path ANCHOR_MD =
      Path.of("docs", "rag-quality", "near-dup-rerank-decomposition-v1.md");

  /** 首次生成开关：常规运行只做逐字节复算比对，绝不落盘。 */
  private static final boolean WRITE_MODE =
      Boolean.getBoolean("near.dup.rerank.decomposition.write");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 图像路由权重镜像（{@code RetrievalConfigResolver} 的 {@code DEFAULT_IMAGE_ROUTE_WEIGHT}；只做 δ → r 反算）。 */
  private static final double IMAGE_ROUTE_WEIGHT = 0.30d;

  /** 候选倍数（{@code RetrievalConfigResolver} 默认 4；本语料只有 6 条图像记录 ⇒ 上限不构成变量）。 */
  private static final int CANDIDATE_MULTIPLIER = 4;

  /** δ → r 反算的容差（R1）。 */
  private static final double RECONCILE_TOLERANCE = 1e-9d;

  /** 恒等式与探针的数值容差。 */
  private static final double IDENTITY_TOLERANCE = 1e-12d;

  /** 两次除法引入的 ±1ulp 归整阈值（值仍由生产归一化的输出决定）。 */
  private static final double SNAP_TOLERANCE = 1e-12d;

  private static final double DEFAULT_VECTOR_WEIGHT_LITERAL = 0.60d;

  private static final double DEFAULT_BM25_WEIGHT_LITERAL = 0.40d;

  private static final String NON_DEGENERATE = "NON-DEGENERATE";

  private static final String DEGENERATE_NOT_EVIDENCE = "DEGENERATE-NOT-EVIDENCE";

  private static final String PROVEN = "PROVEN";

  private static final String REFUTED = "REFUTED";

  private static final String UNDETERMINED = "UNDETERMINED";

  private static final String DOCUMENT_ID_PREFIX = "near-dup-band-";

  @Test
  @DisplayName("卡35：r = wv·normVec + wb·normBm25 零外呼分解（R1/R2/R3/R4）+ 0.90 决策页（R5）")
  void decomposesRerankScore() throws Exception {
    ObjectNode bandEntry = (ObjectNode) readJson(BAND_ENTRY);
    ObjectNode coupling = (ObjectNode) readJson(WINDOW_COUPLING);
    ObjectNode oq1 = (ObjectNode) readJson(OQ1_ANCHOR);
    assertInputs(bandEntry, coupling, oq1);

    Weights weights = measureWeights();
    List<QueryRoute> routes = reconstructRoutes(bandEntry, weights);
    assertReconstructionMatchesAnchor(bandEntry, routes);

    Reconciliation reconciliation = reconcileEvents(bandEntry, routes);
    String verdict = deriveVerdict(routes, reconciliation, weights);
    System.out.println(
        "[near-dup-rerank-decomposition] wv="
            + weights.vector()
            + " wb="
            + weights.bm25()
            + " 复算一致 "
            + reconciliation.consistentCount()
            + "/"
            + reconciliation.rows().size()
            + " verdict="
            + verdict);
    for (QueryRoute route : routes) {
      for (RouteRow row : route.rows()) {
        System.out.println(
            String.format(
                Locale.ROOT,
                "  q%d %-34s cos=%.15f nv=%.15f nb=%.15f r=%.15f bucket=%s",
                route.queryIndex(),
                row.variantKey(),
                row.cos(),
                row.normVec(),
                row.normBm25(),
                row.r(),
                row.bucket()));
      }
    }

    ObjectNode anchor = buildAnchor(bandEntry, oq1, weights, routes, reconciliation, verdict);
    writeOrCompareJson(ANCHOR_JSON, anchor);
    writeOrCompareBytes(ANCHOR_MD, buildAnchorMd(anchor).getBytes(StandardCharsets.UTF_8));
  }

  // ==================== 输入自检（R0：只读三张冻结锚，逐条对账） ====================

  private static void assertInputs(ObjectNode bandEntry, ObjectNode coupling, ObjectNode oq1) {
    assertEquals(
        "near-dup-band-entry-and-tier-fill",
        bandEntry.path("card").asText(),
        "卡 32 锚 card 不符（读错文件或锚被替换）");
    assertEquals("near-dup-window-coupling", coupling.path("card").asText(), "卡 33 锚 card 不符");
    assertEquals("near-dup-oq1-attribution", oq1.path("card").asText(), "卡 34 锚 card 不符");
    assertEquals(6, bandEntry.path("queries").size(), "卡 32 锚查询必须 6 条");
    assertEquals(
        30, bandEntry.path("candidates").size(), "卡 32 锚 candidates 必须 30 行（6 查询 × 5 返回源）");
    assertEquals(12, bandEntry.path("downWeightedCount").asInt(), "卡 32 锚 downWeightedCount 必须 12");
    assertEquals(
        12,
        bandEntry.path("windowCheck").path("entries").size(),
        "卡 32 锚 windowCheck.entries 必须 12 条（被标记的事件）");
    assertEquals(12, oq1.path("dataCheck").path("rByEvent").size(), "卡 34 锚 rByEvent 必须 12 条");
    assertEquals(6, oq1.path("attribution").path("consistencyCheck").size(), "卡 34 锚一致性检查必须 6 条");
    JsonNode summary = oq1.path("attribution").path("consistencySummary");
    assertEquals(3, summary.path("degenerate").asInt(), "卡 34 退化查询必须 3 条");
    assertEquals(3, summary.path("nonDegenerate").asInt(), "卡 34 非退化查询必须 3 条");
    JsonNode material = bandEntry.path("corpusMaterial");
    assertEquals(6, material.path("imageRecords").size(), "锚缺 imageRecords");
    assertEquals(6, material.path("textRecords").size(), "锚缺 textRecords");
    assertEquals(6, material.path("queryImageVectors").size(), "锚缺 queryImageVectors");
    assertEquals(12, material.path("embedCache").size(), "锚缺 embedCache");
    int zeroDelta = 0;
    for (JsonNode entry : bandEntry.path("windowCheck").path("entries")) {
      if (entry.path("delta").asDouble() == 0d) {
        zeroDelta++;
      }
    }
    assertEquals(4, zeroDelta, "零位移事件必须恰 4 条（卡 32 锚）");
  }

  // ==================== 权重实测：用生产 rerank 反解 wv / wb（不读私有常量） ====================

  /**
   * 两个权重<b>实测</b>而非抄字面：在卡 32 的生产配置矩阵下（该矩阵不覆盖两个权重键 ⇒ 回落 {@code DefaultWeightedReranker}
   * 默认字面）用两条受控探针反解，再用<b>改配置矩阵</b>的负对照证明探针确实在读生产 {@code resolveRatio}。
   */
  private static Weights measureWeights() {
    DefaultWeightedReranker reranker = rerankerFor(card32ConfigMatrix());
    double vectorWeight = probeVectorWeight(reranker);
    double bm25Weight = probeBm25Weight(reranker, vectorWeight);
    DefaultWeightedReranker overridden =
        rerankerFor(
            Map.of(
                "rag.retrieval.rerank.vector-weight", 0.90d,
                "rag.retrieval.rerank.bm25-weight", 0.10d));
    double overriddenVector = probeVectorWeight(overridden);
    double overriddenBm25 = probeBm25Weight(overridden, overriddenVector);
    assertEquals(0.90d, overriddenVector, SNAP_TOLERANCE, "负对照：探针必须读到配置矩阵里的 vector-weight");
    assertEquals(0.10d, overriddenBm25, SNAP_TOLERANCE, "负对照：探针必须读到配置矩阵里的 bm25-weight");
    assertFalse(
        card32ConfigMatrix().containsKey("rag.retrieval.rerank.vector-weight")
            || card32ConfigMatrix().containsKey("rag.retrieval.rerank.bm25-weight"),
        "卡 32 矩阵不得覆盖两个权重键（否则「回落默认」不成立）");
    return new Weights(
        vectorWeight, bm25Weight, overriddenVector, overriddenBm25, card32ConfigMatrix().keySet());
  }

  /** 空文本探针：bm25 分支全 0 ⇒ {@code r = wv · normVec}；取 normVec = 1 的探针行即 wv。 */
  private static double probeVectorWeight(DefaultWeightedReranker reranker) {
    List<RetrievalCandidate> probe = new ArrayList<>();
    probe.add(new RetrievalCandidate(hit("probe-min", 0.0d, ""), 0.0d));
    probe.add(new RetrievalCandidate(hit("probe-max", 1.0d, ""), 0.0d));
    List<RetrievalCandidate> reranked = reranker.rerank("probe", probe);
    assertEquals(0.0d, reranked.get(0).rerankScore(), IDENTITY_TOLERANCE, "空文本探针的末位必须为 0");
    return reranked.get(1).rerankScore();
  }

  /** 常数分探针：向量分支 {@code max == min} ⇒ 全 1 ⇒ {@code r = wv + wb · normBm25}。 */
  private static double probeBm25Weight(DefaultWeightedReranker reranker, double vectorWeight) {
    List<RetrievalCandidate> probe = new ArrayList<>();
    probe.add(new RetrievalCandidate(hit("probe-hit", 1.0d, "nist-800"), 0.0d));
    probe.add(new RetrievalCandidate(hit("probe-miss", 1.0d, ""), 0.0d));
    List<RetrievalCandidate> reranked = reranker.rerank("nist-800", probe);
    assertEquals(
        vectorWeight, reranked.get(1).rerankScore(), IDENTITY_TOLERANCE, "bm25 未命中的探针行必须恰为 wv");
    return reranked.get(0).rerankScore() - reranked.get(1).rerankScore();
  }

  private static DefaultWeightedReranker rerankerFor(Map<String, Object> matrix) {
    return new DefaultWeightedReranker(
        new Bm25Scorer(),
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(matrix)));
  }

  /** 卡 32 的生产配置矩阵（{@code NearDupBandEntryIT#config}：切片开 + 视觉路开 + 图片数帽 + 关查询改写）。 */
  private static Map<String, Object> card32ConfigMatrix() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put("pdf.image.slice.enabled", Boolean.TRUE);
    matrix.put("pdf.image.slice.image-vector.enabled", Boolean.TRUE);
    matrix.put("pdf.image.slice.max-images-per-pdf", 20);
    matrix.put("rag.retrieval.query-rewrite.enabled", Boolean.FALSE);
    return matrix;
  }

  // ==================== R1/R2 · 候选集重建 + 分解复算 ====================

  private static List<QueryRoute> reconstructRoutes(ObjectNode bandEntry, Weights weights) {
    JsonNode material = bandEntry.path("corpusMaterial");
    List<NearDupIngestS1IT.StoredRecord> imageRecords = new ArrayList<>();
    for (JsonNode node : material.path("imageRecords")) {
      imageRecords.add(NearDupIngestS1IT.StoredRecord.fromJson(node));
    }
    Map<Integer, float[]> queryVectors = new LinkedHashMap<>();
    for (JsonNode node : material.path("queryImageVectors")) {
      queryVectors.put(node.path("index").asInt(), decodeVector(node.path("vector").asText()));
    }
    Set<String> variantKeys = new LinkedHashSet<>();
    for (JsonNode node : bandEntry.path("variants")) {
      variantKeys.add(node.path("key").asText());
    }

    InMemoryVectorStore store = new InMemoryVectorStore();
    imageRecords.forEach(record -> store.upsert(record.toVectorRecord()));
    Map<String, Object> metadata = imageRecords.get(0).metadata();
    Map<String, Object> filter =
        Map.of(
            "knowledgeBaseId", String.valueOf(metadata.get("knowledgeBaseId")),
            "category", String.valueOf(metadata.get("category")));

    double threshold = ImageSimilarityGate.DEFAULT_THRESHOLD;
    double floor = ImageSimilarityGate.effectiveFloor(threshold);
    int topK = bandEntry.path("constants").path("topK").asInt();
    int candidateLimit = topK * CANDIDATE_MULTIPLIER;
    assertTrue(
        imageRecords.size() <= candidateLimit,
        "图像记录数 " + imageRecords.size() + " 超过召回上限 " + candidateLimit + "（上限不再是常量）");

    DefaultWeightedReranker reranker = rerankerFor(card32ConfigMatrix());
    List<QueryRoute> routes = new ArrayList<>();
    for (JsonNode query : bandEntry.path("queries")) {
      int queryIndex = query.path("index").asInt();
      String text = query.path("text").asText();
      float[] vector = queryVectors.get(queryIndex);
      assertTrue(vector != null, "缺 queryIndex=" + queryIndex + " 的查询图像向量");
      List<RetrievalCandidate> recalled =
          store.search(new VectorSearchRequest(vector, candidateLimit, floor, filter)).stream()
              .filter(result -> result.text() != null && !result.text().isBlank())
              .map(result -> new RetrievalCandidate(result, result.score()))
              .toList();
      List<RetrievalCandidate> gated = ImageSimilarityGate.gate(recalled, threshold);
      List<RetrievalCandidate> reranked = reranker.rerank(text, gated);
      List<RetrievalCandidate> vecProbe = reranker.rerank(text, blankText(gated));
      List<RetrievalCandidate> bm25Probe = reranker.rerank(text, constantScore(gated));
      assertEquals(gated.size(), reranked.size(), "重排必须保序且不增删: q" + queryIndex);

      List<String> vecValues = new ArrayList<>();
      List<String> bm25Values = new ArrayList<>();
      List<RouteRow> rows = new ArrayList<>();
      for (int index = 0; index < gated.size(); index++) {
        VectorSearchHit hit = gated.get(index).hit();
        double normVec = snap(vecProbe.get(index).rerankScore() / weights.vector());
        double normBm25 =
            snap((bm25Probe.get(index).rerankScore() - weights.vector()) / weights.bm25());
        vecValues.add(String.valueOf(normVec));
        bm25Values.add(String.valueOf(normBm25));
        String variantKey = variantKeyOf(hit.documentId());
        assertTrue(variantKeys.contains(variantKey), "文档不在锚 variants 内: " + hit.documentId());
        rows.add(
            new RouteRow(
                queryIndex,
                hit.chunkId(),
                variantKey,
                hit.score(),
                stringOf(hit.metadata().get(ImageSimilarityGate.TIER_METADATA_KEY)),
                stringOf(hit.metadata().get(ImageSimilarityGate.BAND_METADATA_KEY)),
                normVec,
                normBm25,
                reranked.get(index).rerankScore()));
      }
      boolean vecDegenerate = allEqual(vecValues);
      boolean bm25Degenerate = allEqual(bm25Values);
      String bucket = vecDegenerate || bm25Degenerate ? DEGENERATE_NOT_EVIDENCE : NON_DEGENERATE;
      List<RouteRow> tagged = new ArrayList<>(rows.size());
      for (RouteRow row : rows) {
        tagged.add(row.withBucket(bucket));
      }
      routes.add(
          new QueryRoute(
              queryIndex,
              query.path("form").asText(),
              text,
              tagged,
              vecDegenerate,
              bm25Degenerate,
              degenerateReason(vecValues),
              degenerateReason(bm25Values)));
    }
    return routes;
  }

  /**
   * 重建自检（禁抽样）：卡 32 锚 {@code candidates[]} 的 30 行里，{@code cos} 非空者即图像路成员，必须与本次重建逐行同身份、 同 cos、同
   * tier/band；{@code cos} 为空者是被文本路带回的同一切片行（不在图像路），必须<b>不</b>出现在本次重建里。
   */
  private static void assertReconstructionMatchesAnchor(
      ObjectNode bandEntry, List<QueryRoute> routes) {
    Map<String, RouteRow> rebuilt = new LinkedHashMap<>();
    for (QueryRoute route : routes) {
      for (RouteRow row : route.rows()) {
        rebuilt.put(route.queryIndex() + "|" + row.chunkId(), row);
      }
    }
    int inRoute = 0;
    int outsideRoute = 0;
    for (JsonNode candidate : bandEntry.path("candidates")) {
      int queryIndex = candidate.path("queryIndex").asInt();
      String sourceKey = candidate.path("chunkId").asText();
      RouteRow row = rebuilt.get(queryIndex + "|" + sourceKey.replace("IMAGE:", ""));
      JsonNode cos = candidate.path("cos");
      if (cos.isNull() || cos.isMissingNode()) {
        assertTrue(
            row == null, "锚内 cos 为空（未走图像路）的候选不得出现在本次重建的候选集里: q" + queryIndex + " " + sourceKey);
        outsideRoute++;
        continue;
      }
      assertTrue(row != null, "重建缺行（候选集不完整）: q" + queryIndex + " " + sourceKey);
      assertEquals(
          candidate.path("variantKey").asText(), row.variantKey(), "variantKey 不符: " + sourceKey);
      assertEquals(cos.asDouble(), row.cos(), IDENTITY_TOLERANCE, "cos 不符: " + sourceKey);
      assertEquals(nullableText(candidate.path("tier")), row.tier(), "tier 不符: " + sourceKey);
      assertEquals(nullableText(candidate.path("band")), row.band(), "band 不符: " + sourceKey);
      inRoute++;
    }
    assertEquals(30, inRoute + outsideRoute, "必须逐条核对卡 32 锚 30 行（禁抽样）");
    assertEquals(
        inRoute,
        routes.stream().mapToInt(route -> route.rows().size()).sum(),
        "图像路候选集必须恰为锚内 cos 非空的那批行（不多不少）");
    System.out.println(
        "[near-dup-rerank-decomposition] 候选集重建与卡 32 锚逐条一致：图像路 "
            + inRoute
            + "/"
            + inRoute
            + " 行、路外 "
            + outsideRoute
            + "/"
            + outsideRoute
            + " 行；routeListSize="
            + routes.stream().map(route -> route.rows().size()).toList());
  }

  // ==================== R1 · 与卡 32 反算 r 逐条比对 ====================

  private static Reconciliation reconcileEvents(ObjectNode bandEntry, List<QueryRoute> routes) {
    Map<String, RouteRow> byKey = new LinkedHashMap<>();
    for (QueryRoute route : routes) {
      for (RouteRow row : route.rows()) {
        byKey.put(route.queryIndex() + "|" + row.variantKey(), row);
      }
    }
    List<ReconcileRow> rows = new ArrayList<>();
    int consistent = 0;
    double maxAbsDiff = 0d;
    for (JsonNode entry : bandEntry.path("windowCheck").path("entries")) {
      int queryIndex = entry.path("queryIndex").asInt();
      String variantKey = entry.path("variantKey").asText();
      double weight = entry.path("weight").asDouble();
      double fusedA = entry.path("fusedA").asDouble();
      double fusedB = entry.path("fusedB").asDouble();
      double delta = entry.path("delta").asDouble();
      assertEquals(
          fusedB - fusedA, delta, IDENTITY_TOLERANCE, "δ 必须等于 fusedB − fusedA: " + variantKey);
      assertEquals(
          IMAGE_ROUTE_WEIGHT * (1d - weight),
          entry.path("window").asDouble(),
          IDENTITY_TOLERANCE,
          "锚 window 必须仍等于 0.30 × (1 − w): " + variantKey);
      RouteRow row = byKey.get(queryIndex + "|" + variantKey);
      assertTrue(row != null, "12 条事件必须能定位到重建候选: q" + queryIndex + " " + variantKey);
      double deltaDerivedR = delta / (IMAGE_ROUTE_WEIGHT * (1d - weight));
      double absDiff = Math.abs(deltaDerivedR - row.r());
      maxAbsDiff = Math.max(maxAbsDiff, absDiff);
      boolean ok = absDiff <= RECONCILE_TOLERANCE;
      consistent += ok ? 1 : 0;
      assertTrue(
          ok,
          "复算 r 与卡 32 反算 r 必须一致（容差 1e-9）: q"
              + queryIndex
              + " "
              + variantKey
              + " deltaDerived="
              + deltaDerivedR
              + " recomputed="
              + row.r());
      rows.add(
          new ReconcileRow(
              queryIndex, variantKey, row.chunkId(), weight, deltaDerivedR, row.r(), absDiff, ok));
    }
    assertEquals(12, rows.size(), "被标记事件必须 12 条");
    assertEquals(4, zeroRows(routes).size(), "零位移事件必须 4 条（r = 0）");
    return new Reconciliation(rows, consistent, maxAbsDiff);
  }

  // ==================== R3 · 三值判定（机械规则，与 R3 逐字对应） ====================

  private static String deriveVerdict(
      List<QueryRoute> routes, Reconciliation reconciliation, Weights weights) {
    // R1/R2 的恒真部分：无论结论落哪个值都必须逐条成立（放宽即判死）。
    for (QueryRoute route : routes) {
      for (RouteRow row : route.rows()) {
        assertEquals(
            row.r(),
            row.normVec() * weights.vector() + row.normBm25() * weights.bm25(),
            IDENTITY_TOLERANCE,
            "r = wv·normVec + wb·normBm25 必须逐条成立: q" + route.queryIndex() + " " + row.variantKey());
        assertEquals(
            row.r() == 0d,
            row.normVec() == 0d && row.normBm25() == 0d,
            "r = 0 ⟺ (normVec = 0 ∧ normBm25 = 0) 必须逐条成立: q"
                + route.queryIndex()
                + " "
                + row.variantKey());
        assertEquals(
            NON_DEGENERATE.equals(row.bucket()),
            !route.vecDegenerate() && !route.bm25Degenerate(),
            "bucket 必须与两分支退化标志一致: q" + route.queryIndex());
      }
    }
    List<RouteRow> zeroRows = zeroRows(routes);
    assertEquals(4, zeroRows.size(), "零位移事件必须 4 条");
    // 结构不变式（数据层面，与判据无关也必须成立）：r = 0 要求 wb·normBm25 = 0 ⇒ 零位移只可能出现在 bm25 分支全 0 的查询里；
    // 且向量分支不得退化，否则「末位」同样不可分辨。
    for (RouteRow row : zeroRows) {
      QueryRoute route = routeOf(routes, row.queryIndex());
      assertFalse(route.vecDegenerate(), "零位移事件的向量分支不得退化（否则末位不可分辨）: q" + row.queryIndex());
      assertEquals(
          "全同 0.0，max <= 0",
          route.bm25Reason(),
          "零位移事件的 bm25 分支必须全 0（否则 wb 项 ≥ wb·min > 0，r 不可能为 0）: q" + row.queryIndex());
    }
    boolean consistent = reconciliation.consistentCount() == reconciliation.rows().size();
    boolean bothMinimum =
        zeroRows.stream().allMatch(row -> row.normVec() == 0d && row.normBm25() == 0d);
    boolean nonDegenerate = zeroRows.stream().allMatch(row -> NON_DEGENERATE.equals(row.bucket()));
    if (!consistent) {
      return UNDETERMINED;
    }
    if (!bothMinimum) {
      return REFUTED;
    }
    return nonDegenerate ? PROVEN : UNDETERMINED;
  }

  private static List<RouteRow> zeroRows(List<QueryRoute> routes) {
    List<RouteRow> rows = new ArrayList<>();
    for (QueryRoute route : routes) {
      for (RouteRow row : route.rows()) {
        if (row.marked() && row.r() == 0d) {
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private static QueryRoute routeOf(List<QueryRoute> routes, int queryIndex) {
    return routes.stream()
        .filter(route -> route.queryIndex() == queryIndex)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("缺查询: q" + queryIndex));
  }

  // ==================== 锚 ====================

  private static ObjectNode buildAnchor(
      ObjectNode bandEntry,
      ObjectNode oq1,
      Weights weights,
      List<QueryRoute> routes,
      Reconciliation reconciliation,
      String verdict)
      throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-rerank-decomposition");
    root.put("stage", "RERANK-DECOMPOSITION");
    root.put("decisionVersion", "NEAR-DUP-RERANK-DECOMPOSITION-1.0");
    root.put("decisionOwner", "OWNER");
    root.put(
        "scope",
        "腿 A：把 r = wv·normVec + wb·normBm25 在本地拆开（零外呼）；腿 B：NEAR_DUPLICATE_FLOOR 0.90 两栏决策页（不给倾向值）");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put("R0", "零外呼优先：腿 A 先本地复算，全卡不发起任何模型/检索外呼、不设任何 *_REAL；若最终落 UNDETERMINED 只交付埋点方案、不执行");
    criteria.put(
        "R1",
        "r 分解复算：用锚内冻结 corpusMaterial 重建图像路候选集，按生产口径（生产 Bm25Scorer + 生产 DefaultWeightedReranker 的 min-max，本类不重写 min-max）复算 normVec / normBm25 / r，与卡 32 锚 candidates[].fusedB − fusedA 反算的 r 逐条比对（容差 1e-9）");
    criteria.put(
        "R2",
        "两分支末位判定（分桶）：逐条给 (queryIndex, variantKey, normVec, normBm25, r, isVecMin, isBm25Min, bucket)；断言 r = 0 ⟺ (normVec = 0 ∧ normBm25 = 0)；退化分支（生产 min-max 三分支：max <= 0 或 max == min）里的 r = 0 只记 DEGENERATE-NOT-EVIDENCE");
    criteria.put(
        "R3",
        "结论三值：PROVEN = 复算 12/12 一致 ∧ 4 条零位移事件两分支同时为最小值 ∧ 全部落在两分支皆非退化的查询；REFUTED = 复算一致但零位移事件不是两分支末位；UNDETERMINED = 复算与锚对不上，或零位移事件只落在退化分支（按 R2 不构成末位证据）——三值可达路径见 verdictReachability");
    criteria.put("R4", "埋点方案仅在 UNDETERMINED 时交付，且必须比卡 34 的更窄（逐项写本地卡在哪一项），executed = false");
    criteria.put(
        "R5",
        "腿 B 零外呼决策页：维持 0.9887 / 降到 0.90 两栏对照（进带对数与分母、已记录代价、结构性惰性提示、待 Owner 决策项）；不给倾向值、不改任何常量");
    criteria.put(
        "R6", "锚 JSON 纯 LF + md ≤40 行；写模式 -Dnear.dup.rerank.decomposition.write=true，默认逐字节复算");
    criteria.put(
        "R7", "src/main / pom.xml / .github 零 diff；卡 32/33/34 三锚零字节变动；11 个禁改 NearDup* 类只读");
    criteria.put("R8", "停手：本地复算若需跑检索 ⇒ 停手；口径与生产不同源（自行重写 min-max）⇒ 不合格");

    ObjectNode upstream = root.putObject("upstreamAnchors");
    putAnchorRef(upstream, "bandEntry", BAND_ENTRY);
    putAnchorRef(upstream, "windowCoupling", WINDOW_COUPLING);
    putAnchorRef(upstream, "oq1Attribution", OQ1_ANCHOR);

    ObjectNode weightsNode = root.putObject("weights");
    weightsNode.put("vectorWeight", weights.vector());
    weightsNode.put("bm25Weight", weights.bm25());
    weightsNode.put("overriddenVector", weights.overriddenVector());
    weightsNode.put("overriddenBm25", weights.overriddenBm25());
    weightsNode.put("vectorWeightDefaultLiteral", DEFAULT_VECTOR_WEIGHT_LITERAL);
    weightsNode.put("bm25WeightDefaultLiteral", DEFAULT_BM25_WEIGHT_LITERAL);
    weightsNode.put(
        "keys",
        "rag.retrieval.rerank.vector-weight / rag.retrieval.rerank.bm25-weight（生产 DefaultWeightedReranker#resolveRatio）");
    weightsNode.put(
        "measuredBy",
        "两条受控探针直调生产 DefaultWeightedReranker#rerank：① 候选文本全置空 ⇒ 生产 Bm25Scorer 全 0 ⇒ bm25 分支 max <= 0 退化、归一值全 0 ⇒ r = wv·normVec，取 normVec = 1 的行即 wv；② 候选向量分全置 1.0 ⇒ max == min、归一值全 1 ⇒ r = wv + wb·normBm25，取 bm25 命中 / 未命中两行之差即 wb。探针里没有任何手算归一化");
    weightsNode.put(
        "negativeControl",
        "同一探针在配置矩阵写入 vector-weight=0.90 / bm25-weight=0.10 时给出 "
            + weights.overriddenVector()
            + " / "
            + weights.overriddenBm25()
            + " ⇒ 探针确实在读生产 resolveRatio，而非硬编码字面");
    ArrayNode matrixKeys = weightsNode.putArray("card32ConfigMatrixKeys");
    weights.configMatrixKeys().forEach(key -> matrixKeys.add(String.valueOf(key)));
    weightsNode.put(
        "effectiveBecause",
        "卡 32 生产配置矩阵不覆盖这两个权重键 ⇒ 生产 resolveRatio 回落 DefaultWeightedReranker 默认字面；实测值与字面一致");
    weightsNode.put(
        "crossCheck", "用该两权重把图像路全部候选行重新合成 normVec·wv + normBm25·wb，全部与生产 rerank 的输出同值（1e-12）");

    ObjectNode reconstruction = root.putObject("reconstruction");
    reconstruction.put(
        "method",
        "锚内 corpusMaterial 的 imageRecords → 生产 InMemoryVectorStore#upsert / #search（余弦 + minScore 过滤 + topK 截断）→ 生产 ImageSimilarityGate#gate（阈值 0.9944，生产准入下限 effectiveFloor = 0.982）→ 生产 DefaultWeightedReranker#rerank（内含生产 Bm25Scorer，min-max 归一 + 权重）");
    reconstruction.put(
        "notRun",
        "未启动 Spring、未装配 KnowledgeRetrievalServiceImpl、未 instance provider、未调用任何模型、未设任何 *_REAL；查询改写按卡 32 矩阵关闭 ⇒ 重排与 bm25 用的查询文本 = 锚内 queries[].text 原文");
    ObjectNode config = reconstruction.putObject("config");
    config.put("imageSimilarityThreshold", ImageSimilarityGate.DEFAULT_THRESHOLD);
    config.put(
        "effectiveFloor",
        ImageSimilarityGate.effectiveFloor(ImageSimilarityGate.DEFAULT_THRESHOLD));
    int topK = bandEntry.path("constants").path("topK").asInt();
    config.put("topK", topK);
    config.put("candidateMultiplier", CANDIDATE_MULTIPLIER);
    config.put("candidateLimit", topK * CANDIDATE_MULTIPLIER);
    config.put("queryRewriteEnabled", false);
    config.put("imageRouteWeight", IMAGE_ROUTE_WEIGHT);
    ObjectNode corpus = reconstruction.putObject("corpusMaterialCounts");
    corpus.put("imageRecords", bandEntry.path("corpusMaterial").path("imageRecords").size());
    corpus.put("textRecords", bandEntry.path("corpusMaterial").path("textRecords").size());
    corpus.put(
        "queryImageVectors", bandEntry.path("corpusMaterial").path("queryImageVectors").size());
    corpus.put("embedCache", bandEntry.path("corpusMaterial").path("embedCache").size());
    reconstruction.put("externalCalls", 0);
    ArrayNode routeSizes = reconstruction.putArray("routeListSizePerQuery");
    routes.forEach(route -> routeSizes.add(route.rows().size()));
    reconstruction.put(
        "reconstructionCheck",
        "锚内 30 行里 cos 非空者（图像路成员）与本次重建逐行同身份（queryIndex + chunkId + variantKey + cos + tier + band，禁抽样）；cos 空者（被文本路带回的同一切片行）确认不在本次重建里；图像路行数与重建行数相等（不多不少）");

    ArrayNode queriesNode = root.putArray("queries");
    Set<String> degenerateQueries = new LinkedHashSet<>();
    int card34Degenerate = 0;
    for (QueryRoute route : routes) {
      ObjectNode node = queriesNode.addObject();
      node.put("queryIndex", route.queryIndex());
      node.put("form", route.form());
      node.put("text", route.text());
      node.put("routeListSize", route.rows().size());
      long marked = route.rows().stream().filter(RouteRow::marked).count();
      node.put("markedCandidateCount", marked);
      node.put("card34Degenerate", marked == 1L);
      node.put("bucket", route.bucket());
      if (marked == 1L) {
        card34Degenerate++;
      }
      if (!NON_DEGENERATE.equals(route.bucket())) {
        degenerateQueries.add("q" + route.queryIndex());
      }
      ObjectNode vecBranch = node.putObject("vecBranch");
      vecBranch.put("degenerate", route.vecDegenerate());
      vecBranch.put("reason", route.vecReason());
      ObjectNode bm25Branch = node.putObject("bm25Branch");
      bm25Branch.put("degenerate", route.bm25Degenerate());
      bm25Branch.put("reason", route.bm25Reason());
    }
    ObjectNode crossTab = root.putObject("card34CrossTab");
    crossTab.put(
        "note",
        "卡 34 的 degenerate 口径 = 该查询被标记候选数 == 1（次序检查恒真），与本卡的生产 min-max 退化口径不是同一把尺子；两把尺子分开列，禁合并分桶");
    crossTab.put("card34Degenerate", card34Degenerate);
    crossTab.put("card34NonDegenerate", routes.size() - card34Degenerate);
    crossTab.put("normalizerDegenerate", degenerateQueries.size());

    ArrayNode routeArray = root.putArray("routes");
    for (QueryRoute route : routes) {
      for (RouteRow row : route.rows()) {
        ObjectNode node = routeArray.addObject();
        node.put("queryIndex", route.queryIndex());
        node.put("chunkId", row.chunkId());
        node.put("sourceKey", "IMAGE:" + row.chunkId());
        node.put("variantKey", row.variantKey());
        node.put("cos", row.cos());
        node.put("tier", row.tier());
        node.put("band", row.band());
        node.put("marked", row.marked());
        node.put("normVec", row.normVec());
        node.put("normBm25", row.normBm25());
        node.put("r", row.r());
        node.put("isVecMin", row.normVec() == 0d);
        node.put("isBm25Min", row.normBm25() == 0d);
        node.put("bucket", row.bucket());
      }
    }

    ArrayNode reconcileArray = root.putArray("reconciliation");
    for (ReconcileRow row : reconciliation.rows()) {
      ObjectNode node = reconcileArray.addObject();
      node.put("queryIndex", row.queryIndex());
      node.put("variantKey", row.variantKey());
      node.put("chunkId", row.chunkId());
      node.put("w", row.w());
      node.put("deltaDerivedR", row.deltaDerivedR());
      node.put("recomputedR", row.recomputedR());
      node.put("absDiff", row.absDiff());
      node.put("consistent", row.consistent());
    }
    ObjectNode reconSummary = root.putObject("reconciliationSummary");
    reconSummary.put("total", reconciliation.rows().size());
    reconSummary.put("consistent", reconciliation.consistentCount());
    reconSummary.put("tolerance", RECONCILE_TOLERANCE);
    reconSummary.put("maxAbsDiff", reconciliation.maxAbsDiff());
    reconSummary.put(
        "formula", "r = δ / (0.30 × (1 − w))，δ = fusedB − fusedA（卡 32/34 已证恒等式；本卡只做数值比对，不重推）");

    ArrayNode zeroArray = root.putArray("zeroDisplacementEvents");
    for (RouteRow row : zeroRows(routes)) {
      ObjectNode node = zeroArray.addObject();
      node.put("queryIndex", row.queryIndex());
      node.put("variantKey", row.variantKey());
      node.put("chunkId", row.chunkId());
      node.put("cos", row.cos());
      node.put("normVec", row.normVec());
      node.put("normBm25", row.normBm25());
      node.put("r", row.r());
      node.put("isVecMin", row.normVec() == 0d);
      node.put("isBm25Min", row.normBm25() == 0d);
      node.put("bucket", row.bucket());
      node.put("marked", row.marked());
    }

    ObjectNode equivalence = root.putObject("equivalence");
    equivalence.put("identity", "r = wv · normVec + wb · normBm25");
    equivalence.put("checked", routes.stream().mapToInt(route -> route.rows().size()).sum());
    equivalence.put("violations", 0);
    equivalence.put(
        "zeroEquivalence", "r = 0 ⟺ (normVec = 0 ∧ normBm25 = 0)，需 wv > 0 ∧ wb > 0（本卡实测两权重均为正）");
    equivalence.put(
        "zeroEquivalenceChecked", routes.stream().mapToInt(route -> route.rows().size()).sum());
    equivalence.put("violationsZeroEquivalence", 0);

    ObjectNode bucketSummary = root.putObject("bucketSummary");
    bucketSummary.put("total", routes.stream().mapToInt(route -> route.rows().size()).sum());
    bucketSummary.put(
        "nonDegenerate",
        routes.stream()
            .filter(route -> NON_DEGENERATE.equals(route.bucket()))
            .mapToInt(route -> route.rows().size())
            .sum());
    bucketSummary.put(
        "degenerateNotEvidence",
        routes.stream()
            .filter(route -> !NON_DEGENERATE.equals(route.bucket()))
            .mapToInt(route -> route.rows().size())
            .sum());
    bucketSummary.put(
        "degenerateQueries",
        (degenerateQueries.isEmpty() ? "无" : String.join(",", degenerateQueries))
            + "（"
            + degenerateQueries.size()
            + "/6 条查询至少一个分支退化）");
    bucketSummary.put(
        "bucketRule",
        "分支退化 ⟺ 该分支归一值全同（生产 min-max 三分支里 max <= 0 → 全 0；max == min → 全 1）；查询退化 ⟺ 任一分支退化。退化分支里末位不适用 ⇒ r = 0 只记 DEGENERATE-NOT-EVIDENCE");

    root.put("verdict", verdict);
    root.put("verdictReason", verdictReason(routes, reconciliation, verdict));
    root.put("structuralFinding", structuralFinding(routes, weights));
    root.put("verdictRule", verdictRule());
    ObjectNode reachability = root.putObject("verdictReachability");
    reachability.put(
        "PROVEN",
        "触发条件（三条全满足才可达）：复算 r 与卡 32 反算 r 逐条 1e-9 内一致（12/12）∧ 4 条零位移事件的 normVec 与 normBm25 同时为 0.0 ∧ 这 4 条所在查询的向量分支与 bm25 分支都不退化（bucket = NON-DEGENERATE）");
    reachability.put(
        "REFUTED",
        "触发条件：复算 12/12 一致，但至少 1 条零位移事件在某分支上不取最小值（r = 0 却 normVec > 0 或 normBm25 > 0，即生产 min-max 末位不是成因）——本卡实测未出现该分支");
    reachability.put(
        "UNDETERMINED",
        "触发条件：① 复算 r 与锚反算对不上（> 1e-9）或候选集重建不全（卡 32 锚 30 行有任一行无法重建）；② 复算一致且两分支都是最小值，但零位移事件落在退化分支的查询里（退化分支里末位不适用，r = 0 不构成末位证据，R3 的 PROVEN 条件不成立）；③ 权重 / 文本 / 向量任一不足以完成生产口径复算。本卡实测触发的是 ②，见 verdictReason");

    root.put("verdictCaliberNote", caliberNote(degenerateQueries));

    ObjectNode plan = root.putObject("instrumentedPlan");
    plan.put("required", !PROVEN.equals(verdict));
    plan.put("executed", false);
    plan.put(
        "narrowerThanCard34",
        "卡 34 的方案要 30 外呼换 4 个字段（r / routeListSize / bm25 归一值 / 融合图像贡献）。本卡已在本地算出前 3 项（r 由生产 rerank 直接给、routeListSize 逐查询实测、两分支归一值由探针反解），第 4 项也由 δ 反算；因此外呼预算从 30 降到 0——剩下的任何核对都只需读同一份 corpusMaterial 做本地 replay");
    plan.put("localBlocker", localBlocker(routes, verdict));
    plan.put("wouldInstrumentationHelp", false);
    plan.put(
        "whyNotHelp",
        "退化来自数据而非缺字段："
            + degenerateSummary(routes)
            + "。埋点能拿到的是运行期同一批候选与同一批归一值，而 bm25 分支全同是「查询词与图像描述文本无（或全）匹配」的必然结果 ⇒ 快照只会复现本次复算的数字，无法把 DEGENERATE-NOT-EVIDENCE 变成末位证据");
    plan.put(
        "minimalFields",
        "若仅想验证「本地重建与生产链逐字节同源」：在 DefaultWeightedReranker#rerank 出口埋 1 个字段 rerankRouteList（queryIndex + 有序候选 chunkId + hit.score() + normalizedVec + normalizedBm25），不新增外呼；该字段不能提升本条 verdict");
    ArrayNode unlock = plan.putArray("unlockPathsIfOwnerWantsProven");
    unlock.add("换语料/查询：选能让 bm25 在图像描述文本上分化的查询（即查询词命中部分候选、不命中另一部分），使 bm25 分支非退化——属另卡语料设计，不在本卡范围");
    unlock.add("改判据：若 Owner 愿意接受「单分支末位 + 另一分支退化且全体并列最小」也算证据，需在另卡正式修订 R2/R3 口径；本卡不擅自改");
    plan.put("budgetExternalCalls", 0);
    plan.put(
        "note", "本方案未执行、不得据其声明结论（R4/D5）；executed = false；claim 层面本卡只主张「向量分支末位成立、bm25 分支退化，故不升格」");

    buildFloorOptions(root, bandEntry);
    root.put(
        "nonExtrapolationNote",
        "本卡只回答「r 为什么是 0」与本语料（6 查询 / 6 图 / 12 条被标记事件）的 floor 两栏对照；不外推到其它语料、其它 topK、其它权重配置");

    ObjectNode runtime = root.putObject("runtime");
    runtime.put("generatedBy", "NearDupRerankDecompositionTest");
    runtime.put("javaVersion", System.getProperty("java.version"));
    runtime.put("os", System.getProperty("os.name"));
    return root;
  }

  private static String verdictReason(
      List<QueryRoute> routes, Reconciliation reconciliation, String verdict) {
    List<RouteRow> zeroRows = zeroRows(routes);
    long bothMin =
        zeroRows.stream().filter(row -> row.normVec() == 0d && row.normBm25() == 0d).count();
    long nonDegenerate =
        zeroRows.stream().filter(row -> NON_DEGENERATE.equals(row.bucket())).count();
    Set<String> zeroQueries = new LinkedHashSet<>();
    zeroRows.forEach(row -> zeroQueries.add("q" + row.queryIndex()));
    String head =
        "复算 r 与卡 32 反算 r "
            + reconciliation.consistentCount()
            + "/"
            + reconciliation.rows().size()
            + " 条一致（容差 1e-9，maxAbsDiff = "
            + reconciliation.maxAbsDiff()
            + "）；4 条零位移事件里 "
            + bothMin
            + "/4 条两分支归一值同时为 0.0（向量分支全部为末位），"
            + nonDegenerate
            + "/4 条落在两分支皆非退化的查询（零位移事件所在查询："
            + String.join(",", zeroQueries)
            + "）";
    if (PROVEN.equals(verdict)) {
      return head
          + " ⇒ R3 的 PROVEN 三个条件全部满足：复算一致、两分支同时末位、且全在非退化查询。结论：r = 0 不是巧合，是生产 DefaultWeightedReranker#normalizeScores 的 min-max 把两条分支的末位都归成 0.0 的直接结果（卡 34 停在「相容未证」的成因由此升到 PROVEN）";
    }
    if (REFUTED.equals(verdict)) {
      return head + " ⇒ 复算与锚一致，但零位移事件并非两分支末位 ⇒「min-max 末位」不是 r = 0 的成因（R3 = REFUTED）";
    }
    return head
        + " ⇒ 向量分支的末位是实测成立的（4/4 的 normVec = 0.0 且确为最小 cos），但 bm25 分支在 "
        + degenerateSummary(routes)
        + " 上退化：该分支全体归一值相同（全 0.0 或全 1.0），所有候选并列最小/并列最大，「末位」在该分支里不可分辨 ⇒ 按 R2/D3 只能记 DEGENERATE-NOT-EVIDENCE，R3 的 PROVEN 条件（4/4 落在两分支皆非退化的查询）不成立，故落 UNDETERMINED（不升格、不改 R3 迁就数据）";
  }

  /**
   * 结构发现（数据层面，不动 R2/R3）：{@code r = 0} 要求 {@code wv·normVec + wb·normBm25 = 0}，两权重皆正 ⇒ 必须 {@code
   * wb·normBm25 = 0}。本语料 bm25 分支在每条查询上都是同一常数，因此「r 为什么是 0」的可观测答案只剩向量分支的末位。
   */
  private static String structuralFinding(List<QueryRoute> routes, Weights weights) {
    List<String> allOne = new ArrayList<>();
    List<String> allZero = new ArrayList<>();
    for (QueryRoute route : routes) {
      if (!route.bm25Degenerate()) {
        continue;
      }
      if (route.bm25Reason().startsWith("全同 1.0")) {
        allOne.add("q" + route.queryIndex());
      } else {
        allZero.add("q" + route.queryIndex());
      }
    }
    Set<String> zeroQueries = new LinkedHashSet<>();
    zeroRows(routes).forEach(row -> zeroQueries.add("q" + row.queryIndex()));
    return "r = 0 要求 wv·normVec + wb·normBm25 = 0，两权重皆正 ⇒ 必须 wb·normBm25 = 0。本语料 6 条查询的 bm25 分支全部退化："
        + (allOne.isEmpty() ? "" : String.join("/", allOne) + " 全同 1.0（查询词命中全部图像描述文本 ⇒ max == min）")
        + (allOne.isEmpty() || allZero.isEmpty() ? "" : "；")
        + (allZero.isEmpty() ? "" : String.join("/", allZero) + " 全同 0.0（查询词零命中 ⇒ max <= 0）")
        + "。在 bm25 全 1.0 的查询里 r ≥ wb·1 = "
        + weights.bm25()
        + " > 0，零位移不可能出现；4 条零位移事件（"
        + String.join(",", zeroQueries)
        + "）恰好全部落在 bm25 全 0 的查询里。这些查询里 bm25 项恒为 0，故 r = 0 的可观测成因只剩「向量分支末位」，而它在 4/4 条上实测成立（normVec = 0.0 且该候选的 cos 为其 route 内最小）。受 R2/R3 约束（PROVEN 要求两分支皆非退化）本条仍记 UNDETERMINED——这不是缺证据，而是本语料的 bm25 分支在图像描述文本上不是连续量（要么全命中、要么零命中），使「末位」在那一支不可分辨";
  }

  private static String verdictRule() {
    return "机械规则（与 R3 逐字对应）：if 复算不一致 ⇒ UNDETERMINED；else if 至少 1 条零位移事件非两分支同时最小 ⇒ REFUTED；else if 4/4 零位移事件所在查询两分支皆非退化 ⇒ PROVEN；else ⇒ UNDETERMINED（三值封闭，无第四值，不回改判据）";
  }

  private static String caliberNote(Set<String> degenerateQueries) {
    return "分桶口径说明：R2 的「退化」指生产 normalizeScores 的三分支退化（max <= 0 → 全 0；max == min → 全 1），不是卡 34 consistencySummary 的「被标记候选数 == 1」。本卡实测两把尺子不重合（生产退化查询："
        + (degenerateQueries.isEmpty() ? "无" : String.join(",", degenerateQueries))
        + "），故分开列、不合并；合并会把退化分支里的 r = 0 当末位证据";
  }

  /** 退化查询的紧凑摘要：按退化分支的归一值分组，如 {@code q1/q3/q4/q5（bm25 全 0.0）、q0/q2（bm25 全 1.0）}。 */
  private static String degenerateSummary(List<QueryRoute> routes) {
    Map<String, List<String>> byBranchValue = new LinkedHashMap<>();
    for (QueryRoute route : routes) {
      if (route.vecDegenerate()) {
        byBranchValue
            .computeIfAbsent("向量分支 " + route.vecReason(), key -> new ArrayList<>())
            .add("q" + route.queryIndex());
      }
      if (route.bm25Degenerate()) {
        byBranchValue
            .computeIfAbsent("bm25 分支 " + route.bm25Reason(), key -> new ArrayList<>())
            .add("q" + route.queryIndex());
      }
    }
    List<String> parts = new ArrayList<>();
    byBranchValue.forEach(
        (branch, queries) -> parts.add(String.join("/", queries) + "（" + branch + "）"));
    return parts.isEmpty() ? "无查询" : String.join("、", parts);
  }

  private static String localBlocker(List<QueryRoute> routes, String verdict) {
    if (PROVEN.equals(verdict)) {
      return "无（本卡已判 PROVEN，无需埋点）";
    }
    return "本地能算的都算了，卡住的不是「缺字段」而是「数据本身让该分支不可分辨」："
        + degenerateSummary(routes)
        + " 的 bm25 分支归一值全同 ⇒ 该分支里所有候选并列同一个归一值，「末位」不可分辨。运行期快照只会复现本次复算的同一批数字（同一份 corpusMaterial、同一套生产实现），因此再加埋点也改不了本条判定";
  }

  // ==================== 腿 B · 0.90 决策页（零外呼，两栏并列，不给倾向值） ====================

  private static void buildFloorOptions(ObjectNode root, ObjectNode bandEntry) {
    JsonNode thresholdOptions = bandEntry.path("legB").path("thresholdOptions");
    JsonNode options = thresholdOptions.path("options");
    JsonNode entrants = thresholdOptions.path("newEntrantsFromMantleTo090");
    JsonNode reference = thresholdOptions.path("currentProductionReference");
    assertEquals(2, options.size(), "卡 32 腿 B 必须两栏（0.9887 / 0.90）");
    JsonNode keep = options.get(0);
    JsonNode lower = options.get(1);
    assertEquals(0.9887d, keep.path("floor").asDouble(), 0d, "第一栏必须是 0.9887");
    assertEquals(0.90d, lower.path("floor").asDouble(), 0d, "第二栏必须是 0.90");

    ObjectNode floorOptions = root.putObject("floorOptions");
    floorOptions.put(
        "issue",
        "NEAR_DUPLICATE_FLOOR 的 0.90 议题：两栏并列对照，只列已记录的数字与代价，不给倾向值、不排序、不改任何常量（变更属另卡，须 Owner 拍板）");
    floorOptions.put(
        "caliber", "两栏按 R5 原文（维持 0.9887 / 降到 0.90）照列；现行生产 floor 另落 currentProductionReference，非选项");
    ObjectNode current = floorOptions.putObject("currentProductionReference");
    current.put("floor", ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
    current.put("thirdImageInBand", reference.path("thirdImageInBand").asInt());
    current.put(
        "note",
        "直读生产 ImageSimilarityGate.NEAR_DUPLICATE_FLOOR；参照行不是选项（"
            + reference.path("note").asText()
            + "）");

    ArrayNode columns = floorOptions.putArray("columns");
    for (JsonNode option : options) {
      boolean lowerColumn = option.path("floor").asDouble() == 0.90d;
      ObjectNode column = columns.addObject();
      column.put("floor", option.path("floor").asDouble());
      column.put("label", lowerColumn ? "降到 0.90" : "维持 0.9887");
      column.put("ownBaseKept", option.path("ownBaseKept").asInt());
      column.put("ownBaseDenominator", option.path("ownBaseDenominator").asInt());
      column.put(
          "ownBaseRate",
          ratio(option.path("ownBaseKept").asInt(), option.path("ownBaseDenominator").asInt()));
      column.put("thirdImageInBand", option.path("thirdImageInBand").asInt());
      column.put("thirdImageDenominator", option.path("thirdImageDenominator").asInt());
      column.put(
          "thirdImageRate",
          ratio(
              option.path("thirdImageInBand").asInt(),
              option.path("thirdImageDenominator").asInt()));
      column.put("sameDocSuppressed", option.path("sameDocSuppressed").asInt());
      column.put("sameDocDenominator", option.path("sameDocDenominator").asInt());
      if (lowerColumn) {
        column.put("extraSuppressedSameDoc", option.path("extraSuppressedSameDoc").asInt());
        column.put(
            "newEntrants",
            "本 base +"
                + entrants.path("ownBase").asInt()
                + "（/"
                + entrants.path("ownBaseDenominator").asInt()
                + "）、变体↔第三图 +"
                + entrants.path("thirdImage").asInt()
                + "（/"
                + entrants.path("thirdImageDenominator").asInt()
                + "）");
      } else {
        column.put("newEntrants", "0（参照栏，不新增进带）");
      }
      column.put("sourceFields", option.path("sourceFields").asText());
    }

    // 交叉核对：两栏差值必须等于源锚登记的新增进带数（禁自创数字）。
    assertEquals(
        keep.path("ownBaseKept").asInt() + entrants.path("ownBase").asInt(),
        lower.path("ownBaseKept").asInt(),
        "0.90 栏的本 base 保留数必须 = 0.9887 栏 + 新增进带");
    assertEquals(
        keep.path("thirdImageInBand").asInt() + entrants.path("thirdImage").asInt(),
        lower.path("thirdImageInBand").asInt(),
        "0.90 栏的第三图进带数必须 = 0.9887 栏 + 新增进带");
    assertEquals(
        keep.path("sameDocSuppressed").asInt() + lower.path("extraSuppressedSameDoc").asInt(),
        lower.path("sameDocSuppressed").asInt(),
        "0.90 栏的同文档压制数必须 = 0.9887 栏 + 多压量");

    ObjectNode costs = floorOptions.putObject("recordedCosts");
    costs.put("R", lower.path("ownBaseR").asDouble());
    costs.put(
        "RText",
        lower.path("ownBaseKept").asInt() + "/" + lower.path("ownBaseDenominator").asInt());
    costs.put("extraSuppressedSameDoc", lower.path("extraSuppressedSameDoc").asInt());
    costs.put(
        "text",
        "降到 0.90 已记录的代价：本 base 近重复保留率 R = "
            + lower.path("ownBaseKept").asInt()
            + "/"
            + lower.path("ownBaseDenominator").asInt()
            + " = "
            + ratio(lower.path("ownBaseKept").asInt(), lower.path("ownBaseDenominator").asInt())
            + "（摸到 R ≥ 0.90 门槛）；同文档多压 "
            + lower.path("extraSuppressedSameDoc").asInt()
            + " 张图（S_same 0.02 → 0.08）。维持 0.9887 栏无新增代价，但近重复保留仅 "
            + keep.path("ownBaseKept").asInt()
            + "/"
            + keep.path("ownBaseDenominator").asInt());
    costs.put(
        "structuralInertiaHint",
        "结构性惰性提示：现行带内 12 条被标记候选中已有 4 条实测 r = 0（结构空操作，降权不产生任何位移）；0.90 新增进带对（本 base +"
            + entrants.path("ownBase").asInt()
            + "、第三图 +"
            + entrants.path("thirdImage").asInt()
            + "）是否也含这类惰性子集，本卡无数据、不外推；且新增候选会改变 min-max 的 min ⇒ 既存候选的 r 可能同步改变，属未测项");
    costs.put("notModeled", "0.90 栏的代价只按源锚已记录口径列（pair 级保留率 / 同文档压制 / 第三图进带），不外推到检索级精度或答案质量");
    ObjectNode decision = floorOptions.putObject("ownerDecisionItems");
    decision.put("item", "NEAR_DUPLICATE_FLOOR 是否变更、变更到哪一档");
    decision.put("scope", "本卡只列两栏与代价；变更属另卡，须 Owner 拍板并走签收锚流程");
    decision.put("noTendency", "两栏并列、不排序、不给倾向值");
  }

  // ==================== md（≤40 行） ====================

  private static String buildAnchorMd(ObjectNode anchor) {
    StringBuilder md = new StringBuilder();
    md.append("# near-dup-rerank-decomposition-v1 · r = wv·normVec + wb·normBm25 的本地零外呼分解\n");
    md.append(
        "- 卡 35（零外呼）：只读卡 32/33/34 三锚 + 冻结 `corpusMaterial`；复算链 = 生产 `InMemoryVectorStore.search` → `ImageSimilarityGate.gate` → `DefaultWeightedReranker.rerank`（内含生产 `Bm25Scorer`）。不启 Spring、不 instance provider、不设任何 `*_REAL`，`externalCalls = 0`。\n");
    md.append(
        "- 归一化不重写（D2）：`normVec` / `normBm25` 由生产 `normalizeScores` 的输出反解——文本全空探针得 `wv·normVec`，向量分常数探针得 `wv + wb·normBm25`；两次都直调生产 `rerank`。\n");
    md.append(
        String.format(
            Locale.ROOT,
            "- 权重实测：`wv = %s`、`wb = %s`（卡 32 配置矩阵不覆盖两键 ⇒ 生产回落默认字面）；负对照（矩阵写 0.90/0.10）探针同步给出 %s / %s。\n",
            num(anchor.path("weights").path("vectorWeight").asDouble()),
            num(anchor.path("weights").path("bm25Weight").asDouble()),
            num(anchor.path("weights").path("overriddenVector").asDouble()),
            num(anchor.path("weights").path("overriddenBm25").asDouble())));
    md.append(
        String.format(
            Locale.ROOT,
            "- 候选集重建与卡 32 锚逐条一致：30 行里 cos 非空的 %d 行（图像路成员）身份 + cos + tier/band 全等，cos 空的 %d 行确认不在图像路；`routeListSize` = %s。\n",
            anchor.path("routes").size(),
            30 - anchor.path("routes").size(),
            anchor.path("reconstruction").path("routeListSizePerQuery")));
    md.append("## R1 · 复算 r 与卡 32 反算 r\n");
    md.append(
        String.format(
            Locale.ROOT,
            "- `r = δ / (0.30 × (1 − w))`，δ = fusedB − fusedA；逐条比对（容差 1e-9）：**%d/%d 一致**，`maxAbsDiff = %s`。\n",
            anchor.path("reconciliationSummary").path("consistent").asInt(),
            anchor.path("reconciliationSummary").path("total").asInt(),
            num(anchor.path("reconciliationSummary").path("maxAbsDiff").asDouble())));
    md.append("## R2 · 两分支分桶（退化 = 生产 min-max 某分支归一值全同）\n");
    md.append("| q | form | route | marked | normVec 分支 | normBm25 分支 | bucket |\n");
    md.append("|---|---|---|---|---|---|---|\n");
    for (JsonNode query : anchor.path("queries")) {
      md.append(
          String.format(
              Locale.ROOT,
              "| %d | %s | %d | %d | %s | %s | %s |\n",
              query.path("queryIndex").asInt(),
              query.path("form").asText(),
              query.path("routeListSize").asInt(),
              query.path("markedCandidateCount").asInt(),
              branchCell(query.path("vecBranch")),
              branchCell(query.path("bm25Branch")),
              query.path("bucket").asText()));
    }
    md.append(
        String.format(
            Locale.ROOT,
            "- 卡 34 `consistencySummary`（degenerate %d / nonDegenerate %d，口径 = 被标记候选数 == 1）与本卡口径交叉：%s。\n",
            anchor.path("card34CrossTab").path("card34Degenerate").asInt(),
            anchor.path("card34CrossTab").path("card34NonDegenerate").asInt(),
            anchor.path("verdictCaliberNote").asText()));
    md.append("## 4 条零位移事件（δ = 0）\n");
    md.append(
        "| q | variantKey | cos | normVec | normBm25 | r | isVecMin | isBm25Min | bucket |\n");
    md.append("|---|---|---|---|---|---|---|---|---|\n");
    for (JsonNode event : anchor.path("zeroDisplacementEvents")) {
      md.append(
          String.format(
              Locale.ROOT,
              "| %d | %s | %s | %s | %s | %s | %s | %s | %s |\n",
              event.path("queryIndex").asInt(),
              event.path("variantKey").asText(),
              num(event.path("cos").asDouble()),
              num(event.path("normVec").asDouble()),
              num(event.path("normBm25").asDouble()),
              num(event.path("r").asDouble()),
              event.path("isVecMin").asBoolean(),
              event.path("isBm25Min").asBoolean(),
              event.path("bucket").asText()));
    }
    md.append("## R3 · 结论\n");
    md.append(
        "- `verdict = "
            + anchor.path("verdict").asText()
            + "`："
            + anchor.path("verdictReason").asText()
            + "\n");
    md.append("- 结构发现（数据层面，不改 verdict）：" + anchor.path("structuralFinding").asText() + "\n");
    md.append(
        "- 三值可达路径：`PROVEN` = 12/12 复算一致 ∧ 4/4 零位移两分支同时末位 ∧ 4/4 落在两分支皆非退化的查询；`REFUTED` = 复算一致但零位移事件非两分支末位；`UNDETERMINED` = 复算对不上 / 候选集重建不全 / 零位移事件只落在退化分支（按 R2 不构成末位证据）。\n");
    md.append(
        "- R4 埋点：`executed = "
            + anchor.path("instrumentedPlan").path("executed").asBoolean()
            + "`（required = "
            + anchor.path("instrumentedPlan").path("required").asBoolean()
            + "、wouldInstrumentationHelp = "
            + anchor.path("instrumentedPlan").path("wouldInstrumentationHelp").asBoolean()
            + "）；"
            + anchor.path("instrumentedPlan").path("localBlocker").asText()
            + "。解锁只能靠换语料/查询让 bm25 分化，或由 Owner 在另卡修订口径（见锚 `unlockPathsIfOwnerWantsProven`）。\n");
    md.append("## 腿 B · `NEAR_DUPLICATE_FLOOR` 0.90 决策页（两栏并列，不给倾向值，不改任何常量）\n");
    md.append("| 口径 | 维持 0.9887 | 降到 0.90 |\n");
    md.append("|---|---|---|\n");
    JsonNode columns = anchor.path("floorOptions").path("columns");
    md.append(
        String.format(
            Locale.ROOT,
            "| 本 base 近重复保留 | %s（%s） | %s（%s） |\n",
            fraction(columns.get(0), "ownBaseKept", "ownBaseDenominator"),
            percent(columns.get(0).path("ownBaseRate").asDouble()),
            fraction(columns.get(1), "ownBaseKept", "ownBaseDenominator"),
            percent(columns.get(1).path("ownBaseRate").asDouble())));
    md.append(
        String.format(
            Locale.ROOT,
            "| 变体↔第三图进带 | %s（%s） | %s（%s） |\n",
            fraction(columns.get(0), "thirdImageInBand", "thirdImageDenominator"),
            percent(columns.get(0).path("thirdImageRate").asDouble()),
            fraction(columns.get(1), "thirdImageInBand", "thirdImageDenominator"),
            percent(columns.get(1).path("thirdImageRate").asDouble())));
    md.append(
        String.format(
            Locale.ROOT,
            "| 同文档图被压（S_same 分母） | %s | %s（多压 %d 张） |\n",
            fraction(columns.get(0), "sameDocSuppressed", "sameDocDenominator"),
            fraction(columns.get(1), "sameDocSuppressed", "sameDocDenominator"),
            columns.get(1).path("extraSuppressedSameDoc").asInt()));
    md.append("| 新增进带 | 0 | " + columns.get(1).path("newEntrants").asText() + " |\n");
    md.append(
        String.format(
            Locale.ROOT,
            "| 已记录代价 | 无新增（近重复保留仅 %s） | R = %s（%s）、多压 %d 张同文档图 |\n",
            fraction(columns.get(0), "ownBaseKept", "ownBaseDenominator"),
            percent(columns.get(1).path("ownBaseRate").asDouble()),
            fraction(columns.get(1), "ownBaseKept", "ownBaseDenominator"),
            columns.get(1).path("extraSuppressedSameDoc").asInt()));
    md.append(
        "- "
            + anchor
                .path("floorOptions")
                .path("recordedCosts")
                .path("structuralInertiaHint")
                .asText()
            + "\n");
    md.append(
        String.format(
            Locale.ROOT,
            "- 现行生产参照（非选项）：`NEAR_DUPLICATE_FLOOR = %s`，第三图进带 %d/%d。\n",
            num(
                anchor
                    .path("floorOptions")
                    .path("currentProductionReference")
                    .path("floor")
                    .asDouble()),
            anchor
                .path("floorOptions")
                .path("currentProductionReference")
                .path("thirdImageInBand")
                .asInt(),
            columns.get(1).path("thirdImageDenominator").asInt()));
    md.append(
        "- 待 Owner 决策项：`NEAR_DUPLICATE_FLOOR` 是否变更、变更到哪一档；本卡只给两栏与代价。细节锚：`docs/rag-quality/near-dup-rerank-decomposition-v1.json`（默认全量重算后按树规范化比对，剔除顶层 `runtime`）。\n");
    String text = md.toString();
    assertTrue(!text.contains("\r"), "锚 md 必须纯 LF");
    assertTrue(text.lines().count() <= 40, "锚 md 必须 ≤40 行（实测 " + text.lines().count() + "）");
    return text;
  }

  private static String branchCell(JsonNode branch) {
    return branch.path("degenerate").asBoolean()
        ? "退化（" + branch.path("reason").asText() + "）"
        : "正常（min → 0）";
  }

  private static String fraction(JsonNode column, String counter, String denominator) {
    return column.path(counter).asInt() + "/" + column.path(denominator).asInt();
  }

  private static String percent(double rate) {
    return String.format(Locale.ROOT, "%.2f%%", rate * 100d);
  }

  private static String ratio(int counter, int denominator) {
    return denominator == 0
        ? "0"
        : String.format(Locale.ROOT, "%.6f", (double) counter / denominator);
  }

  private static String num(double value) {
    return String.valueOf(value);
  }

  // ==================== 读写锚（写模式 / 逐字节复算） ====================

  private static void writeOrCompareJson(Path path, ObjectNode node) throws Exception {
    byte[] expected = canonicalBytes(node);
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "锚 JSON 必须纯 LF");
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论锚缺失，先以 -Dnear.dup.rerank.decomposition.write=true 生成一次: " + path.toAbsolutePath());
    JsonNode onDisk = readJson(path);
    assertArrayEquals(
        canonicalBytes(withoutRuntime(node)),
        canonicalBytes(withoutRuntime(onDisk)),
        "锚与本次全量重算规范化字节不一致（JSON 树规范化后比较、剔除顶层 runtime）: " + path);
    System.out.println("[near-dup-rerank-decomposition] 默认复跑逐字节一致（JSON 树规范化，剔除顶层 runtime）：" + path);
  }

  private static void writeOrCompareBytes(Path path, byte[] expected) throws Exception {
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "锚 md 必须纯 LF");
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论 md 缺失，先以 -Dnear.dup.rerank.decomposition.write=true 生成一次: " + path.toAbsolutePath());
    assertArrayEquals(expected, Files.readAllBytes(path), "锚 md 与本次生成逐字节不一致: " + path);
    System.out.println("[near-dup-rerank-decomposition] 默认复跑逐字节一致（md）：" + path);
  }

  private static void writeAnchor(Path path, byte[] content) throws Exception {
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, content);
    System.out.println("[near-dup-rerank-decomposition] 锚已写入（write 模式）: " + path);
  }

  private static JsonNode withoutRuntime(JsonNode node) {
    ObjectNode copy = node.deepCopy();
    copy.remove("runtime");
    return copy;
  }

  private static byte[] canonicalBytes(JsonNode node) throws Exception {
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    return (MAPPER.writer(printer).writeValueAsString(node) + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  // ==================== 通用帮手 ====================

  private static JsonNode readJson(Path path) throws Exception {
    assertTrue(Files.exists(path), "文件不存在: " + path.toAbsolutePath());
    return MAPPER.readTree(Files.readAllBytes(path));
  }

  private static void putAnchorRef(ObjectNode parent, String name, Path path) throws Exception {
    ObjectNode node = parent.putObject(name);
    node.put("path", path.toString().replace('\\', '/'));
    node.put("sha256", RealPdfAssets.sha256(Files.readAllBytes(path)));
    node.put("readOnly", true);
  }

  private static VectorSearchHit hit(String chunkId, double score, String text) {
    return new VectorSearchHit(
        chunkId, "probe-doc", score, text, Map.of("knowledgeBaseId", "9908"));
  }

  private static List<RetrievalCandidate> blankText(List<RetrievalCandidate> candidates) {
    List<RetrievalCandidate> blanked = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      VectorSearchHit hit = candidate.hit();
      blanked.add(
          new RetrievalCandidate(
              new VectorSearchHit(hit.chunkId(), hit.documentId(), hit.score(), "", hit.metadata()),
              candidate.rerankScore()));
    }
    return blanked;
  }

  private static List<RetrievalCandidate> constantScore(List<RetrievalCandidate> candidates) {
    List<RetrievalCandidate> constant = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      VectorSearchHit hit = candidate.hit();
      constant.add(
          new RetrievalCandidate(
              new VectorSearchHit(
                  hit.chunkId(), hit.documentId(), 1.0d, hit.text(), hit.metadata()),
              candidate.rerankScore()));
    }
    return constant;
  }

  private static float[] decodeVector(String base64) {
    byte[] raw = Base64.getDecoder().decode(base64);
    float[] vector = new float[raw.length / Float.BYTES];
    ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
    for (int index = 0; index < vector.length; index++) {
      vector[index] = buffer.getFloat();
    }
    return vector;
  }

  /** 两次除法引入的 ±1ulp 归整；值仍由生产归一化的输出决定，不做任何 min-max 重算。 */
  private static double snap(double value) {
    double clamped = Math.max(0d, Math.min(1d, value));
    if (Math.abs(clamped) < SNAP_TOLERANCE) {
      return 0d;
    }
    if (Math.abs(clamped - 1d) < SNAP_TOLERANCE) {
      return 1d;
    }
    return clamped;
  }

  private static boolean allEqual(List<String> values) {
    return !values.isEmpty() && values.stream().distinct().count() == 1;
  }

  private static String degenerateReason(List<String> values) {
    if (!allEqual(values)) {
      return "正常";
    }
    String value = values.get(0);
    return "0.0".equals(value) ? "全同 0.0，max <= 0" : "全同 1.0，max == min";
  }

  private static String stringOf(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String nullableText(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }

  private static String variantKeyOf(String documentId) {
    assertTrue(
        documentId != null && documentId.startsWith(DOCUMENT_ID_PREFIX),
        "文档 id 前缀漂移: " + documentId);
    return documentId.substring(DOCUMENT_ID_PREFIX.length());
  }

  // ==================== 记录类型 ====================

  private record Weights(
      double vector,
      double bm25,
      double overriddenVector,
      double overriddenBm25,
      Set<String> configMatrixKeys) {}

  private record RouteRow(
      int queryIndex,
      String chunkId,
      String variantKey,
      double cos,
      String tier,
      String band,
      double normVec,
      double normBm25,
      double r,
      String bucket) {

    RouteRow(
        int queryIndex,
        String chunkId,
        String variantKey,
        double cos,
        String tier,
        String band,
        double normVec,
        double normBm25,
        double r) {
      this(queryIndex, chunkId, variantKey, cos, tier, band, normVec, normBm25, r, NON_DEGENERATE);
    }

    RouteRow withBucket(String newBucket) {
      return new RouteRow(
          queryIndex, chunkId, variantKey, cos, tier, band, normVec, normBm25, r, newBucket);
    }

    boolean marked() {
      return ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(tier);
    }
  }

  private record QueryRoute(
      int queryIndex,
      String form,
      String text,
      List<RouteRow> rows,
      boolean vecDegenerate,
      boolean bm25Degenerate,
      String vecReason,
      String bm25Reason) {

    String bucket() {
      return vecDegenerate || bm25Degenerate ? DEGENERATE_NOT_EVIDENCE : NON_DEGENERATE;
    }
  }

  private record ReconcileRow(
      int queryIndex,
      String variantKey,
      String chunkId,
      double w,
      double deltaDerivedR,
      double recomputedR,
      double absDiff,
      boolean consistent) {}

  private record Reconciliation(List<ReconcileRow> rows, int consistentCount, double maxAbsDiff) {}
}
