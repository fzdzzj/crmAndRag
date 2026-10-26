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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 36 守卫 · near-dup-conditional-min-criterion。
 *
 * <p>只读 {@code docs/rag-quality/near-dup-conditional-min-criterion-v1.json} 与同名 {@code
 * .md}，独立复算而不是转述： 上游五锚（卡 32/33/34/35 + 签收锚）的 SHA 实算、三分支判定式逐查询重推、12 条事件的「所属分支 + 是否满足该分支充要条件」重判、 腿 B
 * 清单的字段可指回与计数自洽、{@code executed = false}、md ≤ 40 行、纯 LF、无倾向措辞。
 *
 * <p><b>不信任锚自述</b>（F1）：腿 B 的 16 行必须能被守卫独立复核——逐行按 {@code path} 实算源锚 SHA、逐 {@code
 * classificationFields[]} 把路径解析回源锚并比对 {@code sample}、{@code cosFrozen} 由守卫自己递归扫源锚得出，任一项与锚自述不符即红。
 *
 * <p>反向断言（板规 30）：无条件 {@code PROVEN} 档文本不得出现「末位」「两分支同时」；{@code PROVEN-CONDITIONAL} 必须有非空 {@code
 * applicabilityDomain}。
 */
class NearDupConditionalMinCriterionGuardTest {

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-conditional-min-criterion-v1.json");

  private static final Path ANCHOR_MD =
      Path.of("docs", "rag-quality", "near-dup-conditional-min-criterion-v1.md");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String B0 = "B0-BM25-ALL-ZERO";

  private static final String B1 = "B1-BM25-ALL-ONE";

  private static final String B2 = "B2-BM25-NON-DEGENERATE";

  private static final String PROVEN = "PROVEN";

  private static final String PROVEN_CONDITIONAL = "PROVEN-CONDITIONAL";

  private static final String REFUTED = "REFUTED";

  private static final String UNDETERMINED = "UNDETERMINED";

  private static final Set<String> VERDICTS =
      Set.of(PROVEN, PROVEN_CONDITIONAL, REFUTED, UNDETERMINED);

  private static final Set<String> CONTRADICTIONS = Set.of("YES", "NO", UNDETERMINED);

  private static final Set<String> ZERO_CALL_VALUES =
      Set.of("true", "false", "NOT-APPLICABLE", UNDETERMINED);

  private static final Set<String> KNOWN_CAUSES =
      Set.of(
          "OPERATING-FLOOR-LITERAL",
          "ROW-LEVEL-CLASSIFICATION",
          "ROW-LEVEL-UNREPRODUCED",
          "AGGREGATE-ONLY",
          "NO-COS",
          "FROZEN-CLASS-INVARIANT",
          "NONE",
          "MISSING-ANCHOR");

  private static final List<String> FORBIDDEN_TENDENCY_WORDS =
      List.of("推荐", "建议降", "建议不降", "应下调", "recommend", "Recommend");

  private static final List<Path> UPSTREAM_PATHS =
      List.of(
          Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json"),
          Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.json"),
          Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json"),
          Path.of("docs", "rag-quality", "near-dup-rerank-decomposition-v1.json"),
          Path.of("docs", "rag-quality", "near-dup-floor-signoff-v1.json"));

  @Test
  @DisplayName("卡36 守卫：三分支互斥穷尽 + 12 条事件复判 + 四值可达路径 + 腿B 字段可指回 + 边界")
  void guardsConditionalMinCriterionAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR_JSON), "结论锚缺失: " + ANCHOR_JSON.toAbsolutePath());
    assertTrue(Files.exists(ANCHOR_MD), "结论 md 缺失: " + ANCHOR_MD.toAbsolutePath());
    byte[] jsonBytes = Files.readAllBytes(ANCHOR_JSON);
    byte[] mdBytes = Files.readAllBytes(ANCHOR_MD);
    String md = new String(mdBytes, StandardCharsets.UTF_8);
    JsonNode anchor = MAPPER.readTree(jsonBytes);

    assertNoCarriageReturn(jsonBytes, "锚 JSON");
    assertNoCarriageReturn(mdBytes, "锚 md");
    assertHeader(anchor);
    assertUpstreamAnchors(anchor);
    JsonNode weights = anchor.path("weights");
    assertWeights(weights);
    assertCriterion(anchor.path("criterion"));
    assertQueryBranches(anchor.path("queryBranches"));
    assertReplay(anchor.path("eventReplay"));
    assertConditionalVerdict(anchor.path("conditionalVerdict"), weights, md);
    assertReachability(anchor);
    assertImpact(anchor.path("impact"));
    assertReanchorPlan(anchor.path("reanchorPlan"), anchor.path("impact"));
    assertHygiene(anchor.path("numericHygiene"));
    assertNoTendencyWords(anchor.toString() + "\n" + md);
    assertMdShape(md, anchor);
    assertNoExternalCalls(anchor.path("runtime"));
    System.out.println("[near-dup-conditional-min-criterion-guard] 校验通过（16 锚 / 12 事件 / 3 分支）");
  }

  // ==================== 头部与上游 ====================

  private static void assertHeader(JsonNode anchor) {
    assertEquals("near-dup-conditional-min-criterion", anchor.path("card").asText());
    assertEquals("CONDITIONAL-MIN-CRITERION", anchor.path("stage").asText());
    assertEquals("NEAR-DUP-CONDITIONAL-MIN-CRITERION-1.0", anchor.path("decisionVersion").asText());
    assertEquals("OWNER", anchor.path("decisionOwner").asText());
    assertFalse(anchor.path("scope").asText().isEmpty(), "scope 不得为空");
    for (String key : List.of("R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8")) {
      assertFalse(anchor.path("criteria").path(key).asText().isEmpty(), "criteria 缺 " + key);
    }
  }

  private static void assertUpstreamAnchors(JsonNode anchor) throws Exception {
    JsonNode upstream = anchor.path("upstreamAnchors");
    assertEquals(5, upstream.size(), "上游锚应有 5 张（卡 32/33/34/35 + 签收锚）");
    for (String name :
        List.of(
            "bandEntry",
            "windowCoupling",
            "displacementEvidence",
            "rerankDecomposition",
            "floorSignoff")) {
      JsonNode ref = upstream.path(name);
      assertFalse(ref.path("path").asText().isEmpty(), "上游锚缺 path: " + name);
      assertTrue(ref.path("readOnly").asBoolean(false), "上游锚必须标 readOnly: " + name);
      Path path = Path.of(ref.path("path").asText());
      assertTrue(Files.exists(path), "上游锚文件不存在: " + path);
      assertEquals(
          sha256(Files.readAllBytes(path)),
          ref.path("sha256").asText(),
          "上游锚 SHA 与实算不一致（锚可能被改动）: " + path);
    }
    Set<String> referenced = new LinkedHashSet<>();
    for (JsonNode ref : upstream) {
      referenced.add(ref.path("path").asText());
    }
    for (Path expected : UPSTREAM_PATHS) {
      assertTrue(
          referenced.contains(expected.toString().replace('\\', '/')), "五张上游锚必须被引用: " + expected);
    }
  }

  private static void assertWeights(JsonNode weights) {
    double vector = weights.path("vectorWeight").asDouble(-1d);
    double bm25 = weights.path("bm25Weight").asDouble(-1d);
    assertEquals(0.6d, vector, 1e-12, "每条事件都依赖 wv = 0.6");
    assertEquals(0.4d, bm25, 1e-12, "每条事件都依赖 wb = 0.4");
    assertEquals(1.0d, vector + bm25, 1e-12, "两权重之和应为 1（生产 min-max 加权和口径）");
    assertEquals(15, weights.path("identityChecked").asInt(), "恒等式应覆盖 15 行");
    assertEquals(0, weights.path("identityViolations").asInt(), "恒等式违例必须为 0");
    assertTrue(weights.path("identityTolerance").asDouble() > 0d, "容差必须为正");
    assertFalse(weights.path("source").asText().isEmpty(), "权重来源必须指回卡 35 锚");
  }

  // ==================== 三分支 ====================

  private static void assertCriterion(JsonNode criterion) {
    assertFalse(criterion.path("identity").asText().isEmpty(), "必须写明 r 的分解式");
    JsonNode branches = criterion.path("branches");
    assertEquals(3, branches.size(), "判据必须正好三支（不许硬塞第四支）");
    assertEquals(B0, branches.get(0).path("id").asText());
    assertEquals(B1, branches.get(1).path("id").asText());
    assertEquals(B2, branches.get(2).path("id").asText());
    assertTrue(
        branches.get(0).path("predicate").asText().contains("max(normBm25[q]) <= 0.0"),
        "B0 判定式必须是 max <= 0");
    assertTrue(
        branches.get(1).path("predicate").asText().contains("== min"), "B1 判定式必须是 max == min > 0");
    assertTrue(branches.get(2).path("predicate").asText().contains("> min"), "B2 判定式必须是 max > min");
    assertTrue(
        branches.get(0).path("consequence").asText().contains("normVec = 0"),
        "B0 必须给出 r = 0 ⟺ normVec = 0");
    assertTrue(
        branches.get(1).path("consequence").asText().contains("wb"), "B1 必须给出 r >= wb 的不可能性");
    assertTrue(
        branches.get(2).path("consequence").asText().contains("normBm25 = 0"),
        "B2 必须给出两项同时为 0 的充要条件");
    for (JsonNode branch : branches) {
      assertFalse(branch.path("applicableQueries").isMissingNode(), "每支必须有适用查询清单");
      assertTrue(branch.path("observedQueries").asInt() >= 0, "observedQueries 不得为负");
      assertFalse(branch.path("reachable").asText().isEmpty(), "每支必须写明是否可达");
    }
    assertEquals(0, branches.get(2).path("observedQueries").asInt(), "本语料 B2 查询数应为 0");

    JsonNode exhaustiveness = criterion.path("exhaustiveness");
    assertTrue(exhaustiveness.path("mutuallyExclusive").asBoolean(false), "三支必须互斥");
    assertTrue(exhaustiveness.path("exhaustive").asBoolean(false), "三支必须穷尽");
    assertEquals(6, exhaustiveness.path("queriesTotal").asInt(), "本语料 6 条查询");
    JsonNode byBranch = exhaustiveness.path("queriesByBranch");
    int sum = byBranch.path(B0).asInt() + byBranch.path(B1).asInt() + byBranch.path(B2).asInt();
    assertEquals(6, sum, "三分支查询数之和必须等于 6（互斥穷尽的实测核对）");
    assertEquals(sum, exhaustiveness.path("queriesCoveredSum").asInt(), "覆盖数必须与分支配额自洽");
    assertFalse(criterion.path("branchDecisionRule").asText().isEmpty(), "必须写明可执行判定式");
  }

  private static void assertQueryBranches(JsonNode queryBranches) {
    assertEquals(6, queryBranches.size(), "逐查询分支状态应有 6 条");
    Set<Integer> indices = new LinkedHashSet<>();
    for (JsonNode query : queryBranches) {
      int index = query.path("queryIndex").asInt(-1);
      assertTrue(index >= 0, "queryIndex 必须有效");
      assertTrue(indices.add(index), "queryIndex 不得重复: " + index);
      double max = query.path("normBm25Max").asDouble();
      double min = query.path("normBm25Min").asDouble();
      assertFalse(query.path("form").asText().isEmpty(), "查询形态不得为空");
      assertTrue(query.path("routeRows").asInt() > 0, "每条查询至少 1 行 route");
      String declared = query.path("branch").asText();
      String recomputed;
      if (max <= 0d) {
        recomputed = B0;
      } else if (Double.compare(max, min) == 0) {
        recomputed = B1;
      } else {
        recomputed = B2;
      }
      assertEquals(recomputed, declared, "分支状态与判定式不一致: q" + index);
    }
  }

  private static void assertReplay(JsonNode replay) {
    assertEquals(15, replay.path("checked").asInt(), "恒等式复算应覆盖 15 行");
    assertEquals(0, replay.path("violations").asInt(), "恒等式违例必须为 0");
    assertEquals(0, replay.path("vectorMinViolations").asInt(), "向量末位判定违例必须为 0");
    assertTrue(replay.path("tolerance").asDouble() > 0d, "复算容差必须为正");
    assertTrue(replay.path("vectorMinRule").asText().contains("normVec = 0"), "必须写明向量末位判据");
  }

  // ==================== 逐条复判与判定词 ====================

  private static void assertConditionalVerdict(JsonNode conditional, JsonNode weights, String md) {
    String verdict = conditional.path("verdict").asText();
    assertTrue(VERDICTS.contains(verdict), "verdict 必须是四值之一: " + verdict);
    assertEquals(PROVEN_CONDITIONAL, verdict, "本卡预期落在适用域内已证的 PROVEN-CONDITIONAL");
    assertFalse(conditional.path("verdictReason").asText().isEmpty(), "必须给出判定理由");
    assertTrue(
        conditional.path("applicabilityDomain").asText().contains("B0"),
        "PROVEN-CONDITIONAL 必须写明适用域（含 B0）");
    assertTrue(
        conditional.path("applicabilityDomain").asText().contains("B2"), "适用域必须写明未观测到的 B2 域");
    assertTrue(md.contains(verdict), "md 必须复述 verdict");

    JsonNode rows = conditional.path("rows");
    assertEquals(12, rows.size(), "被标记事件必须 12 条逐条落 row");
    double bm25Weight = weights.path("bm25Weight").asDouble();
    int zero = 0;
    int satisfied = 0;
    int zeroOutsideB0 = 0;
    int zeroInB2BothZero = 0;
    for (JsonNode row : rows) {
      String branch = row.path("branch").asText();
      assertTrue(List.of(B0, B1, B2).contains(branch), "事件所属分支必须是三支之一: " + branch);
      assertFalse(row.path("condition").asText().isEmpty(), "每条必须给出所属分支的充要条件");
      assertFalse(row.path("variantKey").asText().isEmpty(), "事件必须可指回 variantKey");
      assertFalse(row.path("chunkId").asText().isEmpty(), "事件必须可指回 chunkId");
      double normVec = row.path("normVec").asDouble();
      double normBm25 = row.path("normBm25").asDouble();
      double r = row.path("r").asDouble();
      boolean declaredSatisfied = row.path("conditionSatisfied").asBoolean(false);
      boolean recomputed;
      if (B0.equals(branch)) {
        recomputed = (r == 0d) == (normVec == 0d);
      } else if (B1.equals(branch)) {
        recomputed = r >= bm25Weight - 1e-12d;
      } else {
        recomputed = (r == 0d) == (normVec == 0d && normBm25 == 0d);
      }
      assertEquals(recomputed, declaredSatisfied, "事件条件判定与所属分支的充要条件不一致: " + row);
      if (declaredSatisfied) {
        satisfied++;
      }
      if (r == 0d) {
        assertTrue(row.path("zeroDisplacement").asBoolean(false), "r = 0 必须标零位移");
        zero++;
        if (!B0.equals(branch)) {
          zeroOutsideB0++;
        }
        if (B2.equals(branch) && normVec == 0d && normBm25 == 0d) {
          zeroInB2BothZero++;
        }
      } else {
        assertFalse(row.path("zeroDisplacement").asBoolean(true), "r != 0 不得标零位移");
      }
    }
    assertEquals(8, 12 - zero, "零位移事件应为 4 条（即非零 8 条）");
    assertEquals(zeroOutsideB0, 0, "4 条零位移事件必须全落 bm25 全 0 桶（R3），不得有例外");
    String expectedVerdict;
    if (satisfied != rows.size()) {
      expectedVerdict = REFUTED;
    } else if (zero == zeroInB2BothZero) {
      expectedVerdict = PROVEN;
    } else if (zeroOutsideB0 > 0 || zero > 0) {
      expectedVerdict = PROVEN_CONDITIONAL;
    } else {
      expectedVerdict = UNDETERMINED;
    }
    assertEquals(expectedVerdict, verdict, "verdict 必须与逐条复判结果自洽");

    JsonNode summary = conditional.path("summary");
    assertEquals(12, summary.path("events").asInt(), "summary.events 应为 12");
    assertEquals(zero, summary.path("zeroDisplacement").asInt(), "summary 零位移数必须与 rows 一致");
    assertEquals(zero, summary.path("zeroInB0").asInt(), "summary.zeroInB0 必须与 rows 一致");
    assertEquals(12, summary.path("satisfied").asInt(), "12/12 条必须满足各自分支条件");
  }

  private static void assertReachability(JsonNode anchor) {
    JsonNode reachability = anchor.path("verdictReachability");
    for (String key : List.of(PROVEN, PROVEN_CONDITIONAL, REFUTED, UNDETERMINED)) {
      assertTrue(reachability.has(key), "四值必须各写可达路径，缺: " + key);
      assertTrue(reachability.path(key).asText().length() > 20, "可达路径不得是占位串: " + key);
    }
    String provenText = reachability.path(PROVEN).asText();
    assertTrue(provenText.contains("可达但本语料未出现"), "无条件 PROVEN 档必须写明「可达但本语料未出现」（板规 30）");
    assertFalse(provenText.contains("末位"), "无条件 PROVEN 档文本不得用「末位」措辞指称本语料（板规 30）");
    assertFalse(provenText.contains("两分支同时"), "无条件 PROVEN 档文本不得用「两分支同时」措辞指称本语料（板规 30）");
    String note = anchor.path("unconditionalProvenNote").asText();
    assertTrue(note.contains("可达但本语料未出现"), "unconditionalProvenNote 必须写明可达性");
    assertFalse(note.contains("末位"), "unconditionalProvenNote 不得含「末位」");
    assertFalse(note.contains("两分支同时"), "unconditionalProvenNote 不得含「两分支同时」");
  }

  // ==================== 腿 B ====================

  private static void assertImpact(JsonNode impact) throws Exception {
    assertFalse(impact.path("caliber").asText().isEmpty(), "必须写明 0.90 的口径");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_FLOOR,
        impact.path("currentValue").asDouble(),
        1e-12d,
        "currentValue 必须等于生产 NEAR_DUPLICATE_FLOOR（只读核对）");
    assertEquals(0.90d, impact.path("proposedValue").asDouble(), 1e-12d, "proposedValue 应为 0.90");
    assertEquals(
        ImageSimilarityGate.BAND_REFERENCE_FLOOR,
        impact.path("bandReferenceFrozen").asDouble(),
        1e-12d,
        "bandReferenceFrozen 必须等于生产 BAND_REFERENCE_FLOOR（冻结口径）");
    for (String key : List.of("contradictionRule", "deltaSemantics", "contradictionWording")) {
      assertFalse(impact.path(key).asText().isEmpty(), "impact 缺说明字段: " + key);
    }
    assertFalse(impact.path("contradictionWording").asText().contains("推荐"), "impact 说明不得含倾向措辞");

    JsonNode perAnchor = impact.path("perAnchor");
    assertEquals(16, perAnchor.size(), "腿 B 必须逐张核对 16 张既有锚");
    Set<String> names = new LinkedHashSet<>();
    int yes = 0;
    int no = 0;
    int undetermined = 0;
    int minCalls = 0;
    int undeterminedCalls = 0;
    List<String> affected = new ArrayList<>();
    List<String> unaffected = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    for (JsonNode row : perAnchor) {
      String name = row.path("anchor").asText();
      assertFalse(name.isEmpty(), "每行必须带 anchor 名");
      assertTrue(names.add(name), "锚名不得重复: " + name);
      assertTrue(row.path("exists").asBoolean(false), "锚必须存在: " + name);
      assertEquals(64, row.path("sha256").asText().length(), "sha256 长度应为 64: " + name);
      String contradiction = row.path("contradictionUnder090").asText();
      assertTrue(CONTRADICTIONS.contains(contradiction), "contradiction 取值非法: " + contradiction);
      String cause = row.path("contradictionCause").asText();
      assertTrue(KNOWN_CAUSES.contains(cause), "cause 取值非法: " + cause);
      assertFalse(row.path("contradictionBasis").asText().isEmpty(), "必须给判定依据: " + name);
      String zeroCall = row.path("zeroCallRecompute").asText();
      assertTrue(ZERO_CALL_VALUES.contains(zeroCall), "zeroCallRecompute 取值非法: " + zeroCall);
      assertTrue(
          row.path("minExternalCallsIfNeeded").asInt(-99) >= -1, "minExternalCalls 必须 >= -1");
      assertTrue(
          row.path("rowLevelClassificationDelta").asInt(-99) >= -1,
          "rowLevelClassificationDelta 必须 >= -1");
      boolean classificationFrozen = row.path("classificationFrozen").asBoolean(false);
      if (classificationFrozen) {
        assertTrue(row.path("classificationFields").size() > 0, "冻了分类就必须给出字段路径 + 样例（D3）: " + name);
        for (JsonNode field : row.path("classificationFields")) {
          assertFalse(field.path("path").asText().isEmpty(), "分类字段必须有路径: " + name);
          assertFalse(field.path("sample").asText().isEmpty(), "分类字段必须有样例: " + name);
        }
      }
      if ("YES".equals(contradiction)) {
        assertTrue(!row.path("contradictionBasis").asText().isEmpty(), "YES 必须给依据: " + name);
      }
      if (UNDETERMINED.equals(contradiction)) {
        assertTrue(
            "UNDETERMINED".equals(zeroCall) || "false".equals(zeroCall), "未判定项必须说明重算代价: " + name);
        undeterminedCalls += Math.max(row.path("minExternalCallsIfNeeded").asInt(0), 0);
      }
      if (!"NOT-APPLICABLE".equals(zeroCall) && row.path("minExternalCallsIfNeeded").asInt() > 0) {
        minCalls += row.path("minExternalCallsIfNeeded").asInt();
      }
      if ("YES".equals(contradiction)) {
        yes++;
        affected.add(name);
        assertTrue("true".equals(zeroCall), "受影响锚若可零外呼重算必须标 true（本卡实测全部零外呼）: " + name);
      } else if ("NO".equals(contradiction)) {
        no++;
        unaffected.add(name);
        assertEquals("NOT-APPLICABLE", zeroCall, "不受影响锚的 zeroCallRecompute 应为 NOT-APPLICABLE");
      } else {
        undetermined++;
        unresolved.add(name);
      }
    }

    JsonNode summary = impact.path("summary");
    assertEquals(16, summary.path("total").asInt(), "summary.total 应为 16");
    assertEquals(yes, summary.path("contradictionYes").asInt(), "summary 计数与逐行不一致");
    assertEquals(no, summary.path("contradictionNo").asInt(), "summary 计数与逐行不一致");
    assertEquals(
        undetermined, summary.path("contradictionUndetermined").asInt(), "summary 计数与逐行不一致");
    assertEquals(minCalls, summary.path("minExternalCallsTotal").asInt(), "最小外呼总量与逐行不一致");
    assertTrue(summary.path("fullRerunTotal").asInt() >= minCalls, "全量重跑上界不应小于最小值");
    assertEquals(
        summary.path("fullRerunTotal").asInt(),
        summary.path("fullRerunTotalAffected").asInt(),
        "fullRerunTotalAffected 必须与 fullRerunTotal 同值（两者都只算已判定受影响的锚）");
    assertEquals(
        summary.path("fullRerunTotal").asInt() + undeterminedCalls,
        summary.path("fullRerunTotalWithUnresolved").asInt(),
        "含未判定项的全量重跑上界必须 = 已判定上界 + Σ(未判定行 minExternalCallsIfNeeded)");
    String boundary = summary.path("fullRerunTotalBoundary").asText();
    assertTrue(boundary.contains("221"), "边界说明必须写出 221（已判定 9 张）: " + boundary);
    assertTrue(boundary.contains("239"), "边界说明必须写出 239（含未判定 1 张的 18）: " + boundary);
    assertTrue(boundary.contains("18"), "边界说明必须写出未判定的 18 次外呼: " + boundary);
    assertEquals(
        String.join(",", affected), summary.path("affected").asText(), "affected 清单与逐行不一致");
    assertEquals(
        String.join(",", unaffected), summary.path("unaffected").asText(), "unaffected 清单不一致");
    assertEquals(
        String.join(",", unresolved), summary.path("unresolved").asText(), "unresolved 清单不一致");
    assertImpactProvenance(impact);
  }

  /** 腿 B 溯源（F1）：不信任锚自述——SHA 实算、字段路径实解析、cosFrozen 独立重算。 */
  private static void assertImpactProvenance(JsonNode impact) throws Exception {
    int checked = 0;
    for (JsonNode row : impact.path("perAnchor")) {
      String name = row.path("anchor").asText();
      Path source = Path.of(row.path("path").asText());
      assertTrue(Files.exists(source), "源锚不存在，无法实算 SHA（不得只信锚自述）: " + name + " -> " + source);
      byte[] bytes = Files.readAllBytes(source);
      assertEquals(sha256(bytes), row.path("sha256").asText(), "源锚 SHA 实算值与锚内自述不一致: " + name);
      checked++;
      JsonNode root = MAPPER.readTree(bytes);

      assertEquals(
          containsCosPayload(root),
          row.path("cosFrozen").asBoolean(false),
          "cosFrozen 与源锚实算不一致（只有 cos 数值字段或向量数组才算冻了 cos）: " + name);

      for (JsonNode field : row.path("classificationFields")) {
        String fieldPath = field.path("path").asText();
        String sample = field.path("sample").asText();
        List<JsonNode> resolved = resolvePath(root, fieldPath);
        if (resolved.isEmpty()) {
          assertEquals(
              UNDETERMINED,
              row.path("contradictionUnder090").asText(),
              "分类字段路径解析不了就必须记 UNDETERMINED（不许静默放过）: " + name + " -> " + fieldPath);
          continue;
        }
        boolean matched = false;
        for (JsonNode value : resolved) {
          if (sample.equals(sampleText(value))) {
            matched = true;
            break;
          }
        }
        assertTrue(matched, "分类字段样例与源锚解析值不一致: " + name + " -> " + fieldPath + " 样例=" + sample);
      }
    }
    assertEquals(16, checked, "溯源必须覆盖腿 B 全部 16 张既有锚");
    System.out.println(
        "[near-dup-conditional-min-criterion-guard] 溯源通过：16/16 SHA 实算 + 分类字段实解析 + cosFrozen 独立重算");
  }

  /** 把 {@code bandSides[2].rows[5].tier} / {@code dataCheck.rByEvent[*].band} 解析到源锚 JSON。 */
  private static List<JsonNode> resolvePath(JsonNode root, String path) {
    List<JsonNode> current = new ArrayList<>();
    current.add(root);
    for (String segment : path.split("\\.")) {
      String field = segment;
      String index = null;
      int bracket = segment.indexOf('[');
      if (bracket >= 0 && segment.endsWith("]")) {
        field = segment.substring(0, bracket);
        index = segment.substring(bracket + 1, segment.length() - 1).trim();
      }
      List<JsonNode> next = new ArrayList<>();
      for (JsonNode node : current) {
        JsonNode child = field.isEmpty() ? node : node.get(field);
        if (child == null) {
          continue;
        }
        if (index == null) {
          next.add(child);
          continue;
        }
        if (!child.isArray()) {
          continue;
        }
        if ("*".equals(index)) {
          for (JsonNode element : child) {
            next.add(element);
          }
          continue;
        }
        try {
          int position = Integer.parseInt(index);
          if (position >= 0 && position < child.size()) {
            next.add(child.get(position));
          }
        } catch (NumberFormatException ignored) {
          // 下标不可解析 ⇒ 该路径解析失败，交由调用方按「必须记 UNDETERMINED」判定。
        }
      }
      current = next;
      if (current.isEmpty()) {
        break;
      }
    }
    return current;
  }

  private static String sampleText(JsonNode value) {
    return value.isNull() ? "null" : value.asText();
  }

  /** 口径与生成器一致但代码独立：cos/cosine 数值字段，或名字含 cos 的数组（向量）即视为冻了 cos。 */
  private static boolean containsCosPayload(JsonNode node) {
    if (node.isArray()) {
      for (JsonNode child : node) {
        if (containsCosPayload(child)) {
          return true;
        }
      }
      return false;
    }
    if (!node.isObject()) {
      return false;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String key = field.getKey();
      JsonNode value = field.getValue();
      if (("cos".equals(key) || "cosine".equals(key)) && value.isNumber()) {
        return true;
      }
      if (value.isArray() && key.toLowerCase(Locale.ROOT).contains("cos")) {
        return true;
      }
      if ((value.isObject() || value.isArray()) && containsCosPayload(value)) {
        return true;
      }
    }
    return false;
  }

  private static void assertReanchorPlan(JsonNode plan, JsonNode impact) throws Exception {
    assertFalse(plan.path("executed").asBoolean(true), "executed 必须为 false（D5：本卡不执行重建）");
    assertFalse(plan.path("executedBasis").asText().isEmpty(), "必须说明为何未执行");
    String orderRule = plan.path("orderRule").asText();
    assertFalse(orderRule.isEmpty(), "必须说明依赖顺序规则");
    for (String token : List.of("被引用者先", "重写", "重签")) {
      assertTrue(
          orderRule.contains(token), "orderRule 必须写明分层规则，缺: " + token + "（原文: " + orderRule + "）");
    }
    assertOrderFollowsShaReferences(plan, impact);
    JsonNode summary = impact.path("summary");
    JsonNode ordered = plan.path("ordered");
    assertEquals(summary.path("contradictionYes").asInt(), ordered.size(), "重锚清单应等于受影响锚数");
    Set<Integer> orders = new LinkedHashSet<>();
    int minOrder = Integer.MAX_VALUE;
    int maxOrder = 0;
    int minTotal = 0;
    for (JsonNode entry : ordered) {
      int order = entry.path("order").asInt();
      assertTrue(orders.add(order), "order 不得重复");
      minOrder = Math.min(minOrder, order);
      maxOrder = Math.max(maxOrder, order);
      assertFalse(entry.path("anchor").asText().isEmpty(), "重锚项必须有锚名");
      assertFalse(entry.path("method").asText().isEmpty(), "重锚项必须有重建方式");
      assertTrue(entry.path("externalCalls").asInt(-99) >= 0, "重锚项外呼必须 >= 0");
      assertFalse(entry.path("basis").asText().isEmpty(), "重锚项必须给依据");
      minTotal += entry.path("externalCalls").asInt();
    }
    assertEquals(1, minOrder, "order 必须从 1 起");
    assertEquals(ordered.size(), maxOrder, "order 必须连续编号到清单长度");
    assertTrue(
        minTotal <= plan.path("minExternalCallsTotal").asInt(), "重锚项外呼合计不得超过总最小外呼（未判定项也计入总量）");
    assertEquals(
        summary.path("contradictionNo").asInt(), plan.path("unaffected").size(), "不受影响清单应等于未受影响锚数");
    assertEquals(
        summary.path("contradictionUndetermined").asInt(),
        plan.path("unresolved").size(),
        "未判定清单应等于未判定锚数");
    for (JsonNode entry : plan.path("unresolved")) {
      assertFalse(entry.path("basis").asText().isEmpty(), "未判定项必须给依据");
    }
    assertTrue(plan.path("note").asText().contains("Owner"), "必须写明决策归 Owner（D4：不替 Owner 决策）");
    assertEquals(ordered.size(), plan.path("orderedCount").asInt(), "orderedCount 应与清单长度一致");
    assertEquals(
        plan.path("unaffected").size(),
        plan.path("unaffectedCount").asInt(),
        "unaffectedCount 不一致");
    assertEquals(
        plan.path("unresolved").size(),
        plan.path("unresolvedCount").asInt(),
        "unresolvedCount 不一致");
  }

  /**
   * 重锚顺序的依赖一致性（F2）：9 张 ordered 锚两两比对，若 A 的 sha256 十六进制串出现在 B 的文件字节中（B 引用了 A），则 A 必须先被重写，断言 {@code
   * order(A) < order(B)}。
   */
  private static void assertOrderFollowsShaReferences(JsonNode plan, JsonNode impact)
      throws Exception {
    Map<String, Path> paths = new LinkedHashMap<>();
    for (JsonNode row : impact.path("perAnchor")) {
      paths.put(row.path("anchor").asText(), Path.of(row.path("path").asText()));
    }
    Map<String, Integer> order = new LinkedHashMap<>();
    for (JsonNode entry : plan.path("ordered")) {
      order.put(entry.path("anchor").asText(), entry.path("order").asInt());
    }
    assertEquals(9, order.size(), "重锚清单应含 9 张已判定受影响锚");
    Map<String, String> sha = new LinkedHashMap<>();
    Map<String, String> body = new LinkedHashMap<>();
    for (String name : order.keySet()) {
      Path path = paths.get(name);
      assertTrue(path != null && Files.exists(path), "重锚清单里的锚必须能指回源锚文件: " + name);
      byte[] bytes = Files.readAllBytes(path);
      sha.put(name, sha256(bytes));
      body.put(name, new String(bytes, StandardCharsets.UTF_8));
    }
    for (String cited : order.keySet()) {
      for (String citing : order.keySet()) {
        if (cited.equals(citing) || !body.get(citing).contains(sha.get(cited))) {
          continue;
        }
        assertTrue(
            order.get(cited) < order.get(citing),
            "重锚顺序违反 sha256 引用："
                + cited
                + "(order "
                + order.get(cited)
                + ") 的 sha256 出现在 "
                + citing
                + "(order "
                + order.get(citing)
                + ") 的字节中，被引用者必须先重写");
      }
    }
  }

  private static void assertHygiene(JsonNode hygiene) {
    assertTrue(hygiene.path("oracleChecked").asInt() > 0, "oracle 必须实跑过冻结行");
    assertEquals(0, hygiene.path("oracleViolations").asInt(), "反事实 evaluator 与生产函数必须同值");
    assertTrue(hygiene.path("tolerance").asDouble() > 0d, "容差必须为正");
    assertFalse(hygiene.path("noRerun").asText().isEmpty(), "必须声明未重算任何既有锚");
  }

  // ==================== 边界（R7 / 零外呼 / md） ====================

  private static void assertNoExternalCalls(JsonNode runtime) {
    assertEquals(0, runtime.path("externalCalls").asInt(-1), "零外呼：externalCalls 必须为 0");
    assertFalse(runtime.path("externalCallsBasis").asText().isEmpty(), "必须给出零外呼依据");
    assertTrue(
        runtime.path("excludedFromReplay").asBoolean(false), "runtime 必须标 excludedFromReplay");
  }

  private static void assertMdShape(String md, JsonNode anchor) {
    String body = md.endsWith("\n") ? md.substring(0, md.length() - 1) : md;
    String[] lines = body.split("\n", -1);
    assertTrue(lines.length <= 40, "md 必须 ≤ 40 行，实际 " + lines.length);
    assertTrue(md.contains("# near-dup-conditional-min-criterion-v1"), "md 必须带标题");
    assertTrue(md.contains("PROVEN-CONDITIONAL"), "md 必须写出 verdict");
    assertTrue(md.contains("executed = false"), "md 必须标 executed = false");
    assertTrue(md.contains("NEAR_DUPLICATE_FLOOR"), "md 必须写出目标常量");
    assertTrue(md.contains("0.9887"), "md 必须写出冻结的带内基准");
    long verdictRows = anchor.path("conditionalVerdict").path("rows").size();
    assertTrue(md.contains(String.valueOf(verdictRows)), "md 必须写出事件数 " + verdictRows);
    assertTrue(md.contains("Owner"), "md 必须写明决策归 Owner（不替 Owner 决策）");
  }

  private static void assertNoTendencyWords(String text) {
    for (String word : FORBIDDEN_TENDENCY_WORDS) {
      assertFalse(text.contains(word), "腿 B 清单不得出现倾向措辞: " + word);
    }
  }

  private static void assertNoCarriageReturn(byte[] bytes, String where) {
    for (byte value : bytes) {
      assertFalse(value == '\r', where + " 必须纯 LF（发现 CR）");
    }
  }

  private static String sha256(byte[] data) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(data);
    StringBuilder builder = new StringBuilder(hash.length * 2);
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }
}
