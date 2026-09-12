-- ============================================================
-- V23__chunk_parent_link.sql —— 双粒度索引（Lane B，upgrade-semantic-chunking-and-index 任务 3.1）
-- 方案03 Small-to-Big：document_vector_chunk 增自引用父块列与切片角色列。
-- 语义切分（rag.chunking.strategy=semantic）按"逻辑段"生成父块行（chunk_role=PARENT），
-- 子块挂 parent_chunk_id；命中子块后 ContextBuilder 展开父块进上下文（生成单元），
-- SourceReference 仍指命中小块（检索单元，锚点精度不降）。
-- fixed 策略下不产生父块行、parent_chunk_id 恒空（行为不变）；存量行默认 CHILD，回退安全。
-- 稀疏召回与邻居增强只消费 CHILD 行，父块行不进入检索单元。
-- ============================================================

ALTER TABLE document_vector_chunk
    ADD COLUMN parent_chunk_id bigint NULL COMMENT '父块行ID（语义切分逻辑段聚合，自引用本表；fixed/存量行为 NULL）' AFTER row_index,
    ADD COLUMN chunk_role varchar(16) NOT NULL DEFAULT 'CHILD' COMMENT '切片角色 CHILD=检索单元 PARENT=生成单元（父块行）' AFTER parent_chunk_id;
