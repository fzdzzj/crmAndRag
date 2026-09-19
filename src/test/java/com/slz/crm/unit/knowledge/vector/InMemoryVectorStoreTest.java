package com.slz.crm.unit.knowledge.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 内存向量库的过滤、排序与删除语义测试。 */
class InMemoryVectorStoreTest {
  private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();

  @Test
  void searchShouldRespectMetadataFilterAndScoreOrder() {
    vectorStore.upsert(
        record("chunk-1", new float[] {1f, 0f}, Map.of("knowledgeBaseId", "1", "category", "crm")));
    vectorStore.upsert(
        record(
            "chunk-2",
            new float[] {0.9f, 0.1f},
            Map.of("knowledgeBaseId", "1", "category", "invoice")));

    List<VectorSearchHit> all =
        vectorStore.search(
            new VectorSearchRequest(
                new float[] {1f, 0f}, 10, null, Map.of("knowledgeBaseId", "1")));
    assertEquals(2, all.size());
    assertTrue(all.get(0).score() >= all.get(1).score());

    List<VectorSearchHit> filtered =
        vectorStore.search(
            new VectorSearchRequest(
                new float[] {1f, 0f}, 10, 0.2, Map.of("knowledgeBaseId", "1", "category", "crm")));
    assertEquals(1, filtered.size());
    assertEquals("chunk-1", filtered.getFirst().chunkId());
  }

  @Test
  void deleteByDocumentIdShouldRemoveAllChunks() {
    vectorStore.upsert(record("chunk-1", new float[] {1f, 0f}, Map.of("knowledgeBaseId", "1")));
    vectorStore.upsert(record("chunk-2", new float[] {1f, 0f}, Map.of("knowledgeBaseId", "1")));
    vectorStore.deleteByDocumentId("doc-1");

    assertTrue(
        vectorStore
            .search(new VectorSearchRequest(new float[] {1f, 0f}, 10, null, Map.of()))
            .isEmpty());
  }

  private VectorRecord record(String chunkId, float[] embedding, Map<String, Object> metadata) {
    return new VectorRecord(
        "point-" + chunkId,
        "doc-1",
        chunkId,
        Integer.parseInt(chunkId.substring(chunkId.length() - 1)),
        "text",
        embedding,
        metadata);
  }
}
