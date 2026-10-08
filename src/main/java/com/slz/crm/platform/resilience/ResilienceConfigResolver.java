package com.slz.crm.platform.resilience;

import com.slz.crm.platform.contract.DynamicConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 熔断韧性参数配置解析器（wire-circuit-dynamic-config 任务 3）。
 *
 * <p>统一读取平台动态配置中的全局熔断参数，每调用实时读取； 键缺失、写入非法值或数值越界时回落安全默认值（fail-safe），绝不抛出配置异常打断业务调用。
 *
 * <p>wire-circuit-per-dependency-override 任务 2：新增按依赖名重载 {@code resolveFailureThreshold(String)} 与
 * {@code resolveOpenDurationMillis(String)}——覆盖键 {@code
 * platform.resilience.failure-threshold.<dep>} 优先；未配置/脏类型/越界/拼错依赖名时逐级回落全局键与默认值，任一级异常不外抛。
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
   * 解析连续失败熔断阈值（全局语义，等同按依赖解析的 null 路径）。
   *
   * @return 连续失败阈值；≤0 或非法值回落默认值 5
   */
  public int resolveFailureThreshold() {
    return resolveFailureThreshold(null);
  }

  /**
   * 解析指定依赖的连续失败熔断阈值（wire-circuit-per-dependency-override 任务 2）。
   *
   * <p>逐级回落：覆盖键 {@code platform.resilience.failure-threshold.<dep>} 未配置/脏类型/越界 → 读全局键 {@code
   * platform.resilience.failure-threshold} → 全局也越界/缺失/异常 → 默认 5。依赖名为 null 时跳过覆盖级
   * （等价无参版全局语义）；拼错依赖名（未注册键 get 返回默认）自然回落全局。全程 fail-safe 不外抛。
   *
   * @param dependency 依赖名（model-chat/model-embed/model-vision/vector-qdrant/storage-minio 等）；null
   *     表示只读全局
   * @return 该依赖的连续失败阈值；越界或非法值回落全局值，全局也越界/缺失/异常回落默认值 5
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 容错解析：配置服务异常时回落安全默认值，绝不打断业务调用
  public int resolveFailureThreshold(String dependency) {
    int result = DEFAULT_FAILURE_THRESHOLD;
    try {
      DynamicConfigService config = resolveService();
      if (config != null) {
        boolean resolved = false;
        if (dependency != null) {
          Integer override =
              config.get(KEY_FAILURE_THRESHOLD + "." + dependency, Integer.class, null);
          if (override != null && override > 0) {
            result = override;
            resolved = true;
          }
        }
        if (!resolved) {
          Integer global =
              config.get(KEY_FAILURE_THRESHOLD, Integer.class, DEFAULT_FAILURE_THRESHOLD);
          if (global != null && global > 0) {
            result = global;
          }
        }
      }
    } catch (Exception ignored) {
      result = DEFAULT_FAILURE_THRESHOLD;
    }
    return result;
  }

  /**
   * 解析熔断开闸保持时长（毫秒，全局语义，等同按依赖解析的 null 路径）。
   *
   * @return 熔断开闸保持毫秒数；<0 或非法值回落默认值 30000
   */
  public long resolveOpenDurationMillis() {
    return resolveOpenDurationMillis(null);
  }

  /**
   * 解析指定依赖的熔断开闸保持时长（毫秒）（wire-circuit-per-dependency-override 任务 2）。
   *
   * <p>逐级回落：覆盖键 {@code platform.resilience.open-duration-ms.<dep>} 未配置/脏类型/越界 → 读全局键 {@code
   * platform.resilience.open-duration-ms} → 全局也越界/缺失/异常 → 默认 30000。依赖名为 null 时跳过覆盖级； 拼错依赖名自然回落全局。全程
   * fail-safe 不外抛。
   *
   * @param dependency 依赖名；null 表示只读全局
   * @return 该依赖的开闸保持毫秒数；越界或非法值回落全局值，全局也越界/缺失/异常回落默认值 30000
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 容错解析：配置服务异常时回落安全默认值，绝不打断业务调用
  public long resolveOpenDurationMillis(String dependency) {
    long result = DEFAULT_OPEN_DURATION_MILLIS;
    try {
      DynamicConfigService config = resolveService();
      if (config != null) {
        boolean resolved = false;
        if (dependency != null) {
          Long override = config.get(KEY_OPEN_DURATION_MS + "." + dependency, Long.class, null);
          if (override != null && override >= 0) {
            result = override;
            resolved = true;
          }
        }
        if (!resolved) {
          Long global = config.get(KEY_OPEN_DURATION_MS, Long.class, DEFAULT_OPEN_DURATION_MILLIS);
          if (global != null && global >= 0) {
            result = global;
          }
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
