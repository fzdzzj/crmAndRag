package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.SparseChunkRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 文档向量切片快照表 Mapper。 */
@Mapper
public interface DocumentVectorChunkMapper extends BaseMapper<DocumentVectorChunkEntity> {

    /**
     * 语料级全文检索（方案16补全，complete-hybrid-retrieval-and-rerank 任务 1.3）：
     * 用 V22 的 ngram FULLTEXT 索引对切片原文做 MATCH...AGAINST 自然语言模式整句查询，
     * 返回按相关度降序的稀疏候选行。
     *
     * <p>授权在此强制执行：经 JOIN uploaded_file（软删过滤）把切片归属收敛到
     * {@code kbIds} 授权集合内，调用方传什么集合就只可能出什么集合的切片——
     * 切片侧不存在可放大的 metadata。类目为可空窄化条件（空 = 不过滤）。</p>
     *
     * <p>双粒度过滤（提案4 任务 3.1）：只召回 CHILD 检索单元行——PARENT 父块行不嵌入，
     * 混入稀疏路会让同一内容以子/父两行挤占 topK（V23 迁移对存量行补默认 CHILD）。</p>
     *
     * @param query    整句查询（不预切词，交给 ngram）
     * @param kbIds    授权知识库 ID 字符串集合（非空）
     * @param category 类目过滤（null/空 = 不过滤）
     * @param limit    返回上限（&gt; 0）
     */
    @Select("""
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
    List<SparseChunkRow> fulltextSearch(@Param("query") String query,
                                        @Param("kbIds") List<String> kbIds,
                                        @Param("category") String category,
                                        @Param("limit") int limit);
}
