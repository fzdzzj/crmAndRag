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
 * 行、R7 文本缺失上限、R8 外推边界；外加「合成 CSV 未进 docs/」这一条防污染断言。
 */
class LabelSheetNearDupRelevanceGuardTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** R4 倾向词表：三情景并列呈现，不得出现取舍建议。 */
  private static final List<String> TENDENCY_WORDS =
      List.of("建议", "推荐", "应下调", "应当下调", "倾向", "更优", "更好", "更差", "应该");

  private static final Pattern AGENT_LIKE =
      Pattern.compile("(?i)(^|[^a-z])(agent|assistant|ai|model)([^a-z]|$)");

  @Test
  @DisplayName("守卫：盲标白名单 / 30 行 / 逐字节确定性 / AWAITING 空情景 / 无倾向措辞 / 合成 CSV 未落 docs")
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
    assertArrayEquals(first.csvBytes(), csvBytes, "落盘 CSV 必须等于本次生成");
    assertArrayEquals(first.readmeBytes(), readmeBytes, "落盘 README 必须等于本次生成");
    assertLf(csvBytes, "CSV");
    assertLf(readmeBytes, "README");
    assertLf(anchorBytes, "锚 JSON");
    assertLf(mdBytes, "锚 md");

    assertBlindSheet(csvBytes);

    JsonNode anchor = MAPPER.readTree(anchorBytes);
    assertEquals("label-sheet-near-dup-relevance", anchor.path("card").asText(), "锚 card 不符");
    assertEquals(30, anchor.path("sheet").path("rowCount").asInt(), "锚必须记 30 行");
    assertEquals(
        RealPdfAssets.sha256(csvBytes),
        anchor.path("sheet").path("sha256").asText(),
        "锚内 CSV sha256 必须等于落盘字节");
    assertEquals(
        RealPdfAssets.sha256(Files.readAllBytes(BAND_ENTRY)),
        anchor.path("sourceAnchors").path("bandEntry").path("sha256").asText(),
        "来源锚 sha256 必须与磁盘一致（既有锚零字节变动）");
    assertEquals(0, anchor.path("runtime").path("externalCalls").asInt(), "零外呼：externalCalls 必须 0");
    assertFalse(anchor.path("runtime").path("springContextStarted").asBoolean(), "不得启动 Spring");
    assertEquals(30, anchor.path("textCoverage").path("rowsWithText").asInt(), "30 行必须都有可读文本");
    assertTrue(anchor.path("textCoverage").path("missingRows").asInt() <= 3, "R7：缺失 > 3 行必须停手");
    assertTrue(
        anchor.path("residual").path("note").asText().contains("6 查询"),
        "R8：residual 必须写明只对 6 查询成立");
    assertTrue(
        anchor.path("residual").path("note").asText().contains("30 候选"),
        "R8：residual 必须写明只对 30 候选成立");
    assertTrue(
        anchor.path("numericHygiene").path("noPlaceholderScores").asBoolean(),
        "numericHygiene 必须声明无占位分数");
    assertEquals(3, anchor.path("scenarioDefinitions").size(), "三情景定义必须都在（与是否填标签无关）");
    JsonNode reconciliation = anchor.path("reconstructionCheck");
    assertEquals(
        reconciliation.path("imageRouteRowsRebuilt").asInt(),
        reconciliation.path("reconciled").asInt(),
        "重建行必须全部对账通过");

    String status = anchor.path("labelsStatus").asText();
    if (STATUS_AWAITING.equals(status)) {
      assertEquals(0, anchor.path("scenarios").size(), "AWAITING 时三情景必须为空");
      assertFalse(hasField(anchor, "precisionAt5"), "AWAITING 时不得写入任何分数键（不得填假分数）");
      assertFalse(hasField(anchor, "ndcgAt5"), "AWAITING 时不得写入 NDCG 键（不得填假分数）");
      assertEquals(
          "", anchor.path("sheet").path("annotator").asText(), "AWAITING 时 annotator 必须为空");
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
    }
    assertEquals(COLUMNS, toTextList(anchor.path("sheet").path("columns")), "锚内列名必须等于 CSV 白名单");

    LabelSheetNearDupRelevanceTest.LabelsOutcome outcome =
        LabelSheetNearDupRelevanceTest.evaluate(
            first,
            LabelSheetNearDupRelevanceTest.parseCsv(new String(csvBytes, StandardCharsets.UTF_8)));
    ObjectNode rebuiltAnchor = LabelSheetNearDupRelevanceTest.buildAnchor(first, outcome);
    ObjectNode rebuiltAgain = LabelSheetNearDupRelevanceTest.buildAnchor(second, outcome);
    assertArrayEquals(
        LabelSheetNearDupRelevanceTest.canonicalBytes(
            LabelSheetNearDupRelevanceTest.withoutRuntime(rebuiltAnchor)),
        LabelSheetNearDupRelevanceTest.canonicalBytes(
            LabelSheetNearDupRelevanceTest.withoutRuntime(rebuiltAgain)),
        "R6：锚两次生成必须逐字节一致（剔除顶层 runtime）");

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
        "[label-sheet-guard] 守卫通过：rows=30 status="
            + status
            + " mdLines="
            + md.split("\n", -1).length);
  }

  /** 盲标与结构：列名白名单、30 行、rowId 唯一且有序、候选文本非空、四列未代填、无提示字样。 */
  private static void assertBlindSheet(byte[] csvBytes) {
    List<List<String>> records =
        LabelSheetNearDupRelevanceTest.parseCsv(new String(csvBytes, StandardCharsets.UTF_8));
    assertFalse(records.isEmpty(), "CSV 不得为空");
    assertEquals(COLUMNS, records.get(0), "CSV 表头必须等于列名白名单");
    for (String column : records.get(0)) {
      String lower = column.toLowerCase(Locale.ROOT);
      assertFalse(
          lower.contains("cos") || lower.contains("tier") || lower.contains("band"),
          "R2：CSV 不得出现 cos/tier/band 列: " + column);
    }
    assertEquals(31, records.size(), "CSV 必须 1 行表头 + 30 行数据");
    Set<String> rowIds = new LinkedHashSet<>();
    int expectedQuery = 0;
    int expectedRank = 1;
    List<Integer> perQuery = new ArrayList<>();
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
        assertEquals("", row.get(column), "D4：执行者不得代填第 " + column + " 列: " + row.get(0));
      }
      expectedRank++;
      if (expectedRank > 5) {
        perQuery.add(expectedRank - 1);
        expectedQuery++;
        expectedRank = 1;
      }
    }
    assertEquals(30, rowIds.size(), "rowId 必须 30 个且唯一");
    assertEquals(6, expectedQuery, "必须恰 6 条查询");
    assertEquals(6, perQuery.size(), "必须 6 条查询各 5 行（本语料无不足 5 条的查询）");
    for (int count : perQuery) {
      assertEquals(5, count, "每条查询必须 5 行");
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
