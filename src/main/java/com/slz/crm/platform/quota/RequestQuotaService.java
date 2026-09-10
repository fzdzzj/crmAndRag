package com.slz.crm.platform.quota;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 身份/IP/知识库/全局四层固定窗口配额服务。
 *
 * <p>固定窗口实现足够保护单体内存与模型并发；每个 key 使用独立锁，
 * 避免 Caffeine 缓存级锁影响不同用户。超限必须快速拒绝并返回机器可读原因。</p>
 */
@Service
public class RequestQuotaService {

    private final QuotaProperties properties;
    private final MeterRegistry meterRegistry;
    private final Cache<QuotaDimension, Cache<String, FixedWindow>> windows;

    /**
     * 构造配额服务。
     *
     * @param properties 配额阈值
     * @param meterRegistry Micrometer 注册表
     */
    public RequestQuotaService(QuotaProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.windows = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    /**
     * 尝试占用一次配额。
     *
     * @param dimension 配额维度
     * @param key 维度内标识；全局传 {@code GLOBAL}
     * @return 允许或拒绝决策
     */
    public QuotaDecision tryAcquire(QuotaDimension dimension, String key) {
        Objects.requireNonNull(dimension, "配额维度不能为空");
        String normalizedKey = key == null || key.isBlank() ? "GLOBAL" : key;
        FixedWindow window = cache(dimension).get(normalizedKey, ignored -> new FixedWindow());
        long limit = limit(dimension);
        synchronized (window) {
            long now = System.currentTimeMillis();
            window.resetIfNeeded(now, 60_000);
            if (window.count.get() >= limit) {
                long retryAfterSeconds = Math.max(1,
                        Duration.ofMillis(window.windowStartMillis + 60_000 - now).toSeconds());
                meterRegistry.counter("platform.quota.rejected", "dimension", dimension.name()).increment();
                return new QuotaDecision(false, window.count.get(), limit, retryAfterSeconds,
                        "RATE_LIMITED");
            }
            window.count.incrementAndGet();
        }
        meterRegistry.counter("platform.quota.acquired", "dimension", dimension.name()).increment();
        return new QuotaDecision(true, window.count.get(), limit, 0, "OK");
    }

    private long limit(QuotaDimension dimension) {
        return switch (dimension) {
            case USER -> properties.getUserPerMinute();
            case IP -> properties.getIpPerMinute();
            case KNOWLEDGE_BASE -> properties.getKnowledgeBasePerMinute();
            case GLOBAL -> properties.getGlobalPerMinute();
        };
    }

    private Cache<String, FixedWindow> cache(QuotaDimension dimension) {
        return windows.get(dimension, ignored -> Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .build());
    }

    /** 单 key 固定窗口计数器；并发访问由调用方同步。 */
    private static final class FixedWindow {
        private final AtomicLong count = new AtomicLong();
        private long windowStartMillis;

        private void resetIfNeeded(long nowMillis, long windowMillis) {
            if (nowMillis - windowStartMillis >= windowMillis) {
                windowStartMillis = nowMillis;
                count.set(0);
            }
        }
    }
}
