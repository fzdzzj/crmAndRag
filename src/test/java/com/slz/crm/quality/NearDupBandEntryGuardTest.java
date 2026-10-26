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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守卫（near-dup-band-entry-and-tier-fill，默认测试集内跑，只读，零外呼）。
 *
 * <ul>
 *   <li><b>语料本地可行性</b>：{@link NearDupS1CropCorpus#generate()} 全量校验（2 base × 3 裁切变体 × 6 查询、裁切后短边
 *       ≥64px、查询非空且单路）+ 生成确定性（两次生成逐字段一致）；边距参数冻结（编译期常量防漂移，D2）；
 *   <li><b>R0 合成 PDF 往返</b>：每个变体包单图 PDF 过生产提取器，图数与像素逐一对上（零外呼）；
 *   <li><b>锚对账</b>（锚已落盘时；真跑前常态跳过并明示）：台账等式与硬帽、逐变体 R2 七链自洽、<b>R3 三分支联动重算 （板规 26：downWeightedCount==0
 *       ⇒ 只能 INCONCLUSIVE + missingEvidence）</b>、窗口算术公式、bandSide 六变体齐全、 <b>腿 B 表逐数字复算（静态算子重算 +
 *       独立向量计数抽查）</b>、<b>腿 B 无推荐阈值（锚节 + md 双 grep 断言）</b>、 三值可达路径、上游锚 SHA 实算、runtime 冻结投影。
 * </ul>
 */
class NearDupBandEntryGuardTest {

  private static final Path ANCHOR = NearDupBandEntryIT.ANCHOR_PATH;

  /** 结论 md（写集内第 6 文件；≤40 行 + 无推荐阈值 grep）。 */
  private static final Path REPORT_MD = Path.of("docs", "rag-quality", "near-dup-band-entry-v1.md");

  /** 「推荐/建议 + 阈值数字」禁式（腿 B R5 grep 断言；锚 legB 节与 md 同尺）。 */
  private static final String RECOMMENDATION_WITH_VALUE =
      "(?s).*(推荐|建议|应取|应降|应当取|recommend)[^\\n。]{0,24}0\\.9.*";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws Exception {
    if (Files.exists(ANCHOR)) {
      anchor = MAPPER.readTree(Files.readAllBytes(ANCHOR));
    }
  }

  @Test
  @DisplayName("语料本地可行性 + 确定性：2 base × 3 裁切变体 × 6 查询全合规，两次生成逐字段一致（零外呼）")
  void corpusLocalFeasibilityAndDeterminism() throws Exception {
    NearDupS1CropCorpus.CropCorpus first = NearDupS1CropCorpus.generate();
    assertEquals(2, first.bases().size(), "base 必须恰 2 个");
    assertEquals(6, first.variants().size(), "变体必须 2×3=6 个");
    assertEquals(6, first.queries().size(), "查询必须 2×3=6 条");
    for (NearDupS1CropCorpus.CropBase base : first.bases()) {
      assertTrue(
          base.key().startsWith(NearDupS1CropCorpus.DOC_KEY),
          "base 必须来自 " + NearDupS1CropCorpus.DOC_KEY);
      assertTrue(
          Math.min(base.width(), base.height()) >= NearDupS1CropCorpus.MIN_EDGE_PX,
          "base 短边必须 ≥" + NearDupS1CropCorpus.MIN_EDGE_PX + "px: " + base.key());
      assertTrue(base.png() != null && base.png().length > 0, "base 位图不得为空");
      assertTrue(base.pageTextChars() > 0, "base 所在页必须有可提取文本: " + base.key());
    }
    List<String> variantKeys = new ArrayList<>();
    for (NearDupS1CropCorpus.CropVariant variant : first.variants()) {
      assertTrue(
          Math.min(variant.width(), variant.height()) >= NearDupS1CropCorpus.MIN_EDGE_PX,
          "裁切后短边必须 ≥" + NearDupS1CropCorpus.MIN_EDGE_PX + "px（R1 本地像素断言）: " + variant.key());
      assertTrue(variant.png() != null && variant.png().length > 0, "变体位图不得为空");
      variantKeys.add(variant.key());
    }
    assertEquals(6, variantKeys.stream().distinct().count(), "变体键必须互不相同");
    for (int index = 0; index < first.variants().size(); index++) {
      String kind = first.variants().get(index).kind();
      assertEquals(
          NearDupS1CropCorpus.KINDS.get(index % NearDupS1CropCorpus.KINDS.size()),
          kind,
          "变体类型序必须冻结（" + NearDupS1CropCorpus.KINDS + "）");
    }
    for (NearDupS1CropCorpus.CropQuery query : first.queries()) {
      assertFalse(query.text().isBlank(), "查询文本不得为空: " + query.baseKey() + "/" + query.form());
      assertTrue(
          com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter.split(query.text()).size() == 1,
          "查询必须单路（1 embed/查询的预算前提）: " + query.text());
      assertTrue(first.baseByKey(query.baseKey()) != null, "查询回指的 base 必须存在");
    }
    // 确定性：两次生成逐字段一致（png 逐字节经 SHA 比对）
    NearDupS1CropCorpus.CropCorpus second = NearDupS1CropCorpus.generate();
    for (int index = 0; index < first.bases().size(); index++) {
      NearDupS1CropCorpus.CropBase a = first.bases().get(index);
      NearDupS1CropCorpus.CropBase b = second.bases().get(index);
      assertEquals(a.key(), b.key(), "base 键确定性");
      assertEquals(RealPdfAssets.sha256(a.png()), RealPdfAssets.sha256(b.png()), "base 位图确定性");
    }
    for (int index = 0; index < first.variants().size(); index++) {
      NearDupS1CropCorpus.CropVariant a = first.variants().get(index);
      NearDupS1CropCorpus.CropVariant b = second.variants().get(index);
      assertEquals(a.key(), b.key(), "变体键确定性");
      assertEquals(a.param(), b.param(), "变体参数确定性（含边距梯子档位）");
      assertEquals(RealPdfAssets.sha256(a.png()), RealPdfAssets.sha256(b.png()), "变体位图确定性");
    }
    for (int index = 0; index < first.queries().size(); index++) {
      assertEquals(
          first.queries().get(index).text(), second.queries().get(index).text(), "查询文本确定性");
    }
  }

  @Test
  @DisplayName("边距参数冻结：三边距与步进为编译期常量（D2：先钉死后测分，防静默改参挑样本）")
  void variantParamsFrozen() {
    assertEquals(List.of("crop-02", "crop-04", "crop-06"), NearDupS1CropCorpus.KINDS);
    assertEquals(0.02D, NearDupS1CropCorpus.MARGIN_02);
    assertEquals(0.04D, NearDupS1CropCorpus.MARGIN_04);
    assertEquals(0.06D, NearDupS1CropCorpus.MARGIN_06);
    assertEquals(0.01D, NearDupS1CropCorpus.MARGIN_STEP);
    assertEquals(64, NearDupS1CropCorpus.MIN_EDGE_PX);
    assertEquals(36, NearDupBandEntryIT.BAND_CALL_CAP);
    assertEquals(30, NearDupBandEntryIT.BAND_CALL_TARGET);
  }

  @Test
  @DisplayName("R0：每个裁切变体单图合成 PDF 过生产提取器，图数与像素逐一对上（零外呼）")
  void variantSyntheticPdfRoundTrip() throws Exception {
    NearDupS1CropCorpus.CropCorpus corpus = NearDupS1CropCorpus.generate();
    for (NearDupS1CropCorpus.CropVariant variant : corpus.variants()) {
      byte[] pdf = NearDupIngestGateProbeIT.buildSingleImagePdf(variant.png());
      List<RealPdfAssets.Extracted> extracted =
          RealPdfAssets.extract(pdf, NearDupIngestGateProbeIT.MAX_IMAGES_PER_PDF);
      assertEquals(1, extracted.size(), variant.key() + " 合成 PDF 必须恰好抠出 1 图");
      assertEquals(variant.width(), extracted.get(0).width(), variant.key() + " 抠图宽度一致");
      assertEquals(variant.height(), extracted.get(0).height(), variant.key() + " 抠图高度一致");
    }
  }

  @Test
  @DisplayName("锚对账（真跑后常态）：台账+R2+R3 三分支联动重算+窗口算术+bandSide+腿 B 逐数字复算+无推荐阈值 grep+上游锚 SHA")
  void anchorAccountingWhenPresent() throws Exception {
    if (anchor == null) {
      assertTrue(!Files.exists(ANCHOR), "锚文件存在但解析失败: " + ANCHOR.toAbsolutePath());
      System.out.println("[near-dup-band-guard] 锚未落盘（真跑前常态，opt-in 写锚后本断言自动生效）：跳过锚对账");
      return;
    }
    assertEquals(
        "COMPLETED", anchor.path("bandEntryStatus").asText(), "完成锚 bandEntryStatus 必须是 COMPLETED");

    // 台账等式（R8）
    JsonNode ledger = anchor.path("callLedger");
    int vision = ledger.path("vision").asInt(-1);
    int embedImage = ledger.path("embedImage").asInt(-1);
    int embed = ledger.path("embed").asInt(-1);
    assertEquals(
        vision + embedImage + embed, ledger.path("total").asInt(-2), "callLedger.total 必须等于三分项之和");
    assertTrue(
        ledger.path("total").asInt(-1) <= NearDupBandEntryIT.BAND_CALL_CAP, "total 不得超硬帽 36");
    assertTrue(ledger.path("total").asInt(-1) > 0, "total 必须实测正数（写 0 即假绿）");
    assertEquals("Owner 2026-10-26 授权（含更强变形）", ledger.path("authorization").asText(), "授权记录必须原样落锚");
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

    // R3 三分支联动重算（板规 26 判据本体：verdict 与 downWeightedCount 必须机械一致）
    String verdict = anchor.path("verdict").asText();
    assertTrue(
        List.of("RANK-CHANGE", "NO-RANK-CHANGE", "INCONCLUSIVE").contains(verdict),
        "verdict 越界: " + verdict);
    int downWeightedCount = anchor.path("downWeightedCount").asInt(-1);
    assertTrue(downWeightedCount >= 0, "downWeightedCount 不得为负");
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
        "imageCandidateCount 重算不符");
    assertEquals(
        maxImageScoreDelta,
        anchor.path("maxImageScoreDelta").asDouble(),
        0D,
        "maxImageScoreDelta 重算不符");
    assertEquals(
        imageDeltaSum,
        anchor.path("imageDeltaSum").asDouble(),
        0D,
        "imageDeltaSum 重算不符（任一行 fused 手改即红）");
    boolean rankOrSetTrigger = anyImageRankChange || anySetUnequal;
    boolean flipped = anchor.path("positiveControl").path("flipped").asBoolean();
    switch (verdict) {
      case "INCONCLUSIVE" ->
          assertTrue(
              (downWeightedCount == 0
                      && "NO-IN-BAND-CANDIDATE".equals(anchor.path("missingEvidence").asText()))
                  || !flipped
                  || "R2-EQUATION-BROKEN".equals(anchor.path("missingEvidence").asText()),
              "INCONCLUSIVE 必须能指认缺失证据：dwc==0 ⇒ missingEvidence=NO-IN-BAND-CANDIDATE（板规 26），"
                  + "或正对照未翻转，或 R2 abort");
      case "RANK-CHANGE" -> {
        assertTrue(downWeightedCount > 0, "RANK-CHANGE 必须 downWeightedCount>0（板规 26 联动）");
        assertTrue(rankOrSetTrigger, "RANK-CHANGE 必须有名次变化或集合不等");
      }
      default -> {
        assertEquals("NO-RANK-CHANGE", verdict);
        assertTrue(
            downWeightedCount > 0,
            "NO-RANK-CHANGE 必须 downWeightedCount>0（板规 26：dwc==0 只能 INCONCLUSIVE）");
        assertFalse(rankOrSetTrigger, "NO-RANK-CHANGE 不得有名次变化或集合不等");
        JsonNode scoreFace = anchor.path("scoreFace");
        assertEquals(
            anyImageScoreChange, scoreFace.path("changed").asBoolean(), "scoreFace.changed 重算不符");
        JsonNode entries = anchor.path("windowCheck").path("entries");
        assertTrue(
            entries.size() >= downWeightedCount,
            "NO-RANK-CHANGE 必须附窗口算术（条目数 ≥ 降权事件数；未入 topK 事件也逐条在册）");
        assertTrue(
            anchor.path("nonExtrapolationNote").asText().contains("不可外推"),
            "NO-RANK-CHANGE 必须显式声明不可外推");
      }
    }
    if (downWeightedCount == 0) {
      assertEquals(
          "NO-IN-BAND-CANDIDATE",
          anchor.path("missingEvidence").asText(),
          "downWeightedCount==0 时 missingEvidence 必须为 NO-IN-BAND-CANDIDATE（板规 26 判据本体）");
      assertEquals("INCONCLUSIVE", verdict, "downWeightedCount==0 时只能判 INCONCLUSIVE（禁写无位移）");
    }
    assertTrue(downWeightedCount >= inTableDownWeighted, "降权事件数不得小于表内 downWeighted 行数");
    assertEquals(
        downWeightedCount, anchor.path("windowCheck").path("entries").size(), "窗口算术条目数必须恰等于降权事件数");

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

    // bandSide 六变体齐全（R5/D3：带外照记）
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

    // 腿 B 逐数字复算：锚 legB 节必须与静态算子重算逐字节一致（共享纯函数 + 源锚冻结 ⇒ 表上任何手改即红）
    assertEquals(
        MAPPER.writeValueAsString(NearDupTierFillAnalysisTest.computeTierFill()),
        MAPPER.writeValueAsString(anchor.path("legB").path("tierFill")),
        "legB.tierFill 与静态算子重算不一致（R4 表被手改或源锚漂移）");
    assertEquals(
        MAPPER.writeValueAsString(NearDupTierFillAnalysisTest.computeThresholdOptions()),
        MAPPER.writeValueAsString(anchor.path("legB").path("thresholdOptions")),
        "legB.thresholdOptions 与静态算子重算不一致（R5 选项被手改或源锚漂移）");

    // 独立向量抽查（不经 thirdImagePairs 的高层封装：裸循环复核两个锚定计数 + 带内总数一致性）
    JsonNode cross =
        NearDupTierFillAnalysisTest.readAnchor(NearDupTierFillAnalysisTest.CROSS_MATRIX_PATH);
    Map<String, double[]> vectors = NearDupTierFillAnalysisTest.vectors(cross);
    List<String> keys = new ArrayList<>();
    cross.path("keys").forEach(key -> keys.add(key.asText()));
    int ownPairs = 0;
    int thirdPairs = 0;
    int thirdInBandCurrent = 0;
    int thirdAtOrAboveReference = 0;
    for (String variantKey : keys) {
      if (!variantKey.contains("@")) {
        continue;
      }
      String ownBase = variantKey.substring(0, variantKey.indexOf('@'));
      ownPairs++;
      for (String thirdKey : keys) {
        if (thirdKey.contains("@") || thirdKey.equals(ownBase)) {
          continue;
        }
        double value =
            NearDupTierFillAnalysisTest.cosine(vectors.get(variantKey), vectors.get(thirdKey));
        thirdPairs++;
        if (value >= ImageSimilarityGate.NEAR_DUPLICATE_FLOOR
            && value < ImageSimilarityGate.DEFAULT_THRESHOLD) {
          thirdInBandCurrent++;
        }
        if (value >= ImageSimilarityGate.BAND_REFERENCE_FLOOR) {
          thirdAtOrAboveReference++;
        }
      }
    }
    assertEquals(120, ownPairs, "own-base 对独立计数必须 120");
    assertEquals(2760, thirdPairs, "third-image 对独立计数必须 2760");
    assertEquals(14, thirdAtOrAboveReference, "cos ≥ 0.9887 独立计数必须 14（源锚 md M3）");
    JsonNode totals = anchor.path("legB").path("tierFill").path("totals");
    assertEquals(
        thirdInBandCurrent, totals.path("thirdImageInBandCurrentFloor").asInt(), "现行带内对数独立重算与锚不符");

    // 腿 B 无推荐阈值（R5 grep 断言）：锚 legB 节序列化 + 结论 md 双面
    String legBSerialized = MAPPER.writeValueAsString(anchor.path("legB"));
    assertTrue(
        !legBSerialized.matches(RECOMMENDATION_WITH_VALUE), "锚 legB 节出现「推荐/建议 + 阈值数字」措辞（R5 禁式）");
    assertTrue(
        !legBSerialized.matches("(?s).*(recommend|suggest|prefer|best|chosen)[A-Za-z]*\"\\s*:.*"),
        "锚 legB 节出现 recommendation 类字段名");
    assertTrue(Files.exists(REPORT_MD), "结论 md 必须随锚交付: " + REPORT_MD);
    List<String> mdLines = Files.readAllLines(REPORT_MD, StandardCharsets.UTF_8);
    assertTrue(mdLines.size() <= 40, "结论 md 必须 ≤40 行（实测 " + mdLines.size() + "）");
    String md = Files.readString(REPORT_MD, StandardCharsets.UTF_8);
    assertTrue(!md.matches(RECOMMENDATION_WITH_VALUE), "结论 md 出现「推荐/建议 + 阈值数字」措辞（R5 禁式）");

    // 上游锚 SHA 实算（既有锚字节漂移即红；master 全等由 git diff 另行举证）
    JsonNode sourceAnchors = anchor.path("sourceAnchors");
    for (String field :
        List.of("gateProbeAnchor", "s1Anchor", "crossMatrixAnchor", "exchangeRateAnchor")) {
      Path file = Path.of(sourceAnchors.path(field).asText());
      assertTrue(Files.exists(file), "上游锚不存在: " + file);
      assertEquals(
          RealPdfAssets.sha256(Files.readAllBytes(file)),
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
        NearDupBandEntryIT.DEFAULT_VISION_MODEL,
        anchor.path("constants").path("visionModel").asText(),
        "constants.visionModel 必须等于编译期常量");
  }
}
