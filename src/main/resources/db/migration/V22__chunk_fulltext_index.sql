-- ============================================================
-- V22__chunk_fulltext_index.sql —— 语料级稀疏召回路载体（Lane B，complete-hybrid-retrieval-and-rerank 任务 1.1）
-- 方案16 Hybrid Search 补全：为 document_vector_chunk.chunk_text 建 ngram 全文索引，
-- MATCH...AGAINST 供 SparseRecallService 做词法预筛（DB 是切片唯一真相源，索引随写入自动维护）。
-- ngram（bigram）适配中文零分词场景；只加索引不改数据、不删数据，回退安全。
-- ============================================================

ALTER TABLE document_vector_chunk
    ADD FULLTEXT INDEX ft_chunk_text (chunk_text) WITH PARSER ngram;
