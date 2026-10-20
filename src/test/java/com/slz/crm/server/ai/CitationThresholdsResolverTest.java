package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.ConfigKeyTier;
import com.slz.crm.platform.config.ConfigKeyTierPolicy;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.contract.DynamicConfigService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阈值解析器与三键 registry 登记的联测（fix-citation-threshold-config 任务 3.1）。
 *
 * <p>同一测试同时锁死两侧：①解析器的键名必须已在 {@link DynamicConfigKeyRegistry} 登记且默认值 = 现常量（防键名漂移、防默认值漂移）；
 * ②解析回退口径（null 配置 / 缺失 / 越界 / 非有限数）逐键回落默认，绝不因运营误配中断收尾。
 */
class CitationThresholdsResolverTest {

  @Test
  @DisplayName("三键已登记：默认值 = 现常量、范围 0.0~1.0、tier = OPERATIONAL（与 minScore 同档）")
  void keysRegisteredWithConstantDefaultsAndOperationalTier() {
    DynamicConfigKeyRegistry registry = new DynamicConfigKeyRegistry(new ObjectMapper());

    assertKey(registry, CitationThresholdsResolver.KEY_KEEP_MIN, CitationAligner.KEEP_MIN);
    assertKey(registry, CitationThresholdsResolver.KEY_REMAP_MIN, CitationAligner.REMAP_MIN);
    assertKey(registry, CitationThresholdsResolver.KEY_TIE_MARGIN, CitationAligner.TIE_MARGIN);
  }

  private static void assertKey(
      DynamicConfigKeyRegistry registry, String key, double expectedDefault) {
    ConfigKeyDefinition def = registry.definitionOf(key).orElseThrow();
    assertThat(def.namespace()).isEqualTo("rag.retrieval");
    assertThat(def.defaultValue()).isEqualTo(Double.toString(expectedDefault));
    assertThat(def.minValue()).isEqualTo("0.0");
    assertThat(def.maxValue()).isEqualTo("1.0");
    assertThat(ConfigKeyTierPolicy.tierOf(def)).isEqualTo(ConfigKeyTier.OPERATIONAL);
  }

  @Test
  @DisplayName("未配置/缺键 → 默认常量；配置命中 → 逐键生效")
  void resolveUsesConfiguredValuesAndFallsBackToConstants() {
    // 无配置服务（旁路/测试场景）
    assertThat(CitationThresholdsResolver.resolve(null)).isEqualTo(CitationThresholds.defaults());

    // 配置服务存在但三键都未配置 → 契约返回调用方默认值 → 仍是默认常量
    assertThat(CitationThresholdsResolver.resolve(new FakeConfig(Map.of())))
        .isEqualTo(CitationThresholds.defaults());

    // 三键均命中 → 采用配置值
    Map<String, Double> configured =
        Map.of(
            CitationThresholdsResolver.KEY_KEEP_MIN, 0.05d,
            CitationThresholdsResolver.KEY_REMAP_MIN, 0.30d,
            CitationThresholdsResolver.KEY_TIE_MARGIN, 0.15d);
    assertThat(CitationThresholdsResolver.resolve(new FakeConfig(configured)))
        .isEqualTo(new CitationThresholds(0.05d, 0.30d, 0.15d));

    // 仅部分键命中：未命中的键各自回落
    Map<String, Double> partial = Map.of(CitationThresholdsResolver.KEY_REMAP_MIN, 0.40d);
    CitationThresholds resolved = CitationThresholdsResolver.resolve(new FakeConfig(partial));
    assertThat(resolved.remapMin()).isEqualTo(0.40d);
    assertThat(resolved.keepMin()).isEqualTo(CitationAligner.KEEP_MIN);
    assertThat(resolved.tieMargin()).isEqualTo(CitationAligner.TIE_MARGIN);
  }

  @Test
  @DisplayName("越界/非有限数逐键回落默认（写入护栏之外的最后一道防线）")
  void resolveFallsBackOnOutOfRangeValues() {
    Map<String, Double> invalid =
        Map.of(
            CitationThresholdsResolver.KEY_KEEP_MIN, 1.5d,
            CitationThresholdsResolver.KEY_REMAP_MIN, -0.2d,
            CitationThresholdsResolver.KEY_TIE_MARGIN, Double.NaN);

    assertThat(CitationThresholdsResolver.resolve(new FakeConfig(invalid)))
        .isEqualTo(CitationThresholds.defaults());
  }

  /** 确定性 fake：只按 key 命中，未命中返回调用方默认值（与真实实现的「未配置」语义一致）。 */
  private static final class FakeConfig implements DynamicConfigService {

    private final Map<String, Double> values;

    private FakeConfig(Map<String, Double> values) {
      this.values = values;
    }

    @Override
    @SuppressWarnings("unchecked") // fake：仅回填 Double 型键，调用方只会按 Double.class 读取
    public <T> T get(String key, Class<T> type, T defaultValue) {
      Double stored = values.get(key);
      return stored == null ? defaultValue : (T) stored;
    }
  }
}
