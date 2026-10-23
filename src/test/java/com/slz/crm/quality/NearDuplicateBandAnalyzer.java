package com.slz.crm.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 近重复三档落带分析（calibrate-near-dup-bands 任务 2.1，<b>纯函数、零外呼</b>）。
 *
 * <p><b>为什么单独一个类</b>：落带口径必须与生产闸门 {@link ImageSimilarityGate} <b>逐式对齐</b>，否则「锚上算出来的带」和
 * 「生产实际降权的带」不是同一个东西，定标结论就没有意义。本类的 {@code floor} / {@code b1} / {@code b2} / 边界归上一档全部<b>委托</b>给
 * {@link ImageSimilarityGate}（{@link ImageSimilarityGate#effectiveFloor(double)} / {@link
 * ImageSimilarityGate#lowBandUpperBound(double)} / {@link
 * ImageSimilarityGate#highBandLowerBound(double)}），生产 视图一行不重写；只有「用实测异图上限当 floor」的锚视图才需要一条带 floor
 * 参数的通用式，且该式与闸门公式逐项同形（见 {@link #lowBandUpperBound(double, double)}）。
 *
 * <p><b>双视图</b>：{@code productionView} 用生产现值（floor 0.9887 / threshold 0.9944）回答「现在的闸门看得见多少近重复」；
 * {@code anchorView} 用本锚实测的异图上限当 floor、其与同图下限的中点当 threshold，回答「间隔带本来能装多少」。两视图的差就是 floor 取值挡掉真重复的
 * 证据（proposal §4 F5）。
 *
 * <p><b>禁止手算</b>：本类同时负责把分析结果装配成锚 JSON（{@link #toJson}），{@code
 * docs/rag-quality/image-near-duplicate-bands-v2.md} 的每个数字都由 {@code
 * scripts/rag-quality/near-duplicate-band-stats.mjs} 从锚的 {@code pairs} 逐数复算后打印——文档抄脚本输出，不抄本类。
 */
final class NearDuplicateBandAnalyzer {

  /** v2 锚版本号（与 v1 的 {@code IMAGE-THRESHOLD-CALIBRATION-1.0} 并列，互不覆盖）。 */
  static final String CALIBRATION_VERSION = "IMAGE-NEAR-DUPLICATE-CALIBRATION-2.0";

  /** 生产视图名（生产现值：floor 0.9887 / threshold 0.9944）。 */
  static final String PRODUCTION_VIEW = "productionView";

  /** 锚视图名（floor = 实测异图上限、threshold = 其与同图下限的中点）。 */
  static final String ANCHOR_VIEW = "anchorView";

  /** 生产下限 = 闸门常量（不新写数字）。 */
  static final double PRODUCTION_FLOOR = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR;

  /** 生产阈值 = 闸门常量（不新写数字）。 */
  static final double PRODUCTION_THRESHOLD = ImageSimilarityGate.DEFAULT_THRESHOLD;

  /** 同图档（同字节再编码）。 */
  static final String STRATUM_SAME_IMAGE = "same-image";

  /** 近重复档（base 与其同源变体）。 */
  static final String STRATUM_NEAR_DUPLICATE = "near-duplicate";

  /** 同类档（同出版物不同 base）。 */
  static final String STRATUM_SAME_CLASS = "same-class";

  /** 跨类档（不同出版物）。 */
  static final String STRATUM_CROSS_CLASS = "cross-class";

  /** 档位全序（锚 JSON 的 {@code strata} 键序）。 */
  static final List<String> STRATA =
      List.of(STRATUM_SAME_IMAGE, STRATUM_NEAR_DUPLICATE, STRATUM_SAME_CLASS, STRATUM_CROSS_CLASS);

  /** 落带标签全序（同图 / 三档近似 / 异图）。 */
  static final List<String> BANDS =
      List.of(
          ImageSimilarityGate.TIER_SAME_IMAGE,
          ImageSimilarityGate.BAND_NEAR_HIGH,
          ImageSimilarityGate.BAND_NEAR_MID,
          ImageSimilarityGate.BAND_NEAR_LOW,
          ImageSimilarityGate.TIER_CROSS_IMAGE);

  /** 近似带三项（判定树里的「每带」）。 */
  static final List<String> NEAR_BANDS =
      List.of(
          ImageSimilarityGate.BAND_NEAR_HIGH,
          ImageSimilarityGate.BAND_NEAR_MID,
          ImageSimilarityGate.BAND_NEAR_LOW);

  /** 判定树「样本足」门槛（proposal §4 写死 30 对，不在跑后调整）。 */
  static final int MIN_BAND_SAMPLES = 30;

  /** 判定树「带内近重复占比」门槛（proposal §4 写死 0.5）。 */
  static final double MIN_NEAR_DUPLICATE_PRECISION = 0.5D;

  /** 判定树 F1 门槛：nearDuplicate 对中低于 floor 的比例 &gt; 0.5。 */
  static final double UNREACHABLE_SHARE = 0.5D;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private NearDuplicateBandAnalyzer() {}

  /**
   * 一对样本的余弦观测（锚 {@code pairs} 的一行）。
   *
   * @param leftKey 左图键（近重复对 = base 键；同图对 = 同一图键）
   * @param rightKey 右图键（近重复对 = baseKey@kind）
   * @param stratum 档位（{@link #STRATA} 之一）
   * @param kind 变体类型（仅 near-duplicate 档有值，其余 null）
   * @param cosine 余弦
   */
  record Pair(String leftKey, String rightKey, String stratum, String kind, double cosine) {}

  /**
   * 一组余弦的分位摘要（空表时所有分位为 {@code null}，<b>不是 0</b>）。
   *
   * @param count 样本数
   * @param min 最小值
   * @param p05 5% 分位
   * @param p25 25% 分位
   * @param median 中位数
   * @param p75 75% 分位
   * @param p95 95% 分位
   * @param max 最大值
   * @param mean 均值
   */
  record Stats(
      int count,
      Double min,
      Double p05,
      Double p25,
      Double median,
      Double p75,
      Double p95,
      Double max,
      Double mean) {}

  /**
   * 单带统计。
   *
   * @param band 带标签
   * @param count 该带全部对数
   * @param nearDuplicateCount 其中 near-duplicate 档的对数
   * @param nearDuplicateShare 带内精度 = nearDuplicateCount / count（分母 0 时为 null）
   * @param stats 该带余弦的分位摘要
   */
  record BandStat(
      String band, int count, int nearDuplicateCount, Double nearDuplicateShare, Stats stats) {}

  /**
   * 单视图落带结果。
   *
   * @param name 视图名
   * @param floor 生效下限
   * @param threshold 生效阈值（不可分时为 null：不硬凑阈值）
   * @param separable 可分（阈值存在）
   * @param pairCount 样本对总数
   * @param bands 五带统计（不可分时为空表）
   * @param belowFloor 余弦 &lt; floor 的对数
   * @param aboveThreshold 余弦 &gt;= threshold 的对数（不可分时为 0）
   * @param nearDuplicateCount near-duplicate 档对数
   * @param nearDuplicateBelowFloor near-duplicate 档中余弦 &lt; floor 的对数
   * @param nearDuplicateBelowFloorShare 上者占比（分母 0 时为 null）
   */
  record View(
      String name,
      double floor,
      Double threshold,
      boolean separable,
      int pairCount,
      List<BandStat> bands,
      int belowFloor,
      int aboveThreshold,
      int nearDuplicateCount,
      int nearDuplicateBelowFloor,
      Double nearDuplicateBelowFloorShare) {}

  /**
   * 单变体类型的统计。
   *
   * @param kind 变体类型
   * @param stats 该类型近重复对的余弦分位摘要
   */
  record KindStat(String kind, Stats stats) {}

  /**
   * 一条相邻反序（强度递增但中位数反而上升）。
   *
   * @param leftKind 较弱一侧
   * @param rightKind 较强一侧
   * @param leftMedian 较弱一侧中位数
   * @param rightMedian 较强一侧中位数
   */
  record Inversion(String leftKind, String rightKind, Double leftMedian, Double rightMedian) {}

  /**
   * 强度单调性结论。
   *
   * @param kinds 强度序（从轻到重）
   * @param medians 各类型中位数
   * @param monotonic 无非递增违例
   * @param inversions 违例明细（允许单点反序，但要记录）
   */
  record Monotonicity(
      List<String> kinds, List<Double> medians, boolean monotonic, List<Inversion> inversions) {}

  /**
   * 判定树一支的判定结果。
   *
   * @param code 分支号（F0–F5）
   * @param condition 触发条件原文（写死，跑后不改口径）
   * @param hit 是否命中
   * @param evidence 触发/未触发的具体数字（引用锚字段名）
   */
  record Judgement(String code, String condition, boolean hit, String evidence) {}

  /**
   * 锚的语料元信息（IT 从 {@link NearDuplicateCorpus.Corpus} 投影）。
   *
   * @param bases base 数（去重且剔除退化后）
   * @param variants 变体数
   * @param images 图总数
   * @param imageEmbeddingCalls 实际视觉 embedding 调用次数
   * @param skips 跳过的变体数
   * @param documents 逐文档取图数
   * @param skipDetail 跳过明细
   * @param baseDetail 逐 base 像素属性（退化判据与数据质量留痕，见 {@link NearDuplicateCorpus.BasePixel}）
   * @param excludedBases 退化 base 剔除登记（不静默保留、不静默丢弃）
   */
  record CorpusMeta(
      int bases,
      int variants,
      int images,
      int imageEmbeddingCalls,
      int skips,
      List<NearDuplicateCorpus.DocumentCount> documents,
      List<NearDuplicateCorpus.Skip> skipDetail,
      List<NearDuplicateCorpus.BasePixel> baseDetail,
      List<NearDuplicateCorpus.ExcludedBase> excludedBases) {}

  /**
   * 一次完整分析（双视图 + 分位 + 单调性 + 候选下限 + 判定树）。
   *
   * @param pairs 样本对
   * @param strata 档位 → 分位摘要
   * @param productionView 生产视图
   * @param anchorView 锚视图
   * @param byKind 按变体类型的统计
   * @param monotonicity 强度单调性
   * @param candidateFloor 候选下限：<b>仅当 F1 命中</b>时取 near-duplicate 档 p05，否则 {@code null}（proposal
   *     §9.5： 不得保留一个由退化/不可达样本算出的数当建议）
   * @param judgements 判定树逐支结论
   */
  record Analysis(
      List<Pair> pairs,
      Map<String, Stats> strata,
      View productionView,
      View anchorView,
      List<KindStat> byKind,
      Monotonicity monotonicity,
      Double candidateFloor,
      List<Judgement> judgements) {}

  /**
   * 全量分析入口（纯函数）。
   *
   * @param pairs 样本对
   * @return 分析结果
   */
  static Analysis analyze(List<Pair> pairs) {
    Map<String, Stats> strata = new LinkedHashMap<>();
    for (String stratum : STRATA) {
      strata.put(stratum, stats(cosines(pairs, stratum)));
    }
    Double sameImageFloor = strata.get(STRATUM_SAME_IMAGE).min();
    Double crossImageCeiling = crossImageCeiling(strata);
    View production = productionView(pairs);
    View anchor = anchorView(pairs, sameImageFloor, crossImageCeiling);
    List<KindStat> byKind = byKind(pairs);
    Monotonicity monotonicity = monotonicity(byKind);
    List<Judgement> judgements = judge(production, anchor, sameImageFloor, crossImageCeiling);
    // candidateFloor 的处置写在 proposal §9.5：只有 F1 命中（近重复对确实基本落在 floor 以下）才给候选下限；
    // 否则一律 null——不允许把「F1 未命中时算出的 p05」当成下调 floor 的建议（首轮 0.4199 就是这样被误读的）。
    boolean unreachable =
        judgements.stream().anyMatch(item -> "F1".equals(item.code()) && item.hit());
    Double candidateFloor = unreachable ? strata.get(STRATUM_NEAR_DUPLICATE).p05() : null;
    return new Analysis(
        List.copyOf(pairs),
        strata,
        production,
        anchor,
        byKind,
        monotonicity,
        candidateFloor,
        judgements);
  }

  /**
   * 生产视图（floor / threshold 取闸门现值）。
   *
   * @param pairs 样本对
   * @return 视图
   */
  static View productionView(List<Pair> pairs) {
    return view(PRODUCTION_VIEW, PRODUCTION_FLOOR, PRODUCTION_THRESHOLD, pairs);
  }

  /**
   * 锚视图：floor = 实测异图上限，threshold = 其与同图下限的中点；不可分时不硬凑阈值（threshold = null、bands 为空）。
   *
   * @param pairs 样本对
   * @param sameImageFloor 同图下限（同图档最小值）
   * @param crossImageCeiling 异图上限（同类 ∪ 跨类的最大值）
   * @return 视图
   */
  static View anchorView(List<Pair> pairs, Double sameImageFloor, Double crossImageCeiling) {
    if (sameImageFloor == null || crossImageCeiling == null) {
      return view(
          ANCHOR_VIEW,
          crossImageCeiling == null ? PRODUCTION_FLOOR : crossImageCeiling,
          null,
          pairs);
    }
    double floor = crossImageCeiling;
    Double threshold =
        crossImageCeiling < sameImageFloor ? (crossImageCeiling + sameImageFloor) / 2D : null;
    return view(ANCHOR_VIEW, floor, threshold, pairs);
  }

  /**
   * 五档落带标签（与 {@code near-duplicate-band-stats.mjs} 的 {@code labelOf} 同序）：区间外落同图/异图。
   *
   * @param cosine 余弦
   * @param threshold 生效阈值（生产口径，floor 由闸门 {@link ImageSimilarityGate#effectiveFloor} 现算）
   * @return 五档标签之一
   */
  static String labelOf(double cosine, double threshold) {
    return labelOf(cosine, ImageSimilarityGate.effectiveFloor(threshold), threshold);
  }

  /**
   * 五档落带标签（带 floor 参数的通用式，锚视图用）。
   *
   * @param cosine 余弦
   * @param floor 生效下限
   * @param threshold 生效阈值
   * @return 五档标签之一
   */
  static String labelOf(double cosine, double floor, double threshold) {
    if (cosine >= threshold) {
      return ImageSimilarityGate.TIER_SAME_IMAGE;
    }
    String band = bandOf(cosine, floor, threshold);
    return band == null ? ImageSimilarityGate.TIER_CROSS_IMAGE : band;
  }

  /**
   * 生产口径落带（直接委托闸门，保证「算出来的带」= 「生产降权的带」）。
   *
   * @param cosine 余弦
   * @param threshold 生效阈值
   * @return 三档近似带之一；区间外 null
   */
  static String bandOf(double cosine, double threshold) {
    return ImageSimilarityGate.bandOf(cosine, threshold);
  }

  /**
   * 通用落带（带 floor 参数；公式与闸门逐项同形，边界值归上一档）。
   *
   * @param cosine 余弦
   * @param floor 生效下限
   * @param threshold 生效阈值
   * @return 三档近似带之一；区间外 null
   */
  static String bandOf(double cosine, double floor, double threshold) {
    String band;
    if (cosine < floor || cosine >= threshold) {
      band = null;
    } else if (cosine >= highBandLowerBound(floor, threshold)) {
      band = ImageSimilarityGate.BAND_NEAR_HIGH;
    } else if (cosine >= lowBandUpperBound(floor, threshold)) {
      band = ImageSimilarityGate.BAND_NEAR_MID;
    } else {
      band = ImageSimilarityGate.BAND_NEAR_LOW;
    }
    return band;
  }

  /**
   * 低/中边界（通用式 {@code floor + Δ/3}，与闸门同形）。
   *
   * @param floor 生效下限
   * @param threshold 生效阈值
   * @return 低档上界（= 中档下界）
   */
  static double lowBandUpperBound(double floor, double threshold) {
    return floor + (threshold - floor) / 3.0D;
  }

  /**
   * 中/高边界（通用式 {@code floor + 2Δ/3}，与闸门同形）。
   *
   * @param floor 生效下限
   * @param threshold 生效阈值
   * @return 高档下界（= 中档上界）
   */
  static double highBandLowerBound(double floor, double threshold) {
    return floor + 2.0D * (threshold - floor) / 3.0D;
  }

  /**
   * 线性插值分位（与 {@code near-duplicate-band-stats.mjs} 同式，保证脚本逐数复算一致）。
   *
   * @param sorted 升序样本
   * @param p 分位（0–1）
   * @return 分位值；空表 null
   */
  static Double quantile(List<Double> sorted, double p) {
    Double result = null;
    if (!sorted.isEmpty()) {
      if (sorted.size() == 1) {
        result = sorted.get(0);
      } else {
        double position = (sorted.size() - 1) * p;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double weight = position - lower;
        result = sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
      }
    }
    return result;
  }

  /**
   * 分位摘要（空表所有分位为 null）。
   *
   * @param values 余弦
   * @return 摘要
   */
  static Stats stats(List<Double> values) {
    Stats result;
    if (values.isEmpty()) {
      result = new Stats(0, null, null, null, null, null, null, null, null);
    } else {
      List<Double> sorted = new ArrayList<>(values);
      sorted.sort(Double::compare);
      double sum = 0D;
      for (double value : sorted) {
        sum += value;
      }
      result =
          new Stats(
              sorted.size(),
              sorted.get(0),
              quantile(sorted, 0.05D),
              quantile(sorted, 0.25D),
              quantile(sorted, 0.50D),
              quantile(sorted, 0.75D),
              quantile(sorted, 0.95D),
              sorted.get(sorted.size() - 1),
              sum / sorted.size());
    }
    return result;
  }

  /**
   * 按变体类型分组统计（顺序 = {@link NearDuplicateCorpus#KINDS}，强度从轻到重）。
   *
   * @param pairs 样本对
   * @return 按类型的统计
   */
  static List<KindStat> byKind(List<Pair> pairs) {
    List<KindStat> result = new ArrayList<>();
    for (String kind : NearDuplicateCorpus.KINDS) {
      List<Double> values = new ArrayList<>();
      for (Pair pair : pairs) {
        if (STRATUM_NEAR_DUPLICATE.equals(pair.stratum()) && kind.equals(pair.kind())) {
          values.add(pair.cosine());
        }
      }
      result.add(new KindStat(kind, stats(values)));
    }
    return List.copyOf(result);
  }

  /**
   * 强度单调性：中位数沿 {@link NearDuplicateCorpus#KINDS} 应非递增（变换越重越不像）；反序单点记录不失败。
   *
   * @param byKind 按类型统计
   * @return 单调性结论
   */
  static Monotonicity monotonicity(List<KindStat> byKind) {
    List<String> kinds = new ArrayList<>();
    List<Double> medians = new ArrayList<>();
    for (KindStat stat : byKind) {
      kinds.add(stat.kind());
      medians.add(stat.stats().median());
    }
    List<Inversion> inversions = new ArrayList<>();
    for (int index = 1; index < medians.size(); index++) {
      Double previous = medians.get(index - 1);
      Double current = medians.get(index);
      if (previous != null && current != null && current > previous) {
        inversions.add(new Inversion(kinds.get(index - 1), kinds.get(index), previous, current));
      }
    }
    return new Monotonicity(
        List.copyOf(kinds),
        Collections.unmodifiableList(new ArrayList<>(medians)),
        inversions.isEmpty(),
        List.copyOf(inversions));
  }

  /**
   * 判定树逐支照判（proposal §4 的条件原文写死在这里；跑之后不许改口径）。
   *
   * @param production 生产视图
   * @param anchor 锚视图
   * @param sameImageFloor 同图下限
   * @param crossImageCeiling 异图上限
   * @return F0–F5 六支结论
   */
  static List<Judgement> judge(
      View production, View anchor, Double sameImageFloor, Double crossImageCeiling) {
    List<Judgement> result = new ArrayList<>();
    BandStat high = band(production, ImageSimilarityGate.BAND_NEAR_HIGH);
    BandStat mid = band(production, ImageSimilarityGate.BAND_NEAR_MID);
    BandStat low = band(production, ImageSimilarityGate.BAND_NEAR_LOW);
    List<BandStat> near = List.of(high, mid, low);

    boolean allEnough = near.stream().allMatch(stat -> stat.count() >= MIN_BAND_SAMPLES);
    boolean allPrecise =
        near.stream()
            .allMatch(
                stat ->
                    stat.nearDuplicateShare() != null
                        && stat.nearDuplicateShare() >= MIN_NEAR_DUPLICATE_PRECISION);
    result.add(
        new Judgement(
            "F0",
            "每带 NEAR_HIGH/NEAR_MID/NEAR_LOW >= 30 对且带内近重复占比 >= 0.5",
            allEnough && allPrecise,
            "productionView " + describe(near) + "（门槛 30 对 / 0.5）"));

    Double belowShare = production.nearDuplicateBelowFloorShare();
    boolean unreachable = belowShare != null && belowShare > UNREACHABLE_SHARE;
    result.add(
        new Judgement(
            "F1",
            "nearDuplicate 对中余弦 < 0.9887 的比例 > 0.5",
            unreachable,
            "productionView.nearDuplicateBelowFloor="
                + production.nearDuplicateBelowFloor()
                + "/"
                + production.nearDuplicateCount()
                + "（占比 "
                + share(belowShare)
                + "，门槛 0.5）"));

    boolean inseparable =
        sameImageFloor != null && crossImageCeiling != null && crossImageCeiling >= sameImageFloor;
    result.add(
        new Judgement(
            "F2",
            "anchorView 不可分（crossImageCeiling >= sameImageFloor）",
            inseparable,
            "strata[\"same-image\"].min="
                + sameImageFloor
                + "，crossImageCeiling="
                + crossImageCeiling
                + "，anchorView.separable="
                + anchor.separable()));

    List<BandStat> impure =
        near.stream()
            .filter(stat -> stat.count() >= MIN_BAND_SAMPLES)
            .filter(
                stat ->
                    stat.nearDuplicateShare() == null
                        || stat.nearDuplicateShare() < MIN_NEAR_DUPLICATE_PRECISION)
            .toList();
    result.add(
        new Judgement(
            "F3",
            "某带 >= 30 对但带内近重复占比 < 0.5",
            !impure.isEmpty(),
            impure.isEmpty() ? "无「样本足但带内以异图为主」的带：" + describe(near) : "命中带：" + describe(impure)));

    List<BandStat> thin = near.stream().filter(stat -> stat.count() < MIN_BAND_SAMPLES).toList();
    result.add(
        new Judgement(
            "F4",
            "任一近似带 < 30 对",
            !thin.isEmpty(),
            thin.isEmpty() ? "三带均 >= 30 对：" + describe(near) : "样本不足带：" + describe(thin)));

    boolean anchorWider =
        NEAR_BANDS.stream()
            .allMatch(
                bandName -> band(anchor, bandName).count() > band(production, bandName).count());
    result.add(
        new Judgement(
            "F5",
            "anchorView 每带样本数 > productionView 同带样本数，且 F1 未触发",
            anchorWider && !unreachable,
            "productionView "
                + describe(near)
                + " vs anchorView "
                + describe(
                    List.of(
                        band(anchor, ImageSimilarityGate.BAND_NEAR_HIGH),
                        band(anchor, ImageSimilarityGate.BAND_NEAR_MID),
                        band(anchor, ImageSimilarityGate.BAND_NEAR_LOW)))
                + "（F1 命中="
                + unreachable
                + "）"));
    return List.copyOf(result);
  }

  /**
   * 锚 JSON 装配（v2 锚的唯一写入口在 IT，本方法只负责结构，不写盘）。
   *
   * @param analysis 分析结果
   * @param meta 语料元信息
   * @param generatedAt 生成时间（ISO-8601，由 IT 给）
   * @param optIn opt-in 与语料说明
   * @return JSON 根节点
   */
  static ObjectNode toJson(Analysis analysis, CorpusMeta meta, String generatedAt, String optIn) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("calibrationVersion", CALIBRATION_VERSION);
    root.put("generatedAt", generatedAt);
    root.put("optIn", optIn);

    ObjectNode corpus = root.putObject("corpus");
    corpus.put("bases", meta.bases());
    corpus.put("variants", meta.variants());
    corpus.put("images", meta.images());
    corpus.put("imageEmbeddingCalls", meta.imageEmbeddingCalls());
    corpus.put("skips", meta.skips());
    ArrayNode kinds = corpus.putArray("kinds");
    NearDuplicateCorpus.KINDS.forEach(kinds::add);
    ArrayNode documents = corpus.putArray("documents");
    for (NearDuplicateCorpus.DocumentCount document : meta.documents()) {
      ObjectNode node = documents.addObject();
      node.put("key", document.docKey());
      node.put("requested", document.requested());
      node.put("taken", document.taken());
    }
    ArrayNode skipDetail = corpus.putArray("skipDetail");
    for (NearDuplicateCorpus.Skip skip : meta.skipDetail()) {
      ObjectNode node = skipDetail.addObject();
      node.put("baseKey", skip.baseKey());
      node.put("kind", skip.kind());
      node.put("width", skip.width());
      node.put("height", skip.height());
      node.put("reason", skip.reason());
    }
    ArrayNode baseDetail = corpus.putArray("baseDetail");
    for (NearDuplicateCorpus.BasePixel pixel : meta.baseDetail()) {
      ObjectNode node = baseDetail.addObject();
      node.put("baseKey", pixel.baseKey());
      node.put("colorModel", pixel.colorModel());
      node.put("hasAlpha", pixel.hasAlpha());
      node.put("uniqueColors", pixel.uniqueColors());
      node.put("mean", pixel.mean());
      node.put("stdDev", pixel.stdDev());
    }
    corpus.put("excludedBaseCount", meta.excludedBases().size());
    ArrayNode excludedBases = corpus.putArray("excludedBases");
    for (NearDuplicateCorpus.ExcludedBase excluded : meta.excludedBases()) {
      ObjectNode node = excludedBases.addObject();
      node.put("baseKey", excluded.baseKey());
      node.put("uniqueColors", excluded.uniqueColors());
      node.put("reason", excluded.reason());
    }

    ObjectNode strata = root.putObject("strata");
    analysis.strata().forEach((name, stat) -> strata.set(name, statsNode(stat)));

    root.set(PRODUCTION_VIEW, viewNode(analysis.productionView()));
    root.set(ANCHOR_VIEW, viewNode(analysis.anchorView()));

    ObjectNode byKind = root.putObject("nearDuplicateByKind");
    for (KindStat stat : analysis.byKind()) {
      byKind.set(stat.kind(), statsNode(stat.stats()));
    }

    ObjectNode monotonicity = root.putObject("monotonicity");
    ArrayNode monotonicityKinds = monotonicity.putArray("kinds");
    analysis.monotonicity().kinds().forEach(monotonicityKinds::add);
    ArrayNode medians = monotonicity.putArray("medians");
    analysis.monotonicity().medians().forEach(medians::add);
    monotonicity.put("monotonic", analysis.monotonicity().monotonic());
    ArrayNode inversions = monotonicity.putArray("inversions");
    for (Inversion inversion : analysis.monotonicity().inversions()) {
      ObjectNode node = inversions.addObject();
      node.put("leftKind", inversion.leftKind());
      node.put("rightKind", inversion.rightKind());
      node.put("leftMedian", inversion.leftMedian());
      node.put("rightMedian", inversion.rightMedian());
    }

    if (analysis.candidateFloor() == null) {
      root.putNull("candidateFloor");
    } else {
      root.put("candidateFloor", analysis.candidateFloor());
    }

    ArrayNode judgements = root.putArray("judgements");
    for (Judgement judgement : analysis.judgements()) {
      ObjectNode node = judgements.addObject();
      node.put("code", judgement.code());
      node.put("condition", judgement.condition());
      node.put("hit", judgement.hit());
      node.put("evidence", judgement.evidence());
    }

    ArrayNode pairs = root.putArray("pairs");
    for (Pair pair : analysis.pairs()) {
      ObjectNode node = pairs.addObject();
      node.put("leftKey", pair.leftKey());
      node.put("rightKey", pair.rightKey());
      node.put("stratum", pair.stratum());
      if (pair.kind() == null) {
        node.putNull("kind");
      } else {
        node.put("kind", pair.kind());
      }
      node.put("cosine", pair.cosine());
    }

    root.put(
        "note",
        "同图档 = 同字节两次编码的余弦；近重复档 = base 与其同源变体（5 类变换）的余弦；"
            + "cross 只取 base 两两（同出版物 = same-class，跨出版物 = cross-class）；"
            + "candidateFloor 仅在 F1 命中时取 near-duplicate 档 p05，否则 null（proposal §9.5），"
            + "本锚不据此改任何常量；corpus.baseDetail 是逐 base 的白底合成像素属性（唯一色数 / 均值 / 标准差），"
            + "唯一色数 <= 1 的 base 进 corpus.excludedBases 并从语料剔除");
    return root;
  }

  /** 视图 → JSON（band 表、精度、belowFloor / aboveThreshold、近重复可达性）。 */
  private static ObjectNode viewNode(View view) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("name", view.name());
    node.put("floor", view.floor());
    if (view.threshold() == null) {
      node.putNull("threshold");
    } else {
      node.put("threshold", view.threshold());
    }
    node.put("separable", view.separable());
    node.put("pairCount", view.pairCount());
    node.put("belowFloor", view.belowFloor());
    node.put("aboveThreshold", view.aboveThreshold());
    node.put("nearDuplicateCount", view.nearDuplicateCount());
    node.put("nearDuplicateBelowFloor", view.nearDuplicateBelowFloor());
    if (view.nearDuplicateBelowFloorShare() == null) {
      node.putNull("nearDuplicateBelowFloorShare");
    } else {
      node.put("nearDuplicateBelowFloorShare", view.nearDuplicateBelowFloorShare());
    }
    ArrayNode bands = node.putArray("bands");
    for (BandStat stat : view.bands()) {
      ObjectNode band = bands.addObject();
      band.put("band", stat.band());
      band.put("count", stat.count());
      band.put("nearDuplicateCount", stat.nearDuplicateCount());
      if (stat.nearDuplicateShare() == null) {
        band.putNull("nearDuplicateShare");
      } else {
        band.put("nearDuplicateShare", stat.nearDuplicateShare());
      }
      band.set("stats", statsNode(stat.stats()));
    }
    return node;
  }

  /** 分位摘要 → JSON（null 写成 JSON null，不写成 0）。 */
  private static ObjectNode statsNode(Stats stats) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("count", stats.count());
    putNullable(node, "min", stats.min());
    putNullable(node, "p05", stats.p05());
    putNullable(node, "p25", stats.p25());
    putNullable(node, "median", stats.median());
    putNullable(node, "p75", stats.p75());
    putNullable(node, "p95", stats.p95());
    putNullable(node, "max", stats.max());
    putNullable(node, "mean", stats.mean());
    return node;
  }

  private static void putNullable(ObjectNode node, String field, Double value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  /** 单视图落带的公共实现（不可分时 threshold = null → bands 为空表）。 */
  private static View view(String name, double floor, Double threshold, List<Pair> pairs) {
    Map<String, List<Pair>> byBand = new LinkedHashMap<>();
    for (String band : BANDS) {
      byBand.put(band, new ArrayList<>());
    }
    int belowFloor = 0;
    int aboveThreshold = 0;
    int nearDuplicateCount = 0;
    int nearDuplicateBelowFloor = 0;
    for (Pair pair : pairs) {
      boolean nearDuplicate = STRATUM_NEAR_DUPLICATE.equals(pair.stratum());
      if (nearDuplicate) {
        nearDuplicateCount++;
      }
      if (pair.cosine() < floor) {
        belowFloor++;
        if (nearDuplicate) {
          nearDuplicateBelowFloor++;
        }
      }
      if (threshold != null) {
        if (pair.cosine() >= threshold) {
          aboveThreshold++;
        }
        byBand.get(labelOf(pair.cosine(), floor, threshold)).add(pair);
      }
    }
    List<BandStat> bands = new ArrayList<>();
    for (String band : BANDS) {
      List<Double> values = new ArrayList<>();
      int nearDuplicates = 0;
      for (Pair pair : byBand.get(band)) {
        values.add(pair.cosine());
        if (STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
          nearDuplicates++;
        }
      }
      Double bandShare = values.isEmpty() ? null : (double) nearDuplicates / values.size();
      bands.add(new BandStat(band, values.size(), nearDuplicates, bandShare, stats(values)));
    }
    Double nearDuplicateBelowFloorShare =
        nearDuplicateCount == 0 ? null : (double) nearDuplicateBelowFloor / nearDuplicateCount;
    return new View(
        name,
        floor,
        threshold,
        threshold != null,
        pairs.size(),
        List.copyOf(bands),
        belowFloor,
        aboveThreshold,
        nearDuplicateCount,
        nearDuplicateBelowFloor,
        nearDuplicateBelowFloorShare);
  }

  private static List<Double> cosines(List<Pair> pairs, String stratum) {
    List<Double> values = new ArrayList<>();
    for (Pair pair : pairs) {
      if (stratum.equals(pair.stratum())) {
        values.add(pair.cosine());
      }
    }
    return values;
  }

  private static Double crossImageCeiling(Map<String, Stats> strata) {
    Double ceiling = null;
    for (String stratum : List.of(STRATUM_SAME_CLASS, STRATUM_CROSS_CLASS)) {
      Double max = strata.get(stratum).max();
      if (max != null && (ceiling == null || max > ceiling)) {
        ceiling = max;
      }
    }
    return ceiling;
  }

  private static BandStat band(View view, String name) {
    BandStat found = new BandStat(name, 0, 0, null, stats(List.of()));
    for (BandStat stat : view.bands()) {
      if (stat.band().equals(name)) {
        found = stat;
        break;
      }
    }
    return found;
  }

  private static String describe(List<BandStat> stats) {
    List<String> parts = new ArrayList<>();
    for (BandStat stat : stats) {
      parts.add(
          stat.band() + "=" + stat.count() + "(近重复占比 " + share(stat.nearDuplicateShare()) + ")");
    }
    return String.join(" ", parts);
  }

  private static String share(Double value) {
    return value == null ? "—" : String.format(java.util.Locale.ROOT, "%.4f", value);
  }
}
