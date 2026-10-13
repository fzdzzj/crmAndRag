package com.slz.crm.knowledge.vector;

import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import java.util.List;

/**
 * 业务实体向量库（add-customer-semantic-retrieval 任务 1.3）。
 *
 * <p>crm_entity 集合的装配载体：底层持有独立的 {@link QdrantVectorStore}（派生 properties，
 * collection=crmEntityCollection）或 {@link InMemoryVectorStore} 实例，与 knowledge_chunk 集合
 * 零共享状态。为什么是包装类型而不是第二枚 CrmVectorStore bean：知识库四消费方按 {@code CrmVectorStore} 类型注入、健康面用 {@code
 * ObjectProvider<CrmVectorStoreHealth>.getIfAvailable()} 单候选假设——新增第二枚裸 bean
 * 会令候选集歧义炸裂启动；包装类型使业务侧按具体类型注入， 既有装配零改动。健康探针维持单实例口径（仅 knowledge_chunk），本类不实现 CrmVectorStoreHealth。
 */
public class CrmEntityVectorStore implements CrmVectorStore {

  private final CrmVectorStore backing;

  public CrmEntityVectorStore(CrmVectorStore backing) {
    this.backing = backing;
  }

  @Override
  public void upsert(VectorRecord record) {
    backing.upsert(record);
  }

  @Override
  public void upsertAll(List<VectorRecord> records) {
    backing.upsertAll(records);
  }

  @Override
  public List<VectorSearchHit> search(VectorSearchRequest request) {
    return backing.search(request);
  }

  @Override
  public void deleteByDocumentId(String documentId) {
    backing.deleteByDocumentId(documentId);
  }
}
