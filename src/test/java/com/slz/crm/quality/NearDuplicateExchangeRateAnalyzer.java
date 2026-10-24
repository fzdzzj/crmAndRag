package com.slz.crm.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 近重复「交换率」离线重定价器（evidence-to-operations G1，任务 1.1.1~1.1.5）：把上一卡混在同一个分母里的负样本
 * 泄漏拆成<b>有害的一半</b>（same-class，同文档不同图被降权，假设 A1/A2）与<b>无害的一半</b>（cross-class，跨出版物 噪声被降权，假设 A3），对 200
 * 点 floor 网格逐点给出可拍板的价目表。
 *
 * <p><b>判据口径（proposal §1.2 E1–E6，跑前写死、跑后禁改）</b>：
 *
 * <ul>
 *   <li><b>E1</b>：逐 floor 计算 {@code R(f)}（同源保留率，同上一卡 M1 口径）/ {@code S_same(f)}（same-class 对中 {@code
 *       cosine ∈ [f, 0.9944)} 的数量 ÷ 100，有害量上界）/ {@code C_cross(f)}（cross-class 同区间数量 ÷ 176）；
 *   <li><b>E2</b>：逐点恒等式 {@code kept(S_same) + kept(C_cross) + dropped = 276}；且 {@code R(f)}
 *       与上一卡锚逐点相等 （跨锚比对在 {@code NearDuplicateExchangeRateDecisionTest} 里做，本类只保证同口径复算）；
 *   <li><b>E3</b>：免费档 = 网格上存在 {@code f* < 0.9887} 使 {@code R(f*) > R(0.9887)} 且 {@code S_same(f*) ≤
 *       S_same(0.9887)}，取 {@code R} 最大者为推荐档（并列取最高 floor）；<b>f* 的域 = 200 点网格</b>（与 E5 同域，
 *       网格外的连续阈值不在本卡量纲内）；
 *   <li><b>E4</b>：无免费档时逐档给出 {@code ΔR / ΔS_same} 与「这一档多压掉的同文档图数」（相对基线 0.9887）；
 *   <li><b>E5</b>：200 点全网格逐点入锚；
 *   <li><b>E6</b>：结论三选一（见 {@link #verdict}），禁造第四种。
 * </ul>
 *
 * <p><b>零外呼</b>：整类只有算术与内存容器，无 I/O、无网络、无环境变量。
 */
final class NearDuplicateExchangeRateAnalyzer {

  /** 生产判别阈值 = {@code ImageSimilarityGate.DEFAULT_THRESHOLD}（0.9944），本卡不改、只作带内上界。 */
  static final double THRESHOLD = 0.9944D;

  /** 基线档 = 定标锚异图上限 0.9887（历史测档；生产准入下限已下探至 0.982，本类不跟随，字面量冻结）。 */
  static final double BASELINE_FLOOR = 0.9887D;

  /** E5 网格下界（含），与上一卡 M1 同源。 */
  static final double GRID_START = 0.90D;

  /** E5 网格步长。 */
  static final double GRID_STEP = 0.0005D;

  /** E5 网格点数。 */
  static final int GRID_POINTS = 200;

  /** same-class 档分母（strata.same-class 实测 100）。 */
  static final int SAME_CLASS_TOTAL = 100;

  /** cross-class 档分母（strata.cross-class 实测 176）。 */
  static final int CROSS_CLASS_TOTAL = 176;

  /** 负样本总数 = same-class + cross-class。 */
  static final int NEGATIVE_TOTAL = SAME_CLASS_TOTAL + CROSS_CLASS_TOTAL;

  /** 同源变体档分母（strata.near-duplicate 实测 120）。 */
  static final int NEAR_DUP_TOTAL = 120;

  /** E6 结论一：存在免费档。 */
  static final String VERDICT_FREE_TIER = "FREE-TIER-FOUND";

  /** E6 结论二：无免费档，但价目表已量出（档位 Owner 拍板）。 */
  static final String VERDICT_EXCHANGE_RATE = "EXCHANGE-RATE";

  /** E6 结论三：网格上不存在任何提升保留率的档位，数据无法定价有益变动。 */
  static final String VERDICT_UNDECIDABLE = "UNDECIDABLE-BY-DATA";

  /** 三选一封闭集（守卫测试比对用）。 */
  static final List<String> VERDICTS =
      List.of(VERDICT_FREE_TIER, VERDICT_EXCHANGE_RATE, VERDICT_UNDECIDABLE);

  /** 同源变体档名（校准锚 {@code pairs[].stratum}）。 */
  static final String STRATUM_NEAR_DUPLICATE = "near-duplicate";

  /** 负样本档名之一：同出版物异图（同文档，有害半边）。 */
  static final String STRATUM_SAME_CLASS = "same-class";

  /** 负样本档名之二：跨出版物（无害半边）。 */
  static final String STRATUM_CROSS_CLASS = "cross-class";

  private NearDuplicateExchangeRateAnalyzer() {}

  /**
   * 一对样本（校准锚 {@code pairs[]} 的判别字段 + 余弦）。
   *
   * @param stratum 档（同源变体 / 同出版物异图 / 跨出版物）
   * @param kind 变体类型（仅同源变体档非空）
   * @param cosine 两次真视觉 embedding 的余弦
   */
  record Pair(String stratum, String kind, double cosine) {}

  /**
   * 一个 floor 档的完整统计（计数保留原值，比率由方法按固定分母现算）。
   *
   * @param floor 生效下限
   * @param retainedNearDup 同源变体对中被保留（{@code cosine >= floor}）的数量
   * @param keptSame same-class 对中被保留（{@code cosine >= floor}）的数量（E2 恒等式分量）
   * @param keptCross cross-class 对中被保留的数量（E2 恒等式分量）
   * @param dropped 负样本对中被丢弃（{@code cosine < floor}）的数量（E2 恒等式分量）
   * @param bandSame same-class 对中落降权带 {@code [floor, 0.9944)} 的数量（有害量，A2 上界）
   * @param bandCross cross-class 对中落降权带的数量（无害量）
   * @param retainedByKind 同源变体逐 kind 保留数（E5 披露项）
   */
  record FloorStat(
      double floor,
      int retainedNearDup,
      int keptSame,
      int keptCross,
      int dropped,
      int bandSame,
      int bandCross,
      Map<String, Integer> retainedByKind) {

    /**
     * @return 同源保留率 R(f)（分母 120）
     */
    double retention() {
      return ratio(retainedNearDup, NEAR_DUP_TOTAL);
    }

    /**
     * @return 有害量占比 S_same(f)（分母 100；A2：这是上界不是实测伤害）
     */
    double sameSuppression() {
      return ratio(bandSame, SAME_CLASS_TOTAL);
    }

    /**
     * @return 无害量占比 C_cross(f)（分母 176）
     */
    double crossSuppression() {
      return ratio(bandCross, CROSS_CLASS_TOTAL);
    }
  }

  /**
   * 相对基线档的增量（E4 价目表一行）。
   *
   * @param deltaR {@code R(f) - R(0.9887)}
   * @param deltaSSame {@code S_same(f) - S_same(0.9887)}
   * @param extraSuppressedSameDoc 这一档多压掉的同文档图数（计数差，非比率）
   * @param exchangeRate {@code ΔR / ΔS_same}；{@code ΔS_same <= 0} 时无意义，记 {@code null}
   */
  record Delta(double deltaR, double deltaSSame, int extraSuppressedSameDoc, Double exchangeRate) {}

  /**
   * 键与分母对齐 fail-fast：strata 计数必须精确等于 120/100/176（负样本合计 276），否则抛。
   *
   * @param pairs 校准锚样本对
   */
  static void requireStrata(List<Pair> pairs) {
    int nearDup = 0;
    int sameClass = 0;
    int crossClass = 0;
    for (Pair pair : pairs) {
      if (STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
        nearDup++;
      } else if (STRATUM_SAME_CLASS.equals(pair.stratum())) {
        sameClass++;
      } else if (STRATUM_CROSS_CLASS.equals(pair.stratum())) {
        crossClass++;
      }
    }
    if (nearDup != NEAR_DUP_TOTAL
        || sameClass != SAME_CLASS_TOTAL
        || crossClass != CROSS_CLASS_TOTAL) {
      throw new IllegalStateException(
          "strata 计数与判据分母不一致: near-duplicate="
              + nearDup
              + " same-class="
              + sameClass
              + " cross-class="
              + crossClass);
    }
  }

  /**
   * 基线档（floor = 0.9887 精确值，<b>不在 200 点网格上</b>——网格步长 0.0005 只落到 0.9885/0.9890， 故基线单独评测， 与上一卡表 1/5
   * 同口径）。
   *
   * @param pairs 校准锚样本对
   * @return 基线统计
   */
  static FloorStat baselineOf(List<Pair> pairs) {
    return evaluate(pairs, BASELINE_FLOOR);
  }

  /**
   * E5 全网格：floor 从 0.90 到 0.9995 步长 0.0005 共 200 点（全整数定点，避免浮点累加漂移）。
   *
   * @param pairs 校准锚样本对
   * @return 200 个网格点（按 floor 升序）
   */
  static List<FloorStat> scanGrid(List<Pair> pairs) {
    List<FloorStat> points = new ArrayList<>(GRID_POINTS);
    for (int index = 0; index < GRID_POINTS; index++) {
      points.add(evaluate(pairs, floorAt(index)));
    }
    return List.copyOf(points);
  }

  /**
   * 网格第 {@code index} 点（0 起）＝ {@code 0.90 + 0.0005 * index}。
   *
   * @param index 0..199
   * @return floor 值
   */
  static double floorAt(int index) {
    return (9000 + index * 5) / 10000D;
  }

  /**
   * 单点统计（E1 口径的唯一实现）。
   *
   * <p><b>保留判定与上一卡 {@code tierOfCosine} 同语义</b>：{@code cosine >= THRESHOLD} 先判同图（保留），其余 {@code
   * cosine >= floor} 判近似带（保留），其余丢弃。基线档 floor（0.9887，历史测档）低于阈值时两者等价于 {@code cosine >= floor}；
   * 网格顶部高于阈值的点不是物理档位，R 平台在 {@code #(cosine>=0.9944)/120}——只为 E2 与上一卡锚逐点相等而披露。 降权带（S_same/C_cross）仍按
   * E1 字面定义 {@code [floor, 0.9944)}，与保留判定互相独立。
   *
   * @param pairs 校准锚样本对
   * @param floor 生效下限
   * @return 该档统计
   */
  static FloorStat evaluate(List<Pair> pairs, double floor) {
    int retainedNearDup = 0;
    int keptSame = 0;
    int keptCross = 0;
    int dropped = 0;
    int bandSame = 0;
    int bandCross = 0;
    Map<String, Integer> retainedByKind = new LinkedHashMap<>();
    for (Pair pair : pairs) {
      boolean retained = pair.cosine() >= THRESHOLD || pair.cosine() >= floor;
      boolean banded = pair.cosine() >= floor && pair.cosine() < THRESHOLD;
      if (STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
        if (retained) {
          retainedNearDup++;
          String kind = pair.kind() == null ? "unknown" : pair.kind();
          retainedByKind.merge(kind, 1, Integer::sum);
        }
      } else if (STRATUM_SAME_CLASS.equals(pair.stratum())) {
        if (pair.cosine() >= floor) {
          keptSame++;
        } else {
          dropped++;
        }
        if (banded) {
          bandSame++;
        }
      } else if (STRATUM_CROSS_CLASS.equals(pair.stratum())) {
        if (pair.cosine() >= floor) {
          keptCross++;
        } else {
          dropped++;
        }
        if (banded) {
          bandCross++;
        }
      }
    }
    if (keptSame + keptCross + dropped != NEGATIVE_TOTAL) {
      throw new IllegalStateException(
          "E2 恒等式被破坏（kept(S_same)+kept(C_cross)+dropped != 276）: floor="
              + floor
              + " keptSame="
              + keptSame
              + " keptCross="
              + keptCross
              + " dropped="
              + dropped);
    }
    return new FloorStat(
        floor,
        retainedNearDup,
        keptSame,
        keptCross,
        dropped,
        bandSame,
        bandCross,
        Collections.unmodifiableMap(new LinkedHashMap<>(retainedByKind)));
  }

  /**
   * E4 价目表一行：相对基线 0.9887 的增量。
   *
   * @param point 目标档
   * @param baseline 基线档
   * @return 增量（{@code ΔS_same <= 0} 时 exchangeRate 为 null）
   */
  static Delta deltaOf(FloorStat point, FloorStat baseline) {
    double deltaR = point.retention() - baseline.retention();
    double deltaSSame = point.sameSuppression() - baseline.sameSuppression();
    int extraSame = point.bandSame() - baseline.bandSame();
    Double exchangeRate = deltaSSame > 0D ? round6(deltaR / deltaSSame) : null;
    return new Delta(round6(deltaR), round6(deltaSSame), extraSame, exchangeRate);
  }

  /**
   * E3 免费档（域 = 200 点网格）：{@code f* < 0.9887} 且 {@code R(f*) > R(0.9887)} 且 {@code S_same(f*) ≤
   * S_same(0.9887)}；满足者取 {@code R} 最大，并列取最高 floor（离现值改动最小的定价）。
   *
   * @param grid 全网格
   * @param baseline 基线档
   * @return 免费档；不存在时空
   */
  static Optional<FloorStat> freeTier(List<FloorStat> grid, FloorStat baseline) {
    Optional<FloorStat> best = Optional.empty();
    for (FloorStat point : grid) {
      if (point.floor() >= baseline.floor()) {
        continue;
      }
      boolean improves = point.retention() > baseline.retention();
      boolean noExtraHarm = point.sameSuppression() <= baseline.sameSuppression();
      if (!improves || !noExtraHarm) {
        continue;
      }
      if (best.isEmpty()
          || point.retention() > best.get().retention()
          || (point.retention() == best.get().retention() && point.floor() > best.get().floor())) {
        best = Optional.of(point);
      }
    }
    return best;
  }

  /**
   * E6 三支判定（按序短路；操作化口径已写入 proposal 判据语义，禁造第四种）：
   *
   * <ol>
   *   <li>免费档存在（E3）⇒ {@link #VERDICT_FREE_TIER}；
   *   <li>否则网格上存在 {@code f < 0.9887} 使 {@code R(f) > R(0.9887)} ⇒ {@link #VERDICT_EXCHANGE_RATE}（E4
   *       价目表逐档披露，档位由 Owner 拍板，本卡不改常量）；
   *   <li>否则 ⇒ {@link #VERDICT_UNDECIDABLE}（网格上不存在任何提升保留率的档位，数据无法定价有益变动）。
   * </ol>
   *
   * @param grid 全网格
   * @param baseline 基线档
   * @return 三选一
   */
  static String verdict(List<FloorStat> grid, FloorStat baseline) {
    String verdict;
    if (freeTier(grid, baseline).isPresent()) {
      verdict = VERDICT_FREE_TIER;
    } else if (anyRetentionImprovement(grid, baseline)) {
      verdict = VERDICT_EXCHANGE_RATE;
    } else {
      verdict = VERDICT_UNDECIDABLE;
    }
    return verdict;
  }

  private static boolean anyRetentionImprovement(List<FloorStat> grid, FloorStat baseline) {
    for (FloorStat point : grid) {
      if (point.floor() < baseline.floor() && point.retention() > baseline.retention()) {
        return true;
      }
    }
    return false;
  }

  private static double ratio(int numerator, int denominator) {
    return (double) numerator / denominator;
  }

  private static double round6(double value) {
    return Math.round(value * 1_000_000D) / 1_000_000D;
  }
}
