package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.DynamicConfigService;

/**
 * 引用对齐阈值的动态配置解析器（fix-citation-threshold-config 任务 1.2）。
 *
 * <p>核心对齐单元（{@link CitationAligner} / {@link CitationSupport} / {@link CitationThresholds}）保持零
 * Spring 依赖；本类只消费平台冻结契约 {@link DynamicConfigService}（纯接口），由调用侧 {@code AiChatStreamLifecycle} 经
 * {@code ObjectProvider<DynamicConfigService>} 惰性注入，与既有 {@code rag.retrieval.*} 键的消费范式一致。
 *
 * <p>回退口径（与其它检索键一致）：未配置 → 契约返回调用方默认值；类型不符 → 契约返回默认值；值非有限数或越出 [0,1] → 逐键回落默认常量，绝不因运营误配中断流式收尾。
 */
final class CitationThresholdsResolver {

  /** 保留阈值键（默认 0.12 = {@link CitationAligner#KEEP_MIN}）。 */
  static final String KEY_KEEP_MIN = "rag.retrieval.citation.keepMin";

  /** 重映射阈值键（默认 0.22 = {@link CitationAligner#REMAP_MIN}）。 */
  static final String KEY_REMAP_MIN = "rag.retrieval.citation.remapMin";

  /** 平局带宽键（默认 0.08 = {@link CitationAligner#TIE_MARGIN}）。 */
  static final String KEY_TIE_MARGIN = "rag.retrieval.citation.tieMargin";

  private CitationThresholdsResolver() {}

  /**
   * 解析当前生效阈值。
   *
   * @param config 动态配置服务；{@code null}（未装配/旁路场景）时回落默认常量
   * @return 三阈值（永远非空，且已通过 {@link CitationThresholds} 范围校验）
   */
  static CitationThresholds resolve(DynamicConfigService config) {
    CitationThresholds result;
    if (config == null) {
      result = CitationThresholds.defaults();
    } else {
      result =
          new CitationThresholds(
              read(config, KEY_KEEP_MIN, CitationAligner.KEEP_MIN),
              read(config, KEY_REMAP_MIN, CitationAligner.REMAP_MIN),
              read(config, KEY_TIE_MARGIN, CitationAligner.TIE_MARGIN));
    }
    return result;
  }

  /** 单键读取：越界/非有限数/读取失败一律回落该键默认常量。 */
  private static double read(DynamicConfigService config, String key, double fallback) {
    Double value = config.get(key, Double.class, Double.valueOf(fallback));
    double result;
    if (value == null
        || !Double.isFinite(value)
        || value < CitationThresholds.MIN_ALLOWED
        || value > CitationThresholds.MAX_ALLOWED) {
      result = fallback;
    } else {
      result = value;
    }
    return result;
  }
}
