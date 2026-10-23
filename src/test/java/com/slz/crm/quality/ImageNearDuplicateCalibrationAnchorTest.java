package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 已提交 v2 锚的结构自检（calibrate-near-dup-bands 任务 5.1，surefire 默认跑、<b>零外呼</b>）。
 *
 * <p><b>为什么要有这道闸</b>：v2 锚是 IT 一次性写出的真跑产物，{@code docs/rag-quality/image-near-duplicate-bands-v2.md}
 * 的每个数字都 由它复算而来。若有人手改锚（改一个计数、删一对样本）或 IT 的配对口径被改坏，<b>默认 {@code mvn test} 必须立刻红</b>——而不是等下一次真跑 花 288
 * 次外呼才发现。本类只读锚、不写锚、不联网。
 *
 * <p><b>断言口径</b>：base 数按 SHA-256 去重后是<b>实际值</b>（可能少于计划值），所以断言<b>关系式</b>而不是魔法数： {@code nearDuplicate
 * = bases × 5}、{@code same-image = images}、{@code cross = bases×(bases−1)/2}、 {@code pairs =
 * 三者之和}；只有下限（{@code bases >= 20}）作为语料规模的兜底。
 *
 * <p><b>余弦上界的诚实说明</b>：{@code ImageCrossModalEvaluator#cosine} 是浮点计算，同字节再编码实测可得到 {@code
 * 1.0000000000000002}（v1 锚 {@code sameImage.max} 就是这个值）。因此本类断言 {@code 0 < cosine <= 1 +
 * 1e-9}，<b>不</b>把浮点舍入 当成缺陷去篡改原始观测，也不放宽到"能装下任何数"。
 */
@DisplayName("v2 近重复定标锚结构自检（零外呼）")
class ImageNearDuplicateCalibrationAnchorTest {

  /** v2 锚路径（IT 的唯一产物；本类只读）。 */
  static final Path ANCHOR_PATH =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** 期望版本号（与 v1 的 {@code IMAGE-THRESHOLD-CALIBRATION-1.0} 并列）。 */
  static final String EXPECTED_VERSION = "IMAGE-NEAR-DUPLICATE-CALIBRATION-2.0";

  /** 合法档位集合。 */
  static final Set<String> STRATA =
      Set.of("same-image", "near-duplicate", "same-class", "cross-class");

  /** 余弦上界的浮点容差（IEEE 双精度累加误差的量级，不是"随便给个数"）。 */
  private static final double COSINE_TOLERANCE = 1e-9D;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws IOException {
    assertTrue(
        Files.exists(ANCHOR_PATH),
        "v2 锚不存在（必须由 ImageNearDuplicateCalibrationIT 真跑写出）: " + ANCHOR_PATH.toAbsolutePath());
    anchor = MAPPER.readTree(Files.readString(ANCHOR_PATH));
  }

  @Test
  @DisplayName("①版本号与生成时间：v2 锚独立于 v1，且带 opt-in 留痕")
  void versionAndProvenance() {
    assertEquals(EXPECTED_VERSION, anchor.path("calibrationVersion").asText(), "锚版本号必须是 v2");
    assertTrue(anchor.path("generatedAt").asText().length() >= 20, "generatedAt 必须是 ISO-8601 时间");
    String optIn = anchor.path("optIn").asText();
    assertTrue(optIn.contains("RAG_VISION_EMBED_REAL"), "optIn 留痕必须写明真跑 opt-in 键");
    assertTrue(optIn.contains("SHA-256"), "optIn 留痕必须写明去重口径");
    assertTrue(anchor.path("corpus").path("imageEmbeddingCalls").asInt() > 0, "调用次数必须留痕");
  }

  @Test
  @DisplayName("②关系式自洽：对数由 base / images 唯一决定，不靠魔法数")
  void pairCountsAreRelational() {
    JsonNode corpus = anchor.path("corpus");
    int bases = corpus.path("bases").asInt();
    int images = corpus.path("images").asInt();
    int variants = corpus.path("variants").asInt();
    assertTrue(bases >= 20, "base 数少于 20：语料规模不足以定标（实际 " + bases + "）");
    assertEquals(bases + variants, images, "images 必须 = bases + variants");
    assertEquals(
        bases * NearDuplicateCorpus.KINDS.size(),
        count("near-duplicate"),
        "近重复档对数必须 = bases × KINDS.size()");
    assertEquals(images, count("same-image"), "同图档对数必须 = images（每图一对 A/B 再编码）");
    assertEquals(
        bases * (bases - 1) / 2,
        count("same-class") + count("cross-class"),
        "cross 对数必须 = bases×(bases−1)/2（只取 base 两两）");
    assertEquals(
        images + bases * NearDuplicateCorpus.KINDS.size() + bases * (bases - 1) / 2,
        anchor.path("pairs").size(),
        "pairs 长度必须 = 三层对数之和");
    assertEquals(
        corpus.path("skips").asInt() + variants,
        bases * NearDuplicateCorpus.KINDS.size(),
        "变体 + 跳过必须 = base × KINDS.size()");
  }

  @Test
  @DisplayName("③档位取值集合 ⊆ {same-image, near-duplicate, same-class, cross-class}")
  void strataAreKnown() {
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode pair : anchor.path("pairs")) {
      String stratum = pair.path("stratum").asText(null);
      assertNotNull(stratum, "每对必须有 stratum");
      assertTrue(STRATA.contains(stratum), "未知档位: " + stratum);
      seen.add(stratum);
    }
    assertEquals(STRATA, seen, "四档必须都出现（缺档说明语料结构塌了）");
    for (String stratum : STRATA) {
      assertTrue(anchor.path("strata").has(stratum), "strata 块缺档位: " + stratum);
      assertEquals(
          count(stratum),
          anchor.path("strata").path(stratum).path("count").asInt(),
          "strata 计数必须与 pairs 实际一致: " + stratum);
    }
  }

  @Test
  @DisplayName("④近重复对可回指：rightKey 前缀 = leftKey（变体键 = baseKey@kind）")
  void nearDuplicatePairsPointBackToBase() {
    Set<String> baseKeys = new LinkedHashSet<>();
    List<String> kinds = new ArrayList<>();
    for (JsonNode pair : anchor.path("pairs")) {
      if ("near-duplicate".equals(pair.path("stratum").asText())) {
        String left = pair.path("leftKey").asText();
        String right = pair.path("rightKey").asText();
        String kind = pair.path("kind").asText(null);
        assertTrue(right.startsWith(left + "@"), "变体键必须以 baseKey@ 开头: " + left + " → " + right);
        assertNotNull(kind, "近重复对必须标明变体类型: " + right);
        assertEquals(left + "@" + kind, right, "变体键必须 = baseKey@kind");
        baseKeys.add(left);
        kinds.add(kind);
      }
    }
    assertFalse(baseKeys.isEmpty(), "近重复对为空：定标无从谈起");
    for (String kind : NearDuplicateCorpus.KINDS) {
      assertTrue(kinds.contains(kind), "变体类型必须每类都有样本: " + kind);
    }
  }

  @Test
  @DisplayName("⑤余弦取值：0 < cosine <= 1 + 1e-9（浮点累加误差量级）")
  void cosinesAreInRange() {
    int checked = 0;
    for (JsonNode pair : anchor.path("pairs")) {
      JsonNode cosine = pair.get("cosine");
      assertNotNull(cosine, "每对必须有余弦");
      assertTrue(cosine.isNumber(), "余弦必须是数值");
      double value = cosine.asDouble();
      assertTrue(value > 0D, "余弦必须为正（零范数不应出现在真视觉向量上）: " + value);
      assertTrue(
          value <= 1D + COSINE_TOLERANCE,
          "余弦不得超过 1（浮点误差量级内）: " + value + " @ " + pair.path("rightKey").asText());
      checked++;
    }
    assertEquals(anchor.path("pairs").size(), checked, "所有样本对都必须被检查到");
  }

  @Test
  @DisplayName("⑥落带与判定树自洽：双视图计数闭合、阈值口径、候选下限、六支结论")
  void viewsAndJudgementsAreConsistent() {
    JsonNode production = anchor.path("productionView");
    assertEquals(0.9887D, production.path("floor").asDouble(), 0D, "生产视图 floor 必须仍取闸门常量");
    assertEquals(0.9944D, production.path("threshold").asDouble(), 0D, "生产视图 threshold 必须仍取闸门常量");

    JsonNode anchorView = anchor.path("anchorView");
    JsonNode sameImage = anchor.path("strata").path("same-image");
    double sameImageFloor = sameImage.path("min").asDouble();
    double ceiling =
        Math.max(
            anchor.path("strata").path("same-class").path("max").asDouble(),
            anchor.path("strata").path("cross-class").path("max").asDouble());
    assertEquals(ceiling, anchorView.path("floor").asDouble(), 1e-12D, "锚视图 floor 必须是实测异图上限");
    if (ceiling < sameImageFloor) {
      assertEquals(
          (ceiling + sameImageFloor) / 2D,
          anchorView.path("threshold").asDouble(),
          1e-12D,
          "锚视图阈值必须是上限与下限的中点");
      assertTrue(anchorView.path("separable").asBoolean(), "可分时 separable 必须为 true");
    } else {
      assertTrue(anchorView.path("threshold").isNull(), "不可分时不得硬凑阈值");
      assertFalse(anchorView.path("separable").asBoolean(), "不可分必须如实标注");
    }

    for (String view : List.of("productionView", "anchorView")) {
      JsonNode node = anchor.path(view);
      int sum = 0;
      for (JsonNode band : node.path("bands")) {
        sum += band.path("count").asInt();
        assertTrue(
            band.path("count").asInt() >= band.path("nearDuplicateCount").asInt(),
            "带内近重复对数不得超过该带总数: " + view + "/" + band.path("band").asText());
      }
      if (node.path("separable").asBoolean()) {
        assertEquals(node.path("pairCount").asInt(), sum, "五带对数之和必须等于样本对总数: " + view);
      }
    }

    double p05 = anchor.path("strata").path("near-duplicate").path("p05").asDouble();
    assertEquals(
        p05,
        anchor.path("candidateFloor").asDouble(),
        1e-12D,
        "candidateFloor 必须就是 near-duplicate 档 p05");

    JsonNode judgements = anchor.path("judgements");
    assertEquals(6, judgements.size(), "判定树必须 F0–F5 六支齐全");
    List<String> codes = new ArrayList<>();
    for (JsonNode judgement : judgements) {
      codes.add(judgement.path("code").asText());
      assertFalse(judgement.path("condition").asText().isBlank(), "每支必须写明条件");
      assertFalse(judgement.path("evidence").asText().isBlank(), "每支必须给出证据");
    }
    assertEquals(List.of("F0", "F1", "F2", "F3", "F4", "F5"), codes, "分支编号与顺序必须与 proposal §4 一致");
  }

  /** 锚里某档位的样本对数。 */
  private static int count(String stratum) {
    int total = 0;
    for (JsonNode pair : anchor.path("pairs")) {
      if (stratum.equals(pair.path("stratum").asText())) {
        total++;
      }
    }
    return total;
  }
}
