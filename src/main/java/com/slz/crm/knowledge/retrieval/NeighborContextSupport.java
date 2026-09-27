package com.slz.crm.knowledge.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 邻居上下文拼装支持类（tighten-pmd-residual-325 任务 6.3 自 ContextBuilder 拆出，行为等价）。 对每个命中块按 {@code documentId +
 * 相邻 chunkIndex} 从 {@code document_vector_chunk} 快照表取前/后邻居（chunkIndex 为全文档切片序号、0 起，故 ±1
 * 即紧邻；同一序号多行按「同页优先 + 主键小者」确定性取舍），拼装为「[n]（前文承接）邻居 / 命中块 / （后文承接）邻居」。
 *
 * <p>快照批读（update-context-snapshot-batch-read）：多命中的邻居行按 {@code document_id IN + chunk_index IN +
 * chunk_role=CHILD} 有界批次一次取回，批查失败时仅受影响批次按旧路径逐命中回查一次后按原语义降级，
 * 不做无上限重试；行级过滤（同文档、目标序号、同页优先）与逐命中查询完全一致，输出逐字等价。
 */
final class NeighborContextSupport {
  private static final Logger LOG = LoggerFactory.getLogger(NeighborContextSupport.class);

  /** 快照表只把 CHILD 行当邻居候选（PARENT 父块行占独立 chunk_index 空间，不作邻居）。 */
  static final String CHUNK_ROLE_CHILD = "CHILD";

  /**
   * 单批安全参数上限（update-context-snapshot-batch-read）：IN 集合按命中数分批， 不生成无界 IN 参数；超限候选拆为多个有限批次，任何命中都不省略。
   */
  static final int SNAPSHOT_BATCH_LIMIT = 500;

  private static final String PREV_LABEL = "（前文承接）";
  private static final String NEXT_LABEL = "（后文承接）";

  private NeighborContextSupport() {}

  static String assembleWithNeighbors(
      List<RetrievalCandidate> candidates, DocumentVectorChunkMapper chunkMapper) {
    List<VectorSearchHit> hits = hits(candidates);
    List<DocumentVectorChunkEntity> rows = fetchNeighborsBatched(hits, chunkMapper);
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        builder.append('\n');
      }
      builder.append('[').append(index + 1).append("] ");
      appendWithNeighbors(builder, hits.get(index), rows);
    }
    return builder.toString();
  }

  /**
   * 命中块 + 前/后邻居拼装（邻居行来自批次预取）；邻居缺失（首末块/跨文档/批查降级）时静默跳过对应一侧。
   *
   * <p>无效索引守卫（fix-neighbor-missing-chunk-index-fallback）：metadata 的 chunkIndex 缺失、非 Number 或为负数时，
   * 只按原文本输出该命中（不拆箱空值、不为负索引取邻居），与 {@link #collectNeighborTargets} 的跳过规则一致； 有效索引 0/正数的邻居拼装不变。
   */
  static void appendWithNeighbors(
      StringBuilder builder, VectorSearchHit hit, List<DocumentVectorChunkEntity> rows) {
    String hitText = hit.text() == null ? "" : hit.text().strip();
    Integer hitChunkIndex = chunkIndex(hit);
    if (hitChunkIndex == null || hitChunkIndex < 0) {
      builder.append(hitText);
      return;
    }
    DocumentVectorChunkEntity prev = neighborAt(rows, hitChunkIndex - 1, hit);
    DocumentVectorChunkEntity next = neighborAt(rows, hitChunkIndex + 1, hit);
    if (prev != null && !prev.getChunkText().isBlank()) {
      builder.append(PREV_LABEL).append(prev.getChunkText().strip()).append('\n');
    }
    builder.append(hitText);
    if (next != null && !next.getChunkText().isBlank()) {
      builder.append('\n').append(NEXT_LABEL).append(next.getChunkText().strip());
    }
  }

  /**
   * 多命中的邻居行批读（update-context-snapshot-batch-read）：一次查询取回本批全部 {@code (document_id, chunk_index±1)} 的
   * CHILD 行，行内多余行由 {@link #neighborAt} 按命中逐个过滤。 查询按文档分组为 OR 条件组（{@code chunk_role=? AND
   * (document_id=? AND chunk_index IN (…)) OR …}）， 返回行数 = 精确 (文档, 序号) 配对 ≲ 2×命中数，不随文档数×序号数叉积放大。
   * 批次大小受 {@link #SNAPSHOT_BATCH_LIMIT} 约束；批查失败时仅受影响批次按旧路径逐命中回查一次（原异常语义），仍失败按无邻居降级。
   */
  static List<DocumentVectorChunkEntity> fetchNeighborsBatched(
      List<VectorSearchHit> hits, DocumentVectorChunkMapper chunkMapper) {
    List<DocumentVectorChunkEntity> rows = new ArrayList<>();
    for (int start = 0; start < hits.size(); start += SNAPSHOT_BATCH_LIMIT) {
      rows.addAll(
          fetchNeighborBatch(
              hits.subList(start, Math.min(hits.size(), start + SNAPSHOT_BATCH_LIMIT)),
              chunkMapper));
    }
    return rows;
  }

  /**
   * 单批邻居行查询：按文档分组 OR 条件组，返回行 ≈ 各文档目标序号并集之和（有界），参数数 ≲ 3×命中数； 批查失败时对受影响批次按旧路径逐命中回查一次（fetchNeighbors
   * 原异常语义），不做无上限重试。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // ORM查询边界：chunkMapper批查多源，失败按无邻居降级
  private static List<DocumentVectorChunkEntity> fetchNeighborBatch(
      List<VectorSearchHit> batch, DocumentVectorChunkMapper chunkMapper) {
    Map<String, Set<Integer>> targetsByDocument = new LinkedHashMap<>();
    for (VectorSearchHit hit : batch) {
      collectNeighborTargets(hit, targetsByDocument);
    }
    List<DocumentVectorChunkEntity> result = new ArrayList<>();
    if (!targetsByDocument.isEmpty()) {
      QueryWrapper<DocumentVectorChunkEntity> wrapper =
          new QueryWrapper<DocumentVectorChunkEntity>().eq("chunk_role", CHUNK_ROLE_CHILD);
      wrapper.and(
          group -> {
            for (Map.Entry<String, Set<Integer>> entry : targetsByDocument.entrySet()) {
              group.or(
                  inner ->
                      inner.eq("document_id", entry.getKey()).in("chunk_index", entry.getValue()));
            }
          });
      try {
        result.addAll(chunkMapper.selectList(wrapper));
      } catch (Exception exception) {
        LOG.warn("邻居切片批查失败，受影响命中按旧路径逐条回查: {}", exception.getMessage());
        for (VectorSearchHit hit : batch) {
          result.addAll(fetchNeighbors(hit, chunkMapper));
        }
      }
    }
    return result;
  }

  /** 收集单个命中的邻居查询目标到其文档分组（同文档 + 前后序号）；chunkIndex 缺失或 documentId 空白的命中不产生目标。 */
  private static void collectNeighborTargets(
      VectorSearchHit hit, Map<String, Set<Integer>> targetsByDocument) {
    Integer chunkIndex = chunkIndex(hit);
    if (chunkIndex != null
        && chunkIndex >= 0
        && hit.documentId() != null
        && !hit.documentId().isBlank()) {
      Set<Integer> indexes =
          targetsByDocument.computeIfAbsent(hit.documentId(), key -> new LinkedHashSet<>());
      if (chunkIndex > 0) {
        indexes.add(chunkIndex - 1);
      }
      indexes.add(chunkIndex + 1);
    }
  }

  /**
   * 查询单个命中块的潜在邻居行（chunkIndex-1 与 chunkIndex+1，同文档）；仅批查失败后的有界回查复用。 chunkIndex
   * 缺失或查询失败时返回空列表（无邻居降级，不抛错）。
   */
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

  /** 候选列表 → 命中列表（按原顺序）。 */
  static List<VectorSearchHit> hits(List<RetrievalCandidate> candidates) {
    List<VectorSearchHit> hits = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      hits.add(candidate.hit());
    }
    return hits;
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
