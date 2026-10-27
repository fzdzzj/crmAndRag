package com.slz.crm.quality;

import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.ANCHOR_JSON;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.ANCHOR_MD;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.BAND_ENTRY;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.COLUMNS;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.CSV;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.LABELS_DIR;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.README;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.STATUS_AWAITING;
import static com.slz.crm.quality.LabelSheetNearDupRelevanceTest.STATUS_COLLECTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守卫（label-sheet-near-dup-relevance · 腿 C）：只读落盘产物 + 复算，不写除生成物以外的任何路径。
 *
 * <p>断言面：R1 逐字节确定性、R2 盲标列名白名单、R3 行 id / 行数 / 代填禁令、R4 无倾向措辞、R5 annotator 非 agent、R6 纯 LF + md ≤40
 * 行、R7 文本缺失上限、R8 外推边界；fix1 追加：落盘锚 == 本次重算的规范化字节（剔除顶层 runtime）+ md 字节对账（防手改）、 池深 / 指标口径 / 可测范围三块可
 * grep、打平检测一旦成立即红。
 */
class LabelSheetNearDupRelevanceGuardTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** R4 倾向词表：三情景并列呈现，不得出现取舍建议。 */
  private static final List<String> TENDENCY_WORDS =
      List.of("建议", "推荐", "应下调", "应当下调", "倾向", "更优", "更好", "更差", "应该");

  private static final Pattern AGENT_LIKE =
      Pattern.compile("(?i)(^|[^a-z])(agent|assistant|ai|model)([^a-z]|$)");

  @Test
  @DisplayName("守卫：盲标白名单 / 逐字节确定性 / 落盘锚==重算 / 池深与口径入锚 / 打平即红 / 无倾向措辞")
  void guardsSheetAndAnchor() throws Exception {
    LabelSheetNearDupRelevanceTest.Bundle first = LabelSheetNearDupRelevanceTest.buildBundle();
    LabelSheetNearDupRelevanceTest.Bundle second = LabelSheetNearDupRelevanceTest.buildBundle();
    assertArrayEquals(first.csvBytes(), second.csvBytes(), "R1：CSV 两次生成必须逐字节一致");
    assertArrayEquals(first.readmeBytes(), second.readmeBytes(), "R1：README 两次生成必须逐字节一致");
    assertEquals(first.rows(), second.rows(), "R1：行集合两次生成必须一致");

    byte[] csvBytes = Files.readAllBytes(CSV);
    byte[] readmeBytes = Files.readAllBytes(README);
    byte[] anchorBytes = Files.readAllBytes(ANCHOR_JSON);
    byte[] mdBytes = Files.readAllBytes(ANCHOR_MD);
    assertArrayEquals(
        csvBytes,
        LabelSheetNearDupRelevanceTest.ensureSheet(CSV, first.csvBytes()),
        "落盘 CSV 必须与本次生成同构（模板列逐格一致；标注列允许人工填写）");
    assertArrayEquals(first.readmeBytes(), readmeBytes, "落盘 README 必须等于本次生成");
    assertLf(csvBytes, "CSV");
    assertLf(readmeBytes, "README");
    assertLf(anchorBytes, "锚 JSON");
    assertLf(mdBytes, "锚 md");

    assertBlindSheet(csvBytes, first.csvBytes());

    JsonNode anchor = MAPPER.readTree(anchorBytes);
    assertEquals("label-sheet-near-dup-relevance", anchor.path("card").asText(), "锚 card 不符");
    assertEquals(first.rows().size(), anchor.path("sheet").path("rowCount").asInt(), "锚行数必须等于本次生成");
    assertEquals(csvBytes.length, anchor.path("sheet").path("bytes").asInt(), "锚内字节数必须等于落盘字节");
    assertEquals(
        RealPdfAssets.sha256(csvBytes),
        anchor.path("sheet").path("sha256").asText(),
        "锚内 CSV sha256 必须等于落盘字节");
    assertEquals(
        RealPdfAssets.sha256(readmeBytes),
        anchor.path("sheet").path("readmeSha256").asText(),
        "锚内 README sha256 必须等于落盘字节");
    assertEquals(
        RealPdfAssets.sha256(Files.readAllBytes(BAND_ENTRY)),
        anchor.path("sourceAnchors").path("bandEntry").path("sha256").asText(),
        "来源锚 sha256 必须与磁盘一致（既有锚零字节变动）");
    assertEquals(0, anchor.path("runtime").path("externalCalls").asInt(), "零外呼：externalCalls 必须 0");
    assertFalse(anchor.path("runtime").path("springContextStarted").asBoolean(), "不得启动 Spring");
    assertEquals(
        first.rows().size(),
        anchor.path("textCoverage").path("rowsWithText").asInt(),
        "每行必须都有可读文本");
    assertTrue(anchor.path("textCoverage").path("missingRows").asInt() <= 3, "R7：缺失 > 3 行必须停手");
    assertTrue(
        anchor.path("residual").path("note").asText().contains("6 查询"),
        "R8：residual 必须写明只对 6 查询成立");
    assertTrue(
        anchor.path("residual").path("note").asText().contains("36 行"),
        "R8：residual 必须写明只对 36 行成立");
    assertTrue(
        anchor.path("numericHygiene").path("noPlaceholderScores").asBoolean(),
        "numericHygiene 必须声明无占位分数");
    assertEquals(3, anchor.path("scenarioDefinitions").size(), "三情景定义必须都在（与是否填标签无关）");
    JsonNode reconciliation = anchor.path("reconstructionCheck");
    assertEquals(
        reconciliation.path("frozenCandidates").asInt(),
        reconciliation.path("fusedCurrentReconciled").asInt(),
        "现状融合分必须全部对账通过");
    assertEquals(
        reconciliation.path("frozenCandidates").asInt(),
        reconciliation.path("fusedNoDownweightReconciled").asInt(),
        "无降权融合分必须全部对账通过");

    assertPoolDepth(anchor, first);
    assertMetricCaliber(anchor, csvBytes, readmeBytes, mdBytes);
    assertMeasurableScope(anchor, first);

    String status = anchor.path("labelsStatus").asText();
    if (STATUS_AWAITING.equals(status)) {
      assertEquals(0, anchor.path("scenarios").size(), "AWAITING 时三情景必须为空");
      assertFalse(hasField(anchor, "precisionAt5"), "AWAITING 时不得写入任何分数键（不得填假分数）");
      assertFalse(hasField(anchor, "ndcgAt5"), "AWAITING 时不得写入 NDCG 键（不得填假分数）");
      assertEquals(
          "", anchor.path("sheet").path("annotator").asText(), "AWAITING 时 annotator 必须为空");
      assertFalse(anchor.path("tieDetected").asBoolean(), "标签未填时不得声称打平");
    } else {
      assertEquals(STATUS_COLLECTED, status, "labelsStatus 只允许两态");
      assertEquals(3, anchor.path("scenarios").size(), "已填标签必须产出三情景");
      for (JsonNode scenario : anchor.path("scenarios")) {
        assertTrue(scenario.path("precisionAt5").isNumber(), "三情景必须给 precisionAt5");
        assertTrue(scenario.path("ndcgAt5").isNumber(), "三情景必须给 ndcgAt5");
        assertFalse(scenario.path("top5RowIds").isEmpty(), "三情景必须可追溯到行 id");
      }
      assertEquals(
          LabelSheetNearDupRelevanceTest.SCENARIO_KEYS,
          List.of(
              anchor.path("scenarios").get(0).path("key").asText(),
              anchor.path("scenarios").get(1).path("key").asText(),
              anchor.path("scenarios").get(2).path("key").asText()),
          "三情景必须按固定顺序并列，不排序");
      assertFalse(
          anchor.path("tieDetected").asBoolean(),
          "打平即红（proposal §8）：三情景指标全等 ⇒ "
              + anchor.path("tieNote").asText()
              + "；不得改口径造差异，须停手上报 Owner");
      assertFalse(anchor.path("tieNote").asText().isBlank(), "tieNote 必须写明是否打平及其依据");
    }
    assertEquals(COLUMNS, toTextList(anchor.path("sheet").path("columns")), "锚内列名必须等于 CSV 白名单");

    LabelSheetNearDupRelevanceTest.LabelsOutcome outcome =
        LabelSheetNearDupRelevanceTest.evaluate(
            first,
            LabelSheetNearDupRelevanceTest.parseCsv(new String(csvBytes, StandardCharsets.UTF_8)));
    ObjectNode rebuiltAnchor = LabelSheetNearDupRelevanceTest.buildAnchor(first, outcome, csvBytes);
    ObjectNode rebuiltAgain = LabelSheetNearDupRelevanceTest.buildAnchor(second, outcome, csvBytes);
    assertArrayEquals(
        LabelSheetNearDupRelevanceTest.canonicalBytes(
            LabelSheetNearDupRelevanceTest.withoutRuntime(rebuiltAnchor)),
        LabelSheetNearDupRelevanceTest.canonicalBytes(
            LabelSheetNearDupRelevanceTest.withoutRuntime(rebuiltAgain)),
        "R6：锚两次生成必须逐字节一致（剔除顶层 runtime）");
    assertArrayEquals(
        LabelSheetNearDupRelevanceTest.canonicalBytes(
            LabelSheetNearDupRelevanceTest.withoutRuntime(rebuiltAnchor)),
        LabelSheetNearDupRelevanceTest.canonicalBytes(
            LabelSheetNearDupRelevanceTest.withoutRuntime(anchor)),
        "落盘锚与重算不一致（可能被手改）：期望剔除顶层 runtime 后逐字节相同");
    assertArrayEquals(
        LabelSheetNearDupRelevanceTest.buildAnchorMdBytes(rebuiltAnchor),
        mdBytes,
        "落盘 md 与重算不一致（可能被手改）");

    String haystack =
        new String(anchorBytes, StandardCharsets.UTF_8)
            + new String(mdBytes, StandardCharsets.UTF_8)
            + new String(readmeBytes, StandardCharsets.UTF_8);
    for (String word : TENDENCY_WORDS) {
      assertFalse(haystack.contains(word), "R4：不得出现倾向/排序措辞「" + word + "」");
    }
    for (String word : List.of("用户偏好", "生产相关性", "全局质量")) {
      assertFalse(haystack.contains(word), "R8：不得出现外推断言「" + word + "」");
    }

    String annotator = anchor.path("sheet").path("annotator").asText();
    assertFalse(AGENT_LIKE.matcher(annotator).find(), "R5：annotator 不得命中 agent/assistant/ai/model");
    assertEquals(
        LabelSheetNearDupRelevanceTest.labelsProvenance(outcome),
        anchor.path("labelsProvenance").asText(),
        "labelsProvenance 必须由 annotator 派生：机器预标不得被读成人工真值");

    String md = new String(mdBytes, StandardCharsets.UTF_8);
    assertTrue(md.split("\n", -1).length <= 40, "R6：锚 md 必须 ≤ 40 行");

    List<String> files;
    try (Stream<Path> stream = Files.list(LABELS_DIR)) {
      files = stream.map(path -> path.getFileName().toString()).sorted().toList();
    }
    assertEquals(
        List.of("near-dup-relevance-v1-README.md", "near-dup-relevance-v1.csv"),
        files,
        "labels 目录只允许本卡两个产物（合成 CSV 不得落 docs/）");
    for (String name : files) {
      assertFalse(
          name.toLowerCase(Locale.ROOT).contains("synth"),
          "合成 CSV 不得出现在 docs/rag-quality/labels: " + name);
    }
    assertFalse(
        new String(anchorBytes, StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT)
            .contains("synthetic"),
        "锚内不得引用合成 CSV");
    System.out.println(
        "[label-sheet-guard] 守卫通过：rows="
            + first.rows().size()
            + " status="
            + status
            + " mdLines="
            + md.split("\n", -1).length);
  }

  /** F1：池深三件套必须入锚且与实测一致；不足请求深度必须写明原因（不凑数）。 */
  private static void assertPoolDepth(
      JsonNode anchor, LabelSheetNearDupRelevanceTest.Bundle bundle) {
    JsonNode pool = anchor.path("poolDepth");
    assertEquals(
        LabelSheetNearDupRelevanceTest.REQUESTED_DEPTH,
        pool.path("requestedDepth").asInt(),
        "F1：锚内 requestedDepth 必须记 10（Owner 拍板）");
    List<Integer> perQuery = new ArrayList<>();
    pool.path("actualPerQuery").forEach(node -> perQuery.add(node.asInt()));
    assertEquals(bundle.actualPerQuery(), perQuery, "F1：逐查询条数必须等于本次实测");
    assertEquals(
        perQuery.stream().mapToInt(Integer::intValue).sum(),
        pool.path("totalRows").asInt(),
        "F1：totalRows 必须等于逐查询条数之和");
    assertEquals(bundle.rows().size(), pool.path("totalRows").asInt(), "F1：totalRows 必须等于实际行数");
    for (int count : perQuery) {
      assertTrue(
          count <= LabelSheetNearDupRelevanceTest.REQUESTED_DEPTH,
          "F1：逐查询条数不得超过请求深度（实测 " + count + "）");
    }
    assertFalse(pool.path("ceilingReason").asText().isBlank(), "F1：不足请求深度必须写明上限原因");
  }

  /** F3：口径逐项可 grep，且同一句在 md 与 README 各出现一次。 */
  private static void assertMetricCaliber(
      JsonNode anchor, byte[] csvBytes, byte[] readmeBytes, byte[] mdBytes) {
    JsonNode caliber = anchor.path("metricCaliber");
    assertTrue(caliber.isArray() && caliber.size() >= 5, "F3：metricCaliber 必须逐项列出（≥5 项）");
    Set<String> metrics = new LinkedHashSet<>();
    caliber.forEach(node -> metrics.add(node.path("metric").asText()));
    for (String expected :
        List.of("pool", "precision@5", "ndcg@5", "ySuppressionAgreement", "rounding")) {
      assertTrue(metrics.contains(expected), "F3：metricCaliber 缺项「" + expected + "」，实测 " + metrics);
    }
    String caliberText = caliber.toString();
    assertTrue(caliberText.contains("min(5"), "F3：precision@5 必须写明分母口径 min(5, 池内条数)");
    assertTrue(caliberText.contains("2^relevance"), "F3：ndcg@5 必须写明增益 2^relevance − 1");
    assertTrue(caliberText.contains("log2(rank+2)"), "F3：ndcg@5 必须写明折损 log2(rank+2)");
    assertTrue(caliberText.contains("IDCG"), "F3：ndcg@5 必须写明 IDCG 口径");
    assertTrue(caliberText.contains("bandWeight < 1"), "F3：一致率必须写明分子口径");
    assertEquals(
        anchor.path("numericHygiene").path("metricRounding").asText(),
        caliber
            .get(caliber.size() - 1)
            .path("caliber")
            .asText()
            .replace("沿用 numericHygiene.metricRounding = ", ""),
        "F3：rounding 必须沿用 numericHygiene.metricRounding");
    String sentence = LabelSheetNearDupRelevanceTest.caliberSentence();
    assertEquals(
        1,
        occurrences(new String(mdBytes, StandardCharsets.UTF_8), sentence),
        "F3：口径句在 md 必须恰出现一次");
    assertEquals(
        1,
        occurrences(new String(readmeBytes, StandardCharsets.UTF_8), sentence),
        "F3：口径句在 README 必须恰出现一次（给 Owner 看）");
    assertTrue(csvBytes.length > 0, "F3：CSV 必须存在");
  }

  /** F4：可测范围三件套（能答 / 不能答 / 不变量）必须入锚。 */
  private static void assertMeasurableScope(
      JsonNode anchor, LabelSheetNearDupRelevanceTest.Bundle bundle) {
    JsonNode scope = anchor.path("measurableScope");
    JsonNode canAnswer = scope.path("canAnswer");
    assertTrue(canAnswer.isObject(), "F4：可测范围必须声明 canAnswer");
    for (String key :
        List.of("duplicatePrevalence", "ySuppressionAgreement", "nonDuplicatePromotion")) {
      assertFalse(canAnswer.path(key).asText().isBlank(), "F4：canAnswer 缺「" + key + "」");
    }
    JsonNode cannot = scope.path("cannotAnswer");
    assertEquals(3, cannot.size(), "F4：cannotAnswer 必须写明三条（fixture / 单人标注 / 只对本批成立）");
    assertTrue(cannot.toString().contains("fixture"), "F4：cannotAnswer 必须点明语料是程序化 fixture");
    assertTrue(cannot.toString().contains("一致性检验"), "F4：cannotAnswer 必须点明单人标注无一致性检验");
    assertTrue(scope.path("orderInvariant").isBoolean(), "F4：必须记 orderInvariant 实测值");
    assertEquals(
        scope.path("orderInvariant").asBoolean(),
        scope.path("invariantObserved").asBoolean(),
        "F4：invariantObserved 必须等于三情景排序全等的实测值");
    assertFalse(scope.path("orderInvariantBasis").asText().isBlank(), "F4：必须写明不变量/差异的实测原因");
    assertEquals(
        !scope.path("downweightOrderInvariant").asBoolean(),
        scope.path("promotionInTop5").asBoolean(),
        "F4：promotionInTop5 必须等于「现状 vs 无降权」排序不同的实测值");
    assertEquals(
        bundle.actualPerQuery().size(),
        anchor.path("poolDepth").path("actualPerQuery").size(),
        "F4：逐查询条数必须与池深声明同长");
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }

  /**
   * 盲标与结构：列名白名单、行 id 唯一且有序、候选文本非空、生成器未代填、无提示字样。
   *
   * <p>D4「不得代填」断言的是**生成器模板**（模板四列必须全空）；人工表本该被填，故只要求整行全空或全填。
   */
  private static void assertBlindSheet(byte[] csvBytes, byte[] templateBytes) {
    List<List<String>> records =
        LabelSheetNearDupRelevanceTest.parseCsv(new String(csvBytes, StandardCharsets.UTF_8));
    List<List<String>> template =
        LabelSheetNearDupRelevanceTest.parseCsv(new String(templateBytes, StandardCharsets.UTF_8));
    assertEquals(template.size(), records.size(), "人工表行数必须等于模板");
    assertEquals(records.get(0), template.get(0), "人工表表头必须等于模板表头");
    assertFalse(records.isEmpty(), "CSV 不得为空");
    assertEquals(COLUMNS, records.get(0), "CSV 表头必须等于列名白名单");
    for (String column : records.get(0)) {
      String lower = column.toLowerCase(Locale.ROOT);
      assertFalse(
          lower.contains("cos") || lower.contains("tier") || lower.contains("band"),
          "R2：CSV 不得出现 cos/tier/band 列: " + column);
    }
    assertEquals(records.size() - 1, records.size() - 1, "行数自洽");
    Set<String> rowIds = new LinkedHashSet<>();
    int expectedQuery = 0;
    int expectedRank = 1;
    List<Integer> perQuery = new ArrayList<>();
    int previousCount = -1;
    for (int index = 1; index < records.size(); index++) {
      List<String> row = records.get(index);
      assertEquals(COLUMNS.size(), row.size(), "第 " + index + " 行字段数必须 11");
      assertTrue(rowIds.add(row.get(0)), "rowId 必须唯一: " + row.get(0));
      assertTrue(
          row.get(0).matches("q[0-5]:\\d+"), "rowId 必须 q{queryIndex}:{chunkId}: " + row.get(0));
      assertEquals(String.valueOf(expectedQuery), row.get(1), "行序必须 queryIndex ASC");
      assertEquals(String.valueOf(expectedRank), row.get(6), "行序必须 currentRank ASC（↑）");
      assertFalse(row.get(2).isBlank(), "查询文本为空: " + row.get(0));
      assertFalse(row.get(3).isBlank(), "R7：候选文本为空: " + row.get(0));
      assertFalse(row.get(4).isBlank(), "候选文件名缺失: " + row.get(0));
      assertTrue(Integer.parseInt(row.get(5)) > 0, "候选页码必须为正: " + row.get(0));
      for (String word : List.of("近重复", "可疑", "压制")) {
        assertFalse(row.get(3).contains(word), "R2：候选文本不得带结论提示「" + word + "」: " + row.get(0));
      }
      for (int column : List.of(7, 8, 9, 10)) {
        assertEquals(
            "", template.get(index).get(column), "D4：生成器不得预填第 " + column + " 列: " + row.get(0));
      }
      int filled = 0;
      for (int column : List.of(7, 8, 9, 10)) {
        if (!row.get(column).isBlank()) {
          filled++;
        }
      }
      assertTrue(filled == 0 || filled == 4, "标注列必须整行全空或全填（半填不可判）: " + row.get(0));
      expectedRank++;
      boolean lastRow = index == records.size() - 1;
      String nextQuery = lastRow ? null : records.get(index + 1).get(1);
      if (lastRow || !nextQuery.equals(row.get(1))) {
        perQuery.add(expectedRank - 1);
        if (previousCount >= 0) {
          assertEquals(previousCount, perQuery.get(perQuery.size() - 1), "各查询条数必须一致（同一语料同一池深）");
        }
        previousCount = expectedRank - 1;
        expectedQuery++;
        expectedRank = 1;
      }
    }
    assertEquals(6, expectedQuery, "必须恰 6 条查询");
    assertEquals(6, perQuery.size(), "必须 6 条查询各若干行");
    int total = perQuery.stream().mapToInt(Integer::intValue).sum();
    assertEquals(records.size() - 1, total, "CSV 数据行数必须等于逐查询条数之和");
    for (int count : perQuery) {
      assertTrue(
          count >= 1 && count <= LabelSheetNearDupRelevanceTest.REQUESTED_DEPTH,
          "逐查询条数必须在 1.." + LabelSheetNearDupRelevanceTest.REQUESTED_DEPTH + "（实测 " + count + "）");
    }
  }

  private static List<String> toTextList(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode node : array) {
      values.add(node.asText());
    }
    return values;
  }

  /** 递归判断树里是否存在某个**对象字段名**（情景定义里的指标名是数组字符串，不算分数键）。 */
  private static boolean hasField(JsonNode node, String field) {
    if (node.isObject()) {
      if (node.has(field)) {
        return true;
      }
      for (JsonNode child : node) {
        if (hasField(child, field)) {
          return true;
        }
      }
      return false;
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        if (hasField(child, field)) {
          return true;
        }
      }
      return false;
    }
    return false;
  }

  private static void assertLf(byte[] content, String name) {
    String text = new String(content, StandardCharsets.UTF_8);
    assertFalse(text.contains("\r"), name + " 必须纯 LF");
    assertTrue(text.endsWith("\n"), name + " 必须单尾换行");
    assertFalse(text.endsWith("\n\n"), name + " 不得有尾部空行");
  }
}
