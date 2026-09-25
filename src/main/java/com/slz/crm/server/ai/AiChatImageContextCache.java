package com.slz.crm.server.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 图片 L2 上下文缓存。
 *
 * <p>L1 的 OCR/摘要/实体在 {@code ai_chat_image} 持久化；这里只缓存 问题聚焦摘要和懒生成的图片向量，按 {@code sessionId + imageHash
 * + question} 命中。
 */
@Component
public class AiChatImageContextCache {

  /** put 发现内层 Map 被并发淘汰后的整次重试上限；仍失败则放弃本条写入，等下次 miss 重算。 */
  private static final int MAX_PUT_ATTEMPTS = 3;

  private final Map<Long, LinkedHashMap<String, Entry>> sessions = new ConcurrentHashMap<>();

  /** 会话访问序号：单调递增计数当访问时间用，时钟粒度并列时也能唯一判定最久未访问。 */
  private final Map<Long, Long> sessionAccessOrder = new ConcurrentHashMap<>();

  private final AtomicLong accessSequence = new AtomicLong();

  @Value("${crm.ai.image-context-ttl-seconds:1800}")
  private long ttlSeconds = 1800;

  @Value("${crm.ai.image-context-limit-per-session:8}")
  private int maxEntriesPerSession = 8;

  @Value("${crm.ai.image-context-max-sessions:256}")
  private int maxSessions = 256;

  /** L2 命中结果；{@code imageVector} 仅在 KB 开启且已生成时非空。 */
  public record CachedImageContext(String focusedSummary, float[] imageVector) {}

  public Optional<CachedImageContext> get(Long sessionId, String imageHash, String question) {
    Optional<CachedImageContext> result = Optional.empty();
    if (sessionId != null && imageHash != null && !imageHash.isBlank()) {
      LinkedHashMap<String, Entry> cache = sessions.get(sessionId);
      if (cache != null) {
        synchronized (cache) {
          // 锁内复核归属：Map 已被并发淘汰/重建时不 touch 也不读写条目，避免给已移除的会话续序号
          if (sessions.get(sessionId) == cache) {
            touch(sessionId);
            String cacheKey = cacheKey(question);
            Entry entry = cache.get(cacheKey);
            if (entry != null && !entry.isExpired(ttlSeconds)) {
              entry.touch();
              cache.remove(cacheKey);
              cache.put(cacheKey, entry);
              result =
                  Optional.of(
                      new CachedImageContext(entry.focusedSummary, clone(entry.imageVector)));
            } else if (entry != null) {
              cache.remove(cacheKey);
            }
          }
        }
      }
    }
    return result;
  }

  public void put(
      Long sessionId,
      String imageHash,
      String question,
      String focusedSummary,
      float[] imageVector) {
    if (sessionId != null && imageHash != null && !imageHash.isBlank()) {
      boolean written = false;
      for (int attempt = 0; attempt < MAX_PUT_ATTEMPTS && !written; attempt++) {
        LinkedHashMap<String, Entry> cache =
            sessions.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>());
        synchronized (cache) {
          // 锁内复核归属：仍是外层 Map 里的同一个实例才写过期清理、条目与 touch
          if (sessions.get(sessionId) == cache) {
            evictExpired(cache);
            String cacheKey = cacheKey(question);
            cache.remove(cacheKey);
            cache.put(
                cacheKey,
                new Entry(focusedSummary == null ? "" : focusedSummary.trim(), clone(imageVector)));
            while (cache.size() > maxEntriesPerSession) {
              Map.Entry<String, Entry> eldest = cache.entrySet().iterator().next();
              cache.remove(eldest.getKey());
            }
            touch(sessionId);
            written = true;
          }
        }
      }
      if (written) {
        cleanupSessions(sessionId);
      }
    }
  }

  public void evictSession(Long sessionId) {
    if (sessionId != null) {
      boolean done = false;
      for (int attempt = 0; attempt < 3 && !done; attempt++) {
        LinkedHashMap<String, Entry> cache = sessions.get(sessionId);
        if (cache == null) {
          sessionAccessOrder.remove(sessionId);
          done = true;
        } else {
          synchronized (cache) {
            if (sessions.get(sessionId) == cache) {
              sessions.remove(sessionId, cache);
              sessionAccessOrder.remove(sessionId);
            }
          }
          if (!sessions.containsKey(sessionId)) {
            done = true;
          }
        }
      }
      if (!done && sessions.containsKey(sessionId)) {
        // 3 轮都被并发 put 重建顶掉：退化为无条件移除，避免与高频写入互耗
        sessions.remove(sessionId);
        sessionAccessOrder.remove(sessionId);
      }
    }
  }

  /**
   * 清理其他会话并强制会话数上限。逐个短暂持有其他会话的内层锁，全过期判定、{@code sessions.remove(id, map)} 与删访问序号在同一把锁内完成，remove
   * 失败（会话已被并发重建）不动序号；当前会话的内层锁已在 put 写回后释放， 任意时刻至多持有一把内层锁，不存在跨会话锁序问题。
   */
  private void cleanupSessions(Long currentSessionId) {
    for (Long otherId : sessions.keySet()) {
      if (otherId.equals(currentSessionId)) {
        continue;
      }
      LinkedHashMap<String, Entry> otherCache = sessions.get(otherId);
      if (otherCache == null) {
        continue;
      }
      synchronized (otherCache) {
        boolean removable =
            otherCache.isEmpty()
                || otherCache.values().stream().allMatch(entry -> entry.isExpired(ttlSeconds));
        if (removable && sessions.remove(otherId, otherCache)) {
          sessionAccessOrder.remove(otherId);
        }
      }
    }
    evictSessionsBeyondLimit(currentSessionId);
  }

  /**
   * 会话数超限时按访问序号从旧到新淘汰。没有访问序号的会话视为最久未访问（{@code Long.MIN_VALUE}）； 牺牲者必须重新拿到自己的内层锁、复核序号仍是刚选中的值且 {@code
   * sessions.remove(id, 同一 Map)} 成功才删序号； 序号已变则换下一个候选，整轮扫描一个都没删掉立即停止，避免与并发访问空转。
   */
  private void evictSessionsBeyondLimit(Long currentSessionId) {
    while (sessions.size() > maxSessions) {
      if (!evictOneEldest(currentSessionId)) {
        return;
      }
    }
  }

  /** 一轮完整扫描：候选按选中的访问序号从旧到新逐个尝试，删掉一个即返回 true；全部候选复核失败返回 false。 */
  private boolean evictOneEldest(Long currentSessionId) {
    List<Map.Entry<Long, Long>> candidates = new ArrayList<>();
    for (Long otherId : sessions.keySet()) {
      if (!otherId.equals(currentSessionId)) {
        long access = sessionAccessOrder.getOrDefault(otherId, Long.MIN_VALUE);
        candidates.add(Map.entry(otherId, access));
      }
    }
    candidates.sort(Comparator.comparingLong(Map.Entry::getValue));
    boolean removed = false;
    for (int i = 0; i < candidates.size() && !removed; i++) {
      Map.Entry<Long, Long> candidate = candidates.get(i);
      Long victimId = candidate.getKey();
      LinkedHashMap<String, Entry> victimCache = sessions.get(victimId);
      if (victimCache != null) {
        synchronized (victimCache) {
          long currentAccess = sessionAccessOrder.getOrDefault(victimId, Long.MIN_VALUE);
          if (sessions.get(victimId) == victimCache
              && currentAccess == candidate.getValue()
              && sessions.remove(victimId, victimCache)) {
            sessionAccessOrder.remove(victimId);
            removed = true;
          }
        }
      }
    }
    return removed;
  }

  private void touch(Long sessionId) {
    sessionAccessOrder.put(sessionId, accessSequence.incrementAndGet());
  }

  private void evictExpired(LinkedHashMap<String, Entry> cache) {
    cache.entrySet().removeIf(entry -> entry.getValue().isExpired(ttlSeconds));
  }

  private String cacheKey(String question) {
    return question == null ? "" : question.strip();
  }

  private float[] clone(float[] vector) {
    return vector == null ? null : vector.clone();
  }

  private static final class Entry {
    private final String focusedSummary;
    private final float[] imageVector;
    private long touchedAtNanos;

    private Entry(String focusedSummary, float[] imageVector) {
      this.focusedSummary = focusedSummary;
      this.imageVector = imageVector;
      this.touchedAtNanos = System.nanoTime();
    }

    private boolean isExpired(long ttlSeconds) {
      return System.nanoTime() - touchedAtNanos >= ttlSeconds * 1_000_000_000L;
    }

    private void touch() {
      touchedAtNanos = System.nanoTime();
    }
  }
}
