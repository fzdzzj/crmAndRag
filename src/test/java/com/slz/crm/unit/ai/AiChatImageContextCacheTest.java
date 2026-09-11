package com.slz.crm.unit.ai;

import com.slz.crm.server.ai.AiChatImageContextCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 图片 L2 缓存：hash+问题隔离、每会话 LRU 与 TTL 过期。 */
class AiChatImageContextCacheTest {

    private AiChatImageContextCache cache;

    @BeforeEach
    void setUp() {
        cache = new AiChatImageContextCache();
        ReflectionTestUtils.setField(cache, "ttlSeconds", 1800L);
        ReflectionTestUtils.setField(cache, "maxEntriesPerSession", 2);
    }

    @Test
    void cache_isolatesQuestionAndSession() {
        cache.put(9L, "hash1", "发票金额是多少", "发票 focused", new float[]{0.1F});

        assertThat(cache.get(9L, "hash1", "发票金额是多少"))
                .hasValueSatisfying(context -> {
                    assertThat(context.focusedSummary()).isEqualTo("发票 focused");
                    assertThat(context.imageVector()).containsExactly(0.1F);
                });
        assertThat(cache.get(9L, "hash1", "另一个问题")).isEmpty();
        assertThat(cache.get(8L, "hash1", "发票金额是多少")).isEmpty();
    }

    @Test
    void cache_evictsLeastRecentlyUsedPerSession() {
        cache.put(9L, "hash1", "问题1", "1", null);
        cache.put(9L, "hash2", "问题2", "2", null);
        cache.get(9L, "hash1", "问题1");
        cache.put(9L, "hash3", "问题3", "3", null);

        assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
        assertThat(cache.get(9L, "hash2", "问题2")).isEmpty();
        assertThat(cache.get(9L, "hash3", "问题3")).isPresent();
    }

    @Test
    void cache_expiresByTtl() {
        ReflectionTestUtils.setField(cache, "ttlSeconds", 0L);
        cache.put(9L, "hash1", "问题", "focused", null);

        assertThat(cache.get(9L, "hash1", "问题")).isEmpty();
    }
}
