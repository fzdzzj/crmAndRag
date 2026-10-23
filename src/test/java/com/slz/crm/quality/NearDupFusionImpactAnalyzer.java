package com.slz.crm.quality;

import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 近重复「融合级代价边界」离线分析器（query-relevance-truth G1，任务 1.1.1~1.1.5，零外呼）。
 *
 * <p><b>回答的问题</b>：降权在检索排序里到底有没有可见效果。生产链路（实测源码）是「文本召回+重排 → 图像召回+gate+重排+{@code
 * downWeightNearDuplicates} → {@code fuseRoutes}(0.7/0.3 分数制同切片累加) → 按 rerankScore 降序取 top-k」，即
 * <b>降权发生在融合之前</b>；由此两条可证明推论由本类数值落证：
 *
 * <ul>
 *   <li><b>推论 1（路由内不改序，R2）</b>：三档带按 cosine 三等分、权重按带单调（{@code 0.5<0.6<0.75<1}）、带边界归上一档 ⇒ {@code
 *       score(c)=c×w(tier(c))} 在 {@code [floor, threshold]} 上仍按 cosine 单调——用 5000 点等距采样 + 边界点数值验证，
 *       任一处反序即推论被推翻（上报，不改判据）；
 *   <li><b>推论 2（跨路位移上界，R3）</b>：融合是分数制，图像候选被乘 {@code w} 后融合贡献从 {@code imageWeight×c} 降到 {@code
 *       imageWeight×w×c} ⇒ 位移上界 {@code bound(w)=imageWeight×(1−w)}；只有竞争对手融合分与该候选之差 ≤ 上界时位移才可能发生
 *       （可见条件）。
 * </ul>
 *
 * <p><b>R4 gap 分布</b>：以基线档 {@code [floor, threshold)} 内的 near-duplicate 候选（查询=变体，被降权方=其 base
 * 黄金切片）为位移主体， 竞争者分数取自校准锚 base 两两余弦（<b>代理</b>：锚内无变体↔第三图对，以 base 对为代理且未经验证，结论因此是必要非充分）；同文档/跨文档分列，
 * 换算融合分 gap 上界 {@code imageWeight×Δcosine}。<b>该分布不是召回率</b>：位移可能 ≠ 位移发生 ≠ 召回损失。
 *
 * <p><b>判据口径（proposal §1.2 R1–R5，跑前写死、跑后禁改）</b>见锚 {@code criteria} 逐字回显。
 *
 * <p><b>零外呼</b>：整类只有算术与内存容器，无 I/O、无网络、无环境变量。
 */
final class NearDupFusionImpactAnalyzer {

  /** 生产判别阈值（{@code ImageSimilarityGate.DEFAULT_THRESHOLD} 现值引用：生产一改，本类跟着变，锚比对即红）。 */
  static final double THRESHOLD = ImageSimilarityGate.DEFAULT_THRESHOLD;

  /** 生产近重复下限（{@code ImageSimilarityGate.NEAR_DUPLICATE_FLOOR} 现值引用）。 */
  static final double BASELINE_FLOOR = ImageSimilarityGate.NEAR_DUPLICATE_FLOOR;

  /** R2 等距采样点数（proposal 判据原文写死 5000）。 */
  static final int R2_SAMPLE_POINTS = 5000;

  /** 基线 floor 的基点整数（0.9887 → 9887），等距采样用整数插值避免浮点累加漂移。 */
  private static final int FLOOR_BASIS_POINTS = 9887;

  /** 阈值的基点整数（0.9944 → 9944）。 */
  private static final int THRESHOLD_BASIS_POINTS = 9944;

  /** 位移判定的浮点容差（并列不判位移）。 */
  private static final double EPS = 1e-12D;

  /** 校准锚同图档（两次编码，本卡用于 24 base 的键归属校验）。 */
  static final String STRATUM_SAME_IMAGE = "same-image";

  /** 校准锚同源变体档（base↔变体，位移主体的来源）。 */
  static final String STRATUM_NEAR_DUPLICATE = "near-duplicate";

  /** 校准锚同出版物异图档（同文档竞争者）。 */
  static final String STRATUM_SAME_CLASS = "same-class";

  /** 校准锚跨出版物档（跨文档竞争者）。 */
  static final String STRATUM_CROSS_CLASS = "cross-class";

  /** R5 结论一：位移上界 > 0 且可见条件已量化（或路由内出现反序——推论 1 被推翻时的唯一合法落点）。 */
  static final String VERDICT_VISIBLE_BOUNDED = "VISIBLE-BOUNDED";

  /** R5 结论二：只能证明路由内不可见，跨路可见性缺文本路分数，无法定案。 */
  static final String VERDICT_INVISIBLE_IN_ROUTE_ONLY = "INVISIBLE-IN-ROUTE-ONLY";

  /** R5 结论三：数据不足以做任何口径的判定。 */
  static final String VERDICT_UNDECIDABLE = "UNDECIDABLE-BY-DATA";

  /** 三选一封闭集（守卫测试比对用）。 */
  static final List<String> VERDICTS =
      List.of(VERDICT_VISIBLE_BOUNDED, VERDICT_INVISIBLE_IN_ROUTE_ONLY, VERDICT_UNDECIDABLE);

  /** 图像路命中率期望值：同图档 144（24 base + 120 变体，两次编码逐图 1 对）。 */
  static final int SAME_IMAGE_TOTAL = 144;

  /** 同源变体档分母（strata.near-duplicate 实测 120）。 */
  static final int NEAR_DUP_TOTAL = 120;

  /** 同出版物异图档分母（strata.same-class 实测 100）。 */
  static final int SAME_CLASS_TOTAL = 100;

  /** 跨出版物档分母（strata.cross-class 实测 176）。 */
  static final int CROSS_CLASS_TOTAL = 176;

  /** base 两两组合总数 = 100 + 176。 */
  static final int BASE_PAIR_TOTAL = SAME_CLASS_TOTAL + CROSS_CLASS_TOTAL;

  private NearDupFusionImpactAnalyzer() {}

  /**
   * 一对样本（校准锚 {@code pairs[]} 全字段，R4 需要键做文档归属）。
   *
   * @param stratum 档（同图 / 同源变体 / 同出版物异图 / 跨出版物）
   * @param leftKey 左图键（base 或图自身）
   * @param rightKey 右图键（变体键形如 {@code baseKey@kind}）
   * @param kind 变体类型（仅同源变体档非空）
   * @param cosine 两次真视觉 embedding 的余弦
   */
  record Pair(String stratum, String leftKey, String rightKey, String kind, double cosine) {}

  /**
   * 一个降权档的位移上界（R3 一行）。
   *
   * @param band 近似带名（{@code NEAR_HIGH/NEAR_MID/NEAR_LOW}）
   * @param w 降权系数（{@code ImageSimilarityGate} 三档权重现值）
   * @param bound 位移上界 = {@code imageRouteWeight × (1 − w)}（融合分口径）
   */
  record Bound(String band, double w, double bound) {}

  /**
   * R2 路由内单调性报告。
   *
   * @param samplePoints 实际采样点数（5000 等距 + 边界点去重后）
   * @param inversions 反序处数（相邻点 {@code score 下降} 超容差）
   * @param maxDrop 最大单步降幅（0 = 无反序）
   * @param strictlyIncreasing 是否全程严格递增（同序判据允许并列，此字段只作披露）
   */
  record MonotonicityReport(
      int samplePoints, long inversions, double maxDrop, boolean strictlyIncreasing) {

    /**
     * @return R2 判定：反序为 0 即 {@code MONOTONIC}
     */
    String verdict() {
      return inversions == 0L ? "MONOTONIC" : "ORDER-BROKEN";
    }
  }

  /**
   * 一个带内降权候选（R4 位移主体）。
   *
   * @param baseKey 其 base 图键（黄金切片归属）
   * @param variantKey 变体图键（查询侧）
   * @param kind 变体类型
   * @param cosine base↔变体余弦（路由内分数， ∈ [floor, threshold)）
   */
  record BandCandidate(String baseKey, String variantKey, String kind, double cosine) {}

  /**
   * 一条竞争者观测（R4：候选 vs 竞争者，同查询共现假设下的分数对）。
   *
   * @param baseKey 被降权候选的 base 键
   * @param competitorKey 竞争者 base 键
   * @param sameDoc 竞争者与候选是否同文档（docKey 相同）
   * @param candidateCosine 被降权候选的路由分（base↔变体余弦）
   * @param competitorCosine 竞争者路由分（base↔competitor 余弦，<b>代理值</b>：真实查询是变体）
   */
  record CompetitorObservation(
      String baseKey,
      String competitorKey,
      boolean sameDoc,
      double candidateCosine,
      double competitorCosine) {

    /**
     * @return 余弦 gap = 候选分 − 竞争者分（正 = 竞争者排在候选下方）
     */
    double gap() {
      return candidateCosine - competitorCosine;
    }

    /**
     * @return 给定降权系数下位移是否数学上可能：{@code w×c < c2 < c}（竞争者落在降权窗口内）
     */
    boolean displacementPossible(double w) {
      return competitorCosine > candidateCosine * w + EPS
          && competitorCosine < candidateCosine - EPS;
    }
  }

  /**
   * 单一分层的 gap 分布（R4 一半）。
   *
   * @param observations 观测条数
   * @param displacementPossibleByW 逐降权系数下「位移数学上可能」的观测条数
   * @param p50 / p90 / p99 余弦 gap 分位（nearest-rank）
   * @param maxGap 最大余弦 gap
   * @param maxFusedGap 最大融合分 gap = {@code imageRouteWeight × round6(maxGap)}（从已披露的 maxGap
   *     派生，读者复算可对上）
   */
  record GapDistribution(
      int observations,
      Map<String, Integer> displacementPossibleByW,
      double p50,
      double p90,
      double p99,
      double maxGap,
      double maxFusedGap) {}

  /**
   * 键与分母对齐 fail-fast：strata 计数必须精确等于 144/120/100/176 且 base 去重恰 24，否则抛。
   *
   * @param pairs 校准锚样本对
   */
  static void requireStrata(List<Pair> pairs) {
    int sameImage = 0;
    int nearDup = 0;
    int sameClass = 0;
    int crossClass = 0;
    Set<String> bases = new LinkedHashSet<>();
    for (Pair pair : pairs) {
      switch (pair.stratum()) {
        case STRATUM_SAME_IMAGE -> sameImage++;
        case STRATUM_NEAR_DUPLICATE -> {
          nearDup++;
          bases.add(pair.leftKey());
        }
        case STRATUM_SAME_CLASS, STRATUM_CROSS_CLASS -> {
          if (STRATUM_SAME_CLASS.equals(pair.stratum())) {
            sameClass++;
          } else {
            crossClass++;
          }
          bases.add(pair.leftKey());
          bases.add(pair.rightKey());
        }
        default -> throw new IllegalStateException("未知 stratum: " + pair.stratum());
      }
    }
    if (sameImage != SAME_IMAGE_TOTAL
        || nearDup != NEAR_DUP_TOTAL
        || sameClass != SAME_CLASS_TOTAL
        || crossClass != CROSS_CLASS_TOTAL
        || bases.size() != NEAR_DUP_TOTAL / 5) {
      throw new IllegalStateException(
          "strata 计数与判据分母不一致: same-image="
              + sameImage
              + " near-duplicate="
              + nearDup
              + " same-class="
              + sameClass
              + " cross-class="
              + crossClass
              + " bases="
              + bases.size());
    }
  }

  /**
   * 图像路内单点路由分（R2 的被测函数，语义 = 生产 {@code downWeightNearDuplicates} 现值）：同图档不降权（×1），近似带按 {@code
   * weightOfBand(bandOf(c))} 降权，异图档不在本卡定义域。
   *
   * @param cosine 图像路余弦（ ∈ [floor, threshold]）
   * @param threshold 生效阈值
   * @return 降权后的路由分
   */
  static double routeScore(double cosine, double threshold) {
    if (cosine < ImageSimilarityGate.effectiveFloor(threshold) - EPS || cosine > threshold + EPS) {
      throw new IllegalArgumentException("cosine 超出图像路保留定义域 [floor, threshold]: " + cosine);
    }
    String tier = ImageSimilarityGate.tierOf(cosine, threshold);
    double score;
    if (ImageSimilarityGate.TIER_SAME_IMAGE.equals(tier)) {
      score = cosine;
    } else {
      score =
          cosine * ImageSimilarityGate.weightOfBand(ImageSimilarityGate.bandOf(cosine, threshold));
    }
    return score;
  }

  /**
   * R2 路由内单调性：对 {@code [floor, threshold]} 上 5000 点整数插值等距采样 + 低/高档边界点，断言路由分与 cosine 同序（允许并列）。
   *
   * @param threshold 生效阈值
   * @return 单调性报告（反序数 = 0 即推论 1 成立）
   */
  static MonotonicityReport checkRouteInternalMonotonicity(double threshold) {
    TreeSet<Double> samples = new TreeSet<>();
    int n = R2_SAMPLE_POINTS - 1;
    for (int i = 0; i <= n; i++) {
      samples.add(
          (FLOOR_BASIS_POINTS * (double) (n - i) + THRESHOLD_BASIS_POINTS * (double) i)
              / (n * 10000D));
    }
    samples.add(ImageSimilarityGate.lowBandUpperBound(threshold));
    samples.add(ImageSimilarityGate.highBandLowerBound(threshold));
    long inversions = 0L;
    double maxDrop = 0D;
    boolean strictly = true;
    double previous = -1D;
    for (Double cosine : samples) {
      double score = routeScore(cosine, threshold);
      if (previous >= 0D) {
        if (score < previous - EPS) {
          inversions++;
          maxDrop = Math.max(maxDrop, previous - score);
        } else if (score <= previous + EPS) {
          strictly = false;
        }
      }
      previous = score;
    }
    return new MonotonicityReport(samples.size(), inversions, round6(maxDrop), strictly);
  }

  /**
   * R3 位移上界逐档：{@code bound(w) = imageRouteWeight × (1 − w)}，w 取 {@code ImageSimilarityGate} 三档权重现值。
   *
   * @param imageRouteWeight 图像路融合权重（由写入口从 {@code RetrievalConfigResolver} 源码实测传入）
   * @return 三档上界（高/中/低带序）
   */
  static List<Bound> bounds(double imageRouteWeight) {
    List<Bound> bounds = new ArrayList<>(3);
    bounds.add(
        new Bound(
            ImageSimilarityGate.BAND_NEAR_HIGH,
            ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH,
            round6(imageRouteWeight * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH))));
    bounds.add(
        new Bound(
            ImageSimilarityGate.BAND_NEAR_MID,
            ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID,
            round6(imageRouteWeight * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID))));
    bounds.add(
        new Bound(
            ImageSimilarityGate.BAND_NEAR_LOW,
            ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW,
            round6(imageRouteWeight * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW))));
    return List.copyOf(bounds);
  }

  /**
   * 基线档的带内降权候选集：near-duplicate 对中 {@code [floor, threshold)} 者（R4 位移主体）。
   *
   * @param pairs 校准锚样本对
   * @param floor 生效下限
   * @param threshold 生效阈值
   * @return 带内候选（按 cosine 降序）
   */
  static List<BandCandidate> bandCandidates(List<Pair> pairs, double floor, double threshold) {
    List<BandCandidate> candidates = new ArrayList<>();
    for (Pair pair : pairs) {
      if (STRATUM_NEAR_DUPLICATE.equals(pair.stratum())
          && pair.cosine() >= floor
          && pair.cosine() < threshold) {
        if (!pair.rightKey().startsWith(pair.leftKey() + "@")) {
          throw new IllegalStateException(
              "near-duplicate 对键形状异常（leftKey 应为 base）: " + pair.leftKey() + "/" + pair.rightKey());
        }
        candidates.add(
            new BandCandidate(pair.leftKey(), pair.rightKey(), pair.kind(), pair.cosine()));
      }
    }
    candidates.sort((a, b) -> Double.compare(b.cosine(), a.cosine()));
    return List.copyOf(candidates);
  }

  /**
   * R4 竞争者观测：对每个带内候选，取 base 两两余弦中涉及其 base 且 {@code >= floor} 的对为竞争者（代理口径，见类注）。
   *
   * @param pairs 校准锚样本对
   * @param candidates 带内候选
   * @param floor 生效下限
   * @return 观测列表（按 gap 降序）
   */
  static List<CompetitorObservation> competitorObservations(
      List<Pair> pairs, List<BandCandidate> candidates, double floor) {
    Map<String, Map<String, Double>> adjacency = basePairAdjacency(pairs);
    List<CompetitorObservation> observations = new ArrayList<>();
    for (BandCandidate candidate : candidates) {
      Map<String, Double> neighbors = adjacency.get(candidate.baseKey());
      if (neighbors == null) {
        continue;
      }
      for (Map.Entry<String, Double> entry : neighbors.entrySet()) {
        if (entry.getValue() >= floor) {
          observations.add(
              new CompetitorObservation(
                  candidate.baseKey(),
                  entry.getKey(),
                  docKeyOf(entry.getKey()).equals(docKeyOf(candidate.baseKey())),
                  candidate.cosine(),
                  entry.getValue()));
        }
      }
    }
    observations.sort((a, b) -> Double.compare(b.gap(), a.gap()));
    return List.copyOf(observations);
  }

  /**
   * R4 单分层 gap 分布（分位 nearest-rank：p50/p90/p99 + 最大；融合分 gap = imageRouteWeight × 余弦 gap）。
   *
   * @param observations 全部竞争者观测（本方法按 {@code sameDoc} 自行分层）
   * @param sameDoc true=同文档层，false=跨文档层
   * @param imageRouteWeight 图像路融合权重
   * @return 该层分布
   */
  static GapDistribution gapDistribution(
      List<CompetitorObservation> observations, boolean sameDoc, double imageRouteWeight) {
    List<Double> gaps = new ArrayList<>();
    for (CompetitorObservation observation : observations) {
      if (observation.sameDoc() == sameDoc) {
        gaps.add(observation.gap());
      }
    }
    Collections.sort(gaps);
    Map<String, Integer> possible = new LinkedHashMap<>();
    for (double w :
        List.of(
            ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH,
            ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID,
            ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW)) {
      int count = 0;
      for (CompetitorObservation observation : observations) {
        if (observation.sameDoc() == sameDoc && observation.displacementPossible(w)) {
          count++;
        }
      }
      possible.put(String.valueOf(w), count);
    }
    double maxGap = gaps.isEmpty() ? 0D : gaps.get(gaps.size() - 1);
    return new GapDistribution(
        gaps.size(),
        Collections.unmodifiableMap(possible),
        quantile(gaps, 0.50D),
        quantile(gaps, 0.90D),
        quantile(gaps, 0.99D),
        round6(maxGap),
        round6(round6(maxGap) * imageRouteWeight));
  }

  /**
   * R5 三值判定（按序短路，禁造第四种）：
   *
   * <ol>
   *   <li>路由内出现反序（R2 推论被数据推翻）⇒ {@link #VERDICT_VISIBLE_BOUNDED}（路由内即可见，跨路上界仍成立）；
   *   <li>路由内不改序且跨路可见性不可定案（缺文本路分数分布）⇒ {@link #VERDICT_INVISIBLE_IN_ROUTE_ONLY}；
   *   <li>否则（跨路可见条件已量化）⇒ {@link #VERDICT_VISIBLE_BOUNDED}。
   * </ol>
   *
   * @param monotonicity R2 报告
   * @param crossRouteDecidable 跨路可见性是否已定案（本卡恒 false：缺文本路分数）
   * @return 三选一
   */
  static String verdict(MonotonicityReport monotonicity, boolean crossRouteDecidable) {
    String verdict;
    if (monotonicity.inversions() > 0L) {
      verdict = VERDICT_VISIBLE_BOUNDED;
    } else if (!crossRouteDecidable) {
      verdict = VERDICT_INVISIBLE_IN_ROUTE_ONLY;
    } else {
      verdict = VERDICT_VISIBLE_BOUNDED;
    }
    return verdict;
  }

  /** base 两两邻接表（same-class + cross-class 无序对双向登记；near-duplicate/same-image 不入表）。 */
  private static Map<String, Map<String, Double>> basePairAdjacency(List<Pair> pairs) {
    Map<String, Map<String, Double>> adjacency = new LinkedHashMap<>();
    for (Pair pair : pairs) {
      if (!STRATUM_SAME_CLASS.equals(pair.stratum())
          && !STRATUM_CROSS_CLASS.equals(pair.stratum())) {
        continue;
      }
      adjacency
          .computeIfAbsent(pair.leftKey(), key -> new LinkedHashMap<>())
          .put(pair.rightKey(), pair.cosine());
      adjacency
          .computeIfAbsent(pair.rightKey(), key -> new LinkedHashMap<>())
          .put(pair.leftKey(), pair.cosine());
    }
    return adjacency;
  }

  /** 图键 → 文档键：{@code docKey-p<page>-b<index>} 截到最后一个 {@code -p}。 */
  static String docKeyOf(String imageKey) {
    int cut = imageKey.lastIndexOf("-p");
    if (cut <= 0) {
      throw new IllegalStateException("图键形状异常（缺 -p<page> 段）: " + imageKey);
    }
    return imageKey.substring(0, cut);
  }

  /** nearest-rank 分位（空集记 0）。 */
  private static double quantile(List<Double> sorted, double q) {
    if (sorted.isEmpty()) {
      return 0D;
    }
    int index = (int) Math.ceil(q * sorted.size()) - 1;
    index = Math.max(0, Math.min(sorted.size() - 1, index));
    return round6(sorted.get(index));
  }

  /** 6 位小数（与既有锚同口径，消除二进制浮点尾差）。 */
  static double round6(double value) {
    return Math.round(value * 1_000_000D) / 1_000_000D;
  }
}
