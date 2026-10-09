package com.slz.crm.knowledge.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.knowledge.entity.KbRetrievalStrategy;
import com.slz.crm.platform.config.DynamicConfigProperties;
import com.slz.crm.server.mapper.KbRetrievalStrategyMapper;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 知识库级检索策略覆盖读取服务（add-per-kb-retrieval-strategy-override 任务 3.1）。
 *
 * <p><b>热度失效镜像 {@link com.slz.crm.platform.config.DynamicConfigCache} 的模式</b>：
 *
 * <ul>
 *   <li><b>稳态命中</b>：读取只碰 {@link ConcurrentHashMap}，不击穿数据库；
 *   <li><b>写后逐键失效</b>：写端（put/delete/rollback 提交后）调用 {@link #invalidate(Long, String)} 摘掉该 (kbId,
 *       key)，下��读取按需回库重载（同实例即时生效）；
 *   <li><b>有界全量刷新</b>：距上次全量加载超过 {@code refreshIntervalMs} 后首次读取触发全量重载（整表换引用）， 兜底多实例/旁路改库——<b>陈旧窗口上界
 *       = 刷新间隔</b>；
 *   <li>存储值解析失败（历史脏数据/手工改库或写坏）记为非法，读取方回落全局，不炸链路。
 * </ul>
 *
 * <p><b>三层合并（任务 3 契约 2）</b>：{@link #resolve} 的语义 = 覆盖值 &gt; 全局动态配置（由调用方传入 globalValue）&gt;
 * 注册表默认。本服务只负责「该 (kbId, key) 是否有合法覆盖」；全局与默认的裁定由 {@link RetrievalConfigResolver} 完成。
 *
 * <p>线程安全：{@code entries} 引用 volatile 换表 + ConcurrentHashMap 原位写，读多写少场景无锁无数据竞争。
 */
@Slf4j
@Service
public class KbRetrievalStrategyService {

  /** 单条覆盖缓存条目：typedValue=null 表示该 (kbId,key) 无覆盖或存储非法（读取方回落全局）。 */
  public record Entry(Long kbId, String key, Object typedValue, Throwable reason) {}

  private static final String KEY_SEPARATOR = "\u0000";

  /** CHM 禁止 null 值：用哨兵对象表示「该 (kbId,key) 无覆盖或存储非法」（读取方回落全局）。 */
  private static final Object NULL_VALUE = new Object();

  private final KbRetrievalStrategyMapper strategyMapper;
  private final KbRetrievalStrategyWhitelist whitelist;
  private final DynamicConfigProperties properties;

  /** 当前快照（全量刷新时整体换引用） */
  private volatile ConcurrentHashMap<String, Object> typedValues = new ConcurrentHashMap<>();

  /** 上次全量刷新时刻（毫秒）；0 = 从未加载 */
  private volatile long lastFullRefreshMs = 0L;

  /** 时钟源（测试可注入控制时间推进） */
  private LongSupplier clock = System::currentTimeMillis;

  public KbRetrievalStrategyService(
      KbRetrievalStrategyMapper strategyMapper,
      KbRetrievalStrategyWhitelist whitelist,
      DynamicConfigProperties properties) {
    this.strategyMapper = strategyMapper;
    this.whitelist = whitelist;
    this.properties = properties;
  }

  /** 测试专用：替换时钟源（仅本包测试可调用）。 */
  void setClock(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * 三层合并读取：返回该 (kbId, key) 的合法覆盖值；无覆盖 / kbId 为空 / 存储值非法返回 {@code globalValue}。
   *
   * <p>单库作用域判定的前置（{@code kbId != null}）由调用方 {@link RetrievalConfigResolver} 负责—— 多库/全库/kbId
   * 不可解析时调用方传 {@code null}，本服务即按全局处理。
   *
   * @param kbId 单库作用域下收敛出的知识库 ID（非单库传 null）
   * @param key 覆盖键
   * @param type 期望类型
   * @param globalValue 全局动态配置或注册表默认的回落值
   * @param <T> 值类型
   * @return 生效值
   */
  public <T> T resolve(Long kbId, String key, Class<T> type, T globalValue) {
    T result = globalValue;
    if (kbId != null) {
      Object cached = entryTyped(kbId, key);
      if (cached != null && cached != NULL_VALUE) {
        if (type.isInstance(cached)) {
          result = type.cast(cached);
        } else {
          // 类型不匹配（理论不出现：解析按注册表类型落值）；防御性回落全局并记 WARN。
          log.warn(
              "per-KB 覆盖类型与期望不符，回落全局：kbId={}, key={}, expected={}, actual={}",
              kbId,
              key,
              type.getName(),
              cached.getClass().getName());
        }
      }
    }
    return result;
  }

  /**
   * 写操作提交后调用：摘掉该 (kbId, key) 键，保证同实例下一次读取立即拿到新值。
   *
   * @param kbId 知识库 ID（非空）
   * @param key 覆盖键
   */
  public void invalidate(Long kbId, String key) {
    if (kbId != null && key != null) {
      typedValues.remove(cacheKey(kbId, key));
    }
  }

  /** 全量重载（管理端手动刷新 / 有界刷新触发），返回加载的活覆盖记录数。 */
  public int refreshAll() {
    List<KbRetrievalStrategy> items =
        strategyMapper.selectList(
            new LambdaQueryWrapper<KbRetrievalStrategy>()
                .eq(KbRetrievalStrategy::getIsDeleted, false));
    ConcurrentHashMap<String, Object> fresh = new ConcurrentHashMap<>();
    for (KbRetrievalStrategy item : items) {
      // 仅缓存白名单键（旁路改库可能写过白名单外/软删脏数据，防御性滤除，不缓存不炸链路）
      if (item.getKbId() == null || item.getStrategyKey() == null) {
        continue;
      }
      Object typed = null;
      if (item.getConfigValue() != null) {
        typed = whitelist.parseStored(item.getStrategyKey(), item.getConfigValue());
      }
      if (typed == null && item.getConfigValue() != null) {
        log.warn(
            "per-KB 覆盖存储值无法解析，回落全局：kbId={}, key={}, storedLen={}",
            item.getKbId(),
            item.getStrategyKey(),
            item.getConfigValue().length());
      }
      fresh.put(
          cacheKey(item.getKbId(), item.getStrategyKey()), typed == null ? NULL_VALUE : typed);
    }
    typedValues = fresh;
    lastFullRefreshMs = clock.getAsLong();
    return items.size();
  }

  /**
   * @return 当前缓存条目数（管理端/测试查看用）
   */
  public int size() {
    return typedValues.size();
  }

  /** 读取某 (kbId, key) 的类型化覆盖值；null = 无覆盖或存储非法（调用方回落全局）。 */
  public Object entryTyped(Long kbId, String key) {
    Object result;
    if (!properties.isCacheEnabled()) {
      result = loadFromDb(kbId, key);
    } else {
      ensureFreshIfDue();
      ConcurrentHashMap<String, Object> current = typedValues;
      Object typed = current.get(cacheKey(kbId, key));
      if (typed == null && !current.containsKey(cacheKey(kbId, key))) {
        // 键级 miss：写后失效或首次访问 → 按需回库一次并回填（不触发全量刷新）
        typed = loadFromDb(kbId, key);
        current.put(cacheKey(kbId, key), typed == null ? NULL_VALUE : typed);
      }
      result = typed;
    }
    return normalize(result);
  }

  /** 把缓存中的哨兵还原为对外契约的 null。 */
  private Object normalize(Object typed) {
    return typed == NULL_VALUE ? null : typed;
  }

  /** 有界刷新判定：距上次全量加载超过刷新间隔则执行全量重载 */
  private void ensureFreshIfDue() {
    if (clock.getAsLong() - lastFullRefreshMs >= properties.getRefreshIntervalMs()) {
      refreshAll();
    }
  }

  /** 按 (kbId, key) 回库加载（写后失效/首访路径；过滤软删行） */
  private Object loadFromDb(Long kbId, String key) {
    Object result = null;
    if (kbId != null && key != null) {
      result = loadLiveValue(kbId, key);
    }
    return result;
  }

  /** 回库读活覆盖并解析类型值；无匹配/值空返回 null（调用方回落全局）。 */
  private Object loadLiveValue(Long kbId, String key) {
    KbRetrievalStrategy item =
        strategyMapper.selectOne(
            new LambdaQueryWrapper<KbRetrievalStrategy>()
                .eq(KbRetrievalStrategy::getKbId, kbId)
                .eq(KbRetrievalStrategy::getStrategyKey, key)
                .eq(KbRetrievalStrategy::getIsDeleted, false));
    Object result = null;
    if (item != null && item.getConfigValue() != null) {
      result = whitelist.parseStored(item.getStrategyKey(), item.getConfigValue());
      if (result == null) {
        log.warn(
            "per-KB 覆盖存储值无法解析，回落全局：kbId={}, key={}, storedLen={}",
            kbId,
            key,
            item.getConfigValue().length());
      }
    }
    return result;
  }

  private static String cacheKey(Long kbId, String key) {
    return kbId + KEY_SEPARATOR + key;
  }
}
