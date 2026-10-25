package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 腿 B 档位填充盘点（near-dup-band-entry-and-tier-fill 任务 D.10/D.11，surefire，<b>零外呼</b>，只统计不推断）。
 *
 * <p><b>数据源全部冻结</b>：{@code image-variant-cross-matrix-v1.json}（144 向量 / 2760 对变体×第三图，anchor 内含
 * {@code vectors} 原始向量，可零外呼复算）、{@code near-dup-exchange-rate-v1.json}（floor 价目表）、{@code
 * near-dup-ingest-gate-probe-v1.json}（crop-04 带内实证）。产出「编辑类型 × cos(min/median/max) × 命中档位 × 带内对数」表与
 * 0.90 议题的选项/代价清单；{@link #computeTierFill()} / {@link #computeThresholdOptions()} 为纯静态算子， 供腿 A
 * 锚（{@code NearDupBandEntryIT}）与守卫（{@code NearDupBandEntryGuardTest}）共用同一实现。
 *
 * <p><b>边界（R4/R5）</b>：档位边界一律直读生产常量（{@link ImageSimilarityGate}）现算，锚内不抄数字；表上禁因果推断与阈值主张； 0.90
 * 议题只列选项与代价，<b>不给推荐阈值、不改任何常量</b>（守卫 grep 断言）。proposal R4 括注「396 对」经实测核对为源锚 M2
 * 偏差对账对数（120+100+176），变体×第三图全域实为 2760 对——如实落 {@code pairCountClarification}，判据语料域不变。
 */
class NearDupTierFillAnalysisTest {

  /** 交叉矩阵锚（144 向量冻结，零外呼复算基底）。 */
  static final Path CROSS_MATRIX_PATH =
      Path.of("docs", "rag-quality", "image-variant-cross-matrix-v1.json");

  /** 交换率价目表锚（R5 选项与代价的 own-base 侧来源）。 */
  static final Path EXCHANGE_RATE_PATH =
      Path.of("docs", "rag-quality", "near-dup-exchange-rate-v1.json");

  /** 0.90 议题的选项值（R5 原文口径之二；0.9887 = BAND_REFERENCE_FLOOR 直读）。 */
  static final double OPTION_FLOOR_090 = 0.90D;

  /** 源锚分位对账容差（Java 双精度求和顺序差异的吸收面；源锚 md 同数量级披露）。 */
  static final double QUANTILE_TOLERANCE = 1e-9D;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("源锚完整性：向量数/对数/分位/带内 14 对全部复算对上（零外呼）")
  void sourceAnchorIntegrity() throws Exception {
    JsonNode root = readAnchor(CROSS_MATRIX_PATH);
    assertEquals(144, root.path("vectors").size(), "源锚必须冻结 144 条向量");
    assertEquals(144, root.path("keys").size(), "源锚必须冻结 144 个键");
    assertEquals(24, root.path("corpus").path("bases").asInt(), "源锚 base 数必须 24");
    assertEquals(120, root.path("corpus").path("variants").asInt(), "源锚变体数必须 120");
    Map<String, double[]> vectors = vectors(root);
    List<Double> thirdPairs = thirdImagePairs(root, vectors);
    JsonNode summary = root.path("variantVsThirdImage");
    assertEquals(
        summary.path("count").asInt(), thirdPairs.size(), "变体×第三图复算对数必须等于源锚 count（120×23=2760）");
    double[] sorted = sortedCosines(thirdPairs);
    assertEquals(
        summary.path("p50").asDouble(), nearestRank(sorted, 0.5D), QUANTILE_TOLERANCE, "p50 复算不一致");
    assertEquals(
        summary.path("p90").asDouble(), nearestRank(sorted, 0.9D), QUANTILE_TOLERANCE, "p90 复算不一致");
    assertEquals(
        summary.path("p99").asDouble(),
        nearestRank(sorted, 0.99D),
        QUANTILE_TOLERANCE,
        "p99 复算不一致");
    assertEquals(
        summary.path("maxVariantVsThird").asDouble(),
        sorted[sorted.length - 1],
        QUANTILE_TOLERANCE,
        "max 复算不一致");
    int atOrAboveBandReference = 0;
    for (double value : sorted) {
      if (value >= ImageSimilarityGate.BAND_REFERENCE_FLOOR) {
        atOrAboveBandReference++;
      }
    }
    assertEquals(14, atOrAboveBandReference, "cos ≥ 0.9887 的变体×第三图对必须恰 14（源锚 md M3 实测）");
    assertTrue(
        summary.path("hasCosAtOrAboveFloor").asBoolean(),
        "源锚 hasCosAtOrAboveFloor 必须为 true（与 14 对一致）");
  }

  @Test
  @DisplayName("档位填充表：逐行 pair 数与档位计数自洽、总对数 120/2760、现行带内 22 对")
  void tierFillTableSumsAndTiers() throws Exception {
    ObjectNode tierFill = computeTierFill();
    assertEquals(
        120, tierFill.path("totals").path("ownBasePairs").asInt(), "own-base 侧必须 120 对（24×5）");
    assertEquals(
        2760,
        tierFill.path("totals").path("thirdImagePairs").asInt(),
        "third-image 侧必须 2760 对（120×23）");
    for (String side : List.of("ownBaseByKind", "thirdImageByKind")) {
      ArrayNode rows = (ArrayNode) tierFill.path(side);
      assertEquals(5, rows.size(), side + " 必须 5 行（编辑类型全序）");
      int pairSum = 0;
      for (JsonNode row : rows) {
        int tiers =
            row.path("sameImage").asInt()
                + row.path("bandNearHigh").asInt()
                + row.path("bandNearMid").asInt()
                + row.path("bandNearLow").asInt()
                + row.path("belowFloor").asInt();
        assertEquals(row.path("pairs").asInt(), tiers, side + " 行档位计数之和必须等于 pair 数");
        assertEquals(
            row.path("bandNearHigh").asInt()
                + row.path("bandNearMid").asInt()
                + row.path("bandNearLow").asInt(),
            row.path("inBand").asInt(),
            side + " 行 inBand 必须等于三档之和");
        assertTrue(row.path("cosMin").asDouble() <= row.path("cosMedian").asDouble(), "min≤median");
        assertTrue(row.path("cosMedian").asDouble() <= row.path("cosMax").asDouble(), "median≤max");
        pairSum += row.path("pairs").asInt();
      }
      assertEquals(
          "ownBaseByKind".equals(side) ? 120 : 2760, pairSum, side + " 五行 pair 数总和必须等于全域对数");
    }
    assertEquals(
        22,
        tierFill.path("totals").path("thirdImageInBandCurrentFloor").asInt(),
        "现行生产带 [floor,0.9944) 内的变体×第三图对必须 22（向量复算）");
    assertEquals(
        14,
        tierFill.path("totals").path("thirdImageAtOrAboveBandReference").asInt(),
        "cos ≥ 0.9887 对数必须 14（源锚 md M3 同值）");
  }

  @Test
  @DisplayName("0.90 议题：恰两选项（0.9887/0.90）、新增对数可复算、无推荐阈值措辞（R5）")
  void thresholdOptionsShapeAndNoRecommendation() throws Exception {
    ObjectNode options = computeThresholdOptions();
    ArrayNode rows = (ArrayNode) options.path("options");
    assertEquals(2, rows.size(), "R5 只列两口径：维持 0.9887 / 降到 0.90");
    assertEquals(
        ImageSimilarityGate.BAND_REFERENCE_FLOOR,
        rows.get(0).path("floor").asDouble(),
        0D,
        "选项一 floor=0.9887");
    assertEquals(OPTION_FLOOR_090, rows.get(1).path("floor").asDouble(), 0D, "选项二 floor=0.90");
    int keptMantle = rows.get(0).path("ownBaseKept").asInt();
    int kept090 = rows.get(1).path("ownBaseKept").asInt();
    assertEquals(
        kept090 - keptMantle,
        options.path("newEntrantsFromMantleTo090").path("ownBase").asInt(),
        "own-base 新增进带必须等于两口径保留数之差");
    assertEquals(
        rows.get(1).path("thirdImageInBand").asInt() - rows.get(0).path("thirdImageInBand").asInt(),
        options.path("newEntrantsFromMantleTo090").path("thirdImage").asInt(),
        "third-image 新增进带必须等于两口径带内对数之差");
    assertEquals(120, rows.get(0).path("ownBaseDenominator").asInt(), "own-base 分母必须标明 120");
    assertEquals(
        2760, rows.get(0).path("thirdImageDenominator").asInt(), "third-image 分母必须标明 2760");
    String serialized =
        MAPPER.writeValueAsString(options) + MAPPER.writeValueAsString(computeTierFill());
    assertTrue(
        !serialized.matches("(?s).*(推荐|建议|应取|应降|应当取|recommend)[^\\n。]{0,24}0\\.9.*"),
        "腿 B 产出不得出现「推荐/建议 + 阈值数字」措辞（R5 grep 断言）");
    assertTrue(
        !serialized.matches("(?s).*(recommend|suggest|prefer|best|chosen)[A-Za-z]*\"\\s*:.*"),
        "腿 B 产出不得出现 recommendation 类字段名");
  }

  @Test
  @DisplayName("确定性：两轮计算序列化逐字节一致（零外呼）")
  void determinism() throws Exception {
    assertEquals(
        MAPPER.writeValueAsString(computeTierFill()),
        MAPPER.writeValueAsString(computeTierFill()),
        "tierFill 两轮序列化必须一致");
    assertEquals(
        MAPPER.writeValueAsString(computeThresholdOptions()),
        MAPPER.writeValueAsString(computeThresholdOptions()),
        "thresholdOptions 两轮序列化必须一致");
  }

  // ==================== 纯静态算子（腿 A 锚与守卫共用同一实现） ====================

  /**
   * 档位填充盘点表（R4）：own-base 侧（120 对）与 third-image 侧（2760 对）各 5 行（编辑类型全序）， 每行 cos(min/median/max) +
   * 档位计数 + 带内对数；档位边界由生产 gate 现算（tierOf/bandOf），锚内不抄数字。
   */
  static ObjectNode computeTierFill() {
    try {
      JsonNode root = readAnchor(CROSS_MATRIX_PATH);
      Map<String, double[]> vectors = vectors(root);
      List<String> keys = new ArrayList<>();
      root.path("keys").forEach(key -> keys.add(key.asText()));
      Set<String> baseKeys = new LinkedHashSet<>();
      Set<String> variantKeys = new LinkedHashSet<>();
      for (String key : keys) {
        if (key.contains("@")) {
          variantKeys.add(key);
        } else {
          baseKeys.add(key);
        }
      }
      Map<String, List<Double>> ownByKind = new LinkedHashMap<>();
      Map<String, List<Double>> thirdByKind = new LinkedHashMap<>();
      for (String kind : NearDuplicateCorpus.KINDS) {
        ownByKind.put(kind, new ArrayList<>());
        thirdByKind.put(kind, new ArrayList<>());
      }
      List<Double> thirdAll = new ArrayList<>();
      for (String variantKey : variantKeys) {
        String ownBase = variantKey.substring(0, variantKey.indexOf('@'));
        String kind = variantKey.substring(variantKey.indexOf('@') + 1);
        ownByKind.get(kind).add(cosine(vectors.get(variantKey), vectors.get(ownBase)));
        for (String thirdKey : baseKeys) {
          if (thirdKey.equals(ownBase)) {
            continue;
          }
          double value = cosine(vectors.get(variantKey), vectors.get(thirdKey));
          thirdByKind.get(kind).add(value);
          thirdAll.add(value);
        }
      }
      ObjectNode node = MAPPER.createObjectNode();
      node.put("leg", "B");
      node.put("externalCalls", 0);
      ObjectNode sources = node.putObject("sources");
      ObjectNode cross = sources.putObject("crossMatrix");
      cross.put("path", "docs/rag-quality/image-variant-cross-matrix-v1.json");
      cross.put("sha256", RealPdfAssets.sha256(Files.readAllBytes(CROSS_MATRIX_PATH)));
      cross.put("vectorCount", keys.size());
      cross.put("baseCount", baseKeys.size());
      cross.put("variantCount", variantKeys.size());
      cross.put(
          "fieldPaths",
          "vectors（144×1024 float 大端 base64）/ matrix / variantVsThirdImage（count=2760 汇总）/ corpus");
      ObjectNode exchange = sources.putObject("exchangeRate");
      exchange.put("path", "docs/rag-quality/near-dup-exchange-rate-v1.json");
      exchange.put("sha256", RealPdfAssets.sha256(Files.readAllBytes(EXCHANGE_RATE_PATH)));
      exchange.put("fieldPaths", "baseline / grid.items（floor 价目表）");
      ObjectNode probe = sources.putObject("gateProbe");
      probe.put("path", "docs/rag-quality/near-dup-ingest-gate-probe-v1.json");
      probe.put("sha256", RealPdfAssets.sha256(Files.readAllBytes(probePath())));
      probe.put(
          "inBandEvidence",
          "crop-04 对 base cos=0.9934 ∈ [0.982,0.9944)（near-dup-ingest-gate-probe-v1.md §4 选取规则实测落点）");

      ObjectNode domains = node.putObject("domainNotes");
      domains.put("ownBase", "120 对：每变体对其自身 base（源锚 matrix 对角块），「同源保留」侧——R(f) 的逐 kind 底数");
      domains.put("thirdImage", "2760 对：120 变体 × 23 异 base（源锚 variantVsThirdImage 同口径），「带内竞争者」侧");
      domains.put(
          "pairCountClarification",
          "proposal R4 括注「396 对」经实测核对为源锚 M2 偏差对账对数（deviationReconciliation：near-duplicate 120 + "
              + "same-class 100 + cross-class 176 = 396），变体×第三图全域实为 2760 对（variantVsThirdImage.count）；"
              + "本表按 R4 语料域（编辑类型 × 变体×第三图）如实取 2760，cos ≥ 0.9887 的 14 对与源锚 md M3 一致");
      node.put(
          "tierBoundarySource", "ImageSimilarityGate.tierOf/bandOf 现算（DEFAULT_THRESHOLD 直读生产常量）");

      node.putArray("ownBaseByKind");
      node.putArray("thirdImageByKind");
      fillKindRows((ArrayNode) node.path("ownBaseByKind"), ownByKind);
      fillKindRows((ArrayNode) node.path("thirdImageByKind"), thirdByKind);

      double[] sortedThird =
          sortedCosines(thirdAll.stream().mapToDouble(Double::doubleValue).toArray());
      int inBandCurrent = 0;
      int atOrAboveReference = 0;
      for (double value : sortedThird) {
        if (value >= ImageSimilarityGate.NEAR_DUPLICATE_FLOOR
            && value < ImageSimilarityGate.DEFAULT_THRESHOLD) {
          inBandCurrent++;
        }
        if (value >= ImageSimilarityGate.BAND_REFERENCE_FLOOR) {
          atOrAboveReference++;
        }
      }
      ObjectNode totals = node.putObject("totals");
      totals.put("ownBasePairs", 120);
      totals.put("thirdImagePairs", sortedThird.length);
      totals.put("thirdImageInBandCurrentFloor", inBandCurrent);
      totals.put("thirdImageAtOrAboveBandReference", atOrAboveReference);
      node.put(
          "scopeNote",
          "只统计不推断（R4）：本表不承载因果结论；编辑类型全集 = NearDuplicateCorpus.KINDS（jpeg-q80/scale-085/scale-070/crop-04/crop-12）");
      return node;
    } catch (Exception exception) {
      throw new IllegalStateException("tierFill 计算失败（零外呼装置性错误）", exception);
    }
  }

  /** 0.90 议题选项清单（R5）：恰两口径（0.9887/0.90），逐字段可指回源锚；不给推荐阈值、不改常量。 */
  static ObjectNode computeThresholdOptions() {
    try {
      JsonNode exchange = readAnchor(EXCHANGE_RATE_PATH);
      JsonNode baseline = exchange.path("baseline");
      JsonNode floor090 = null;
      for (JsonNode item : exchange.path("grid").path("items")) {
        if (Math.abs(item.path("floor").asDouble() - OPTION_FLOOR_090) < 1e-9D) {
          floor090 = item;
          break;
        }
      }
      if (floor090 == null) {
        throw new IllegalStateException("交换率锚缺 floor=0.90 行");
      }
      JsonNode cross = readAnchor(CROSS_MATRIX_PATH);
      Map<String, double[]> vectors = vectors(cross);
      List<Double> thirdAll = thirdImagePairs(cross, vectors);
      double mantle = ImageSimilarityGate.BAND_REFERENCE_FLOOR;
      int thirdInBandMantle = countInBand(thirdAll, mantle);
      int thirdInBand090 = countInBand(thirdAll, OPTION_FLOOR_090);

      ObjectNode node = MAPPER.createObjectNode();
      node.put(
          "issue",
          "NEAR_DUPLICATE_FLOOR 的 0.90 议题（R5）：只列选项与代价——不给推荐阈值、不改任何常量；"
              + "NEAR_DUPLICATE_FLOOR 变更属另卡，须 Owner 拍板");
      node.put(
          "caliberNote",
          "R5 原文两口径为「维持 0.9887 / 降到 0.90」；现行生产 floor=0.982（constants 直读生产常量），"
              + "两口径按 R5 原文照列，0.982 另落 currentProductionReference 参照行（非选项）");
      ArrayNode options = node.putArray("options");
      ObjectNode mantleOption = options.addObject();
      mantleOption.put("floor", mantle);
      mantleOption.put("label", "维持 0.9887（BAND_REFERENCE_FLOOR 口径）");
      mantleOption.put("ownBaseKept", baseline.path("retainedNearDup").asInt());
      mantleOption.put("ownBaseDenominator", 120);
      mantleOption.put("ownBaseR", baseline.path("R").asDouble());
      mantleOption.put("thirdImageInBand", thirdInBandMantle);
      mantleOption.put("thirdImageDenominator", thirdAll.size());
      mantleOption.put("sameDocSuppressed", baseline.path("keptSame").asInt());
      mantleOption.put("sameDocDenominator", 100);
      mantleOption.put(
          "sourceFields",
          "ownBaseKept/ownBaseR/sameDocSuppressed ← near-dup-exchange-rate-v1.json#baseline"
              + "（retainedNearDup/R/keptSame）；thirdImageInBand ← image-variant-cross-matrix-v1.json#vectors 复算"
              + "（[0.9887,0.9944) 区间计数）");
      ObjectNode option090 = options.addObject();
      option090.put("floor", OPTION_FLOOR_090);
      option090.put("label", "降到 0.90");
      option090.put("ownBaseKept", floor090.path("retainedNearDup").asInt());
      option090.put("ownBaseDenominator", 120);
      option090.put("ownBaseR", floor090.path("R").asDouble());
      option090.put("thirdImageInBand", thirdInBand090);
      option090.put("thirdImageDenominator", thirdAll.size());
      option090.put("sameDocSuppressed", floor090.path("keptSame").asInt());
      option090.put("sameDocDenominator", 100);
      option090.put("extraSuppressedSameDoc", floor090.path("extraSuppressedSameDoc").asInt());
      option090.put(
          "sourceFields",
          "ownBaseKept/ownBaseR/sameDocSuppressed/extraSuppressedSameDoc ← "
              + "near-dup-exchange-rate-v1.json#grid.items[floor=0.9]"
              + "（retainedNearDup/R/keptSame/extraSuppressedSameDoc）；thirdImageInBand ← "
              + "image-variant-cross-matrix-v1.json#vectors 复算（[0.90,0.9944) 区间计数）");

      ObjectNode newEntrants = node.putObject("newEntrantsFromMantleTo090");
      newEntrants.put(
          "ownBase",
          floor090.path("retainedNearDup").asInt() - baseline.path("retainedNearDup").asInt());
      newEntrants.put("ownBaseDenominator", 120);
      newEntrants.put("thirdImage", thirdInBand090 - thirdInBandMantle);
      newEntrants.put("thirdImageDenominator", thirdAll.size());

      ArrayNode costs = node.putArray("recordedCosts");
      ObjectNode cost1 = costs.addObject();
      cost1.put("cost", "R 膝盖点 113/120=0.941667（摸到 R ≥ 0.90 门槛）");
      cost1.put("sourceField", "near-dup-exchange-rate-v1.json#grid.items[floor=0.9].R");
      ObjectNode cost2 = costs.addObject();
      cost2.put("cost", "多压 6 张同文档图（S_same 0.02 → 0.08，A1/A2 口径下的有害量上界）");
      cost2.put(
          "sourceField",
          "near-dup-exchange-rate-v1.json#grid.items[floor=0.9].extraSuppressedSameDoc");

      ObjectNode reference = node.putObject("currentProductionReference");
      reference.put("floor", ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
      reference.put(
          "thirdImageInBand", countInBand(thirdAll, ImageSimilarityGate.NEAR_DUPLICATE_FLOOR));
      reference.put(
          "note",
          "现行生产 NEAR_DUPLICATE_FLOOR（直读 ImageSimilarityGate 常量），参照行非选项：R=80/120、S_same=0.03 见 "
              + "near-dup-exchange-rate-v1.json#grid.items[floor=0.982]");
      return node;
    } catch (Exception exception) {
      throw new IllegalStateException("thresholdOptions 计算失败（零外呼装置性错误）", exception);
    }
  }

  /** 探针锚路径（唯一只读依赖；守卫与 IT 同源引用）。 */
  static Path probePath() {
    return Path.of("docs", "rag-quality", "near-dup-ingest-gate-probe-v1.json");
  }

  /** 源锚读取（存在性前置；这些锚是冻结数据面，读不到即装置性错误）。 */
  static JsonNode readAnchor(Path path) throws Exception {
    assertTrue(Files.exists(path), "源锚不存在（冻结数据面被破坏？）: " + path);
    return MAPPER.readTree(Files.readAllBytes(path));
  }

  /** 变体×第三图全量 cos（顺序与源锚 variantVsThirdImage 同口径：变体序 × 异 base 序）。 */
  static List<Double> thirdImagePairs(JsonNode root, Map<String, double[]> vectors) {
    List<String> keys = new ArrayList<>();
    root.path("keys").forEach(key -> keys.add(key.asText()));
    List<Double> values = new ArrayList<>();
    for (String variantKey : keys) {
      if (!variantKey.contains("@")) {
        continue;
      }
      String ownBase = variantKey.substring(0, variantKey.indexOf('@'));
      for (String thirdKey : keys) {
        if (!thirdKey.contains("@") && !thirdKey.equals(ownBase)) {
          values.add(cosine(vectors.get(variantKey), vectors.get(thirdKey)));
        }
      }
    }
    return values;
  }

  /** 锚内冻结向量解码（Java ByteBuffer 默认大端 float 数组 → base64，与写锚侧对称）。 */
  static Map<String, double[]> vectors(JsonNode root) {
    Map<String, double[]> vectors = new LinkedHashMap<>();
    JsonNode vectorNode = root.path("vectors");
    vectorNode
        .fieldNames()
        .forEachRemaining(
            key -> {
              byte[] bytes = Base64.getDecoder().decode(vectorNode.path(key).asText());
              if (bytes.length % 4 != 0) {
                throw new IllegalStateException("向量字节数非 4 的倍数: " + key);
              }
              double[] vector = new double[bytes.length / 4];
              for (int index = 0; index < vector.length; index++) {
                vector[index] =
                    Float.intBitsToFloat(
                        ((bytes[index * 4] & 0xFF) << 24)
                            | ((bytes[index * 4 + 1] & 0xFF) << 16)
                            | ((bytes[index * 4 + 2] & 0xFF) << 8)
                            | (bytes[index * 4 + 3] & 0xFF));
              }
              vectors.put(key, vector);
            });
    return vectors;
  }

  /** 余弦（双精度累加；与源锚写锚侧同式，容差对账见 QUANTILE_TOLERANCE）。 */
  static double cosine(double[] a, double[] b) {
    double dot = 0.0D;
    double normA = 0.0D;
    double normB = 0.0D;
    for (int index = 0; index < a.length; index++) {
      dot += a[index] * b[index];
      normA += a[index] * a[index];
      normB += b[index] * b[index];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  private static void fillKindRows(ArrayNode rows, Map<String, List<Double>> byKind) {
    for (String kind : NearDuplicateCorpus.KINDS) {
      double[] sorted =
          sortedCosines(byKind.get(kind).stream().mapToDouble(Double::doubleValue).toArray());
      int sameImage = 0;
      int high = 0;
      int mid = 0;
      int low = 0;
      int below = 0;
      for (double value : sorted) {
        String tier = ImageSimilarityGate.tierOf(value, ImageSimilarityGate.DEFAULT_THRESHOLD);
        if (ImageSimilarityGate.TIER_SAME_IMAGE.equals(tier)) {
          sameImage++;
        } else if (ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(tier)) {
          String band = ImageSimilarityGate.bandOf(value, ImageSimilarityGate.DEFAULT_THRESHOLD);
          if (ImageSimilarityGate.BAND_NEAR_HIGH.equals(band)) {
            high++;
          } else if (ImageSimilarityGate.BAND_NEAR_MID.equals(band)) {
            mid++;
          } else {
            low++;
          }
        } else {
          below++;
        }
      }
      ObjectNode row = rows.addObject();
      row.put("kind", kind);
      row.put("pairs", sorted.length);
      row.put("cosMin", sorted[0]);
      row.put("cosMedian", nearestRank(sorted, 0.5D));
      row.put("cosMax", sorted[sorted.length - 1]);
      row.put("sameImage", sameImage);
      row.put("bandNearHigh", high);
      row.put("bandNearMid", mid);
      row.put("bandNearLow", low);
      row.put("belowFloor", below);
      row.put("inBand", high + mid + low);
    }
  }

  private static int countInBand(List<Double> values, double floor) {
    int count = 0;
    for (double value : values) {
      if (value >= floor && value < ImageSimilarityGate.DEFAULT_THRESHOLD) {
        count++;
      }
    }
    return count;
  }

  private static double[] sortedCosines(double[] values) {
    double[] sorted = values.clone();
    java.util.Arrays.sort(sorted);
    return sorted;
  }

  private static double[] sortedCosines(List<Double> values) {
    return sortedCosines(values.stream().mapToDouble(Double::doubleValue).toArray());
  }

  /** 最近秩分位（升序第 ⌈q·n⌉ 个；与源锚 quantileMethod 同口径）。 */
  static double nearestRank(double[] sorted, double q) {
    int rank = (int) Math.ceil(q * sorted.length);
    return sorted[Math.min(Math.max(rank, 1), sorted.length) - 1];
  }
}
