package com.slz.crm.server.ai;

/**
 * 引用对齐三阈值（fix-citation-threshold-config 任务 1.1：由编译期常量升级为可注入值）。
 *
 * <p>{@link CitationAligner} 的 KEEP / REMAP 决策边界：{@code keepMin} 是原编号可保留的最低支撑分、{@code remapMin}
 * 是重映射到最佳片段所需的最低支撑分、{@code tieMargin} 是平局带宽（得分差小于该值时保留原编号）。
 *
 * <p>零 Spring、零外呼：本记录只承载数值与范围校验，配置读取在调用侧（{@link CitationThresholdsResolver}）。
 *
 * <p>默认值 = fix-citation-alignment 交付的三个常量（0.12 / 0.22 / 0.08）：未改配置时对齐行为与既有两参形态逐字节等价。
 *
 * @param keepMin 保留阈值，范围 [0,1]
 * @param remapMin 重映射阈值，范围 [0,1]
 * @param tieMargin 平局带宽，范围 [0,1]
 */
public record CitationThresholds(double keepMin, double remapMin, double tieMargin) {

  /** 阈值允许区间下界（与 rag.retrieval.citation.* 三键的登记范围一致）。 */
  public static final double MIN_ALLOWED = 0.0d;

  /** 阈值允许区间上界（与 rag.retrieval.citation.* 三键的登记范围一致）。 */
  public static final double MAX_ALLOWED = 1.0d;

  /** 构造校验：三值均须为 [0,1] 内的有限数，否则在装配期即暴露配置错误。 */
  public CitationThresholds {
    checkRange("keepMin", keepMin);
    checkRange("remapMin", remapMin);
    checkRange("tieMargin", tieMargin);
  }

  /**
   * 默认阈值（= 现三个编译期常量）。
   *
   * @return 与升级前逐字节等价的对齐阈值
   */
  public static CitationThresholds defaults() {
    return new CitationThresholds(
        CitationAligner.KEEP_MIN, CitationAligner.REMAP_MIN, CitationAligner.TIE_MARGIN);
  }

  private static void checkRange(String name, double value) {
    if (!Double.isFinite(value) || value < MIN_ALLOWED || value > MAX_ALLOWED) {
      throw new IllegalArgumentException(
          "引用对齐阈值 " + name + " 须为 [" + MIN_ALLOWED + "," + MAX_ALLOWED + "] 内的有限数，实际值：" + value);
    }
  }
}
