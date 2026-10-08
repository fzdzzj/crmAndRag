package com.slz.crm.platform.resilience;

import com.slz.crm.platform.contract.DynamicConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 熔断韧性参数配置解析器（wire-circuit-dynamic-config 任务 3）。
 *
 * <p>统一读取平台动态配置中的全局熔断参数，每调用实时读取； 键缺失、写入非法值或数值越界时回落安全默认值（fail-safe），绝不抛出配置异常打断业务调用。
 */
@Component
public class ResilienceConfigResolver {

  public static final String KEY_FAILURE_THRESHOLD = "platform.resilience.failure-threshold";
  public static final String KEY_OPEN_DURATION_MS = "platform.resilience.open-duration-ms";

  public static final int DEFAULT_FAILURE_THRESHOLD = 5;
  public static final long DEFAULT_OPEN_DURATION_MILLIS = 30000L;

  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private final DynamicConfigService directService;

  @Autowired
  public ResilienceConfigResolver(ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.directService = null;
  }

  ResilienceConfigResolver(DynamicConfigService dynamicConfigService) {
    this.dynamicConfigProvider = null;
    this.directService = dynamicConfigService;
  }

  /**
   * 解析连续失败熔断阈值。
   *
   * @return 连续失败阈值；≤0 或非法值回落默认值 5
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 容错解析：配置服务异常时回落安全默认值，绝不打断业务调用
  public int resolveFailureThreshold() {
    int result = DEFAULT_FAILURE_THRESHOLD;
    try {
      DynamicConfigService config = resolveService();
      if (config != null) {
        Integer value = config.get(KEY_FAILURE_THRESHOLD, Integer.class, DEFAULT_FAILURE_THRESHOLD);
        if (value != null && value > 0) {
          result = value;
        }
      }
    } catch (Exception ignored) {
      result = DEFAULT_FAILURE_THRESHOLD;
    }
    return result;
  }

  /**
   * 解析熔断开闸保持时长（毫秒）。
   *
   * @return 熔断开闸保持毫秒数；<0 或非法值回落默认值 30000
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 容错解析：配置服务异常时回落安全默认值，绝不打断业务调用
  public long resolveOpenDurationMillis() {
    long result = DEFAULT_OPEN_DURATION_MILLIS;
    try {
      DynamicConfigService config = resolveService();
      if (config != null) {
        Long value = config.get(KEY_OPEN_DURATION_MS, Long.class, DEFAULT_OPEN_DURATION_MILLIS);
        if (value != null && value >= 0) {
          result = value;
        }
      }
    } catch (Exception ignored) {
      result = DEFAULT_OPEN_DURATION_MILLIS;
    }
    return result;
  }

  private DynamicConfigService resolveService() {
    DynamicConfigService result = directService;
    if (result == null && dynamicConfigProvider != null) {
      result = dynamicConfigProvider.getIfAvailable();
    }
    return result;
  }
}
