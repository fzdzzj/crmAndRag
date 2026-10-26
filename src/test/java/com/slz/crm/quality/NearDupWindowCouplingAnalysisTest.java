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
 * 卡 33 · near-dup-window-coupling 分析（判据 R0–R8 / 决策 D1–D5 见 {@code
 * openspec/changes/near-dup-window-coupling/proposal.md}）。
 *
 * <p><b>零外呼</b>：本类唯一输入是卡 32 的冻结锚 {@code docs/rag-quality/near-dup-band-entry-v1.json}（只读、零字节变动），
 * 不启动 Spring 上下文、不引用任何 provider、不设任何 {@code *_REAL} opt-in。全部产出是算术。
 *
 * <ul>
 *   <li><b>腿 A（R1/R2）</b>：卡 32 的窗口判据 {@code gap > 0.30×(1−w) ⇒ 不可能翻转} 默认「下位竞争者不动」。本类改成精确耦合式 {@code
 *       margin_A(e,r) = (fusedB_e − δ_e) − (fusedB_r − δ_r)}，{@code flipRisk = margin_A ≤
 *       0}（并列按风险计）； 并证明窗口判据是该式的<b>保守特例</b>（充分不必要）：{@code gap > window ⇒ margin_A > 0} 恒成立，但 {@code
 *       gap ≤ window} 不等于会翻；
 *   <li><b>腿 B（R4）</b>：把「事件发生次数」拆成 {@code markedCount}（被标记，12）与 {@code displacedCount}（分数真的动，8）。
 *       <b>判据的触发量只能是 {@code displacedCount}</b>；
 *   <li><b>R5</b>：判据必须能取反——三组构造对照（不读锚、不跑检索）覆盖「能报翻转 / 耦合托住 / 并列算风险」；
 *   <li><b>R6</b>：结论锚 {@code docs/rag-quality/near-dup-window-coupling-v1.json}（纯 LF，写模式 {@code
 *       -Dnear.dup.window.coupling.write=true}，默认逐字节复算并剔除顶层 {@code runtime}）+ 同名 {@code .md}（≤40
 *       行）。
 * </ul>
 *
 * <p><b>不改常量</b>（D4）：{@code window = 0.30×(1−w)}、三档权重、{@code NEAR_DUPLICATE_FLOOR} 一个不动；本卡产出的是
 * 「判据该怎么算」，不是「阈值该定多少」。{@code IMAGE_ROUTE_WEIGHT_MIRROR} 只是公式镜像，不参与任何生产值。
 */
class NearDupWindowCouplingAnalysisTest {

  /** 本卡唯一数据源（卡 32 冻结锚，D1：只读，零字节变动）。 */
  private static final Path UPSTREAM_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.json");

  private static final Path ANCHOR_MD =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.md");

  /** 首次生成开关（-Dnear.dup.window.coupling.write=true）：常规运行只做逐字节比对，绝不落盘。 */
  private static final boolean WRITE_MODE = Boolean.getBoolean("near.dup.window.coupling.write");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 并列容差：{@code |margin_A| ≤ 1e-12} 视为并列，按「可能翻转」计（R5c / D5 只朝更保守方向）。 */
  private static final double TIE_EPS = 1e-12d;

  /** 窗口公式镜像（卡 32 {@code NearDupBandEntryIT:145} 同名常量按值引用；只用于复核窗口算术，不改任何生产值）。 */
  private static final double IMAGE_ROUTE_WEIGHT_MIRROR = 0.30d;

  /** 上游锚字段名（卡 32 锚 {@code sourceAnchors} 的四个条目；本卡逐项实算 SHA-256）。 */
  private static final List<String> UPSTREAM_SHA_FIELDS =
      List.of("gateProbeAnchor", "s1Anchor", "crossMatrixAnchor", "exchangeRateAnchor");

  @Test
  @DisplayName("卡33：耦合判据 margin_A + marked/displaced 解耦（读卡32冻结锚，零外呼）")
  void analyzesWindowCoupling() throws Exception {
    ObjectNode upstream = (ObjectNode) readJson(UPSTREAM_ANCHOR);
    assertEquals(
        30, upstream.path("candidates").size(), "卡32 锚 candidates 必须 30 行（6 查询 × TOP_K 5）");
    assertEquals(12, upstream.path("windowCheck").path("entries").size(), "卡32 锚窗口事件必须 12 条");
    assertEquals(
        12, upstream.path("downWeightedCount").asInt(), "卡32 锚 downWeightedCount 必须 12（被标记数）");

    List<Event> events = buildEvents(upstream);
    assertEquals(12, events.size(), "逐条事件必须恰 12 条（禁止抽样）");

    long marked = events.size();
    long displaced = events.stream().filter(event -> event.deltaE() > 0d).count();
    assertEquals(8L, displaced, "卡32 实测位移事件必须 8 条（delta > 0）");
    assertTrue(displaced <= marked, "displacedCount 必须 ≤ markedCount（位移 ⊂ 标记）");

    List<Event> falsePositives =
        events.stream()
            .filter(
                event -> event.gap() != null && event.gap() <= event.window() && !event.flipRisk())
            .toList();
    assertEquals(4, falsePositives.size(), "卡32 实测 naive 假阳性必须 4 条（gap ≤ window 却未翻）");
    assertEquals(
        3L,
        falsePositives.stream().filter(event -> "COMPETITOR-SYNKS".equals(event.bucket())).count(),
        "COMPETITOR-SYNKS 桶必须 3 条（下位竞争者同为被降权候选，两边同步下沉）");
    assertEquals(
        1L,
        falsePositives.stream().filter(event -> "NO-DISPLACEMENT".equals(event.bucket())).count(),
        "NO-DISPLACEMENT 桶必须 1 条（事件本身 δ_e = 0）");

    // R2 两向（逐条断言，禁抽样）
    int forwardChecked = 0;
    int contrapositiveChecked = 0;
    for (Event event : events) {
      if (event.gapExceedsWindow()) {
        forwardChecked++;
        assertFalse(event.flipRisk(), "R2 正向失败：gap > window 却判可能翻转（窗口不是上界）: " + event);
      }
      if (event.flipRisk()) {
        contrapositiveChecked++;
        assertTrue(
            event.gap() != null && event.gap() <= event.window(),
            "R2 逆否失败：判了翻转却在窗口之外（窗口漏报，判据方向错误，停手）: " + event);
      }
    }
    assertEquals(8, forwardChecked, "gapExceedsWindow == true 的事件必须 8 条");
    assertEquals(0, contrapositiveChecked, "卡32 实测 flipRisk == true 的事件必须 0 条");

    ObjectNode root = buildAnchor(upstream, events, falsePositives);
    writeOrCompareJson(ANCHOR_JSON, root);
    writeOrCompareBytes(
        ANCHOR_MD,
        buildAnchorMd(events, falsePositives, displaced).getBytes(StandardCharsets.UTF_8));

    System.out.println(
        "[near-dup-window] events="
            + events.size()
            + " marked="
            + marked
            + " displaced="
            + displaced
            + " flipRisk=true:"
            + contrapositiveChecked
            + " falsePositives="
            + falsePositives.size()
            + " (COMPETITOR-SYNKS="
            + falsePositives.stream().filter(e -> "COMPETITOR-SYNKS".equals(e.bucket())).count()
            + ", NO-DISPLACEMENT="
            + falsePositives.stream().filter(e -> "NO-DISPLACEMENT".equals(e.bucket())).count()
            + ")");
  }

  // ==================== R1：逐事件复算（δ / 竞争者 / margin_A / flipRisk） ====================

  private static List<Event> buildEvents(ObjectNode upstream) {
    Map<Integer, List<JsonNode>> candidatesByQuery = new LinkedHashMap<>();
    for (JsonNode candidate : upstream.path("candidates")) {
      candidatesByQuery
          .computeIfAbsent(candidate.path("queryIndex").asInt(), key -> new ArrayList<>())
          .add(candidate);
    }
    List<Event> events = new ArrayList<>();
    int index = 0;
    for (JsonNode entry : upstream.path("windowCheck").path("entries")) {
      int queryIndex = entry.path("queryIndex").asInt();
      String variantKey = entry.path("variantKey").asText();
      double deltaE = entry.path("delta").asDouble();
      double fusedA = entry.path("fusedA").asDouble();
      double fusedB = entry.path("fusedB").asDouble();
      double weight = entry.path("weight").asDouble();
      double window = entry.path("window").asDouble();
      boolean gapExceedsWindow = entry.path("gapExceedsWindow").asBoolean();
      Double gap = entry.path("gap").isNull() ? null : Double.valueOf(entry.path("gap").asDouble());
      assertTrue(
          Math.abs(window - IMAGE_ROUTE_WEIGHT_MIRROR * (1d - weight)) <= TIE_EPS,
          "窗口算术与 0.30×(1−w) 不符（卡32 锚被改动？）: q" + queryIndex + " weight=" + weight);

      Double marginA = null;
      boolean flipRisk = false;
      String tieBreak = null;
      Double deltaR = null;
      Double competitorFusedB = null;
      String competitorKey = null;
      String competitorChunkId = null;
      JsonNode strongest = entry.path("strongestCompetitorBelowFusedB");
      if (!strongest.isNull()) {
        JsonNode competitor =
            locateCompetitor(candidatesByQuery.get(queryIndex), strongest.asDouble(), variantKey);
        competitorFusedB = Double.valueOf(competitor.path("fusedB").asDouble());
        competitorKey = competitor.path("variantKey").asText();
        competitorChunkId = competitor.path("chunkId").asText();
        Double competitorDelta =
            competitor.path("downWeighted").asBoolean()
                ? deltaOf(upstream, queryIndex, competitorKey)
                : null;
        deltaR =
            competitorDelta != null && competitorDelta > 0d ? competitorDelta : Double.valueOf(0d);
        marginA = Double.valueOf((fusedB - deltaE) - (competitorFusedB.doubleValue() - deltaR));
        flipRisk = marginA.doubleValue() <= TIE_EPS;
        if (flipRisk && Math.abs(marginA.doubleValue()) <= TIE_EPS) {
          tieBreak = "EQUAL-MARGIN-COUNTS-AS-RISK";
        }
      }
      boolean falsePositive = gap != null && gap <= window && !flipRisk;
      events.add(
          new Event(
              index,
              queryIndex,
              entry.path("chunkId").asText(),
              variantKey,
              entry.path("band").asText(),
              weight,
              deltaE,
              fusedA,
              fusedB,
              competitorFusedB,
              competitorChunkId,
              competitorKey,
              deltaR,
              gap,
              window,
              gapExceedsWindow,
              marginA,
              flipRisk,
              tieBreak,
              falsePositive,
              falsePositive ? attribute(deltaE, deltaR) : null));
      index++;
    }
    return events;
  }

  /** 下位竞争者在同查询 candidates 中按 fusedB <b>精确匹配</b>；并列取 rankB 最小者（更保守，见 proposal §8）。 */
  private static JsonNode locateCompetitor(
      List<JsonNode> candidates, double fusedB, String selfKey) {
    assertTrue(candidates != null && !candidates.isEmpty(), "同查询 candidates 为空，无法定位竞争者");
    JsonNode best = null;
    int matches = 0;
    for (JsonNode candidate : candidates) {
      if (candidate.path("variantKey").asText().equals(selfKey)) {
        continue;
      }
      if (Double.compare(candidate.path("fusedB").asDouble(), fusedB) != 0) {
        continue;
      }
      matches++;
      if (best == null || candidate.path("rankB").asInt() < best.path("rankB").asInt()) {
        best = candidate;
      }
    }
    if (best == null) {
      throw new IllegalStateException(
          "R1 停手：strongestCompetitorBelowFusedB=" + fusedB + " 在同查询 candidates 中精确匹配不到竞争者行");
    }
    if (matches > 1) {
      System.out.println(
          "[near-dup-window] 竞争者并列 " + matches + " 行，取 rankB 最小者: " + best.path("variantKey"));
    }
    return best;
  }

  private static Double deltaOf(ObjectNode upstream, int queryIndex, String variantKey) {
    for (JsonNode entry : upstream.path("windowCheck").path("entries")) {
      if (entry.path("queryIndex").asInt() == queryIndex
          && entry.path("variantKey").asText().equals(variantKey)) {
        return Double.valueOf(entry.path("delta").asDouble());
      }
    }
    return null;
  }

  /**
   * R3 归因桶（两桶互不重叠，出现第三类即停手）：{@code COMPETITOR-SYNKS} ⇔ δ_r > 0（竞争者被托住）；{@code NO-DISPLACEMENT} ⇔
   * δ_e = 0（事件本身没位移）。
   */
  private static String attribute(double deltaE, Double deltaR) {
    boolean synks = deltaR != null && deltaR > 0d;
    boolean noDisplacement = deltaE == 0d;
    if (synks && noDisplacement) {
      throw new IllegalStateException("R3 停手：归因判据重叠（δ_r > 0 且 δ_e = 0），两桶不再互斥");
    }
    if (synks) {
      return "COMPETITOR-SYNKS";
    }
    if (noDisplacement) {
      return "NO-DISPLACEMENT";
    }
    throw new IllegalStateException("R3 停手：归因出现第三类（δ_e=" + deltaE + ", δ_r=" + deltaR + "），不得自创桶");
  }

  // ==================== R6：结论锚 ====================

  private static ObjectNode buildAnchor(
      ObjectNode upstream, List<Event> events, List<Event> falsePositives) throws Exception {
    long displaced = events.stream().filter(event -> event.deltaE() > 0d).count();
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-window-coupling");
    root.put("stage", "WINDOW-COUPLING");
    root.put("decisionVersion", "NEAR-DUP-WINDOW-COUPLING-1.0");
    root.put("decisionOwner", "OWNER");
    root.put("upstreamAnchor", "docs/rag-quality/near-dup-band-entry-v1.json");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "R0", "零外呼：不设任何 *_REAL opt-in，不 instance 真 provider，不启动 Spring 上下文；输入只有卡32 冻结锚 + 本类算术");
    criteria.put(
        "R1",
        "精确判据（耦合）：δ_i := fusedB_i − fusedA_i；margin_A(e,r) := (fusedB_e − δ_e) − (fusedB_r − δ_r)；flipRisk := margin_A ≤ 0；竞争者按 fusedB 精确匹配、并列取 rankB 最小者；r 未降权或不在窗口事件内 ⇒ δ_r = 0");
    criteria.put(
        "R2",
        "保守性：δ_e ≤ window_e 且 δ_r ≥ 0 ⇒ gap > window_e ⇒ margin_A > 0（窗口是精确判据的保守特例）；两向逐条断言：正向 gapExceedsWindow ⇒ flipRisk=false，逆否 flipRisk ⇒ gap ≤ window");
    criteria.put(
        "R3",
        "假阳性刻画：gap ≤ window 且 flipRisk=false ⟺ δ_r > δ_e − gap；归因两桶 COMPETITOR-SYNKS / NO-DISPLACEMENT 互斥，实测 3 + 1");
    criteria.put(
        "R4",
        "marked ≠ displaced：markedCount = 窗口事件条数（12）、displacedCount = delta > 0 条数（8）；判据触发量只能是 displacedCount；markedButNotDisplaced 逐条落身份与证据字段");
    criteria.put(
        "R5", "判据可取反：三组构造对照（δ_e>0 且 δ_r=0 报翻转 / δ_r=δ_e 耦合托住 / margin_A=0 并列算风险），不读锚、不跑检索");
    criteria.put(
        "R6",
        "锚与本 md 落地：锚纯 LF 且默认逐字节复算（剔除顶层 runtime），md ≤40 行；守卫 NearDupWindowCouplingGuardTest 零外呼钉死 R1–R5 与上游锚 SHA 实算");
    criteria.put("R7", "边界：src/main 零 diff、pom.xml 与 .github/** 零改、既有锚零字节变动、卡32 的 IT/守卫/语料类零修改");
    criteria.put("R8", "判据修订记：R1–R5 任一不可满足即停手上报并记 proposal §8，不改锚、不反解、不放宽断言换绿");

    ObjectNode formulas = root.putObject("formulas");
    formulas.put(
        "delta", "δ_i := fusedB_i − fusedA_i（事件 e 的 δ_e 直接取 windowCheck.entries[i].delta，D2 不反解）");
    formulas.put(
        "marginA",
        "margin_A(e,r) := (fusedB_e − δ_e) − (fusedB_r − δ_r)；等价于 margin_A = gap − δ_e + δ_r");
    formulas.put(
        "flipRisk",
        "flipRisk := margin_A ≤ " + TIE_EPS + "（并列 |margin_A| ≤ " + TIE_EPS + " 按「可能翻转」计，R5c）");
    formulas.put(
        "window", "window := " + IMAGE_ROUTE_WEIGHT_MIRROR + " × (1 − w)（卡32 原式，本卡未修改任何常量）");
    formulas.put("gap", "gap := fusedB_该候选 − 同查询下位竞争者最大 fusedB（卡32 原字段，本卡只读不复算）");

    ObjectNode r2 = root.putObject("r2");
    r2.put("forwardChecked", (int) events.stream().filter(Event::gapExceedsWindow).count());
    r2.put("forwardHolds", true);
    r2.put("contrapositiveChecked", (int) events.stream().filter(Event::flipRisk).count());
    r2.put("contrapositiveHolds", true);
    r2.put(
        "contrapositiveNote",
        "真实数据上 flipRisk=true 的条数为 0 ⇒ 该向断言在锚上空真（VACUOUS）；非空可证性由 controls 里 (a)/(c) 两组构造提供"
            + "（构造数据 flipRisk=true 且 gap ≤ windowUpperBound）");
    r2.put(
        "proofSketch",
        "实测 δ_e ≤ window_e（位移不超过窗口上限），且 δ_r ≥ 0 ⇒ margin_A = gap − δ_e + δ_r ≥ gap − window_e > 0，"
            + "故 gap > window_e ⇒ flipRisk = false：窗口判据 ⇒ 精确判据，反向不成立（充分不必要）");

    ArrayNode eventsNode = root.putArray("events");
    for (Event event : events) {
      ObjectNode node = eventsNode.addObject();
      node.put("index", event.index());
      node.put("queryIndex", event.queryIndex());
      node.put("variantKey", event.variantKey());
      node.put("chunkId", event.chunkId());
      node.put("band", event.band());
      node.put("weight", event.weight());
      node.put("fusedA", event.fusedA());
      node.put("fusedB", event.fusedB());
      node.put("deltaE", event.deltaE());
      putNullableText(node, "competitorKey", event.competitorKey());
      putNullableText(node, "competitorChunkId", event.competitorChunkId());
      putNullableNumber(node, "competitorFusedB", event.competitorFusedB());
      putNullableNumber(node, "deltaR", event.deltaR());
      putNullableNumber(node, "gap", event.gap());
      node.put("window", event.window());
      node.put("gapExceedsWindow", event.gapExceedsWindow());
      putNullableNumber(node, "marginA", event.marginA());
      node.put("flipRisk", event.flipRisk());
      putNullableText(node, "tieBreak", event.tieBreak());
      node.put("falsePositive", event.falsePositive());
      putNullableText(node, "bucket", event.bucket());
    }

    ObjectNode counts = root.putObject("counts");
    counts.put("markedCount", events.size());
    counts.put("displacedCount", displaced);
    counts.put("triggerCount", displaced);
    counts.put(
        "triggerRule", "判据的触发量是 displacedCount（分数真的动了），不是 markedCount（被标记）；markedCount 只作事件总数上界");
    counts.put("markedButNotDisplacedCount", events.size() - displaced);
    counts.put(
        "invariant",
        "displacedCount ≤ markedCount；差值 "
            + (events.size() - displaced)
            + " 条为「标记了但 fusedA == fusedB」");

    ObjectNode falsePositiveNode = root.putObject("falsePositives");
    falsePositiveNode.put("definition", "gap ≤ window 且 flipRisk = false：naive 窗口判「可能翻转」，精确判据判不翻");
    falsePositiveNode.put("count", falsePositives.size());
    ObjectNode bucketCounts = falsePositiveNode.putObject("bucketCounts");
    bucketCounts.put(
        "COMPETITOR-SYNKS",
        (int) falsePositives.stream().filter(e -> "COMPETITOR-SYNKS".equals(e.bucket())).count());
    bucketCounts.put(
        "NO-DISPLACEMENT",
        (int) falsePositives.stream().filter(e -> "NO-DISPLACEMENT".equals(e.bucket())).count());
    falsePositiveNode.put(
        "mutualExclusion",
        "COMPETITOR-SYNKS ⇔ δ_r > 0；NO-DISPLACEMENT ⇔ δ_e = 0；实测无一条同时满足（重叠或都不满足即停手）");
    falsePositiveNode.put(
        "bucketRule",
        "gap ≤ window 且 flipRisk=false ⟺ δ_r > δ_e − gap；δ_r > 0 时归 COMPETITOR-SYNKS（同步下沉托住），否则 δ_e = 0 归 NO-DISPLACEMENT");
    ArrayNode falsePositiveEntries = falsePositiveNode.putArray("entries");
    for (Event event : falsePositives) {
      ObjectNode node = falsePositiveEntries.addObject();
      node.put("queryIndex", event.queryIndex());
      node.put("variantKey", event.variantKey());
      node.put("chunkId", event.chunkId());
      putNullableText(node, "competitorKey", event.competitorKey());
      node.put("deltaE", event.deltaE());
      node.put("deltaR", event.deltaR() == null ? 0d : event.deltaR().doubleValue());
      node.put("gap", event.gap() == null ? 0d : event.gap().doubleValue());
      node.put("marginA", event.marginA() == null ? 0d : event.marginA().doubleValue());
      node.put("bucket", event.bucket());
      node.put(
          "bucketEvidence",
          "COMPETITOR-SYNKS".equals(event.bucket())
              ? "δ_r("
                  + event.deltaR()
                  + ") > δ_e − gap("
                  + (event.deltaE() - event.gap())
                  + ")：竞争者下沉更多，相对次序不变"
              : "δ_e = 0：被标记降权但融合分未变，事件本身无位移");
    }

    ArrayNode notDisplaced = root.putArray("markedButNotDisplaced");
    for (Event event : events) {
      if (event.deltaE() > 0d) {
        continue;
      }
      JsonNode candidateRow = candidateRow(upstream, event.queryIndex(), event.chunkId());
      ObjectNode node = notDisplaced.addObject();
      node.put("queryIndex", event.queryIndex());
      node.put("variantKey", event.variantKey());
      node.put("chunkId", event.chunkId());
      node.put(
          "tier", candidateRow.path("tier").isNull() ? null : candidateRow.path("tier").asText());
      node.put("downWeighted", candidateRow.path("downWeighted").asBoolean());
      node.put("weight", event.weight());
      node.put("delta", event.deltaE());
      node.put("fusedA", event.fusedA());
      node.put("fusedB", event.fusedB());
      node.put("deltaZeroEvidence", "fusedA == fusedB（同 candidates 行同名字段逐位相等）");
      node.put("openQuestionId", "OQ1");
    }

    ArrayNode displacedEvents = root.putArray("displacedEvents");
    for (Event event : events) {
      if (event.deltaE() <= 0d) {
        continue;
      }
      ObjectNode node = displacedEvents.addObject();
      node.put("queryIndex", event.queryIndex());
      node.put("variantKey", event.variantKey());
      node.put("chunkId", event.chunkId());
      node.put("weight", event.weight());
      node.put("fusedA", event.fusedA());
      node.put("fusedB", event.fusedB());
      node.put("delta", event.deltaE());
      node.put("gap", event.gap() == null ? 0d : event.gap().doubleValue());
      node.put("marginA", event.marginA() == null ? 0d : event.marginA().doubleValue());
      node.put("flipRisk", event.flipRisk());
    }

    ArrayNode controls = root.putArray("controls");
    for (Control control : controls()) {
      ObjectNode node = controls.addObject();
      node.put("label", control.label());
      node.put("deltaE", control.deltaE());
      node.put("deltaR", control.deltaR());
      node.put("gap", control.gap());
      node.put("windowUpperBound", control.windowUpperBound());
      node.put("marginA", control.marginA());
      node.put("flipRisk", control.flipRisk());
      putNullableText(node, "tieBreak", control.tieBreak());
      node.put("expectation", control.expectation());
    }

    ArrayNode openQuestions = root.putArray("openQuestions");
    ObjectNode question = openQuestions.addObject();
    question.put("id", "OQ1");
    question.put("subject", "4 条 delta = 0 的成因（被标记降权却 fusedA == fusedB）");
    question.put("status", "INCONCLUSIVE");
    ArrayNode missing = question.putArray("missingFields");
    missing.add("逐候选降权前/后的融合分（fusedBeforeDownWeight / fusedAfterDownWeight）");
    missing.add("降权是否真的命中的记录（downWeightApplied 之类的布尔字段）");
    missing.add("图像路 rerank 分量（imageRerankScoreA / imageRerankScoreB）");
    question.put(
        "availableEvidence",
        "锚内只有 tier / downWeighted / fusedA / fusedB / cos：能证明「δ_e = 0」，不能区分「降权已应用但融合分未变」与「标记了但未应用」");
    question.put("rule", "D3：不新跑检索、不编因果；缺字段就如实记缺口");

    ObjectNode conclusions = root.putObject("conclusions");
    conclusions.put(
        "windowIsUpperBoundFilter",
        "gapExceedsWindow=true 的 8 条 flipRisk 全为 false（8/8）：窗口说「不可能翻」从不出错；但 gap ≤ window 的 4 条一条都没翻 ⇒ 窗口只能作上界速筛");
    conclusions.put(
        "exactCriterion", "精确判据是 margin_A（事件与下位竞争者两侧位移都算进去）；窗口判据是它的保守特例（充分不必要），二者可并存但「可能」不等于「会」");
    conclusions.put(
        "markedNeDisplaced",
        "downWeightedCount = 12 记的是「被标记」12 次；判据的触发量 displacedCount = 8，另 4 条 fusedA == fusedB 未产生位移");

    root.put(
        "thresholdDisposition",
        "本锚不主张任何阈值取值：0.30×(1−w) 与三档权重 0.75/0.60/0.50 原样引用卡32，一个不动；本卡只定「判据怎么算」");

    ObjectNode upstreamAnchors = root.putObject("upstreamAnchors");
    ObjectNode readOnlyInputs = upstreamAnchors.putArray("readOnlyInputs").addObject();
    readOnlyInputs.put("path", "docs/rag-quality/near-dup-band-entry-v1.json");
    readOnlyInputs.put("role", "本卡唯一数据源（D1：只读，零字节变动）");
    readOnlyInputs.put("sha256", RealPdfAssets.sha256(Files.readAllBytes(UPSTREAM_ANCHOR)));
    ObjectNode sha256 = upstreamAnchors.putObject("sha256");
    for (String field : UPSTREAM_SHA_FIELDS) {
      Path file = Path.of(upstream.path("sourceAnchors").path(field).asText());
      ObjectNode entry = sha256.putObject(field);
      entry.put("path", upstream.path("sourceAnchors").path(field).asText());
      entry.put("sha256", RealPdfAssets.sha256(Files.readAllBytes(file)));
      entry.put(
          "matchesUpstream",
          upstream
              .path("sourceAnchors")
              .path(field + "Sha256")
              .asText()
              .equals(RealPdfAssets.sha256(Files.readAllBytes(file))));
    }

    ObjectNode runtime = root.putObject("runtime");
    runtime.put("externalCalls", 0);
    runtime.putArray("excludedFromReplay").add("runtime");
    runtime.put("note", "本卡零外呼：输入只有卡32 冻结锚 + 本类算术；本节点不参与默认复跑的逐字节比对（剔除顶层 runtime）");
    return root;
  }

  private static JsonNode candidateRow(ObjectNode upstream, int queryIndex, String chunkId) {
    for (JsonNode candidate : upstream.path("candidates")) {
      if (candidate.path("queryIndex").asInt() == queryIndex
          && candidate.path("chunkId").asText().equals(chunkId)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "candidates 里找不到事件对应行: queryIndex=" + queryIndex + " chunkId=" + chunkId);
  }

  /** R5 三组构造对照（不读锚、不跑检索）：与事件同一算术序，保证判据可被取反。 */
  private static List<Control> controls() {
    List<Control> controls = new ArrayList<>();
    controls.add(
        control(
            "a-deltaE-only",
            0.05d,
            0d,
            0.03d,
            "δ_e > 0 且 δ_r = 0，gap < δ_e ⇒ margin_A = gap − δ_e < 0：判据能报翻转"));
    controls.add(
        control(
            "b-both-sink-equally",
            0.05d,
            0.05d,
            0.03d,
            "δ_r = δ_e ⇒ margin_A = gap = 0.03 > 0：耦合托住，两侧同步下沉不翻转"));
    controls.add(
        control(
            "c-equal-margin",
            0.05d,
            0.02d,
            0.03d,
            "δ_e − δ_r = gap ⇒ margin_A = 0（并列）⇒ flipRisk = true，并按更保守方向记 tieBreak"));
    return controls;
  }

  private static Control control(
      String label, double deltaE, double deltaR, double gap, String expectation) {
    double marginA = (gap - deltaE) - (0d - deltaR);
    boolean flipRisk = marginA <= TIE_EPS;
    String tieBreak =
        flipRisk && Math.abs(marginA) <= TIE_EPS ? "EQUAL-MARGIN-COUNTS-AS-RISK" : null;
    return new Control(
        label, deltaE, deltaR, gap, deltaE, marginA, flipRisk, tieBreak, expectation);
  }

  private static String buildAnchorMd(
      List<Event> events, List<Event> falsePositives, long displaced) {
    StringBuilder md = new StringBuilder();
    md.append("# near-dup-window-coupling-v1 · 窗口是上界速筛，精确判据是 margin_A\n");
    md.append("\n");
    md.append("- 卡 33（零外呼判据卡）：只读卡 32 冻结锚 `docs/rag-quality/near-dup-band-entry-v1.json` + 纯算术。\n");
    md.append(
        "- 输入实测：`candidates` 30 行（6 查询 × TOP_K 5）、`windowCheck.entries` 12 条、`downWeightedCount` 12、`verdict` NO-RANK-CHANGE。\n");
    md.append("\n");
    md.append("## 结论 1 · 窗口只能当上界速筛，精确判据是 margin_A\n");
    md.append("\n");
    md.append("- 现行窗口：`window = 0.30×(1−w)`，判法 `gap > window ⇒ 不可能翻转`；它默认下位竞争者不动。\n");
    md.append(
        "- 精确判据（耦合）：`δ_i = fusedB_i − fusedA_i`；`margin_A(e,r) = (fusedB_e − δ_e) − (fusedB_r − δ_r)`；`flipRisk = margin_A ≤ 0`。\n");
    md.append(
        "- 保序：`δ_e ≤ window_e` 且 `δ_r ≥ 0` ⇒ `gap > window_e ⇒ margin_A > 0`，即窗口判据是精确判据的保守特例（充分不必要）。\n");
    md.append(
        "- 实测：12 条事件 `flipRisk = true` 的 **0 条**（逆否在真实数据上空真，非空可证性由构造对照提供）；`gapExceedsWindow = true` 的 8 条全部 `flipRisk = false`（8/8 逐条）。\n");
    md.append("- `gap ≤ window` 的 4 条一条都没翻 ⇒ 窗口说「可能」不等于「会」。\n");
    md.append("\n");
    md.append("## 结论 2 · marked ≠ displaced\n");
    md.append("\n");
    md.append(
        "- `markedCount = 12`（被标记 = `entries` 条数）；`displacedCount = 8`（分数真的动 = `delta > 0`）；`triggerCount = displacedCount`。\n");
    md.append(
        "- 判据的触发量只能是 `displacedCount`：`downWeightedCount = 12` 记的是「被标记」12 次，其中 4 条 `fusedA == fusedB` 分数没动。\n");
    md.append(
        "- 4 条 `delta = 0` 的身份：q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`（`tier` 均为 NEAR_DUPLICATE，`downWeighted = true`）。\n");
    md.append(
        "- 其成因 **INCONCLUSIVE**（决策 D3）：锚内没有「降权是否真的应用」的字段，不新跑检索、不编因果，缺口记在锚 `openQuestions.OQ1`。\n");
    md.append("\n");
    md.append("## 4 条 naive 假阳性（`gap ≤ window` 却未翻）归因\n");
    md.append("\n");
    md.append("| 查询 | 事件 | δ_e | δ_r | gap | margin_A | 归因桶 |\n");
    md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
    for (Event event : falsePositives) {
      md.append(
          String.format(
              Locale.ROOT,
              "| q%d | `%s` | %.6f | %.6f | %.6f | %.6f | %s |\n",
              event.queryIndex(),
              event.variantKey().replace("nist-sp800-101r1-", ""),
              event.deltaE(),
              event.deltaR() == null ? 0d : event.deltaR().doubleValue(),
              event.gap(),
              event.marginA(),
              event.bucket()));
    }
    md.append("\n");
    md.append(
        "- 3×`COMPETITOR-SYNKS`：q0/q1/q2 的 `@crop-02`，下位竞争者都是同查询的 `@crop-04`，`δ_r`(0.075 / 0.045 / 0.075) 大于该事件的 `δ_e`，两边同步下沉 ⇒ 相对次序不变。\n");
    md.append("- 1×`NO-DISPLACEMENT`：q5 `p16-b2@crop-04` 自身 `δ_e = 0`，根本没有位移可谈。\n");
    md.append("\n");
    md.append("## 边界\n");
    md.append("\n");
    md.append("- **不改任何阈值 / 窗口常量**：`0.30×(1−w)`、三档权重 0.75/0.60/0.50 原样引用卡 32，一个不动；本卡只定「判据怎么算」。\n");
    md.append(
        "- 零外呼：无 `*_REAL`、无 Spring 上下文、无 provider；上游 4 项锚 SHA-256 实算已核对（锚 `upstreamAnchors.sha256`）。\n");
    md.append(
        "- 细节锚：`docs/rag-quality/near-dup-window-coupling-v1.json`（默认按本次全量重算逐字节比对，剔除顶层 `runtime`）。\n");
    String text = md.toString();
    assertTrue(!text.contains("\r"), "锚 md 必须纯 LF");
    assertFalse(text.endsWith("\n\n"), "锚 md 不得以空行结尾");
    return text;
  }

  // ==================== 读写锚（写模式 / 逐字节比对，剔除顶层 runtime） ====================

  private static void writeOrCompareJson(Path path, ObjectNode node) throws Exception {
    byte[] expected = canonicalBytes(node);
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "锚 JSON 必须纯 LF");
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论锚缺失，先以 -Dnear.dup.window.coupling.write=true 生成一次: " + path.toAbsolutePath());
    JsonNode onDisk = readJson(path);
    assertArrayEquals(
        canonicalBytes(withoutRuntime(node)),
        canonicalBytes(withoutRuntime(onDisk)),
        "锚与本次全量重算逐字节不一致（剔除顶层 runtime 后）：锚被改动或链路行为漂移: " + path);
    System.out.println("[near-dup-window] 默认复跑逐字节一致（剔除顶层 runtime）：" + path);
  }

  private static void writeOrCompareBytes(Path path, byte[] expected) throws Exception {
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "锚 md 必须纯 LF");
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论 md 缺失，先以 -Dnear.dup.window.coupling.write=true 生成一次: " + path.toAbsolutePath());
    assertArrayEquals(expected, Files.readAllBytes(path), "锚 md 与本次生成逐字节不一致: " + path);
    System.out.println("[near-dup-window] 默认复跑逐字节一致（md）：" + path);
  }

  private static void writeAnchor(Path path, byte[] content) throws Exception {
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, content);
    System.out.println("[near-dup-window] 锚已写入（write 模式）: " + path);
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

  private static JsonNode readJson(Path path) throws Exception {
    assertTrue(Files.exists(path), "文件不存在: " + path.toAbsolutePath());
    return MAPPER.readTree(Files.readAllBytes(path));
  }

  private static void putNullableNumber(ObjectNode node, String field, Double value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.doubleValue());
    }
  }

  private static void putNullableText(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  /** 单条降权事件与耦合判据结果；字段名与锚内 {@code events[]} 一一对应（板规 28）。 */
  private record Event(
      int index,
      int queryIndex,
      String chunkId,
      String variantKey,
      String band,
      double weight,
      double deltaE,
      double fusedA,
      double fusedB,
      Double competitorFusedB,
      String competitorChunkId,
      String competitorKey,
      Double deltaR,
      Double gap,
      double window,
      boolean gapExceedsWindow,
      Double marginA,
      boolean flipRisk,
      String tieBreak,
      boolean falsePositive,
      String bucket) {}

  /** R5 构造对照的一条（{@code windowUpperBound} 取 {@code δ_e}：δ_e ≤ window 的最紧合法上界）。 */
  private record Control(
      String label,
      double deltaE,
      double deltaR,
      double gap,
      double windowUpperBound,
      double marginA,
      boolean flipRisk,
      String tieBreak,
      String expectation) {}
}
