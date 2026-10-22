package com.slz.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyWhitelist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * image-retrieval-threshold-gate 任务 1.1：图像同图阈值键的注册表登记护栏。
 *
 * <p>锁四件事：①键已登记且默认值 = 定标锚 0.9944；②类型 Double、范围 0~1（越界拒写）；③定级 OPERATIONAL（复用 608， 零新权限）；④不在 per-KB
 * 12 键白名单内（本卡零迁移、per-KB 覆盖不生效）。文本键 {@code rag.retrieval.minScore} 默认值保持 0.20 不变。
 */
class ImageSimilarityThresholdKeyTest {

  private static final DynamicConfigKeyRegistry REGISTRY =
      new DynamicConfigKeyRegistry(new ObjectMapper());

  private static final String KEY = ImageSimilarityGate.THRESHOLD_KEY;

  @Test
  @DisplayName("键已登记：rag.retrieval 命名空间 / Double / 默认 0.9944 / OPERATIONAL 档")
  void keyRegisteredWithAnchorDefaultAndOperationalTier() {
    ConfigKeyDefinition definition = REGISTRY.definitionOf(KEY).orElseThrow();

    assertThat(definition.namespace()).isEqualTo("rag.retrieval");
    assertThat(definition.type()).isEqualTo(ConfigValueType.DOUBLE);
    assertThat(definition.defaultValue()).isEqualTo("0.9944");
    assertThat(ConfigKeyTierPolicy.tierOf(definition)).isEqualTo(ConfigKeyTier.OPERATIONAL);
  }

  @Test
  @DisplayName("越界拒写：0~1 内通过，1.5 / -0.1 一律拒绝")
  void outOfRangeValueRejected() {
    assertThat(REGISTRY.validate(KEY, "0.9944").valid()).isTrue();
    assertThat(REGISTRY.validate(KEY, "0.9887").valid()).isTrue();
    assertThat(REGISTRY.validate(KEY, "1.5").valid()).isFalse();
    assertThat(REGISTRY.validate(KEY, "-0.1").valid()).isFalse();
  }

  @Test
  @DisplayName("零回归：文本 minScore 键仍是 0.20，未被图像键顶替")
  void textMinScoreKeyUnchanged() {
    assertThat(REGISTRY.definitionOf("rag.retrieval.minScore").orElseThrow().defaultValue())
        .isEqualTo("0.2");
  }

  @Test
  @DisplayName("per-KB 覆盖不生效：新键不在 12 键白名单封闭集内（零迁移）")
  void notInPerKbWhitelist() {
    assertThat(KbRetrievalStrategyWhitelist.isWhitelisted(KEY)).isFalse();
    assertThat(KbRetrievalStrategyWhitelist.KEYS).hasSize(12);
  }
}
