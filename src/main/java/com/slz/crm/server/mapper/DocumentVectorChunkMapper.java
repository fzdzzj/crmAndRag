package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.SparseChunkRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 文档向量切片快照表 Mapper。 */
@Mapper
public interface DocumentVectorChunkMapper extends BaseMapper<DocumentVectorChunkEntity> {

  /**
   * 受限批次写入切片快照行（update-document-chunk-write-batching）。
   *
   * <p>以单条多行 {@code INSERT ... VALUES (...),(...)} 代替逐条写入，用 MyBatis {@code keyProperty}
   * 按行序把自增主键回填到传入实体（与 {@code ContractOrderItemMapper#insertBatch} 同一机制）：一行数据行的物理写入形状从「一行一次往返/提交」收敛为
   * 「一批一次」，但仍保持每行一行记录、列语义与原逐条插入等价（未列出的时间列/软删列走数据库默认值）。
   *
   * <p>批次大小由调用方按固定上限切分（{@code
   * DocumentIngestionSupport.PERSIST_BATCH_SIZE}），本方法不接收无界集合；主键逐行回填的可用性由一次性真库 可行性闸 {@code
   * ChunkBatchInsertGeneratedKeyIT} 验证，回填不完整由调用方按生成键缺失失败处理（不静默继续）。
   *
   * @param rows 待写入切片行（非空；调用方保证有界批次）
   * @return 实际写入行数
   */
  @Insert(
      """
            <script>
            INSERT INTO document_vector_chunk
              (document_id, chunk_index, chunk_text, chunk_hash, filename, category, keywords,
               page_no, row_index, parent_chunk_id, chunk_role)
            VALUES
              <foreach collection="list" item="row" separator=",">
                (#{row.documentId}, #{row.chunkIndex}, #{row.chunkText}, #{row.chunkHash},
                 #{row.filename}, #{row.category}, #{row.keywords}, #{row.pageNo}, #{row.rowIndex},
                 #{row.parentChunkId}, #{row.chunkRole})
              </foreach>
            </script>
            """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertBatch(List<DocumentVectorChunkEntity> rows);

  /**
   * 物理删除文档全部切片行（提案4 任务 4.1，reingest 幂等前置）。
   *
   * <p>{@link BaseMapper#delete} 走 {@code @TableLogic} 软删，软删行仍占用 {@code uk(document_id,
   * chunk_index)}，同 documentId 重建插入会撞唯一键—— 重建与失败清理必须物理删，保证"不留半量、可重试"。
   */
  @Delete("DELETE FROM document_vector_chunk WHERE document_id = #{documentId}")
  int deletePhysicallyByDocumentId(@Param("documentId") String documentId);

  /**
   * 语料级全文检索（方案16补全，complete-hybrid-retrieval-and-rerank 任务 1.3）： 用 V22 的 ngram FULLTEXT 索引对切片原文做
   * MATCH...AGAINST 自然语言模式整句查询， 返回按相关度降序的稀疏候选行。
   *
   * <p>授权在此强制执行：经 JOIN uploaded_file（软删过滤）把切片归属收敛到 {@code kbIds} 授权集合内，调用方传什么集合就只可能出什么集合的切片——
   * 切片侧不存在可放大的 metadata。类目为可空窄化条件（空 = 不过滤）。
   *
   * <p>双粒度过滤（提案4 任务 3.1）：只召回 CHILD 检索单元行——PARENT 父块行不嵌入， 混入稀疏路会让同一内容以子/父两行挤占 topK（V23 迁移对存量行补默认
   * CHILD）。
   *
   * @param query 整句查询（不预切词，交给 ngram）
   * @param kbIds 授权知识库 ID 字符串集合（非空）
   * @param category 类目过滤（null/空 = 不过滤）
   * @param limit 返回上限（&gt; 0）
   */
  @Select(
      """
            <script>
            SELECT c.id AS chunkId, c.document_id AS documentId, c.chunk_index AS chunkIndex,
                   c.chunk_text AS chunkText, c.filename AS filename, c.category AS category,
                   c.page_no AS pageNo, c.row_index AS rowIndex, f.knowledge_base AS knowledgeBaseId,
                   MATCH(c.chunk_text) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM document_vector_chunk c
            JOIN uploaded_file f ON f.document_id = c.document_id AND f.is_deleted = 0
            WHERE c.is_deleted = 0
              AND c.chunk_role = 'CHILD'
              AND MATCH(c.chunk_text) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
              AND f.knowledge_base IN
              <foreach collection="kbIds" item="kbId" open="(" separator="," close=")">#{kbId}</foreach>
              <if test="category != null and category != ''">
                  AND c.category = #{category}
              </if>
            ORDER BY score DESC
            LIMIT #{limit}
            </script>
            """)
  List<SparseChunkRow> fulltextSearch(
      @Param("query") String query,
      @Param("kbIds") List<String> kbIds,
      @Param("category") String category,
      @Param("limit") int limit);
}
