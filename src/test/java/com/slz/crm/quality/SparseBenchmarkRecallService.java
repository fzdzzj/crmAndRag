package com.slz.crm.quality;

import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.platform.contract.VectorSearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 真检索基准的测试侧稀疏路（run-baseline-ladder 任务组 2，决策 0.1 落地）。
 *
 * <p>背景：生产 {@link SparseRecallService} 的稀疏候选 chunkId 是 MySQL {@code document_vector_chunk} 的 Long
 * 主键；而基准向量库（InMemoryVectorStore）的 chunkId 是 {@code <语料key>-<chunkIndex>} 字符串。两路 fusionKey 命名空间不一致时
 * RRF 融合与 recall 判分无法对齐。故本类以 {@code SparseRecallService} 的<b>子类</b> override {@link #recall}（public
 * 非 final，无需改 src/main），用同一批 fixture 切片的字符 bigram 重叠分近似 FULLTEXT(ngram)，并复制向量库的 chunkId，使两路可融合可判分。
 *
 * <p>口径偏差（与决策 0.1 同源）：近似 ngram 评分、无授权 JOIN/软删（单一授权 KB 无软删场景）；真库 ngram 路径 的 DB 级正确性由 {@code
 * SparseRecallServiceIT}/{@code FlywayMigrationIT} 独立背书。本类只在基准测试用。
 */
final class SparseBenchmarkRecallService extends SparseRecallService {

  private final List<RagBenchmarkDataPreparer.BenchmarkChunk> chunks;

  SparseBenchmarkRecallService(List<RagBenchmarkDataPreparer.BenchmarkChunk> chunks) {
    super(null); // 不触 DB，recall 全由本类内存实现
    this.chunks = chunks;
  }

  @Override
  public List<RetrievalCandidate> recall(
      String query, List<Long> authorizedKbIds, String category, int limit) {
    if (query == null || query.isBlank() || limit <= 0) {
      return List.of();
    }
    Set<String> queryGrams = bigrams(query);
    if (queryGrams.isEmpty()) {
      return List.of();
    }
    List<RetrievalCandidate> hits = new ArrayList<>();
    for (RagBenchmarkDataPreparer.BenchmarkChunk chunk : chunks) {
      if (category != null && !category.isBlank() && !category.equals(chunk.category())) {
        continue;
      }
      Set<String> chunkGrams = bigrams(chunk.text());
      double overlap = 0;
      for (String gram : queryGrams) {
        if (chunkGrams.contains(gram)) {
          overlap++;
        }
      }
      if (overlap <= 0) {
        continue;
      }
      double score = overlap / queryGrams.size();
      hits.add(toCandidate(chunk, score));
    }
    hits.sort((a, b) -> Double.compare(b.rerankScore(), a.rerankScore()));
    return hits.size() > limit ? hits.subList(0, limit) : hits;
  }

  private RetrievalCandidate toCandidate(
      RagBenchmarkDataPreparer.BenchmarkChunk chunk, double score) {
    String key = chunk.chunkId().substring(0, chunk.chunkId().lastIndexOf('-'));
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", "99001");
    metadata.put("category", chunk.category());
    metadata.put("filename", chunk.filename());
    metadata.put("fileType", fileType(chunk.filename()));
    metadata.put("chunkIndex", chunk.chunkIndex());
    metadata.put("pageNo", chunk.pageNo() == null ? 0L : chunk.pageNo().longValue());
    metadata.put("rowIndex", chunk.rowIndex() == null ? 0L : chunk.rowIndex().longValue());
    metadata.put("chunkId", chunk.chunkId());
    VectorSearchHit hit =
        new VectorSearchHit(chunk.chunkId(), "benchdoc-" + key, score, chunk.text(), metadata);
    return new RetrievalCandidate(hit, score);
  }

  private static String fileType(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "txt" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** 字符 bigram 集（小写，过滤空白；与稀疏近似/邻居索引共用算法口径）。 */
  private static Set<String> bigrams(String text) {
    StringBuilder compact = new StringBuilder();
    if (text != null) {
      for (int index = 0; index < text.length(); index++) {
        char ch = text.charAt(index);
        if (Character.isLetterOrDigit(ch)) {
          compact.append(Character.toLowerCase(ch));
        }
      }
    }
    Set<String> grams = new java.util.HashSet<>();
    for (int index = 0; index < compact.length() - 1; index++) {
      grams.add(compact.substring(index, index + 2));
    }
    return grams;
  }
}
