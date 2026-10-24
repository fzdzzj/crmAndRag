package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 消融锚守卫（near-dup-ingest-cooccurrence 任务 C.22 + D.24，默认测试集内跑，只读，零外呼）。
 *
 * <ul>
 *   <li><b>选取规则零外呼复验</b>：重新生成语料 + 选取，断言 48 图 / 7 带内对全覆盖 / ≥6 非带内变体（带内覆盖 ≥7 由选取实读得出）；
 *   <li><b>锚对账</b>：上游两锚 SHA 实算、verdict 三选一且与 displacedEvidence/coverage 自洽、externalCalls 实测正数且
 *       ≤144、ingestion 四值对账（N == N_pass == imageVectorCount == descriptionChunkCount ==
 *       queryVectors）；
 *   <li><b>R5 一致性由数据重算</b>：逐候选闭式校验（带内成员 fusedA == 0.7t + 0.3·img·w；其余成员 fusedA == fusedB；
 *       纯文本候选两模式同分），越 ε 即红。
 * </ul>
 */
class NearDupIngestCooccurrenceGuardTest {

  private static final Path ANCHOR = NearDupIngestCooccurrenceIT.ANCHOR_PATH;

  private static final Path CALIBRATION_ANCHOR =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  private static final Path DISPLACEMENT_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws Exception {
    if (Files.exists(ANCHOR)) {
      anchor = MAPPER.readTree(Files.readAllBytes(ANCHOR));
    }
  }

  @Test
  @DisplayName("选取规则复验（零外呼）：48 图 / 带内 7 对参与者全覆盖 / ≥6 非带内变体")
  void selectionRuleReverified() throws Exception {
    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    NearDupIngestCooccurrenceIT.Selection selection =
        NearDupIngestCooccurrenceIT.selectImagesInGuard(corpus);
    assertEquals(48, selection.images().size(), "入选图必须 48");
    assertEquals(7, selection.bandPairs().size(), "带内对必须 7");
    assertTrue(
        selection.nonBandVariantCount() >= 6, "非带内变体必须 ≥6，实测 " + selection.nonBandVariantCount());
    Set<String> chosen = new HashSet<>();
    selection.images().forEach(image -> chosen.add(image.assetKey()));
    for (NearDupIngestCooccurrenceIT.BandPair pair : selection.bandPairs()) {
      assertTrue(chosen.contains(pair.baseKey()), "带内 base 未入选: " + pair.baseKey());
      assertTrue(chosen.contains(pair.variantKey()), "带内 variant 未入选: " + pair.variantKey());
    }
    if (anchor != null) {
      assertEquals(
          anchor.path("corpus").path("selectedCount").asInt(-1),
          selection.images().size(),
          "锚 corpus.selectedCount 与复验选取漂移");
      assertEquals(
          anchor.path("corpus").path("nonBandVariantCount").asInt(-1),
          selection.nonBandVariantCount(),
          "锚 corpus.nonBandVariantCount 与复验选取漂移");
    }
  }

  @Test
  @DisplayName("S1 锚对账：上游 SHA 实算 + verdict 三选一自洽 + 外呼实测 ≤144 + ingestion 四值对账")
  void anchorAccounting() throws Exception {
    assertNotNull(anchor, "S1 锚缺失（未真跑或被删）: " + ANCHOR.toAbsolutePath());
    JsonNode sourceAnchors = anchor.path("sourceAnchors");
    assertEquals(
        RealPdfAssets.sha256(Files.readAllBytes(CALIBRATION_ANCHOR)),
        sourceAnchors.path("calibrationAnchorSha256").asText(),
        "定标锚 SHA-256 与实算不一致");
    assertEquals(
        RealPdfAssets.sha256(Files.readAllBytes(DISPLACEMENT_ANCHOR)),
        sourceAnchors.path("competitorAnchorSha256").asText(),
        "竞争者锚 SHA-256 与实算不一致");

    String verdict = anchor.path("verdict").asText("");
    assertTrue(
        "DISPLACED-IN-FUSION".equals(verdict)
            || "NOT-DISPLACED-IN-FUSION".equals(verdict)
            || "INSUFFICIENT-COVERAGE".equals(verdict),
        "verdict 必须三选一，实测 " + verdict);

    int externalCalls = anchor.path("externalCalls").asInt(-1);
    assertTrue(externalCalls > 0, "externalCalls 必须是实测正数（写 0 即假绿），实测 " + externalCalls);
    assertTrue(externalCalls <= NearDupIngestCooccurrenceIT.S1_CALL_CAP, "S1 外呼实测超硬帽 144");
    JsonNode calls = anchor.path("callBreakdown");
    assertEquals(
        externalCalls,
        calls.path("visionCalls").asInt(-1)
            + calls.path("embedImageCalls").asInt(-1)
            + calls.path("embedCalls").asInt(-1),
        "callBreakdown 三分项之和必须等于 externalCalls");
    // embed 计数对账（由锚数据重算）：去重描述文本数 + 拆分子查询预热数（重复描述缓存命中零边际外呼）
    java.util.Set<String> seenTexts = new java.util.LinkedHashSet<>();
    for (JsonNode record : anchor.path("corpusMaterial").path("textRecords")) {
      seenTexts.add(record.path("text").asText());
    }
    assertEquals(
        seenTexts.size() + calls.path("splitRoutePreembeds").asInt(-1),
        calls.path("embedCalls").asInt(-1),
        "embed 外呼必须等于去重描述文本数 + 拆分子查询预热数");

    JsonNode ingestion = anchor.path("ingestion");
    int n = ingestion.path("N").asInt(-1);
    int nPass = ingestion.path("N_pass").asInt(-1);
    assertEquals(48, n, "N 必须是入选图数 48");
    assertTrue(
        ingestion.path("N_extracted").asInt(-1) <= n && ingestion.path("N_extracted").asInt(-1) > 0,
        "N_extracted 必须落在 (0, N]：实测 " + ingestion.path("N_extracted").asInt(-1));
    assertEquals(
        ingestion.path("extractionDropped").asInt(-1)
            + ingestion.path("gateRejected").asInt(-1)
            + nPass,
        n,
        "三态计数之和必须等于入选图数");
    assertEquals(
        calls.path("visionCalls").asInt(-1),
        ingestion.path("N_extracted").asInt(-1),
        "vision 外呼必须等于被生产提取器看到的图数（提取丢弃不耗外呼）");
    assertEquals(nPass, ingestion.path("imageVectorCount").asInt(-1), "image 集合向量数必须等于 N_pass");
    assertEquals(nPass, ingestion.path("descriptionChunkCount").asInt(-1), "描述 chunk 数必须等于 N_pass");
    assertEquals(
        nPass,
        anchor.path("corpusMaterial").path("queryVectors").size(),
        "queryVectors 数必须等于 N_pass");
    assertEquals(
        ingestion.path("imageVectorCount").asInt(-1),
        anchor.path("corpusMaterial").path("imageRecords").size(),
        "imageRecords 数与 imageVectorCount 不一致");
    assertEquals(
        n, anchor.path("corpusMaterial").path("imageStates").size(), "imageStates 必须逐图覆盖入选集");

    int covered = anchor.path("coverage").path("bandPairsCovered").asInt(-1);
    assertTrue(covered >= 0 && covered <= 7, "带内覆盖计数越界: " + covered);
    int displacedPairs = anchor.path("displacedEvidence").size();
    if ("DISPLACED-IN-FUSION".equals(verdict)) {
      assertTrue(displacedPairs > 0, "DISPLACED 必须有逐条证据");
    } else {
      assertEquals(0, displacedPairs, "非 DISPLACED 结论不得有 displacedEvidence");
      if ("NOT-DISPLACED-IN-FUSION".equals(verdict)) {
        assertEquals(7, covered, "NOT-DISPLACED 必须带内 7 对全覆盖（否则只能判 INSUFFICIENT-COVERAGE）");
      } else {
        assertTrue(covered < 7, "INSUFFICIENT-COVERAGE 必须覆盖不足");
      }
    }
  }

  @Test
  @DisplayName("R5 一致性由锚数据重算：逐候选闭式校验（带内 0.7t+0.3·img·w；其余档 fusedA==fusedB；纯文本两模式同分）")
  void r5ConsistencyRecomputed() throws Exception {
    assertNotNull(anchor, "S1 锚缺失: " + ANCHOR.toAbsolutePath());
    double epsilon = anchor.path("r5").path("epsilon").asDouble(1e-9);
    double maxDeviation = 0D;
    int checked = 0;
    for (JsonNode query : anchor.path("queries")) {
      for (JsonNode candidate : query.path("candidates")) {
        boolean imageMember = candidate.path("imageRouteMember").asBoolean();
        double fusedA = candidate.path("fusedScoreA").asDouble();
        double fusedB = candidate.path("fusedScoreB").asDouble();
        JsonNode textScoreNode = candidate.path("textRouteScore");
        Double textScore =
            textScoreNode.isMissingNode() || textScoreNode.isNull()
                ? null
                : textScoreNode.asDouble();
        if (imageMember) {
          JsonNode tierNode = candidate.path("tier");
          JsonNode weightNode = candidate.path("weightA");
          JsonNode imageScoreNode = candidate.path("imageRouteScore");
          assertFalse(
              imageScoreNode.isMissingNode() || imageScoreNode.isNull(), "图像成员缺 imageRouteScore");
          double imageScore = imageScoreNode.asDouble();
          if (!tierNode.isNull()
              && "NEAR_DUPLICATE".equals(tierNode.asText())
              && !weightNode.isNull()) {
            double weight = weightNode.asDouble();
            double predicted =
                textScore == null
                    ? 0.3D * imageScore * weight
                    : 0.7D * textScore + 0.3D * imageScore * weight;
            maxDeviation = Math.max(maxDeviation, Math.abs(fusedA - predicted));
            checked++;
          } else {
            maxDeviation = Math.max(maxDeviation, Math.abs(fusedA - fusedB));
            checked++;
          }
        } else {
          if (textScore != null) {
            maxDeviation = Math.max(maxDeviation, Math.abs(fusedA - 0.7D * textScore));
            maxDeviation = Math.max(maxDeviation, Math.abs(fusedB - 0.7D * textScore));
            checked += 2;
          } else {
            maxDeviation = Math.max(maxDeviation, Math.abs(fusedA - fusedB));
            checked++;
          }
        }
        // rankA/rankB 双有值且落在各自候选数内（R3 集合一致的记录面）
        assertTrue(
            candidate.path("rankA").asInt(-1) >= 0 && candidate.path("rankB").asInt(-1) >= 0,
            "候选缺 rankA/rankB: " + candidate.path("chunkId").asText());
      }
    }
    assertTrue(checked > 0, "锚无任何候选可校验");
    assertTrue(
        maxDeviation <= epsilon,
        "R5 重算红：最大残差 " + maxDeviation + " > ε " + epsilon + "（锚数据被改或消融不纯）");
    assertEquals(
        checked,
        anchor.path("r5").path("checkedCandidates").asInt(-1),
        "r5.checkedCandidates 与重算值不一致");
    assertTrue(anchor.path("r5").path("consistent").asBoolean(false), "r5.consistent 必须为 true");
  }
}
