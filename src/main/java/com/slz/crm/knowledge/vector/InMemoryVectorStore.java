package com.slz.crm.knowledge.vector;

import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.CrmVectorStoreHealth;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存向量库实现，仅用于开发或测试回退，生产 MUST 使用 Qdrant。 */
public class InMemoryVectorStore implements CrmVectorStore, CrmVectorStoreHealth {
  private final ConcurrentMap<String, VectorRecord> records = new ConcurrentHashMap<>();

  @Override
  public void upsert(VectorRecord record) {
    records.put(record.id(), record);
  }

  @Override
  public void upsertAll(List<VectorRecord> vectorRecords) {
    if (vectorRecords == null || vectorRecords.isEmpty()) {
      return;
    }
    vectorRecords.forEach(this::upsert);
  }

  @Override
  public List<VectorSearchHit> search(VectorSearchRequest request) {
    List<VectorSearchHit> hits = new ArrayList<>();
    for (VectorRecord record : records.values()) {
      if (!matchesFilter(record.metadata(), request.filter())) {
        continue;
      }
      double score = cosine(request.queryVector(), record.embedding());
      if (request.minScore() != null && score < request.minScore()) {
        continue;
      }
      hits.add(
          new VectorSearchHit(
              record.chunkId(),
              record.documentId(),
              score,
              record.text(),
              Map.copyOf(record.metadata())));
    }
    hits.sort(Comparator.comparingDouble(VectorSearchHit::score).reversed());
    return hits.size() > request.topK() ? hits.subList(0, request.topK()) : hits;
  }

  @Override
  public void deleteByDocumentId(String documentId) {
    records.values().removeIf(record -> Objects.equals(record.documentId(), documentId));
  }

  @Override
  public String componentName() {
    return "vectorStoreInMemory";
  }

  @Override
  public boolean inMemoryFallback() {
    return true;
  }

  @Override
  public String collectionName() {
    return "in-memory-knowledge_chunk";
  }

  @Override
  public boolean probe() {
    return true;
  }

  /** metadata 语义与 Qdrant 一致：等值匹配，Boolean 视为 1/0。 */
  private boolean matchesFilter(Map<String, Object> metadata, Map<String, Object> filter) {
    boolean matches = true;
    for (Map.Entry<String, Object> entry : filter.entrySet()) {
      Object actual = metadata.get(entry.getKey());
      Object expected =
          entry.getValue() instanceof Boolean bool ? (bool ? 1L : 0L) : entry.getValue();
      if (!Objects.equals(actual, expected)) {
        matches = false;
        break;
      }
    }
    return matches;
  }

  /** 余弦相似度统一折算 0~1，零向量返回 0。 */
  private double cosine(float[] left, float[] right) {
    if (left.length != right.length) {
      throw new IllegalArgumentException("向量维度不一致: " + left.length + " vs " + right.length);
    }
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int index = 0; index < left.length; index++) {
      dot += (double) left[index] * right[index];
      leftNorm += (double) left[index] * left[index];
      rightNorm += (double) right[index] * right[index];
    }
    double result = 0;
    if (leftNorm != 0 && rightNorm != 0) {
      result = Math.max(0, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }
    return result;
  }
}
