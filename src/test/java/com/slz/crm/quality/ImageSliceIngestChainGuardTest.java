package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图像切片摄取链对账守卫（image-slice-ingest-chain-reconciliation 任务 F，只读、零外呼）。
 *
 * <p>从结论锚 {@code docs/rag-quality/image-slice-ingest-chain-v1.json} 重算：
 *
 * <ul>
 *   <li><b>R1</b>：七链差值逐行重算并与锚内 deltas 对账（改坏任一计数或差值即红）；
 *   <li><b>R2</b>：四数关系（invocationsNoCache == childRows、差值 == childRows − distinctEmbedTexts）与
 *       cacheSuppressionVerdict 机械重导（改坏任一数即红）；
 *   <li><b>R5</b>：verdict 三选一 + 条件字段（COUNTER-SUPPRESSED 不得带 brokenLink / CHAIN-BROKEN 必带 brokenLink
 *       / INCONCLUSIVE 必带 missingEvidence），第四种字符串直接红；
 *   <li><b>R3/R4/R0</b>：两条丢弃语义、分母三件套与零外呼结构字段自洽。
 * </ul>
 *
 * <p>锚的数值本体由 {@link ImageSliceIngestChainReconciliationTest#anchorIsByteReproducible()}
 * 以全链重算逐字节钉死；本类是第二道独立对账。
 */
@DisplayName("图像切片摄取链对账守卫：锚重算 R1 差值 / R2 四数 / R5 三选一（只读，零外呼）")
class ImageSliceIngestChainGuardTest {

  private static final Path ANCHOR =
      Path.of("docs", "rag-quality", "image-slice-ingest-chain-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR), "结论锚缺失（先跑 ImageSliceIngestChainReconciliationTest 生成）");
    byte[] bytes = Files.readAllBytes(ANCHOR);
    assertFalse(
        new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("\r"), "锚 JSON 必须纯 LF");
    anchor = MAPPER.readTree(bytes);
  }

  @Test
  @DisplayName("R5：verdict 三选一，条件字段按 verdict 语义钉住")
  void verdictThreeWayWithConditionalFields() {
    String verdict = anchor.path("verdict").asText();
    assertTrue(
        List.of("COUNTER-SUPPRESSED", "CHAIN-BROKEN", "INCONCLUSIVE").contains(verdict),
        "verdict 只许三选一，实际: " + verdict);
    switch (verdict) {
      case "COUNTER-SUPPRESSED" -> {
        assertTrue(anchor.path("brokenLink").isNull(), "链路完好结论不得带 brokenLink");
        assertTrue(anchor.path("missingEvidence").isNull(), "链路完好结论不得带 missingEvidence");
      }
      case "CHAIN-BROKEN" -> {
        assertFalse(anchor.path("brokenLink").asText("").isBlank(), "CHAIN-BROKEN 必须带 brokenLink");
        assertTrue(r1HasNegativeDelta(), "CHAIN-BROKEN 必须有相邻链差值 < 0");
      }
      default ->
          assertFalse(anchor.path("missingEvidence").asText("").isBlank(), "INCONCLUSIVE 必须写缺哪条证据");
    }
    // fix2：七链相邻差值全 0 是实测事实，与本卡归因无关——提为无条件断言，不随 verdict 存废
    assertTrue(r1AllDeltasZero(), "七链相邻差值必须全 0（实测事实）");
  }

  @Test
  @DisplayName("R1：七链差值逐行重算并与锚对账，全部计数 ≥ 1")
  void r1DeltasRecomputedFromCounts() {
    JsonNode rows = anchor.path("r1").path("rows");
    assertEquals(2, rows.size(), "R1 必须覆盖 n1 与 n8 两档");
    assertEquals(7, anchor.path("r1").path("chainOrder").size(), "chainOrder 必须七链");
    assertTrue(
        anchor.path("r1").path("skipPoints").size() >= 4,
        "四类生产跳过点（:286/:290/:310/ImageSliceIngestSupport:132）必须登记");
    for (JsonNode row : rows) {
      int[] counts = {
        row.path("extracted").asInt(),
        row.path("describeCalls").asInt(),
        row.path("gatePassed").asInt(),
        row.path("slices").asInt(),
        row.path("childRows").asInt(),
        row.path("embedCalls").asInt(),
        row.path("imageStaged").asInt()
      };
      for (int i = 0; i < counts.length; i++) {
        assertTrue(counts[i] >= 1, "七链计数必须 ≥ 1（" + row.path("label").asText() + " 链位 " + i + "）");
      }
      assertEquals(
          6, row.path("deltas").size(), "六段相邻差值必须显式落锚（" + row.path("label").asText() + "）");
      for (int i = 0; i + 1 < counts.length; i++) {
        assertEquals(
            counts[i + 1] - counts[i],
            row.path("deltas").get(i).asInt(),
            "差值必须与计数重算一致（" + row.path("label").asText() + " 链位 " + i + "）");
      }
    }
  }

  @Test
  @DisplayName("R2：四数关系机械重导，cacheSuppressionVerdict 与关系一致")
  void r2FourNumbersRelationsHold() {
    int noCache = anchor.path("r2").path("invocationsNoCache").asInt();
    int withCache = anchor.path("r2").path("invocationsWithCache").asInt();
    int distinct = anchor.path("r2").path("distinctEmbedTexts").asInt();
    int childRows = anchor.path("r2").path("childRows").asInt();
    assertEquals(childRows, noCache, "判据一：无缓存桩调用数 == 子块行数");
    assertTrue(withCache <= noCache, "缓存桩调用数不得多于无缓存");
    assertEquals(
        childRows - distinct, noCache - withCache, "调用差值 == childRows − distinctEmbedTexts");
    assertTrue(anchor.path("r2").path("hitConstructed").asBoolean(), "必然命中对必须成立");
    assertTrue(
        anchor.path("r2").path("collidingEmbedTextsIdentical").asBoolean(), "命中对 embedText 逐字相同");
    assertTrue(anchor.path("r2").path("chainCountsUnchangedByCache").asBoolean(), "缓存不改链路计数");
    boolean suppressed = withCache < noCache && noCache - withCache == childRows - distinct;
    String derived =
        noCache == childRows && suppressed
            ? "COUNTER-SUPPRESSED"
            : (noCache == childRows ? "INCONCLUSIVE" : "CHAIN-BROKEN");
    assertEquals(
        derived,
        anchor.path("r2").path("cacheSuppressionVerdict").asText(),
        "cacheSuppressionVerdict 必须由四数机械重导");
  }

  @Test
  @DisplayName("R3：两条丢弃语义在锚内自洽（单图 N−1 / 整批归零 + 文本保全）")
  void r3DropSemanticsConsistent() {
    JsonNode perImage = anchor.path("r3").path("storageFailurePerImage");
    int images = perImage.path("images").asInt();
    assertTrue(images >= 2, "单图丢弃语义至少要 2 图才能证「其余照常」");
    assertEquals(images, perImage.path("describeCalls").asInt(), "全部图先描述");
    assertEquals(images, perImage.path("storeCalls").asInt(), "全部图尝试落盘（过闸数口径）");
    assertEquals(images - 1, perImage.path("slices").asInt(), "切片数 = N−1");
    assertEquals(images - 1, perImage.path("childRows").asInt(), "下游子块行随切片数");
    assertEquals(images - 1, perImage.path("embedCalls").asInt(), "下游 embed 随子块行");
    assertEquals(images - 1, perImage.path("imageStaged").asInt(), "视觉暂存随子块行");
    assertEquals(1, perImage.path("warnCount").asInt(), "落盘失败 WARN 恰一条");
    assertTrue(perImage.path("textChunksPreserved").asInt() >= 1, "文本切片未受影响");
    assertEquals(images - 1, perImage.path("survivingPageNos").size(), "存活页数 = N−1");

    JsonNode docLevel = anchor.path("r3").path("documentLevelSwallow");
    assertTrue(docLevel.path("imageSlicesLost").asBoolean(), "整批图片切片全丢");
    assertTrue(docLevel.path("textChunksPreserved").asInt() >= 1, "文本切片照常入库");
    assertEquals(1, docLevel.path("warnCount").asInt(), "ImageSliceIngestSupport WARN 恰一条");
    assertEquals(0, docLevel.path("describeCalls").asInt(), "逐图循环未进入：零描述外呼");

    JsonNode inner = anchor.path("r3").path("extractorThrowInnerLayer");
    assertEquals(0, inner.path("slices").asInt(), "提取器抛 → 切片数 0");
    assertEquals(0, inner.path("describeCalls").asInt(), "提取失败在 describe 之前");
    assertEquals(1, inner.path("warnCount").asInt(), "内层文档级 WARN 恰一条");
  }

  @Test
  @DisplayName("R4：三件套 rowIndex 恒 null，充分条件四因子齐备")
  void r4DenominatorTriplesConsistent() {
    assertTrue(anchor.path("r4").path("allRowIndexNull").asBoolean(), "rowIndex 恒 null 结论必须落锚");
    JsonNode triples = anchor.path("r4").path("triples");
    int tripleCount = anchor.path("r4").path("tripleCount").asInt();
    assertEquals(tripleCount, triples.size(), "tripleCount 与 triples 数组一致");
    assertTrue(tripleCount >= 8, "三件套必须来自 N≥8 档");
    for (JsonNode triple : triples) {
      assertTrue(triple.path("rowIndex").isNull(), "每个图片切片 rowIndex 恒 null");
      assertTrue(triple.path("pageNo").asInt() >= 1, "页码 = 图所在页（1 起）");
      assertFalse(triple.path("embedText").asText("").isBlank(), "embedText 三件套必须落全文");
    }
    assertTrue(
        anchor.path("r4").path("rowIndexNullCount").asInt() >= tripleCount,
        "rowIndexNullCount 覆盖全部断言过的图片切片");
    String condition = anchor.path("r4").path("sufficientCollisionCondition").asText();
    assertFalse(condition.isBlank(), "embedText 碰撞充分条件必须由数据推出并落锚");
    for (String factor : List.of("文件名", "类目", "页码", "描述")) {
      assertTrue(condition.contains(factor), "充分条件必须覆盖因子: " + factor);
    }
  }

  @Test
  @DisplayName("R0：零外呼结构字段与桩计数自洽")
  void zeroExternalCallStructure() {
    assertFalse(
        anchor.path("zeroExternalCall").path("realProviderConstructed").asBoolean(true),
        "realProviderConstructed 必须为 false（未构造真实 provider）");
    assertFalse(
        anchor.path("zeroExternalCall").path("structuralGuarantee").asText("").isBlank(),
        "零外呼结构保证必须显式声明");
    JsonNode counters = anchor.path("zeroExternalCall").path("stubCallCounters");
    assertTrue(counters.path("vision").asInt() > 0, "桩 vision 计数 > 0");
    assertTrue(counters.path("embedImage").asInt() > 0, "桩 embedImage 计数 > 0");
    assertTrue(counters.path("embed").asInt() > 0, "桩 embed 计数 > 0");
  }

  private static boolean r1AllDeltasZero() {
    for (JsonNode row : anchor.path("r1").path("rows")) {
      for (JsonNode delta : row.path("deltas")) {
        if (delta.asInt() != 0) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean r1HasNegativeDelta() {
    for (JsonNode row : anchor.path("r1").path("rows")) {
      for (JsonNode delta : row.path("deltas")) {
        if (delta.asInt() < 0) {
          return true;
        }
      }
    }
    return false;
  }
}
