package com.slz.crm.knowledge.document;

/**
 * 文档入库结果。
 *
 * @param uploadedFileId 数据库主键
 * @param documentId     文档业务键
 * @param chunkCount     文本分块数
 * @param vectorCount    向量数
 */
public record DocumentIngestionResult(
        Long uploadedFileId,
        String documentId,
        int chunkCount,
        int vectorCount) {
}
