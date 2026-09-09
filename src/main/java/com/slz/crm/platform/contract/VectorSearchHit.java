package com.slz.crm.platform.contract;

import java.util.Map;

/**
 * 向量检索命中（冻结契约）。
 *
 * @param chunkId    切片主键（回查 DB / 高亮锚点）
 * @param documentId 文档主键
 * @param score      相似度得分（实现方统一折算到 0~1，越大越相关）
 * @param text       切片文本（用于直接生成引用摘录，避免二次查询）
 * @param metadata   业务元数据（pageNo/rowIndex/knowledgeBaseId…）
 */
public record VectorSearchHit(
        String chunkId,
        String documentId,
        double score,
        String text,
        Map<String, Object> metadata) {
}
