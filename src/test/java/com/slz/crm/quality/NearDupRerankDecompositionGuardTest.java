package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 35 守卫：把 {@code near-dup-rerank-decomposition-v1} 的每条判据钉成机械断言（只读锚，不重跑链路）。
 *
 * <ul>
 *   <li><b>R1</b>：12 条事件的 {@code r} 必须能由卡 32 锚 {@code fusedB − fusedA} 反算复核（1e-9），且与本次复算 的 {@code
 *       wv·normVec + wb·normBm25} 同值（1e-12）；
 *   <li><b>R2</b>：分桶必须与两分支「归一值全同 ⇒ 退化」一致，{@code r = 0 ⟺ 两分支归一值同时为 0} 逐行成立，4 条零位移事件与 routes 行必须一一对应；
 *   <li><b>R3</b>：{@code verdict} 三选一、其值必须与锚内数据的机械规则重算结果一致、三值可达路径齐备；
 *   <li><b>R4</b>：埋点方案 {@code executed = false}，且未执行结论不得被写成已发生；
 *   <li><b>R5</b>：政策页两栏并列、不给倾向值（禁用词双面断言：禁用词全无 <b>且</b> 两栏关键数字与分母在位）；
 *   <li><b>R6/R7</b>：上游三锚 SHA 实算一致、md ≤40 行、两锚纯 LF、{@code externalCalls = 0}。
 * </ul>
 */
class NearDupRerankDecompositionGuardTest {

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-rerank-decomposition-v1.json");

  private static final Path ANCHOR_MD =
      Path.of("docs", "rag-quality", "near-dup-rerank-decomposition-v1.md");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final double RECONCILE_TOLERANCE = 1e-9d;

  private static final double IDENTITY_TOLERANCE = 1e-12d;

  private static final String NON_DEGENERATE = "NON-DEGENERATE";

  private static final String DEGENERATE_NOT_EVIDENCE = "DEGENERATE-NOT-EVIDENCE";

  private static final String PROVEN = "PROVEN";

  private static final String REFUTED = "REFUTED";

  private static final String UNDETERMINED = "UNDETERMINED";

  /** R5 禁用词（覆盖中文倾向词与英文 recommend 家族）。 */
  private static final List<String> FORBIDDEN_TENDENCY_WORDS =
      List.of("推荐", "建议改为", "应下调", "recommend");

  @Test
  @DisplayName("卡35 守卫：R1 逐条复算 / R2 分桶与等价式 / R3 三值可达 / R4 未执行 / R5 无倾向值 / 上游 SHA + md ≤40 行")
  void guardsDecompositionAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR_JSON), "缺结论锚: " + ANCHOR_JSON.toAbsolutePath());
    assertTrue(Files.exists(ANCHOR_MD), "缺结论 md: " + ANCHOR_MD.toAbsolutePath());
    byte[] jsonBytes = Files.readAllBytes(ANCHOR_JSON);
    byte[] mdBytes = Files.readAllBytes(ANCHOR_MD);
    JsonNode anchor = MAPPER.readTree(jsonBytes);
    String md = new String(mdBytes, StandardCharsets.UTF_8);

    assertNoCarriageReturn(jsonBytes, "锚 JSON");
    assertNoCarriageReturn(mdBytes, "锚 md");
    assertHeader(anchor);
    JsonNode bandEntry = assertUpstreamAnchors(anchor);
    assertWeights(anchor);
    assertReconciliation(anchor, bandEntry);
    assertDecomposition(anchor);
    assertBuckets(anchor);
    assertZeroDisplacementEvents(anchor);
    assertVerdict(anchor, md);
    assertInstrumentedPlan(anchor);
    assertFloorOptions(anchor, md);
    assertMdShape(md);
    assertNoExternalCalls(anchor);
  }

  // ==================== 头部与上游锚（R6/R7） ====================

  private static void assertHeader(JsonNode anchor) {
    assertEquals("near-dup-rerank-decomposition", anchor.path("card").asText(), "card 不符");
    assertEquals(
        "NEAR-DUP-RERANK-DECOMPOSITION-1.0",
        anchor.path("decisionVersion").asText(),
        "decisionVersion 不符");
    assertEquals("OWNER", anchor.path("decisionOwner").asText(), "决策权必须记在 OWNER");
  }

  /** 上游三锚必须逐字节可考：锚内记录的 sha256 必须等于当前文件实算值（卡 32/33/34 零字节变动的机读凭据）。 */
  private static JsonNode assertUpstreamAnchors(JsonNode anchor) throws Exception {
    JsonNode upstream = anchor.path("upstreamAnchors");
    List<String> names = List.of("bandEntry", "windowCoupling", "oq1Attribution");
    Map<String, JsonNode> files = new LinkedHashMap<>();
    for (String name : names) {
      JsonNode node = upstream.path(name);
      assertFalse(node.isMissingNode(), "缺上游锚登记: " + name);
      Path path = Path.of(node.path("path").asText());
      assertTrue(Files.exists(path), "上游锚不存在: " + path.toAbsolutePath());
      assertEquals(
          RealPdfAssets.sha256(Files.readAllBytes(path)),
          node.path("sha256").asText(),
          "上游锚字节已变（只读面被改）: " + path);
      assertTrue(node.path("readOnly").asBoolean(), "上游锚必须标 readOnly: " + name);
      files.put(name, MAPPER.readTree(Files.readAllBytes(path)));
    }
    assertEquals(
        "near-dup-band-entry-and-tier-fill",
        files.get("bandEntry").path("card").asText(),
        "bandEntry 锚 card 不符");
    return files.get("bandEntry");
  }

  // ==================== 权重（R1 的同源前提） ====================

  private static void assertWeights(JsonNode anchor) {
    JsonNode weights = anchor.path("weights");
    double vectorWeight = weights.path("vectorWeight").asDouble();
    double bm25Weight = weights.path("bm25Weight").asDouble();
    assertTrue(vectorWeight > 0d && bm25Weight > 0d, "两权重必须为正（否则 r = 0 的等价式不成立）");
    assertTrue(vectorWeight <= 1d && bm25Weight <= 1d, "两权重必须落在 (0,1]");
    assertEquals(
        weights.path("vectorWeightDefaultLiteral").asDouble(),
        vectorWeight,
        1e-12d,
        "实测 wv 必须等于生产默认字面（卡 32 矩阵未覆盖该键）");
    assertEquals(
        weights.path("bm25WeightDefaultLiteral").asDouble(),
        bm25Weight,
        1e-12d,
        "实测 wb 必须等于生产默认字面（卡 32 矩阵未覆盖该键）");
    // 负对照必须真的把配置值读出来（证明是实测而非抄字面）
    assertEquals(0.90d, weights.path("overriddenVector").asDouble(), 1e-12d, "负对照 wv 不符");
    assertEquals(0.10d, weights.path("overriddenBm25").asDouble(), 1e-12d, "负对照 wb 不符");
    assertEquals(4, weights.path("card32ConfigMatrixKeys").size(), "卡 32 矩阵键数必须 4");
    for (JsonNode key : weights.path("card32ConfigMatrixKeys")) {
      assertFalse(
          key.asText().startsWith("rag.retrieval.rerank.vector-weight")
              || key.asText().startsWith("rag.retrieval.rerank.bm25-weight"),
          "卡 32 矩阵不得覆盖两个权重键: " + key.asText());
    }
  }

  // ==================== R1 · 12 条事件逐条复算 ====================

  private static void assertReconciliation(JsonNode anchor, JsonNode bandEntry) {
    JsonNode reconciliation = anchor.path("reconciliation");
    assertEquals(12, reconciliation.size(), "被标记事件必须逐条复算 12 条（禁抽样）");
    Map<String, JsonNode> entries = new LinkedHashMap<>();
    for (JsonNode entry : bandEntry.path("windowCheck").path("entries")) {
      entries.put(
          entry.path("queryIndex").asInt() + "|" + entry.path("variantKey").asText(), entry);
    }
    assertEquals(12, entries.size(), "卡 32 锚 windowCheck.entries 必须 12 条");
    Map<String, JsonNode> routeByKey = new LinkedHashMap<>();
    for (JsonNode row : anchor.path("routes")) {
      routeByKey.put(row.path("queryIndex").asInt() + "|" + row.path("variantKey").asText(), row);
    }
    double vectorWeight = anchor.path("weights").path("vectorWeight").asDouble();
    double bm25Weight = anchor.path("weights").path("bm25Weight").asDouble();
    int consistent = 0;
    double maxAbsDiff = 0d;
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode row : reconciliation) {
      String key = row.path("queryIndex").asInt() + "|" + row.path("variantKey").asText();
      assertTrue(seen.add(key), "同一事件不得重复: " + key);
      JsonNode source = entries.get(key);
      assertTrue(source != null, "锚内事件必须能在卡 32 锚里找到: " + key);
      double weight = source.path("weight").asDouble();
      assertEquals(weight, row.path("w").asDouble(), 0d, "w 必须与卡 32 锚一致: " + key);
      double delta = source.path("fusedB").asDouble() - source.path("fusedA").asDouble();
      assertEquals(
          source.path("delta").asDouble(),
          delta,
          IDENTITY_TOLERANCE,
          "δ 必须等于 fusedB − fusedA: " + key);
      double deltaDerived = delta / (0.30d * (1d - weight));
      assertEquals(deltaDerived, row.path("deltaDerivedR").asDouble(), 1e-15d, "反算 r 不符: " + key);
      JsonNode route = routeByKey.get(key);
      assertTrue(route != null, "事件必须对应一条 route 行: " + key);
      assertEquals(
          route.path("r").asDouble(),
          row.path("recomputedR").asDouble(),
          0d,
          "复算 r 必须与 route 行同值: " + key);
      double absDiff = Math.abs(deltaDerived - route.path("r").asDouble());
      assertEquals(absDiff, row.path("absDiff").asDouble(), 1e-15d, "absDiff 不符: " + key);
      assertTrue(row.path("consistent").asBoolean(), "逐条必须一致: " + key);
      assertTrue(
          absDiff <= RECONCILE_TOLERANCE,
          "复算 r 与卡 32 反算 r 超容差 1e-9: " + key + " absDiff=" + absDiff);
      consistent++;
      maxAbsDiff = Math.max(maxAbsDiff, absDiff);
      // 恒等式：复算 r 必须等于 wv·normVec + wb·normBm25
      assertEquals(
          route.path("r").asDouble(),
          route.path("normVec").asDouble() * vectorWeight
              + route.path("normBm25").asDouble() * bm25Weight,
          IDENTITY_TOLERANCE,
          "r = wv·normVec + wb·normBm25 不成立: " + key);
    }
    JsonNode summary = anchor.path("reconciliationSummary");
    assertEquals(12, summary.path("total").asInt(), "summary.total 必须 12");
    assertEquals(consistent, summary.path("consistent").asInt(), "summary.consistent 必须等于实测一致条数");
    assertEquals(RECONCILE_TOLERANCE, summary.path("tolerance").asDouble(), 0d, "容差必须记 1e-9");
    assertEquals(
        maxAbsDiff, summary.path("maxAbsDiff").asDouble(), 1e-15d, "maxAbsDiff 必须等于实测最大偏差");
  }

  // ==================== R2 · 分解逐行 + 分桶 + 等价式 ====================

  private static void assertDecomposition(JsonNode anchor) {
    JsonNode routes = anchor.path("routes");
    assertEquals(15, routes.size(), "图像路候选行必须 15 条（6 查询：3/3/3/2/2/2）");
    double vectorWeight = anchor.path("weights").path("vectorWeight").asDouble();
    double bm25Weight = anchor.path("weights").path("bm25Weight").asDouble();
    int checked = 0;
    for (JsonNode row : routes) {
      double normVec = row.path("normVec").asDouble();
      double normBm25 = row.path("normBm25").asDouble();
      double r = row.path("r").asDouble();
      assertTrue(normVec >= 0d && normVec <= 1d, "normVec 必须落在 [0,1]");
      assertTrue(normBm25 >= 0d && normBm25 <= 1d, "normBm25 必须落在 [0,1]");
      assertEquals(
          r,
          normVec * vectorWeight + normBm25 * bm25Weight,
          IDENTITY_TOLERANCE,
          "分解式不成立: q" + row.path("queryIndex").asInt() + " " + row.path("variantKey").asText());
      assertEquals(
          r == 0d,
          normVec == 0d && normBm25 == 0d,
          "r = 0 ⟺ 两分支归一值同时为 0 不成立: " + row.path("variantKey").asText());
      assertEquals(
          normVec == 0d, row.path("normVecAtZero").asBoolean(), "normVecAtZero 必须等价于 normVec = 0");
      assertEquals(
          normBm25 == 0d,
          row.path("normBm25AtZero").asBoolean(),
          "normBm25AtZero 必须等价于 normBm25 = 0");
      assertEquals(
          "IMAGE:" + row.path("chunkId").asText(),
          row.path("sourceKey").asText(),
          "sourceKey 必须 = IMAGE:chunkId");
      assertEquals(
          "NEAR_DUPLICATE".equals(row.path("tier").asText()),
          row.path("marked").asBoolean(),
          "marked 必须等价于 tier = NEAR_DUPLICATE");
      checked++;
    }
    assertEquals(15, checked, "必须逐行核对分解表（禁抽样）");
    assertEquals(
        15, anchor.path("equivalence").path("checked").asInt(), "equivalence.checked 必须 15");
    assertEquals(
        0, anchor.path("equivalence").path("violations").asInt(), "equivalence.violations 必须 0");
    assertEquals(
        15,
        anchor.path("equivalence").path("zeroEquivalenceChecked").asInt(),
        "zeroEquivalence.checked 必须 15");
    assertEquals(
        0,
        anchor.path("equivalence").path("violationsZeroEquivalence").asInt(),
        "zeroEquivalence 违反数必须 0");
  }

  private static void assertBuckets(JsonNode anchor) {
    JsonNode queries = anchor.path("queries");
    assertEquals(6, queries.size(), "查询必须 6 条");
    int nonDegenerate = 0;
    int degenerate = 0;
    int routeSum = 0;
    for (JsonNode query : queries) {
      int queryIndex = query.path("queryIndex").asInt();
      boolean vecDegenerate = query.path("vecBranch").path("degenerate").asBoolean();
      boolean bm25Degenerate = query.path("bm25Branch").path("degenerate").asBoolean();
      String expectedBucket =
          vecDegenerate || bm25Degenerate ? DEGENERATE_NOT_EVIDENCE : NON_DEGENERATE;
      assertEquals(
          expectedBucket, query.path("bucket").asText(), "bucket 必须由两分支退化标志推出: q" + queryIndex);
      int routeSize = query.path("routeListSize").asInt();
      assertTrue(routeSize > 0, "routeListSize 必须 > 0: q" + queryIndex);
      // 分支退化必须与归一值全同一致（逐查询从 routes 重算，不看锚内自述）
      List<String> vecValues = new ArrayList<>();
      List<String> bm25Values = new ArrayList<>();
      int marked = 0;
      for (JsonNode row : anchor.path("routes")) {
        if (row.path("queryIndex").asInt() != queryIndex) {
          continue;
        }
        vecValues.add(row.path("normVec").asText());
        bm25Values.add(row.path("normBm25").asText());
        assertEquals(
            query.path("bucket").asText(),
            row.path("bucket").asText(),
            "同一查询的 bucket 必须一致: q" + queryIndex);
        if (row.path("marked").asBoolean()) {
          marked++;
        }
      }
      assertEquals(routeSize, vecValues.size(), "routeListSize 必须等于 routes 行数: q" + queryIndex);
      routeSum += routeSize;
      assertEquals(
          vecValues.stream().distinct().count() == 1,
          vecDegenerate,
          "向量分支退化标志必须等于「归一值全同」: q" + queryIndex);
      assertEquals(
          bm25Values.stream().distinct().count() == 1,
          bm25Degenerate,
          "bm25 分支退化标志必须等于「归一值全同」: q" + queryIndex);
      assertEquals(
          marked,
          query.path("markedCandidateCount").asInt(),
          "markedCandidateCount 不符: q" + queryIndex);
      assertEquals(
          marked == 1,
          query.path("card34Degenerate").asBoolean(),
          "card34Degenerate 必须等价于被标记数 == 1");
      if (NON_DEGENERATE.equals(expectedBucket)) {
        nonDegenerate += routeSize;
      } else {
        degenerate += routeSize;
      }
    }
    assertEquals(15, routeSum, "6 查询的 routeListSize 之和必须 15");
    JsonNode bucketSummary = anchor.path("bucketSummary");
    assertEquals(15, bucketSummary.path("total").asInt(), "bucketSummary.total 必须 15");
    assertEquals(
        nonDegenerate, bucketSummary.path("nonDegenerate").asInt(), "nonDegenerate 必须等于实测");
    assertEquals(
        degenerate,
        bucketSummary.path("degenerateNotEvidence").asInt(),
        "degenerateNotEvidence 必须等于实测");
    assertFalse(bucketSummary.path("bucketRule").asText().isBlank(), "bucketRule 必须写明");
    // 卡 34 口径与本卡口径必须分开登记（禁合并分桶）
    JsonNode crossTab = anchor.path("card34CrossTab");
    assertEquals(3, crossTab.path("card34Degenerate").asInt(), "卡 34 口径退化查询必须 3 条");
    assertEquals(3, crossTab.path("card34NonDegenerate").asInt(), "卡 34 口径非退化查询必须 3 条");
    assertFalse(crossTab.path("note").asText().isBlank(), "交叉口径必须写明");
  }

  private static void assertZeroDisplacementEvents(JsonNode anchor) {
    JsonNode events = anchor.path("zeroDisplacementEvents");
    assertEquals(4, events.size(), "零位移事件必须 4 条（卡 32/33/34 一致）");
    Map<String, JsonNode> routeByKey = new LinkedHashMap<>();
    for (JsonNode row : anchor.path("routes")) {
      routeByKey.put(row.path("queryIndex").asInt() + "|" + row.path("chunkId").asText(), row);
    }
    for (JsonNode event : events) {
      String key = event.path("queryIndex").asInt() + "|" + event.path("chunkId").asText();
      JsonNode route = routeByKey.get(key);
      assertTrue(route != null, "零位移事件必须来自 routes 行: " + key);
      assertEquals(0d, event.path("r").asDouble(), 0d, "零位移事件的 r 必须 0: " + key);
      assertEquals(0d, event.path("normVec").asDouble(), 0d, "零位移事件的 normVec 必须 0: " + key);
      assertEquals(0d, event.path("normBm25").asDouble(), 0d, "零位移事件的 normBm25 必须 0: " + key);
      assertEquals(
          route.path("r").asDouble(), event.path("r").asDouble(), 0d, "r 必须与 route 行同值: " + key);
      assertEquals(
          route.path("bucket").asText(),
          event.path("bucket").asText(),
          "bucket 必须与 route 行同值: " + key);
      assertTrue(event.path("marked").asBoolean(), "零位移事件必须是被标记候选: " + key);
    }
  }

  // ==================== R3 · 三值判定与可达路径 ====================

  private static void assertVerdict(JsonNode anchor, String md) {
    String verdict = anchor.path("verdict").asText();
    assertTrue(
        List.of(PROVEN, REFUTED, UNDETERMINED).contains(verdict), "verdict 必须三选一，实测: " + verdict);
    // 机械规则重算：判据只能由锚内数据推出，禁手填
    boolean consistent =
        anchor.path("reconciliationSummary").path("consistent").asInt()
            == anchor.path("reconciliationSummary").path("total").asInt();
    boolean bothMinimum = true;
    boolean nonDegenerate = true;
    for (JsonNode event : anchor.path("zeroDisplacementEvents")) {
      bothMinimum &=
          event.path("normVec").asDouble() == 0d && event.path("normBm25").asDouble() == 0d;
      nonDegenerate &= NON_DEGENERATE.equals(event.path("bucket").asText());
    }
    String expected = UNDETERMINED;
    if (consistent && bothMinimum && nonDegenerate) {
      expected = PROVEN;
    } else if (consistent && !bothMinimum) {
      expected = REFUTED;
    }
    assertEquals(expected, verdict, "verdict 必须与机械规则一致（禁放宽判据）");
    if (!PROVEN.equals(verdict)) {
      assertTrue(!anchor.path("verdictReason").asText().isBlank(), "非 PROVEN 必须写清原因");
      // 非 PROVEN 不得在任何地方自称成因已证
      assertFalse(
          anchor.path("verdictReason").asText().contains("由此升到 PROVEN"),
          "非 PROVEN 的结论不得引用 PROVEN 措辞");
      assertFalse(md.contains("结论 1 · PROVEN"), "非 PROVEN 的 md 不得出现已证小节");
    }
    assertFalse(anchor.path("verdictRule").asText().isBlank(), "verdictRule 必须写明机械规则");
    assertFalse(anchor.path("structuralFinding").asText().isBlank(), "结构发现必须写明（r = 0 的可观测成因）");
    assertTrue(
        anchor.path("structuralFinding").asText().contains("向量分支末位"), "结构发现必须点明零位移事件的向量分支末位是实测成立的");
    JsonNode reachability = anchor.path("verdictReachability");
    for (String key : List.of(PROVEN, REFUTED, UNDETERMINED)) {
      assertFalse(reachability.path(key).asText().isBlank(), "三值可达路径必须写清（缺 " + key + "）");
    }
    assertTrue(
        reachability.path(PROVEN).asText().contains("NON-DEGENERATE"),
        "PROVEN 的可达路径必须写明「全在两分支皆非退化的查询」这一条件");
    assertFalse(anchor.path("verdictCaliberNote").asText().isBlank(), "分桶口径说明必须写明");
  }

  // ==================== R4 · 埋点方案未执行 ====================

  private static void assertInstrumentedPlan(JsonNode anchor) {
    JsonNode plan = anchor.path("instrumentedPlan");
    assertFalse(plan.isMissingNode(), "缺 instrumentedPlan");
    assertFalse(
        plan.path("executed").asBoolean(), "instrumentedPlan.executed 必须 false（本卡零外呼、方案未执行）");
    assertEquals(0, plan.path("budgetExternalCalls").asInt(), "埋点预算外呼必须 0");
    assertFalse(plan.path("narrowerThanCard34").asText().isBlank(), "必须写明比卡 34 更窄");
    assertFalse(plan.path("localBlocker").asText().isBlank(), "必须逐项写明本地卡在哪");
    assertFalse(plan.path("minimalFields").asText().isBlank(), "必须给最小字段");
    if (!PROVEN.equals(anchor.path("verdict").asText())) {
      assertTrue(plan.path("required").asBoolean(), "UNDETERMINED/REFUTED 必须要求交付方案");
      assertEquals(
          false,
          plan.path("wouldInstrumentationHelp").asBoolean(),
          "本卡实测：退化来自数据而非缺字段，埋点不能提升判定（若改判须同步改本断言）");
      assertTrue(plan.path("unlockPathsIfOwnerWantsProven").size() >= 1, "必须给出解锁路径");
    }
  }

  // ==================== R5 · 腿 B 两栏决策页（无倾向值，双面断言） ====================

  private static void assertFloorOptions(JsonNode anchor, String md) {
    JsonNode floorOptions = anchor.path("floorOptions");
    JsonNode columns = floorOptions.path("columns");
    assertEquals(2, columns.size(), "决策页必须两栏并列");
    assertEquals(0.9887d, columns.get(0).path("floor").asDouble(), 0d, "第一栏必须 0.9887");
    assertEquals(0.90d, columns.get(1).path("floor").asDouble(), 0d, "第二栏必须 0.90");
    // 分母必须写明，且两栏差值必须等于源锚登记的新增进带数
    for (JsonNode column : columns) {
      assertTrue(column.path("ownBaseDenominator").asInt() > 0, "本 base 分母必须写明");
      assertTrue(column.path("thirdImageDenominator").asInt() > 0, "第三图分母必须写明");
      assertTrue(column.path("sameDocDenominator").asInt() > 0, "同文档分母必须写明");
      assertFalse(column.path("sourceFields").asText().isBlank(), "必须指回源锚字段");
    }
    assertEquals(70, columns.get(0).path("ownBaseKept").asInt(), "0.9887 栏本 base 保留必须 70");
    assertEquals(120, columns.get(0).path("ownBaseDenominator").asInt(), "0.9887 栏分母必须 120");
    assertEquals(113, columns.get(1).path("ownBaseKept").asInt(), "0.90 栏本 base 保留必须 113");
    assertEquals(13, columns.get(0).path("thirdImageInBand").asInt(), "0.9887 栏第三图进带必须 13");
    assertEquals(93, columns.get(1).path("thirdImageInBand").asInt(), "0.90 栏第三图进带必须 93");
    assertEquals(2760, columns.get(1).path("thirdImageDenominator").asInt(), "第三图分母必须 2760");
    assertEquals(6, columns.get(1).path("extraSuppressedSameDoc").asInt(), "0.90 栏多压必须 6 张");
    assertFalse(
        floorOptions.path("recordedCosts").path("structuralInertiaHint").asText().isBlank(),
        "必须给结构性惰性提示");
    assertFalse(
        floorOptions.path("ownerDecisionItems").path("item").asText().isBlank(), "必须写明待 Owner 决策项");
    assertFalse(
        floorOptions.path("currentProductionReference").path("floor").asDouble() > 1d,
        "现行生产参照行必须给值");
    assertEquals(
        0.982d,
        floorOptions.path("currentProductionReference").path("floor").asDouble(),
        0d,
        "现行生产 floor 参照必须直读生产常量 0.982");
    // 双面断言：禁用词全无（反）＋ 两栏标签与关键分母在位（正）
    assertNoTendencyWords(anchor.toString(), "锚 JSON");
    assertNoTendencyWords(md, "锚 md");
    assertTrue(md.contains("维持 0.9887"), "md 必须给「维持 0.9887」栏");
    assertTrue(md.contains("降到 0.90"), "md 必须给「降到 0.90」栏");
    assertTrue(md.contains("113/120"), "md 必须给 0.90 栏本 base 分子/分母");
    assertTrue(md.contains("70/120"), "md 必须给 0.9887 栏本 base 分子/分母");
    assertTrue(md.contains("93/2760"), "md 必须给 0.90 栏第三图分子/分母");
    assertTrue(md.contains("13/2760"), "md 必须给 0.9887 栏第三图分子/分母");
  }

  private static void assertNoTendencyWords(String text, String where) {
    String lower = text.toLowerCase(Locale.ROOT);
    for (String word : FORBIDDEN_TENDENCY_WORDS) {
      assertFalse(
          lower.contains(word.toLowerCase(Locale.ROOT)), where + " 不得出现倾向性措辞（禁词: " + word + "）");
    }
  }

  // ==================== R6/R7 · 形状与零外呼 ====================

  private static void assertMdShape(String md) {
    long lines = md.lines().count();
    assertTrue(lines <= 40, "锚 md 必须 ≤40 行（实测 " + lines + "）");
    assertTrue(md.startsWith("# near-dup-rerank-decomposition-v1"), "md 首行必须是标题");
    assertNoCarriageReturn(md.getBytes(StandardCharsets.UTF_8), "锚 md");
    assertTrue(md.contains("docs/rag-quality/near-dup-rerank-decomposition-v1.json"), "md 必须指回机读锚");
  }

  private static void assertNoExternalCalls(JsonNode anchor) {
    JsonNode reconstruction = anchor.path("reconstruction");
    assertEquals(0, reconstruction.path("externalCalls").asInt(), "本卡必须零外呼（externalCalls = 0）");
    assertEquals(
        0,
        anchor.path("runtime").path("externalCalls").asInt(),
        "runtime.externalCalls 必须 0（与卡 33/34 上游惯例一致）");
    assertTrue(
        anchor.path("runtime").path("excludedFromReplay").asBoolean(),
        "runtime.excludedFromReplay 必须 true");
    assertEquals(
        0,
        anchor.path("vectorNormalizationIndependence").path("endpointViolations").asInt(),
        "独立路径（cos 端值映射）必须 0 违规");
    assertTrue(
        anchor.path("vectorNormalizationIndependence").path("checked").asInt() > 0,
        "独立路径必须至少检查 1 条 route");
    assertFalse(anchor.path("numericHygiene").path("snapRule").asText().isBlank(), "必须在锚内记录数值归整规则");
    assertEquals(5, reconstruction.path("config").path("topK").asInt(), "topK 必须与卡 32 锚一致");
    assertEquals(
        0.9944d,
        reconstruction.path("config").path("imageSimilarityThreshold").asDouble(),
        0d,
        "图像路阈值必须是生产默认 0.9944");
    assertEquals(
        0.982d,
        reconstruction.path("config").path("effectiveFloor").asDouble(),
        0d,
        "图像路准入下限必须是生产 effectiveFloor 0.982");
    assertFalse(
        reconstruction.path("config").path("queryRewriteEnabled").asBoolean(),
        "查询改写必须关闭（与卡 32 矩阵一致）");
    assertEquals(
        6,
        reconstruction.path("corpusMaterialCounts").path("imageRecords").asInt(),
        "imageRecords 计数");
    assertEquals(
        6,
        reconstruction.path("corpusMaterialCounts").path("textRecords").asInt(),
        "textRecords 计数");
    assertEquals(
        6,
        reconstruction.path("corpusMaterialCounts").path("queryImageVectors").asInt(),
        "queryImageVectors 计数");
    assertEquals(
        12,
        reconstruction.path("corpusMaterialCounts").path("embedCache").asInt(),
        "embedCache 计数");
    JsonNode sizes = reconstruction.path("routeListSizePerQuery");
    assertEquals(6, sizes.size(), "routeListSizePerQuery 必须 6 条");
    assertEquals(15, anchor.path("routes").size(), "routes 行数必须等于 routeListSize 之和");
    assertNotEquals(0d, sizes.get(0).asDouble() + sizes.get(5).asDouble(), "routeListSize 不得全 0");
  }

  private static void assertNoCarriageReturn(byte[] bytes, String where) {
    for (byte value : bytes) {
      assertNotEquals((byte) '\r', value, where + " 必须纯 LF（发现 CR）");
    }
  }
}
