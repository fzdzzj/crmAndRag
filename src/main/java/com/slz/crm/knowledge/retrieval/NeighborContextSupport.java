package com.slz.crm.knowledge.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 邻居上下文拼装支持类（tighten-pmd-residual-325 任务 6.3 自 ContextBuilder 拆出，行为等价）。 对每个命中块按 {@code documentId +
 * 相邻 chunkIndex} 从 {@code document_vector_chunk} 快照表取前/后邻居（chunkIndex 为全文档切片序号、0 起，故 ±1
 * 即紧邻；同一序号多行按「同页优先 + 主键小者」确定性取舍），拼装为「[n]（前文承接）邻居 / 命中块 / （后文承接）邻居」。
 */
final class NeighborContextSupport {
  private static final Logger LOG = LoggerFactory.getLogger(NeighborContextSupport.class);

  /** 快照表只把 CHILD 行当邻居候选（PARENT 父块行占独立 chunk_index 空间，不作邻居）。 */
  static final String CHUNK_ROLE_CHILD = "CHILD";

  private static final String PREV_LABEL = "（前文承接）";
  private static final String NEXT_LABEL = "（后文承接）";

  private NeighborContextSupport() {}

  static String assembleWithNeighbors(
      List<RetrievalCandidate> candidates, DocumentVectorChunkMapper chunkMapper) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        builder.append('\n');
      }
      VectorSearchHit hit = candidates.get(index).hit();
      builder.append('[').append(index + 1).append("] ");
      appendWithNeighbors(builder, hit, chunkMapper);
    }
    return builder.toString();
  }

  /** 命中块 + 前/后邻居拼装；邻居缺失（首末块/跨文档/快照表不可用）时静默跳过对应一侧。 */
  static void appendWithNeighbors(
      StringBuilder builder, VectorSearchHit hit, DocumentVectorChunkMapper chunkMapper) {
    String hitText = hit.text() == null ? "" : hit.text().strip();
    List<DocumentVectorChunkEntity> neighbors = fetchNeighbors(hit, chunkMapper);
    Integer hitChunkIndex = chunkIndex(hit);
    DocumentVectorChunkEntity prev = neighborAt(neighbors, hitChunkIndex - 1, hit);
    DocumentVectorChunkEntity next = neighborAt(neighbors, hitChunkIndex + 1, hit);
    if (prev != null && !prev.getChunkText().isBlank()) {
      builder.append(PREV_LABEL).append(prev.getChunkText().strip()).append('\n');
    }
    builder.append(hitText);
    if (next != null && !next.getChunkText().isBlank()) {
      builder.append('\n').append(NEXT_LABEL).append(next.getChunkText().strip());
    }
  }

  /** 查询命中块的潜在邻居行（chunkIndex-1 与 chunkIndex+1，同文档）。 chunkIndex 缺失或查询失败时返回空列表（无邻居降级，不抛错）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // ORM查询边界：chunkMapper批查多源，失败按无邻居降级
  private static List<DocumentVectorChunkEntity> fetchNeighbors(
      VectorSearchHit hit, DocumentVectorChunkMapper chunkMapper) {
    Integer chunkIndex = chunkIndex(hit);
    List<DocumentVectorChunkEntity> result = List.of();
    if (chunkIndex != null
        && chunkIndex >= 0
        && hit.documentId() != null
        && !hit.documentId().isBlank()) {
      List<Integer> targets = new ArrayList<>();
      if (chunkIndex > 0) {
        targets.add(chunkIndex - 1);
      }
      targets.add(chunkIndex + 1);
      try {
        result =
            chunkMapper.selectList(
                new QueryWrapper<DocumentVectorChunkEntity>()
                    .eq("document_id", hit.documentId())
                    .eq("chunk_role", CHUNK_ROLE_CHILD)
                    .in("chunk_index", targets));
      } catch (Exception exception) {
        LOG.warn("邻居切片查询失败，按无邻居降级: {}", exception.getMessage());
      }
    }
    return result;
  }

  /** 取目标序号的邻居行：SQL 已按 document_id 收敛，此处再过滤跨文档行兜底； 同序号多行按「同页优先、主键小者」确定性取舍（同页优先，任务 1.1）。 */
  private static DocumentVectorChunkEntity neighborAt(
      List<DocumentVectorChunkEntity> rows, int targetIndex, VectorSearchHit hit) {
    Integer hitPageNo = positivePageNo(hit);
    DocumentVectorChunkEntity best = null;
    if (targetIndex >= 0) {
      for (DocumentVectorChunkEntity row : rows) {
        if (row.getChunkIndex() == null
            || row.getChunkIndex() != targetIndex
            || row.getChunkText() == null
            || row.getDocumentId() == null
            || !row.getDocumentId().equals(hit.documentId())) {
          continue;
        }
        if (best == null || betterNeighbor(row, best, hitPageNo)) {
          best = row;
        }
      }
    }
    return best;
  }

  /** 同页优先；同页（或同跨页）时取主键小者，保证同输入同输出。 */
  private static boolean betterNeighbor(
      DocumentVectorChunkEntity candidate, DocumentVectorChunkEntity current, Integer hitPageNo) {
    boolean candidateSamePage =
        candidate.getPageNo() != null && candidate.getPageNo().equals(hitPageNo);
    boolean currentSamePage = current.getPageNo() != null && current.getPageNo().equals(hitPageNo);
    boolean result;
    if (candidateSamePage != currentSamePage) {
      result = candidateSamePage;
    } else {
      result =
          candidate.getId() != null
              && current.getId() != null
              && candidate.getId() < current.getId();
    }
    return result;
  }

  private static Integer chunkIndex(VectorSearchHit hit) {
    Object value = hit.metadata().get("chunkIndex");
    return value instanceof Number number ? number.intValue() : null;
  }

  private static Integer positivePageNo(VectorSearchHit hit) {
    Object value = hit.metadata().get("pageNo");
    Integer result = null;
    if (value instanceof Number number) {
      long pageNo = number.longValue();
      if (pageNo > 0) {
        result = (int) pageNo;
      }
    }
    return result;
  }
}
