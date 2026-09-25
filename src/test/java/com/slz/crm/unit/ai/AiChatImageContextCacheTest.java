package com.slz.crm.unit.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.server.ai.AiChatImageContextCache;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 图片 L2 缓存：hash+问题隔离、每会话 LRU 与 TTL 过期、会话级释放与会话数上限。 */
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
    cache.put(9L, "hash1", "发票金额是多少", "发票 focused", new float[] {0.1F});

    assertThat(cache.get(9L, "hash1", "发票金额是多少"))
        .hasValueSatisfying(
            context -> {
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

  @Test
  void put_removesFullyExpiredOrEmptySessionsFromOuterMap() {
    cache.put(9L, "hash1", "问题", "9", null);
    cache.put(10L, "hash1", "问题", "10", null);
    ReflectionTestUtils.setField(cache, "ttlSeconds", 0L);
    // 会话 10 的最后一条经 get 过期删除，留下空内层 Map；会话 9 的条目全部过期
    cache.get(10L, "hash1", "问题");
    cache.put(11L, "hash1", "问题", "11", null);
    ReflectionTestUtils.setField(cache, "ttlSeconds", 1800L);

    assertThat(outerSessions()).doesNotContainKey(9L).doesNotContainKey(10L).containsKey(11L);
    assertThat(cache.get(11L, "hash1", "问题")).isPresent();
  }

  @Test
  void put_evictsLeastRecentlyAccessedSessionBeyondSessionLimit() {
    ReflectionTestUtils.setField(cache, "maxSessions", 2);
    cache.put(9L, "hash1", "问题1", "1", null);
    cache.put(10L, "hash2", "问题2", "2", null);
    cache.get(9L, "hash1", "问题1");
    cache.put(11L, "hash3", "问题3", "3", null);

    assertThat(outerSessions()).containsKey(9L).containsKey(11L).doesNotContainKey(10L);
    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
    assertThat(cache.get(10L, "hash2", "问题2")).isEmpty();
    assertThat(cache.get(11L, "hash3", "问题3")).isPresent();
  }

  @Test
  void put_keepsUnexpiredSessionsWithinLimitWithSummaryAndVectorCopy() {
    ReflectionTestUtils.setField(cache, "maxSessions", 2);
    cache.put(9L, "hash1", "问题1", "摘要9", new float[] {0.1F, 0.2F});
    cache.put(10L, "hash2", "问题2", "摘要10", null);
    cache.get(9L, "hash1", "问题1");
    cache.put(11L, "hash3", "问题3", "摘要11", null);

    float[] returned = cache.get(9L, "hash1", "问题1").orElseThrow().imageVector();
    returned[0] = 9.9F;

    assertThat(cache.get(9L, "hash1", "问题1"))
        .hasValueSatisfying(
            context -> {
              assertThat(context.focusedSummary()).isEqualTo("摘要9");
              assertThat(context.imageVector()).containsExactly(0.1F, 0.2F);
            });
    assertThat(cache.get(10L, "hash2", "问题2")).isEmpty();
  }

  @Test
  void put_doesNotEvictSessionJustWrittenWhenAlreadyAtLimit() {
    ReflectionTestUtils.setField(cache, "maxSessions", 1);
    cache.put(9L, "hash1", "问题1", "1", null);

    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();

    cache.put(10L, "hash2", "问题2", "2", null);

    assertThat(cache.get(9L, "hash1", "问题1")).isEmpty();
    assertThat(cache.get(10L, "hash2", "问题2")).isPresent();
  }

  @SuppressWarnings("unchecked")
  private Map<Long, Object> outerSessions() {
    return (Map<Long, Object>) ReflectionTestUtils.getField(cache, "sessions");
  }
}
