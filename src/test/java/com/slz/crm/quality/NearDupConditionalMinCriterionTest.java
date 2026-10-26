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
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.math.BigDecimal;
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
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 36 · near-dup-conditional-min-criterion（腿 A：把卡 35 撞死的「末位」判据改成条件化三分支；腿 B：0.90 的影响与重锚清单）。
 *
 * <p><b>零外呼</b>（R0）：输入是卡 32/33/34/35 四张冻结锚 + 生产纯函数 + 源码字面；不启动 Spring、不 instance provider、
 * 不调任何模型、不设任何 {@code *_REAL}、不跑检索。
 *
 * <p><b>腿 A</b>：把 {@code r = wv·normVec + wb·normBm25} 按 bm25 分支的状态拆成互斥穷尽的三支（全 0 / 全 1 / 非退化），
 * 逐支给出「{@code r = 0} 的充要条件」，再按「所属分支 + 该分支条件」逐条复判卡 35 锚的 12 条被标记事件。分支判定只用 {@code normBm25}
 * 的整值全同判定（{@code max <= 0} / {@code max == min > 0} / 其余），不做数值拟合（D2）。
 *
 * <p><b>腿 B</b>：逐张核对 16 张既有锚「有没有把 {@code tier}/{@code band}/{@code bandSide} 分类结果写死」（给字段路径 +
 * 样例），并判「{@code NEAR_DUPLICATE_FLOOR} 降到 0.90 后是否自相矛盾」「能否零外呼重算（依据：是否冻了 cos 或向量）」。 反事实分类用本类显式的
 * {@code floor} 参数求值，内边界沿用生产的 {@code bandReferenceFloor + Δ/3} / {@code + 2Δ/3} 形式，并以生产 {@code
 * tierOf}/{@code bandOf} 为 oracle 逐行校验，不构成第二把尺子。**不改常量、不执行重建、不给倾向值**（D4/D5）。
 *
 * <p><b>写模式</b> {@code -Dnear.dup.conditional.min.write=true}；默认模式按本次全量重算<b>逐字节</b>比对（JSON 走树规范化并剔除
 * 顶层 {@code runtime}；md 走真字节）。
 */
class NearDupConditionalMinCriterionTest {

  private static final Path BAND_ENTRY =
      Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  private static final Path WINDOW_COUPLING =
      Path.of("docs", "rag-quality", "near-dup-window-coupling-v1.json");

  private static final Path DISPLACEMENT_EVIDENCE =
      Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json");

  private static final Path RERANK_DECOMPOSITION =
      Path.of("docs", "rag-quality", "near-dup-rerank-decomposition-v1.json");

  private static final Path FLOOR_SIGNOFF =
      Path.of("docs", "rag-quality", "near-dup-floor-signoff-v1.json");

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "near-dup-conditional-min-criterion-v1.json");

  private static final Path ANCHOR_MD =
      Path.of("docs", "rag-quality", "near-dup-conditional-min-criterion-v1.md");

  /** 首次生成开关：常规运行只做逐字节复算比对，绝不落盘。 */
  private static final boolean WRITE_MODE = Boolean.getBoolean("near.dup.conditional.min.write");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 生产判别阈值（直读 {@link ImageSimilarityGate#DEFAULT_THRESHOLD}，非手写常量）。 */
  private static final double THRESHOLD = ImageSimilarityGate.DEFAULT_THRESHOLD;

  /** 现行生产准入下限（直读 {@link ImageSimilarityGate#NEAR_DUPLICATE_FLOOR}；本卡不改它）。 */
  private static final double CURRENT_FLOOR = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR;

  /** 带内基准（直读 {@link ImageSimilarityGate#BAND_REFERENCE_FLOOR}；冻结，不随准入下限缩放）。 */
  private static final double BAND_REFERENCE = ImageSimilarityGate.BAND_REFERENCE_FLOOR;

  /** 历史准入下限（签收锚 {@code previousFloor}，也是多数既有锚的 {@code nearDuplicateFloor} 记录口径）。 */
  private static final double LEGACY_FLOOR = 0.9887d;

  /** 议题里的目标值（本卡只做反事实判定，不改常量）。 */
  private static final double PROPOSED_FLOOR = 0.90d;

  /** 恒等式与反事实判定的数值容差。 */
  private static final double TOLERANCE = 1e-12d;

  private static final String B0 = "B0-BM25-ALL-ZERO";

  private static final String B1 = "B1-BM25-ALL-ONE";

  private static final String B2 = "B2-BM25-NON-DEGENERATE";

  private static final String PROVEN = "PROVEN";

  private static final String PROVEN_CONDITIONAL = "PROVEN-CONDITIONAL";

  private static final String REFUTED = "REFUTED";

  private static final String UNDETERMINED = "UNDETERMINED";

  private static final String YES = "YES";

  private static final String NO = "NO";

  private static final String NOT_APPLICABLE = "NOT-APPLICABLE";

  /** 分类字段名（值层面；{@code bandSide} 单独计入格式检查，不参与行级反事实）。 */
  private static final Set<String> CLASSIFICATION_KEYS =
      Set.of("tier", "band", "bandSide", "imageSimilarityTier", "imageSimilarityBand");

  private static final Pattern FLOOR_KEY = Pattern.compile("(?i).*floor.*");

  private static final Pattern AGGREGATE_KEY =
      Pattern.compile(
          "(?i).*(passCount|passRate|belowFloor|aboveFloor|atOrAboveFloor|inBand|bandCross|bandSame"
              + "|thirdImage|retainedNearDup|keptSame|hasCos|cosAtOrAbove|newEntrant).*");

  private static final Pattern COS_LIKE = Pattern.compile("(?i).*cos.*");

  @Test
  @DisplayName("卡36：末位判据条件化（三分支 R1/R2/R3/R4）+ 0.90 影响与重锚清单（R5/R6）")
  void conditionalizesMinCriterion() throws Exception {
    ObjectNode bandEntry = readObject(BAND_ENTRY);
    ObjectNode coupling = readObject(WINDOW_COUPLING);
    ObjectNode displacement = readObject(DISPLACEMENT_EVIDENCE);
    ObjectNode decomposition = readObject(RERANK_DECOMPOSITION);
    ObjectNode signoff = readObject(FLOOR_SIGNOFF);
    for (ObjectNode upstream : List.of(bandEntry, coupling, displacement, decomposition)) {
      assertFalse(upstream.path("card").asText("").isEmpty(), "四张上游锚必须各自带 card 标识（只读输入）");
    }
    assertEquals(
        CURRENT_FLOOR,
        signoff.path("signedFloor").asDouble(),
        TOLERANCE,
        "签收锚 signedFloor 必须与生产 NEAR_DUPLICATE_FLOOR 同值（只读核对）");
    assertEquals(
        BAND_REFERENCE,
        signoff.path("bandReferenceFloor").asDouble(),
        TOLERANCE,
        "签收锚 bandReferenceFloor 必须与生产 BAND_REFERENCE_FLOOR 同值（只读核对）");

    double wv = decomposition.path("weights").path("vectorWeight").asDouble();
    double wb = decomposition.path("weights").path("bm25Weight").asDouble();
    assertEquals(0.6d, wv, TOLERANCE, "向量权重应等于卡 35 探针实测值");
    assertEquals(0.4d, wb, TOLERANCE, "bm25 权重应等于卡 35 探针实测值");

    List<RouteRow> routes = routeRows(decomposition);
    assertEquals(15, routes.size(), "卡 35 锚 route 行数应为 15（6 查询 × routeListSize 合计）");
    List<QueryBranch> branches = queryBranches(routes, decomposition);
    assertEquals(6, branches.size(), "本语料 6 条查询，逐条必须落且只落一支");
    Replay replay = replayIdentity(routes, branches, wv, wb);
    assertEquals(15, replay.checked(), "恒等式检查必须覆盖 15 行");
    assertEquals(0, replay.violations(), "r = wv·normVec + wb·normBm25 必须逐行成立（1e-12）");
    assertEquals(0, replay.vectorMinViolations(), "normVec = 0 ⟺ 该行 cos 为该查询内最小值");

    List<EventRow> events = eventRows(routes, branches, wb);
    assertEquals(12, events.size(), "被标记事件应为 12 条");
    String verdict = deriveVerdict(replay, events);
    List<EventRow> zeroEvents = events.stream().filter(EventRow::zeroDisplacement).toList();
    assertEquals(4, zeroEvents.size(), "零位移事件应为 4 条");
    assertTrue(
        zeroEvents.stream().allMatch(row -> B0.equals(row.branch()) && row.normVec() == 0d),
        "4 条零位移事件必须全落 bm25 全 0 桶且 normVec = 0（R3）");
    assertTrue(events.stream().allMatch(EventRow::conditionSatisfied), "12/12 条事件必须满足其所属分支的充要条件");

    ArrayNode impact = MAPPER.createArrayNode();
    LinkedHashMap<String, byte[]> anchorBytes = new LinkedHashMap<>();
    List<FrozenRow> frozenRows = new ArrayList<>();
    for (AnchorRef ref : LEG_B_ANCHORS) {
      impact.add(impactRow(ref, anchorBytes, frozenRows));
    }
    assertEquals(16, impact.size(), "腿 B 必须逐张核对 16 张既有锚");
    int oracleViolations = oracleCheck(frozenRows);
    assertEquals(0, oracleViolations, "反事实 evaluator 必须与生产 tierOf/bandOf 逐行同值（不得构成第二把尺子）");
    System.out.println(
        "[near-dup-conditional-min-criterion] oracle rows="
            + frozenRows.size()
            + " violations="
            + oracleViolations);
    ObjectNode summary = impactSummary(impact);
    Map<String, String> shaByName = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : anchorBytes.entrySet()) {
      shaByName.put(entry.getKey(), sha256(entry.getValue()));
    }

    System.out.println(
        "[near-dup-conditional-min-criterion] verdict="
            + verdict
            + " branches="
            + branchCounts(branches)
            + " events="
            + events.size()
            + " zero="
            + zeroEvents.size()
            + " contradictionYes="
            + summary.path("contradictionYes").asInt()
            + " zeroCallYes="
            + summary.path("zeroCallRecomputeYes").asInt()
            + " minExternalCalls="
            + summary.path("minExternalCallsTotal").asInt());

    ObjectNode anchor =
        buildAnchor(
            decomposition,
            signoff,
            wv,
            wb,
            branches,
            replay,
            events,
            verdict,
            impact,
            summary,
            shaByName,
            frozenRows.size(),
            oracleViolations);
    writeOrCompareJson(ANCHOR_JSON, anchor);
    byte[] md = buildAnchorMd(anchor).getBytes(StandardCharsets.UTF_8);
    writeOrCompareBytes(ANCHOR_MD, md);
  }

  // ==================== 腿 A · 三分支判据与逐条复判 ====================

  private static List<RouteRow> routeRows(JsonNode decomposition) {
    List<RouteRow> rows = new ArrayList<>();
    for (JsonNode node : decomposition.path("routes")) {
      rows.add(
          new RouteRow(
              node.path("queryIndex").asInt(),
              node.path("variantKey").asText(),
              node.path("chunkId").asText(),
              node.path("cos").asDouble(),
              node.path("tier").asText(null),
              node.path("band").asText(null),
              node.path("marked").asBoolean(false),
              node.path("normVec").asDouble(),
              node.path("normBm25").asDouble(),
              node.path("r").asDouble()));
    }
    return rows;
  }

  private static List<QueryBranch> queryBranches(List<RouteRow> routes, JsonNode decomposition) {
    Map<Integer, Boolean> vecDegenerate = new LinkedHashMap<>();
    Map<Integer, String> forms = new LinkedHashMap<>();
    for (JsonNode query : decomposition.path("queries")) {
      int index = query.path("queryIndex").asInt();
      forms.put(index, query.path("form").asText());
      vecDegenerate.put(index, query.path("vecBranch").path("degenerate").asBoolean(false));
    }
    Map<Integer, List<RouteRow>> grouped = new LinkedHashMap<>();
    for (RouteRow row : routes) {
      grouped.computeIfAbsent(row.queryIndex(), key -> new ArrayList<>()).add(row);
    }
    List<QueryBranch> branches = new ArrayList<>();
    for (Map.Entry<Integer, List<RouteRow>> entry : grouped.entrySet()) {
      double max = Double.NEGATIVE_INFINITY;
      double min = Double.POSITIVE_INFINITY;
      for (RouteRow row : entry.getValue()) {
        max = Math.max(max, row.normBm25());
        min = Math.min(min, row.normBm25());
      }
      branches.add(
          new QueryBranch(
              entry.getKey(),
              forms.getOrDefault(entry.getKey(), "unknown"),
              entry.getValue().size(),
              max,
              min,
              branchOf(max, min),
              vecDegenerate.getOrDefault(entry.getKey(), false)));
    }
    return branches;
  }

  /**
   * 三分支判定式（互斥穷尽）：{@code max <= 0} / {@code max == min > 0} / 其余（{@code max > min}）。
   *
   * <p>归一整值恒在 {@code [0,1]}，故三支两两不交且覆盖全部可能状态；不引入任何新推导量（D2）。
   */
  private static String branchOf(double max, double min) {
    String branch;
    if (max <= 0d) {
      branch = B0;
    } else if (Double.compare(max, min) == 0) {
      branch = B1;
    } else {
      branch = B2;
    }
    return branch;
  }

  private static Replay replayIdentity(
      List<RouteRow> routes, List<QueryBranch> branches, double wv, double wb) {
    int violations = 0;
    long checked = 0L;
    double maxAbsDiff = 0d;
    for (RouteRow row : routes) {
      double expected = wv * row.normVec() + wb * row.normBm25();
      double diff = Math.abs(expected - row.r());
      maxAbsDiff = Math.max(maxAbsDiff, diff);
      checked++;
      if (diff > TOLERANCE) {
        violations++;
      }
    }
    Map<Integer, Double> minCos = new LinkedHashMap<>();
    for (RouteRow row : routes) {
      minCos.merge(row.queryIndex(), row.cos(), Math::min);
    }
    int vectorMinChecked = 0;
    int vectorMinViolations = 0;
    for (RouteRow row : routes) {
      QueryBranch branch = branchOf(branches, row.queryIndex());
      if (branch.vecDegenerate()) {
        continue;
      }
      vectorMinChecked++;
      boolean isMin = Double.compare(row.cos(), minCos.get(row.queryIndex())) == 0;
      if ((row.normVec() == 0d) != isMin) {
        vectorMinViolations++;
      }
    }
    return new Replay(checked, violations, maxAbsDiff, vectorMinChecked, vectorMinViolations);
  }

  private static QueryBranch branchOf(List<QueryBranch> branches, int queryIndex) {
    for (QueryBranch branch : branches) {
      if (branch.queryIndex() == queryIndex) {
        return branch;
      }
    }
    throw new IllegalStateException("查询未落分支: q" + queryIndex);
  }

  private static List<EventRow> eventRows(
      List<RouteRow> routes, List<QueryBranch> branches, double wb) {
    List<EventRow> events = new ArrayList<>();
    for (RouteRow row : routes) {
      if (!row.marked()) {
        continue;
      }
      QueryBranch branch = branchOf(branches, row.queryIndex());
      boolean satisfied;
      if (B0.equals(branch.branch())) {
        satisfied = (row.r() == 0d) == (row.normVec() == 0d);
      } else if (B1.equals(branch.branch())) {
        satisfied = row.r() >= wb - TOLERANCE;
      } else {
        satisfied = (row.r() == 0d) == (row.normVec() == 0d && row.normBm25() == 0d);
      }
      events.add(
          new EventRow(
              row.queryIndex(),
              row.variantKey(),
              row.chunkId(),
              branch.branch(),
              row.normVec(),
              row.normBm25(),
              row.r(),
              row.r() == 0d,
              satisfied));
    }
    return events;
  }

  /** R2 四值的机械判定（与 proposal §3 R2 逐条对应，不外推）。 */
  private static String deriveVerdict(Replay replay, List<EventRow> events) {
    String verdict;
    if (replay.violations() > 0 || replay.vectorMinViolations() > 0 || events.isEmpty()) {
      verdict = UNDETERMINED;
    } else if (events.stream().anyMatch(row -> !row.conditionSatisfied())) {
      verdict = REFUTED;
    } else {
      List<EventRow> zeroEvents = events.stream().filter(EventRow::zeroDisplacement).toList();
      if (zeroEvents.isEmpty()) {
        verdict = UNDETERMINED;
      } else if (zeroEvents.stream()
          .allMatch(
              row -> B2.equals(row.branch()) && row.normVec() == 0d && row.normBm25() == 0d)) {
        verdict = PROVEN;
      } else if (zeroEvents.stream().anyMatch(row -> !B2.equals(row.branch()))) {
        verdict = PROVEN_CONDITIONAL;
      } else {
        verdict = UNDETERMINED;
      }
    }
    return verdict;
  }

  private static String branchCounts(List<QueryBranch> branches) {
    long b0 = branches.stream().filter(branch -> B0.equals(branch.branch())).count();
    long b1 = branches.stream().filter(branch -> B1.equals(branch.branch())).count();
    long b2 = branches.stream().filter(branch -> B2.equals(branch.branch())).count();
    return "B0=" + b0 + "/B1=" + b1 + "/B2=" + b2;
  }

  private static List<String> queriesInBranch(List<QueryBranch> branches, String branchId) {
    List<String> queries = new ArrayList<>();
    for (QueryBranch branch : branches) {
      if (branchId.equals(branch.branch())) {
        queries.add("q" + branch.queryIndex());
      }
    }
    return queries;
  }

  // ==================== 腿 B · 逐张核对 16 张既有锚 ====================

  private static ObjectNode impactRow(
      AnchorRef ref, Map<String, byte[]> anchorBytes, List<FrozenRow> frozenRows) throws Exception {
    boolean exists = Files.exists(ref.path());
    ObjectNode row = MAPPER.createObjectNode();
    row.put("anchor", ref.name());
    row.put("path", rel(ref.path()));
    row.put("exists", exists);
    if (!exists) {
      row.put("sha256", "");
      row.put("classificationFrozen", false);
      row.put("classificationFields", MAPPER.createArrayNode());
      row.put("rowCount", 0);
      row.put("cosFrozen", false);
      row.put("hasFloor090Record", false);
      row.put("operatingFloorLiteral", "");
      row.put("floorLiterals", MAPPER.createArrayNode());
      row.put("recordingFloorCandidates", MAPPER.createArrayNode());
      row.put("rowLevelClassificationDelta", -1);
      row.put("contradictionUnder090", UNDETERMINED);
      row.put("contradictionCause", "MISSING-ANCHOR");
      row.put("contradictionBasis", "锚文件不存在");
      row.put("zeroCallRecompute", UNDETERMINED);
      row.put("zeroCallBasis", "锚文件不存在");
      row.put("minExternalCallsIfNeeded", -1);
      row.put("recordedBatchExternalCalls", -1);
      return row;
    }
    byte[] bytes = Files.readAllBytes(ref.path());
    anchorBytes.put(ref.name(), bytes);
    row.put("sha256", sha256(bytes));
    JsonNode root = MAPPER.readTree(bytes);
    List<Hit> hits = new ArrayList<>();
    List<FrozenRow> rows = new ArrayList<>();
    walk(root, "", hits, rows);
    frozenRows.addAll(rows);

    List<Hit> classHits = distinctByPath(filter(hits, Kind.CLASSIFICATION));
    List<Hit> cosHits = distinctByPath(filter(hits, Kind.COS));
    List<Hit> floorHits = distinctFloorHits(filter(hits, Kind.FLOOR));
    List<Hit> aggregateHits = distinctByPath(filter(hits, Kind.AGGREGATE));
    int externalCalls = -1;
    for (Hit hit : hits) {
      if (hit.kind() == Kind.CALLS) {
        externalCalls = (int) hit.value();
        break;
      }
    }
    Set<String> tierValues = new LinkedHashSet<>();
    boolean nullBandFrozen = false;
    for (Hit hit : classHits) {
      String field = fieldName(hit.path());
      if ("tier".equals(field) || "imageSimilarityTier".equals(field)) {
        tierValues.add(hit.sample());
      } else if (("band".equals(field) || "imageSimilarityBand".equals(field))
          && "null".equals(hit.sample())) {
        nullBandFrozen = true;
      }
    }
    boolean classificationFrozen = !classHits.isEmpty();
    boolean mutableClass =
        tierValues.contains(ImageSimilarityGate.TIER_CROSS_IMAGE) || nullBandFrozen;
    boolean floor090 =
        floorHits.stream()
            .filter(hit -> !hit.path().contains("rejected"))
            .anyMatch(hit -> Math.abs(hit.value() - PROPOSED_FLOOR) <= TOLERANCE);
    double[] operating = operatingFloorLiteral(floorHits);

    row.put("classificationFrozen", classificationFrozen);
    ArrayNode fieldArray = row.putArray("classificationFields");
    for (Hit hit : classHits) {
      ObjectNode field = fieldArray.addObject();
      field.put("path", hit.path());
      field.put("sample", hit.sample());
    }
    row.put("rowCount", rows.size());
    row.put("cosFrozen", !rows.isEmpty() || !cosHits.isEmpty());
    row.put("hasFloor090Record", floor090);
    row.put(
        "operatingFloorLiteral",
        operating == null ? "" : floorPath(floorHits) + "=" + format(operating[0]));
    ArrayNode literalArray = row.putArray("floorLiterals");
    int literalCount = 0;
    for (Hit hit : floorHits) {
      if (literalCount >= 6) {
        break;
      }
      ObjectNode literal = literalArray.addObject();
      literal.put("path", hit.path());
      literal.put("value", hit.value());
      literalCount++;
    }
    row.put("floorLiteralDistinctCount", floorHits.size());
    row.put("floorDependentAggregateCount", aggregateHits.size());

    List<Double> reproduced = rows.isEmpty() ? List.of() : reproducibleFloors(rows);
    int delta = rows.isEmpty() || reproduced.isEmpty() ? -1 : deltaUnderProposedFloor(rows);
    row.put("rowLevelClassificationDelta", delta);
    ArrayNode reproducedArray = row.putArray("recordingFloorCandidates");
    for (Double candidate : reproduced) {
      reproducedArray.add(candidate);
    }

    String cause;
    String contradiction;
    String basis;
    if (operating != null && Math.abs(operating[0] - PROPOSED_FLOOR) > TOLERANCE) {
      cause = "OPERATING-FLOOR-LITERAL";
      contradiction = YES;
      basis =
          "operating-point 字面量 "
              + floorPath(floorHits)
              + "="
              + format(operating[0])
              + " 与 0.90 口径不自洽（须改写并重签）";
    } else if (delta > 0) {
      cause = "ROW-LEVEL-CLASSIFICATION";
      contradiction = YES;
      basis = "行级 tier/band 在 0.90 参数下与冻结值不同 " + delta + "/" + rows.size() + " 行";
    } else if (floor090) {
      cause = "NONE";
      contradiction = NO;
      basis = "锚内已记录 floor=0.90 的实测列，无需改判";
    } else if (delta == 0 && aggregateHits.isEmpty()) {
      cause = "NONE";
      contradiction = NO;
      basis = "行级分类 0 变化、无 operating-point 字面量、无门槛敏感聚合字段";
    } else if (delta == 0) {
      cause = "AGGREGATE-ONLY";
      contradiction = UNDETERMINED;
      basis = "行级 0 变化，但存在门槛敏感聚合字段（" + pathsOf(aggregateHits) + "）且其分母未冻结";
    } else if (!rows.isEmpty()) {
      cause = "ROW-LEVEL-UNREPRODUCED";
      contradiction = UNDETERMINED;
      basis = "冻结行无法被 0.9887/0.982 任一候选口径复现（" + rows.size() + " 行），0.90 的改判无法归因";
    } else if (classificationFrozen && !mutableClass) {
      cause = "FROZEN-CLASS-INVARIANT";
      contradiction = NO;
      basis =
          "冻结的 tier（"
              + join(new ArrayList<>(tierValues))
              + "）不含 CROSS_IMAGE 且无 null band ⇒ 降准入下限只会给此前未进带的行新增分类，已冻结行不变";
    } else if (classificationFrozen) {
      cause = "NO-COS";
      contradiction = UNDETERMINED;
      basis = "冻结了 tier 但锚内无 cos，无法判定行级分类是否随 0.90 改变";
    } else if (aggregateHits.isEmpty()) {
      cause = "NONE";
      contradiction = NO;
      basis = "未冻结任何分类结果、无 operating-point 字面量、无门槛敏感聚合字段";
    } else {
      cause = "AGGREGATE-ONLY";
      contradiction = UNDETERMINED;
      basis = "未冻结分类，但存在门槛敏感聚合字段（" + pathsOf(aggregateHits) + "）且其分母未冻结";
    }
    row.put("contradictionUnder090", contradiction);
    row.put("contradictionCause", cause);
    row.put("contradictionBasis", basis);

    String zeroCall;
    String zeroCallBasis;
    int minCalls;
    if (NO.equals(contradiction)) {
      zeroCall = NOT_APPLICABLE;
      zeroCallBasis = "无需重锚（" + basis + "）";
      minCalls = 0;
    } else if (YES.equals(contradiction)) {
      zeroCall = "true";
      if ("ROW-LEVEL-CLASSIFICATION".equals(cause)) {
        zeroCallBasis =
            "cos 冻结于 "
                + pathsOf(cosHits)
                + "（"
                + rows.size()
                + " 行），tier/band 是生产纯函数，可按 0.90 参数本地重算";
      } else {
        zeroCallBasis = "只需改写 operating-point 字面量并重签，分类数据本身仍成立";
      }
      minCalls = 0;
    } else if ("ROW-LEVEL-UNREPRODUCED".equals(cause)) {
      zeroCall = "false";
      zeroCallBasis = "冻结行与 0.9887/0.982 任一候选口径都不一致，需重跑该锚原始测量批次才能判定";
      minCalls = externalCalls;
    } else if ("NO-COS".equals(cause)) {
      zeroCall = "false";
      zeroCallBasis = "tier 已冻结但锚内无 cos/向量，无法本地复算 0.90 下的分类";
      minCalls = externalCalls;
    } else {
      zeroCall = UNDETERMINED;
      zeroCallBasis = "门槛敏感聚合字段的分母未冻结，无法确认是否需重跑";
      minCalls = externalCalls;
    }
    row.put("zeroCallRecompute", zeroCall);
    row.put("zeroCallBasis", zeroCallBasis);
    row.put("minExternalCallsIfNeeded", minCalls);
    row.put("recordedBatchExternalCalls", externalCalls);
    return row;
  }

  /** 生产 oracle：反事实 evaluator（准入下限 = 现常量）必须与 {@code tierOf} / {@code bandOf} 逐行同值。 */
  private static int oracleCheck(List<FrozenRow> rows) {
    int violations = 0;
    for (FrozenRow row : rows) {
      if (row.hasTier()
          && !tierUnder(row.cos(), THRESHOLD, CURRENT_FLOOR)
              .equals(ImageSimilarityGate.tierOf(row.cos(), THRESHOLD))) {
        violations++;
      }
      String counterfactual = bandUnder(row.cos(), THRESHOLD, CURRENT_FLOOR, BAND_REFERENCE);
      String production = ImageSimilarityGate.bandOf(row.cos(), THRESHOLD);
      if (counterfactual == null ? production != null : !counterfactual.equals(production)) {
        violations++;
      }
    }
    return violations;
  }

  /** 行级反事实：冻结值 vs 0.90 参数重算值，统计「会改判」的行数（无 cos 时上游已排除）。 */
  private static int deltaUnderProposedFloor(List<FrozenRow> rows) {
    int changed = 0;
    for (FrozenRow row : rows) {
      boolean differs = false;
      if (row.hasTier() && !tierUnder(row.cos(), THRESHOLD, PROPOSED_FLOOR).equals(row.tier())) {
        differs = true;
      }
      if (row.hasBand()) {
        String recomputed = bandUnder(row.cos(), THRESHOLD, PROPOSED_FLOOR, BAND_REFERENCE);
        if (recomputed == null ? row.band() != null : !recomputed.equals(row.band())) {
          differs = true;
        }
      }
      if (differs) {
        changed++;
      }
    }
    return changed;
  }

  /**
   * 记录口径复现：候选口径（历史 0.9887 / 现行 0.982）中能把全部冻结行逐行复现者。
   *
   * <p>为空说明冻结行无法归因到任一已知口径，则 0.90 的改判也不能归因，按 R8 记 UNDETERMINED。
   */
  private static List<Double> reproducibleFloors(List<FrozenRow> rows) {
    List<Double> reproduced = new ArrayList<>();
    for (double candidate : new double[] {LEGACY_FLOOR, CURRENT_FLOOR}) {
      boolean all = true;
      for (FrozenRow row : rows) {
        if (row.hasTier() && !tierUnder(row.cos(), THRESHOLD, candidate).equals(row.tier())) {
          all = false;
          break;
        }
        if (row.hasBand()) {
          String band = bandUnder(row.cos(), THRESHOLD, candidate, BAND_REFERENCE);
          if (band == null ? row.band() != null : !band.equals(row.band())) {
            all = false;
            break;
          }
        }
      }
      if (all) {
        reproduced.add(candidate);
      }
    }
    return reproduced;
  }

  /**
   * 反事实 tier：与生产 {@link ImageSimilarityGate#tierOf(double, double)} 同构，唯一差别是把准入下限做成显式入参（生产常量不可改）。
   */
  private static String tierUnder(double cosine, double threshold, double admissionFloor) {
    double floor = Math.min(admissionFloor, threshold);
    String tier;
    if (cosine >= threshold) {
      tier = ImageSimilarityGate.TIER_SAME_IMAGE;
    } else if (cosine >= floor) {
      tier = ImageSimilarityGate.TIER_NEAR_DUPLICATE;
    } else {
      tier = ImageSimilarityGate.TIER_CROSS_IMAGE;
    }
    return tier;
  }

  /**
   * 反事实 band：与生产 {@link ImageSimilarityGate#bandOf(double, double)} 同构，两个基准都做成显式入参；内边界逐值复用生产的
   * {@code bandReferenceFloor + Δ/3} / {@code + 2Δ/3} 形式。
   */
  private static String bandUnder(
      double cosine, double threshold, double admissionFloor, double bandReference) {
    double floor = Math.min(admissionFloor, threshold);
    double reference = Math.min(bandReference, threshold);
    double delta = threshold - reference;
    String band;
    if (cosine < floor || cosine >= threshold) {
      band = null;
    } else if (cosine >= reference + 2.0d * delta / 3.0d) {
      band = ImageSimilarityGate.BAND_NEAR_HIGH;
    } else if (cosine >= reference + delta / 3.0d) {
      band = ImageSimilarityGate.BAND_NEAR_MID;
    } else {
      band = ImageSimilarityGate.BAND_NEAR_LOW;
    }
    return band;
  }

  private static double[] operatingFloorLiteral(List<Hit> floorHits) {
    double[] found = null;
    for (Hit hit : floorHits) {
      String field = fieldName(hit.path());
      boolean operating =
          "nearDuplicateFloor".equals(field)
              || "signedFloor".equals(field)
              || ("floor".equals(field) && hit.path().contains("currentProduction"));
      if (operating) {
        found = new double[] {hit.value()};
        break;
      }
    }
    return found;
  }

  private static String floorPath(List<Hit> floorHits) {
    for (Hit hit : floorHits) {
      String field = fieldName(hit.path());
      if ("nearDuplicateFloor".equals(field) || "signedFloor".equals(field)) {
        return hit.path();
      }
    }
    for (Hit hit : floorHits) {
      if ("floor".equals(fieldName(hit.path())) && hit.path().contains("currentProduction")) {
        return hit.path();
      }
    }
    return "";
  }

  private static String fieldName(String path) {
    int cut = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
    return cut < 0 ? path : path.substring(cut + 1);
  }

  private static List<Hit> filter(List<Hit> hits, Kind kind) {
    return hits.stream().filter(hit -> hit.kind() == kind).toList();
  }

  private static List<Hit> distinctByPath(List<Hit> hits) {
    Map<String, Hit> distinct = new LinkedHashMap<>();
    for (Hit hit : hits) {
      distinct.putIfAbsent(hit.path(), hit);
    }
    return new ArrayList<>(distinct.values());
  }

  private static List<Hit> distinctFloorHits(List<Hit> hits) {
    Map<String, Hit> distinct = new LinkedHashMap<>();
    for (Hit hit : hits) {
      distinct.putIfAbsent(hit.path() + "@" + format(hit.value()), hit);
    }
    return new ArrayList<>(distinct.values());
  }

  private static String pathsOf(List<Hit> hits) {
    Set<String> paths = new LinkedHashSet<>();
    for (Hit hit : hits) {
      paths.add(hit.path());
    }
    return String.join(",", paths);
  }

  private static void walk(JsonNode node, String path, List<Hit> hits, List<FrozenRow> rows) {
    if (node.isArray()) {
      for (JsonNode child : node) {
        walk(child, path + "[*]", hits, rows);
      }
      return;
    }
    if (!node.isObject()) {
      return;
    }
    double cosine = Double.NaN;
    String tier = null;
    String band = null;
    boolean hasTier = false;
    boolean hasBand = false;
    List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
    while (iterator.hasNext()) {
      entries.add(iterator.next());
    }
    for (Map.Entry<String, JsonNode> entry : entries) {
      String name = entry.getKey();
      JsonNode value = entry.getValue();
      String childPath = path.isEmpty() ? name : path + "." + name;
      if (value.isNumber()) {
        double number = value.asDouble();
        boolean floorKey = FLOOR_KEY.matcher(name).matches();
        if ("cos".equals(name) || "cosine".equals(name)) {
          cosine = number;
          hits.add(new Hit(Kind.COS, childPath, value.asText(), number));
        } else if ("externalCalls".equals(name)) {
          hits.add(new Hit(Kind.CALLS, childPath, value.asText(), number));
        }
        if (floorKey && number > 0d && number <= 1d) {
          hits.add(new Hit(Kind.FLOOR, childPath, value.asText(), number));
        }
        if (AGGREGATE_KEY.matcher(name).matches()) {
          hits.add(new Hit(Kind.AGGREGATE, childPath, value.asText(), number));
        }
      } else if (value.isTextual() && CLASSIFICATION_KEYS.contains(name)) {
        hits.add(new Hit(Kind.CLASSIFICATION, childPath, value.asText(), 0d));
        if ("tier".equals(name) || "imageSimilarityTier".equals(name)) {
          tier = value.asText();
          hasTier = true;
        } else if ("band".equals(name) || "imageSimilarityBand".equals(name)) {
          band = value.asText();
          hasBand = true;
        }
      } else if (value.isNull() && CLASSIFICATION_KEYS.contains(name)) {
        hits.add(new Hit(Kind.CLASSIFICATION, childPath, "null", 0d));
        if ("band".equals(name) || "imageSimilarityBand".equals(name)) {
          hasBand = true;
        }
      } else if (value.isArray() && COS_LIKE.matcher(name).matches()) {
        hits.add(new Hit(Kind.COS, childPath + "[*]", "count=" + value.size(), value.size()));
      }
    }
    if (!Double.isNaN(cosine)) {
      rows.add(new FrozenRow(cosine, tier, hasTier, band, hasBand));
    }
    for (Map.Entry<String, JsonNode> entry : entries) {
      JsonNode value = entry.getValue();
      if (value.isObject() || value.isArray()) {
        String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
        walk(value, childPath, hits, rows);
      }
    }
  }

  private static ObjectNode impactSummary(ArrayNode perAnchor) {
    int yes = 0;
    int no = 0;
    int undetermined = 0;
    int zeroCallYes = 0;
    int zeroCallNo = 0;
    int minCalls = 0;
    List<String> affected = new ArrayList<>();
    List<String> unaffected = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    for (JsonNode row : perAnchor) {
      String contradiction = row.path("contradictionUnder090").asText();
      String name = row.path("anchor").asText();
      if (YES.equals(contradiction)) {
        yes++;
        affected.add(name);
      } else if (NO.equals(contradiction)) {
        no++;
        unaffected.add(name);
      } else {
        undetermined++;
        unresolved.add(name);
      }
      if ("true".equals(row.path("zeroCallRecompute").asText())) {
        zeroCallYes++;
      } else if ("false".equals(row.path("zeroCallRecompute").asText())) {
        zeroCallNo++;
      }
      int calls = row.path("minExternalCallsIfNeeded").asInt(-1);
      if (calls > 0) {
        minCalls += calls;
      }
    }
    ObjectNode summary = MAPPER.createObjectNode();
    summary.put("total", perAnchor.size());
    summary.put("contradictionYes", yes);
    summary.put("contradictionNo", no);
    summary.put("contradictionUndetermined", undetermined);
    summary.put("zeroCallRecomputeYes", zeroCallYes);
    summary.put("zeroCallRecomputeNo", zeroCallNo);
    summary.put("minExternalCallsTotal", minCalls);
    summary.put("fullRerunTotal", fullRerunTotal(perAnchor, affected));
    summary.put(
        "minExternalCallsBasis",
        "逐锚 minExternalCallsIfNeeded：行级/字面量修复 0 外呼；需重跑者取该锚自记 externalCalls（无记录记 -1 = 无法给出量级）");
    summary.put("affected", join(affected));
    summary.put("unaffected", join(unaffected));
    summary.put("unresolved", join(unresolved));
    return summary;
  }

  private static int fullRerunTotal(ArrayNode perAnchor, List<String> affected) {
    int total = 0;
    for (JsonNode row : perAnchor) {
      if (affected.contains(row.path("anchor").asText())) {
        total += Math.max(row.path("recordedBatchExternalCalls").asInt(-1), 0);
      }
    }
    return total;
  }

  private static String join(List<String> values) {
    return String.join(",", values);
  }

  // ==================== 结论锚 ====================

  private static ObjectNode buildAnchor(
      ObjectNode decomposition,
      ObjectNode signoff,
      double wv,
      double wb,
      List<QueryBranch> branches,
      Replay replay,
      List<EventRow> events,
      String verdict,
      ArrayNode impact,
      ObjectNode impactSummary,
      Map<String, String> shaByName,
      int oracleChecked,
      int oracleViolations)
      throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-conditional-min-criterion");
    root.put("stage", "CONDITIONAL-MIN-CRITERION");
    root.put("decisionVersion", "NEAR-DUP-CONDITIONAL-MIN-CRITERION-1.0");
    root.put("decisionOwner", "OWNER");
    root.put(
        "scope",
        "腿 A：把末位判据改成条件化三分支（bm25 全 0 / 全 1 / 非退化），逐条复判 12 条事件；腿 B："
            + "NEAR_DUPLICATE_FLOOR 降到 0.90 的影响清单与重锚清单（不执行、不给倾向值、不改任何常量）");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "R0",
        "零外呼：输入是卡 32/33/34/35 四张冻结锚 + 生产纯函数 + 源码字面；不启动 Spring、不 instance provider、不设任何 *_REAL、不跑检索");
    criteria.put("R1", "把末位判据写成三分支条件式（全 0 / 全 1 / 非退化），逐支给出 r = 0 的充要条件；三支互斥穷尽，判定只用 max/min 整值");
    criteria.put(
        "R2",
        "判定词升级为四值：PROVEN-CONDITIONAL（适用域内已证）/ PROVEN（仅非退化分支，可达但本语料未出现）/ REFUTED / UNDETERMINED，四值各写可达路径");
    criteria.put("R3", "逐 12 条事件判「所属分支 + 是否满足该分支充要条件」，断言 4 条零位移全落 bm25 全 0 桶且 normVec = 0");
    criteria.put(
        "R4",
        "反向断言：无条件 PROVEN 档文本不得含「末位 / 两分支同时」等未在本语料成立的措辞；PROVEN-CONDITIONAL 档必须带 applicabilityDomain");
    criteria.put("R5", "腿 B 只列事实：逐锚给字段路径 + 值样例、降门槛后是否自相矛盾、能否零外呼重算（依据：是否冻 cos/向量）、否则最小外呼量与理由");
    criteria.put("R6", "腿 B 给重锚清单（方式 + 是否花钱 + 依赖顺序）与不受影响清单；executed = false，本卡不动任何既有锚");
    criteria.put("R7", "边界：src/main 零 diff、pom.xml / .github 零改、既有锚零字节变动、不改任何生产常量、禁改 NearDup* 类只读");
    criteria.put("R8", "停手：任一影响判定若无法从源锚字段证明（指不出字段路径）⇒ 记 UNDETERMINED，不许猜；清单内不得出现任何降或不降的倾向措辞");

    ObjectNode upstream = root.putObject("upstreamAnchors");
    putAnchorRef(upstream, "bandEntry", BAND_ENTRY);
    putAnchorRef(upstream, "windowCoupling", WINDOW_COUPLING);
    putAnchorRef(upstream, "displacementEvidence", DISPLACEMENT_EVIDENCE);
    putAnchorRef(upstream, "rerankDecomposition", RERANK_DECOMPOSITION);
    putAnchorRef(upstream, "floorSignoff", FLOOR_SIGNOFF);

    ObjectNode weights = root.putObject("weights");
    weights.put("vectorWeight", wv);
    weights.put("bm25Weight", wb);
    weights.put(
        "source",
        "卡 35 锚 weights 块（受控探针 + 0.9/0.1 负对照反解出的生产 DefaultWeightedReranker 权重）；本卡只读引用，不重测");
    weights.put("identity", "r = wv · normVec + wb · normBm25");
    weights.put("identityChecked", replay.checked());
    weights.put("identityViolations", replay.violations());
    weights.put("identityTolerance", TOLERANCE);
    weights.put("identityMaxAbsDiff", replay.maxAbsDiff());

    ObjectNode criterion = root.putObject("criterion");
    criterion.put("identity", "r = wv · normVec + wb · normBm25（wv = 0.6、wb = 0.4，均为正）");
    ArrayNode branchArray = criterion.putArray("branches");
    branchArray.add(
        branchNode(
            decomposition,
            B0,
            "bm25 全 0",
            "max(normBm25[q]) <= 0.0",
            "r = wv · normVec + wb · 0 = wv · normVec ⇒ r = 0 ⟺ normVec = 0（向量分支取最小即充分必要）",
            branches,
            events));
    branchArray.add(
        branchNode(
            decomposition,
            B1,
            "bm25 全 1",
            "max(normBm25[q]) == min(normBm25[q]) && max > 0.0",
            "r = wv · normVec + wb · 1 >= wb > 0 ⇒ r = 0 不可能（可证的不可能性）",
            branches,
            events));
    branchArray.add(
        branchNode(
            decomposition,
            B2,
            "bm25 非退化",
            "max(normBm25[q]) > min(normBm25[q])",
            "两个归一化项都参与 ⇒ r = 0 ⟺ normVec = 0 ∧ normBm25 = 0（卡 34 的原判据 = 本分支，属特例）",
            branches,
            events));
    criterion.put(
        "branchDecisionRule",
        "逐查询取该查询全部 route 行的 normBm25，求 max 与 min：max <= 0.0 ⇒ B0；max == min && max > 0.0 ⇒ B1；max > min ⇒ B2。"
            + "归一整值恒在 [0,1]，三支两两不交且覆盖全部状态；不做数值拟合（D2）");
    ObjectNode exhaustiveness = criterion.putObject("exhaustiveness");
    exhaustiveness.put("mutuallyExclusive", true);
    exhaustiveness.put("exhaustive", true);
    exhaustiveness.put(
        "rule", "三支判定式两两互补：(max <= 0) ∪ (max == min ∧ max > 0) ∪ (max > min) 覆盖全部实数且交集为空");
    exhaustiveness.put("queriesTotal", branches.size());
    ObjectNode byBranch = exhaustiveness.putObject("queriesByBranch");
    byBranch.put(B0, queriesInBranch(branches, B0).size());
    byBranch.put(B1, queriesInBranch(branches, B1).size());
    byBranch.put(B2, queriesInBranch(branches, B2).size());
    exhaustiveness.put("queriesCoveredSum", branches.size());
    criterion.put("branchCaveat", "B2 在本语料 0 条查询出现；B0/B1 的结论只在该支的适用域内成立，不能跨支外推");

    ArrayNode queryArray = root.putArray("queryBranches");
    for (QueryBranch branch : branches) {
      ObjectNode node = queryArray.addObject();
      node.put("queryIndex", branch.queryIndex());
      node.put("form", branch.form());
      node.put("routeRows", branch.rows());
      node.put("normBm25Max", branch.max());
      node.put("normBm25Min", branch.min());
      node.put("branch", branch.branch());
      node.put("vecBranchDegenerate", branch.vecDegenerate());
    }

    ObjectNode replayNode = root.putObject("eventReplay");
    replayNode.put("identity", "r = wv · normVec + wb · normBm25");
    replayNode.put("checked", replay.checked());
    replayNode.put("violations", replay.violations());
    replayNode.put("tolerance", TOLERANCE);
    replayNode.put("maxAbsDiff", replay.maxAbsDiff());
    replayNode.put("vectorMinChecked", replay.vectorMinChecked());
    replayNode.put("vectorMinViolations", replay.vectorMinViolations());
    replayNode.put(
        "vectorMinRule",
        "normVec = 0 ⟺ 该行 cos 为其所在查询 route 内的最小 cos（只在向量分支非退化的查询上判；退化查询的末位不可分辨，不参与）");

    ObjectNode conditional = root.putObject("conditionalVerdict");
    conditional.put("verdict", verdict);
    conditional.put("verdictReason", verdictReason(verdict, branches, events, replay, wb));
    conditional.put("applicabilityDomain", applicabilityDomain(branches));
    ArrayNode eventArray = conditional.putArray("rows");
    for (EventRow event : events) {
      ObjectNode node = eventArray.addObject();
      node.put("queryIndex", event.queryIndex());
      node.put("variantKey", event.variantKey());
      node.put("chunkId", event.chunkId());
      node.put("branch", event.branch());
      node.put("condition", conditionOf(event.branch(), wb));
      node.put("normVec", event.normVec());
      node.put("normBm25", event.normBm25());
      node.put("r", event.r());
      node.put("zeroDisplacement", event.zeroDisplacement());
      node.put("conditionSatisfied", event.conditionSatisfied());
    }
    ObjectNode eventSummary = conditional.putObject("summary");
    eventSummary.put("events", events.size());
    eventSummary.put(
        "zeroDisplacement", events.stream().filter(EventRow::zeroDisplacement).count());
    eventSummary.put(
        "nonZeroEvents", events.stream().filter(row -> !row.zeroDisplacement()).count());
    eventSummary.put("zeroInB0", zeroInBranch(events, B0));
    eventSummary.put("zeroInB1", zeroInBranch(events, B1));
    eventSummary.put("zeroInB2", zeroInBranch(events, B2));
    eventSummary.put("satisfied", events.stream().filter(EventRow::conditionSatisfied).count());

    ObjectNode reachability = root.putObject("verdictReachability");
    reachability.put(
        PROVEN_CONDITIONAL,
        "本卡实测路径：复算一致 ∧ 全部零位移事件都满足其所属分支的充要条件 ∧ 至少 1 条零位移事件落在 B0 或 B1（即其所属分支退化）⇒ 结论只在适用域内成立，见 conditionalVerdict.applicabilityDomain");
    reachability.put(
        PROVEN,
        "可达但本语料未出现：需要全部零位移事件都落在 B2（bm25 分支非退化）且该行两个归一化项皆为 0.0；本语料 B2 查询 "
            + queriesInBranch(branches, B2).size()
            + " 条 ⇒ 该档在语料外可达、在本语料未出现");
    reachability.put(
        REFUTED,
        "触发条件：复算一致但至少 1 条零位移事件违反其所属分支的充要条件（例如 r = 0 却 normVec > 0，或在 B1 查询里出现 r = 0）；本卡实测未触发");
    reachability.put(
        UNDETERMINED,
        "触发条件：复算 r 与锚内值不一致（> 1e-12）、向量分支末位判定出现违例、无被标记事件，或零位移事件全部落在 B2 但两个归一化项不同时为 0（分支不可判定）；本卡实测未触发");
    root.put(
        "unconditionalProvenNote",
        "PROVEN（无条件档）可达但本语料未出现：要求零位移事件全在 B2 且两个归一化项皆为 0.0；本语料无 B2 查询。该档文本不引用未在本语料实测成立的判据措辞");

    ObjectNode impactNode = root.putObject("impact");
    impactNode.put(
        "caliber",
        "目标常量 = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR（准入下限，现行 0.982，历史签收口径 0.9887）；"
            + "ImageSimilarityGate.BAND_REFERENCE_FLOOR（带内基准 0.9887）在本判定中冻结不动——三档内边界只由它推导，不随准入下限缩放");
    impactNode.put("currentValue", CURRENT_FLOOR);
    impactNode.put("legacyValue", LEGACY_FLOOR);
    impactNode.put("proposedValue", PROPOSED_FLOOR);
    impactNode.put("bandReferenceFrozen", BAND_REFERENCE);
    impactNode.put(
        "contradictionRule",
        "YES = 存在 operating-point 字面量（nearDuplicateFloor / signedFloor / currentProductionReference.floor）≠ 0.90，"
            + "或行级 tier/band 用 0.90 参数重算后与冻结值不同；"
            + "NO = 无 operating-point 字面量 ∧ 行级 0 变化 ∧ 无门槛敏感聚合字段（或锚内已有 floor=0.90 实测列，或只冻结 band/bandSide —— band 取值只由 bandReferenceFloor 决定）；"
            + "UNDETERMINED = 无 cos 无法复算行级分类，或只有门槛敏感聚合字段而分母未冻结");
    impactNode.put(
        "deltaSemantics",
        "rowLevelClassificationDelta 只统计「锚内已冻结的行」在 0.90 参数下是否改判（本卡 affected 锚实测全为 0）；"
            + "它不覆盖 0.90 新增进带的候选——那些行不在任何既有锚里，补测代价见各锚 recordedBatchExternalCalls");
    impactNode.put(
        "contradictionWording",
        "「自相矛盾」在本卡指「锚内记录与 0.90 口径不自洽、须重锚或重签」，不指行级数值在 0.90 下改判（后者由 rowLevelClassificationDelta 单列）");
    impactNode.put(
        "counterfactualBasis",
        "反事实 evaluator 与生产 tierOf/bandOf 同构，仅把准入下限做成显式入参；边界沿用 bandReferenceFloor + Δ/3 与 + 2Δ/3 形式，逐行以生产函数为 oracle 校验（见 numericHygiene.oracleViolations）");
    impactNode.set("perAnchor", impact);
    impactNode.set("summary", impactSummary);

    buildReanchorPlan(root, impact, impactSummary, shaByName);

    ObjectNode hygiene = root.putObject("numericHygiene");
    hygiene.put("tolerance", TOLERANCE);
    hygiene.put("oracleChecked", oracleChecked);
    hygiene.put("oracleViolations", oracleViolations);
    hygiene.put(
        "oracleRule",
        "对每条冻结行比较反事实 evaluator（准入下限 = 现行 0.982）与生产 ImageSimilarityGate.tierOf / bandOf 的输出；不一致即视为第二把尺子、本卡判定无效");
    hygiene.put("noRerun", "本卡不重算任何既有锚的任何数值，也不改任何生产常量；only read + 反事实求值");

    root.put(
        "nonExtrapolationNote",
        "本卡结论只覆盖本语料（6 查询 / 6 图 / 12 条被标记事件）与 16 张列名锚的字段事实；不外推到其它语料、其它 topK、其它权重配置，也不外推到 0.90 的检索级效果");

    ObjectNode runtime = root.putObject("runtime");
    runtime.put("generatedBy", "NearDupConditionalMinCriterionTest");
    runtime.put("javaVersion", System.getProperty("java.version"));
    runtime.put("os", System.getProperty("os.name"));
    runtime.put("externalCalls", 0);
    runtime.put(
        "externalCallsBasis", "零外呼：输入只有既有锚 + 生产纯函数 + 源码字面；无 provider 调用点、不设任何 *_REAL、不启动 Spring");
    runtime.put("excludedFromReplay", true);
    assertEquals(
        CURRENT_FLOOR, signoff.path("signedFloor").asDouble(), TOLERANCE, "签收口径与生产常量必须同值（写入前再核一次）");
    return root;
  }

  private static ObjectNode branchNode(
      JsonNode decomposition,
      String id,
      String label,
      String predicate,
      String consequence,
      List<QueryBranch> branches,
      List<EventRow> events) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", id);
    node.put("label", label);
    node.put("predicate", predicate);
    node.put("consequence", consequence);
    node.put("applicableQueries", join(queriesInBranch(branches, id)));
    node.put("observedQueries", queriesInBranch(branches, id).size());
    node.put("observedEvents", events.stream().filter(event -> id.equals(event.branch())).count());
    node.put("observedZeroEvents", zeroInBranch(events, id));
    node.put(
        "reachable",
        B2.equals(id)
            ? "语料外可达（需 bm25 在图像描述文本上分出高低）；本语料未出现"
            : "本语料已出现（" + queriesInBranch(branches, id).size() + " 条查询）");
    node.put("decompositionAnchor", decomposition.path("card").asText());
    return node;
  }

  private static long zeroInBranch(List<EventRow> events, String branchId) {
    return events.stream()
        .filter(event -> event.zeroDisplacement() && branchId.equals(event.branch()))
        .count();
  }

  private static String conditionOf(String branch, double wb) {
    String condition;
    if (B0.equals(branch)) {
      condition = "max(normBm25) <= 0.0 ⇒ r = wv · normVec，故 r = 0 ⟺ normVec = 0";
    } else if (B1.equals(branch)) {
      condition =
          String.format(
              Locale.ROOT, "max == min > 0.0 ⇒ r >= wv · 0 + wb · 1 = %.1f > 0，故 r = 0 不可能", wb);
    } else {
      condition = "max > min ⇒ r = 0 ⟺ normVec = 0 ∧ normBm25 = 0";
    }
    return condition;
  }

  private static String verdictReason(
      String verdict, List<QueryBranch> branches, List<EventRow> events, Replay replay, double wb) {
    long zero = events.stream().filter(EventRow::zeroDisplacement).count();
    String head =
        "复算 r = wv · normVec + wb · normBm25 与卡 35 锚逐行一致 "
            + (replay.checked() - replay.violations())
            + "/"
            + replay.checked()
            + "（容差 1e-12，maxAbsDiff = "
            + replay.maxAbsDiff()
            + "）；"
            + events.size()
            + " 条被标记事件 "
            + events.stream().filter(EventRow::conditionSatisfied).count()
            + "/"
            + events.size()
            + " 条满足所属分支的充要条件；"
            + zero
            + " 条零位移事件 "
            + zeroInBranch(events, B0)
            + "/"
            + zero
            + " 条落在 B0（bm25 全 0）且 normVec = 0";
    String tail;
    if (PROVEN_CONDITIONAL.equals(verdict)) {
      tail =
          " ⇒ 三分支条件式在「零位移事件全部落在 bm25 分支退化的查询（B0 "
              + queriesInBranch(branches, B0).size()
              + " 条 / B1 "
              + queriesInBranch(branches, B1).size()
              + " 条）」这一适用域内成立：B0 支里 r = 0 的充要条件就是 normVec = 0，实测 "
              + zero
              + "/"
              + zero
              + " 成立；B1 支里 r >= "
              + String.format(Locale.ROOT, "%.1f", wb)
              + " > 0，r = 0 可证不可能。卡 35 之所以记 UNDETERMINED，是因为旧判据要求零位移事件落在「两分支皆非退化」的查询，而本语料 B2 查询 0 条 ⇒ 旧判据不可满足；本卡把该要求拆到适用域，判据本身未被放宽（D1：各支的充要条件都是原式的代入结果）";
    } else if (PROVEN.equals(verdict)) {
      tail = " ⇒ 零位移事件全落在 B2 且两个归一化项皆为 0.0，无条件档成立";
    } else if (REFUTED.equals(verdict)) {
      tail = " ⇒ 至少 1 条零位移事件违反其所属分支的充要条件，条件式被反例推翻";
    } else {
      tail = " ⇒ 复算不一致或分支状态不可判定，不出结论（R8）";
    }
    return head + tail;
  }

  private static String applicabilityDomain(List<QueryBranch> branches) {
    return "本语料 6 条查询的分支状态：B0（bm25 全 0）"
        + queriesInBranch(branches, B0).size()
        + " 条（"
        + join(queriesInBranch(branches, B0))
        + "）、B1（bm25 全 1）"
        + queriesInBranch(branches, B1).size()
        + " 条（"
        + join(queriesInBranch(branches, B1))
        + "）、B2（非退化）"
        + queriesInBranch(branches, B2).size()
        + " 条"
        + (queriesInBranch(branches, B2).isEmpty()
            ? ""
            : "（" + join(queriesInBranch(branches, B2)) + "）")
        + "；结论只在「零位移事件全部落在 B0 或 B1 查询」这一域内成立，B2 域本卡未观测到，故不主张";
  }

  private static void buildReanchorPlan(
      ObjectNode root, ArrayNode perAnchor, ObjectNode summary, Map<String, String> shaByName) {
    ObjectNode plan = root.putObject("reanchorPlan");
    plan.put("executed", false);
    plan.put("executedBasis", "本卡只读既有锚（零字节变动）；重锚属另卡且须 Owner 拍板，本卡不执行任何重建（D5）");
    plan.put("orderRule", "依赖顺序按锚间 sha256 引用现算：被引用者先于引用者重锚；决策/签收锚最后，因为它签的是常量值");
    ArrayNode ordered = plan.putArray("ordered");
    List<String> affected = split(summary.path("affected").asText());
    List<String> ordered2 = dependencyOrder(affected, shaByName);
    int index = 0;
    for (String name : ordered2) {
      index++;
      ObjectNode node = findAnchorRow(perAnchor, name);
      ObjectNode entry = ordered.addObject();
      entry.put("order", index);
      entry.put("anchor", name);
      String cause = node == null ? "" : node.path("contradictionCause").asText();
      entry.put("method", reanchorMethod(cause));
      entry.put(
          "externalCalls", node == null ? -1 : node.path("minExternalCallsIfNeeded").asInt(-1));
      entry.put("cause", cause);
      entry.put("basis", node == null ? "" : node.path("contradictionBasis").asText());
    }
    ArrayNode unaffected = plan.putArray("unaffected");
    for (String name : split(summary.path("unaffected").asText())) {
      ObjectNode node = findAnchorRow(perAnchor, name);
      ObjectNode entry = unaffected.addObject();
      entry.put("anchor", name);
      entry.put("basis", node == null ? "" : node.path("contradictionBasis").asText());
    }
    ArrayNode unresolved = plan.putArray("unresolved");
    for (String name : split(summary.path("unresolved").asText())) {
      ObjectNode node = findAnchorRow(perAnchor, name);
      ObjectNode entry = unresolved.addObject();
      entry.put("anchor", name);
      entry.put("basis", node == null ? "" : node.path("contradictionBasis").asText());
      entry.put(
          "minExternalCallsIfNeeded",
          node == null ? -1 : node.path("minExternalCallsIfNeeded").asInt(-1));
    }
    plan.put("orderedCount", ordered.size());
    plan.put("unaffectedCount", unaffected.size());
    plan.put("unresolvedCount", unresolved.size());
    plan.put("minExternalCallsTotal", summary.path("minExternalCallsTotal").asInt());
    plan.put("fullRerunTotal", summary.path("fullRerunTotal").asInt());
    plan.put("note", "清单只列事实与代价：降不降是 Owner 的决定，本卡不给倾向值、不排序优劣、不执行（D4/D5）");
  }

  private static ObjectNode findAnchorRow(ArrayNode perAnchor, String name) {
    ObjectNode found = null;
    for (JsonNode node : perAnchor) {
      if (name.equals(node.path("anchor").asText())) {
        found = (ObjectNode) node;
        break;
      }
    }
    return found;
  }

  private static String reanchorMethod(String cause) {
    String method;
    if ("ROW-LEVEL-CLASSIFICATION".equals(cause)) {
      method = "用锚内冻结 cos 按 0.90 参数本地重算 tier/band（零外呼）";
    } else if ("OPERATING-FLOOR-LITERAL".equals(cause)) {
      method = "改写 operating-point 字面量并按签收流程重签（零外呼）";
    } else if ("NO-COS".equals(cause) || "AGGREGATE-ONLY".equals(cause)) {
      method = "重跑该锚的原始测量批次（锚内未冻 cos，聚合分母不可本地重建）";
    } else {
      method = "无需重锚";
    }
    return method;
  }

  private static List<String> dependencyOrder(List<String> names, Map<String, String> shaByName) {
    Map<String, Set<String>> dependencies = new LinkedHashMap<>();
    for (String name : names) {
      dependencies.put(name, new LinkedHashSet<>());
    }
    for (String name : names) {
      for (String other : names) {
        if (name.equals(other)) {
          continue;
        }
        String sha = shaByName.get(other);
        String body = shaByName.get(name);
        if (sha != null && body != null && body.contains(sha)) {
          dependencies.get(name).add(other);
        }
      }
    }
    List<String> ordered = new ArrayList<>();
    Set<String> visiting = new LinkedHashSet<>();
    for (String name : names) {
      visit(name, dependencies, ordered, visiting);
    }
    return ordered;
  }

  private static void visit(
      String name,
      Map<String, Set<String>> dependencies,
      List<String> ordered,
      Set<String> visiting) {
    if (ordered.contains(name) || !visiting.add(name)) {
      return;
    }
    for (String dependency : dependencies.getOrDefault(name, Set.of())) {
      visit(dependency, dependencies, ordered, visiting);
    }
    visiting.remove(name);
    if (!ordered.contains(name)) {
      ordered.add(name);
    }
  }

  private static List<String> split(String value) {
    List<String> parts = new ArrayList<>();
    for (String part : value.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        parts.add(trimmed);
      }
    }
    return parts;
  }

  // ==================== md（≤40 行） ====================

  private static String buildAnchorMd(ObjectNode anchor) {
    List<String> lines = new ArrayList<>();
    lines.add("# near-dup-conditional-min-criterion-v1");
    lines.add("");
    lines.add("卡 36 · 零外呼 · 腿 A 判据条件化（三分支）/ 腿 B 0.90 影响与重锚清单（不执行、不替 Owner 决策）");
    lines.add("");
    lines.add("## 结论");
    lines.add("");
    lines.add("- verdict: " + anchor.path("conditionalVerdict").path("verdict").asText());
    lines.add("- 适用域: " + anchor.path("conditionalVerdict").path("applicabilityDomain").asText());
    lines.add(
        "- 复算: r = 0.6·normVec + 0.4·normBm25，"
            + anchor.path("eventReplay").path("checked").asInt()
            + "/"
            + anchor.path("eventReplay").path("checked").asInt()
            + " 行一致（容差 1e-12，maxAbsDiff "
            + anchor.path("eventReplay").path("maxAbsDiff").asText()
            + "）");
    lines.add(
        "- 零位移: "
            + anchor.path("conditionalVerdict").path("summary").path("zeroDisplacement").asInt()
            + "/"
            + anchor.path("conditionalVerdict").path("summary").path("zeroDisplacement").asInt()
            + " 落 B0 且 normVec = 0；非零 "
            + anchor.path("conditionalVerdict").path("summary").path("nonZeroEvents").asInt()
            + "/"
            + anchor.path("conditionalVerdict").path("summary").path("nonZeroEvents").asInt()
            + " 满足所属分支条件");
    lines.add("");
    lines.add("## 三分支（互斥穷尽）");
    lines.add("");
    lines.add("| 分支 | 判定式 | r = 0 的充要条件 | 查询 | 零位移事件 |");
    lines.add("| --- | --- | --- | --- | --- |");
    for (JsonNode branch : anchor.path("criterion").path("branches")) {
      lines.add(
          "| "
              + branch.path("id").asText()
              + " | `"
              + branch.path("predicate").asText()
              + "` | "
              + branch.path("consequence").asText()
              + " | "
              + branch.path("observedQueries").asInt()
              + " | "
              + branch.path("observedZeroEvents").asInt()
              + " |");
    }
    lines.add("");
    ObjectNode summary = (ObjectNode) anchor.path("impact").path("summary");
    lines.add("## 0.90 影响（NEAR_DUPLICATE_FLOOR 0.982 -> 0.90；BAND_REFERENCE_FLOOR 0.9887 冻结）");
    lines.add("");
    lines.add(
        "- 自相矛盾 "
            + summary.path("contradictionYes").asInt()
            + "/"
            + summary.path("total").asInt()
            + "；不受影响 "
            + summary.path("contradictionNo").asInt()
            + "；未判定 "
            + summary.path("contradictionUndetermined").asInt());
    lines.add(
        "- 可零外呼重算 "
            + summary.path("zeroCallRecomputeYes").asInt()
            + "/"
            + summary.path("total").asInt()
            + "；最小外呼估算总量 "
            + summary.path("minExternalCallsTotal").asInt()
            + "（全量重跑上界 "
            + summary.path("fullRerunTotal").asInt()
            + "）");
    lines.add("- 判定式与逐锚字段路径见 impact.contradictionRule / impact.perAnchor");
    lines.add("");
    lines.add("## 重锚清单（executed = false）");
    lines.add("");
    List<String> orderedNames = new ArrayList<>();
    for (JsonNode entry : anchor.path("reanchorPlan").path("ordered")) {
      orderedNames.add(entry.path("order").asInt() + ". " + entry.path("anchor").asText());
    }
    lines.add("- 顺序（被引用者先）：" + String.join("，", orderedNames));
    lines.add("- 方式：改写 operating-point 字面量并按签收流程重签（零外呼；逐项外呼与依据见 reanchorPlan.ordered）");
    lines.add("");
    lines.add("## 边界");
    lines.add("");
    lines.add("- 不改任何生产常量；src/main / pom.xml / .github 零 diff；既有锚零字节变动");
    lines.add("- 未判定项按 R8 记 UNDETERMINED，不猜；降不降由 Owner 决定，本清单不给倾向值");
    return String.join("\n", lines);
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
        "结论锚缺失，先以 -Dnear.dup.conditional.min.write=true 生成一次: " + path.toAbsolutePath());
    JsonNode onDisk = MAPPER.readTree(Files.readAllBytes(path));
    assertArrayEquals(
        canonicalBytes(withoutRuntime(node)),
        canonicalBytes(withoutRuntime(onDisk)),
        "锚与本次全量重算规范化字节不一致（JSON 树规范化后比较、剔除顶层 runtime）: " + path);
    System.out.println("[near-dup-conditional-min-criterion] 默认复跑逐字节一致（JSON，剔除 runtime）：" + path);
  }

  private static void writeOrCompareBytes(Path path, byte[] expected) throws Exception {
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "锚 md 必须纯 LF");
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论 md 缺失，先以 -Dnear.dup.conditional.min.write=true 生成一次: " + path.toAbsolutePath());
    assertArrayEquals(expected, Files.readAllBytes(path), "锚 md 与本次生成逐字节不一致: " + path);
    System.out.println("[near-dup-conditional-min-criterion] 默认复跑逐字节一致（md）：" + path);
  }

  private static void writeAnchor(Path path, byte[] content) throws Exception {
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, content);
    System.out.println("[near-dup-conditional-min-criterion] 锚已写入（write 模式）: " + path);
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

  private static ObjectNode readObject(Path path) throws Exception {
    assertTrue(Files.exists(path), "文件不存在: " + path.toAbsolutePath());
    return (ObjectNode) MAPPER.readTree(Files.readAllBytes(path));
  }

  private static void putAnchorRef(ObjectNode parent, String name, Path path) throws Exception {
    ObjectNode node = parent.putObject(name);
    node.put("path", rel(path));
    node.put("sha256", sha256(Files.readAllBytes(path)));
    node.put("readOnly", true);
  }

  private static String rel(Path path) {
    return path.toString().replace('\\', '/');
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

  private static String format(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  /** 腿 B 逐锚核对清单（16 张；照 proposal §2 的分类表逐张核对，不照抄结论）。 */
  private static final List<AnchorRef> LEG_B_ANCHORS =
      List.of(
          new AnchorRef("near-dup-floor-signoff-v1", FLOOR_SIGNOFF),
          new AnchorRef(
              "near-dup-ingest-gate-probe-v1",
              Path.of("docs", "rag-quality", "near-dup-ingest-gate-probe-v1.json")),
          new AnchorRef(
              "near-dup-ingest-s1-v1",
              Path.of("docs", "rag-quality", "near-dup-ingest-s1-v1.json")),
          new AnchorRef("near-dup-band-entry-v1", BAND_ENTRY),
          new AnchorRef("near-dup-window-coupling-v1", WINDOW_COUPLING),
          new AnchorRef(
              "near-dup-oq1-attribution-v1",
              Path.of("docs", "rag-quality", "near-dup-oq1-attribution-v1.json")),
          new AnchorRef("near-dup-rerank-decomposition-v1", RERANK_DECOMPOSITION),
          new AnchorRef(
              "near-dup-exchange-rate-v1",
              Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json")),
          new AnchorRef(
              "image-variant-cross-matrix-v1",
              Path.of("docs", "rag-quality", "image-variant-cross-matrix-v1.json")),
          new AnchorRef(
              "near-dup-displacement-evidence-v1",
              Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json")),
          new AnchorRef(
              "near-dup-fusion-impact-v1",
              Path.of("docs", "rag-quality", "near-dup-fusion-impact-v1.json")),
          new AnchorRef(
              "image-near-dup-truth-v1",
              Path.of("docs", "rag-quality", "image-near-dup-truth-v1.json")),
          new AnchorRef(
              "near-dup-ablation-v1", Path.of("docs", "rag-quality", "near-dup-ablation-v1.json")),
          new AnchorRef(
              "image-slice-ingest-chain-v1",
              Path.of("docs", "rag-quality", "image-slice-ingest-chain-v1.json")),
          new AnchorRef(
              "eval-port-route-v1", Path.of("docs", "rag-quality", "eval-port-route-v1.json")),
          new AnchorRef(
              "ui-permission-coverage-v1", Path.of("docs", "ui-permission-coverage-v1.json")));

  private enum Kind {
    CLASSIFICATION,
    COS,
    FLOOR,
    AGGREGATE,
    CALLS
  }

  private record AnchorRef(String name, Path path) {}

  private record Hit(Kind kind, String path, String sample, double value) {}

  private record FrozenRow(
      double cos, String tier, boolean hasTier, String band, boolean hasBand) {}

  private record RouteRow(
      int queryIndex,
      String variantKey,
      String chunkId,
      double cos,
      String tier,
      String band,
      boolean marked,
      double normVec,
      double normBm25,
      double r) {}

  private record QueryBranch(
      int queryIndex,
      String form,
      int rows,
      double max,
      double min,
      String branch,
      boolean vecDegenerate) {}

  private record Replay(
      long checked,
      int violations,
      double maxAbsDiff,
      int vectorMinChecked,
      int vectorMinViolations) {}

  private record EventRow(
      int queryIndex,
      String variantKey,
      String chunkId,
      String branch,
      double normVec,
      double normBm25,
      double r,
      boolean zeroDisplacement,
      boolean conditionSatisfied) {}
}
