package com.slz.crm.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 近重复三机制离线评估器（retrieval-and-contract-hardening G1 任务 1.3）：余弦锚 540 对 + dHash-64 → M1/M2/M3 全网格。
 *
 * <p><b>判据口径（proposal §1.2，跑前写死、跑后禁改）</b>：{@code retained} = 判 {@code SAME_IMAGE} 或 {@code
 * NEAR_DUPLICATE}（未被丢弃）、{@code dropped} = 判 {@code CROSS_IMAGE}；{@code R_keep} = 同源变体 （{@code
 * near-duplicate} 档）保留占比，{@code L} = 负样本（{@code same-class} + {@code cross-class}）被保留占比。
 *
 * <p><b>为什么不调 {@code src/main}</b>：{@link
 * com.slz.crm.knowledge.retrieval.ImageSimilarityGate#tierOf} 是本卡<b>不许改</b>的 生产实现，这里按同语义（{@code >=
 * threshold} 同图 / {@code >= floor} 近似 / 其余丢弃）<b>独立重写</b>一份，保证"评估器不依赖 被评估对象"，同时用 {@link
 * #THRESHOLD}/{@link #COSINE_FLOOR} 常量把生产现值钉进锚（{@code NearDuplicateMechanismDecisionGuardTest} 守）。
 *
 * <p><b>零外呼</b>：整类只有算术与内存容器，无 I/O、无网络、无环境变量。
 */
final class NearDuplicateMechanismAnalyzer {

  /** 判据 C1：同源保留率下限（proposal §1.2 原文，禁改）。 */
  static final double C1_MIN_RETENTION = 0.90D;

  /** 判据 C2：负样本泄漏率上限（proposal §1.2 原文，禁改）。 */
  static final double C2_MAX_LEAKAGE = 0.02D;

  /** 判据 C6：floor 网格下界（含）。 */
  static final double FLOOR_GRID_START = 0.90D;

  /** 判据 C6：floor 网格步长。 */
  static final double FLOOR_GRID_STEP = 0.0005D;

  /** 判据 C6：floor 网格点数。 */
  static final int FLOOR_GRID_POINTS = 200;

  /** 判据 C6：dHash 距离下界（含）。 */
  static final int DHASH_MIN = 0;

  /** 判据 C6：dHash 距离上界（含）。 */
  static final int DHASH_MAX = 24;

  /** M1 固定的判别阈值 = {@code ImageSimilarityGate.DEFAULT_THRESHOLD}（0.9944），本卡不改它。 */
  static final double THRESHOLD = 0.9944D;

  /** M3 余弦兜底下限 = {@code ImageSimilarityGate.NEAR_DUPLICATE_FLOOR}（0.9887）。 */
  static final double COSINE_FLOOR = 0.9887D;

  /** 决策三选一（C5），禁造第四种。 */
  static final String VERDICT_GO = "GO";

  static final String VERDICT_NO_GO = "NO-GO";

  static final String VERDICT_UNDECIDABLE = "UNDECIDABLE-BY-DATA";

  /** 同源对档名（v2 锚 {@code pairs[].stratum}）。 */
  static final String STRATUM_NEAR_DUPLICATE = "near-duplicate";

  /** 同图对档名（同一张图两次编码；本卡自证 144 对仍判同图）。 */
  static final String STRATUM_SAME_IMAGE = "same-image";

  /** 负样本档名之一（同出版物异图）。 */
  static final String STRATUM_SAME_CLASS = "same-class";

  /** 负样本档名之二（跨出版物）。 */
  static final String STRATUM_CROSS_CLASS = "cross-class";

  /** 档位名（与 {@code ImageSimilarityGate} 三档同名）。 */
  static final String TIER_SAME_IMAGE = "SAME_IMAGE";

  static final String TIER_NEAR_DUPLICATE = "NEAR_DUPLICATE";

  static final String TIER_CROSS_IMAGE = "CROSS_IMAGE";

  /** C3 单调链（严格按 proposal §1.2 顺序；jpeg-q80 允许与 scale-085 互换，另行单判）。 */
  static final List<String> MONOTONIC_CHAIN =
      List.of("crop-12", "crop-04", "scale-070", "scale-085");

  /** C3 的互换位变体类型。 */
  static final String KIND_JPEG_Q80 = "jpeg-q80";

  private NearDuplicateMechanismAnalyzer() {}

  /**
   * 一对样本（v2 锚 {@code pairs[]} 的四个判别字段 + 余弦）。
   *
   * @param leftKey 左图键
   * @param rightKey 右图键（同图对与 leftKey 相同）
   * @param stratum 档（同图 / 同源变体 / 同出版物异图 / 跨出版物）
   * @param kind 变体类型（仅同源变体档非空）
   * @param cosine 两次真视觉 embedding 的余弦
   */
  record Pair(String leftKey, String rightKey, String stratum, String kind, double cosine) {}

  /** 机制枚举（决定 {@link #tierOf} 走哪条规则）。 */
  enum Mechanism {
    /** M1：threshold 固定，仅扫 floor。 */
    FLOOR,
    /** M2：只看 dHash 距离。 */
    DHASH,
    /** M3：dHash 双阈值 + 余弦兜底。 */
    HYBRID
  }

  /**
   * 一个网格点的评估结果。
   *
   * @param variantTotal 同源变体对数（分母 120）
   * @param retainedVariant 其中被保留（未丢弃）的对数
   * @param negativeTotal 负样本对数（分母 276）
   * @param leakedNegative 其中被误保留的对数
   * @param kindTotal 逐 kind 分母（每 kind 24）
   * @param retainedByKind 逐 kind 保留数
   * @param sameImageClassified 判成 {@code SAME_IMAGE} 的对数（M3 里体现 {@code D_low} 的作用）
   */
  record Outcome(
      int variantTotal,
      int retainedVariant,
      int negativeTotal,
      int leakedNegative,
      Map<String, Integer> kindTotal,
      Map<String, Integer> retainedByKind,
      int sameImageClassified) {

    /**
     * @return 同源保留率 R_keep
     */
    double retention() {
      return ratio(retainedVariant, variantTotal);
    }

    /**
     * @return 负样本泄漏率 L
     */
    double leakage() {
      return ratio(leakedNegative, negativeTotal);
    }

    /**
     * @return C1 是否满足（保留率 ≥ 0.90）
     */
    boolean satisfiesC1() {
      return retention() >= C1_MIN_RETENTION;
    }

    /**
     * @return C2 是否满足（泄漏率 ≤ 0.02）
     */
    boolean satisfiesC2() {
      return leakage() <= C2_MAX_LEAKAGE;
    }

    /**
     * C3：{@code crop-12 ≤ crop-04 ≤ scale-070 ≤ scale-085} 且 {@code jpeg-q80 ≥ scale-070}（允许与
     * scale-085 互换）。
     *
     * @return 是否单调
     */
    boolean satisfiesC3() {
      boolean monotonic = true;
      for (int index = 1; index < MONOTONIC_CHAIN.size(); index++) {
        String lower = MONOTONIC_CHAIN.get(index - 1);
        String upper = MONOTONIC_CHAIN.get(index);
        if (kindCount(retainedByKind, lower) > kindCount(retainedByKind, upper)) {
          monotonic = false;
          break;
        }
      }
      if (monotonic) {
        monotonic =
            kindCount(retainedByKind, KIND_JPEG_Q80) >= kindCount(retainedByKind, "scale-070");
      }
      return monotonic;
    }

    /**
     * @param kind 变体类型
     * @return 该 kind 的保留率（分母为该 kind 的实际对数）
     */
    double retentionOfKind(String kind) {
      return ratio(kindCount(retainedByKind, kind), kindCount(kindTotal, kind));
    }
  }

  /** M1 网格点。 */
  record FloorPoint(double floor, Outcome outcome) {}

  /** M2 网格点。 */
  record DHashPoint(int distance, Outcome outcome) {}

  /** M3 网格点。 */
  record HybridPoint(int distanceLow, int distanceHigh, Outcome outcome) {}

  /**
   * M1 全网格：threshold 固定 {@link #THRESHOLD}，floor 走 200 点。
   *
   * @param pairs 540 对样本
   * @return 200 个网格点（按 floor 升序）
   */
  static List<FloorPoint> scanFloors(List<Pair> pairs) {
    List<FloorPoint> points = new ArrayList<>(FLOOR_GRID_POINTS);
    for (int index = 0; index < FLOOR_GRID_POINTS; index++) {
      double floor = floorAt(index);
      points.add(new FloorPoint(floor, evaluate(pairs, Map.of(), Mechanism.FLOOR, floor, 0, 0)));
    }
    return List.copyOf(points);
  }

  /**
   * M2 全网格：dHash 距离 0..24。
   *
   * @param pairs 540 对样本
   * @param hashes 图键 → dHash
   * @return 25 个网格点
   */
  static List<DHashPoint> scanDHash(List<Pair> pairs, Map<String, Long> hashes) {
    List<DHashPoint> points = new ArrayList<>(DHASH_MAX - DHASH_MIN + 1);
    for (int distance = DHASH_MIN; distance <= DHASH_MAX; distance++) {
      points.add(
          new DHashPoint(
              distance, evaluate(pairs, hashes, Mechanism.DHASH, 0D, distance, distance)));
    }
    return List.copyOf(points);
  }

  /**
   * M3 全网格：{@code (D_low, D_high)}，{@code D_low ≤ D_high}，两者都取 0..24。
   *
   * @param pairs 540 对样本
   * @param hashes 图键 → dHash
   * @return 325 个网格点（按 D_low、再按 D_high 升序）
   */
  static List<HybridPoint> scanHybrid(List<Pair> pairs, Map<String, Long> hashes) {
    List<HybridPoint> points = new ArrayList<>(325);
    for (int low = DHASH_MIN; low <= DHASH_MAX; low++) {
      for (int high = low; high <= DHASH_MAX; high++) {
        points.add(
            new HybridPoint(low, high, evaluate(pairs, hashes, Mechanism.HYBRID, 0D, low, high)));
      }
    }
    return List.copyOf(points);
  }

  /**
   * floor 网格第 {@code index} 点（0 起）：{@code 0.90 + 0.0005 * index}。
   *
   * @param index 0..199
   * @return floor 值
   */
  static double floorAt(int index) {
    // 全整数定点：0.90 = 9000/10000、步长 0.0005 = 5/10000，避免 0.0005 累加的浮点漂移
    return (9000 + index * 5) / 10000D;
  }

  /**
   * 单点评估（M1/M2/M3 共用同一统计口径，保证三机制可比）。
   *
   * @param pairs 540 对样本
   * @param hashes 图键 → dHash（FLOOR 机制不用，可传空表）
   * @param mechanism 机制
   * @param floor M1 的 floor（其余机制忽略）
   * @param distanceLow M3 的 D_low（M2 时忽略）
   * @param distanceHigh M2/M3 的上界距离
   * @return 该点的统计
   */
  static Outcome evaluate(
      List<Pair> pairs,
      Map<String, Long> hashes,
      Mechanism mechanism,
      double floor,
      int distanceLow,
      int distanceHigh) {
    int variantTotal = 0;
    int retainedVariant = 0;
    int negativeTotal = 0;
    int leakedNegative = 0;
    int sameClassified = 0;
    Map<String, Integer> kindTotal = new LinkedHashMap<>();
    Map<String, Integer> retainedByKind = new LinkedHashMap<>();
    for (Pair pair : pairs) {
      String tier = tierOf(pair, hashes, mechanism, floor, distanceLow, distanceHigh);
      boolean retained = !TIER_CROSS_IMAGE.equals(tier);
      if (STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
        variantTotal++;
        if (retained) {
          retainedVariant++;
        }
        String kind = pair.kind() == null ? "unknown" : pair.kind();
        kindTotal.merge(kind, 1, Integer::sum);
        if (retained) {
          retainedByKind.merge(kind, 1, Integer::sum);
        }
      } else if (isNegativeStratum(pair.stratum())) {
        negativeTotal++;
        if (retained) {
          leakedNegative++;
        }
      }
      if (TIER_SAME_IMAGE.equals(tier)) {
        sameClassified++;
      }
    }
    return new Outcome(
        variantTotal,
        retainedVariant,
        negativeTotal,
        leakedNegative,
        Collections.unmodifiableMap(new LinkedHashMap<>(kindTotal)),
        Collections.unmodifiableMap(new LinkedHashMap<>(retainedByKind)),
        sameClassified);
  }

  /**
   * 单对判档（语义与 {@code ImageSimilarityGate#tierOf} 一致，外加 M2/M3 两条 dHash 规则）。
   *
   * @param pair 样本对
   * @param hashes 图键 → dHash
   * @param mechanism 机制
   * @param floor M1 的 floor
   * @param distanceLow M3 的 D_low
   * @param distanceHigh M2/M3 的上界距离
   * @return 三档之一
   */
  static String tierOf(
      Pair pair,
      Map<String, Long> hashes,
      Mechanism mechanism,
      double floor,
      int distanceLow,
      int distanceHigh) {
    String tier;
    if (Mechanism.FLOOR == mechanism) {
      tier = tierOfCosine(pair.cosine(), floor);
    } else {
      int distance = hamming(hashes, pair.leftKey(), pair.rightKey());
      tier = tierOfHash(pair.cosine(), distance, mechanism, distanceLow, distanceHigh);
    }
    return tier;
  }

  /**
   * M1 判档（{@code ImageSimilarityGate#tierOf} 同语义的独立重写）。
   *
   * @param cosine 余弦
   * @param floor 生效下限
   * @return 三档之一
   */
  static String tierOfCosine(double cosine, double floor) {
    String tier;
    if (cosine >= THRESHOLD) {
      tier = TIER_SAME_IMAGE;
    } else if (cosine >= floor) {
      tier = TIER_NEAR_DUPLICATE;
    } else {
      tier = TIER_CROSS_IMAGE;
    }
    return tier;
  }

  /**
   * 会话裁决（C5 四支，按序短路）：① 有点同时满足 C1∧C2∧C3 ⇒ GO；② 有点满足 C1∧C2、仅 C3 缺 ⇒ UNDECIDABLE-BY-DATA；③ 有点满足 C1（不论
   * C2）或有点满足 C2（不论 C1），且两者不可同时满足 ⇒ UNDECIDABLE-BY-DATA（须给出"关节不可达 + 两侧可达"的量化前沿）；④ 连 C1 与 C2 都无任何点可达
   * ⇒ NO-GO（按序短路后 ④ 的实际域；单侧可达由 ③ 先捕获，含"有 C2 无 C1"）。
   *
   * <p>四支取自 proposal §1.2 C5 规则（v1.2 消歧）；门槛 C1=0.90 / C2=0.02 / C3 规则与网格参数零改动。
   *
   * @param floorPoints M1 全网格
   * @param dhashPoints M2 全网格
   * @param hybridPoints M3 全网格
   * @return 三选一（GO / UNDECIDABLE-BY-DATA / NO-GO）
   */
  static String verdict(
      List<FloorPoint> floorPoints, List<DHashPoint> dhashPoints, List<HybridPoint> hybridPoints) {
    boolean anyFull = false;
    boolean anyHalfPoint = false;
    boolean anyC1 = false;
    boolean anyC2 = false;
    for (List<Outcome> outcomes : outcomesOf(floorPoints, dhashPoints, hybridPoints)) {
      for (Outcome outcome : outcomes) {
        boolean c1 = outcome.satisfiesC1();
        boolean c2 = outcome.satisfiesC2();
        anyC1 = anyC1 || c1;
        anyC2 = anyC2 || c2;
        anyFull = anyFull || (c1 && c2 && outcome.satisfiesC3());
        anyHalfPoint = anyHalfPoint || (c1 && c2);
      }
    }
    String verdict;
    if (anyFull) {
      verdict = VERDICT_GO;
    } else if (anyHalfPoint) {
      verdict = VERDICT_UNDECIDABLE;
    } else if (anyC1 || anyC2) {
      verdict = VERDICT_UNDECIDABLE;
    } else {
      verdict = VERDICT_NO_GO;
    }
    return verdict;
  }

  /**
   * 帕累托前沿：每个泄漏档下的最大保留率（同一机制内不许被支配点存在）。
   *
   * @param outcomes 同一机制的全网格点
   * @return 前沿点（按泄漏率升序）
   */
  static List<Outcome> paretoFrontier(List<Outcome> outcomes) {
    Map<Long, Outcome> bestByLeakage = new LinkedHashMap<>();
    for (Outcome outcome : outcomes) {
      long key = Math.round(outcome.leakage() * 1_000_000D);
      Outcome existing = bestByLeakage.get(key);
      if (existing == null || outcome.retention() > existing.retention()) {
        bestByLeakage.put(key, outcome);
      }
    }
    List<Outcome> frontier = new ArrayList<>();
    double bestRetention = -1D;
    for (Outcome candidate :
        bestByLeakage.values().stream()
            .sorted(
                (left, right) -> {
                  int byLeakage = Double.compare(left.leakage(), right.leakage());
                  return byLeakage != 0
                      ? byLeakage
                      : Double.compare(right.retention(), left.retention());
                })
            .toList()) {
      if (candidate.retention() > bestRetention) {
        frontier.add(candidate);
        bestRetention = candidate.retention();
      }
    }
    return List.copyOf(frontier);
  }

  /**
   * 键对齐 fail-fast（任务 1.3.5）：任一图键在图集合中缺失即抛，报出所有缺失键。
   *
   * @param pairs 样本对
   * @param imageKeys 语料实际产出的图键集合（由 corpus 的 base/variant 键构成）
   */
  static void requireAligned(List<Pair> pairs, Set<String> imageKeys) {
    List<String> missing = new ArrayList<>();
    for (Pair pair : pairs) {
      if (!imageKeys.contains(pair.leftKey()) && !missing.contains(pair.leftKey())) {
        missing.add(pair.leftKey());
      }
      if (!imageKeys.contains(pair.rightKey()) && !missing.contains(pair.rightKey())) {
        missing.add(pair.rightKey());
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("样本图键在语料中缺失（禁止静默跳过/兜底图）: " + missing);
    }
  }

  /**
   * 逐对 dHash。
   *
   * @param hashes 图键 → dHash
   * @param leftKey 左键
   * @param rightKey 右键
   * @return 汉明距离（键缺失即抛）
   */
  static int hamming(Map<String, Long> hashes, String leftKey, String rightKey) {
    Long left = hashes.get(leftKey);
    Long right = hashes.get(rightKey);
    if (left == null || right == null) {
      throw new IllegalStateException("dHash 缺键: left=" + leftKey + " right=" + rightKey);
    }
    return PerceptualHashSupport.hamming(left, right);
  }

  /**
   * M2/M3 的 dHash 判档。
   *
   * <p>M2 只有单阈值：{@code D} 内一律判近似同图（dHash 单看像素无法区分"同图"与"同源变体"，不冒充 {@code SAME_IMAGE}）； M3
   * 才有双阈值：{@code D_low} 内判同图、{@code D_high} 内或余弦兜底判近似带。
   */
  private static String tierOfHash(
      double cosine, int distance, Mechanism mechanism, int distanceLow, int distanceHigh) {
    String tier;
    if (Mechanism.HYBRID == mechanism && distance <= distanceLow) {
      tier = TIER_SAME_IMAGE;
    } else if (distance <= distanceHigh
        || (Mechanism.HYBRID == mechanism && cosine >= COSINE_FLOOR)) {
      tier = TIER_NEAR_DUPLICATE;
    } else {
      tier = TIER_CROSS_IMAGE;
    }
    return tier;
  }

  /**
   * @param stratum 档名
   * @return 是否负样本档（{@code same-class} 或 {@code cross-class}）
   */
  static boolean isNegativeStratum(String stratum) {
    return STRATUM_SAME_CLASS.equals(stratum) || STRATUM_CROSS_CLASS.equals(stratum);
  }

  private static List<List<Outcome>> outcomesOf(
      List<FloorPoint> floorPoints, List<DHashPoint> dhashPoints, List<HybridPoint> hybridPoints) {
    List<List<Outcome>> all = new ArrayList<>(3);
    all.add(floorPoints.stream().map(FloorPoint::outcome).toList());
    all.add(dhashPoints.stream().map(DHashPoint::outcome).toList());
    all.add(hybridPoints.stream().map(HybridPoint::outcome).toList());
    return all;
  }

  private static Integer kindCount(Map<String, Integer> counts, String kind) {
    Integer count = counts.get(kind);
    return count == null ? 0 : count;
  }

  private static double ratio(int numerator, int denominator) {
    return denominator == 0 ? 0D : (double) numerator / denominator;
  }
}
