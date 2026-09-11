package com.slz.crm.platform.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigItemMapper;
import com.slz.crm.platform.config.service.DynamicConfigServiceImpl;
import com.slz.crm.platform.contract.DynamicConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 冻结契约 {@link DynamicConfigService#get} 的读取语义测试：
 * 未配置/未知键/类型不匹配/非法存储值一律回退默认，配置生效值热更新后无需重启即可读到。
 */
class DynamicConfigServiceImplTest {

    private DynamicConfigItemMapper itemMapper;
    private DynamicConfigService service;

    @BeforeEach
    void setUp() {
        // 单元测试无 Spring 上下文：手动初始化 MP 表元信息（否则 LambdaQueryWrapper 拿不到列映射）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DynamicConfigItemEntity.class);
        itemMapper = mock(DynamicConfigItemMapper.class);
        DynamicConfigKeyRegistry registry = new DynamicConfigKeyRegistry(new ObjectMapper());
        DynamicConfigProperties props = new DynamicConfigProperties();
        props.setRefreshIntervalMs(60_000L);
        DynamicConfigCache cache = new DynamicConfigCache(itemMapper, registry, props);
        service = new DynamicConfigServiceImpl(registry, cache);
    }

    private DynamicConfigItemEntity item(String key, String value, String valueType) {
        DynamicConfigItemEntity e = new DynamicConfigItemEntity();
        e.setId(1L);
        e.setConfigKey(key);
        e.setConfigValue(value);
        e.setValueType(valueType);
        e.setVersion(1);
        e.setIsDeleted(false);
        return e;
    }

    @Test
    @DisplayName("数据库无覆盖 → 回退调用方默认值")
    void absentConfigReturnsDefault() {
        when(itemMapper.selectOne(any())).thenReturn(null);
        assertThat(service.get("rag.retrieval.topK", Integer.class, 5)).isEqualTo(5);
        assertThat(service.get("ai.model.temperature", Double.class, 0.3d)).isEqualTo(0.3d);
    }

    @Test
    @DisplayName("数据库有覆盖 → 动态值优先（覆盖静态默认）")
    void databaseValueOverridesStaticDefault() {
        when(itemMapper.selectOne(any())).thenReturn(item("rag.retrieval.topK", "8", "INTEGER"));
        assertThat(service.get("rag.retrieval.topK", Integer.class, 5)).isEqualTo(8);
    }

    @Test
    @DisplayName("未知键 → 回退默认（消费方按固定键编程，防御性兜底）")
    void unknownKeyReturnsDefault() {
        when(itemMapper.selectOne(any())).thenReturn(null);
        assertThat(service.get("no.such.key", Integer.class, 42)).isEqualTo(42);
    }

    @Test
    @DisplayName("类型不匹配（如 String 读 INTEGER 键）→ 回退默认")
    void typeMismatchReturnsDefault() {
        when(itemMapper.selectOne(any())).thenReturn(item("rag.retrieval.topK", "8", "INTEGER"));
        assertThat(service.get("rag.retrieval.topK", String.class, "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("存储值非法（历史脏数据/手工改库）→ 回退默认，不抛异常")
    void invalidStoredValueReturnsDefault() {
        when(itemMapper.selectOne(any())).thenReturn(item("rag.retrieval.topK", "not-a-number", "INTEGER"));
        assertThat(service.get("rag.retrieval.topK", Integer.class, 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("意图类目/开关类读取返回类型化对象（List/Boolean）")
    void typedReadsForListsAndBooleans() {
        when(itemMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<?> w = inv.getArgument(0);
            w.getSqlSegment(); // 先渲染 SQL 段，参数占位才会填充进 paramNameValuePairs
            // 按参数值匹配（不依赖 MP 内部参数命名）：查询里唯一的 String 参数就是 config_key
            Object key = w.getParamNameValuePairs().values().stream()
                    .filter(String.class::isInstance)
                    .findFirst().orElse(null);
            if ("rag.intent.categories".equals(key)) {
                return item("rag.intent.categories", "[\"财务报销\",\"人事制度\"]", "STRING_LIST");
            }
            return item("rag.retrieval.strictKb", "true", "BOOLEAN");
        });
        List<String> categories = service.get("rag.intent.categories", List.class, List.of());
        assertThat(categories).containsExactly("财务报销", "人事制度");
        assertThat(service.get("rag.retrieval.strictKb", Boolean.class, false)).isTrue();
    }
}
