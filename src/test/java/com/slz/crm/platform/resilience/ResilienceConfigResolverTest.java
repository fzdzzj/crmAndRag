package com.slz.crm.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.slz.crm.platform.contract.DynamicConfigService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 熔断韧性配置解析器测试（wire-circuit-dynamic-config 任务 3）。 覆盖缺省、合法、越界非法与异常回落语义。 */
class ResilienceConfigResolverTest {

  @Test
  @DisplayName("服务缺失或未配置时回落安全默认值 5 与 30000")
  void shouldReturnDefaultValuesWhenConfigMissing() {
    ResilienceConfigResolver nullResolver =
        new ResilienceConfigResolver((DynamicConfigService) null);
    assertThat(nullResolver.resolveFailureThreshold()).isEqualTo(5);
    assertThat(nullResolver.resolveOpenDurationMillis()).isEqualTo(30000L);

    MutableDynamicConfigService emptyConfig = new MutableDynamicConfigService();
    ResilienceConfigResolver resolver = new ResilienceConfigResolver(emptyConfig);
    assertThat(resolver.resolveFailureThreshold()).isEqualTo(5);
    assertThat(resolver.resolveOpenDurationMillis()).isEqualTo(30000L);
  }

  @Test
  @DisplayName("合法配置值实时返回")
  void shouldReturnConfiguredValuesWhenValid() {
    MutableDynamicConfigService config = new MutableDynamicConfigService();
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 3);
    config.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, 15000L);

    ResilienceConfigResolver resolver = new ResilienceConfigResolver(config);
    assertThat(resolver.resolveFailureThreshold()).isEqualTo(3);
    assertThat(resolver.resolveOpenDurationMillis()).isEqualTo(15000L);

    // 0ms 开闸保持是允许的边界值
    config.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, 0L);
    assertThat(resolver.resolveOpenDurationMillis()).isEqualTo(0L);
  }

  @Test
  @DisplayName("越界或非法数值安全回落默认值")
  void shouldFallbackToDefaultsWhenValuesOutOfBounds() {
    MutableDynamicConfigService config = new MutableDynamicConfigService();
    ResilienceConfigResolver resolver = new ResilienceConfigResolver(config);

    // threshold <= 0 回落 5
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 0);
    assertThat(resolver.resolveFailureThreshold()).isEqualTo(5);
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, -5);
    assertThat(resolver.resolveFailureThreshold()).isEqualTo(5);

    // duration < 0 回落 30000
    config.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, -1L);
    assertThat(resolver.resolveOpenDurationMillis()).isEqualTo(30000L);
    config.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, -1000L);
    assertThat(resolver.resolveOpenDurationMillis()).isEqualTo(30000L);
  }

  @Test
  @DisplayName("配置服务抛异常时 fail-safe 回落默认值绝不打断业务")
  void shouldNotThrowWhenConfigServiceFails() {
    DynamicConfigService failingService =
        new DynamicConfigService() {
          @Override
          public <T> T get(String key, Class<T> type, T defaultValue) {
            throw new RuntimeException("DB down");
          }
        };

    ResilienceConfigResolver resolver = new ResilienceConfigResolver(failingService);
    assertThatCode(
            () -> {
              assertThat(resolver.resolveFailureThreshold()).isEqualTo(5);
              assertThat(resolver.resolveOpenDurationMillis()).isEqualTo(30000L);
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("wire-circuit-per-dependency-override 任务 2：覆盖键合法值生效于对应依赖")
  void perDependencyValidOverrideTakesPrecedence() {
    MutableDynamicConfigService config = new MutableDynamicConfigService();
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 7);
    config.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, 10000L);
    // 覆盖键合法范围值
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD + ".model-chat", 3);
    config.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS + ".model-chat", 5000L);

    ResilienceConfigResolver resolver = new ResilienceConfigResolver(config);
    assertThat(resolver.resolveFailureThreshold("model-chat")).isEqualTo(3);
    assertThat(resolver.resolveOpenDurationMillis("model-chat")).isEqualTo(5000L);
    // 其他依赖不受覆盖键影响，走全局
    assertThat(resolver.resolveFailureThreshold("model-embed")).isEqualTo(7);
    assertThat(resolver.resolveOpenDurationMillis("model-embed")).isEqualTo(10000L);
  }

  @Test
  @DisplayName("wire-circuit-per-dependency-override 任务 2：级联回落——覆盖缺失就走全局，覆盖越界回落全局，全局也越界回落默认")
  void perDependencyCascadingFallback() {
    MutableDynamicConfigService config = new MutableDynamicConfigService();
    ResilienceConfigResolver resolver = new ResilienceConfigResolver(config);
    String chatKey = ResilienceConfigResolver.KEY_FAILURE_THRESHOLD + ".model-chat";

    // 覆盖键缺失 → 读全局
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 7);
    assertThat(resolver.resolveFailureThreshold("model-chat")).isEqualTo(7);

    // 覆盖键越界（0）→ 视同未配置，回落全局 7
    config.put(chatKey, 0);
    assertThat(resolver.resolveFailureThreshold("model-chat")).isEqualTo(7);
    // 覆盖键负值同样回落全局
    config.put(chatKey, -5);
    assertThat(resolver.resolveFailureThreshold("model-chat")).isEqualTo(7);

    // 全局也越界（-1）→ 回落默认 5
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, -1);
    assertThat(resolver.resolveFailureThreshold("model-chat")).isEqualTo(5);

    // 删覆盖键 → 恢复全局 9
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 9);
    config.remove(chatKey);
    assertThat(resolver.resolveFailureThreshold("model-chat")).isEqualTo(9);
  }

  @Test
  @DisplayName("wire-circuit-per-dependency-override 任务 2：脏类型/未知覆盖键自然回落全局")
  void perDependencyDirtyOrUnknownKeyFallsBackToGlobal() {
    MutableDynamicConfigService config = new MutableDynamicConfigService();
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 6);
    ResilienceConfigResolver resolver = new ResilienceConfigResolver(config);

    // 未知（拼错）依赖名：未注册键 get 返回 null → 走全局
    assertThat(resolver.resolveFailureThreshold("model-chat-typo")).isEqualTo(6);
    // 覆盖键脏类型（放 String 而非 Integer）：get 返回 null → 走全局
    config.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD + ".model-chat", "abc");
    assertThat(resolver.resolveFailureThreshold("model-chat")).isEqualTo(6);
  }

  private static final class MutableDynamicConfigService implements DynamicConfigService {
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    void put(String key, Object value) {
      if (value == null) {
        values.remove(key);
      } else {
        values.put(key, value);
      }
    }

    void remove(String key) {
      values.remove(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
      Object val = values.get(key);
      if (val != null && type.isInstance(val)) {
        return (T) val;
      }
      return defaultValue;
    }
  }
}
