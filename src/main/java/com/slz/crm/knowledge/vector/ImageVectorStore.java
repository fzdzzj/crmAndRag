package com.slz.crm.knowledge.vector;

import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import java.util.List;

/**
 * 图片视觉向量库（multimodal-shared-space 任务 2.1）。
 *
 * <p>{@code knowledge_image} 集合的装配载体：底层持有独立的 {@link QdrantVectorStore}（派生 properties，{@code
 * collection=imageCollection}，{@code dimensions=imageDimensions}）或 {@link InMemoryVectorStore} 实例，
 * 与 {@code knowledge_chunk}（文本空间）零共享状态。
 *
 * <p>为什么是包装类型而不是第二枚 {@code CrmVectorStore} bean：知识库四消费方按 {@code CrmVectorStore} 类型注入、健康面用 {@code
 * ObjectProvider<CrmVectorStoreHealth>} 单候选假设——新增第二枚裸 bean 会让候选集歧义炸裂启动；包装类型让视觉路按具体类型 （{@link
 * ImageVectorStore}）注入，既有装配零改动。健康探针维持单实例口径（仅 knowledge_chunk），本类不实现 {@link
 * com.slz.crm.platform.contract.CrmVectorStoreHealth}。
 *
 * <p>维度语义：{@link VectorRecord#embedding()} 长度必须等于 {@code
 * knowledge.qdrant.image-dimensions}；与文本维度可不等， 故检索侧绝不可把文本查询向量送进本库。
 */
public class ImageVectorStore implements CrmVectorStore {

  private final CrmVectorStore backing;

  public ImageVectorStore(CrmVectorStore backing) {
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
