package com.slz.crm.platform.health;

import com.slz.crm.platform.contract.CrmVectorStoreHealth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 向量库健康指示器。
 *
 * <p>实现归属 Lane B；本类只消费冻结契约，缺实现时保持 UP，避免基座在未接入 知识库实现时阻塞 readiness。内存回退返回自定义 WARN 状态，供告警单独识别。
 *
 * <p>TASK-10：非 UP 一律带 host / port / collection 明细，探测抛异常时再带 error—— 否则 {@code /actuator/health} 与启动期
 * {@link FailFastValidator} 只能报出一个没有坐标的 DOWN。host/port 经 {@code knowledge.qdrant.*} 配置键读取（而不是引
 * {@code QdrantProperties} 类型）， 与 {@link MinioHealthIndicator} 一致，以保持 platform → knowledge
 * 之间只走契约/配置、不走类型依赖。
 */
@Component("vectorStoreHealthIndicator")
public class VectorStoreHealthIndicator implements HealthIndicator {

  /** 内存回退专用降级状态；Spring 默认聚合器不会把非标准状态作为 DOWN。 */
  static final Status WARN = new Status("WARN", "向量库处于内存回退降级状态");

  private final ObjectProvider<CrmVectorStoreHealth> vectorStoreHealthProvider;
  private final String host;
  private final int port;

  public VectorStoreHealthIndicator(
      ObjectProvider<CrmVectorStoreHealth> vectorStoreHealthProvider,
      @Value("${knowledge.qdrant.host:localhost}") String host,
      @Value("${knowledge.qdrant.port:6334}") int port) {
    this.vectorStoreHealthProvider = vectorStoreHealthProvider;
    this.host = host;
    this.port = port;
  }

  @Override
  public Health health() {
    CrmVectorStoreHealth storeHealth = vectorStoreHealthProvider.getIfAvailable();
    if (storeHealth == null) {
      return Health.up()
          .withDetail("component", "not-configured")
          .withDetail("message", "知识库向量实现尚未接入")
          .build();
    }
    if (storeHealth.inMemoryFallback()) {
      return Health.status(WARN)
          .withDetail("component", storeHealth.componentName())
          .withDetail("collection", storeHealth.collectionName())
          .withDetail("inMemoryFallback", true)
          .build();
    }
    return probe(storeHealth);
  }

  private Health probe(CrmVectorStoreHealth storeHealth) {
    String error = null;
    boolean reachable;
    try {
      reachable = storeHealth.probe();
    } catch (Exception exception) {
      reachable = false;
      error = describe(exception);
    }
    Health.Builder builder =
        (reachable ? Health.up() : Health.status(Status.DOWN))
            .withDetail("component", storeHealth.componentName())
            .withDetail("collection", storeHealth.collectionName())
            .withDetail("inMemoryFallback", false)
            .withDetail("host", host)
            .withDetail("port", port);
    if (error != null) {
      builder.withDetail("error", error);
    } else if (!reachable) {
      builder.withDetail("message", "Qdrant 集合探测失败（collectionExists 返回不可用）");
    }
    return builder.build();
  }

  private static String describe(Exception exception) {
    return exception.getClass().getName() + ": " + exception.getMessage();
  }
}
