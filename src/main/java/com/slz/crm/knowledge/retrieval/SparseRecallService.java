package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 语料级稀疏召回路（方案16补全，complete-hybrid-retrieval-and-rerank 任务 1.3）。
 *
 * <p>载体：MySQL FULLTEXT（ngram parser）索引 {@code document_vector_chunk.chunk_text} （V22 迁移，选项 A），整句
 * {@code MATCH...AGAINST} 预筛出「词法精确命中但向量可能漏召」 的切片（型号、代码、专有名词场景），与向量召回互补后进入融合。
 *
 * <p>授权语义：入参 {@code authorizedKbIds} 即唯一授权口径，SQL JOIN {@code uploaded_file.knowledge_base}
 * 强制收敛——切片侧伪造 metadata 不能放大授权集合。
 *
 * <p>失败边界：稀疏路是增量召回，DB/全文能力不可用（如 H2 测试上下文、迁移未到 V22）时 按空列表降级并告警，不拖垮整条检索链；向量路不受影响。
 */
@Service
public class SparseRecallService {
  private static final Logger LOG = LoggerFactory.getLogger(SparseRecallService.class);

  private final DocumentVectorChunkMapper chunkMapper;

  public SparseRecallService(DocumentVectorChunkMapper chunkMapper) {
    this.chunkMapper = chunkMapper;
  }

  /**
   * 整句全文检索，返回按相关度降序的稀疏候选。
   *
   * @param query 整句查询（不预切词，交给 ngram）
   * @param authorizedKbIds 授权知识库集合（空 = 无授权，返回空）
   * @param category 类目过滤（null/空 = 不过滤；语义与向量路一致，只窄化不放大）
   * @param limit 候选上限
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // ORM全文检索+流多源，失败按空结果降级
  public List<RetrievalCandidate> recall(
      String query, List<Long> authorizedKbIds, String category, int limit) {
    List<RetrievalCandidate> result = List.of();
    if (query != null
        && !query.isBlank()
        && authorizedKbIds != null
        && !authorizedKbIds.isEmpty()
        && limit > 0) {
      List<String> kbIds = authorizedKbIds.stream().map(String::valueOf).toList();
      try {
        result =
            chunkMapper.fulltextSearch(query.strip(), kbIds, blankToNull(category), limit).stream()
                .map(SparseRecallService::toCandidate)
                .toList();
      } catch (Exception exception) {
        LOG.warn("稀疏召回落空，按空结果降级: {}", exception.getMessage());
      }
    }
    return result;
  }

  private static RetrievalCandidate toCandidate(SparseChunkRow row) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", row.getKnowledgeBaseId());
    metadata.put("category", row.getCategory() == null ? "" : row.getCategory());
    metadata.put("filename", row.getFilename() == null ? "" : row.getFilename());
    metadata.put("chunkIndex", row.getChunkIndex());
    metadata.put("pageNo", row.getPageNo() == null ? 0L : row.getPageNo().longValue());
    metadata.put("rowIndex", row.getRowIndex() == null ? 0L : row.getRowIndex().longValue());
    metadata.put("chunkId", String.valueOf(row.getChunkId()));
    VectorSearchHit hit =
        new VectorSearchHit(
            String.valueOf(row.getChunkId()),
            row.getDocumentId(),
            row.getScore() == null ? 0.0d : row.getScore(),
            row.getChunkText(),
            metadata);
    return new RetrievalCandidate(hit, hit.score());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
