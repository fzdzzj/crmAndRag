package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 守卫（near-dup-ingest-s1-window-cooccurrence，默认测试集内跑，只读，零外呼）。
 *
 * <ul>
 *   <li><b>语料本地可行性</b>：{@link NearDupS1Corpus#generate()} 全量校验（2 base × 3 变体 × 6 查询、短边 ≥64px、
 *       查询非空且单路）+ 生成确定性（两次生成键/尺寸/参数/文本逐字段一致）；变体参数冻结（编译期常量防漂移）；
 *   <li><b>R0 合成 PDF 往返</b>：每个变体包单图 PDF 过生产提取器，图数与像素逐一对上（零外呼）；
 *   <li><b>负空间</b>：{@code near-dup-ingest-cooccurrence-v1.json} 必须不存在（S0 守卫同款断言，本卡锚用新文件名）；
 *   <li><b>锚对账</b>（锚已落盘时；真跑前常态跳过并明示）：台账等式与硬帽、逐变体 R2 七链自洽、verdict 三选一与 逐候选表重算一致、窗口算术条目数与公式、bandSide
 *       六变体齐全、三值可达路径、既有上游锚 SHA 实算、runtime 冻结投影。
 * </ul>
 */
class NearDupIngestS1GuardTest {

  private static final Path ANCHOR = NearDupIngestS1IT.ANCHOR_PATH;

  /** S0 守卫的负空间锚名（卡 26 裁定 S1 未跑不得存在；本卡锚为 near-dup-ingest-s1-v1.json，互不冲突）。 */
  private static final Path FORBIDDEN_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-ingest-cooccurrence-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws Exception {
    if (Files.exists(ANCHOR)) {
      anchor = MAPPER.readTree(Files.readAllBytes(ANCHOR));
    }
  }

  @Test
  @DisplayName("语料本地可行性 + 确定性：2 base × 3 变体 × 6 查询全合规，两次生成逐字段一致（零外呼）")
  void corpusLocalFeasibilityAndDeterminism() throws Exception {
    NearDupS1Corpus.S1Corpus first = NearDupS1Corpus.generate();
    assertEquals(2, first.bases().size(), "base 必须恰 2 个");
    assertEquals(6, first.variants().size(), "变体必须 2×3=6 个");
    assertEquals(6, first.queries().size(), "查询必须 2×3=6 条");
    for (NearDupS1Corpus.S1Base base : first.bases()) {
      assertTrue(
          base.key().startsWith(NearDupS1Corpus.DOC_KEY), "base 必须来自 " + NearDupS1Corpus.DOC_KEY);
      assertTrue(
          Math.min(base.width(), base.height()) >= NearDupS1Corpus.MIN_EDGE_PX,
          "base 短边必须 ≥" + NearDupS1Corpus.MIN_EDGE_PX + "px: " + base.key());
      assertTrue(base.png() != null && base.png().length > 0, "base 位图不得为空");
      assertTrue(base.pageTextChars() > 0, "base 所在页必须有可提取文本: " + base.key());
    }
    List<String> variantKeys = new ArrayList<>();
    for (NearDupS1Corpus.S1Variant variant : first.variants()) {
      assertTrue(
          Math.min(variant.width(), variant.height()) >= NearDupS1Corpus.MIN_EDGE_PX,
          "变体短边必须 ≥" + NearDupS1Corpus.MIN_EDGE_PX + "px: " + variant.key());
      assertTrue(variant.png() != null && variant.png().length > 0, "变体位图不得为空");
      variantKeys.add(variant.key());
    }
    assertEquals(6, variantKeys.stream().distinct().count(), "变体键必须互不相同");
    for (int index = 0; index < first.variants().size(); index++) {
      String kind = first.variants().get(index).kind();
      assertEquals(
          NearDupS1Corpus.KINDS.get(index % NearDupS1Corpus.KINDS.size()),
          kind,
          "变体类型序必须冻结（" + NearDupS1Corpus.KINDS + "）");
    }
    for (NearDupS1Corpus.S1Query query : first.queries()) {
      assertFalse(query.text().isBlank(), "查询文本不得为空: " + query.baseKey() + "/" + query.form());
      assertTrue(
          com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter.split(query.text()).size() == 1,
          "查询必须单路（1 embed/查询的预算前提）: " + query.text());
      assertNotNull(first.baseByKey(query.baseKey()), "查询回指的 base 必须存在");
    }
    // 确定性：两次生成逐字段一致（png 逐字节经 SHA 比对）
    NearDupS1Corpus.S1Corpus second = NearDupS1Corpus.generate();
    for (int index = 0; index < first.bases().size(); index++) {
      NearDupS1Corpus.S1Base a = first.bases().get(index);
      NearDupS1Corpus.S1Base b = second.bases().get(index);
      assertEquals(a.key(), b.key(), "base 键确定性");
      assertEquals(RealPdfAssets.sha256(a.png()), RealPdfAssets.sha256(b.png()), "base 位图确定性");
    }
    for (int index = 0; index < first.variants().size(); index++) {
      NearDupS1Corpus.S1Variant a = first.variants().get(index);
      NearDupS1Corpus.S1Variant b = second.variants().get(index);
      assertEquals(a.key(), b.key(), "变体键确定性");
      assertEquals(a.param(), b.param(), "变体参数确定性（含 scale 梯子档位）");
      assertEquals(RealPdfAssets.sha256(a.png()), RealPdfAssets.sha256(b.png()), "变体位图确定性");
    }
    for (int index = 0; index < first.queries().size(); index++) {
      assertEquals(
          first.queries().get(index).text(), second.queries().get(index).text(), "查询文本确定性");
    }
  }

  @Test
  @DisplayName("变体参数冻结：三参数与梯子为编译期常量（D2：先钉死后测分，防静默改参挑样本）")
  void variantParamsFrozen() {
    assertEquals(List.of("jpeg-95", "jpeg-85", "scale-95"), NearDupS1Corpus.KINDS);
    assertEquals(0.95F, NearDupS1Corpus.JPEG_95_QUALITY);
    assertEquals(0.85F, NearDupS1Corpus.JPEG_85_QUALITY);
    assertEquals(0.95D, NearDupS1Corpus.SCALE_95_FACTOR);
    assertEquals(List.of(0.95D, 0.96D, 0.97D, 0.98D), NearDupS1Corpus.SCALE_LADDER);
    assertEquals(64, NearDupS1Corpus.MIN_EDGE_PX);
  }

  @Test
  @DisplayName("R0：每个变体单图合成 PDF 过生产提取器，图数与像素逐一对上（零外呼）")
  void variantSyntheticPdfRoundTrip() throws Exception {
    NearDupS1Corpus.S1Corpus corpus = NearDupS1Corpus.generate();
    for (NearDupS1Corpus.S1Variant variant : corpus.variants()) {
      byte[] pdf = NearDupIngestGateProbeIT.buildSingleImagePdf(variant.png());
      List<RealPdfAssets.Extracted> extracted =
          RealPdfAssets.extract(pdf, NearDupIngestGateProbeIT.MAX_IMAGES_PER_PDF);
      assertEquals(1, extracted.size(), variant.key() + " 合成 PDF 必须恰好抠出 1 图");
      assertEquals(variant.width(), extracted.get(0).width(), variant.key() + " 抠图宽度一致");
      assertEquals(variant.height(), extracted.get(0).height(), variant.key() + " 抠图高度一致");
    }
  }

  @Test
  @DisplayName("负空间：卡 26 裁定的 cooccurrence 锚必须仍不存在（本卡锚用新文件名，S0 守卫断言不被破坏）")
  void forbiddenAnchorStillAbsent() {
    assertFalse(
        Files.exists(FORBIDDEN_ANCHOR),
        "near-dup-ingest-cooccurrence-v1.json 不得存在: " + FORBIDDEN_ANCHOR);
  }

  @Test
  @DisplayName("锚对账（真跑后常态）：台账等式+硬帽、逐变体 R2 自洽、verdict 与逐候选表重算、窗口算术、bandSide、可达路径、上游锚 SHA")
  void anchorAccountingWhenPresent() throws Exception {
    if (anchor == null) {
      assertTrue(!Files.exists(ANCHOR), "锚文件存在但解析失败: " + ANCHOR.toAbsolutePath());
      System.out.println("[near-dup-s1-guard] 锚未落盘（真跑前常态，opt-in 写锚后本断言自动生效）：跳过锚对账");
      return;
    }
    assertEquals("COMPLETED", anchor.path("s1Status").asText(), "完成锚 s1Status 必须是 COMPLETED");

    // 台账等式（R8）
    JsonNode ledger = anchor.path("callLedger");
    int vision = ledger.path("vision").asInt(-1);
    int embedImage = ledger.path("embedImage").asInt(-1);
    int embed = ledger.path("embed").asInt(-1);
    assertEquals(
        vision + embedImage + embed, ledger.path("total").asInt(-2), "callLedger.total 必须等于三分项之和");
    assertTrue(ledger.path("total").asInt(-1) <= NearDupIngestS1IT.S1_CALL_CAP, "total 不得超硬帽 36");
    assertTrue(ledger.path("total").asInt(-1) > 0, "total 必须实测正数（写 0 即假绿）");
    assertEquals("Owner 2026-10-25 报批 36", ledger.path("authorization").asText(), "授权记录必须原样落锚");
    assertEquals(6, vision, "vision 必须每变体恰 1 次（6 变体）");

    // 逐变体 R2 七链自洽（rowIndex 恒 null 的载体面由 IT 断言；这里对账冻结行内部等式）
    JsonNode ingests = anchor.path("ingest");
    assertEquals(6, ingests.size(), "入库冻结行必须 6 条");
    int childRows = 0;
    int staged = 0;
    for (JsonNode row : ingests) {
      assertEquals(1, row.path("extracted").asInt(-1), "提取=1: " + row.path("variantKey").asText());
      assertEquals(
          1, row.path("described").asInt(-1), "describe=1: " + row.path("variantKey").asText());
      assertTrue(row.path("gatePass").asBoolean(), "过闸=1: " + row.path("variantKey").asText());
      assertTrue(row.path("slices").asInt(0) >= 1, "切片≥1: " + row.path("variantKey").asText());
      assertEquals(
          row.path("slices").asInt(0),
          row.path("stagedImageVectors").asInt(-1),
          "图像embed=切片数: " + row.path("variantKey").asText());
      assertEquals(
          row.path("childRows").asInt(-1),
          row.path("textEmbeds").asInt(-2),
          "文本embed=实测子块行数: " + row.path("variantKey").asText());
      assertEquals(
          row.path("chunkIds").size(),
          row.path("childRows").asInt(-1),
          "chunkIds 行数=子块行数: " + row.path("variantKey").asText());
      childRows += row.path("childRows").asInt(0);
      staged += row.path("stagedImageVectors").asInt(0);
    }
    assertEquals(6, vision, "vision 台账必须等于入库行数");
    assertEquals(staged + 6, embedImage, "embedImage 台账必须等于摄取暂存 + 6 查询图像");
    assertEquals(childRows + 6, embed, "embed 台账必须等于子块行 + 6 查询文本");

    // verdict 三选一 + 逐候选表重算（卡 28 守卫同款机械推导）
    String verdict = anchor.path("verdict").asText();
    assertTrue(
        List.of("RANK-CHANGE", "NO-RANK-CHANGE", "INCONCLUSIVE").contains(verdict),
        "verdict 越界: " + verdict);
    boolean anyImageRankChange = false;
    boolean anySetUnequal = false;
    boolean anyImageScoreChange = false;
    double imageDeltaSum = 0.0D;
    double maxImageScoreDelta = 0.0D;
    int imageCandidateCount = 0;
    int inTableDownWeighted = 0;
    for (JsonNode candidate : anchor.path("candidates")) {
      boolean image = "IMAGE".equals(candidate.path("sourceType").asText());
      if (image) {
        imageCandidateCount++;
      }
      if (candidate.path("downWeighted").asBoolean()) {
        inTableDownWeighted++;
      }
      JsonNode fusedANode = candidate.path("fusedA");
      JsonNode fusedBNode = candidate.path("fusedB");
      if (fusedANode.isNull() || fusedBNode.isNull()) {
        continue;
      }
      double fusedA = fusedANode.asDouble();
      double fusedB = fusedBNode.asDouble();
      if (image) {
        double delta = Math.abs(fusedA - fusedB);
        imageDeltaSum += delta;
        if (delta > maxImageScoreDelta) {
          maxImageScoreDelta = delta;
        }
        if (Double.compare(fusedA, fusedB) != 0) {
          anyImageScoreChange = true;
        }
        JsonNode rankA = candidate.path("rankA");
        JsonNode rankB = candidate.path("rankB");
        if (!rankA.isNull() && !rankB.isNull() && rankA.asInt() != rankB.asInt()) {
          anyImageRankChange = true;
        }
      } else {
        assertEquals(0, Double.compare(fusedA, fusedB), "非 IMAGE 候选两跑融合分必须逐字节相等: " + candidate);
      }
    }
    for (JsonNode query : anchor.path("queriesOutcome")) {
      if (!query.path("chunkIdSetEqual").asBoolean()) {
        anySetUnequal = true;
      }
    }
    assertEquals(
        imageCandidateCount,
        anchor.path("imageCandidateCount").asInt(-1),
        "imageCandidateCount 与逐候选表重算不符");
    assertEquals(
        maxImageScoreDelta,
        anchor.path("maxImageScoreDelta").asDouble(),
        0D,
        "maxImageScoreDelta 与逐候选表重算不符");
    assertEquals(
        imageDeltaSum,
        anchor.path("imageDeltaSum").asDouble(),
        0D,
        "imageDeltaSum 与逐候选表重算不符（任一行 fused 手改即红）");
    boolean rankOrSetTrigger = anyImageRankChange || anySetUnequal;
    if ("RANK-CHANGE".equals(verdict)) {
      assertTrue(rankOrSetTrigger, "RANK-CHANGE 必须有名次变化或集合不等");
    } else if ("NO-RANK-CHANGE".equals(verdict)) {
      assertFalse(rankOrSetTrigger, "NO-RANK-CHANGE 不得有名次变化或集合不等");
      JsonNode scoreFace = anchor.path("scoreFace");
      assertEquals(
          anyImageScoreChange, scoreFace.path("changed").asBoolean(), "scoreFace.changed 重算不符");
      JsonNode entries = anchor.path("windowCheck").path("entries");
      assertTrue(
          entries.size() >= anchor.path("downWeightedCount").asInt(-1),
          "NO-RANK-CHANGE 必须附窗口算术（条目数 ≥ 降权事件数；未入 topK 事件也逐条在册）");
      assertTrue(
          anchor.path("nonExtrapolationNote").asText().contains("不可外推"),
          "NO-RANK-CHANGE 必须显式声明不可外推");
    }
    assertTrue(
        anchor.path("downWeightedCount").asInt(-1) >= inTableDownWeighted,
        "降权事件数不得小于表内 downWeighted 行数");

    // 窗口算术公式（window = imageRouteWeight × (1 − w)）
    for (JsonNode entry : anchor.path("windowCheck").path("entries")) {
      if (entry.path("weight").isNull()) {
        continue;
      }
      double weight = entry.path("weight").asDouble();
      assertTrue(weight > 0D && weight < 1D, "降权权重必须在 (0,1) 内: " + entry);
      assertEquals(
          anchor.path("constants").path("imageRouteWeight").asDouble() * (1D - weight),
          entry.path("window").asDouble(),
          1e-12D,
          "window 必须等于 imageRouteWeight × (1 − w): " + entry);
    }

    // bandSide 六变体齐全（R5：逐变体记录，rows 恰为其 base 的 3 查询）
    JsonNode bandSides = anchor.path("bandSides");
    assertEquals(6, bandSides.size(), "bandSides 必须 6 条");
    for (JsonNode bandSide : bandSides) {
      assertTrue(
          List.of("IN-BAND", "ABOVE", "BELOW").contains(bandSide.path("bandSide").asText()),
          "bandSide 越界: " + bandSide);
      assertEquals(
          3,
          bandSide.path("rows").size(),
          "每变体恰 3 行（其 base 的 3 查询）: " + bandSide.path("variantKey"));
    }
    List<String> inBand = new ArrayList<>();
    for (JsonNode inBandKey : anchor.path("inBandVariantKeys")) {
      inBand.add(inBandKey.asText());
    }
    int inBandCount = 0;
    for (JsonNode bandSide : bandSides) {
      if ("IN-BAND".equals(bandSide.path("bandSide").asText())) {
        inBandCount++;
      }
    }
    assertEquals(inBandCount, inBand.size(), "inBandVariantKeys 必须与 bandSides 的 IN-BAND 集一致");
    if (inBand.isEmpty()) {
      assertFalse(anchor.path("constructionFailure").isNull(), "全带外必须落 constructionFailure（D3）");
    }

    // 三值可达路径 + 判据 R0-R8 + 正对照
    JsonNode reachability = anchor.path("verdictReachability");
    for (String key : List.of("RANK-CHANGE", "NO-RANK-CHANGE", "INCONCLUSIVE")) {
      assertFalse(reachability.path(key).asText("").isBlank(), "缺 " + key + " 可达路径说明");
    }
    for (String criterion : List.of("R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8")) {
      assertFalse(
          anchor.path("criteria").path(criterion).asText("").isBlank(), "锚缺判据 " + criterion);
    }
    assertTrue(anchor.path("positiveControl").path("flipped").asBoolean(), "正对照必须翻转（装置鉴别力前提）");

    // 上游锚 SHA 实算（既有锚字节漂移即红；master 全等由 git diff 另行举证）
    JsonNode sourceAnchors = anchor.path("sourceAnchors");
    for (String field :
        List.of("gateProbeAnchor", "notRunAnchor", "ablationAnchor", "displacementAnchor")) {
      Path file = Path.of(sourceAnchors.path(field).asText());
      assertTrue(Files.exists(file), "上游锚不存在: " + file);
      assertEquals(
          sha256(Files.readAllBytes(file)),
          sourceAnchors.path(field + "Sha256").asText(),
          "上游锚 SHA-256 与实算不一致（手抄或文件漂移）: " + field);
    }

    // runtime 冻结投影（NF-1：constants 只落编译期常量，活值只进 runtime 且白名单恰 [visionModel]）
    JsonNode runtime = anchor.path("runtime");
    assertTrue(runtime.isObject(), "锚缺顶层 runtime 块");
    JsonNode excluded = runtime.path("excludedFromReplay");
    assertTrue(excluded.isArray() && excluded.size() == 1, "excludedFromReplay 必须恰 1 项");
    assertEquals("visionModel", excluded.get(0).asText(), "白名单只许 visionModel");
    assertEquals(
        NearDupIngestGateProbeIT.DEFAULT_VISION_MODEL,
        anchor.path("constants").path("visionModel").asText(),
        "constants.visionModel 必须等于编译期常量");
  }

  private static String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    StringBuilder out = new StringBuilder();
    for (byte item : digest.digest(bytes)) {
      out.append(Character.forDigit((item >> 4) & 0xF, 16));
      out.append(Character.forDigit(item & 0xF, 16));
    }
    return out.toString();
  }
}
