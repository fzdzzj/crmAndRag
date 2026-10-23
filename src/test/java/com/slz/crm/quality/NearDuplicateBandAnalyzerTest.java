package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.Analysis;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.BandStat;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.CorpusMeta;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.Judgement;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.KindStat;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.Pair;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.Stats;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.View;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NearDuplicateBandAnalyzer} 的零外呼单测（calibrate-near-dup-bands 任务 2.2）。
 *
 * <p>守四类性质：①落带与 {@link ImageSimilarityGate} <b>逐式对齐</b>（边界值归上一档、Δ 随阈值缩放、floor 随阈值收敛）；
 * ②退化路径不硬凑（不可分不给阈值、空表分位为 null、分母 0 精度为 null）；③强度单调性反序可检出；④判定树与锚 JSON 结构自洽。
 */
@DisplayName("近重复落带分析（零外呼）")
class NearDuplicateBandAnalyzerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("边界值归上一档：0.9887 / b1 / b2 / 0.9944 逐点对账")
  void boundariesGoToUpperBand() {
    double floor = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR;
    double threshold = ImageSimilarityGate.DEFAULT_THRESHOLD;
    double lowUpper = ImageSimilarityGate.lowBandUpperBound(threshold);
    double highLower = ImageSimilarityGate.highBandLowerBound(threshold);

    assertEquals(0.9887D, floor, 0D, "下限必须仍是锚的异图上限展示口径");
    assertEquals(0.9944D, threshold, 0D, "阈值必须仍是锚的判别阈值展示口径");

    assertNull(ImageSimilarityGate.bandOf(floor - 1e-12D, threshold), "下限下面是异图");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_LOW,
        NearDuplicateBandAnalyzer.bandOf(floor, threshold),
        "下限本身归最低近似带（>= 判据）");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_MID,
        NearDuplicateBandAnalyzer.bandOf(lowUpper, threshold),
        "b1 本身归中档（边界值归上一档）");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_LOW,
        NearDuplicateBandAnalyzer.bandOf(Math.nextDown(lowUpper), threshold),
        "b1 下一档点归低档");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_HIGH,
        NearDuplicateBandAnalyzer.bandOf(highLower, threshold),
        "b2 本身归高档");
    assertEquals(
        ImageSimilarityGate.BAND_NEAR_MID,
        NearDuplicateBandAnalyzer.bandOf(Math.nextDown(highLower), threshold),
        "b2 下一档点归中档");
    assertNull(NearDuplicateBandAnalyzer.bandOf(threshold, threshold), "阈值本身不在近似带内");
    assertEquals(
        ImageSimilarityGate.TIER_SAME_IMAGE,
        NearDuplicateBandAnalyzer.labelOf(threshold, threshold),
        "阈值本身归同图（五档标签的 >= 判据）");
    assertEquals(
        ImageSimilarityGate.TIER_CROSS_IMAGE,
        NearDuplicateBandAnalyzer.labelOf(Math.nextDown(floor), threshold),
        "下限下面是异图");
  }

  @Test
  @DisplayName("逐式对齐：全区间扫描下与闸门 bandOf 完全一致")
  void alignsWithGateEverywhere() {
    for (double threshold : new double[] {0.9944D, 0.996D, 0.99D, 0.9887D, 0.98D}) {
      double floor = ImageSimilarityGate.effectiveFloor(threshold);
      assertEquals(
          ImageSimilarityGate.lowBandUpperBound(threshold),
          NearDuplicateBandAnalyzer.lowBandUpperBound(floor, threshold),
          0D,
          "低/中边界公式必须与闸门逐项同形: threshold=" + threshold);
      assertEquals(
          ImageSimilarityGate.highBandLowerBound(threshold),
          NearDuplicateBandAnalyzer.highBandLowerBound(floor, threshold),
          0D,
          "中/高边界公式必须与闸门逐项同形: threshold=" + threshold);
      for (int step = 0; step <= 1000; step++) {
        double cosine = 0.95D + 0.0001D * step;
        assertEquals(
            ImageSimilarityGate.bandOf(cosine, threshold),
            NearDuplicateBandAnalyzer.bandOf(cosine, threshold),
            "落带必须与闸门一致: cosine=" + cosine + " threshold=" + threshold);
        String expectedLabel =
            cosine >= threshold
                ? ImageSimilarityGate.TIER_SAME_IMAGE
                : ImageSimilarityGate.bandOf(cosine, threshold) == null
                    ? ImageSimilarityGate.TIER_CROSS_IMAGE
                    : ImageSimilarityGate.bandOf(cosine, threshold);
        assertEquals(
            expectedLabel,
            NearDuplicateBandAnalyzer.labelOf(cosine, threshold),
            "五档标签必须由闸门落带推导: cosine=" + cosine + " threshold=" + threshold);
      }
    }
  }

  @Test
  @DisplayName("Δ 随阈值缩放：阈值抬高则三档边界整体上移，阈值跌破下限则退化无近似带")
  void bandWidthScalesWithThreshold() {
    double floor = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR;
    double baseUpper = NearDuplicateBandAnalyzer.lowBandUpperBound(floor, 0.9944D);
    double raisedUpper =
        NearDuplicateBandAnalyzer.lowBandUpperBound(
            ImageSimilarityGate.effectiveFloor(0.999D), 0.999D);
    assertTrue(raisedUpper > baseUpper, "阈值抬高后低/中边界必须上移（Δ 随阈值缩放）");
    assertTrue(
        NearDuplicateBandAnalyzer.highBandLowerBound(
                ImageSimilarityGate.effectiveFloor(0.999D), 0.999D)
            > NearDuplicateBandAnalyzer.highBandLowerBound(floor, 0.9944D),
        "阈值抬高后中/高边界必须上移");

    double collapsed = 0.98D;
    assertEquals(collapsed, ImageSimilarityGate.effectiveFloor(collapsed), 0D, "下限随阈值收敛");
    assertEquals(
        collapsed,
        NearDuplicateBandAnalyzer.lowBandUpperBound(collapsed, collapsed),
        0D,
        "区间退化时低/中边界 = 阈值");
    assertNull(NearDuplicateBandAnalyzer.bandOf(collapsed, collapsed), "退化区间没有近似带");
    assertEquals(
        ImageSimilarityGate.TIER_SAME_IMAGE,
        NearDuplicateBandAnalyzer.labelOf(collapsed, collapsed),
        "退化区间里阈值本身算同图");
  }

  @Test
  @DisplayName("不可分退化：crossImageCeiling >= sameImageFloor 时锚视图不给阈值")
  void inseparableAnchorViewHasNoThreshold() {
    List<Pair> pairs = syntheticPairs();
    View anchor = NearDuplicateBandAnalyzer.anchorView(pairs, 0.99D, 0.995D);
    assertNull(anchor.threshold(), "不可分时不得硬凑阈值");
    assertFalse(anchor.separable(), "不可分必须如实标注");
    assertTrue(
        anchor.bands().stream().allMatch(stat -> stat.count() == 0), "不可分时落带计数必须全 0（没有阈值就没有带，不硬凑）");
    assertEquals(0, anchor.aboveThreshold(), "不可分时没有阈值以上的计数");
    assertNotNull(anchor.nearDuplicateBelowFloorShare(), "近重复可达性仍要算（floor = 实测上限）");

    View separable = NearDuplicateBandAnalyzer.anchorView(pairs, 0.9995D, 0.995D);
    assertTrue(separable.separable(), "上限低于下限时应可分");
    assertEquals((0.995D + 0.9995D) / 2D, separable.threshold(), 1e-12D, "阈值 = 上限与下限的中点");
    assertEquals(0.995D, separable.floor(), 0D, "锚视图 floor = 实测异图上限");
  }

  @Test
  @DisplayName("单调性：强度序中位数反序可检出并明细记录")
  void monotonicityDetectsInversions() {
    List<KindStat> monotone = new ArrayList<>();
    for (int index = 0; index < NearDuplicateCorpus.KINDS.size(); index++) {
      monotone.add(
          new KindStat(
              NearDuplicateCorpus.KINDS.get(index), statsWithMedian(0.999D - index * 0.002D)));
    }
    NearDuplicateBandAnalyzer.Monotonicity decreasing =
        NearDuplicateBandAnalyzer.monotonicity(monotone);
    assertTrue(decreasing.monotonic(), "中位数随强度递减即单调");
    assertTrue(decreasing.inversions().isEmpty(), "单调时无反序明细");
    assertEquals(NearDuplicateCorpus.KINDS, decreasing.kinds(), "单调性顺序必须取变体强度序");

    List<KindStat> bumpy = new ArrayList<>();
    double[] medians = {0.999D, 0.996D, 0.997D, 0.99D, 0.985D};
    for (int index = 0; index < medians.length; index++) {
      bumpy.add(
          new KindStat(NearDuplicateCorpus.KINDS.get(index), statsWithMedian(medians[index])));
    }
    NearDuplicateBandAnalyzer.Monotonicity broken = NearDuplicateBandAnalyzer.monotonicity(bumpy);
    assertFalse(broken.monotonic(), "出现反序必须不单调");
    assertEquals(1, broken.inversions().size(), "单点反序只记一条");
    assertEquals(
        NearDuplicateCorpus.KIND_SCALE_070, broken.inversions().get(0).rightKind(), "反序右侧是更强的变换");
    assertEquals(
        NearDuplicateCorpus.KIND_SCALE_085, broken.inversions().get(0).leftKind(), "反序左侧是更弱的变换");
  }

  @Test
  @DisplayName("空表退化：分位返回 null 而非 0")
  void emptyStatsReturnNull() {
    Stats empty = NearDuplicateBandAnalyzer.stats(List.of());
    assertEquals(0, empty.count(), "空表 count 为 0");
    assertNull(empty.min(), "空表 min 必须 null");
    assertNull(empty.p05(), "空表 p05 必须 null");
    assertNull(empty.p25(), "空表 p25 必须 null");
    assertNull(empty.median(), "空表 median 必须 null");
    assertNull(empty.p75(), "空表 p75 必须 null");
    assertNull(empty.p95(), "空表 p95 必须 null");
    assertNull(empty.max(), "空表 max 必须 null");
    assertNull(empty.mean(), "空表 mean 必须 null");
    assertNull(NearDuplicateBandAnalyzer.quantile(List.of(), 0.05D), "空表分位函数必须 null");
  }

  @Test
  @DisplayName("带内精度：分母为 0 时返回 null，不是 0")
  void bandPrecisionIsNullWhenEmpty() {
    View view = NearDuplicateBandAnalyzer.productionView(syntheticPairs());
    for (BandStat stat : view.bands()) {
      if (stat.count() == 0) {
        assertNull(stat.nearDuplicateShare(), "空带精度必须 null（0 会被误读成「全是异图」）: " + stat.band());
      } else {
        assertNotNull(stat.nearDuplicateShare(), "非空带必须有精度: " + stat.band());
      }
    }
  }

  @Test
  @DisplayName("视图计数闭合：五带之和 = 对数，belowFloor = 异图带，aboveThreshold = 同图带")
  void viewCountsAreClosed() {
    List<Pair> pairs = syntheticPairs();
    View view = NearDuplicateBandAnalyzer.productionView(pairs);
    int bandSum = view.bands().stream().mapToInt(BandStat::count).sum();
    assertEquals(pairs.size(), bandSum, "五带对数之和必须等于样本对总数");
    assertEquals(pairs.size(), view.pairCount(), "pairCount 必须等于样本对总数");
    assertEquals(
        view.belowFloor(),
        band(ImageSimilarityGate.TIER_CROSS_IMAGE, view).count(),
        "belowFloor 即异图带");
    assertEquals(
        view.aboveThreshold(),
        band(ImageSimilarityGate.TIER_SAME_IMAGE, view).count(),
        "aboveThreshold 即同图带");
    assertEquals(
        0.2D, view.nearDuplicateBelowFloorShare(), 1e-12D, "近重复可达性占比必须按 near-duplicate 档算");
    assertEquals(2, view.nearDuplicateBelowFloor(), "低于下限的近重复对计数");
    assertEquals(10, view.nearDuplicateCount(), "近重复对总数");
  }

  @Test
  @DisplayName("候选下限 = near-duplicate 档 p05，档位分位摘要自洽")
  void candidateFloorIsP05() {
    Analysis analysis = NearDuplicateBandAnalyzer.analyze(syntheticPairs());
    Stats nearDuplicate = analysis.strata().get(NearDuplicateBandAnalyzer.STRATUM_NEAR_DUPLICATE);
    assertEquals(10, nearDuplicate.count(), "近重复档样本数");
    assertEquals(0.980D, nearDuplicate.min(), 1e-12D, "近重复档最小值 = 低于下限的那批");
    assertEquals(nearDuplicate.p05(), analysis.candidateFloor(), 0D, "候选下限必须就是 p05");
    assertEquals(0.980D, analysis.candidateFloor(), 1e-12D, "10 个样本里最小的两个同值，p05 落在它们之间");
    assertEquals(
        0.1D * 0.95D + 0.9D * 0.05D,
        NearDuplicateBandAnalyzer.quantile(List.of(0.1D, 0.9D), 0.05D),
        1e-12D,
        "p05 必须走线性插值而不是取下标");
    assertEquals(5, analysis.byKind().size(), "按变体类型必须每类一行（顺序 = 强度序）");
    assertEquals(
        NearDuplicateCorpus.KINDS.get(0), analysis.byKind().get(0).kind(), "类型顺序必须从最轻变换开始");
  }

  @Test
  @DisplayName("判定树：六支齐全，F1 / F4 在合成样本上如实命中")
  void judgementTreeCoversAllBranches() {
    Analysis analysis = NearDuplicateBandAnalyzer.analyze(syntheticPairs());
    List<Judgement> judgements = analysis.judgements();
    assertEquals(6, judgements.size(), "判定树必须 F0–F5 六支齐全");
    List<String> codes = judgements.stream().map(Judgement::code).toList();
    assertEquals(List.of("F0", "F1", "F2", "F3", "F4", "F5"), codes, "分支编号与顺序必须与 proposal §4 一致");
    for (Judgement judgement : judgements) {
      assertFalse(judgement.condition().isBlank(), "每支必须写明触发条件原文: " + judgement.code());
      assertFalse(judgement.evidence().isBlank(), "每支必须给出证据数字: " + judgement.code());
    }
    assertTrue(judgement("F4", judgements).hit(), "三带样本都远小于 30，F4 必须命中");
    assertFalse(judgement("F0", judgements).hit(), "样本不足时 F0 不得命中");
    assertFalse(judgement("F1", judgements).hit(), "合成样本只有 2/10 低于下限，F1 未触发");
    assertFalse(judgement("F2", judgements).hit(), "合成样本同图下限高于异图上限，F2 未触发");
  }

  @Test
  @DisplayName("判定树：F1 命中 = 近重复对过半落在下限以下")
  void unreachableBandTriggersF1() {
    List<Pair> pairs = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      pairs.add(
          new Pair(
              "same-" + index,
              "same-" + index,
              NearDuplicateBandAnalyzer.STRATUM_SAME_IMAGE,
              null,
              0.9995D));
    }
    for (int index = 0; index < 10; index++) {
      pairs.add(
          new Pair(
              "base-" + index,
              "base-" + index + "@" + NearDuplicateCorpus.KIND_CROP_12,
              NearDuplicateBandAnalyzer.STRATUM_NEAR_DUPLICATE,
              NearDuplicateCorpus.KIND_CROP_12,
              0.97D));
    }
    Analysis analysis = NearDuplicateBandAnalyzer.analyze(pairs);
    assertTrue(judgement("F1", analysis.judgements()).hit(), "10/10 近重复对低于下限，F1 必须命中");
    assertEquals(0.97D, analysis.candidateFloor(), 1e-12D, "候选下限取 p05（此处全部同值）");
  }

  @Test
  @DisplayName("锚 JSON：结构、判定树与 null 语义自洽")
  void anchorJsonIsSelfConsistent() throws Exception {
    Analysis analysis = NearDuplicateBandAnalyzer.analyze(syntheticPairs());
    CorpusMeta meta =
        new CorpusMeta(
            10,
            50,
            60,
            120,
            0,
            List.of(new NearDuplicateCorpus.DocumentCount("nist-sp800-101r1", 8, 10)),
            List.of());
    JsonNode root =
        NearDuplicateBandAnalyzer.toJson(analysis, meta, "2026-10-24T00:00:00Z", "opt-in 说明");
    assertEquals(
        NearDuplicateBandAnalyzer.CALIBRATION_VERSION,
        root.path("calibrationVersion").asText(),
        "版本号必须是 v2 的独立值");
    assertEquals(10, root.path("corpus").path("bases").asInt(), "语料 base 数落盘");
    assertEquals(50, root.path("corpus").path("variants").asInt(), "语料变体数落盘");
    assertEquals(120, root.path("corpus").path("imageEmbeddingCalls").asInt(), "调用次数落盘（成本留痕）");
    assertEquals(syntheticPairs().size(), root.path("pairs").size(), "全量样本对必须可逐对复核");
    assertEquals(6, root.path("judgements").size(), "判定树结论必须落盘");
    for (JsonNode pair : root.path("pairs")) {
      assertNotNull(pair.get("cosine"), "每对必须有余弦");
      assertNotNull(pair.get("stratum"), "每对必须有档位");
    }
    assertTrue(root.path("anchorView").path("separable").asBoolean(), "合成样本可分");
    assertFalse(root.path("anchorView").path("threshold").isNull(), "可分时阈值必须落盘");
    assertFalse(root.path("monotonicity").path("monotonic").isNull(), "单调性结论必须落盘");
    assertTrue(root.path("candidateFloor").isNumber(), "候选下限必须落盘");
    assertEquals(
        NearDuplicateBandAnalyzer.CALIBRATION_VERSION,
        MAPPER.readTree(root.toString()).path("calibrationVersion").asText(),
        "装配出的 JSON 必须可被重新解析");
  }

  /** 合成样本：10 同图 / 10 近重复（8 高 + 2 低于下限）/ 10 异图。 */
  private static List<Pair> syntheticPairs() {
    List<Pair> pairs = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      pairs.add(
          new Pair(
              "img-" + index,
              "img-" + index,
              NearDuplicateBandAnalyzer.STRATUM_SAME_IMAGE,
              null,
              0.999D));
    }
    for (int index = 0; index < 8; index++) {
      pairs.add(
          new Pair(
              "img-" + index,
              "img-" + index + "@" + NearDuplicateCorpus.KIND_JPEG_Q80,
              NearDuplicateBandAnalyzer.STRATUM_NEAR_DUPLICATE,
              NearDuplicateCorpus.KIND_JPEG_Q80,
              0.993D));
    }
    for (int index = 0; index < 2; index++) {
      pairs.add(
          new Pair(
              "img-" + index,
              "img-" + index + "@" + NearDuplicateCorpus.KIND_CROP_12,
              NearDuplicateBandAnalyzer.STRATUM_NEAR_DUPLICATE,
              NearDuplicateCorpus.KIND_CROP_12,
              0.980D));
    }
    for (int index = 0; index < 10; index++) {
      pairs.add(
          new Pair(
              "img-a-" + index,
              "img-b-" + index,
              NearDuplicateBandAnalyzer.STRATUM_CROSS_CLASS,
              null,
              0.90D));
    }
    return pairs;
  }

  private static Stats statsWithMedian(double median) {
    return new Stats(1, median, median, median, median, median, median, median, median);
  }

  private static BandStat band(String name, View view) {
    BandStat found = null;
    for (BandStat stat : view.bands()) {
      if (stat.band().equals(name)) {
        found = stat;
      }
    }
    assertNotNull(found, "视图必须包含该带: " + name);
    return found;
  }

  private static Judgement judgement(String code, List<Judgement> judgements) {
    Judgement found = null;
    for (Judgement judgement : judgements) {
      if (judgement.code().equals(code)) {
        found = judgement;
      }
    }
    assertNotNull(found, "判定树缺少分支: " + code);
    return found;
  }
}
