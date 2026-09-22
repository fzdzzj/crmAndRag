package com.slz.crm.server.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

  private final Map<Long, LinkedHashMap<String, Entry>> sessions = new ConcurrentHashMap<>();

  @Value("${crm.ai.image-context-ttl-seconds:1800}")
  private long ttlSeconds = 1800;

  @Value("${crm.ai.image-context-limit-per-session:8}")
  private int maxEntriesPerSession = 8;

  /** L2 命中结果；{@code imageVector} 仅在 KB 开启且已生成时非空。 */
  public record CachedImageContext(String focusedSummary, float[] imageVector) {}

  public Optional<CachedImageContext> get(Long sessionId, String imageHash, String question) {
    Optional<CachedImageContext> result = Optional.empty();
    if (sessionId != null && imageHash != null && !imageHash.isBlank()) {
      LinkedHashMap<String, Entry> cache = sessions.get(sessionId);
      if (cache != null) {
        String cacheKey = cacheKey(question);
        synchronized (cache) {
          Entry entry = cache.get(cacheKey);
          if (entry != null && !entry.isExpired(ttlSeconds)) {
            entry.touch();
            cache.remove(cacheKey);
            cache.put(cacheKey, entry);
            result =
                Optional.of(new CachedImageContext(entry.focusedSummary, clone(entry.imageVector)));
          } else if (entry != null) {
            cache.remove(cacheKey);
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
    if (sessionId == null || imageHash == null || imageHash.isBlank()) {
      return;
    }
    LinkedHashMap<String, Entry> cache =
        sessions.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>());
    synchronized (cache) {
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
    }
  }

  public void evictSession(Long sessionId) {
    if (sessionId != null) {
      sessions.remove(sessionId);
    }
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
