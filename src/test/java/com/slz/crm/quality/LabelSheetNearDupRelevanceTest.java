package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 近重复人标最小包（label-sheet-near-dup-relevance）· 腿 A 生成器 + 腿 B 打分器（零外呼）。
 *
 * <p><b>池深（fix1，Owner 拍板）</b>：按生产语义把候选池从 top-5 扩到 <b>limit = 10</b>（{@code candidateLimit = 10 ×
 * 4}）。语料只有 6 条图像记录 ⇒ 每查询实测最多 6 条，<b>如实落 6 条、不凑数</b>；{@code currentRank} = 该查询现状情景下的 1..k 名次，行序 =
 * 该名次升序。
 *
 * <p><b>融合面本地重算（零外呼）</b>：文本路（冻结 {@code corpusMaterial.textRecords} 向量 + 冻结 {@code embedCache}
 * 内的查询文本向量 → {@link InMemoryVectorStore#search} → {@link DefaultWeightedReranker}）+ 图像路（冻结 {@code
 * queryImageVectors} → {@link ImageSimilarityGate#gate} → 重排）→ 生产 {@code fuseRoutes} 口径（文本 0.70 /
 * 图像 0.30，同一切片 {@code documentId:chunkId} 两路得分按权重相加）。30 条冻结候选的 {@code fusedA}（现状，含降权）与 {@code
 * fusedB}（无降权）逐条对账 1e-12，才是「池深 10 可测」的依据。
 *
 * <p><b>腿 B（打分器）</b>：读回 CSV；四个待填列全空 ⇒ 锚记 {@code AWAITING-HUMAN-LABELS} 且三情景字段为空；已填 ⇒
 * 逐行校验后按三情景并列打分，并检测三情景指标是否全等（{@code tieDetected}，{@code proposal §8} 打平停手条款）。
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

  /** 文本路融合权重（生产默认 0.70）。 */
  static final double TEXT_ROUTE_WEIGHT = 0.70d;

  /** 图像路融合权重（生产默认 0.30）。 */
  static final double IMAGE_ROUTE_WEIGHT = 0.30d;

  /** 文本路召回下限（生产 {@code rag.retrieval.min-score} 默认 0.20）。 */
  static final double TEXT_MIN_SCORE = 0.20d;

  /** 请求池深（Owner 拍板：top-5 → top-10）。 */
  static final int REQUESTED_DEPTH = 10;

  /** 候选倍数（生产默认 4 ⇒ candidateLimit = 请求池深 × 4）。 */
  static final int CANDIDATE_MULTIPLIER = 4;

  /** 对账容差（same-byte 复算口径）。 */
  static final double TOLERANCE = 1e-12d;

  /** 0.90 口径的准入下限。 */
  static final double FLOOR_090 = 0.90d;

  /** 指标窗口（precision@5 / NDCG@5）。 */
  private static final int METRIC_WINDOW = 5;

  private static final int QUERY_COUNT = 6;

  /** 冻结锚里每条查询的候选数（top-5）——只用于对账，不是本表行数上限。 */
  private static final int FROZEN_CANDIDATES_PER_QUERY = 5;

  private static final int FROZEN_ROW_COUNT = 30;

  /** 冻结锚内图像路候选行数（对账基准）。 */
  private static final int FROZEN_IMAGE_ROUTE_ROWS = 15;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** annotator 身份闸：只命中整词，避免把 `Bailey` 之类的正常署名误判成 agent。 */
  private static final Pattern AGENT_LIKE =
      Pattern.compile("(?i)(^|[^a-z])(agent|assistant|ai|model)([^a-z]|$)");

  @Test
  @DisplayName("腿 A+腿 B：池深 10 实测 36 行盲标表 + 三情景打分器（零外呼，标签未填时 AWAITING）")
  void generatesSheetAndScores() throws Exception {
    Bundle bundle = buildBundle();
    assertSheet(bundle);
    writeOrCompareBytes(CSV, bundle.csvBytes());
    writeOrCompareBytes(README, bundle.readmeBytes());
    List<List<String>> records = parseCsv(Files.readString(CSV, StandardCharsets.UTF_8));
    LabelsOutcome outcome = evaluate(bundle, records);
    ObjectNode anchor = buildAnchor(bundle, outcome);
    writeOrCompareJson(ANCHOR_JSON, anchor);
    writeOrCompareBytes(ANCHOR_MD, buildAnchorMdBytes(anchor));
    System.out.println(
        "[label-sheet] rows="
            + bundle.rows().size()
            + " actualPerQuery="
            + bundle.actualPerQuery()
            + " missingText="
            + bundle.missingRows()
            + " labelsStatus="
            + outcome.status()
            + " scenarios="
            + outcome.scenarios().size()
            + " tieDetected="
            + outcome.tieDetected());
    if (STATUS_AWAITING.equals(outcome.status())) {
      assertEquals(0, outcome.scenarios().size(), "未填标签时三情景必须为空（不得填假分数）");
      assertFalse(outcome.tieDetected(), "标签未填时无指标可比，不得声称打平");
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

    String firstRow = bundle.rows().get(0).rowId();
    assertRejected(bundle, tempDir, "blank-relevance", withField(valid, firstRow, 7, ""), firstRow);
    assertRejected(
        bundle, tempDir, "relevance-range", withField(valid, firstRow, 7, "3"), firstRow);
    assertRejected(bundle, tempDir, "near-dup-value", withField(valid, firstRow, 8, "X"), firstRow);
    assertRejected(
        bundle,
        tempDir,
        "agent-annotator",
        withField(valid, firstRow, 9, "ai-labeler"),
        "ai-labeler");
    assertRejected(bundle, tempDir, "blank-date", withField(valid, firstRow, 10, ""), firstRow);
    assertRejected(
        bundle, tempDir, "bad-date", withField(valid, firstRow, 10, "2026-13-40"), "2026-13-40");
    assertRejected(bundle, tempDir, "row-id", withField(valid, firstRow, 0, "q0:9999"), "q0:9999");
    assertRejected(bundle, tempDir, "duplicate-row", withDuplicate(valid, firstRow), firstRow);
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
          "本合成标签下每查询前 5 名里 3 条相关（名次 1–2 记 2、名次 3 记 1）⇒ precision@5 = 3/5");
      assertTrue(score.ndcgAt5() >= 0d && score.ndcgAt5() <= 1d, "NDCG@5 必须落在 [0,1]");
      assertTrue(
          score.ySuppressionRate() == null
              || (score.ySuppressionRate() >= 0d && score.ySuppressionRate() <= 1d),
          "一致率必须落在 [0,1] 或为空");
    }
    ScenarioScore noDownweight = scenarioOf(outcome, "no-downweight");
    assertEquals(QUERY_COUNT, noDownweight.yTotal(), "合成标签把每条查询的名次 1 记为 Y ⇒ 6 条 Y");
    assertEquals(0.0d, noDownweight.ySuppressionRate(), "δ=0 情景不压制任何候选");
    ScenarioScore current = scenarioOf(outcome, "current-0982");
    assertTrue(
        current.ySuppressed() > 0 && current.ySuppressed() < current.yTotal(),
        "现状情景下部分 Y 候选被压制（q0–q2 的名次 1 是带内图像，q3–q5 的是同图）");
    assertEquals(
        current.precisionAt5(),
        scenarioOf(outcome, "floor-090").precisionAt5(),
        "本语料三情景排序一致 ⇒ precision@5 必须相同（不做排序、不造差异）");
    assertEquals(
        current.precisionAt5(),
        noDownweight.precisionAt5(),
        "本语料三情景排序一致 ⇒ precision@5 必须相同（不做排序、不造差异）");
  }

  @Test
  @DisplayName("池深：请求 10、实测逐查询如实记条数（不足 10 不凑数），行 id 与名次升序")
  void poolDepthIsMeasuredNotPadded() throws Exception {
    Bundle bundle = buildBundle();
    List<Integer> perQuery = bundle.actualPerQuery();
    assertEquals(QUERY_COUNT, perQuery.size(), "逐查询条数必须 6 项");
    int total = 0;
    for (int count : perQuery) {
      assertTrue(
          count > 0 && count <= REQUESTED_DEPTH,
          "每查询实际条数必须在 1.." + REQUESTED_DEPTH + "（实测 " + count + "）");
      total += count;
    }
    assertEquals(total, bundle.rows().size(), "行数必须等于逐查询条数之和");
    assertEquals(REQUESTED_DEPTH, bundle.requestedDepth(), "请求池深必须入包（Owner 拍板 10）");
    for (int index = 0; index < QUERY_COUNT; index++) {
      int queryIndex = index;
      List<Integer> ranks =
          bundle.rows().stream()
              .filter(row -> row.queryIndex() == queryIndex)
              .map(SheetRow::currentRank)
              .toList();
      assertEquals(
          IntStream.rangeClosed(1, perQuery.get(index)).boxed().toList(),
          ranks,
          "行序必须是 currentRank 升序且 1..k 连续（q" + index + "）");
    }
  }

  // ==================== 腿 A · 材料与重建 ====================

  /** 生成一份完整包（CSV 字节 / README 字节 / 行 / 重建结果 / 冻结锚）。纯函数：不写盘。 */
  static Bundle buildBundle() throws Exception {
    ObjectNode bandEntry = (ObjectNode) readJson(BAND_ENTRY);
    assertInputs(bandEntry);
    Material material = material(bandEntry);
    Rebuild rebuild = rebuild(bandEntry, material);
    reconcile(bandEntry, rebuild);
    Map<String, JsonNode> imageByChunk = new LinkedHashMap<>();
    for (JsonNode node : bandEntry.path("corpusMaterial").path("imageRecords")) {
      imageByChunk.put(node.path("chunkId").asText(), node);
    }
    List<SheetRow> rows = new ArrayList<>();
    List<Integer> actualPerQuery = new ArrayList<>();
    List<String> missingIds = new ArrayList<>();
    for (JsonNode query : bandEntry.path("queries")) {
      int queryIndex = query.path("index").asInt();
      String queryText = query.path("text").asText();
      List<Candidate> ranked = rebuild.ranked(queryIndex, "current-0982");
      actualPerQuery.add(ranked.size());
      int currentRank = 1;
      for (Candidate candidate : ranked) {
        JsonNode record = imageByChunk.get(candidate.chunkId());
        assertNotNull(record, "候选切片必须在冻结 corpusMaterial.imageRecords 内: " + candidate.chunkId());
        JsonNode metadata = record.path("metadata");
        String candidateText = flattenText(record.path("text").asText(""));
        String fileName = metadata.path("filename").asText("");
        int pageNo = metadata.path("pageNo").asInt(0);
        assertFalse(fileName.isBlank(), "候选文件名缺失: " + candidate.chunkId());
        assertTrue(pageNo > 0, "候选页码缺失: " + candidate.chunkId());
        String rowId = rowId(queryIndex, candidate.chunkId());
        if (candidateText.isBlank()) {
          missingIds.add(rowId);
        }
        rows.add(
            new SheetRow(
                rowId, queryIndex, queryText, candidateText, fileName, pageNo, currentRank));
        currentRank++;
      }
    }
    assertEquals(
        actualPerQuery.stream().mapToInt(Integer::intValue).sum(),
        rows.size(),
        "标注包行数必须等于逐查询实测条数之和");
    assertEquals(
        rows.size(), new LinkedHashSet<>(rows.stream().map(SheetRow::rowId).toList()).size());
    assertTrue(
        missingIds.size() <= 3,
        "R7/D5：可读文本缺失 " + missingIds.size() + " 行（> 3）⇒ 停手上报，不降级成只标能标的: " + missingIds);
    byte[] csvBytes = renderCsv(rows).getBytes(StandardCharsets.UTF_8);
    byte[] readmeBytes =
        readmeText(rows.size(), actualPerQuery, caliberSentence()).getBytes(StandardCharsets.UTF_8);
    assertTrue(!new String(csvBytes, StandardCharsets.UTF_8).contains("\r"), "CSV 必须纯 LF");
    assertTrue(!new String(readmeBytes, StandardCharsets.UTF_8).contains("\r"), "README 必须纯 LF");
    return new Bundle(
        List.copyOf(rows),
        csvBytes,
        readmeBytes,
        rebuild,
        List.copyOf(missingIds),
        List.copyOf(actualPerQuery));
  }

  private static void assertInputs(ObjectNode bandEntry) {
    assertEquals(
        "near-dup-band-entry-and-tier-fill", bandEntry.path("card").asText(), "读错冻结锚（card 不符）");
    assertEquals(QUERY_COUNT, bandEntry.path("queries").size(), "冻结锚查询必须 6 条");
    assertEquals(FROZEN_ROW_COUNT, bandEntry.path("candidates").size(), "冻结锚候选必须 30 行（对账基准）");
    assertEquals(
        QUERY_COUNT, bandEntry.path("corpusMaterial").path("imageRecords").size(), "冻结材料缺图像记录");
    assertEquals(
        QUERY_COUNT,
        bandEntry.path("corpusMaterial").path("textRecords").size(),
        "冻结材料缺文本记录（文本路无法重建）");
    assertEquals(
        QUERY_COUNT,
        bandEntry.path("corpusMaterial").path("queryImageVectors").size(),
        "冻结材料缺查询图像向量");
    assertEquals(
        QUERY_COUNT,
        bandEntry.path("corpusMaterial").path("embedCache").size() - QUERY_COUNT,
        "冻结 embedCache 必须含 6 条查询文本向量（其余为切片文本向量）");
    assertEquals(
        FROZEN_CANDIDATES_PER_QUERY,
        bandEntry.path("constants").path("topK").asInt(),
        "冻结锚的生产 topK 必须是 5（本表按请求池深 10 重建）");
    assertEquals(
        12, bandEntry.path("windowCheck").path("entries").size(), "冻结锚必须仍记 12 条降权事件（对账基准）");
  }

  /** 冻结材料 + 生产类（文本路 / 图像路两个内存向量集合 + 生产重排器）。 */
  private static Material material(ObjectNode bandEntry) {
    JsonNode material = bandEntry.path("corpusMaterial");
    Map<String, NearDupIngestS1IT.StoredRecord> imageByChunk = new LinkedHashMap<>();
    List<NearDupIngestS1IT.StoredRecord> textRecords = new ArrayList<>();
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    InMemoryVectorStore imageStore = new InMemoryVectorStore();
    for (JsonNode node : material.path("imageRecords")) {
      NearDupIngestS1IT.StoredRecord record = NearDupIngestS1IT.StoredRecord.fromJson(node);
      imageByChunk.put(record.chunkId(), record);
      imageStore.upsert(record.toVectorRecord());
    }
    for (JsonNode node : material.path("textRecords")) {
      NearDupIngestS1IT.StoredRecord record = NearDupIngestS1IT.StoredRecord.fromJson(node);
      textRecords.add(record);
      textStore.upsert(record.toVectorRecord());
    }
    assertEquals(imageByChunk.size(), textRecords.size(), "文本记录与图像记录必须一一对应");
    Map<String, float[]> queryTextVectors = new LinkedHashMap<>();
    for (JsonNode node : material.path("embedCache")) {
      queryTextVectors.put(node.path("text").asText(), decodeVector(node.path("vector").asText()));
    }
    Map<Integer, float[]> queryImageVectors = new LinkedHashMap<>();
    for (JsonNode node : material.path("queryImageVectors")) {
      queryImageVectors.put(node.path("index").asInt(), decodeVector(node.path("vector").asText()));
    }
    Map<String, Object> metadata = imageByChunk.values().iterator().next().metadata();
    Map<String, Object> filter =
        Map.of(
            "knowledgeBaseId", String.valueOf(metadata.get("knowledgeBaseId")),
            "category", String.valueOf(metadata.get("category")));
    DefaultWeightedReranker reranker =
        new DefaultWeightedReranker(
            new Bm25Scorer(),
            RagBenchmarkPipelineFactory.provider(
                RagBenchmarkPipelineFactory.dynamicConfig(bandConfigMatrix())));
    return new Material(
        Map.copyOf(imageByChunk),
        textStore,
        imageStore,
        Map.copyOf(queryTextVectors),
        Map.copyOf(queryImageVectors),
        reranker,
        filter);
  }

  /** 按生产语义重建候选池：文本路 + 图像路 → 融合（文本 0.70 / 图像 0.30），三情景各一份融合分。 */
  private static Rebuild rebuild(ObjectNode bandEntry, Material material) {
    double threshold = ImageSimilarityGate.DEFAULT_THRESHOLD;
    int candidateLimit = REQUESTED_DEPTH * CANDIDATE_MULTIPLIER;
    int floor090GapRows = 0;
    Map<Integer, List<Candidate>> byQuery = new LinkedHashMap<>();
    for (JsonNode query : bandEntry.path("queries")) {
      int queryIndex = query.path("index").asInt();
      String queryText = query.path("text").asText();
      float[] textVector = material.queryTextVectors().get(queryText);
      assertNotNull(textVector, "冻结 embedCache 缺查询文本向量 ⇒ 文本路无法零外呼重建（不得退化成只算图像路）: q" + queryIndex);
      Map<String, Double> textScores = textRoute(material, textVector, queryText, candidateLimit);
      assertFalse(textScores.isEmpty(), "文本路召回为空（语料/过滤口径变了？）: q" + queryIndex);
      Map<String, ImageHit> imageHits =
          imageRoute(
              material,
              material.queryImageVectors().get(queryIndex),
              queryText,
              candidateLimit,
              ImageSimilarityGate.effectiveFloor(threshold),
              false);
      Map<String, ImageHit> floor090Hits =
          imageRoute(
              material,
              material.queryImageVectors().get(queryIndex),
              queryText,
              candidateLimit,
              FLOOR_090,
              true);
      List<Candidate> candidates = new ArrayList<>();
      for (Map.Entry<String, Double> entry : textScores.entrySet()) {
        String chunkId = entry.getKey();
        double rText = entry.getValue();
        double rImage = 0d;
        double weight = 1d;
        String tier = null;
        String band = null;
        Double cos = null;
        ImageHit hit = imageHits.get(chunkId);
        if (hit != null) {
          cos = hit.cosine();
          rImage = hit.rerankScore();
          tier = hit.tier();
          band = hit.band();
          weight = hit.weight();
        }
        double rImage090 = 0d;
        double weight090 = 1d;
        ImageHit hit090 = floor090Hits.get(chunkId);
        if (hit090 != null) {
          rImage090 = hit090.rerankScore();
          weight090 = hit090.weight();
          if (ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(hit090.tier())
              && hit090.band() == null) {
            floor090GapRows++;
          }
        }
        candidates.add(
            new Candidate(
                queryIndex,
                chunkId,
                rText,
                cos,
                rImage,
                tier,
                band,
                weight,
                rImage090,
                weight090,
                TEXT_ROUTE_WEIGHT * rText + IMAGE_ROUTE_WEIGHT * weight * rImage,
                TEXT_ROUTE_WEIGHT * rText + IMAGE_ROUTE_WEIGHT * rImage,
                TEXT_ROUTE_WEIGHT * rText + IMAGE_ROUTE_WEIGHT * weight090 * rImage090));
      }
      assertTrue(
          candidates.size() <= REQUESTED_DEPTH,
          "请求池深 " + REQUESTED_DEPTH + " 内实测 " + candidates.size() + " 条（q" + queryIndex + "）");
      byQuery.put(queryIndex, List.copyOf(candidates));
    }
    return new Rebuild(Map.copyOf(byQuery), candidateLimit, floor090GapRows);
  }

  /** 文本路：冻结文本向量召回 → 生产重排（与 {@code KnowledgeRetrievalServiceImpl} 六参构造同序）。 */
  private static Map<String, Double> textRoute(
      Material material, float[] queryVector, String queryText, int candidateLimit) {
    List<RetrievalCandidate> hits =
        material
            .textStore()
            .search(
                new VectorSearchRequest(
                    queryVector, candidateLimit, TEXT_MIN_SCORE, material.filter()))
            .stream()
            .filter(hit -> hit.text() != null && !hit.text().isBlank())
            .map(hit -> new RetrievalCandidate(hit, hit.score()))
            .toList();
    Map<String, Double> scores = new LinkedHashMap<>();
    for (RetrievalCandidate candidate : material.reranker().rerank(queryText, hits)) {
      scores.put(candidate.hit().chunkId(), candidate.rerankScore());
    }
    return scores;
  }

  /**
   * 图像路：冻结查询图像向量召回（下限 = 生产准入下限或 0.90）→ 分档 → 生产重排。 {@code floor090 = false} 用生产 {@link
   * ImageSimilarityGate#gate}；{@code true} 用 0.90 口径（阈值仍 0.9944，只降准入下限）。
   */
  private static Map<String, ImageHit> imageRoute(
      Material material,
      float[] queryVector,
      String queryText,
      int candidateLimit,
      double floor,
      boolean floor090) {
    double threshold = ImageSimilarityGate.DEFAULT_THRESHOLD;
    List<RetrievalCandidate> recalled =
        material
            .imageStore()
            .search(new VectorSearchRequest(queryVector, candidateLimit, floor, material.filter()))
            .stream()
            .filter(result -> result.text() != null && !result.text().isBlank())
            .map(result -> new RetrievalCandidate(result, result.score()))
            .toList();
    Map<String, ImageHit> hits = new LinkedHashMap<>();
    if (!floor090) {
      List<RetrievalCandidate> gated = ImageSimilarityGate.gate(recalled, threshold);
      for (RetrievalCandidate candidate : material.reranker().rerank(queryText, gated)) {
        String band =
            stringOf(candidate.hit().metadata().get(ImageSimilarityGate.BAND_METADATA_KEY));
        hits.put(
            candidate.hit().chunkId(),
            new ImageHit(
                candidate.hit().score(),
                stringOf(candidate.hit().metadata().get(ImageSimilarityGate.TIER_METADATA_KEY)),
                band,
                band == null ? 1d : ImageSimilarityGate.weightOfBand(band),
                candidate.rerankScore()));
      }
      return hits;
    }
    List<RetrievalCandidate> admitted = new ArrayList<>();
    Map<String, String[]> labels = new LinkedHashMap<>();
    for (RetrievalCandidate candidate : recalled) {
      double cosine = candidate.hit().score();
      String tier =
          cosine >= threshold
              ? ImageSimilarityGate.TIER_SAME_IMAGE
              : (cosine >= FLOOR_090
                  ? ImageSimilarityGate.TIER_NEAR_DUPLICATE
                  : ImageSimilarityGate.TIER_CROSS_IMAGE);
      if (ImageSimilarityGate.TIER_CROSS_IMAGE.equals(tier)) {
        continue;
      }
      boolean same = ImageSimilarityGate.TIER_SAME_IMAGE.equals(tier);
      // [0.90, 0.982) 区间生产 bandOf 未定义（内边界以 0.9887 为基准），沿用生产 weightOfBand(null) 回落最低档
      String band = same ? null : ImageSimilarityGate.bandOf(cosine, threshold);
      labels.put(candidate.hit().chunkId(), new String[] {tier, band});
      admitted.add(candidate);
    }
    for (RetrievalCandidate candidate : material.reranker().rerank(queryText, admitted)) {
      String[] label = labels.get(candidate.hit().chunkId());
      boolean same = ImageSimilarityGate.TIER_SAME_IMAGE.equals(label[0]);
      hits.put(
          candidate.hit().chunkId(),
          new ImageHit(
              candidate.hit().score(),
              label[0],
              label[1],
              same ? 1d : ImageSimilarityGate.weightOfBand(label[1]),
              candidate.rerankScore()));
    }
    return hits;
  }

  /**
   * 与冻结锚逐条对账（禁抽样）：{@code cos} / {@code tier} / {@code band} 必须一致；现状融合分必须等于 {@code fusedA}、
   * 无降权融合分必须等于 {@code fusedB}（容差 1e-12）；冻结 top-5 顺序必须等于现状重算顺序；12 条降权事件的位移必须等于 0.30 × (1 − w) × r。
   */
  private static void reconcile(ObjectNode bandEntry, Rebuild rebuild) {
    int reconciled = 0;
    int noDownweight = 0;
    int imageRows = 0;
    for (JsonNode frozenCandidate : bandEntry.path("candidates")) {
      int queryIndex = frozenCandidate.path("queryIndex").asInt();
      String chunkId = frozenCandidate.path("chunkId").asText().replace("IMAGE:", "");
      Candidate built = rebuild.find(queryIndex, chunkId);
      assertNotNull(built, "重算缺行（冻结候选必须落在重建池内）: q" + queryIndex + " " + chunkId);
      JsonNode cos = frozenCandidate.path("cos");
      if (cos.isNull() || cos.isMissingNode()) {
        assertNull(built.cos(), "锚内未走图像路的候选不得有图像余弦: q" + queryIndex + " " + chunkId);
        assertEquals(
            0d, built.rImage(), TOLERANCE, "锚内未走图像路的候选不得有图像分: q" + queryIndex + " " + chunkId);
      } else {
        imageRows++;
        assertEquals(
            cos.asDouble(), built.cos(), TOLERANCE, "cos 不符: q" + queryIndex + " " + chunkId);
        assertEquals(
            nullableText(frozenCandidate.path("tier")),
            built.tier(),
            "tier 不符: q" + queryIndex + " " + chunkId);
        assertEquals(
            nullableText(frozenCandidate.path("band")),
            built.band(),
            "band 不符: q" + queryIndex + " " + chunkId);
      }
      assertEquals(
          frozenCandidate.path("fusedA").asDouble(),
          built.fusedCurrent(),
          TOLERANCE,
          "现状融合分必须等于锚内 fusedA（0.70 × 文本路 + 0.30 × w × 图像路）: q" + queryIndex + " " + chunkId);
      reconciled++;
      assertEquals(
          frozenCandidate.path("fusedB").asDouble(),
          built.fusedNoDown(),
          TOLERANCE,
          "无降权融合分必须等于锚内 fusedB（0.70 × 文本路 + 0.30 × 图像路）: q" + queryIndex + " " + chunkId);
      noDownweight++;
      assertEquals(
          frozenCandidate.path("downWeighted").asBoolean(),
          built.weight() < 1d,
          "降权标记必须与重算权重一致: q" + queryIndex + " " + chunkId);
    }
    assertEquals(FROZEN_ROW_COUNT, reconciled, "冻结 30 条候选必须逐条对账");
    assertEquals(FROZEN_ROW_COUNT, noDownweight, "冻结 30 条候选的两种融合分都必须逐条对账");
    assertEquals(FROZEN_IMAGE_ROUTE_ROWS, imageRows, "冻结锚内图像路候选必须 15 行（对账基准）");
    for (JsonNode query : bandEntry.path("queries")) {
      int queryIndex = query.path("index").asInt();
      List<JsonNode> frozen = new ArrayList<>();
      for (JsonNode candidate : bandEntry.path("candidates")) {
        if (candidate.path("queryIndex").asInt() == queryIndex) {
          frozen.add(candidate);
        }
      }
      frozen.sort(Comparator.comparingInt(node -> node.path("rankB").asInt()));
      List<String> frozenOrder = new ArrayList<>();
      frozen.forEach(node -> frozenOrder.add(node.path("chunkId").asText().replace("IMAGE:", "")));
      List<String> rebuiltOrder =
          rebuild.ranked(queryIndex, "current-0982").stream()
              .map(Candidate::chunkId)
              .limit(FROZEN_CANDIDATES_PER_QUERY)
              .toList();
      assertEquals(frozenOrder, rebuiltOrder, "冻结 top-5 顺序必须等于现状重算顺序（禁打乱）: q" + queryIndex);
    }
    for (JsonNode entry : bandEntry.path("windowCheck").path("entries")) {
      int queryIndex = entry.path("queryIndex").asInt();
      String chunkId = entry.path("chunkId").asText().replace("IMAGE:", "");
      Candidate built = rebuild.find(queryIndex, chunkId);
      assertNotNull(built, "12 条降权事件必须能定位到重建候选: q" + queryIndex + " " + chunkId);
      assertEquals(
          entry.path("delta").asDouble(),
          IMAGE_ROUTE_WEIGHT * (1d - entry.path("weight").asDouble()) * built.rImage(),
          TOLERANCE,
          "windowCheck.delta 必须等于 0.30 × (1 − w) × r: q" + queryIndex + " " + chunkId);
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
      return new LabelsOutcome(STATUS_AWAITING, "", "", List.of(), false, "");
    }
    List<String> errors = validate(bundle.rows(), labels);
    if (!errors.isEmpty()) {
      throw new IllegalStateException("标签校验失败（R3），逐行错误：\n" + String.join("\n", errors));
    }
    String annotator = labels.get(0).annotator();
    String annotatedOn = labels.get(0).annotatedOn();
    List<ScenarioScore> scores = score(bundle, labels);
    return new LabelsOutcome(
        STATUS_COLLECTED, annotator, annotatedOn, scores, tieDetected(scores), tieNote(scores));
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
        errors.add("缺行（rowId=" + row.rowId() + "）：包内 " + sheet.size() + " 行必须逐行给出标签");
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

  /** 三情景并列打分：全池重排后取前 5（分母 = min(5, 池内条数)），另给 `Y` 候选被压制一致率。 */
  private static List<ScenarioScore> score(Bundle bundle, List<LabelRow> labels) {
    Map<String, LabelRow> byId = new LinkedHashMap<>();
    for (LabelRow label : labels) {
      byId.put(label.rowId(), label);
    }
    List<ScenarioScore> scores = new ArrayList<>();
    for (String key : SCENARIO_KEYS) {
      double precisionSum = 0d;
      double ndcgSum = 0d;
      int queryCount = 0;
      List<String> top = new ArrayList<>();
      for (int queryIndex = 0; queryIndex < QUERY_COUNT; queryIndex++) {
        List<Candidate> ranked = bundle.rebuild().ranked(queryIndex, key);
        int window = Math.min(METRIC_WINDOW, ranked.size());
        assertTrue(window > 0, "情景 " + key + " 下每查询必须有候选（q" + queryIndex + "）");
        List<Integer> relevance = new ArrayList<>();
        for (int index = 0; index < window; index++) {
          Candidate candidate = ranked.get(index);
          String rowId = rowId(queryIndex, candidate.chunkId());
          relevance.add(Integer.parseInt(byId.get(rowId).relevance()));
          top.add(rowId);
        }
        int relevant = 0;
        for (int value : relevance) {
          relevant += value >= 1 ? 1 : 0;
        }
        precisionSum += (double) relevant / window;
        ndcgSum += ndcg(relevance);
        queryCount++;
      }
      int yTotal = 0;
      int ySuppressed = 0;
      for (SheetRow row : bundle.rows()) {
        if (!"Y".equals(byId.get(row.rowId()).isNearDuplicate())) {
          continue;
        }
        yTotal++;
        Candidate candidate = bundle.rebuild().find(row.queryIndex(), chunkOf(row.rowId()));
        assertNotNull(candidate, "打分器找不到候选: " + row.rowId());
        if (candidate.weightFor(key) < 1d) {
          ySuppressed++;
        }
      }
      scores.add(
          new ScenarioScore(
              key,
              scenarioLabel(key),
              round(precisionSum / queryCount),
              round(ndcgSum / queryCount),
              yTotal == 0 ? null : round((double) ySuppressed / yTotal),
              ySuppressed,
              yTotal,
              List.copyOf(top)));
    }
    return List.copyOf(scores);
  }

  /** 三情景在 precision@5 / NDCG@5 / `Y` 压制一致率上全等 ⇒ 打平（{@code proposal §8} 停手条款）。 */
  private static boolean tieDetected(List<ScenarioScore> scores) {
    ScenarioScore first = scores.get(0);
    for (ScenarioScore score : scores) {
      if (Double.compare(first.precisionAt5(), score.precisionAt5()) != 0
          || Double.compare(first.ndcgAt5(), score.ndcgAt5()) != 0) {
        return false;
      }
      if (first.ySuppressionRate() == null || score.ySuppressionRate() == null) {
        if (first.ySuppressionRate() != score.ySuppressionRate()) {
          return false;
        }
      } else if (Double.compare(first.ySuppressionRate(), score.ySuppressionRate()) != 0) {
        return false;
      }
    }
    return true;
  }

  private static String tieNote(List<ScenarioScore> scores) {
    if (!tieDetected(scores)) {
      return "三情景不全等（至少一项指标有差异）";
    }
    return "三情景在 precision@5 / NDCG@5 / Y 压制一致率上全等 ⇒ 按 proposal §8 停手上报，不得改口径造差异；"
        + "原因见锚内 measurableScope.orderInvariantBasis";
  }

  private static ScenarioScore scenarioOf(LabelsOutcome outcome, String key) {
    return outcome.scenarios().stream()
        .filter(score -> score.key().equals(key))
        .findFirst()
        .orElseThrow();
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
        return "tier/band 由生产 ImageSimilarityGate 现算，融合分 = 0.70 × 文本路 + 0.30 × bandWeight × 图像路";
      }
      case "no-downweight" -> {
        return "不施加任何 band 降权，融合分 = 0.70 × 文本路 + 0.30 × 图像路（δ = 0）";
      }
      case "floor-090" -> {
        return "准入下限降到 0.90（阈值仍 0.9944），按同批候选重算 tier/band/weight 与融合分；[0.90, 0.982) 区间生产 bandOf 未定义，沿用生产 weightOfBand(null) 回落最低档 0.5（实测行数见 reconstructionCheck.floor090UndefinedBandRows）";
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
    assertTrue(!bundle.rows().isEmpty(), "标注包不得为空");
    assertEquals(
        bundle.actualPerQuery().stream().mapToInt(Integer::intValue).sum(),
        bundle.rows().size(),
        "行数必须等于逐查询实测条数之和");
    Set<String> ids = new LinkedHashSet<>();
    for (SheetRow row : bundle.rows()) {
      assertTrue(
          row.rowId().matches("q[0-5]:\\d+"), "rowId 必须 q{queryIndex}:{chunkId}: " + row.rowId());
      assertTrue(ids.add(row.rowId()), "rowId 必须唯一: " + row.rowId());
      assertFalse(row.candidateText().isBlank(), "候选文本为空白行: " + row.rowId());
      assertFalse(row.queryText().isBlank(), "查询文本为空: " + row.rowId());
      assertTrue(
          row.currentRank() >= 1 && row.currentRank() <= REQUESTED_DEPTH, "名次越界: " + row.rowId());
    }
    String csv = new String(bundle.csvBytes(), StandardCharsets.UTF_8);
    for (String forbidden : List.of("近重复", "可疑", "压制")) {
      assertFalse(csv.contains(forbidden), "盲标 CSV 不得出现提示字样「" + forbidden + "」");
    }
    assertEquals(bundle.rows().size() + 1, parseCsv(csv).size(), "CSV 必须 1 行表头 + 每行数据");
    String readme = new String(bundle.readmeBytes(), StandardCharsets.UTF_8);
    assertTrue(readme.contains(caliberSentence()), "README 必须出现与 md 同一句口径");
    assertTrue(readme.contains("看不出来"), "README 必须写「看不出来填 U」");
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
    sheet.put("bytes", bundle.csvBytes().length);
    sheet.put("columnCount", COLUMNS.size());
    ArrayNode columns = sheet.putArray("columns");
    COLUMNS.forEach(columns::add);
    ArrayNode fillable = sheet.putArray("fillableColumns");
    fillable.add("relevance");
    fillable.add("isNearDuplicate");
    fillable.add("annotator");
    fillable.add("annotatedOn");
    sheet.put("rowOrder", "queryIndex ASC, currentRank ASC（currentRank = 现状情景全池名次，不打乱、不按 cos 排）");
    sheet.put("sha256", RealPdfAssets.sha256(bundle.csvBytes()));
    sheet.put("readmeSha256", RealPdfAssets.sha256(bundle.readmeBytes()));
    sheet.put("annotator", outcome.annotator());
    sheet.put("annotatedOn", outcome.annotatedOn());
    sheet.put("queryCount", QUERY_COUNT);
    ObjectNode material = root.putObject("sheetMaterial");
    material.put("queries", "docs/rag-quality/near-dup-band-entry-v1.json#queries[].text（冻结）");
    material.put(
        "candidateIdentityAndOrder",
        "本地上生产链重建（文本路 + 图像路 → 融合）后取请求池深 10；与冻结锚 #candidates 的 30 行逐条对账");
    material.put(
        "candidateText",
        "docs/rag-quality/near-dup-band-entry-v1.json#corpusMaterial.imageRecords[].text（冻结原文；仅把行内换行落成字面 \\n、去行尾空白，便于一行一条记录）");
    material.put("textSuppliedRowCount", bundle.rows().size() - bundle.missingRows());
    root.set("poolDepth", poolDepth(bundle));
    root.set("metricCaliber", metricCaliber(bundle));
    root.set("measurableScope", measurableScope(bundle, outcome));
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
    root.put("tieDetected", outcome.tieDetected());
    root.put("tieNote", outcome.tieNote());
    ObjectNode coverage = root.putObject("textCoverage");
    coverage.put("rowCount", bundle.rows().size());
    coverage.put("rowsWithText", bundle.rows().size() - bundle.missingRows());
    coverage.put("missingRows", bundle.missingRows());
    ArrayNode missing = coverage.putArray("missingRowIds");
    bundle.missingRowIds().forEach(missing::add);
    ObjectNode reconciliation = root.putObject("reconstructionCheck");
    reconciliation.put("frozenCandidates", FROZEN_ROW_COUNT);
    reconciliation.put("imageRouteRowsRebuilt", FROZEN_IMAGE_ROUTE_ROWS);
    reconciliation.put("textRouteRowsRebuilt", bundle.rows().size());
    reconciliation.put("fusedCurrentReconciled", FROZEN_ROW_COUNT);
    reconciliation.put("fusedNoDownweightReconciled", FROZEN_ROW_COUNT);
    reconciliation.put("orderingMatchesRankB", true);
    reconciliation.put("floor090UndefinedBandRows", bundle.rebuild().floor090UndefinedBandRows());
    reconciliation.put("tolerance", TOLERANCE);
    reconciliation.put(
        "method",
        "生产口径本地重建：冻结 textRecords 向量 + 冻结 embedCache 查询文本向量 → InMemoryVectorStore.search → DefaultWeightedReranker（图像路先 ImageSimilarityGate.gate）→ 融合（文本 0.70 / 图像 0.30），逐条比对锚内 cos / tier / band / fusedA / fusedB");
    ObjectNode residual = root.putObject("residual");
    residual.put("queryCount", QUERY_COUNT);
    residual.put("rowCount", bundle.rows().size());
    residual.put(
        "note",
        "本锚只对本次 " + QUERY_COUNT + " 查询 × " + bundle.rows().size() + " 行成立；不外推到其它语料、其它查询或线上体验。");
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

  /** 池深（F1）：请求深度、逐查询实测条数、合计行数，以及「为什么不足 10」。 */
  private static ObjectNode poolDepth(Bundle bundle) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("requestedDepth", REQUESTED_DEPTH);
    ArrayNode perQuery = node.putArray("actualPerQuery");
    bundle.actualPerQuery().forEach(perQuery::add);
    node.put("totalRows", bundle.rows().size());
    node.put("candidateLimit", REQUESTED_DEPTH * CANDIDATE_MULTIPLIER);
    node.put(
        "ceilingReason",
        "语料只有 6 条图像记录（2 base × 3 变体）⇒ 单查询两路去重后最多 6 条；实测 "
            + bundle.actualPerQuery()
            + " 为真实上限，不足 10 如实记，不凑数");
    node.put("rowIdRule", "q{queryIndex}:{chunkId}");
    node.put("rowOrder", "currentRank ASC（现状情景全池名次 1..k）");
    return node;
  }

  /** 指标口径（F3/板规 29）：逐项可 grep，同一句口径在 md 与 README 各出现一次。 */
  private static ArrayNode metricCaliber(Bundle bundle) {
    ArrayNode caliber = MAPPER.createArrayNode();
    caliber.add(
        caliberItem(
            "pool",
            "候选池 = 文本路 ∪ 图像路（fusionKey 去重）按请求池深 "
                + REQUESTED_DEPTH
                + " 截断；requestedDepth="
                + REQUESTED_DEPTH
                + "、actualPerQuery="
                + bundle.actualPerQuery()
                + "、totalRows="
                + bundle.rows().size()));
    caliber.add(
        caliberItem(
            "precision@5",
            "每情景先按该情景融合分对全池重排，再取前 5；命中 = relevance ≥ 1；分母 = min(5, 池内条数)（本表每查询池内 6 条 ⇒ 分母 5）"));
    caliber.add(
        caliberItem(
            "ndcg@5",
            "增益 = 2^relevance − 1；IDCG = 同集合 relevance 降序后的 DCG；折损 = log2(rank+2)，rank 从 1 起"));
    caliber.add(
        caliberItem(
            "ySuppressionAgreement",
            "分子 = isNearDuplicate=Y 且该情景下 bandWeight < 1（被压制）的候选数；分母 = isNearDuplicate=Y 的候选总数（全池，不只看前 5）"));
    caliber.add(caliberItem("rounding", "沿用 numericHygiene.metricRounding = round(1e-6)"));
    return caliber;
  }

  private static ObjectNode caliberItem(String metric, String caliber) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("metric", metric);
    node.put("caliber", caliber);
    return node;
  }

  /** 可测范围声明（F4）：能答什么、不能答什么，以及本语料实测的位移不变量。 */
  private static ObjectNode measurableScope(Bundle bundle, LabelsOutcome outcome) {
    ObjectNode scope = MAPPER.createObjectNode();
    ObjectNode canAnswer = scope.putObject("canAnswer");
    canAnswer.put("duplicatePrevalence", "top-5 里 isNearDuplicate=Y 的候选占比（逐查询 + 合计）；标签未填时为空，不预填");
    canAnswer.put(
        "ySuppressionAgreement", "isNearDuplicate=Y 的候选里被该情景压制过的比例（分子/分母见 metricCaliber）");
    canAnswer.put(
        "nonDuplicatePromotion",
        "降权是否让非重复候选升进 top-5：池深 " + REQUESTED_DEPTH + " 后此项才可观测（本次实测见 orderInvariant）");
    ArrayNode cannot = scope.putArray("cannotAnswer");
    cannot.add("语料仍是程序化 fixture（NearDuplicateCorpus 合成 PDF），不是生产库文档");
    cannot.add("单人标注，无一致性检验（不做多人 κ、不抽样复核）");
    cannot.add(
        "结论只对这 " + QUERY_COUNT + " 查询 × 实测 " + bundle.rows().size() + " 行成立，不外推到别的语料/查询/线上体验");
    boolean orderInvariant = orderInvariant(bundle);
    boolean downweightInvariant = sameOrder(bundle, "current-0982", "no-downweight");
    boolean floor090Invariant = sameOrder(bundle, "current-0982", "floor-090");
    scope.put("orderInvariant", orderInvariant);
    scope.put("invariantObserved", orderInvariant);
    scope.put("downweightOrderInvariant", downweightInvariant);
    scope.put("floor090OrderInvariant", floor090Invariant);
    scope.put(
        "orderInvariantBasis",
        orderInvariant
            ? "实测三情景逐查询排序逐行相同（池深 "
                + REQUESTED_DEPTH
                + " 实测每查询 "
                + bundle.actualPerQuery()
                + " 条仍无跨边界位移）：降权位移（最大 "
                + maxDownweightShift(bundle)
                + "）未跨越任何相邻候选间距 ⇒ 主效应在本语料上仍不可观测，如实记不变量，未为了出差异改口径"
            : "现状 vs 无降权"
                + (downweightInvariant ? "排序逐行相同" : "排序存在差异")
                + "（降权位移最大 "
                + maxDownweightShift(bundle)
                + "）；现状 vs 0.90 口径"
                + (floor090Invariant ? "排序逐行相同" : "排序存在差异")
                + "（0.90 额外准入 "
                + bundle.rebuild().floor090UndefinedBandRows()
                + " 行，落在 [0.90, 0.982) 未定档区间，沿用生产 weightOfBand(null) = 0.5）"
                + "⇒ 三情景并非全等，其中降权本身"
                + (downweightInvariant ? "仍无跨边界位移" : "已产生跨边界位移"));
    scope.put("promotionInTop5", !downweightInvariant);
    scope.put("labelsPending", STATUS_AWAITING.equals(outcome.status()));
    return scope;
  }

  /** 两个情景的逐查询排序是否完全相同（与标签无关，纯分数面）。 */
  private static boolean sameOrder(Bundle bundle, String left, String right) {
    for (int queryIndex = 0; queryIndex < QUERY_COUNT; queryIndex++) {
      List<String> leftOrder =
          bundle.rebuild().ranked(queryIndex, left).stream().map(Candidate::chunkId).toList();
      List<String> rightOrder =
          bundle.rebuild().ranked(queryIndex, right).stream().map(Candidate::chunkId).toList();
      if (!leftOrder.equals(rightOrder)) {
        return false;
      }
    }
    return true;
  }

  /** 三情景逐查询排序是否完全相同（与标签无关，纯分数面）。 */
  private static boolean orderInvariant(Bundle bundle) {
    for (int queryIndex = 0; queryIndex < QUERY_COUNT; queryIndex++) {
      List<String> base = null;
      for (String key : SCENARIO_KEYS) {
        List<String> order =
            bundle.rebuild().ranked(queryIndex, key).stream().map(Candidate::chunkId).toList();
        if (base == null) {
          base = order;
        } else if (!base.equals(order)) {
          return false;
        }
      }
    }
    return true;
  }

  /** 实测最大降权位移（|现状 − 无降权|，全池）。 */
  private static double maxDownweightShift(Bundle bundle) {
    double max = 0d;
    for (List<Candidate> candidates : bundle.rebuild().byQuery().values()) {
      for (Candidate candidate : candidates) {
        max = Math.max(max, Math.abs(candidate.fusedNoDown() - candidate.fusedCurrent()));
      }
    }
    return round(max);
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
    JsonNode poolDepth = anchor.path("poolDepth");
    JsonNode scope = anchor.path("measurableScope");
    boolean awaiting = STATUS_AWAITING.equals(anchor.path("labelsStatus").asText());
    StringBuilder out = new StringBuilder();
    out.append("# 近重复人标最小包 · v1\n\n");
    out.append("- 卡：`")
        .append(anchor.path("card").asText())
        .append("` · 状态：`")
        .append(anchor.path("labelsStatus").asText())
        .append("`\n");
    out.append("- 标注表：`")
        .append(sheet.path("csv").asText())
        .append("`（")
        .append(sheet.path("rowCount").asInt())
        .append(" 行 × ")
        .append(sheet.path("columnCount").asInt())
        .append(" 列，")
        .append(sheet.path("bytes").asInt())
        .append(" 字节，末 4 列待人填）\n");
    out.append("- 填表说明：`")
        .append(sheet.path("readme").asText())
        .append("` · CSV sha256：`")
        .append(sheet.path("sha256").asText())
        .append("`\n");
    out.append("- 来源锚：`")
        .append(anchor.path("sourceAnchors").path("bandEntry").path("path").asText())
        .append("`（只读，sha256 `")
        .append(anchor.path("sourceAnchors").path("bandEntry").path("sha256").asText())
        .append("`）\n");
    out.append("- 池深：请求 ")
        .append(poolDepth.path("requestedDepth").asInt())
        .append("，实测逐查询 ")
        .append(poolDepth.path("actualPerQuery").toString())
        .append("，合计 ")
        .append(poolDepth.path("totalRows").asInt())
        .append(" 行（语料 6 条图像记录 ⇒ 每查询上限 6，不凑数）\n\n");
    out.append("## 材料\n\n");
    out.append(
            "- 行 id `q{queryIndex}:{chunkId}`，行序 = 现状情景全池名次升序（1..k）；候选文本取自冻结 `corpusMaterial.imageRecords`，缺失 ")
        .append(coverage.path("missingRows").asInt())
        .append(" 行。\n");
    out.append("- 融合面本地重算逐条对账：现状 ")
        .append(reconciliation.path("fusedCurrentReconciled").asInt())
        .append("/")
        .append(reconciliation.path("frozenCandidates").asInt())
        .append("、无降权 ")
        .append(reconciliation.path("fusedNoDownweightReconciled").asInt())
        .append("/")
        .append(reconciliation.path("frozenCandidates").asInt())
        .append("（图像路 ")
        .append(reconciliation.path("imageRouteRowsRebuilt").asInt())
        .append(" 行；0.90 口径未定档 ")
        .append(reconciliation.path("floor090UndefinedBandRows").asInt())
        .append(" 行）。\n\n");
    out.append("## 三情景（并列，不排序）\n\n");
    out.append("- 现状：生产准入下限 0.982 + 三档 band 降权。\n");
    out.append("- 无降权：δ = 0，不施加 band 降权。\n");
    out.append("- 0.90 口径：准入下限降到 0.90，按同批候选重算 tier/band/weight。\n\n");
    out.append("## 口径\n\n");
    out.append("- ").append(caliberSentence()).append("\n\n");
    out.append("## 当前产出\n\n");
    if (awaiting) {
      out.append("- 标签未填：三情景指标一律留空，不写任何占位分数。\n\n");
    } else {
      out.append("- 三情景：");
      for (JsonNode scenario : anchor.path("scenarios")) {
        out.append("`")
            .append(scenario.path("key").asText())
            .append("` p@5=")
            .append(scenario.path("precisionAt5").asText())
            .append("、n@5=")
            .append(scenario.path("ndcgAt5").asText())
            .append("、y一致率=")
            .append(scenario.path("ySuppressionRate").asText())
            .append(" ｜ ");
      }
      out.setLength(out.length() - 3);
      out.append("\n");
      out.append("- 打平检测：`tieDetected`=")
          .append(anchor.path("tieDetected").asBoolean())
          .append("（")
          .append(anchor.path("tieNote").asText())
          .append("）\n\n");
    }
    out.append("## 可测范围\n\n");
    out.append("- 能答：top-5 重复占比、Y 压制一致率、降权是否让非重复候选升进 top-5（池深后此项才可观测）。\n");
    out.append("- 不能答：生产库文档；单人标注无一致性检验；结论只对 ")
        .append(anchor.path("residual").path("queryCount").asInt())
        .append(" 查询 × ")
        .append(anchor.path("residual").path("rowCount").asInt())
        .append(" 行成立。\n");
    out.append("- 三情景排序实测")
        .append(scope.path("orderInvariant").asBoolean() ? "逐行相同" : "并非全相同")
        .append(" ⇒ `invariantObserved`=")
        .append(scope.path("invariantObserved").asBoolean())
        .append("；其中降权面（现状 vs 无降权）")
        .append(scope.path("downweightOrderInvariant").asBoolean() ? "无跨边界位移" : "有跨边界位移")
        .append("。\n\n");
    out.append("## 边界与复认证\n\n");
    out.append("- 零外呼：`externalCalls = 0`；不启 Spring、不调 provider。\n");
    out.append(
        "- 写模式：`mvn -B -ntp test -Dtest=LabelSheetNearDupRelevanceTest \"-Dlabel.sheet.write=true\"`；默认复跑逐字节一致（JSON 剔除顶层 runtime）。\n");
    return out.toString();
  }

  static byte[] buildAnchorMdBytes(ObjectNode anchor) {
    return buildAnchorMd(anchor).getBytes(StandardCharsets.UTF_8);
  }

  /** md 与 README 共用同一句口径（F3：同一句各出现一次）。 */
  static String caliberSentence() {
    return "口径：候选全池按各情景融合分重排后取前 5（分母 = min(5, 池内条数)），"
        + "NDCG@5 增益 2^relevance − 1、IDCG 取同集合 relevance 降序、折损 log2(rank+2)，"
        + "Y 压制一致率 = 被压制 Y 候选数 / Y 候选总数，舍入 round(1e-6)。";
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
        "落盘锚与重算不一致（可能被手改）: " + path);
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

  /** 一条候选的三情景融合分与图像路信息（{@code cos} = 图像路余弦，未走图像路时 null）。 */
  record Candidate(
      int queryIndex,
      String chunkId,
      double rText,
      Double cos,
      double rImage,
      String tier,
      String band,
      double weight,
      double rImage090,
      double weight090,
      double fusedCurrent,
      double fusedNoDown,
      double fusedFloor090) {

    double fused(String key) {
      switch (key) {
        case "current-0982" -> {
          return fusedCurrent;
        }
        case "no-downweight" -> {
          return fusedNoDown;
        }
        case "floor-090" -> {
          return fusedFloor090;
        }
        default -> throw new IllegalArgumentException("未知情景: " + key);
      }
    }

    double weightFor(String key) {
      switch (key) {
        case "current-0982" -> {
          return weight;
        }
        case "no-downweight" -> {
          return 1d;
        }
        case "floor-090" -> {
          return weight090;
        }
        default -> throw new IllegalArgumentException("未知情景: " + key);
      }
    }
  }

  /** 图像路命中（cos / tier / band / 已施权重 / 重排分）。 */
  record ImageHit(double cosine, String tier, String band, double weight, double rerankScore) {}

  /** 冻结材料与生产类装配。 */
  record Material(
      Map<String, NearDupIngestS1IT.StoredRecord> imageByChunk,
      InMemoryVectorStore textStore,
      InMemoryVectorStore imageStore,
      Map<String, float[]> queryTextVectors,
      Map<Integer, float[]> queryImageVectors,
      DefaultWeightedReranker reranker,
      Map<String, Object> filter) {}

  /** 重建结果（逐查询候选池 + 候选召回上限 + 0.90 口径未定档行数）。 */
  record Rebuild(
      Map<Integer, List<Candidate>> byQuery, int candidateLimit, int floor090UndefinedBandRows) {

    List<Candidate> ranked(int queryIndex, String scenarioKey) {
      List<Candidate> candidates = byQuery.get(queryIndex);
      assertNotNull(candidates, "重建缺查询: q" + queryIndex);
      List<Candidate> sorted = new ArrayList<>(candidates);
      sorted.sort(
          Comparator.comparingDouble((Candidate value) -> -value.fused(scenarioKey))
              .thenComparing(Candidate::chunkId));
      return List.copyOf(sorted);
    }

    Candidate find(int queryIndex, String chunkId) {
      List<Candidate> candidates = byQuery.get(queryIndex);
      if (candidates == null) {
        return null;
      }
      return candidates.stream()
          .filter(candidate -> candidate.chunkId().equals(chunkId))
          .findFirst()
          .orElse(null);
    }
  }

  /** 一份完整包。 */
  record Bundle(
      List<SheetRow> rows,
      byte[] csvBytes,
      byte[] readmeBytes,
      Rebuild rebuild,
      List<String> missingRowIds,
      List<Integer> actualPerQuery) {

    int missingRows() {
      return missingRowIds.size();
    }

    int requestedDepth() {
      return REQUESTED_DEPTH;
    }
  }

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
      String status,
      String annotator,
      String annotatedOn,
      List<ScenarioScore> scenarios,
      boolean tieDetected,
      String tieNote) {}

  /** 填表说明（生成物；不得出现分数列与倾向措辞）。 */
  static String readmeText(int rowCount, List<Integer> actualPerQuery, String caliber) {
    return "# 近重复相关性盲标表 · v1（"
        + rowCount
        + " 行）\n"
        + "\n"
        + "本表 "
        + QUERY_COUNT
        + " 条查询 × 每条查询的全部候选（逐查询实测 "
        + actualPerQuery
        + " 条，合计 "
        + rowCount
        + " 行）。请只依据「查询原文」与「候选文本」判断两件事，大约 15 分钟。\n"
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
        + "## 四、评的是什么口径\n"
        + "\n"
        + "- "
        + caliber
        + "\n"
        + "\n"
        + "## 五、三条纪律\n"
        + "\n"
        + "1. **不要参考任何锚或分数**：本表故意不提供相似度、档位、机器名次提示。\n"
        + "2. **不要改结构**：不要改 `rowId`、不要调整行序、不要增删行。\n"
        + "3. **拿不准就填 `U`**：不要为了填满而猜。\n"
        + "\n"
        + "## 六、填写方式\n"
        + "\n"
        + "- 用 Excel / 文本编辑器直接打开 CSV；CSV 为 UTF-8（无 BOM）、纯 LF，Excel 请用「数据 → 自文本」按 UTF-8 导入。\n"
        + "- 候选文本里的 `\\n` 是原文换行的显示形式（一行一条记录），不是内容错误。\n"
        + "- 只填 `relevance` / `isNearDuplicate` / `annotator` / `annotatedOn` 四列，其余列保持原样。\n"
        + "- 填完保存后，由执行者跑打分器读取本表。\n";
  }
}
