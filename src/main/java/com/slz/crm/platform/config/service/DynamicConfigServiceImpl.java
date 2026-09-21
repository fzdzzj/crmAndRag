package com.slz.crm.platform.config.service;

import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.DynamicConfigCache;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.contract.DynamicConfigService;
import org.springframework.stereotype.Service;

/**
 * 动态配置读取服务（冻结契约 {@link DynamicConfigService} 的实现，B/C/D 消费）。
 *
 * <p>读取语义（对齐契约 Javadoc）：
 *
 * <ul>
 *   <li>键未注册 / 数据库无覆盖 / 存储值非法 / 类型不匹配 → 一律返回调用方传入的默认值，不抛异常；
 *   <li>数据库有覆盖且合法 → 返回动态值（热生效由 {@link DynamicConfigCache} 的有界刷新保证）。
 * </ul>
 *
 * <p>线程安全：无状态组件 + 线程安全缓存，可并发读。
 */
@Service
public class DynamicConfigServiceImpl implements DynamicConfigService {

  private final DynamicConfigKeyRegistry registry;
  private final DynamicConfigCache cache;

  public DynamicConfigServiceImpl(DynamicConfigKeyRegistry registry, DynamicConfigCache cache) {
    this.registry = registry;
    this.cache = cache;
  }

  @Override
  public <T> T get(String key, Class<T> type, T defaultValue) {
    T result = defaultValue;
    ConfigKeyDefinition def = registry.definitionOf(key).orElse(null);
    if (def != null) {
      Object value = cache.typedValue(key);
      if (value != null && type.isInstance(value)) {
        // 类型匹配才返回动态值，否则契约要求回退默认，避免脏类型泄漏
        result = type.cast(value);
      }
    }
    return result;
  }
}
