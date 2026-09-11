package com.slz.crm.platform.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热生效缓存测试：稳态命中不打库、写后失效即时生效、有界刷新收敛陈旧值、非法存储值回退。
 */
class DynamicConfigCacheTest {

    private DynamicConfigItemMapper itemMapper;
    private DynamicConfigKeyRegistry registry;
    private DynamicConfigCache cache;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        // 单元测试无 Spring 上下文：手动初始化 MP 表元信息（否则 LambdaQueryWrapper 拿不到列映射）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DynamicConfigItemEntity.class);
        itemMapper = mock(DynamicConfigItemMapper.class);
        registry = new DynamicConfigKeyRegistry(new ObjectMapper());
        DynamicConfigProperties props = new DynamicConfigProperties();
        props.setRefreshIntervalMs(5000L);
        cache = new DynamicConfigCache(itemMapper, registry, props);
        clock = new AtomicLong(0);
        cache.setClock(clock::get);
    }

    private DynamicConfigItemEntity item(String key, String value, int version) {
        DynamicConfigItemEntity e = new DynamicConfigItemEntity();
        e.setId(1L);
        e.setConfigKey(key);
        e.setNamespace("rag.retrieval");
        e.setValueType("INTEGER");
        e.setConfigValue(value);
        e.setVersion(version);
        e.setIsDeleted(false);
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("稳态读取命中缓存不击穿数据库；超界后全量刷新收敛到新值")
    void boundedRefreshPicksUpDbChange() {
        DynamicConfigItemEntity row = item("rag.retrieval.topK", "8", 1);
        when(itemMapper.selectOne(any())).thenReturn(row);
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of(row));

        // 首次访问：键级加载（selectOne），窗口内不再触库
        assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);
        assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);
        verify(itemMapper, times(1)).selectOne(any());
        verify(itemMapper, never()).selectList(any());

        // 窗口内：DB 已被旁路修改，但陈旧窗口允许旧值（上界 = 刷新间隔）
        row.setConfigValue("6");
        assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);

        // 超过刷新间隔：有界刷新触发全量重载，收敛到最新值
        clock.set(6000L);
        assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(6);
        verify(itemMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("写后失效：下一读立即回库取新值，且不再走全量刷新")
    void invalidateReloadsImmediately() {
        DynamicConfigItemEntity row = item("rag.retrieval.topK", "8", 1);
        when(itemMapper.selectOne(any())).thenReturn(row);
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of(row));

        assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);
        verify(itemMapper, times(1)).selectOne(any());

        // 写操作提交后失效：即使窗口未到，也立即回库取新值
        row.setConfigValue("6");
        cache.invalidate("rag.retrieval.topK");
        assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(6);
        verify(itemMapper, times(2)).selectOne(any());
        // 全量刷新仍未触发（窗口内），说明失效走的是键级回库而非整表重载
        verify(itemMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("软删行不可见（回退默认）；非法存储值解析失败返回 null")
    void deletedAndInvalidValuesFallBack() {
        // 用可编程状态驱动单个 Answer（避免重桩触发旧 Answer 的副作用）
        AtomicReference<DynamicConfigItemEntity> current = new AtomicReference<>();
        when(itemMapper.selectOne(any())).thenAnswer(inv -> {
            DynamicConfigItemEntity row = current.get();
            if (row == null) {
                return null;
            }
            String sql = ((LambdaQueryWrapper<?>) inv.getArgument(0)).getSqlSegment();
            return sql.contains("is_deleted") && Boolean.TRUE.equals(row.getIsDeleted()) ? null : row;
        });

        // 软删行：loadFromDb 的 is_deleted 过滤使其不可见 → 回退默认
        DynamicConfigItemEntity deleted = item("rag.retrieval.topK", "8", 2);
        deleted.setIsDeleted(true);
        current.set(deleted);
        assertThat(cache.typedValue("rag.retrieval.topK")).isNull();

        // 非法存储值：解析失败 → 回退默认
        current.set(item("rag.retrieval.topK", "not-a-number", 1));
        assertThat(cache.typedValue("rag.retrieval.topK")).isNull();

        // 无覆盖行：回退默认
        current.set(null);
        assertThat(cache.typedValue("rag.retrieval.topK")).isNull();
    }

    @Test
    @DisplayName("缓存关闭时读取直查数据库（排障模式）")
    void cacheDisabledReadsThrough() {
        DynamicConfigProperties props = new DynamicConfigProperties();
        props.setCacheEnabled(false);
        DynamicConfigCache direct = new DynamicConfigCache(itemMapper, registry, props);
        DynamicConfigItemEntity row = item("rag.retrieval.topK", "8", 1);
        when(itemMapper.selectOne(any())).thenReturn(row);

        assertThat(direct.typedValue("rag.retrieval.topK")).isEqualTo(8);
        assertThat(direct.typedValue("rag.retrieval.topK")).isEqualTo(8);
        // 每次直查数据库
        verify(itemMapper, times(2)).selectOne(any());
    }

    @Test
    @DisplayName("缓存条目携带版本与敏感标记，供管理端展示")
    void entryCarriesMetadata() {
        DynamicConfigItemEntity row = item("rag.retrieval.topK", "8", 3);
        row.setSensitive(false);
        when(itemMapper.selectOne(any())).thenReturn(row);
        DynamicConfigCache.Entry entry = cache.entryOf("rag.retrieval.topK");
        assertThat(entry).isNotNull();
        assertThat(entry.version()).isEqualTo(3);
        assertThat(entry.sensitive()).isFalse();
        assertThat(entry.rawValue()).isEqualTo("8");
        assertThat(entry.typedValue()).isEqualTo(8);
    }
}
