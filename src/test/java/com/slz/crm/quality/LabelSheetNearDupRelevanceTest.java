package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 近重复人标最小包（label-sheet-near-dup-relevance）· 腿 A 生成器 + 腿 B 打分器（零外呼）。
 *
 * <p><b>腿 A（标注包）</b>：用生产类（{@link InMemoryVectorStore} → {@link ImageSimilarityGate#gate} → {@link
 * DefaultWeightedReranker}，内含生产 {@link Bm25Scorer}）重建 6 条查询的图像路候选，与冻结锚 {@code
 * docs/rag-quality/near-dup-band-entry-v1.json} 的 30 行候选逐条对账（{@code cos} / {@code tier} / {@code
 * band} / {@code fusedB − fusedA}），再按现行名次（{@code rankB} 升序）落成 30 行盲标表。候选身份与顺序来自该冻结锚的 {@code
 * candidates}（文本路无法在零外呼下重建：查询文本向量不在冻结material 内），图像路分数全部本地重算并逐条对账，脚本不抄任何分数。
 *
 * <p><b>盲标纪律</b>：CSV 只有 {@link #COLUMNS} 这 11 列，不含 {@code cos} / {@code tier} / {@code
 * band}，候选文本逐字取自冻结 {@code corpusMaterial.imageRecords}，不增删内容、不提示。为了「一行一条记录」便于填写，行内换行落成字面 {@code
 * \n}、行尾空白去掉（只动显示形式，不动文字）。
 *
 * <p><b>腿 B（打分器）</b>：读回 CSV；四个待填列全空 ⇒ 锚记 {@code AWAITING-HUMAN-LABELS} 且三情景字段为空；已填 ⇒ 先逐行校验（空值 /
 * 取值越界 / {@code annotator} 命中 agent 字样 / 日期缺失 / 行 id 不符或重复），通过后按三情景并列打分（现状 0.982 / δ=0 / 0.90 口径）。
 *
 * <p><b>零外呼</b>：不设任何 real 开关、不启动 Spring、不调 provider；{@code runtime.externalCalls = 0}。
 */
class LabelSheetNearDupRelevanceTest {

  static final Path BAND_ENTRY = Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  static final Path LABELS_DIR = Path.of("docs", "rag-quality", "labels");

  static final Path CSV = LABELS_DIR.resolve("near-dup-relevance-v1.csv");

  static final Path README = LABELS_DIR.resolve("near-dup-relevance-v1-README.md");

  static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "label-sheet-near-dup-relevance-v1.json");

  static final Path ANCHOR_MD =
      Path.of("docs", "rag-quality", "label-sheet-near-dup-relevance-v1.md");

  /** 首次生成开关（默认只复算比对，不落盘）。 */
  static final boolean WRITE_MODE = Boolean.getBoolean("label.sheet.write");

  static final String CARD = "label-sheet-near-dup-relevance";

  static final String STATUS_AWAITING = "AWAITING-HUMAN-LABELS";

  static final String STATUS_COLLECTED = "LABELS-COLLECTED";

  /** CSV 列名白名单（顺序即写盘顺序；`cos` / `tier` / `band` 不在此表内 = 盲标）。 */
  static final List<String> COLUMNS =
      List.of(
          "rowId",
          "queryIndex",
          "queryText",
          "candidateText",
          "fileName",
          "pageNo",
          "currentRank",
          "relevance",
          "isNearDuplicate",
          "annotator",
          "annotatedOn");

  /** 三情景固定顺序（并列呈现，不排序）。 */
  static final List<String> SCENARIO_KEYS = List.of("current-0982", "no-downweight", "floor-090");

  /** 图像路由权重（{@code RetrievalConfigResolver} 默认 0.30；只用于 δ = 0.30 × (1 − w) × r）。 */
  static final double IMAGE_ROUTE_WEIGHT = 0.30d;

  /** 候选倍数（生产默认 4；本语料 6 条图像记录 ⇒ 上限不构成变量）。 */
  static final int CANDIDATE_MULTIPLIER = 4;

  /** 对账容差（R1/R5 的 same-byte 复算口径）。 */
  static final double TOLERANCE = 1e-9d;

  /** 0.90 口径的准入下限。 */
  static final double FLOOR_090 = 0.90d;

  private static final int TOP_K = 5;

  private static final int QUERY_COUNT = 6;

  private static final int ROW_COUNT = 30;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** annotator 身份闸：只命中整词，避免把 `Bailey` 之类的正常署名误判成 agent。 */
  private static final Pattern AGENT_LIKE =
      Pattern.compile("(?i)(^|[^a-z])(agent|assistant|ai|model)([^a-z]|$)");

  @Test
  @DisplayName("腿 A+腿 B：30 行盲标表 + 三情景打分器（零外呼，标签未填时 AWAITING）")
  void generatesSheetAndScores() throws Exception {
    Bundle bundle = buildBundle();
    assertSheet(bundle);
    writeOrCompareBytes(CSV, bundle.csvBytes());
    writeOrCompareBytes(README, bundle.readmeBytes());
    List<List<String>> records = parseCsv(Files.readString(CSV, StandardCharsets.UTF_8));
    LabelsOutcome outcome = evaluate(bundle, records);
    ObjectNode anchor = buildAnchor(bundle, outcome);
    writeOrCompareJson(ANCHOR_JSON, anchor);
    writeOrCompareBytes(ANCHOR_MD, buildAnchorMd(anchor).getBytes(StandardCharsets.UTF_8));
    System.out.println(
        "[label-sheet] rows="
            + bundle.rows().size()
            + " missingText="
            + bundle.missingRows()
            + " labelsStatus="
            + outcome.status()
            + " scenarios="
            + outcome.scenarios().size());
    if (STATUS_AWAITING.equals(outcome.status())) {
      assertEquals(0, outcome.scenarios().size(), "未填标签时三情景必须为空（不得填假分数）");
    } else {
      assertEquals(SCENARIO_KEYS.size(), outcome.scenarios().size(), "三情景必须并列产出");
    }
  }

  @Test
  @DisplayName("打分器：非法标签逐行拒绝（@TempDir 合成 CSV，不进 docs）")
  void scorerRejectsIllegalLabels(@TempDir Path tempDir) throws Exception {
    Bundle bundle = buildBundle();
    String valid = labelCsv(bundle, row -> new String[] {"2", "N", "owner", "2026-01-01"});
    LabelsOutcome accepted = evaluate(bundle, writeTemp(tempDir, "valid.csv", valid));
    assertEquals(STATUS_COLLECTED, accepted.status(), "合法合成标签必须通过校验");
    assertEquals(SCENARIO_KEYS.size(), accepted.scenarios().size(), "通过后必须产出三情景");

    assertRejected(
        bundle, tempDir, "blank-relevance", withField(valid, "q0:9820", 7, ""), "q0:9820");
    assertRejected(
        bundle, tempDir, "relevance-range", withField(valid, "q0:9820", 7, "3"), "q0:9820");
    assertRejected(
        bundle, tempDir, "near-dup-value", withField(valid, "q0:9820", 8, "X"), "q0:9820");
    assertRejected(
        bundle,
        tempDir,
        "agent-annotator",
        withField(valid, "q0:9820", 9, "ai-labeler"),
        "ai-labeler");
    assertRejected(bundle, tempDir, "blank-date", withField(valid, "q0:9820", 10, ""), "q0:9820");
    assertRejected(
        bundle, tempDir, "bad-date", withField(valid, "q0:9820", 10, "2026-13-40"), "2026-13-40");
    assertRejected(bundle, tempDir, "row-id", withField(valid, "q0:9820", 0, "q0:9999"), "q0:9999");
    assertRejected(bundle, tempDir, "duplicate-row", withDuplicate(valid, "q0:9820"), "q0:9820");
  }

  @Test
  @DisplayName("打分器：已填合成标签产出三情景（并列、不排序、指标在 [0,1] 内）")
  void scorerProducesThreeScenarios(@TempDir Path tempDir) throws Exception {
    Bundle bundle = buildBundle();
    String filled =
        labelCsv(
            bundle,
            row -> {
              if (row.currentRank() <= 2) {
                return new String[] {
                  "2", row.currentRank() == 1 ? "Y" : "N", "owner", "2026-01-01"
                };
              }
              if (row.currentRank() == 3) {
                return new String[] {"1", "U", "owner", "2026-01-01"};
              }
              return new String[] {"0", "N", "owner", "2026-01-01"};
            });
    LabelsOutcome outcome = evaluate(bundle, writeTemp(tempDir, "filled.csv", filled));
    assertEquals(STATUS_COLLECTED, outcome.status());
    assertEquals(SCENARIO_KEYS, outcome.scenarios().stream().map(ScenarioScore::key).toList());
    for (ScenarioScore score : outcome.scenarios()) {
      assertEquals(
          0.6d,
          score.precisionAt5(),
          "本合成标签下每查询 3/5 行相关（名次 1–2 记 2、名次 3 记 1、名次 4–5 记 0）⇒ precision@5 = 0.6");
      assertTrue(score.ndcgAt5() >= 0d && score.ndcgAt5() <= 1d, "NDCG@5 必须落在 [0,1]");
      assertTrue(
          score.ySuppressionRate() == null
              || (score.ySuppressionRate() >= 0d && score.ySuppressionRate() <= 1d),
          "一致率必须落在 [0,1] 或为空");
    }
    ScenarioScore noDownweight =
        outcome.scenarios().stream()
            .filter(score -> score.key().equals("no-downweight"))
            .findFirst()
            .orElseThrow();
    assertEquals(6, noDownweight.yTotal(), "合成标签把每条查询的名次 1 记为 Y ⇒ 6 条 Y");
    assertEquals(0.0d, noDownweight.ySuppressionRate(), "δ=0 情景不压制任何候选");
    assertTrue(outcome.scenarios().get(0).ySuppressionRate() > 0d, "现状情景下至少一条 Y 候选被压制（Y 才有一致率可言）");
    assertEquals(
        outcome.scenarios().get(0).precisionAt5(),
        outcome.scenarios().get(1).precisionAt5(),
        "本语料三情景排序一致 ⇒ precision@5 必须相同（不做排序、不造差异）");
  }

  // ==================== 腿 A · 材料与重建 ====================

  /** 生成一份完整包（CSV 字节 / README 字节 / 30 行 / 重建结果 / 冻结锚）。纯函数：不写盘、不读本地产物。 */
  static Bundle buildBundle() throws Exception {
    ObjectNode bandEntry = (ObjectNode) readJson(BAND_ENTRY);
    assertInputs(bandEntry);
    Reconstruction reconstruction = reconstruct(bandEntry);
    reconcile(bandEntry, reconstruction);
    Map<String, JsonNode> imageByChunk = new LinkedHashMap<>();
    for (JsonNode node : bandEntry.path("corpusMaterial").path("imageRecords")) {
      imageByChunk.put(node.path("chunkId").asText(), node);
    }
    List<SheetRow> rows = new ArrayList<>();
    Map<String, Double> fusedA = new LinkedHashMap<>();
    List<String> missingIds = new ArrayList<>();
    for (JsonNode query : bandEntry.path("queries")) {
      int queryIndex = query.path("index").asInt();
      String queryText = query.path("text").asText();
      List<JsonNode> candidates = new ArrayList<>();
      for (JsonNode candidate : bandEntry.path("candidates")) {
        if (candidate.path("queryIndex").asInt() == queryIndex) {
          candidates.add(candidate);
        }
      }
      assertEquals(
          TOP_K,
          candidates.size(),
          "每条查询必须恰有 " + TOP_K + " 个现行候选（不足就如实减少，本语料实测为满额）: q" + queryIndex);
      int currentRank = 1;
      for (JsonNode candidate : candidates) {
        assertEquals(
            currentRank,
            candidate.path("rankB").asInt(),
            "行顺序必须等于现行名次（rankB 升序，不打乱、不按 cos 排）: q" + queryIndex);
        String chunkId = candidate.path("chunkId").asText().replace("IMAGE:", "");
        JsonNode record = imageByChunk.get(chunkId);
        assertNotNull(record, "候选切片必须在冻结 corpusMaterial.imageRecords 内: " + chunkId);
        JsonNode metadata = record.path("metadata");
        String candidateText = flattenText(record.path("text").asText(""));
        String fileName = metadata.path("filename").asText("");
        int pageNo = metadata.path("pageNo").asInt(0);
        assertFalse(fileName.isBlank(), "候选文件名缺失: " + chunkId);
        assertTrue(pageNo > 0, "候选页码缺失: " + chunkId);
        String rowId = rowId(queryIndex, chunkId);
        if (candidateText.isBlank()) {
          missingIds.add(rowId);
        }
        rows.add(
            new SheetRow(
                rowId, queryIndex, queryText, candidateText, fileName, pageNo, currentRank));
        fusedA.put(rowId, candidate.path("fusedA").asDouble());
        currentRank++;
      }
    }
    assertEquals(ROW_COUNT, rows.size(), "标注包必须恰 " + ROW_COUNT + " 行");
    assertEquals(
        ROW_COUNT, new LinkedHashSet<>(rows.stream().map(SheetRow::rowId).toList()).size());
    assertTrue(
        missingIds.size() <= 3,
        "R7/D5：可读文本缺失 " + missingIds.size() + " 行（> 3）⇒ 停手上报，不降级成只标能标的: " + missingIds);
    byte[] csvBytes = renderCsv(rows).getBytes(StandardCharsets.UTF_8);
    byte[] readmeBytes = README_TEXT.getBytes(StandardCharsets.UTF_8);
    assertTrue(!new String(csvBytes, StandardCharsets.UTF_8).contains("\r"), "CSV 必须纯 LF");
    assertTrue(!new String(readmeBytes, StandardCharsets.UTF_8).contains("\r"), "README 必须纯 LF");
    return new Bundle(
        List.copyOf(rows),
        Map.copyOf(fusedA),
        csvBytes,
        readmeBytes,
        reconstruction,
        List.copyOf(missingIds));
  }

  private static void assertInputs(ObjectNode bandEntry) {
    assertEquals(
        "near-dup-band-entry-and-tier-fill", bandEntry.path("card").asText(), "读错冻结锚（card 不符）");
    assertEquals(QUERY_COUNT, bandEntry.path("queries").size(), "冻结锚查询必须 6 条");
    assertEquals(ROW_COUNT, bandEntry.path("candidates").size(), "冻结锚候选必须 30 行");
    assertEquals(6, bandEntry.path("corpusMaterial").path("imageRecords").size(), "冻结材料缺图像记录");
    assertEquals(
        6, bandEntry.path("corpusMaterial").path("queryImageVectors").size(), "冻结材料缺查询图像向量");
    assertEquals(TOP_K, bandEntry.path("constants").path("topK").asInt(), "生产 topK 必须是 5");
    assertEquals(
        12, bandEntry.path("windowCheck").path("entries").size(), "冻结锚必须仍记 12 条降权事件（对账基准）");
  }

  /** 用生产类重建图像路候选（顺序与 卡35 同源：search → gate → rerank）。 */
  private static Reconstruction reconstruct(ObjectNode bandEntry) {
    JsonNode material = bandEntry.path("corpusMaterial");
    List<NearDupIngestS1IT.StoredRecord> imageRecords = new ArrayList<>();
    for (JsonNode node : material.path("imageRecords")) {
      imageRecords.add(NearDupIngestS1IT.StoredRecord.fromJson(node));
    }
    Map<Integer, float[]> queryVectors = new LinkedHashMap<>();
    for (JsonNode node : material.path("queryImageVectors")) {
      queryVectors.put(node.path("index").asInt(), decodeVector(node.path("vector").asText()));
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
    int candidateLimit = TOP_K * CANDIDATE_MULTIPLIER;
    assertTrue(
        imageRecords.size() <= candidateLimit,
        "图像记录数 " + imageRecords.size() + " 超过召回上限 " + candidateLimit);
    DefaultWeightedReranker reranker =
        new DefaultWeightedReranker(
            new Bm25Scorer(),
            RagBenchmarkPipelineFactory.provider(
                RagBenchmarkPipelineFactory.dynamicConfig(bandConfigMatrix())));
    List<RebuiltRow> rows = new ArrayList<>();
    for (JsonNode query : bandEntry.path("queries")) {
      int queryIndex = query.path("index").asInt();
      String text = query.path("text").asText();
      float[] vector = queryVectors.get(queryIndex);
      assertNotNull(vector, "缺 queryIndex=" + queryIndex + " 的查询图像向量");
      List<RetrievalCandidate> recalled =
          store.search(new VectorSearchRequest(vector, candidateLimit, floor, filter)).stream()
              .filter(result -> result.text() != null && !result.text().isBlank())
              .map(result -> new RetrievalCandidate(result, result.score()))
              .toList();
      List<RetrievalCandidate> gated = ImageSimilarityGate.gate(recalled, threshold);
      List<RetrievalCandidate> reranked = reranker.rerank(text, gated);
      assertEquals(gated.size(), reranked.size(), "重排必须保序且不增删: q" + queryIndex);
      for (int index = 0; index < gated.size(); index++) {
        VectorSearchHit hit = gated.get(index).hit();
        rows.add(
            new RebuiltRow(
                queryIndex,
                hit.chunkId(),
                hit.score(),
                stringOf(hit.metadata().get(ImageSimilarityGate.TIER_METADATA_KEY)),
                stringOf(hit.metadata().get(ImageSimilarityGate.BAND_METADATA_KEY)),
                reranked.get(index).rerankScore()));
      }
    }
    assertTrue(rows.size() > 0, "图像路重建结果不得为空");
    return new Reconstruction(List.copyOf(rows));
  }

  /**
   * 与冻结锚逐条对账（禁抽样）：{@code cos} / {@code tier} / {@code band} 必须一致；{@code fusedB − fusedA} 必须等于
   * {@code 0.30 × (1 − w) × r}（容差 1e-9），即生产重排分 r 必须能反推锚里记录的降权位移。
   */
  private static void reconcile(ObjectNode bandEntry, Reconstruction reconstruction) {
    Map<String, RebuiltRow> rebuilt = new LinkedHashMap<>();
    for (RebuiltRow row : reconstruction.rows()) {
      rebuilt.put(row.queryIndex() + "|" + row.chunkId(), row);
    }
    int reconciled = 0;
    for (JsonNode candidate : bandEntry.path("candidates")) {
      int queryIndex = candidate.path("queryIndex").asInt();
      String chunkId = candidate.path("chunkId").asText().replace("IMAGE:", "");
      RebuiltRow row = rebuilt.get(queryIndex + "|" + chunkId);
      JsonNode cos = candidate.path("cos");
      if (cos.isNull() || cos.isMissingNode()) {
        assertTrue(row == null, "锚内未走图像路的候选不得出现在重建结果里: q" + queryIndex + " " + chunkId);
        continue;
      }
      assertNotNull(row, "重建缺行（图像路候选不完整）: q" + queryIndex + " " + chunkId);
      assertEquals(cos.asDouble(), row.cos(), 1e-12d, "cos 不符: q" + queryIndex + " " + chunkId);
      assertEquals(
          nullableText(candidate.path("tier")),
          row.tier(),
          "tier 不符: q" + queryIndex + " " + chunkId);
      assertEquals(
          nullableText(candidate.path("band")),
          row.band(),
          "band 不符: q" + queryIndex + " " + chunkId);
      double weight = row.band() == null ? 1d : ImageSimilarityGate.weightOfBand(row.band());
      double expectedDelta = IMAGE_ROUTE_WEIGHT * (1d - weight) * row.r();
      assertEquals(
          expectedDelta,
          candidate.path("fusedB").asDouble() - candidate.path("fusedA").asDouble(),
          TOLERANCE,
          "降权位移必须等于 0.30 × (1 − w) × r: q" + queryIndex + " " + chunkId);
      reconciled++;
    }
    assertEquals(reconstruction.rows().size(), reconciled, "重建行必须与锚内图像路候选一一对应（不多不少）");
    for (JsonNode entry : bandEntry.path("windowCheck").path("entries")) {
      int queryIndex = entry.path("queryIndex").asInt();
      String chunkId = entry.path("chunkId").asText().replace("IMAGE:", "");
      RebuiltRow row = rebuilt.get(queryIndex + "|" + chunkId);
      assertNotNull(row, "12 条降权事件必须能定位到重建候选: q" + queryIndex + " " + chunkId);
      assertEquals(
          entry.path("delta").asDouble(),
          IMAGE_ROUTE_WEIGHT * (1d - entry.path("weight").asDouble()) * row.r(),
          TOLERANCE,
          "windowCheck.delta 必须与本地重算一致: q" + queryIndex + " " + chunkId);
    }
  }

  private static Map<String, Object> bandConfigMatrix() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put("pdf.image.slice.enabled", Boolean.TRUE);
    matrix.put("pdf.image.slice.image-vector.enabled", Boolean.TRUE);
    matrix.put("pdf.image.slice.max-images-per-pdf", 20);
    matrix.put("rag.retrieval.query-rewrite.enabled", Boolean.FALSE);
    return matrix;
  }

  // ==================== 腿 B · 读回 / 校验 / 三情景打分 ====================

  /** 读回 CSV：先判「四列全空 ⇒ AWAITING」，否则逐行校验后再打分。 */
  static LabelsOutcome evaluate(Bundle bundle, List<List<String>> records) {
    List<LabelRow> labels = toLabels(records);
    boolean allBlank = true;
    for (LabelRow label : labels) {
      if (!label.isBlank()) {
        allBlank = false;
        break;
      }
    }
    if (allBlank) {
      return new LabelsOutcome(STATUS_AWAITING, "", "", List.of());
    }
    List<String> errors = validate(bundle.rows(), labels);
    if (!errors.isEmpty()) {
      throw new IllegalStateException("标签校验失败（R3），逐行错误：\n" + String.join("\n", errors));
    }
    String annotator = labels.get(0).annotator();
    String annotatedOn = labels.get(0).annotatedOn();
    return new LabelsOutcome(STATUS_COLLECTED, annotator, annotatedOn, score(bundle, labels));
  }

  private static List<LabelRow> toLabels(List<List<String>> records) {
    assertTrue(!records.isEmpty(), "CSV 为空");
    assertEquals(COLUMNS, records.get(0), "CSV 表头必须等于列名白名单（盲标：不得出现 cos/tier/band）");
    List<LabelRow> labels = new ArrayList<>();
    for (int index = 1; index < records.size(); index++) {
      List<String> fields = records.get(index);
      assertEquals(
          COLUMNS.size(), fields.size(), "第 " + index + " 行字段数必须 " + COLUMNS.size() + "（不许增删列）");
      labels.add(
          new LabelRow(
              fields.get(0),
              fields.get(1),
              fields.get(6),
              fields.get(7),
              fields.get(8),
              fields.get(9),
              fields.get(10)));
    }
    return labels;
  }

  /** R3：逐行收集错误，不在第一条就中断（便于 Owner 一次改完）。 */
  private static List<String> validate(List<SheetRow> sheet, List<LabelRow> labels) {
    List<String> errors = new ArrayList<>();
    Map<String, SheetRow> byId = new LinkedHashMap<>();
    for (SheetRow row : sheet) {
      byId.put(row.rowId(), row);
    }
    Map<String, Integer> seen = new LinkedHashMap<>();
    if (labels.size() != sheet.size()) {
      errors.add("行数不符：CSV " + labels.size() + " 行，包 " + sheet.size() + " 行");
    }
    for (int index = 0; index < labels.size(); index++) {
      LabelRow label = labels.get(index);
      int line = index + 1;
      String prefix = "第 " + line + " 行（rowId=" + label.rowId() + "）";
      SheetRow row = byId.get(label.rowId());
      if (row == null) {
        errors.add(prefix + "：rowId 不在包内");
        continue;
      }
      Integer previous = seen.get(label.rowId());
      if (previous != null) {
        errors.add(prefix + "：rowId 重复（第 " + previous + " 行已出现）");
      } else {
        seen.put(label.rowId(), line);
      }
      if (!String.valueOf(row.queryIndex()).equals(label.queryIndex())) {
        errors.add(prefix + "：queryIndex 与包不一致（包内 " + row.queryIndex() + "）");
      }
      if (!String.valueOf(row.currentRank()).equals(label.currentRank())) {
        errors.add(prefix + "：currentRank 与包不一致（包内 " + row.currentRank() + "）");
      }
      if (label.relevance().isBlank()) {
        errors.add(prefix + "：relevance 为空");
      } else if (!label.relevance().matches("[012]")) {
        errors.add(prefix + "：relevance 只能 0 / 1 / 2，实测 " + label.relevance());
      }
      if (label.isNearDuplicate().isBlank()) {
        errors.add(prefix + "：isNearDuplicate 为空");
      } else if (!label.isNearDuplicate().matches("[YNU]")) {
        errors.add(prefix + "：isNearDuplicate 只能 Y / N / U，实测 " + label.isNearDuplicate());
      }
      if (label.annotator().isBlank()) {
        errors.add(prefix + "：annotator 为空");
      } else if (AGENT_LIKE.matcher(label.annotator()).find()) {
        errors.add(prefix + "：annotator 命中 agent/assistant/ai/model 字样，必须由人署名");
      }
      if (label.annotatedOn().isBlank()) {
        errors.add(prefix + "：annotatedOn 为空");
      } else {
        try {
          LocalDate.parse(label.annotatedOn());
        } catch (DateTimeParseException exception) {
          errors.add(prefix + "：annotatedOn 必须 YYYY-MM-DD，实测 " + label.annotatedOn());
        }
      }
    }
    for (SheetRow row : sheet) {
      if (!seen.containsKey(row.rowId())) {
        errors.add("缺行（rowId=" + row.rowId() + "）：包内 30 行必须逐行给出标签");
      }
    }
    Set<String> annotators = new LinkedHashSet<>();
    Set<String> dates = new LinkedHashSet<>();
    for (LabelRow label : labels) {
      if (!label.annotator().isBlank()) {
        annotators.add(label.annotator());
      }
      if (!label.annotatedOn().isBlank()) {
        dates.add(label.annotatedOn());
      }
    }
    if (annotators.size() > 1) {
      errors.add("annotator 不一致（多值：" + annotators + "），本表只登记一位标注人");
    }
    if (dates.size() > 1) {
      errors.add("annotatedOn 不一致（多值：" + dates + "），本表只登记一个填写日期");
    }
    return errors;
  }

  /** 三情景并列打分：每情景给 precision@5、NDCG@5、`Y` 候选被压制一致率。 */
  private static List<ScenarioScore> score(Bundle bundle, List<LabelRow> labels) {
    Map<String, LabelRow> byId = new LinkedHashMap<>();
    for (LabelRow label : labels) {
      byId.put(label.rowId(), label);
    }
    List<ScenarioScore> scores = new ArrayList<>();
    for (String key : SCENARIO_KEYS) {
      Map<Integer, List<RankedRow>> ranked = rank(key, bundle);
      double precisionSum = 0d;
      double ndcgSum = 0d;
      List<String> top5 = new ArrayList<>();
      for (Map.Entry<Integer, List<RankedRow>> entry : ranked.entrySet()) {
        List<RankedRow> rows = entry.getValue();
        assertEquals(TOP_K, rows.size(), "情景 " + key + " 下每条查询必须仍可评 5 行（q" + entry.getKey() + "）");
        List<Integer> relevance = new ArrayList<>();
        List<String> rowIds = new ArrayList<>();
        for (RankedRow row : rows) {
          relevance.add(Integer.parseInt(byId.get(row.row().rowId()).relevance()));
          rowIds.add(row.row().rowId());
        }
        int relevant = 0;
        for (int value : relevance) {
          relevant += value >= 1 ? 1 : 0;
        }
        precisionSum += (double) relevant / TOP_K;
        ndcgSum += ndcg(relevance);
        top5.addAll(rowIds);
      }
      int yTotal = 0;
      int ySuppressed = 0;
      for (String rowId : bundle.rows().stream().map(SheetRow::rowId).toList()) {
        if (!"Y".equals(byId.get(rowId).isNearDuplicate())) {
          continue;
        }
        yTotal++;
        if (suppressed(key, bundle, rowId)) {
          ySuppressed++;
        }
      }
      int queryCount = ranked.size();
      scores.add(
          new ScenarioScore(
              key,
              scenarioLabel(key),
              round(precisionSum / queryCount),
              round(ndcgSum / queryCount),
              yTotal == 0 ? null : round((double) ySuppressed / yTotal),
              ySuppressed,
              yTotal,
              List.copyOf(top5)));
    }
    return List.copyOf(scores);
  }

  /** 按情景重算每行 fused 分并排序（不写回 CSV，纯内存）。 */
  private static Map<Integer, List<RankedRow>> rank(String key, Bundle bundle) {
    Map<Integer, List<RankedRow>> ranked = new LinkedHashMap<>();
    for (SheetRow row : bundle.rows()) {
      RowScore rowScore = rowScore(key, row, bundle);
      if (rowScore.weight() <= 0d) {
        throw new IllegalStateException("情景 " + key + " 下候选被准入下限过滤（本语料不应发生）: " + row.rowId());
      }
      ranked
          .computeIfAbsent(row.queryIndex(), ignored -> new ArrayList<>())
          .add(new RankedRow(row, rowScore.score(), rowScore.weight()));
    }
    for (List<RankedRow> rows : ranked.values()) {
      rows.sort(
          Comparator.comparingDouble(RankedRow::score)
              .reversed()
              .thenComparingInt(value -> value.row().currentRank())
              .thenComparing(value -> value.row().rowId()));
    }
    return ranked;
  }

  private static RowScore rowScore(String key, SheetRow row, Bundle bundle) {
    double base = bundle.fusedA().get(row.rowId());
    RebuiltRow rebuilt = bundle.reconstruction().find(row.queryIndex(), chunkOf(row.rowId()));
    if (rebuilt == null) {
      return new RowScore(base, 1d);
    }
    switch (key) {
      case "no-downweight" -> {
        return new RowScore(base, 1d);
      }
      case "current-0982" -> {
        double weight =
            rebuilt.band() == null ? 1d : ImageSimilarityGate.weightOfBand(rebuilt.band());
        return new RowScore(base + IMAGE_ROUTE_WEIGHT * (1d - weight) * rebuilt.r(), weight);
      }
      case "floor-090" -> {
        String tier = floor090Tier(rebuilt.cos());
        if (ImageSimilarityGate.TIER_CROSS_IMAGE.equals(tier)) {
          return new RowScore(base, 0d);
        }
        double weight =
            ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(tier)
                ? ImageSimilarityGate.weightOfBand(
                    ImageSimilarityGate.bandOf(
                        rebuilt.cos(), ImageSimilarityGate.DEFAULT_THRESHOLD))
                : 1d;
        return new RowScore(base + IMAGE_ROUTE_WEIGHT * (1d - weight) * rebuilt.r(), weight);
      }
      default -> throw new IllegalArgumentException("未知情景: " + key);
    }
  }

  /** 0.90 口径：阈值不变（0.9944），只把准入下限从 0.982 降到 0.90。 */
  private static String floor090Tier(double cosine) {
    if (cosine >= ImageSimilarityGate.DEFAULT_THRESHOLD) {
      return ImageSimilarityGate.TIER_SAME_IMAGE;
    }
    return cosine >= FLOOR_090
        ? ImageSimilarityGate.TIER_NEAR_DUPLICATE
        : ImageSimilarityGate.TIER_CROSS_IMAGE;
  }

  private static boolean suppressed(String key, Bundle bundle, String rowId) {
    SheetRow row =
        bundle.rows().stream()
            .filter(value -> value.rowId().equals(rowId))
            .findFirst()
            .orElseThrow();
    double weight = rowScore(key, row, bundle).weight();
    return weight > 0d && weight < 1d;
  }

  private static double ndcg(List<Integer> relevance) {
    double dcg = 0d;
    for (int index = 0; index < relevance.size(); index++) {
      dcg += gain(relevance.get(index)) / log2(index + 2);
    }
    List<Integer> ideal = new ArrayList<>(relevance);
    ideal.sort(Comparator.reverseOrder());
    double idcg = 0d;
    for (int index = 0; index < ideal.size(); index++) {
      idcg += gain(ideal.get(index)) / log2(index + 2);
    }
    return idcg == 0d ? 0d : dcg / idcg;
  }

  private static double gain(int relevance) {
    return Math.pow(2d, relevance) - 1d;
  }

  private static double log2(int value) {
    return Math.log(value) / Math.log(2d);
  }

  private static double round(double value) {
    return Math.round(value * 1_000_000d) / 1_000_000d;
  }

  private static String scenarioLabel(String key) {
    switch (key) {
      case "current-0982" -> {
        return "现状（生产准入下限 0.982）";
      }
      case "no-downweight" -> {
        return "无降权（δ = 0）";
      }
      case "floor-090" -> {
        return "0.90 口径（准入下限降到 0.90）";
      }
      default -> throw new IllegalArgumentException("未知情景: " + key);
    }
  }

  private static String scenarioDefinition(String key) {
    switch (key) {
      case "current-0982" -> {
        return "tier/band 由生产 ImageSimilarityGate 现算，fused = fusedA + 0.30 × (1 − bandWeight) × r";
      }
      case "no-downweight" -> {
        return "不施加任何 band 降权，fused = fusedA（δ = 0）";
      }
      case "floor-090" -> {
        return "准入下限降到 0.90（阈值仍 0.9944），按同批候选重算 tier/band/weight 与 δ";
      }
      default -> throw new IllegalArgumentException("未知情景: " + key);
    }
  }

  // ==================== CSV / README 渲染 ====================

  static String renderCsv(List<SheetRow> rows) {
    return renderCsv(rows, row -> new String[] {"", "", "", ""});
  }

  static String renderCsv(List<SheetRow> rows, Function<SheetRow, String[]> labels) {
    StringBuilder out = new StringBuilder();
    out.append(String.join(",", COLUMNS)).append('\n');
    for (SheetRow row : rows) {
      String[] label = labels.apply(row);
      appendCsvRow(
          out,
          List.of(
              row.rowId(),
              String.valueOf(row.queryIndex()),
              row.queryText(),
              row.candidateText(),
              row.fileName(),
              String.valueOf(row.pageNo()),
              String.valueOf(row.currentRank()),
              label[0],
              label[1],
              label[2],
              label[3]));
    }
    return out.toString();
  }

  private static void appendCsvRow(StringBuilder out, List<String> values) {
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        out.append(',');
      }
      out.append(csvField(values.get(index)));
    }
    out.append('\n');
  }

  private static String csvField(String value) {
    boolean needsQuote =
        value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    return needsQuote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
  }

  /** 读回：RFC4180 子集（双引号包裹、内部引号翻倍、引号内可含逗号与换行）。 */
  static List<List<String>> parseCsv(String content) {
    List<List<String>> records = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < content.length(); index++) {
      char ch = content.charAt(index);
      if (quoted) {
        if (ch == '"') {
          if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
            field.append('"');
            index++;
          } else {
            quoted = false;
          }
        } else {
          field.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == ',') {
        fields.add(field.toString());
        field.setLength(0);
      } else if (ch == '\n') {
        fields.add(field.toString());
        field.setLength(0);
        records.add(List.copyOf(fields));
        fields.clear();
      } else if (ch != '\r') {
        field.append(ch);
      }
    }
    if (field.length() > 0 || !fields.isEmpty()) {
      fields.add(field.toString());
      records.add(List.copyOf(fields));
    }
    return records;
  }

  private static void assertSheet(Bundle bundle) {
    assertEquals(ROW_COUNT, bundle.rows().size(), "标注包必须 30 行");
    Set<String> ids = new LinkedHashSet<>();
    for (SheetRow row : bundle.rows()) {
      assertTrue(
          row.rowId().matches("q[0-5]:\\d+"), "rowId 必须 q{queryIndex}:{chunkId}: " + row.rowId());
      assertTrue(ids.add(row.rowId()), "rowId 必须唯一: " + row.rowId());
      assertFalse(row.candidateText().isBlank(), "候选文本为空白行: " + row.rowId());
      assertFalse(row.queryText().isBlank(), "查询文本为空: " + row.rowId());
      assertTrue(row.currentRank() >= 1 && row.currentRank() <= TOP_K, "名次越界: " + row.rowId());
    }
    String csv = new String(bundle.csvBytes(), StandardCharsets.UTF_8);
    for (String forbidden : List.of("近重复", "可疑", "压制")) {
      assertFalse(csv.contains(forbidden), "盲标 CSV 不得出现提示字样「" + forbidden + "」");
    }
    assertEquals(ROW_COUNT + 1, parseCsv(csv).size(), "CSV 必须 1 行表头 + 30 行数据");
  }

  private static String labelCsv(Bundle bundle, Function<SheetRow, String[]> labels) {
    return renderCsv(bundle.rows(), labels);
  }

  private static List<List<String>> writeTemp(Path dir, String name, String content)
      throws Exception {
    Path file = dir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    assertTrue(Files.exists(file), "合成 CSV 必须落在 @TempDir 内: " + file);
    assertFalse(
        file.toAbsolutePath().normalize().startsWith(Path.of("docs").toAbsolutePath().normalize()),
        "合成 CSV 不得进 docs/");
    return parseCsv(Files.readString(file, StandardCharsets.UTF_8));
  }

  /** 对合法 CSV 做一处定点破坏，断言打分器点名该行失败（而不是静默通过）。 */
  private static void assertRejected(
      Bundle bundle, Path tempDir, String name, String broken, String expectedToken)
      throws Exception {
    List<List<String>> records = writeTemp(tempDir, name + ".csv", broken);
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> evaluate(bundle, records));
    assertTrue(
        error.getMessage() != null && error.getMessage().contains("标签校验失败"),
        "错误信息必须说明校验失败: " + name);
    assertTrue(
        error.getMessage().contains(expectedToken),
        "错误信息必须点名具体行/取值（期望含 " + expectedToken + "），实测: " + error.getMessage());
  }

  /** 按列下标改写某一行（CSV 已有换行/引号，直接改文本会误伤，故先解析再重渲染）。 */
  static String withField(String csv, String rowId, int columnIndex, String value) {
    List<List<String>> records = parseCsv(csv);
    List<List<String>> mutatedRecords = new ArrayList<>();
    boolean patched = false;
    for (List<String> record : records) {
      if (!patched && record.get(0).equals(rowId)) {
        List<String> mutated = new ArrayList<>(record);
        mutated.set(columnIndex, value);
        mutatedRecords.add(List.copyOf(mutated));
        patched = true;
      } else {
        mutatedRecords.add(record);
      }
    }
    assertTrue(patched, "定点破坏找不到行: " + rowId);
    return renderRecords(mutatedRecords);
  }

  /** 复制一行制造重复 rowId。 */
  static String withDuplicate(String csv, String rowId) {
    List<List<String>> records = parseCsv(csv);
    List<String> copy = null;
    for (List<String> record : records) {
      if (record.get(0).equals(rowId)) {
        copy = new ArrayList<>(record);
        break;
      }
    }
    assertNotNull(copy, "找不到待复制的行: " + rowId);
    records.add(copy);
    return renderRecords(records);
  }

  private static String renderRecords(List<List<String>> records) {
    StringBuilder out = new StringBuilder();
    for (List<String> record : records) {
      appendCsvRow(out, record);
    }
    return out.toString();
  }

  // ==================== 锚 ====================

  static ObjectNode buildAnchor(Bundle bundle, LabelsOutcome outcome) throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", CARD);
    root.put("stage", "leg-A+leg-B+leg-C");
    root.put("labelsStatus", outcome.status());
    ObjectNode sheet = root.putObject("sheet");
    sheet.put("csv", slash(CSV));
    sheet.put("readme", slash(README));
    sheet.put("rowCount", bundle.rows().size());
    sheet.put("columnCount", COLUMNS.size());
    ArrayNode columns = sheet.putArray("columns");
    COLUMNS.forEach(columns::add);
    ArrayNode fillable = sheet.putArray("fillableColumns");
    fillable.add("relevance");
    fillable.add("isNearDuplicate");
    fillable.add("annotator");
    fillable.add("annotatedOn");
    sheet.put("rowOrder", "queryIndex ASC, currentRank ASC（= 现行名次，不打乱、不按 cos 排）");
    sheet.put("sha256", RealPdfAssets.sha256(bundle.csvBytes()));
    sheet.put("readmeSha256", RealPdfAssets.sha256(bundle.readmeBytes()));
    sheet.put("annotator", outcome.annotator());
    sheet.put("annotatedOn", outcome.annotatedOn());
    sheet.put("queryCount", QUERY_COUNT);
    sheet.put("candidatesPerQuery", TOP_K);
    ObjectNode material = root.putObject("sheetMaterial");
    material.put("queries", "docs/rag-quality/near-dup-band-entry-v1.json#queries[].text（冻结）");
    material.put(
        "candidateIdentityAndOrder",
        "docs/rag-quality/near-dup-band-entry-v1.json#candidates（生产 top-5，图像路 + 文本路）");
    material.put(
        "candidateText",
        "docs/rag-quality/near-dup-band-entry-v1.json#corpusMaterial.imageRecords[].text（冻结原文；仅把行内换行落成字面 \\n、去行尾空白，便于一行一条记录）");
    material.put("textSuppliedRowCount", bundle.rows().size() - bundle.missingRows());
    root.set("scenarioDefinitions", scenarioDefinitions());
    ArrayNode scenarios = root.putArray("scenarios");
    for (ScenarioScore score : outcome.scenarios()) {
      ObjectNode node = scenarios.addObject();
      node.put("key", score.key());
      node.put("label", score.label());
      node.put("definition", scenarioDefinition(score.key()));
      node.put("precisionAt5", score.precisionAt5());
      node.put("ndcgAt5", score.ndcgAt5());
      if (score.ySuppressionRate() == null) {
        node.putNull("ySuppressionRate");
      } else {
        node.put("ySuppressionRate", score.ySuppressionRate());
      }
      node.put("ySuppressed", score.ySuppressed());
      node.put("yTotal", score.yTotal());
      ArrayNode top5 = node.putArray("top5RowIds");
      score.top5RowIds().forEach(top5::add);
    }
    ObjectNode coverage = root.putObject("textCoverage");
    coverage.put("rowCount", bundle.rows().size());
    coverage.put("rowsWithText", bundle.rows().size() - bundle.missingRows());
    coverage.put("missingRows", bundle.missingRows());
    ArrayNode missing = coverage.putArray("missingRowIds");
    bundle.missingRowIds().forEach(missing::add);
    ObjectNode reconciliation = root.putObject("reconstructionCheck");
    reconciliation.put("imageRouteRowsRebuilt", bundle.reconstruction().rows().size());
    reconciliation.put("reconciled", bundle.reconstruction().rows().size());
    reconciliation.put("tolerance", TOLERANCE);
    reconciliation.put(
        "method",
        "生产 InMemoryVectorStore.search → ImageSimilarityGate.gate → DefaultWeightedReranker.rerank（内含生产 Bm25Scorer），逐条比对锚内 cos / tier / band / (fusedB − fusedA)");
    ObjectNode residual = root.putObject("residual");
    residual.put("queryCount", QUERY_COUNT);
    residual.put("candidateCount", ROW_COUNT);
    residual.put("note", "本锚只对本次 6 查询 × 30 候选成立；不外推到其它语料、其它查询或线上体验。文本路候选的 fusedA 直接取自冻结锚，未在本地重算。");
    ObjectNode runtime = root.putObject("runtime");
    runtime.put("externalCalls", 0);
    runtime.put("providerInstances", 0);
    runtime.put("springContextStarted", false);
    runtime.put("dockerRequired", false);
    runtime.put("generatedAt", Instant.now().toString());
    ObjectNode hygiene = root.putObject("numericHygiene");
    hygiene.put("metricRounding", "round(1e-6)");
    hygiene.put("nullWhenAwaiting", true);
    hygiene.put("scenariosParallel", true);
    hygiene.put("scenariosSorted", false);
    hygiene.put("noPlaceholderScores", true);
    ObjectNode sources = root.putObject("sourceAnchors");
    ObjectNode band = sources.putObject("bandEntry");
    band.put("path", slash(BAND_ENTRY));
    band.put("sha256", RealPdfAssets.sha256(Files.readAllBytes(BAND_ENTRY)));
    band.put("readOnly", true);
    return root;
  }

  private static ArrayNode scenarioDefinitions() {
    ArrayNode definitions = MAPPER.createArrayNode();
    for (String key : SCENARIO_KEYS) {
      ObjectNode node = definitions.addObject();
      node.put("key", key);
      node.put("label", scenarioLabel(key));
      node.put("definition", scenarioDefinition(key));
      ArrayNode metrics = node.putArray("metrics");
      metrics.add("precisionAt5");
      metrics.add("ndcgAt5");
      metrics.add("ySuppressionRate");
    }
    return definitions;
  }

  static String buildAnchorMd(ObjectNode anchor) {
    JsonNode sheet = anchor.path("sheet");
    JsonNode coverage = anchor.path("textCoverage");
    JsonNode reconciliation = anchor.path("reconstructionCheck");
    boolean awaiting = STATUS_AWAITING.equals(anchor.path("labelsStatus").asText());
    StringBuilder out = new StringBuilder();
    out.append("# 近重复人标最小包 · v1\n\n");
    out.append("- 卡：`").append(anchor.path("card").asText()).append("`\n");
    out.append("- 状态：`").append(anchor.path("labelsStatus").asText()).append("`\n");
    out.append("- 标注表：`")
        .append(sheet.path("csv").asText())
        .append("`（")
        .append(sheet.path("rowCount").asInt())
        .append(" 行 × ")
        .append(sheet.path("columnCount").asInt())
        .append(" 列，末 4 列待人填）\n");
    out.append("- 填表说明：`").append(sheet.path("readme").asText()).append("`\n");
    out.append("- CSV sha256：`").append(sheet.path("sha256").asText()).append("`\n");
    out.append("- 来源锚：`")
        .append(anchor.path("sourceAnchors").path("bandEntry").path("path").asText())
        .append("`（只读，sha256 `")
        .append(anchor.path("sourceAnchors").path("bandEntry").path("sha256").asText())
        .append("`）\n\n");
    out.append("## 材料\n\n");
    out.append("- 6 条查询 × 现行前 5 候选 = ")
        .append(sheet.path("rowCount").asInt())
        .append(" 行；行 id `q{queryIndex}:{chunkId}`，行序 = 现行名次（不打乱、不按相似度排）。\n");
    out.append("- 候选可读文本取自冻结 `corpusMaterial.imageRecords`，缺失 ")
        .append(coverage.path("missingRows").asInt())
        .append(" 行。\n");
    out.append("- 图像路 cos/tier/band/r 由本地生产类重算，与来源锚逐条对账 ")
        .append(reconciliation.path("reconciled").asInt())
        .append("/")
        .append(reconciliation.path("imageRouteRowsRebuilt").asInt())
        .append("。\n\n");
    out.append("## 三情景（并列，不排序）\n\n");
    out.append("| 情景 | 排序口径 |\n| --- | --- |\n");
    out.append("| 现状 | 生产准入下限 0.982 + 三档 band 降权 |\n");
    out.append("| 无降权 | δ = 0，不施加 band 降权 |\n");
    out.append("| 0.90 口径 | 准入下限降到 0.90，按同批候选重算 tier/band/weight |\n\n");
    out.append("## 当前产出\n\n");
    if (awaiting) {
      out.append("- 标签未填：三情景指标一律留空，不写任何占位分数。\n");
    } else {
      for (JsonNode scenario : anchor.path("scenarios")) {
        out.append("- ")
            .append(scenario.path("label").asText())
            .append("：`precisionAt5`=")
            .append(scenario.path("precisionAt5").asText())
            .append("、`ndcgAt5`=")
            .append(scenario.path("ndcgAt5").asText())
            .append("、`ySuppressionRate`=")
            .append(scenario.path("ySuppressionRate").asText())
            .append("\n");
      }
    }
    out.append("\n## 边界\n\n");
    out.append("- 本锚只对本次 6 查询 × 30 候选成立；不外推到其它语料、其它查询或线上体验。\n");
    out.append("- 零外呼：`externalCalls = 0`；不启 Spring、不调 provider。\n\n");
    out.append("## 复认证\n\n");
    out.append(
        "- 写模式：`mvn -B -ntp test -Dtest=LabelSheetNearDupRelevanceTest \"-Dlabel.sheet.write=true\"`\n");
    out.append("- 默认复跑逐字节一致（JSON 剔除顶层 runtime）。\n");
    return out.toString();
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
        Files.exists(path), "结论锚缺失，先以 -Dlabel.sheet.write=true 生成一次: " + path.toAbsolutePath());
    JsonNode onDisk = readJson(path);
    assertArrayEquals(
        canonicalBytes(withoutRuntime(node)),
        canonicalBytes(withoutRuntime(onDisk)),
        "锚与本次全量重算规范化字节不一致（剔除顶层 runtime）: " + path);
    System.out.println("[label-sheet] 默认复跑逐字节一致（JSON 树规范化，剔除顶层 runtime）：" + path);
  }

  private static void writeOrCompareBytes(Path path, byte[] expected) throws Exception {
    assertTrue(!new String(expected, StandardCharsets.UTF_8).contains("\r"), "产物必须纯 LF: " + path);
    if (WRITE_MODE) {
      writeAnchor(path, expected);
      return;
    }
    assertTrue(
        Files.exists(path), "产物缺失，先以 -Dlabel.sheet.write=true 生成一次: " + path.toAbsolutePath());
    assertArrayEquals(expected, Files.readAllBytes(path), "产物与本次生成逐字节不一致: " + path);
    System.out.println("[label-sheet] 默认复跑逐字节一致：" + path);
  }

  private static void writeAnchor(Path path, byte[] content) throws Exception {
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, content);
    System.out.println("[label-sheet] 已写入（write 模式）: " + path);
  }

  static JsonNode withoutRuntime(JsonNode node) {
    ObjectNode copy = node.deepCopy();
    copy.remove("runtime");
    return copy;
  }

  static byte[] canonicalBytes(JsonNode node) throws Exception {
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

  // ==================== 通用帮手 ====================

  private static float[] decodeVector(String base64) {
    byte[] raw = Base64.getDecoder().decode(base64);
    float[] vector = new float[raw.length / Float.BYTES];
    ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
    for (int index = 0; index < vector.length; index++) {
      vector[index] = buffer.getFloat();
    }
    return vector;
  }

  private static String rowId(int queryIndex, String chunkId) {
    return "q" + queryIndex + ":" + chunkId;
  }

  /** 一行一条记录：行内换行落成字面 {@code \n}，逐行去行尾空白、去首尾空行（只动显示形式，不增删文字）。 */
  private static String flattenText(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      lines.add(line.stripTrailing());
    }
    while (!lines.isEmpty() && lines.get(0).isBlank()) {
      lines.remove(0);
    }
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
    return String.join("\\n", lines);
  }

  private static String chunkOf(String rowId) {
    return rowId.substring(rowId.indexOf(':') + 1);
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String stringOf(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String nullableText(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }

  /** 标注表一行（CSV 里 11 列里由包决定的前 7 列）。 */
  record SheetRow(
      String rowId,
      int queryIndex,
      String queryText,
      String candidateText,
      String fileName,
      int pageNo,
      int currentRank) {}

  /** 读回的标注行（后 4 列，原样字符串以便逐条校验）。 */
  record LabelRow(
      String rowId,
      String queryIndex,
      String currentRank,
      String relevance,
      String isNearDuplicate,
      String annotator,
      String annotatedOn) {

    boolean isBlank() {
      return relevance.isBlank()
          && isNearDuplicate.isBlank()
          && annotator.isBlank()
          && annotatedOn.isBlank();
    }
  }

  /** 重建的一行图像路候选（r = 生产重排分）。 */
  record RebuiltRow(
      int queryIndex, String chunkId, double cos, String tier, String band, double r) {}

  /** 图像路重建结果。 */
  record Reconstruction(List<RebuiltRow> rows) {

    RebuiltRow find(int queryIndex, String chunkId) {
      return rows.stream()
          .filter(row -> row.queryIndex() == queryIndex && row.chunkId().equals(chunkId))
          .findFirst()
          .orElse(null);
    }
  }

  /** 一份完整包。 */
  record Bundle(
      List<SheetRow> rows,
      Map<String, Double> fusedA,
      byte[] csvBytes,
      byte[] readmeBytes,
      Reconstruction reconstruction,
      List<String> missingRowIds) {

    int missingRows() {
      return missingRowIds.size();
    }
  }

  /** 单行的情景得分与已施加权重。 */
  record RowScore(double score, double weight) {}

  /** 排序后的一行。 */
  record RankedRow(SheetRow row, double score, double weight) {}

  /** 一个情景的指标（未填标签时不构造）。 */
  record ScenarioScore(
      String key,
      String label,
      double precisionAt5,
      double ndcgAt5,
      Double ySuppressionRate,
      int ySuppressed,
      int yTotal,
      List<String> top5RowIds) {}

  /** 打分器结论。 */
  record LabelsOutcome(
      String status, String annotator, String annotatedOn, List<ScenarioScore> scenarios) {}

  /** 填表说明（生成物，一页；不得出现分数列与倾向措辞）。 */
  static final String README_TEXT =
      "# 近重复相关性盲标表 · v1（30 行）\n"
          + "\n"
          + "本表 6 条查询 × 每条现行前 5 个候选 = 30 行。请只依据「查询原文」与「候选文本」判断两件事，"
          + "大约 15 分钟。\n"
          + "\n"
          + "## 一、`relevance`：这条候选有多相关（只填 0 / 1 / 2）\n"
          + "\n"
          + "| 取值 | 含义 |\n"
          + "| --- | --- |\n"
          + "| `2` | 直接回答了这条查询 |\n"
          + "| `1` | 同主题，有参考价值但不直接回答 |\n"
          + "| `0` | 与这条查询无关 |\n"
          + "\n"
          + "## 二、`isNearDuplicate`：是不是同一张图的不同版本（只填 Y / N / U）\n"
          + "\n"
          + "判断对象是「同一查询里、名次比本行更高的候选」。\n"
          + "\n"
          + "| 取值 | 含义 |\n"
          + "| --- | --- |\n"
          + "| `Y` | 与更高名次的某张图是同一张图的不同版本 |\n"
          + "| `N` | 不是 |\n"
          + "| `U` | 看不出来（信息不足就填 `U`，这是合法答案，不算错） |\n"
          + "\n"
          + "## 三、另外两列\n"
          + "\n"
          + "- `annotator`：填你的署名。请不要写 agent / assistant / ai / model 之类字样。\n"
          + "- `annotatedOn`：填表日期，格式 `YYYY-MM-DD`。\n"
          + "\n"
          + "## 四、三条纪律\n"
          + "\n"
          + "1. **不要参考任何锚或分数**：本表故意不提供相似度、档位、机器名次提示。\n"
          + "2. **不要改结构**：不要改 `rowId`、不要调整行序、不要增删行。\n"
          + "3. **拿不准就填 `U`**：不要为了填满而猜。\n"
          + "\n"
          + "## 五、填写方式\n"
          + "\n"
          + "- 用 Excel / 文本编辑器直接打开 CSV；CSV 为 UTF-8（无 BOM），Excel 请用「数据 → 自文本」按 UTF-8 导入。\n"
          + "- 候选文本里的 `\\n` 是原文换行的显示形式（一行一条记录），不是内容错误。\n"
          + "- 只填 `relevance` / `isNearDuplicate` / `annotator` / `annotatedOn` 四列，其余列保持原样。\n"
          + "- 填完保存后，由执行者跑打分器读取本表。\n";
}
