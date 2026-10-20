package com.slz.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.server.ai.CitationAligner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用对齐三阈值的 registry 校验（fix-citation-threshold-config 任务 3.1）。
 *
 * <p>键名/默认值/范围/档位四件事一次锁死：默认值必须等于 {@link CitationAligner} 现三个常量（否则「未改配置时行为逐字节等价」失效）， 写入期范围护栏必须与
 * {@code CitationThresholds} 的构造校验同界（0.0~1.0）。
 */
class CitationThresholdKeysRegistryTest {

  private static final String KEEP_MIN = "rag.retrieval.citation.keepMin";
  private static final String REMAP_MIN = "rag.retrieval.citation.remapMin";
  private static final String TIE_MARGIN = "rag.retrieval.citation.tieMargin";

  private final DynamicConfigKeyRegistry registry =
      new DynamicConfigKeyRegistry(new ObjectMapper());

  @Test
  @DisplayName("三键已登记：Double 类型、rag.retrieval 命名空间、默认值 = 现常量、范围 0.0~1.0、OPERATIONAL 档")
  void keysRegisteredWithConstantDefaults() {
    assertKey(KEEP_MIN, CitationAligner.KEEP_MIN);
    assertKey(REMAP_MIN, CitationAligner.REMAP_MIN);
    assertKey(TIE_MARGIN, CitationAligner.TIE_MARGIN);
  }

  private void assertKey(String key, double expectedDefault) {
    ConfigKeyDefinition def = registry.definitionOf(key).orElseThrow();
    assertThat(def.type()).isEqualTo(ConfigValueType.DOUBLE);
    assertThat(def.namespace()).isEqualTo("rag.retrieval");
    assertThat(Double.parseDouble(def.defaultValue())).isEqualTo(expectedDefault);
    assertThat(def.minValue()).isEqualTo("0.0");
    assertThat(def.maxValue()).isEqualTo("1.0");
    assertThat(def.allowedValues()).isEmpty();
    assertThat(def.sensitive()).isFalse();
    assertThat(ConfigKeyTierPolicy.tierOf(def)).isEqualTo(ConfigKeyTier.OPERATIONAL);
  }

  @Test
  @DisplayName("合法值含上下界放行，规范化为存储文本")
  void validValuesAccepted() {
    for (String key : new String[] {KEEP_MIN, REMAP_MIN, TIE_MARGIN}) {
      assertThat(registry.validate(key, "0.0").valid()).as("%s 下界", key).isTrue();
      assertThat(registry.validate(key, "1.0").valid()).as("%s 上界", key).isTrue();
      ConfigValueType.Parsed parsed = registry.validate(key, "0.15");
      assertThat(parsed.valid()).as("%s 中值", key).isTrue();
      assertThat(parsed.typed()).isEqualTo(0.15d);
      assertThat(parsed.canonical()).isEqualTo("0.15");
    }
    // 默认值本身必须可用（与 CitationThresholds 构造校验一致）
    assertThat(registry.validate(KEEP_MIN, "0.12").typed()).isEqualTo(0.12d);
    assertThat(registry.validate(REMAP_MIN, "0.22").typed()).isEqualTo(0.22d);
    assertThat(registry.validate(TIE_MARGIN, "0.08").typed()).isEqualTo(0.08d);
  }

  @Test
  @DisplayName("越界/类型不符/空值一律拒绝")
  void invalidValuesRejected() {
    for (String key : new String[] {KEEP_MIN, REMAP_MIN, TIE_MARGIN}) {
      assertRejected(key, "1.01");
      assertRejected(key, "-0.01");
      assertRejected(key, "abc");
      assertRejected(key, "");
      assertRejected(key, "   ");
    }
  }

  private void assertRejected(String key, String raw) {
    ConfigValueType.Parsed parsed = registry.validate(key, raw);
    assertThat(parsed.valid()).as("%s = [%s] 应被拒绝", key, raw).isFalse();
    assertThat(parsed.errorMessage()).as("%s 应给出可读原因", key).isNotBlank();
  }
}
