package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 34 · near-dup-oq1-attribution 守卫（零外呼；判据 R0–R8 见 {@code
 * openspec/changes/near-dup-oq1-attribution/proposal.md}）。
 *
 * <p>本类只读卡 32/33 的冻结锚（不得改动一字）、结论锚 {@code docs/rag-quality/near-dup-oq1-attribution-v1.json} + 同名
 * md、以及三处生产源码字面。它不生成任何文件、不跑检索、 不启动 Spring 上下文。
 *
 * <ul>
 *   <li><b>R1</b>：三处代码事实逐处 grep（片段一字不差、行号实算、恰出现 1 次）；{@code DEFAULT_IMAGE_ROUTE_WEIGHT} 字面 = 0.30；
 *   <li><b>R2</b>：逐条反算 {@code r = δ / (0.30 × (1 − w))}，断言 {@code r ∈ [0,1]} 与 {@code δ = 0 ⇔ r =
 *       0}；
 *   <li><b>R3</b>：4 条零位移事件的空操作算式逐条复算；
 *   <li><b>R4/R5</b>：三档齐备且互不越级、一致性检查不升级、{@code verdict} 取值合法；
 *   <li><b>R6</b>：上游两锚 SHA-256 实算、锚纯 LF、md ≤40 行、埋点方案 {@code executed = false}；
 *   <li><b>R0</b>：静态扫描分析类源码，禁 Spring / 禁真 provider / 禁 {@code *_REAL} opt-in。
 * </ul>
 */
class NearDupOq1AttributionGuardTest {

  private static final Path BAND_ENTRY =
      Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  private static final Path WINDOW_COUPLING =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.json");

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-oq1-attribution-v1.json");

  private static final Path REPORT_MD =
      Path.of("docs", "rag-quality", "near-dup-oq1-attribution-v1.md");

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

  private static final Path ANALYSIS_TEST_SRC =
      Path.of(
          "src",
          "test",
          "java",
          "com",
          "slz",
          "crm",
          "quality",
          "NearDupOq1AttributionAnalysisTest.java");

  private static final double EPS = 1e-12d;

  private static final double IMAGE_ROUTE_WEIGHT = 0.30d;

  /** R5 三值判定（闭合集，不许第四值）。 */
  private static final List<String> ALLOWED_VERDICTS =
      List.of("MECHANISM-CONFIRMED", "INCONCLUSIVE", "NEEDS-INSTRUMENTED-RUN");

  /** R6 最小字段集（四项，少一项就不足以区分「min-max 末位」与「列表退化 / 未进图像路」）。 */
  private static final List<String> REQUIRED_PLAN_FIELDS =
      List.of(
          "imageRerankScore", "imageRankInRouteList", "routeListSize", "fusionImageContribution");

  /** R0 静态扫描禁式（分析类源码里出现即判红：这些符号只可能来自真外呼 / Spring 上下文）。 */
  private static final List<String> FORBIDDEN_OUTCALL_TOKENS =
      List.of(
          "SpringBootTest",
          "Testcontainers",
          "RAG_BENCHMARK_REAL",
          "RAG_INGEST_REAL",
          "System.setProperty",
          "RestTemplate",
          "WebClient");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode bandEntry;

  private static JsonNode coupling;

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchors() throws Exception {
    bandEntry = readJson(BAND_ENTRY);
    coupling = readJson(WINDOW_COUPLING);
    anchor = readJson(ANCHOR_JSON);
  }

  @Test
  @DisplayName("R1 · 三处代码事实仍存在（片段一字不差、行号实算、DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30）")
  void codeFactsStillPresent() throws Exception {
    JsonNode codeFacts = anchor.path("mechanism").path("codeFacts");
    assertEquals(3, codeFacts.size(), "R1 必须恰三处代码事实");
    List<String> files = new ArrayList<>();
    for (JsonNode fact : codeFacts) {
      Path file = Path.of(fact.path("file").asText());
      files.add(fact.path("file").asText());
      assertTrue(Files.exists(file), "R1 代码事实的文件不存在（上游漂移）: " + file);
      assertTrue(fact.path("verified").asBoolean(), "代码事实必须 verified = true: " + fact.path("id"));
      assertFalse(fact.path("symbol").asText().isBlank(), "代码事实必须写 symbol");
      JsonNode fragments = fact.path("fragments");
      assertTrue(fragments.size() >= 3, "每处代码事实至少 3 个片段（含分支/累加/乘权重）: " + fact.path("id"));
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (JsonNode fragment : fragments) {
        String text = fragment.path("text").asText();
        assertFalse(text.isBlank(), "片段不得为空: " + fact.path("id"));
        List<Integer> hits = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
          if (lines.get(index).contains(text)) {
            hits.add(index + 1);
          }
        }
        assertEquals(1, hits.size(), "片段必须恰出现 1 次（否则锚失效、守卫无法钉死）: " + text);
        assertEquals(
            hits.get(0).intValue(),
            fragment.path("line").asInt(),
            "锚内行号与源码实算行号不一致（行号漂移，需重跑写模式并复核）: " + text);
      }
    }
    assertTrue(
        files.contains(
            "src/main/java/com/slz/crm/knowledge/retrieval/DefaultWeightedReranker.java"),
        "缺 normalizeScores 所在文件");
    assertTrue(
        files.contains(
            "src/main/java/com/slz/crm/knowledge/retrieval/RetrievalConfigResolver.java"),
        "缺 mergeRoute 所在文件");
    assertTrue(
        files.contains("src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java"),
        "缺 downWeightNearDuplicates 所在文件");

    String reranker = Files.readString(RERANKER_SRC, StandardCharsets.UTF_8);
    assertTrue(
        reranker.contains("result = java.util.Collections.nCopies(scores.size(), 0.0);"),
        "normalizeScores 的「最小值 → 0」分支已消失（R1 第一处被推翻）");
    assertTrue(
        reranker.contains("result = java.util.Collections.nCopies(scores.size(), 1.0);"),
        "normalizeScores 的 max == min → 全 1 分支已消失");
    assertTrue(reranker.contains("(score - min) / (max - min)"), "normalizeScores 的 min-max 缩放已消失");

    String resolver = Files.readString(RESOLVER_SRC, StandardCharsets.UTF_8);
    assertTrue(
        resolver.contains("private static final double DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30;"),
        "DEFAULT_IMAGE_ROUTE_WEIGHT 字面必须是 0.30（R1 第二处）");
    assertTrue(
        resolver.contains("candidate.rerankScore() * routeWeight"),
        "mergeRoute 的 rerankScore × routeWeight 已消失");
    assertEquals(
        IMAGE_ROUTE_WEIGHT,
        anchor.path("mechanism").path("imageRouteWeight").asDouble(),
        0d,
        "锚内 imageRouteWeight 必须是 0.30");

    String gate = Files.readString(GATE_SRC, StandardCharsets.UTF_8);
    assertTrue(gate.contains("downWeightNearDuplicates("), "downWeightNearDuplicates 已消失（R1 第三处）");
    assertTrue(
        gate.contains("candidate.rerankScore() * weight"),
        "downWeightNearDuplicates 的 rerankScore × weight 已消失");
  }

  @Test
  @DisplayName("R2 · 逐条反算 r：r ∈ [0,1] 且 δ = 0 ⇔ r = 0（12/12，禁抽样）")
  void identityHoldsPerEvent() {
    JsonNode entries = bandEntry.path("windowCheck").path("entries");
    JsonNode rByEvent = anchor.path("dataCheck").path("rByEvent");
    assertEquals(12, entries.size(), "卡 32 锚 windowCheck.entries 必须 12 条");
    assertEquals(entries.size(), rByEvent.size(), "rByEvent 必须逐条覆盖（禁抽样）");
    int zeroCount = 0;
    for (int index = 0; index < entries.size(); index++) {
      JsonNode entry = entries.get(index);
      JsonNode row = rByEvent.get(index);
      assertEquals(index, row.path("index").asInt(), "rByEvent 顺序必须与卡 32 事件一致");
      assertEquals(
          entry.path("queryIndex").asInt(),
          row.path("queryIndex").asInt(),
          "queryIndex 不一致: index=" + index);
      assertEquals(
          entry.path("variantKey").asText(),
          row.path("variantKey").asText(),
          "variantKey 不一致: index=" + index);
      assertEquals(
          entry.path("chunkId").asText(),
          row.path("chunkId").asText(),
          "chunkId 不一致: index=" + index);

      double weight = entry.path("weight").asDouble();
      double delta = entry.path("delta").asDouble();
      assertEquals(
          weight, row.path("w").asDouble(), 0d, "w 必须取自卡 32 锚 weight: " + entry.path("variantKey"));
      assertEquals(
          delta,
          row.path("delta").asDouble(),
          0d,
          "delta 必须取自卡 32 锚（D2 不反解）: " + entry.path("variantKey"));
      assertEquals(
          entry.path("fusedB").asDouble() - entry.path("fusedA").asDouble(),
          delta,
          EPS,
          "δ 必须等于 fusedB − fusedA: " + entry.path("variantKey"));
      assertEquals(
          IMAGE_ROUTE_WEIGHT * (1d - weight),
          entry.path("window").asDouble(),
          EPS,
          "window 必须等于 0.30 × (1 − w): " + entry.path("variantKey"));

      double factor = IMAGE_ROUTE_WEIGHT * (1d - weight);
      double expectedR = delta / factor;
      assertEquals(
          expectedR,
          row.path("r").asDouble(),
          EPS,
          "r 必须等于 δ / (0.30 × (1 − w)): " + entry.path("variantKey"));
      assertTrue(
          expectedR >= 0d && expectedR <= 1d + EPS,
          "r 必须落在 [0,1]: " + entry.path("variantKey") + " r=" + expectedR);
      assertEquals(
          delta,
          factor * expectedR,
          EPS,
          "δ = 0.30 × (1 − w) × r 必须逐条还原: " + entry.path("variantKey"));
      assertEquals(
          delta == 0d, expectedR == 0d, "δ = 0 ⇔ r = 0 必须逐条成立: " + entry.path("variantKey"));
      if (delta == 0d) {
        zeroCount++;
        assertEquals(
            0d, row.path("r").asDouble(), 0d, "零位移事件的 r 必须恰为 0: " + entry.path("variantKey"));
      }
    }
    assertEquals(4, zeroCount, "零位移事件必须恰 4 条（卡 32/33 锚）");
    JsonNode distribution = anchor.path("dataCheck").path("rDistribution");
    assertEquals(
        zeroCount,
        distribution.path("zeroDisplacementCount").asInt(),
        "zeroDisplacementCount 必须是 4");
    assertEquals(
        entries.size() - zeroCount,
        distribution.path("nonzeroDisplacementCount").asInt(),
        "nonzeroDisplacementCount 必须是 8");
    assertEquals(
        8, distribution.path("nonzeroRValues").size(), "非零 r 必须逐个列出 8 条（与卡 33 displacedCount 对账）");
    assertEquals(4, distribution.path("zeroRValues").size(), "零位移 r 必须逐条列出 4 条");
    assertTrue(distribution.path("rMin").asDouble() >= 0d, "rMin 必须 ≥ 0");
    assertTrue(distribution.path("rMax").asDouble() <= 1d + EPS, "rMax 必须 ≤ 1");
  }

  @Test
  @DisplayName("R3 · 4 条零位移事件的空操作算式逐条复算（0.30 × (1 − w) × 0 = 0）")
  void noOpProofPerZeroEvent() {
    JsonNode entries = bandEntry.path("windowCheck").path("entries");
    JsonNode proof = anchor.path("dataCheck").path("noOpProof");
    int expectedZero = 0;
    for (JsonNode entry : entries) {
      if (entry.path("delta").asDouble() == 0d) {
        expectedZero++;
      }
    }
    assertEquals(4, expectedZero, "前置：卡 32 锚里零位移事件必须恰 4 条");
    assertEquals(expectedZero, proof.size(), "noOpProof 必须逐条覆盖零位移事件");
    for (JsonNode item : proof) {
      double weight = item.path("w").asDouble();
      double r = item.path("r").asDouble();
      assertEquals(0d, r, 0d, "空操作事件的 r 必须恰为 0: " + item.path("variantKey"));
      assertEquals(
          0d, item.path("product").asDouble(), 0d, "算式结果必须恰为 0: " + item.path("variantKey"));
      assertEquals(
          "NEAR_DUPLICATE",
          item.path("tier").asText(),
          "tier 必须是 NEAR_DUPLICATE: " + item.path("variantKey"));
      assertTrue(
          item.path("downWeighted").asBoolean(),
          "downWeighted 必须为 true（标记了但无贡献）: " + item.path("variantKey"));
      assertTrue(
          item.path("markedAndZeroDeltaCoexist").asBoolean(),
          "必须显式记「标记与 δ = 0 并存」: " + item.path("variantKey"));
      assertEquals(
          IMAGE_ROUTE_WEIGHT * (1d - weight),
          item.path("factor").asDouble(),
          EPS,
          "factor 必须是 0.30 × (1 − w): " + item.path("variantKey"));
      String expectedComputation = String.format(Locale.ROOT, "0.30 × (1 − %.2f) × 0 = 0", weight);
      assertEquals(
          expectedComputation,
          item.path("computation").asText(),
          "算式必须是一字不差的 0.30 × (1 − w) × 0 = 0: " + item.path("variantKey"));
      assertEquals(
          0d, IMAGE_ROUTE_WEIGHT * (1d - weight) * r, 0d, "复算乘积必须恰为 0: " + item.path("variantKey"));
    }
    assertFalse(
        anchor.path("dataCheck").path("noOpStatement").asText().isBlank(),
        "noOpStatement 必须写明结构空操作");
    assertTrue(
        anchor.path("dataCheck").path("noOpStatement").asText().contains("结构空操作"),
        "noOpStatement 必须点名「结构空操作」");
  }

  @Test
  @DisplayName("R4/R5 · 三档结论齐备且不越级 + 一致性检查不升级")
  void claimClassThreeTiersAndConsistency() {
    JsonNode claimClass = anchor.path("attribution").path("claimClass");
    JsonNode proven = claimClass.path("PROVEN");
    JsonNode consistent = claimClass.path("CONSISTENT-NOT-PROVEN");
    JsonNode missing = claimClass.path("MISSING-EVIDENCE");
    assertTrue(proven.isArray() && proven.size() >= 3, "PROVEN 至少 3 条（R1 机制 / R2 恒等式 / R3 空操作）");
    assertTrue(consistent.isArray() && consistent.size() >= 1, "CONSISTENT-NOT-PROVEN 必须显式列出未证解释");
    assertTrue(
        missing.isArray() && missing.size() >= 3,
        "MISSING-EVIDENCE 至少 3 条（r / routeListSize / bm25）");

    String consistentText = consistent.toString();
    assertTrue(consistentText.contains("min-max"), "未证解释必须点名 min-max（r = 0 的候选成因）");
    assertTrue(consistentText.contains("不得升格"), "未证解释必须写明「不得升格为结论」");
    assertTrue(consistentText.contains("cos"), "未证解释必须写清 cos 不是 r（量纲纪律）");

    String missingText = missing.toString();
    assertTrue(
        missingText.contains("imageRerankScore"), "MISSING-EVIDENCE 必须点名 r 本身（imageRerankScore）");
    assertTrue(missingText.contains("routeListSize"), "MISSING-EVIDENCE 必须点名 routeListSize");
    assertTrue(missingText.contains("bm25"), "MISSING-EVIDENCE 必须点名 bm25 归一值");

    List<String> ids = new ArrayList<>();
    for (JsonNode item : claimClass.path("PROVEN")) {
      ids.add(item.path("id").asText());
    }
    for (JsonNode item : claimClass.path("CONSISTENT-NOT-PROVEN")) {
      ids.add(item.path("id").asText());
    }
    for (JsonNode item : claimClass.path("MISSING-EVIDENCE")) {
      ids.add(item.path("id").asText());
    }
    assertEquals(ids.size(), ids.stream().distinct().count(), "三档内的结论条目 id 不得重复");

    JsonNode checks = anchor.path("attribution").path("consistencyCheck");
    assertEquals(6, checks.size(), "一致性检查必须逐查询覆盖 6 个查询");
    int counterExamples = 0;
    int sameCount = 0;
    for (JsonNode check : checks) {
      boolean same = check.path("sameCandidate").asBoolean();
      String status = check.path("status").asText();
      if (same) {
        sameCount++;
      } else {
        counterExamples++;
      }
      assertEquals(same ? "CONSISTENT" : "COUNTER-EXAMPLE", status, "status 必须与 sameCandidate 一致");
      assertTrue(check.path("markedCount").asInt() >= 1, "每个查询至少 1 条被标记候选");
      assertFalse(check.path("rMaxVariantKey").asText().isBlank(), "必须记 r 最大者的身份");
      assertFalse(check.path("cosMaxVariantKey").asText().isBlank(), "必须记 cos 最大者的身份");
    }
    JsonNode summary = anchor.path("attribution").path("consistencySummary");
    assertEquals(
        checks.size(), summary.path("queries").asInt(), "consistencySummary.queries 必须等于 6");
    assertEquals(sameCount, summary.path("consistent").asInt(), "consistent 计数必须与逐条一致");
    assertEquals(
        counterExamples, summary.path("counterExamples").asInt(), "counterExamples 计数必须与逐条一致");
    assertFalse(summary.path("upgraded").asBoolean(), "一致性检查不得升级为证明（upgraded 必须 false）");
    assertEquals(
        checks.size(),
        summary.path("nonDegenerate").asInt() + summary.path("degenerate").asInt(),
        "退化 + 非退化必须覆盖全部查询");

    JsonNode observation = anchor.path("attribution").path("routeLevelCosObservation");
    assertTrue(
        observation.path("zeroRIsMinCosAmongRowsWithCos").asBoolean(),
        "辅助观察：r = 0 的候选应是 cos 有值行里的最小者");
    assertEquals(4, observation.path("checkedZeroREvents").asInt(), "辅助观察必须覆盖 4 条零位移事件");
  }

  @Test
  @DisplayName("R5/R6 · verdict 取值合法 + 埋点方案 executed = false（30 外呼预算、四项最小字段）")
  void verdictAndInstrumentedPlan() {
    String verdict = anchor.path("verdict").asText();
    assertTrue(ALLOWED_VERDICTS.contains(verdict), "verdict 只能取三值之一，实测: " + verdict);
    assertFalse(anchor.path("verdictReason").asText().isBlank(), "必须附 verdictReason");

    // 反向断言（复核 MINOR-2）：PROVEN 档不得出现「未证解释」，否则三档边界失守
    String provenText = anchor.path("attribution").path("claimClass").path("PROVEN").toString();
    for (String banned : java.util.List.of("末位", "min-max 把", "归 0 造成", "成因是")) {
      assertFalse(
          provenText.contains(banned), "PROVEN 档不得含未证解释（三档边界）：命中禁式「" + banned + "」⇒ " + provenText);
    }

    if ("NEEDS-INSTRUMENTED-RUN".equals(verdict)) {
      JsonNode plan = anchor.path("instrumentedPlan");
      assertFalse(plan.isMissingNode(), "NEEDS-INSTRUMENTED-RUN 必须交付 instrumentedPlan（R6）");
      assertFalse(plan.path("executed").asBoolean(), "埋点方案必须 executed = false（本卡不执行，D5）");
      assertEquals(
          "PROPOSED-NOT-EXECUTED", plan.path("status").asText(), "埋点方案状态必须是 PROPOSED-NOT-EXECUTED");
      JsonNode fields = plan.path("minimalFields");
      assertEquals(REQUIRED_PLAN_FIELDS.size(), fields.size(), "最小字段集必须恰 4 项");
      for (int index = 0; index < REQUIRED_PLAN_FIELDS.size(); index++) {
        assertEquals(
            REQUIRED_PLAN_FIELDS.get(index),
            fields.get(index).path("field").asText(),
            "最小字段集第 " + index + " 项不符");
        assertFalse(fields.get(index).path("where").asText().isBlank(), "每个字段必须写埋点位置");
        assertFalse(fields.get(index).path("why").asText().isBlank(), "每个字段必须写为什么需要");
      }
      JsonNode budget = plan.path("callBudget");
      assertEquals(30, budget.path("total").asInt(), "重跑预算必须是 30 外呼（6 摄取 × 3 + 6 查询 × 2）");
      assertEquals("6 摄取 × 3 + 6 查询 × 2 = 30", budget.path("formula").asText(), "预算算式必须一字不差");
      assertTrue(plan.path("whyCannotShrinkFurther").size() >= 3, "必须写清「为什么不能再省」");
      assertTrue(plan.path("note").asText().contains("未执行"), "埋点方案必须明写未执行");
      assertTrue(
          anchor.path("attribution").path("claimClass").path("CONSISTENT-NOT-PROVEN").size() >= 1,
          "落 NEEDS-INSTRUMENTED-RUN 时一致性档不得为空（否则等于把未证写成已证）");
      assertTrue(
          anchor.path("attribution").path("claimClass").path("MISSING-EVIDENCE").size() >= 3,
          "落 NEEDS-INSTRUMENTED-RUN 时缺失档必须列全");
    }
  }

  @Test
  @DisplayName("R6/R7 · 上游两锚 SHA-256 实算 + 锚纯 LF + md ≤40 行 + 零外呼 runtime")
  void upstreamAnchorsAndReportBoundaries() throws Exception {
    JsonNode upstream = anchor.path("upstreamAnchors");
    for (String key : List.of("bandEntry", "windowCoupling")) {
      JsonNode entry = upstream.path(key);
      Path file = Path.of(entry.path("path").asText());
      assertTrue(Files.exists(file), "上游锚不存在: " + file);
      assertEquals(
          RealPdfAssets.sha256(Files.readAllBytes(file)),
          entry.path("sha256").asText(),
          "上游锚 SHA-256 实算不一致（冻结锚被改动）: " + key);
      assertTrue(entry.path("matchesUpstream").asBoolean(), "matchesUpstream 必须为 true: " + key);
      assertEquals(
          "READ-ONLY-INPUT", entry.path("role").asText(), "上游锚角色必须是 READ-ONLY-INPUT: " + key);
    }
    assertEquals(
        coupling.path("openQuestions").get(0).path("status").asText(),
        upstream.path("couplingOq1Status").asText(),
        "锚内记录的 OQ1 状态必须与卡 33 锚一致");
    assertEquals(
        "INCONCLUSIVE", upstream.path("couplingOq1Status").asText(), "本卡回答的 OQ1 必须仍是 INCONCLUSIVE");
    assertTrue(upstream.path("couplingOq1MissingFieldCount").asInt() >= 3, "卡 33 记的缺失字段至少 3 项");

    byte[] anchorBytes = Files.readAllBytes(ANCHOR_JSON);
    String anchorText = new String(anchorBytes, StandardCharsets.UTF_8);
    assertFalse(anchorText.contains("\r"), "结论锚必须纯 LF（实测含 CR）");
    assertTrue(anchorText.endsWith("}\n"), "结论锚必须以单尾换行结束");
    for (String required :
        List.of(
            "mechanism",
            "dataCheck",
            "attribution",
            "verdict",
            "verdictReason",
            "instrumentedPlan",
            "runtime",
            "upstreamAnchors")) {
      assertFalse(anchor.path(required).isMissingNode(), "结论锚缺顶层节: " + required);
    }
    assertEquals(
        0,
        anchor.path("runtime").path("externalCalls").asInt(),
        "本卡必须零外呼（runtime.externalCalls = 0）");
    assertEquals(
        "runtime",
        anchor.path("runtime").path("excludedFromReplay").get(0).asText(),
        "runtime 是默认复跑剔除项（白名单只许 runtime）");

    assertTrue(Files.exists(REPORT_MD), "结论 md 必须随锚交付: " + REPORT_MD);
    String md = Files.readString(REPORT_MD, StandardCharsets.UTF_8);
    long mdLines = md.lines().count();
    assertTrue(mdLines <= 40, "结论 md 必须 ≤40 行（实测 " + mdLines + "）");
    assertFalse(md.contains("\r"), "结论 md 必须纯 LF");
    assertFalse(md.endsWith("\n\n"), "结论 md 不得以空行结尾");
    assertTrue(md.contains("PROVEN"), "md 必须写出 PROVEN 档");
    assertTrue(md.contains("CONSISTENT-NOT-PROVEN"), "md 必须写出 CONSISTENT-NOT-PROVEN 档");
    assertTrue(md.contains("MISSING-EVIDENCE"), "md 必须写出 MISSING-EVIDENCE 档");
    assertTrue(md.contains("NEEDS-INSTRUMENTED-RUN"), "md 必须写出最终判定");
    assertTrue(md.contains("结构空操作"), "md 必须写清「降权对 r = 0 的候选是结构空操作」");
    assertTrue(md.contains("min-max"), "md 必须点名 min-max");
    assertTrue(md.contains("30 外呼"), "md 必须写出 30 外呼预算");
    assertTrue(md.contains("未执行"), "md 必须明写埋点方案未执行");
  }

  @Test
  @DisplayName("R0 · 零外呼静态扫描：分析类源码不含 Spring / 真 provider / *_REAL opt-in")
  void zeroOutcallStaticScan() throws Exception {
    assertTrue(Files.exists(ANALYSIS_TEST_SRC), "分析类源码不存在: " + ANALYSIS_TEST_SRC);
    String source = Files.readString(ANALYSIS_TEST_SRC, StandardCharsets.UTF_8);
    for (String token : FORBIDDEN_OUTCALL_TOKENS) {
      assertFalse(source.contains(token), "分析类出现外呼/Spring 符号（R0 零外呼被破坏）: " + token);
    }
    assertTrue(
        source.contains("docs/rag-quality/near-dup-band-entry-v1.json")
            || source.contains("\"near-dup-band-entry-v1.json\""),
        "分析类必须只读卡 32 锚");
    assertFalse(source.contains("\"http://") && source.contains("\"https://"), "分析类不得含外呼 URL");
    assertTrue(Files.exists(BAND_ENTRY), "卡 32 锚必须存在");
    assertTrue(Files.exists(WINDOW_COUPLING), "卡 33 锚必须存在");
  }

  // ==================== 复算帮手（本类独立实现，不复用分析类） ====================

  private static JsonNode readJson(Path path) throws Exception {
    assertTrue(Files.exists(path), "文件不存在: " + path.toAbsolutePath());
    return MAPPER.readTree(Files.readAllBytes(path));
  }
}
