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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 34 · near-dup-oq1-attribution 分析（判据 R0–R8 / 决策 D1–D5 见 {@code
 * openspec/changes/near-dup-oq1-attribution/proposal.md}）。
 *
 * <p><b>零外呼</b>：输入只有卡 32 的冻结锚 {@code docs/rag-quality/near-dup-band-entry-v1.json}、卡 33 的冻结锚 {@code
 * docs/rag-quality/near-dup-window-coupling-v1.json}（两者只读、零字节变动）与三处生产源码的字面；不启动 Spring 上下文、不引用任何
 * provider、不设任何 {@code *_REAL} opt-in。全部产出是算术。
 *
 * <ul>
 *   <li><b>R1</b>：三处机制代码字面逐处落锚（文件 + 运行期定位到的行号 + 一字不差片段）——{@code
 *       DefaultWeightedReranker#normalizeScores} 的 min-max 三分支、{@code
 *       RetrievalConfigResolver#mergeRoute} 的 {@code rerankScore × routeWeight} 累加与 {@code
 *       DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30}、{@code ImageSimilarityGate#downWeightNearDuplicates} 的
 *       {@code rerankScore × weight}；
 *   <li><b>R2/R3</b>：{@code δ = fusedB − fusedA = 0.30 × (1 − w) × r}，反算 {@code r = δ / (0.30 × (1
 *       − w))}， 逐条断言 {@code r ∈ [0, 1]} 与 {@code δ = 0 ⇔ r = 0}；4 条零位移事件因此是<b>结构空操作</b>；
 *   <li><b>R4/R5</b>：结论三档（{@code PROVEN} / {@code CONSISTENT-NOT-PROVEN} / {@code
 *       MISSING-EVIDENCE}）逐条落锚，{"r = 0 由 min-max 末位造成"} 只由一致性检查支持、<b>不升格</b>；判定落 {@code
 *       NEEDS-INSTRUMENTED-RUN}（埋点方案<b>不执行</b>）。
 * </ul>
 *
 * <p><b>不改生产、不改常量</b>（D4）：{@code 0.30}、三档权重 0.75/0.60/0.50、任何阈值一个不动； {@code IMAGE_ROUTE_WEIGHT}
 * 只是公式镜像，不参与任何生产值。
 */
class NearDupOq1AttributionAnalysisTest {

  /** 卡 32 冻结锚（D1：只读，零字节变动）。 */
  private static final Path BAND_ENTRY =
      Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  /** 卡 33 冻结锚（只读：OQ1 的出处，本卡回答它）。 */
  private static final Path WINDOW_COUPLING =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.json");

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-oq1-attribution-v1.json");

  private static final Path ANCHOR_MD =
      Path.of("docs", "rag-quality", "near-dup-oq1-attribution-v1.md");

  /** 三处机制源码（R1）：只读字面，测试期定位行号，不改一字。 */
  private static final Path RERANKER_SRC =
      Path.of(
          "src",
          "main",
          "java",
          "com",
          "slz",
          "crm",
          "knowledge",
          "retrieval",
          "DefaultWeightedReranker.java");

  private static final Path RESOLVER_SRC =
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

  private static final Path GATE_SRC =
      Path.of(
          "src",
          "main",
          "java",
          "com",
          "slz",
          "crm",
          "knowledge",
          "retrieval",
          "ImageSimilarityGate.java");

  /** 首次生成开关（-Dnear.dup.oq1.attribution.write=true）：常规运行只做逐字节比对，绝不落盘。 */
  private static final boolean WRITE_MODE = Boolean.getBoolean("near.dup.oq1.attribution.write");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 图像路由权重镜像（{@code RetrievalConfigResolver:19} {@code DEFAULT_IMAGE_ROUTE_WEIGHT}；只做公式复算）。 */
  private static final double IMAGE_ROUTE_WEIGHT = 0.30d;

  /** 恒等式还原与 SHA 之外的算术容差。 */
  private static final double EPS = 1e-12d;

  /** 卡 32 锚实测的三档降权权重（只读引用，不改不改写）。 */
  private static final List<Double> BAND_WEIGHTS = List.of(0.75d, 0.60d, 0.50d);

  /** 三档结论标签（R4）：第二档不得写成第一档。 */
  private static final String PROVEN = "PROVEN";

  private static final String CONSISTENT_NOT_PROVEN = "CONSISTENT-NOT-PROVEN";

  private static final String MISSING_EVIDENCE = "MISSING-EVIDENCE";

  /** R5 三值判定；本卡落 {@code NEEDS-INSTRUMENTED-RUN}（min-max 末位的成因不在锚内）。 */
  private static final String VERDICT = "NEEDS-INSTRUMENTED-RUN";

  @Test
  @DisplayName("卡34：OQ1 归因（δ=0 ⇔ r=0 ⇒ no-op 可证；min-max 末位只到「相容未证」+ 埋点方案不执行）")
  void attributesOq1() throws Exception {
    ObjectNode bandEntry = (ObjectNode) readJson(BAND_ENTRY);
    ObjectNode coupling = (ObjectNode) readJson(WINDOW_COUPLING);
    assertInputs(bandEntry, coupling);

    List<Event> events = buildEvents(bandEntry, coupling);
    ObjectNode anchor = buildAnchor(bandEntry, coupling, events);

    writeOrCompareJson(ANCHOR_JSON, anchor);
    writeOrCompareBytes(ANCHOR_MD, buildAnchorMd(anchor, events).getBytes(StandardCharsets.UTF_8));
  }

  // ==================== 输入自检（R0：只读两个冻结锚，逐条对账） ====================

  private static void assertInputs(ObjectNode bandEntry, ObjectNode coupling) {
    assertEquals(
        "near-dup-band-entry-and-tier-fill",
        bandEntry.path("card").asText(),
        "卡 32 锚 card 不符（读错文件或锚被替换）");
    assertEquals("near-dup-window-coupling", coupling.path("card").asText(), "卡 33 锚 card 不符");
    assertEquals(30, bandEntry.path("candidates").size(), "卡 32 锚 candidates 必须 30 行");
    assertEquals(12, bandEntry.path("downWeightedCount").asInt(), "卡 32 锚 downWeightedCount 必须 12");
    JsonNode entries = bandEntry.path("windowCheck").path("entries");
    assertEquals(12, entries.size(), "card 32 windowCheck.entries 必须 12 条（被标记的事件）");
    assertEquals(12, coupling.path("events").size(), "卡 33 锚 events 必须 12 条");
    assertEquals(
        "OQ1",
        coupling.path("openQuestions").get(0).path("id").asText(),
        "卡 33 锚的第一条 openQuestion 必须是 OQ1");
    assertEquals(
        "INCONCLUSIVE",
        coupling.path("openQuestions").get(0).path("status").asText(),
        "OQ1 在卡 33 锚里必须仍是 INCONCLUSIVE（本卡回答的就是它）");
    long zeroCount = 0L;
    for (JsonNode entry : entries) {
      if (entry.path("delta").asDouble() == 0d) {
        zeroCount++;
      }
    }
    assertEquals(4L, zeroCount, "零位移事件必须恰 4 条（卡 32/33 锚）");
  }

  // ==================== R2 · 逐条反算 r 与恒等式 ====================

  private static List<Event> buildEvents(ObjectNode bandEntry, ObjectNode coupling) {
    JsonNode entries = bandEntry.path("windowCheck").path("entries");
    JsonNode couplingEvents = coupling.path("events");
    List<Event> events = new ArrayList<>(entries.size());
    for (int index = 0; index < entries.size(); index++) {
      JsonNode entry = entries.get(index);
      JsonNode counterpart = couplingEvents.get(index);
      int queryIndex = entry.path("queryIndex").asInt();
      String variantKey = entry.path("variantKey").asText();
      assertEquals(queryIndex, counterpart.path("queryIndex").asInt(), "两锚事件顺序不一致: index=" + index);
      assertEquals(
          variantKey, counterpart.path("variantKey").asText(), "两锚事件身份不一致: index=" + index);

      double weight = entry.path("weight").asDouble();
      assertTrue(BAND_WEIGHTS.contains(weight), "权重必须是卡 32 实测三档之一: " + weight);
      double fusedA = entry.path("fusedA").asDouble();
      double fusedB = entry.path("fusedB").asDouble();
      double delta = entry.path("delta").asDouble();
      assertEquals(delta, counterpart.path("deltaE").asDouble(), 0d, "两锚 δ 必须同值: " + variantKey);
      assertEquals(fusedB - fusedA, delta, EPS, "δ 必须等于 fusedB − fusedA: " + variantKey);
      assertEquals(
          IMAGE_ROUTE_WEIGHT * (1d - weight),
          entry.path("window").asDouble(),
          EPS,
          "卡 32 锚的 window 必须仍等于 0.30 × (1 − w): " + variantKey);

      double factor = IMAGE_ROUTE_WEIGHT * (1d - weight);
      double r = delta / factor;
      assertTrue(r >= 0d && r <= 1d + EPS, "r 必须落在 [0, 1]: " + variantKey + " r=" + r);
      assertEquals(delta, factor * r, EPS, "δ = 0.30 × (1 − w) × r 必须逐条还原: " + variantKey);
      assertEquals(delta == 0d, r == 0d, "δ = 0 ⇔ r = 0 必须逐条成立: " + variantKey);

      JsonNode row = candidateRow(bandEntry, queryIndex, variantKey);
      assertEquals(
          "NEAR_DUPLICATE",
          row.path("tier").asText(),
          "被标记候选 tier 必须是 NEAR_DUPLICATE: " + variantKey);
      assertTrue(
          row.path("downWeighted").asBoolean(), "被标记候选 downWeighted 必须为 true: " + variantKey);
      assertEquals(
          entry.path("cos").asDouble(),
          row.path("cos").asDouble(),
          0d,
          "两处 cos 必须同值: " + variantKey);

      events.add(
          new Event(
              index,
              queryIndex,
              variantKey,
              entry.path("chunkId").asText(),
              entry.path("band").asText(),
              weight,
              entry.path("window").asDouble(),
              entry.path("cos").asDouble(),
              fusedA,
              fusedB,
              delta,
              r));
    }
    return events;
  }

  private static JsonNode candidateRow(ObjectNode bandEntry, int queryIndex, String variantKey) {
    for (JsonNode row : bandEntry.path("candidates")) {
      if (row.path("queryIndex").asInt() == queryIndex
          && row.path("variantKey").asText().equals(variantKey)) {
        return row;
      }
    }
    throw new AssertionError("卡 32 锚 candidates 里找不到该查询的候选: q" + queryIndex + " " + variantKey);
  }

  // ==================== 锚构造（R1/R2/R3/R4/R5/R6） ====================

  private static ObjectNode buildAnchor(
      ObjectNode bandEntry, ObjectNode coupling, List<Event> events) throws Exception {
    ObjectNode anchor = MAPPER.createObjectNode();
    anchor.put("card", "near-dup-oq1-attribution");
    anchor.put("stage", "OQ1-ATTRIBUTION");
    anchor.put("decisionVersion", "NEAR-DUP-OQ1-ATTRIBUTION-1.0");
    anchor.put("decisionOwner", "OWNER");
    anchor.put("questionId", "OQ1");
    anchor.put("upstreamAnchor", slash(BAND_ENTRY));
    anchor.put("windowCouplingAnchor", slash(WINDOW_COUPLING));

    ObjectNode criteria = anchor.putObject("criteria");
    criteria.put(
        "R0", "零外呼：不设任何 *_REAL opt-in，不启动 Spring 上下文，不 instance provider，不跑检索；输入只有两个冻结锚 + 三处源码字面");
    criteria.put(
        "R1",
        "机制锚定三处，逐处给文件:行 + 一字不差片段：normalizeScores 的 min-max 三分支；mergeRoute 的 rerankScore × routeWeight 累加且 DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30；downWeightNearDuplicates 的 rerankScore × weight");
    criteria.put(
        "R2",
        "位移-贡献恒等式：δ = 0.30 × (1 − w) × r，r = δ / (0.30 × (1 − w))；逐条断言 r ∈ [0,1] 且 δ = 0 ⇔ r = 0（禁抽样）");
    criteria.put("R3", "结构性推论：r = 0 ⇒ 该候选在融合里的图像贡献为 0 ⇒ 降权是结构空操作（0.30 × (1 − w) × 0 = 0），与是否被标记无关");
    criteria.put(
        "R4",
        "可判定性三档：PROVEN / CONSISTENT-NOT-PROVEN / MISSING-EVIDENCE 逐条落锚；第二档不得写成第一档；一致性检查不升级为证明");
    criteria.put(
        "R5",
        "三值判定：MECHANISM-CONFIRMED / INCONCLUSIVE / NEEDS-INSTRUMENTED-RUN，附 verdictReason；不为得到某一档而放宽 R4");
    criteria.put(
        "R6",
        "埋点方案（仅 NEEDS-INSTRUMENTED-RUN）：最小字段集 4 项 + 30 外呼预算 + 为什么不能再省；executed = false，本卡不执行");
    criteria.put("R7", "边界：src/main 零 diff；pom.xml 与 .github/** 零改；既有锚零字节变动；卡 32/33 及既有关联测试类只读");
    criteria.put(
        "R8", "停手：任何「合理但未测」的解释被写成结论即不合格（沿用卡 33 D3）；一致性检查出现反例则判 INCONCLUSIVE 并记 proposal §8");

    ObjectNode upstream = anchor.putObject("upstreamAnchors");
    ObjectNode bandEntryAnchor = upstream.putObject("bandEntry");
    bandEntryAnchor.put("path", slash(BAND_ENTRY));
    bandEntryAnchor.put("sha256", sha256(BAND_ENTRY));
    bandEntryAnchor.put("role", "READ-ONLY-INPUT");
    bandEntryAnchor.put("matchesUpstream", true);
    ObjectNode couplingAnchor = upstream.putObject("windowCoupling");
    couplingAnchor.put("path", slash(WINDOW_COUPLING));
    couplingAnchor.put("sha256", sha256(WINDOW_COUPLING));
    couplingAnchor.put("role", "READ-ONLY-INPUT");
    couplingAnchor.put("matchesUpstream", true);
    upstream.put(
        "couplingOq1Status", coupling.path("openQuestions").get(0).path("status").asText());
    upstream.put(
        "couplingOq1MissingFieldCount",
        coupling.path("openQuestions").get(0).path("missingFields").size());

    // ---------- R1 · 机制三处（行号运行期定位，片段一字不差） ----------
    ObjectNode mechanism = anchor.putObject("mechanism");
    ArrayNode codeFacts = mechanism.putArray("codeFacts");
    codeFacts.add(
        codeFact(
            "CF-R1-1",
            RERANKER_SRC,
            "DefaultWeightedReranker#normalizeScores",
            "图像路重排器：min-max 归一化，候选列表最小值 → 0.0（R1 的第一处代码事实）",
            List.of(
                "private List<Double> normalizeScores(List<Double> scores) {",
                "if (max <= 0) {",
                "result = java.util.Collections.nCopies(scores.size(), 0.0);",
                "} else if (max == min) {",
                "result = java.util.Collections.nCopies(scores.size(), 1.0);",
                "result = scores.stream().map(score -> (score - min) / (max - min)).toList();")));
    codeFacts.add(
        codeFact(
            "CF-R1-2",
            RESOLVER_SRC,
            "RetrievalConfigResolver#mergeRoute",
            "图文路由融合：重排分乘路由权重后同键累加；DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30（R1 的第二处代码事实）",
            List.of(
                "private static final double DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30;",
                "mergeRoute(merged, imageCandidates, imageWeight);",
                "new RetrievalCandidate(candidate.hit(), candidate.rerankScore() * routeWeight));",
                "existing.hit(), existing.rerankScore() + candidate.rerankScore() * routeWeight));")));
    codeFacts.add(
        codeFact(
            "CF-R1-3",
            GATE_SRC,
            "ImageSimilarityGate#downWeightNearDuplicates",
            "近似同图降权：重排后 rerankScore × weight，发生在融合之前（R1 的第三处代码事实）",
            List.of(
                "public static List<RetrievalCandidate> downWeightNearDuplicates(",
                "double weight = weightOfBand(stringOf(metadata.get(BAND_METADATA_KEY)));",
                "tag(candidate.hit(), BAND_WEIGHT_METADATA_KEY, weight),",
                "candidate.rerankScore() * weight));")));
    mechanism.put("imageRouteWeight", IMAGE_ROUTE_WEIGHT);

    ObjectNode identity = mechanism.putObject("identity");
    identity.put("formula", "δ = fusedB − fusedA = 0.30 × (1 − w) × r");
    identity.put("reverse", "r = δ / (0.30 × (1 − w))");
    identity.put("equivalence", "δ = 0 ⇔ r = 0");
    identity.put(
        "signNote",
        "δ ≥ 0：A 跑是生产降权（fusedA，图像重排分已乘 w），B 跑是恒等替换（fusedB，未乘），故 fusedB ≥ fusedA（卡 32 锚 ablationDesign.passA / passB）");
    identity.put(
        "rDecomposition",
        "r = 0.60 × normVec + 0.40 × normBm25（DefaultWeightedReranker#rerank 的两个默认权重）⇒ 两权重均为正 ⇒ r = 0 ⇔ 两分支归一值同时为 0 ⇒ 该候选在两条分支上都取到最小值");
    ArrayNode premises = identity.putArray("premises");
    premises.add(
        "0.30 > 0（DEFAULT_IMAGE_ROUTE_WEIGHT 为正值，卡 32 锚 constants.imageRouteWeight = 0.3）");
    premises.add("w ∈ {0.75, 0.60, 0.50} < 1 ⇒ 1 − w > 0（降权系数严格小于 1，故 δ 不会因 w 而恒 0）");
    premises.add(
        "降权发生在融合之前：downWeightNearDuplicates 先乘 w，mergeRoute 再乘 routeWeight 累加（同一候选的图像贡献 = 0.30 × w × r）");
    premises.add(
        "r 由恒等式反算，不是实测；r ∈ [0, 1] 以 DefaultWeightedReranker 默认权重 0.60 + 0.40 = 1 为前提（动态配置可改，锚内未记录实际权重 ⇒ 记入 MISSING-EVIDENCE）");
    mechanism.put(
        "noOpCorollary",
        "r = 0 ⇒ 融合里该候选的图像贡献 = 0.30 × w × 0 = 0 ⇒ 对它降权是结构空操作（no-op），与它是否带 NEAR_DUPLICATE 标记无关");

    // ---------- R2/R3 · 逐条反算 + 空操作算式 ----------
    ObjectNode dataCheck = anchor.putObject("dataCheck");
    ArrayNode rByEvent = dataCheck.putArray("rByEvent");
    long zeroCount = 0L;
    for (Event event : events) {
      ObjectNode row = rByEvent.addObject();
      row.put("index", event.index());
      row.put("queryIndex", event.queryIndex());
      row.put("variantKey", event.variantKey());
      row.put("chunkId", event.chunkId());
      row.put("band", event.band());
      row.put("tier", "NEAR_DUPLICATE");
      row.put("downWeighted", true);
      row.put("w", event.w());
      row.put("window", event.window());
      row.put("cos", event.cos());
      row.put("fusedA", event.fusedA());
      row.put("fusedB", event.fusedB());
      row.put("delta", event.delta());
      row.put("r", event.r());
      if (event.delta() == 0d) {
        zeroCount++;
      }
    }
    assertEquals(4L, zeroCount, "零位移事件必须恰 4 条");
    assertEquals(events.size(), rByEvent.size(), "rByEvent 必须逐条覆盖全部事件（禁抽样）");

    ObjectNode distribution = dataCheck.putObject("rDistribution");
    distribution.put("zeroDisplacementCount", zeroCount);
    distribution.put("nonzeroDisplacementCount", events.size() - zeroCount);
    ArrayNode zeroRValues = distribution.putArray("zeroRValues");
    ArrayNode nonzeroREntries = distribution.putArray("nonzeroRValues");
    double rMin = Double.MAX_VALUE;
    double rMax = -1d;
    for (Event event : events) {
      rMin = Math.min(rMin, event.r());
      rMax = Math.max(rMax, event.r());
      if (event.delta() == 0d) {
        zeroRValues.add(event.r());
      } else {
        ObjectNode row = nonzeroREntries.addObject();
        row.put("queryIndex", event.queryIndex());
        row.put("variantKey", event.variantKey());
        row.put("w", event.w());
        row.put("delta", event.delta());
        row.put("r", event.r());
      }
    }
    distribution.put("rMin", rMin);
    distribution.put("rMax", rMax);
    distribution.put("note", "零位移 4 条的 r 全为 0；非零 8 条逐个列出（与卡 33 锚 displacedCount = 8 对账）");

    ObjectNode invariants = dataCheck.putObject("invariants");
    invariants.put("eventCount", events.size());
    invariants.put("perEventChecked", true);
    invariants.put("sampled", false);
    invariants.put("rInUnitInterval", true);
    invariants.put("deltaZeroIffRZero", true);
    invariants.put("deltaEqualsFusedBDifference", true);
    invariants.put("windowEqualsRouteWeightTimesOneMinusW", true);
    ArrayNode weightsObserved = invariants.putArray("weightsObserved");
    for (double weight : BAND_WEIGHTS) {
      weightsObserved.add(weight);
    }

    ArrayNode noOpProof = dataCheck.putArray("noOpProof");
    for (Event event : events) {
      if (event.delta() != 0d) {
        continue;
      }
      double factor = IMAGE_ROUTE_WEIGHT * (1d - event.w());
      double product = IMAGE_ROUTE_WEIGHT * (1d - event.w()) * event.r();
      assertEquals(0d, product, 0d, "空操作算式必须恰为 0: " + event.variantKey());
      ObjectNode proof = noOpProof.addObject();
      proof.put("index", event.index());
      proof.put("queryIndex", event.queryIndex());
      proof.put("variantKey", event.variantKey());
      proof.put("w", event.w());
      proof.put("r", event.r());
      proof.put("factor", factor);
      proof.put("computation", "0.30 × (1 − " + twoDecimals(event.w()) + ") × 0 = 0");
      proof.put("product", product);
      proof.put("tier", "NEAR_DUPLICATE");
      proof.put("downWeighted", true);
      proof.put("markedAndZeroDeltaCoexist", true);
    }
    assertEquals(zeroCount, noOpProof.size(), "空操作算式必须逐条覆盖 4 条零位移事件");
    dataCheck.put(
        "noOpStatement",
        "该候选在融合里的图像贡献为 0 ⇒ 对它降权是结构空操作（no-op）：0.30 × (1 − w) × 0 = 0。「downWeighted = true」与「δ = 0」并存不是矛盾，而是「标记了但无贡献」。本结论不依赖任何缺失字段。");
    ObjectNode counts = dataCheck.putObject("counts");
    counts.put("markedCount", events.size());
    counts.put("zeroDeltaCount", zeroCount);
    counts.put("displacedCount", events.size() - zeroCount);
    counts.put("crossCheck", "与卡 33 锚 counts.markedCount = 12、displacedCount = 8 对账");

    // ---------- R4/R5 · 三档结论 + 一致性检查 + 判定 ----------
    ObjectNode attribution = anchor.putObject("attribution");
    ObjectNode claimClass = attribution.putObject("claimClass");
    ArrayNode proven = claimClass.putArray(PROVEN);
    proven.add(
        claim(
            "P1",
            "机制三处字面在位：normalizeScores 的 min-max 三分支；mergeRoute 的 rerankScore × routeWeight 累加且 DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30；downWeightNearDuplicates 的 rerankScore × weight",
            "mechanism.codeFacts CF-R1-1/2/3（文件:行 + 一字不差片段；守卫逐处 grep 复验）"));
    proven.add(
        claim(
            "P2",
            "δ = 0.30 × (1 − w) × r，且 δ = 0 ⇔ r = 0",
            "mechanism.identity + dataCheck.rByEvent：12/12 逐条还原、12/12 r ∈ [0,1]、12/12 等价式成立"));
    proven.add(
        claim(
            "P3",
            "4 条零位移事件的 r 恰为 0 ⇒ 对它们的降权是结构空操作",
            "dataCheck.noOpProof：4/4 逐条算式 0.30 × (1 − w) × 0 = 0，且 tier = NEAR_DUPLICATE、downWeighted = true 同时成立"));
    ArrayNode consistent = claimClass.putArray(CONSISTENT_NOT_PROVEN);
    consistent.add(
        claim(
            "C1",
            "r = 0 的成因是 DefaultWeightedReranker#normalizeScores 的 min-max 把末位归一成 0.0",
            "只有一致性检查支持（attribution.consistencyCheck 6/6 + attribution.routeLevelCosObservation）；锚内没有 r、routeListSize、bm25 归一值 ⇒ 不得升格为结论（R4/D3）"));
    consistent.add(
        claim(
            "C2",
            "该候选是图像路的末位（图像向量分最小）",
            "attribution.routeLevelCosObservation：4 条 r = 0 的候选在各自查询 cos 有值的行里确为最小；但 cos 不是 r（卡 27 量纲纪律），且列表长度未知 ⇒ 仍不升级"));
    ArrayNode missing = claimClass.putArray(MISSING_EVIDENCE);
    missing.add(
        claim(
            "M1",
            "r 本身（imageRerankScoreA / imageRerankScoreB）",
            "卡 32 锚 candidates 只有 fusedA / fusedB / cos / tier / band / rankA / rankB，无逐路由重排分；本卡 r 只能由恒等式反算"));
    missing.add(
        claim("M2", "routeListSize（该查询图像路候选数）", "无法区分「末位归 0」与「图像路列表长度为 1 的退化」，两者在 δ 上都表现为 r = 0"));
    missing.add(
        claim(
            "M3",
            "bm25 归一值（imageRerankBm25Normalized）",
            "r = 0.60 × normVec + 0.40 × normBm25，r = 0 要求两分支同时为 0；只有 cos（向量分）不足以说明 bm25 分支"));
    missing.add(
        claim(
            "M4",
            "rag.retrieval.rerank.vector-weight / bm25-weight 的实测值",
            "r ∈ [0, 1] 是 R2 的断言前提，以两者按默认 0.60 / 0.40 求和为 1 成立；卡 32 运行未记录实际权重"));

    Map<Integer, List<Event>> byQuery = new LinkedHashMap<>();
    for (Event event : events) {
      byQuery.computeIfAbsent(event.queryIndex(), key -> new ArrayList<>()).add(event);
    }
    assertEquals(6, byQuery.size(), "被标记事件应覆盖 6 个查询");
    ArrayNode consistencyCheck = attribution.putArray("consistencyCheck");
    int consistentQueries = 0;
    int counterExamples = 0;
    int nonDegenerate = 0;
    int degenerate = 0;
    for (Map.Entry<Integer, List<Event>> entry : byQuery.entrySet()) {
      List<Event> group = entry.getValue();
      assertEquals(
          group.size(),
          group.stream().map(Event::r).distinct().count(),
          "同一查询内的 r 不应并列（并列会让「最大者」不唯一）: q" + entry.getKey());
      Event rMaxEvent = group.get(0);
      Event cosMaxEvent = group.get(0);
      for (Event event : group) {
        if (event.r() > rMaxEvent.r()) {
          rMaxEvent = event;
        }
        if (event.cos() > cosMaxEvent.cos()) {
          cosMaxEvent = event;
        }
      }
      boolean sameCandidate = rMaxEvent.variantKey().equals(cosMaxEvent.variantKey());
      boolean isDegenerate = group.size() == 1;
      if (sameCandidate) {
        consistentQueries++;
      } else {
        counterExamples++;
      }
      if (isDegenerate) {
        degenerate++;
      } else {
        nonDegenerate++;
      }
      ObjectNode check = consistencyCheck.addObject();
      check.put("queryIndex", entry.getKey());
      check.put("markedCount", group.size());
      check.put("rMaxVariantKey", rMaxEvent.variantKey());
      check.put("rMaxR", rMaxEvent.r());
      check.put("cosMaxVariantKey", cosMaxEvent.variantKey());
      check.put("cosMaxCos", cosMaxEvent.cos());
      check.put("sameCandidate", sameCandidate);
      check.put("degenerate", isDegenerate);
      check.put("rOrderMatchesCosOrder", sameOrder(group));
      check.put("status", sameCandidate ? "CONSISTENT" : "COUNTER-EXAMPLE");
    }
    assertTrue(consistentQueries > 0, "一致性检查至少要有一致查询");

    ObjectNode summary = attribution.putObject("consistencySummary");
    summary.put("queries", byQuery.size());
    summary.put("consistent", consistentQueries);
    summary.put("counterExamples", counterExamples);
    summary.put("nonDegenerate", nonDegenerate);
    summary.put("degenerate", degenerate);
    summary.put("upgraded", false);
    summary.put("rule", "一致性检查只记「与 min-max 结构相容」，不升级为证明；r 最大者与 cos 最大者同一是结构相容的必要条件，不是因果证明（R4/D3）");
    summary.put("degenerateNote", "q3/q4/q5 只有 1 条被标记候选 ⇒ 「r 最大者 = cos 最大者」恒真，属退化检查，不计为证据");

    ObjectNode observation = attribution.putObject("routeLevelCosObservation");
    observation.put(
        "note",
        "辅助观察（不属于 R4 要求的逐查询检查，同样不升级为证明）：4 条 r = 0 的候选，在各自查询 cos 有值的图像行里均为 cos 最小者；q0–q2 的 r 次序与 cos 次序完全一致（@crop-04 > @crop-02 > @crop-06）");
    observation.put("zeroRIsMinCosAmongRowsWithCos", zeroREventsAreMinCos(bandEntry, events));
    observation.put("checkedZeroREvents", (int) zeroCount);
    observation.put(
        "rOrderMatchesCosOrderInNonDegenerateQueries", orderMatchesForNonDegenerate(byQuery));

    anchor.put("verdict", VERDICT);
    anchor.put(
        "verdictReason",
        "PROVEN 部分齐备（R1 三处代码字面在位 + R2 恒等式 12/12 + R3 空操作 4/4 + R4 一致性检查 "
            + consistentQueries
            + "/"
            + byQuery.size()
            + " 无矛盾）；不取 MECHANISM-CONFIRMED 的理由：该值只能覆盖「降权对 r = 0 的候选是 no-op」这层恒等式，覆盖不了 OQ1 的因果问句「r 为什么是 0」；成因（min-max 末位）所需字段 r / routeListSize / bm25 归一值均不在卡 32 锚内，只能停在 CONSISTENT-NOT-PROVEN，故判定取 NEEDS-INSTRUMENTED-RUN");

    // ---------- R6 · 埋点方案（不执行） ----------
    ObjectNode plan = anchor.putObject("instrumentedPlan");
    plan.put("executed", false);
    plan.put("status", "PROPOSED-NOT-EXECUTED");
    plan.put(
        "verdictDependency", "仅在 verdict = NEEDS-INSTRUMENTED-RUN 时交付；written 不等于 authorized（D5）");
    ArrayNode minimalFields = plan.putArray("minimalFields");
    minimalFields.add(
        fieldPlan(
            "imageRerankScore",
            "图像路候选经 DefaultWeightedReranker#rerank 之后、downWeightNearDuplicates 之前（A/B 两跑各一份）",
            "缺它就分不清「r 本身就是 0」与「r ≠ 0 但在融合里被抵消」"));
    minimalFields.add(
        fieldPlan(
            "imageRankInRouteList",
            "该候选在图像路重排列表里的位次（1..routeListSize）",
            "min-max 只把最小值归 0；不知道位次就无法把「末位」与「非末位却 r = 0」分开"));
    minimalFields.add(
        fieldPlan(
            "routeListSize",
            "该查询图像路候选总数",
            "routeListSize = 1 时归一化必然退化（min == max），r = 0 与该结构无关，必须能排除"));
    minimalFields.add(
        fieldPlan(
            "fusionImageContribution",
            "融合时该候选的图像贡献（rerankScore × 0.30，A/B 两跑各一份）",
            "把「r 与 0.30 相乘」这一步与融合里其它项分开，δ 不再需要反解"));
    ObjectNode budget = plan.putObject("callBudget");
    budget.put("ingestPasses", 6);
    budget.put("callsPerIngest", 3);
    budget.put("queries", 6);
    budget.put("callsPerQuery", 2);
    budget.put("total", 30);
    budget.put("formula", "6 摄取 × 3 + 6 查询 × 2 = 30");
    budget.put("basis", "复用卡 32 冻结语料（6 变体 / 6 查询），不新增外呼口径；预算另请授权（proposal R6 / D5）");
    ArrayNode why = plan.putArray("whyCannotShrinkFurther");
    why.add("少 imageRerankScore：分不清「r = 0」与「r ≠ 0 但融合里被抵消」，成因仍只能反解");
    why.add("少 imageRankInRouteList 或 routeListSize：分不清「min-max 把末位归 0」与「列表退化 / 该候选只有 1 条」");
    why.add("少 fusionImageContribution：δ 仍要反解，「乘以 0.30」这一步与融合其它项无法分离");
    why.add("四项构成最小闭包；缺任一项，min-max 末位就只能停在 CONSISTENT-NOT-PROVEN（R4/D3）");
    plan.put("note", "本方案未执行；本卡零外呼，不得据其声明任何结论（D5）");

    ObjectNode runtime = anchor.putObject("runtime");
    runtime.put("externalCalls", 0);
    ArrayNode excluded = runtime.putArray("excludedFromReplay");
    excluded.add("runtime");
    runtime.put(
        "basis", "零外呼：不设任何 *_REAL、不启动 Spring 上下文、不 instance provider；输入 = 两个冻结锚 + 三处源码字面 + 算术");
    return anchor;
  }

  private static ObjectNode claim(String id, String statement, String evidence) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", id);
    node.put("statement", statement);
    node.put("evidence", evidence);
    return node;
  }

  private static ObjectNode fieldPlan(String field, String where, String why) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("field", field);
    node.put("where", where);
    node.put("why", why);
    return node;
  }

  private static boolean sameOrder(List<Event> group) {
    List<Event> byR = new ArrayList<>(group);
    byR.sort((left, right) -> Double.compare(right.r(), left.r()));
    List<Event> byCos = new ArrayList<>(group);
    byCos.sort((left, right) -> Double.compare(right.cos(), left.cos()));
    for (int index = 0; index < byR.size(); index++) {
      if (!byR.get(index).variantKey().equals(byCos.get(index).variantKey())) {
        return false;
      }
    }
    return true;
  }

  private static boolean orderMatchesForNonDegenerate(Map<Integer, List<Event>> byQuery) {
    for (List<Event> group : byQuery.values()) {
      if (group.size() > 1 && !sameOrder(group)) {
        return false;
      }
    }
    return true;
  }

  /** 辅助观察：r = 0 的候选是否是其查询里 cos（有值行）最小者；cos 无值的行不参与（不造 0）。 */
  private static boolean zeroREventsAreMinCos(ObjectNode bandEntry, List<Event> events) {
    for (Event event : events) {
      if (event.delta() != 0d) {
        continue;
      }
      double minCos = Double.MAX_VALUE;
      String minKey = null;
      for (JsonNode row : bandEntry.path("candidates")) {
        if (row.path("queryIndex").asInt() != event.queryIndex()) {
          continue;
        }
        if (row.path("cos").isNull()) {
          continue;
        }
        double cos = row.path("cos").asDouble();
        if (cos < minCos) {
          minCos = cos;
          minKey = row.path("variantKey").asText();
        }
      }
      if (!event.variantKey().equals(minKey)) {
        return false;
      }
    }
    return true;
  }

  // ==================== R1 · 源码字面定位（文件:行 + 一字不差片段） ====================

  private static ObjectNode codeFact(
      String id, Path file, String symbol, String role, List<String> fragments) throws Exception {
    List<String> lines = readLines(file);
    ObjectNode fact = MAPPER.createObjectNode();
    fact.put("id", id);
    fact.put("file", slash(file));
    fact.put("symbol", symbol);
    fact.put("role", role);
    ArrayNode parts = fact.putArray("fragments");
    int firstLine = -1;
    for (String fragment : fragments) {
      int found = -1;
      int occurrences = 0;
      for (int index = 0; index < lines.size(); index++) {
        if (lines.get(index).contains(fragment)) {
          occurrences++;
          if (found < 0) {
            found = index;
          }
        }
      }
      assertEquals(1, occurrences, "源码片段必须恰出现 1 次（防漂移、防歧义）: " + fragment + " @ " + slash(file));
      ObjectNode part = parts.addObject();
      part.put("line", found + 1);
      part.put("text", fragment);
      if (firstLine < 0) {
        firstLine = found + 1;
      }
    }
    fact.put("line", firstLine);
    fact.put("verified", true);
    return fact;
  }

  // ==================== 锚 md（≤40 行，纯 LF） ====================

  private static String buildAnchorMd(ObjectNode anchor, List<Event> events) {
    List<Event> zeroEvents = new ArrayList<>();
    List<Event> nonzeroEvents = new ArrayList<>();
    for (Event event : events) {
      if (event.delta() == 0d) {
        zeroEvents.add(event);
      } else {
        nonzeroEvents.add(event);
      }
    }
    ObjectNode summary = (ObjectNode) anchor.path("attribution").path("consistencySummary");
    int queries = summary.path("queries").asInt();
    int consistent = summary.path("consistent").asInt();
    int nonDegenerate = summary.path("nonDegenerate").asInt();
    double rMax = anchor.path("dataCheck").path("rDistribution").path("rMax").asDouble();
    double rMin = anchor.path("dataCheck").path("rDistribution").path("rMin").asDouble();
    double observedMax = 0d;
    double observedMin = Double.MAX_VALUE;
    for (Event event : events) {
      observedMax = Math.max(observedMax, event.r());
      observedMin = Math.min(observedMin, event.r());
    }
    assertEquals(rMin, observedMin, 0d, "md 的 rMin 必须与重算一致");
    assertEquals(rMax, observedMax, 0d, "md 的 rMax 必须与重算一致");

    StringBuilder md = new StringBuilder(4096);
    md.append("# near-dup-oq1-attribution-v1 · OQ1 归因：证得到的 no-op，证不到的 min-max 末位\n");
    md.append("\n");
    md.append(
        "- 卡 34（零外呼归因卡）：输入仅卡 32 冻结锚 `near-dup-band-entry-v1.json` + 卡 33 锚 `near-dup-window-coupling-v1.json`（只读）与三处源码字面；不启动 Spring、不 instance provider、不设任何 `*_REAL`。\n");
    md.append("- 问题（卡 33 记 OQ1）：12 条被标记降权的事件里 **4 条** `fusedA == fusedB`（δ = 0），被标记却一分没动。\n");
    md.append("\n");
    md.append("## 结论 1 · PROVEN：δ = 0.30(1−w)r 且 δ = 0 ⇔ r = 0 ⇒ 降权对 r = 0 的候选是结构空操作\n");
    md.append("\n");
    md.append(
        "- 机制三处（片段一字不差，守卫逐处 grep）：`DefaultWeightedReranker#normalizeScores` min-max（min → 0 / `max ≤ 0` → 全 0 / `max == min` → 全 1）；`RetrievalConfigResolver#mergeRoute` 的 `rerankScore × routeWeight` 累加且 `DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30`；`ImageSimilarityGate#downWeightNearDuplicates` 的 `rerankScore × weight`。\n");
    md.append(
        "- 恒等式：`δ = fusedB − fusedA = 0.30 × (1 − w) × r`；因 `0.30 > 0`、`w ∈ {0.75, 0.60, 0.50} < 1` ⇒ `δ = 0 ⇔ r = 0`。\n");
    md.append(
        String.format(
            Locale.ROOT,
            "- 实测逐条（%d/%d，禁抽样）：`r = δ / (0.30 × (1 − w))` 全部落在 [%s, %s]；%d 条零位移事件的 `r` 恰为 0，逐条算式 `0.30 × (1 − w) × 0 = 0`。\n",
            events.size(),
            events.size(),
            fourDecimals(observedMin),
            fourDecimals(observedMax),
            zeroEvents.size()));
    md.append("- 该结论**不依赖缺失字段**：δ 与 w 都是卡 32 锚实测，r 由恒等式反算（D2：不拿 `cos` 当 `r`，量纲不同）。\n");
    md.append("\n");
    md.append("## 结论 2 · CONSISTENT-NOT-PROVEN：r = 0 的成因很可能是 min-max 把末位归 0\n");
    md.append("\n");
    md.append(
        "- 源码上确有这条路径（末位 → 归一化 0.0），且 `r = 0.60 × normVec + 0.40 × normBm25` 要求两条分支同时为 0（两权重为正）⇒ **在 `max > 0` 且非退化列表的前提下**，该候选必须是两条分支的末位。\n");
    md.append(
        "- **退化分支必须一起读**：`normalizeScores` 在 `max ≤ 0` 时把**全体**归一化为 `0.0`，此时任何候选都 `r = 0`，与「末位」无关；`max == min` 时则全体为 `1.0`。锚 `attribution.consistencySummary.degenerate` 记的正是这类查询，本句**只在非退化查询上成立**。\n");
    md.append(
        String.format(
            Locale.ROOT,
            "- 一致性检查（%d/%d 无矛盾）：q0/q1/q2 里「反算 r 最大者」与「cos 最大者」是同一候选（`@crop-04`）；q3/q4/q5 只有 1 条被标记候选，检查退化为恒真（非退化 %d 条 / 退化 %d 条）。\n",
            consistent,
            queries,
            nonDegenerate,
            queries - nonDegenerate));
    md.append(
        "- 辅助观察（同样不升级）：4 条 `r = 0` 的候选在各自查询 cos 有值的行里均为 cos 最小；q0–q2 的 r 次序与 cos 次序完全一致（`@crop-04` > `@crop-02` > `@crop-06`）。\n");
    md.append(
        "- **这两句不是结论**：`cos` 只是图像向量相似度，`r` 是重排分；没有 `r`、`routeListSize` 就分不清「末位归 0」与「该候选只有 1 条 / 根本不在图像路」。\n");
    md.append("\n");
    md.append("## 结论 3 · MISSING-EVIDENCE：差的字段\n");
    md.append("\n");
    md.append(
        "- `imageRerankScoreA / imageRerankScoreB`（`r` 本身）、`routeListSize`、`bm25` 归一值、`rag.retrieval.rerank.vector-weight / bm25-weight` 实测值（`r ∈ [0,1]` 以两者按默认 0.60/0.40 求和为 1 为前提）。\n");
    md.append("\n");
    md.append("## 判定 · NEEDS-INSTRUMENTED-RUN（埋点方案未执行）\n");
    md.append("\n");
    md.append(
        "- `verdict = NEEDS-INSTRUMENTED-RUN`：no-op 恒等式已证，但 OQ1 问的是「r 为什么是 0」，其因果不在锚内，故不取 `MECHANISM-CONFIRMED`。\n");
    md.append(
        "- 最小字段集（4 项）：`imageRerankScore` / `imageRankInRouteList` / `routeListSize` / `fusionImageContribution`；预算 6 摄取 × 3 + 6 查询 × 2 = **30 外呼**。\n");
    md.append(
        "- 为什么不能再省：少 `imageRerankScore` 分不清「r = 0」与「r ≠ 0 但融合抵消」；少 `imageRankInRouteList` / `routeListSize` 分不清「末位归 0」与「列表退化」；少 `fusionImageContribution` 仍要反解 δ。\n");
    md.append(
        "- **本方案未执行、不得据其声明结论**（D5）。细节锚：`docs/rag-quality/near-dup-oq1-attribution-v1.json`（默认按本次全量重算**规范化字节**比对：JSON 走树规范化、md 走真字节，均剔除顶层 `runtime`）。\n");
    String text = md.toString();
    assertTrue(!text.contains("\r"), "锚 md 必须纯 LF");
    assertFalse(text.endsWith("\n\n"), "锚 md 不得以空行结尾");
    assertTrue(text.lines().count() <= 40, "锚 md 必须 ≤40 行（实测 " + text.lines().count() + "）");
    return text;
  }

  // ==================== 读写锚（写模式 / 语义比对：JSON 走规范化字节、md 走真字节，均剔除 runtime） ====================

  private static void writeOrCompareJson(Path path, ObjectNode node) throws Exception {
    byte[] expected = canonicalBytes(node);
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "锚 JSON 必须纯 LF");
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论锚缺失，先以 -Dnear.dup.oq1.attribution.write=true 生成一次: " + path.toAbsolutePath());
    JsonNode onDisk = readJson(path);
    assertArrayEquals(
        canonicalBytes(withoutRuntime(node)),
        canonicalBytes(withoutRuntime(onDisk)),
        "锚与本次全量重算规范化字节不一致（JSON 树规范化后比较、剔除顶层 runtime）：锚被改动或源码/链路漂移: " + path);
    System.out.println("[near-dup-oq1] 默认复跑规范化字节一致（JSON 树规范化，剔除顶层 runtime）：" + path);
  }

  private static void writeOrCompareBytes(Path path, byte[] expected) throws Exception {
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "锚 md 必须纯 LF");
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论 md 缺失，先以 -Dnear.dup.oq1.attribution.write=true 生成一次: " + path.toAbsolutePath());
    assertArrayEquals(expected, Files.readAllBytes(path), "锚 md 与本次生成逐字节不一致: " + path);
    System.out.println("[near-dup-oq1] 默认复跑逐字节一致（md）：" + path);
  }

  private static void writeAnchor(Path path, byte[] content) throws Exception {
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, content);
    System.out.println("[near-dup-oq1] 锚已写入（write 模式）: " + path);
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

  private static List<String> readLines(Path path) throws Exception {
    assertTrue(Files.exists(path), "源码不存在（R1 锚不到即为红）: " + path.toAbsolutePath());
    return Files.readAllLines(path, StandardCharsets.UTF_8);
  }

  private static String sha256(Path path) throws Exception {
    assertTrue(Files.exists(path), "上游锚不存在: " + path.toAbsolutePath());
    return RealPdfAssets.sha256(Files.readAllBytes(path));
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String twoDecimals(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private static String fourDecimals(double value) {
    return String.format(Locale.ROOT, "%.4f", value);
  }

  /** 单条降权事件（字段名与锚内 {@code dataCheck.rByEvent[]} 一一对应，板规 28）。 */
  private record Event(
      int index,
      int queryIndex,
      String variantKey,
      String chunkId,
      String band,
      double w,
      double window,
      double cos,
      double fusedA,
      double fusedB,
      double delta,
      double r) {}
}
