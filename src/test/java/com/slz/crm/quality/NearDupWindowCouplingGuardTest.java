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
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 33 守卫（near-dup-window-coupling，默认测试集内跑，只读，零外呼）。
 *
 * <p>与 {@link NearDupWindowCouplingAnalysisTest} 分工：分析类负责<b>生成</b>结论锚，本类负责<b>钉死</b>它——两者互不调用，本类的每个
 * 数字都是自己从卡 32 冻结锚重新算出来的。
 *
 * <ul>
 *   <li><b>R1</b>：12 条事件逐条复算（竞争者定位 / δ_r / margin_A / flipRisk），禁抽样；
 *   <li><b>R2</b>：两向断言——正向 {@code gapExceedsWindow ⇒ flipRisk=false}（8 条逐条）；逆否 {@code flipRisk ⇒
 *       gap ≤ window}（真实数据 0 条，空真已显式记锚；非空覆盖由 controls 的 (a)/(c) 两组提供）；
 *   <li><b>R3</b>：4 条假阳性归因恰为 3×COMPETITOR-SYNKS + 1×NO-DISPLACEMENT，两桶判据互不重叠；
 *   <li><b>R4</b>：{@code markedCount=12} / {@code displacedCount=8} / {@code
 *       triggerCount=displacedCount}，4 条 marked-but-not-displaced 逐条与 candidates 行对照；
 *   <li><b>R5</b>：三组构造对照复算（能报翻转 / 耦合托住 / 并列算风险）；
 *   <li><b>R6</b>：上游 4 项锚 SHA-256 实算、锚纯 LF、md ≤40 行、锚与 md 双面 grep 禁式（无阈值取值主张）。
 * </ul>
 */
class NearDupWindowCouplingGuardTest {

  private static final Path UPSTREAM_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.json");

  private static final Path REPORT_MD =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.md");

  /** 并列容差（与分析类同值；并列按「可能翻转」计，R5c）。 */
  private static final double TIE_EPS = 1e-12d;

  /** 两种等价 margin 写法之间的舍入容差（{@code gap−δ_e+δ_r} vs {@code (fusedB_e−δ_e)−(fusedB_r−δ_r)}）。 */
  private static final double ROUNDING_EPS = 1e-12d;

  private static final List<String> UPSTREAM_SHA_FIELDS =
      List.of("gateProbeAnchor", "s1Anchor", "crossMatrixAnchor", "exchangeRateAnchor");

  /** 阈值取值主张禁式（锚与 md 双面；本卡只定判据怎么算，不定阈值该多少）。 */
  private static final List<String> FORBIDDEN_PHRASES =
      List.of("建议改为", "推荐阈值", "应下调", "建议下调", "推荐取值");

  /** recommendation 类字段名禁式（如 recommendedThreshold）。 */
  private static final Pattern RECOMMEND_FIELD = Pattern.compile("\"recommend[a-zA-Z]*\"\\s*:");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode upstream;

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchors() throws Exception {
    upstream = readJson(UPSTREAM_ANCHOR);
    anchor = readJson(ANCHOR_JSON);
  }

  @Test
  @DisplayName("R1 · 12 条事件逐条复算：竞争者定位 / δ_r / margin_A / flipRisk 与卡32 锚逐字段对得上")
  void recomputesCouplingCriterionForEveryEvent() {
    JsonNode entries = upstream.path("windowCheck").path("entries");
    JsonNode events = anchor.path("events");
    assertEquals(12, entries.size(), "卡32 锚窗口事件必须 12 条");
    assertEquals(entries.size(), events.size(), "结论锚 events 必须与窗口事件等量（禁抽样）");
    int noCompetitor = 0;
    for (int index = 0; index < entries.size(); index++) {
      JsonNode entry = entries.get(index);
      JsonNode event = events.get(index);
      assertEquals(index, event.path("index").asInt(), "事件顺序必须与卡32 锚一致");
      assertEquals(
          entry.path("queryIndex").asInt(), event.path("queryIndex").asInt(), "queryIndex");
      assertEquals(
          entry.path("variantKey").asText(), event.path("variantKey").asText(), "variantKey");
      assertEquals(entry.path("chunkId").asText(), event.path("chunkId").asText(), "chunkId");
      assertEquals(entry.path("band").asText(), event.path("band").asText(), "band");
      assertEquals(entry.path("weight").asDouble(), event.path("weight").asDouble(), 0d, "weight");
      assertEquals(entry.path("window").asDouble(), event.path("window").asDouble(), 0d, "window");
      assertEquals(
          entry.path("gapExceedsWindow").asBoolean(),
          event.path("gapExceedsWindow").asBoolean(),
          "gapExceedsWindow");
      assertEquals(entry.path("delta").asDouble(), event.path("deltaE").asDouble(), 0d, "deltaE");

      JsonNode strongest = entry.path("strongestCompetitorBelowFusedB");
      if (strongest.isNull()) {
        noCompetitor++;
        assertTrue(
            event.path("competitorKey").isNull(),
            "无下位竞争者的条目不得凭空造出 competitorKey: q" + entry.path("queryIndex").asInt());
        assertTrue(
            event.path("marginA").isNull(),
            "无下位竞争者 ⇒ margin_A 无定义，必须记 null 而不是 0: q" + entry.path("queryIndex").asInt());
        assertFalse(
            event.path("flipRisk").asBoolean(),
            "没有下位竞争者就不可能被反超: q" + entry.path("queryIndex").asInt());
        continue;
      }

      int queryIndex = entry.path("queryIndex").asInt();
      JsonNode competitor =
          locateCompetitor(queryIndex, strongest.asDouble(), entry.path("variantKey").asText());
      double deltaR = deltaOf(queryIndex, competitor.path("variantKey").asText());
      double expectedMargin =
          (entry.path("fusedB").asDouble() - entry.path("delta").asDouble())
              - (strongest.asDouble() - deltaR);
      assertEquals(
          competitor.path("variantKey").asText(),
          event.path("competitorKey").asText(),
          "竞争者定位不一致（fusedB 精确匹配 + rankB 最小）: q" + queryIndex);
      assertEquals(
          competitor.path("chunkId").asText(),
          event.path("competitorChunkId").asText(),
          "竞争者 chunkId");
      assertEquals(
          strongest.asDouble(),
          event.path("competitorFusedB").asDouble(),
          0d,
          "竞争者 fusedB 必须等于 strongestCompetitorBelowFusedB");
      assertEquals(deltaR, event.path("deltaR").asDouble(), 0d, "δ_r 复算不一致: q" + queryIndex);
      assertEquals(
          expectedMargin, event.path("marginA").asDouble(), ROUNDING_EPS, "margin_A 复算不一致");
      assertEquals(
          expectedMargin <= TIE_EPS,
          event.path("flipRisk").asBoolean(),
          "flipRisk 复算不一致（margin_A ≤ 0 判翻转）: q" + queryIndex);
    }
    assertEquals(1, noCompetitor, "卡32 锚里恰 1 条无下位竞争者（q3 @p16-b2@crop-04，gap=null）");
  }

  @Test
  @DisplayName("R2 · 窗口是精确判据的上界：正向 8 条逐条 + 逆否（真实数据空真、controls 非空覆盖）")
  void windowIsOnlyAnUpperBoundFilter() {
    JsonNode events = anchor.path("events");
    int forwardChecked = 0;
    int contrapositiveChecked = 0;
    for (JsonNode event : events) {
      boolean flipRisk = event.path("flipRisk").asBoolean();
      if (event.path("gapExceedsWindow").asBoolean()) {
        forwardChecked++;
        assertFalse(
            flipRisk,
            "R2 正向失败：gap > window 却判可能翻转（窗口不是上界，判据方向错误）："
                + event.path("queryIndex").asInt()
                + " "
                + event.path("variantKey").asText());
      }
      if (flipRisk) {
        contrapositiveChecked++;
        assertFalse(event.path("gap").isNull(), "R2 逆否失败：判了翻转却没有 gap 可用");
        assertTrue(
            event.path("gap").asDouble() <= event.path("window").asDouble(),
            "R2 逆否失败：判了翻转却在窗口之外（窗口漏报，停手上报）："
                + event.path("queryIndex").asInt()
                + " "
                + event.path("variantKey").asText());
      }
    }
    assertEquals(8, forwardChecked, "卡32 锚 gapExceedsWindow=true 的必须 8 条");
    assertEquals(
        0, contrapositiveChecked, "卡32 锚 flipRisk=true 的必须 0 条（空真已记锚 r2.contrapositiveNote）");
    assertTrue(
        anchor.path("r2").path("contrapositiveHolds").asBoolean(),
        "锚 r2.contrapositiveHolds 必须为 true");

    int controlsChecked = 0;
    for (JsonNode control : anchor.path("controls")) {
      if (!control.path("flipRisk").asBoolean()) {
        continue;
      }
      controlsChecked++;
      assertTrue(
          control.path("gap").asDouble() <= control.path("windowUpperBound").asDouble(),
          "构造对照逆否也不成立：判翻转却 gap > window 上界: " + control.path("label").asText());
    }
    assertEquals(2, controlsChecked, "构造对照里必须有 2 组 flipRisk=true，用来非空覆盖逆否向");
  }

  @Test
  @DisplayName("R3 · 4 条 naive 假阳性归因：3×COMPETITOR-SYNKS + 1×NO-DISPLACEMENT，两桶互斥")
  void attributesFalsePositivesToTwoExclusiveBuckets() {
    JsonNode falsePositives = anchor.path("falsePositives");
    JsonNode entries = falsePositives.path("entries");
    assertEquals(4, falsePositives.path("count").asInt(), "假阳性必须 4 条");
    assertEquals(4, entries.size(), "假阳性明细必须逐条落锚");
    assertEquals(
        3,
        falsePositives.path("bucketCounts").path("COMPETITOR-SYNKS").asInt(),
        "COMPETITOR-SYNKS 桶计数必须 3");
    assertEquals(
        1,
        falsePositives.path("bucketCounts").path("NO-DISPLACEMENT").asInt(),
        "NO-DISPLACEMENT 桶计数必须 1");

    List<JsonNode> events = new ArrayList<>();
    for (JsonNode event : anchor.path("events")) {
      events.add(event);
    }
    int synks = 0;
    int noDisplacement = 0;
    for (JsonNode entry : entries) {
      double deltaE = entry.path("deltaE").asDouble();
      double deltaR = entry.path("deltaR").asDouble();
      double gap = entry.path("gap").asDouble();
      double marginA = entry.path("marginA").asDouble();
      String bucket = entry.path("bucket").asText();
      // 假阳性定义的两侧都复算一遍：gap ≤ window 且 margin_A > 0
      JsonNode source =
          findEvent(events, entry.path("queryIndex").asInt(), entry.path("variantKey").asText());
      assertTrue(
          gap <= source.path("window").asDouble(),
          "假阳性必须满足 gap ≤ window: " + entry.path("variantKey").asText());
      assertTrue(
          marginA > 0d, "假阳性必须满足 margin_A > 0（精确判据判不翻）: " + entry.path("variantKey").asText());
      assertEquals(
          gap - deltaE + deltaR, marginA, ROUNDING_EPS, "margin_A 与 gap − δ_e + δ_r 不一致（等价式）");
      assertTrue(
          bucket.equals("COMPETITOR-SYNKS") || bucket.equals("NO-DISPLACEMENT"),
          "归因桶只能两选一，出现第三类即停手: " + bucket);
      assertFalse(
          deltaR > 0d && deltaE == 0d,
          "两桶判据重叠（δ_r > 0 且 δ_e = 0）：归因不再互斥，停手: " + entry.path("variantKey").asText());
      if (bucket.equals("COMPETITOR-SYNKS")) {
        synks++;
        assertTrue(deltaR > 0d, "COMPETITOR-SYNKS 必须有 δ_r > 0");
        assertTrue(
            deltaR > deltaE - gap,
            "COMPETITOR-SYNKS 必须满足 δ_r > δ_e − gap: " + entry.path("variantKey").asText());
      } else {
        noDisplacement++;
        assertEquals(0d, deltaE, 0d, "NO-DISPLACEMENT 必须 δ_e = 0");
      }
    }
    assertEquals(3, synks, "逐条复算的 COMPETITOR-SYNKS 必须 3 条");
    assertEquals(1, noDisplacement, "逐条复算的 NO-DISPLACEMENT 必须 1 条");
  }

  @Test
  @DisplayName("R4 · marked ≠ displaced：12 标记 / 8 位移 / 触发量=位移，4 条未位移逐条对 candidates")
  void separatesMarkedFromDisplaced() {
    JsonNode counts = anchor.path("counts");
    JsonNode entries = upstream.path("windowCheck").path("entries");
    assertEquals(entries.size(), counts.path("markedCount").asInt(), "markedCount 必须等于窗口事件条数");
    assertEquals(12, counts.path("markedCount").asInt(), "markedCount 实测 12");
    assertEquals(8, counts.path("displacedCount").asInt(), "displacedCount 实测 8（delta > 0）");
    assertEquals(
        counts.path("displacedCount").asInt(),
        counts.path("triggerCount").asInt(),
        "判据触发量必须等于 displacedCount（不得用 markedCount）");
    assertTrue(
        counts.path("displacedCount").asInt() <= counts.path("markedCount").asInt(),
        "displacedCount 必须 ≤ markedCount");

    JsonNode notDisplaced = anchor.path("markedButNotDisplaced");
    assertEquals(
        counts.path("markedCount").asInt() - counts.path("displacedCount").asInt(),
        notDisplaced.size(),
        "markedButNotDisplaced 条数必须等于标记数 − 位移数");
    for (JsonNode entry : notDisplaced) {
      assertEquals(0d, entry.path("delta").asDouble(), 0d, "未位移条目 delta 必须为 0");
      assertEquals(
          entry.path("fusedA").asDouble(),
          entry.path("fusedB").asDouble(),
          0d,
          "未位移证据必须是 fusedA == fusedB");
      JsonNode candidate =
          candidateRow(entry.path("queryIndex").asInt(), entry.path("chunkId").asText());
      assertEquals(
          candidate.path("fusedA").asDouble(),
          entry.path("fusedA").asDouble(),
          0d,
          "未位移条目必须与 candidates 行逐字段对得上（fusedA）");
      assertEquals(
          candidate.path("fusedB").asDouble(),
          entry.path("fusedB").asDouble(),
          0d,
          "未位移条目必须与 candidates 行逐字段对得上（fusedB）");
      assertEquals(
          candidate.path("downWeighted").asBoolean(),
          entry.path("downWeighted").asBoolean(),
          "未位移条目的 downWeighted 必须与 candidates 行一致");
      assertTrue(entry.path("downWeighted").asBoolean(), "未位移条目必须仍是被标记降权的（否则不属本口径）");
      assertEquals(
          candidate.path("tier").asText(),
          entry.path("tier").asText(),
          "未位移条目的 tier 必须与 candidates 行一致");
      assertEquals("OQ1", entry.path("openQuestionId").asText(), "成因缺口必须挂到 openQuestions.OQ1");
    }

    JsonNode displacedEvents = anchor.path("displacedEvents");
    assertEquals(8, displacedEvents.size(), "位移事件明细必须 8 条");
    for (JsonNode event : displacedEvents) {
      assertTrue(event.path("delta").asDouble() > 0d, "位移事件明细里不得出现 delta = 0");
    }
    for (JsonNode event : anchor.path("events")) {
      if (event.path("deltaE").asDouble() > 0d) {
        continue;
      }
      assertEquals(
          1, countMatching(notDisplaced, event), "delta = 0 的事件必须出现在 markedButNotDisplaced 里");
    }
    assertEquals(
        "INCONCLUSIVE",
        anchor.path("openQuestions").get(0).path("status").asText(),
        "4 条 delta=0 的成因必须如实记 INCONCLUSIVE（D3：不许编因果）");
  }

  @Test
  @DisplayName("R5 · 判据可取反：三组构造对照（能报翻转 / 耦合托住 / 并列算风险）")
  void controlsAreFalsifiable() {
    JsonNode controls = anchor.path("controls");
    assertEquals(3, controls.size(), "对照必须恰 3 组（少一组判不合格，D5）");
    for (JsonNode control : controls) {
      double deltaE = control.path("deltaE").asDouble();
      double deltaR = control.path("deltaR").asDouble();
      double gap = control.path("gap").asDouble();
      double marginA = (gap - deltaE) - (0d - deltaR);
      assertEquals(marginA, control.path("marginA").asDouble(), ROUNDING_EPS, "对照 margin_A 复算不一致");
      assertEquals(marginA <= TIE_EPS, control.path("flipRisk").asBoolean(), "对照 flipRisk 复算不一致");
      String label = control.path("label").asText();
      switch (label) {
        case "a-deltaE-only" -> {
          assertTrue(control.path("flipRisk").asBoolean(), "(a) δ_e > 0 且 δ_r = 0 ⇒ 必须报翻转");
          assertTrue(control.path("tieBreak").isNull(), "(a) 不是并列，不得记 tieBreak");
        }
        case "b-both-sink-equally" -> {
          assertFalse(control.path("flipRisk").asBoolean(), "(b) δ_r == δ_e ⇒ 耦合托住，不得报翻转");
          assertEquals(
              gap, control.path("marginA").asDouble(), ROUNDING_EPS, "(b) margin_A 必须等于 gap");
        }
        case "c-equal-margin" -> {
          assertTrue(control.path("flipRisk").asBoolean(), "(c) margin_A = 0（并列）⇒ 必须报翻转");
          assertEquals(
              "EQUAL-MARGIN-COUNTS-AS-RISK",
              control.path("tieBreak").asText(),
              "(c) 并列必须记 tieBreak（朝更保守方向）");
        }
        default -> throw new AssertionError("出现未预期的对照组: " + label);
      }
    }
  }

  @Test
  @DisplayName("R6 · 上游锚 SHA 实算 + 锚纯 LF + md ≤40 行 + 锚与 md 双面禁式 grep")
  void upstreamAnchorsAndReportBoundaries() throws Exception {
    JsonNode sha256 = anchor.path("upstreamAnchors").path("sha256");
    assertEquals(4, sha256.size(), "上游锚 SHA 必须恰 4 项");
    for (String field : UPSTREAM_SHA_FIELDS) {
      JsonNode entry = sha256.path(field);
      Path file = Path.of(entry.path("path").asText());
      assertTrue(Files.exists(file), "上游锚不存在: " + file);
      String actual = RealPdfAssets.sha256(Files.readAllBytes(file));
      assertEquals(actual, entry.path("sha256").asText(), "上游锚 SHA-256 实算不一致: " + field);
      assertEquals(
          upstream.path("sourceAnchors").path(field + "Sha256").asText(),
          actual,
          "上游锚 SHA 与卡32 锚 sourceAnchors 不一致（文件漂移或手抄）: " + field);
      assertTrue(entry.path("matchesUpstream").asBoolean(), "matchesUpstream 必须为 true: " + field);
    }
    JsonNode readOnlyInputs = anchor.path("upstreamAnchors").path("readOnlyInputs");
    assertEquals(1, readOnlyInputs.size(), "readOnlyInputs 必须恰 1 项（卡32 锚本身）");
    assertEquals(
        RealPdfAssets.sha256(Files.readAllBytes(UPSTREAM_ANCHOR)),
        readOnlyInputs.get(0).path("sha256").asText(),
        "卡32 锚（只读输入）的 SHA-256 必须实算一致");

    byte[] anchorBytes = Files.readAllBytes(ANCHOR_JSON);
    assertTrue(
        !new String(anchorBytes, StandardCharsets.UTF_8).contains("\r"), "结论锚必须纯 LF（实测含 CR）");
    String anchorText = new String(anchorBytes, StandardCharsets.UTF_8);
    assertTrue(anchorText.endsWith("}\n"), "结论锚必须以单尾换行结束");

    assertTrue(Files.exists(REPORT_MD), "结论 md 必须随锚交付: " + REPORT_MD);
    String md = Files.readString(REPORT_MD, StandardCharsets.UTF_8);
    long mdLines = md.lines().count();
    assertTrue(mdLines <= 40, "结论 md 必须 ≤40 行（实测 " + mdLines + "）");
    assertFalse(md.contains("\r"), "结论 md 必须纯 LF");
    assertFalse(md.endsWith("\n\n"), "结论 md 不得以空行结尾");
    assertTrue(md.contains("margin_A"), "md 必须写清精确判据是 margin_A");
    assertTrue(md.contains("上界速筛"), "md 必须写清窗口只是上界速筛");
    assertTrue(
        md.contains("markedCount") && md.contains("displacedCount"), "md 必须写清 marked ≠ displaced");
    assertTrue(md.contains("0.30×(1−w)"), "md 必须点名被冻结的窗口公式");

    for (String phrase : FORBIDDEN_PHRASES) {
      assertFalse(md.contains(phrase), "结论 md 出现阈值取值主张措辞（禁式）: " + phrase);
      assertFalse(anchorText.contains(phrase), "结论锚出现阈值取值主张措辞（禁式）: " + phrase);
    }
    assertFalse(RECOMMEND_FIELD.matcher(anchorText).find(), "结论锚出现 recommendation 类字段名（禁式）");
    assertFalse(RECOMMEND_FIELD.matcher(md).find(), "结论 md 出现 recommendation 类字段名（禁式）");

    for (String required :
        List.of(
            "events",
            "falsePositives",
            "counts",
            "markedButNotDisplaced",
            "controls",
            "upstreamAnchors",
            "runtime")) {
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
  }

  // ==================== 复算帮手（本类独立实现，不复用分析类） ====================

  private static JsonNode locateCompetitor(int queryIndex, double fusedB, String selfKey) {
    JsonNode best = null;
    for (JsonNode candidate : upstream.path("candidates")) {
      if (candidate.path("queryIndex").asInt() != queryIndex) {
        continue;
      }
      if (candidate.path("variantKey").asText().equals(selfKey)) {
        continue;
      }
      if (Double.compare(candidate.path("fusedB").asDouble(), fusedB) != 0) {
        continue;
      }
      if (best == null || candidate.path("rankB").asInt() < best.path("rankB").asInt()) {
        best = candidate;
      }
    }
    assertTrue(best != null, "下位竞争者无法按 fusedB 精确匹配（锚被改动？）: q" + queryIndex + " fusedB=" + fusedB);
    return best;
  }

  private static double deltaOf(int queryIndex, String variantKey) {
    for (JsonNode entry : upstream.path("windowCheck").path("entries")) {
      if (entry.path("queryIndex").asInt() == queryIndex
          && entry.path("variantKey").asText().equals(variantKey)) {
        return entry.path("delta").asDouble();
      }
    }
    return 0d;
  }

  private static JsonNode candidateRow(int queryIndex, String chunkId) {
    for (JsonNode candidate : upstream.path("candidates")) {
      if (candidate.path("queryIndex").asInt() == queryIndex
          && candidate.path("chunkId").asText().equals(chunkId)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "candidates 里找不到对应行: queryIndex=" + queryIndex + " chunkId=" + chunkId);
  }

  private static int countMatching(JsonNode entries, JsonNode event) {
    int matches = 0;
    for (JsonNode entry : entries) {
      if (entry.path("queryIndex").asInt() == event.path("queryIndex").asInt()
          && entry.path("chunkId").asText().equals(event.path("chunkId").asText())) {
        matches++;
      }
    }
    return matches;
  }

  private static JsonNode findEvent(List<JsonNode> events, int queryIndex, String variantKey) {
    for (JsonNode event : events) {
      if (event.path("queryIndex").asInt() == queryIndex
          && event.path("variantKey").asText().equals(variantKey)) {
        return event;
      }
    }
    throw new AssertionError("events 里找不到对应事件: q" + queryIndex + " " + variantKey);
  }

  private static JsonNode readJson(Path path) throws Exception {
    assertTrue(Files.exists(path), "文件不存在: " + path.toAbsolutePath());
    return MAPPER.readTree(Files.readAllBytes(path));
  }
}
