package com.slz.crm.platform.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 动态配置读取缓存（热生效的核心：缓存 + 有界刷新）。
 *
 * <p><b>机制（对应 spec「缓存与失效」场景）：</b></p>
 * <ul>
 *   <li><b>稳态命中</b>：读取只碰 {@link ConcurrentHashMap}，不击穿数据库；</li>
 *   <li><b>写后逐键失效</b>：{@link #invalidate(String)} 摘掉该键，下次读取按需回库重载（同实例即时生效）；</li>
 *   <li><b>有界全量刷新</b>：距上次全量加载超过 {@code refreshIntervalMs} 后首次读取触发全量重载
 *       （整表换引用，无锁），兜底多实例/旁路改库——<b>陈旧窗口上界 = 刷新间隔</b>；
 *       窗口内最坏读到旧值，超界必收敛到最新值；</li>
 *   <li>存储值解析失败（历史脏数据/手工改库）记为非法，读取方回退默认，不炸链路。</li>
 * </ul>
 *
 * <p>线程安全：{@code entries} 引用 volatile 换表 + ConcurrentHashMap 原位写，读多写少的场景下
 * 无锁且无数据竞争（最坏丢一次失效信号，由有界刷新兜底）。</p>
 */
@Slf4j
@Component
public class DynamicConfigCache {

    /** 单个键的缓存条目 */
    public record Entry(String key, Object typedValue, String rawValue, int version, boolean sensitive) {
    }

    private final DynamicConfigItemMapper itemMapper;
    private final DynamicConfigKeyRegistry registry;
    private final DynamicConfigProperties properties;

    /** 当前快照（全量刷新时整体换引用） */
    private volatile ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** 上次全量刷新时刻（毫秒）；0 = 从未加载 */
    private volatile long lastFullRefreshMs = 0L;

    /** 时钟源（测试可注入控制时间推进） */
    private LongSupplier clock = System::currentTimeMillis;

    public DynamicConfigCache(DynamicConfigItemMapper itemMapper,
                              DynamicConfigKeyRegistry registry,
                              DynamicConfigProperties properties) {
        this.itemMapper = itemMapper;
        this.registry = registry;
        this.properties = properties;
    }

    /** 测试专用：替换时钟源（仅本包测试可调用） */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 读取某个键的类型化值；未配置 / 存储值非法返回 {@code null}（调用方回退默认）。
     *
     * @param key 配置键
     */
    public Object typedValue(String key) {
        Entry entry = entryOf(key);
        return entry == null ? null : entry.typedValue();
    }

    /**
     * 读取某个键的缓存条目；未配置返回 {@code null}。
     */
    public Entry entryOf(String key) {
        if (!properties.isCacheEnabled()) {
            return loadFromDb(key);
        }
        ensureFreshIfDue();
        ConcurrentHashMap<String, Entry> current = entries;
        Entry entry = current.get(key);
        if (entry == null) {
            // 键级 miss：写后失效或首次访问 → 按需回库一次并回填（不触发全量刷新）
            entry = loadFromDb(key);
            if (entry != null) {
                current.put(key, entry);
            }
        }
        return entry;
    }

    /**
     * 写操作提交后调用：摘掉该键，保证同实例下一次读取立即拿到新值。
     *
     * @param key 配置键
     */
    public void invalidate(String key) {
        entries.remove(key);
    }

    /**
     * 全量重载（管理端手动刷新 / 有界刷新触发），返回加载条目数。
     */
    public int refreshAll() {
        List<DynamicConfigItemEntity> items = itemMapper.selectList(
                new LambdaQueryWrapper<DynamicConfigItemEntity>()
                        .eq(DynamicConfigItemEntity::getIsDeleted, false));
        ConcurrentHashMap<String, Entry> fresh = new ConcurrentHashMap<>();
        for (DynamicConfigItemEntity item : items) {
            Entry entry = build(item);
            if (entry != null) {
                fresh.put(item.getConfigKey(), entry);
            }
        }
        entries = fresh;
        lastFullRefreshMs = clock.getAsLong();
        return fresh.size();
    }

    /** @return 当前缓存条目数（管理端/测试查看用） */
    public int size() {
        return entries.size();
    }

    /** 有界刷新判定：距上次全量刷新超过刷新间隔则执行全量重载 */
    private void ensureFreshIfDue() {
        if (clock.getAsLong() - lastFullRefreshMs >= properties.getRefreshIntervalMs()) {
            refreshAll();
        }
    }

    /** 按键回库加载（写后失效/首访路径；过滤软删行） */
    private Entry loadFromDb(String key) {
        DynamicConfigItemEntity item = itemMapper.selectOne(
                new LambdaQueryWrapper<DynamicConfigItemEntity>()
                        .eq(DynamicConfigItemEntity::getConfigKey, key)
                        .eq(DynamicConfigItemEntity::getIsDeleted, false));
        return item == null ? null : build(item);
    }

    /** 实体 → 缓存条目；存储值解析失败记日志并返回非法条目（读取方回退默认） */
    private Entry build(DynamicConfigItemEntity item) {
        Object typed = registry.parseStored(item.getConfigKey(), item.getConfigValue());
        if (typed == null && item.getConfigValue() != null) {
            log.warn("动态配置存储值无法解析，回退默认：key={}, valueType={}, stored={}",
                    item.getConfigKey(), item.getValueType(), maskForLog(item.getConfigValue()));
        }
        return new Entry(item.getConfigKey(), typed, item.getConfigValue(),
                item.getVersion() == null ? 1 : item.getVersion(),
                Boolean.TRUE.equals(item.getSensitive()));
    }

    /** 日志脱敏：敏感值/解析失败值只露长度，不露明文 */
    private String maskForLog(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() + "字符";
    }
}
