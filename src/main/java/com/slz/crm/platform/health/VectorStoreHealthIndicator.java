package com.slz.crm.platform.health;

import com.slz.crm.platform.contract.CrmVectorStoreHealth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 向量库健康指示器。
 *
 * <p>实现归属 Lane B；本类只消费冻结契约，缺实现时保持 UP，避免基座在未接入
 * 知识库实现时阻塞 readiness。内存回退返回自定义 WARN 状态，供告警单独识别。</p>
 */
@Component("vectorStoreHealthIndicator")
public class VectorStoreHealthIndicator implements HealthIndicator {

    /**
     * 内存回退专用降级状态；Spring 默认聚合器不会把非标准状态作为 DOWN。
     */
    static final Status WARN = new Status("WARN", "向量库处于内存回退降级状态");

    private final ObjectProvider<CrmVectorStoreHealth> vectorStoreHealthProvider;

    public VectorStoreHealthIndicator(ObjectProvider<CrmVectorStoreHealth> vectorStoreHealthProvider) {
        this.vectorStoreHealthProvider = vectorStoreHealthProvider;
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

        Health.Builder builder = Health.status(WARN)
                .withDetail("component", storeHealth.componentName())
                .withDetail("collection", storeHealth.collectionName())
                .withDetail("inMemoryFallback", storeHealth.inMemoryFallback());
        if (!storeHealth.inMemoryFallback()) {
            builder = storeHealth.probe()
                    ? Health.up()
                    : Health.down();
            builder.withDetail("component", storeHealth.componentName())
                    .withDetail("collection", storeHealth.collectionName())
                    .withDetail("inMemoryFallback", false);
        }
        return builder.build();
    }
}
