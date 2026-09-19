package com.slz.crm.server.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 缓存配置类 为不同业务数据设置差异化的缓存策略 */
@Configuration
@EnableCaching
public class CacheConfig {

  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager();

    // 定义各缓存名的差异化配置
    Map<String, Caffeine<Object, Object>> caffeineConfigs = new HashMap<>();

    // 用户名缓存：2小时过期（test-hygiene 任务 1.1：开统计供 Micrometer 采集命中率/驱逐）
    caffeineConfigs.put(
        "userName",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(2))
            .initialCapacity(100)
            .maximumSize(1000)
            .recordStats()); // test-hygiene 任务 1.1

    // 部门名缓存：4小时过期（test-hygiene 任务 1.1：开统计）
    caffeineConfigs.put(
        "deptName",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(4))
            .initialCapacity(50)
            .maximumSize(200)
            .recordStats()); // test-hygiene 任务 1.1

    // 公司名称缓存：2小时过期（test-hygiene 任务 1.1：开统计）
    caffeineConfigs.put(
        "companyName",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(2))
            .initialCapacity(100)
            .maximumSize(500)
            .recordStats()); // test-hygiene 任务 1.1

    // 联系人姓名缓存：2小时过期（test-hygiene 任务 1.1：开统计）
    caffeineConfigs.put(
        "contactName",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(2))
            .initialCapacity(100)
            .maximumSize(500)
            .recordStats()); // test-hygiene 任务 1.1

    // 商机名称缓存：30分钟过期（test-hygiene 任务 1.1：开统计）
    caffeineConfigs.put(
        "opportunityName",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .initialCapacity(100)
            .maximumSize(500)
            .recordStats()); // test-hygiene 任务 1.1

    // 合同名称缓存：30分钟过期（test-hygiene 任务 1.1：开统计）
    caffeineConfigs.put(
        "contractName",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .initialCapacity(100)
            .maximumSize(500)
            .recordStats()); // test-hygiene 任务 1.1

    // 统计图表缓存：10分钟过期（test-hygiene 任务 1.1：开统计）
    caffeineConfigs.put(
        "chartDataCache",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .initialCapacity(20)
            .maximumSize(100)
            .recordStats()); // test-hygiene 任务 1.1

    // 统计图表缓存：10分钟过期（test-hygiene 任务 1.1：开统计）
    caffeineConfigs.put(
        "chartCache",
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .initialCapacity(20)
            .maximumSize(100)
            .recordStats()); // test-hygiene 任务 1.1

    // 注册所有自定义缓存
    caffeineConfigs.forEach(
        (name, caffeine) -> cacheManager.registerCustomCache(name, caffeine.build()));

    return cacheManager;
  }
}
