package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡 28 消融锚守卫（near-dup-ablation-redesign，默认测试集内跑，只读）。
 *
 * <ul>
 *   <li><b>verdict 三选一</b>：RANK-CHANGE / NO-RANK-CHANGE / INCONCLUSIVE 之外即红；与逐候选表自洽重算不符即红；
 *   <li><b>条件字段自洽</b>：maxImageScoreDelta / maxTextScoreDelta / downWeightedCount /
 *       imageCandidateCount 全部由逐候选表与 窗口算术重算比对（手改锚字段即红）；
 *   <li><b>分母实测</b>：childRows == 逐条 chunkId 列表长度 == 位移锚 corpusMaterial 行数和（跨锚重算）；embedInvocations
 *       实测正数；
 *   <li><b>正对照结论位</b>：flipped 必须为 true，翻转结构由锚内数字复核（B 跑领先被降权损失跨过）；
 *   <li><b>S0 零外呼</b>：externalCalls 必须 == 0（与位移锚守卫的「必须为正」互为镜像——S0 消融按构造零外呼）；
 *   <li><b>上游锚 SHA</b>：位移锚与融合影响锚 SHA-256 实算比对（字节漂移即红）。
 * </ul>
 */
class NearDupAblationGuardTest {

  private static final Path ANCHOR = Path.of("docs", "rag-quality", "near-dup-ablation-v1.json");

  private static final Path DISPLACEMENT_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR), "消融锚缺失（未写锚或被删）: " + ANCHOR.toAbsolutePath());
    anchor = MAPPER.readTree(Files.readAllBytes(ANCHOR));
  }

  @Test
  @DisplayName("verdict 三选一；与逐候选表/查询集自洽重算一致（手改 verdict 或数字即红）")
  void verdictConsistentWithCandidateTable() {
    String verdict = anchor.path("verdict").asText();
    List<String> allowed = List.of("RANK-CHANGE", "NO-RANK-CHANGE", "INCONCLUSIVE");
    assertTrue(allowed.contains(verdict), "verdict 越界（只允许三值）: " + verdict);

    boolean anyImageScoreChange = false;
    boolean anyImageRankChange = false;
    double maxImageScoreDelta = 0.0D;
    double maxTextScoreDelta = 0.0D;
    double imageDeltaSum = 0.0D;
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
      double delta = Math.abs(fusedA - fusedB);
      if (image) {
        if (delta > maxImageScoreDelta) {
          maxImageScoreDelta = delta;
        }
        imageDeltaSum += delta;
        if (Double.compare(fusedA, fusedB) != 0) {
          anyImageScoreChange = true;
        }
        JsonNode rankANode = candidate.path("rankA");
        JsonNode rankBNode = candidate.path("rankB");
        if (!rankANode.isNull() && !rankBNode.isNull() && rankANode.asInt() != rankBNode.asInt()) {
          anyImageRankChange = true;
        }
      } else {
        assertEquals(
            0,
            Double.compare(fusedA, fusedB),
            "非 IMAGE 候选 fusedA/fusedB 逐字节不等（R3 隔离被破坏）: " + candidate);
        if (delta > maxTextScoreDelta) {
          maxTextScoreDelta = delta;
        }
      }
    }
    boolean anySetUnequal = false;
    for (JsonNode query : anchor.path("queries")) {
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
        "maxImageScoreDelta 与逐候选表重算不符（手改即红）");
    assertEquals(
        imageDeltaSum,
        anchor.path("imageDeltaSum").asDouble(),
        "imageDeltaSum（逐行 delta 之和）与逐候选表重算不符——max 聚合对非最大行有盲区，" + "任一行 fused 值被手改都会移动本和（红）");
    assertEquals(
        maxTextScoreDelta,
        anchor.path("maxTextScoreDelta").asDouble(),
        "maxTextScoreDelta 与逐候选表重算不符（手改即红）");
    assertTrue(
        anchor.path("downWeightedCount").asInt(-1) >= inTableDownWeighted,
        "downWeightedCount（降权事件数）不得小于逐候选表内 downWeighted 行数（未入 topK 的事件也在内）");
    assertTrue(
        anchor.path("downWeightedCount").asInt(-1) > 0 || !anyImageScoreChange,
        "存在 IMAGE 分数变化就必须发生过降权事件");

    boolean trigger = anyImageScoreChange || anyImageRankChange || anySetUnequal;
    switch (verdict) {
      case "RANK-CHANGE" -> assertTrue(trigger, "RANK-CHANGE 必须有 IMAGE 分数/名次变化或集合不等（重算全否）");
      case "NO-RANK-CHANGE" -> {
        assertFalse(trigger, "NO-RANK-CHANGE 不得有任何 IMAGE 分数/名次变化或集合不等");
        assertEquals(
            0.0D,
            anchor.path("maxImageScoreDelta").asDouble(),
            "NO-RANK-CHANGE 必须 maxImageScoreDelta==0");
        assertTrue(
            anchor.path("nonExtrapolationNote").asText().contains("不可外推"),
            "NO-RANK-CHANGE 必须显式声明不可外推为「生产无位移」");
      }
      default ->
          assertFalse(
              anchor.path("verdictReason").asText().isBlank(),
              "INCONCLUSIVE 必须附缺哪条证据（verdictReason）");
    }
  }

  @Test
  @DisplayName("NO-RANK-CHANGE 必附逐条窗口算术；窗口条目数 == downWeightedCount（降权事件逐条在册）")
  void windowArithmeticPresentPerDownWeightedEvent() {
    int downWeightedCount = anchor.path("downWeightedCount").asInt(-1);
    JsonNode entries = anchor.path("windowCheck").path("entries");
    assertTrue(downWeightedCount >= 0, "downWeightedCount 缺失");
    assertEquals(downWeightedCount, entries.size(), "窗口算术条目数必须等于降权事件数（逐条被降权候选 gap vs 0.30×(1−w)）");
    for (JsonNode entry : entries) {
      double weight = entry.path("weight").asDouble(-1D);
      assertTrue(weight > 0D && weight < 1D, "降权权重必须在 (0,1) 内: " + entry);
      double window = entry.path("window").asDouble();
      assertEquals(
          anchor.path("productionConstants").path("imageRouteWeight").asDouble() * (1D - weight),
          window,
          1e-12D,
          "window 必须等于 imageRouteWeight × (1 − w): " + entry);
    }
    assertTrue(anchor.path("windowCheck").path("note").asText().contains("0.30"), "窗口算术口径说明必须落锚");
  }

  @Test
  @DisplayName("分母实测：childRows == 逐条 chunkId 数 == 位移锚 corpusMaterial 行数和；embedInvocations 实测正数")
  void denominatorsMeasuredNotDerived() throws Exception {
    JsonNode denominators = anchor.path("denominators");
    int childRows = denominators.path("childRows").asInt(-1);
    assertTrue(childRows > 0, "childRows 必须实测正数");
    JsonNode chunkIds = denominators.path("childRowChunkIds");
    assertEquals(childRows, chunkIds.size(), "childRows 必须等于逐条 chunkId 列表长度");
    for (JsonNode chunkId : chunkIds) {
      assertFalse(chunkId.asText().isBlank(), "childRowChunkIds 不得含空条目");
    }
    JsonNode displacement = MAPPER.readTree(Files.readAllBytes(DISPLACEMENT_ANCHOR));
    int expected =
        displacement.path("corpusMaterial").path("imageRecords").size()
            + displacement.path("corpusMaterial").path("textRecords").size();
    assertEquals(expected, childRows, "childRows 与位移锚 corpusMaterial 行数和不符（跨锚重算）");
    assertTrue(
        denominators.path("embedInvocations").asInt(-1) > 0, "embedInvocations 必须实测正数（按未命中计的嵌入请求）");
    assertTrue(
        denominators.path("childRowsVsEmbedInvocationsReason").asText().contains("实测"),
        "两数不等原因必须落锚且声明无推导");
  }

  @Test
  @DisplayName("正对照结论位：flipped=true 且翻转结构由锚内数字复核（0.225s vs 0.3s 跨过 B 跑领先）")
  void positiveControlSlotConsistent() {
    JsonNode positive = anchor.path("positiveControl");
    assertTrue(positive.path("flipped").asBoolean(), "正对照未翻转（装置无鉴别力）——本锚一切结论无效");
    double nearHighWeight = positive.path("nearHighWeight").asDouble();
    assertEquals(0.75D, nearHighWeight, 1e-12D, "正对照档位必须 NEAR_HIGH 的 0.75");
    double withoutDownWeight = positive.path("fusedNearHighWithoutDownWeight").asDouble();
    double withDownWeight = positive.path("fusedNearHighWithDownWeight").asDouble();
    double nonNearWithout = positive.path("fusedNonNearImageWithoutDownWeight").asDouble();
    double nonNearWith = positive.path("fusedNonNearImageWithDownWeight").asDouble();
    assertEquals(
        positive.path("nearHighInputScore").asDouble()
            * anchor.path("productionConstants").path("imageRouteWeight").asDouble(),
        withoutDownWeight,
        "正对照 B 跑融合分必须 == s×0.3（未降权，逐字节）");
    assertEquals(
        (positive.path("nearHighInputScore").asDouble() * nearHighWeight)
            * anchor.path("productionConstants").path("imageRouteWeight").asDouble(),
        withDownWeight,
        "正对照 A 跑融合分必须 == (s×0.75)×0.3（降权，逐字节）");
    assertTrue(withoutDownWeight > nonNearWithout, "B 跑（不降权）该图像候选必须排前（领先非近似图像）");
    assertTrue(withDownWeight < nonNearWith, "A 跑（降权）该图像候选必须排后");
    assertTrue(
        positive.path("rankNearHighWithDownWeight").asInt()
            > positive.path("rankNearHighWithoutDownWeight").asInt(),
        "正对照名次必须 A 降权后更靠后");
  }

  @Test
  @DisplayName("S0 零外呼对账：externalCalls 必须 == 0 且结构依据落锚（与位移锚守卫的必须为正互为镜像）")
  void externalCallsMustBeZero() {
    assertEquals(0, anchor.path("externalCalls").asInt(-1), "S0 消融必须零外呼（externalCalls==0）");
    String basis = anchor.path("zeroExternalCallBasis").asText();
    assertTrue(basis.contains("未构造真实 provider"), "零外呼结构依据必须落锚（未构造真实 provider）");
    assertTrue(basis.contains("downWeightNearDuplicates"), "零外呼结构依据必须声明 B 跑只替换一个方法");
  }

  @Test
  @DisplayName("上游两锚 SHA-256 实算比对（位移锚与融合影响锚字节漂移即红）")
  void upstreamAnchorShasRecomputed() throws Exception {
    JsonNode sourceAnchors = anchor.path("sourceAnchors");
    for (String field : List.of("displacementEvidenceAnchor", "fusionImpactAnchor")) {
      String relative = sourceAnchors.path(field).asText();
      assertFalse(relative.isBlank(), "锚缺 sourceAnchors." + field);
      Path file = Path.of(relative);
      assertTrue(Files.exists(file), "上游锚不存在: " + file);
      String expected = sourceAnchors.path(field + "Sha256").asText();
      assertEquals(
          sha256(Files.readAllBytes(file)), expected, "上游锚 SHA-256 与实算不一致（手抄或文件漂移）: " + relative);
    }
    assertEquals("OWNER", anchor.path("decisionOwner").asText(), "decisionOwner 必须是 OWNER");
    for (String criterion : List.of("R0", "R1", "R2", "R3", "R4", "R5", "R6")) {
      assertFalse(
          anchor.path("criteria").path(criterion).asText().isBlank(),
          "锚缺判据 " + criterion + "（判据原文必须落锚）");
    }
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
