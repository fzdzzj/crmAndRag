package com.slz.crm.platform.contract;

import java.util.Map;

/**
 * 向量库写入单元（冻结契约）。
 *
 * @param id 向量主键（Qdrant point id），实现方负责唯一性
 * @param documentId 所属文档主键（按文档删除向量时依赖）
 * @param chunkId 切片主键（高亮/引用回查依赖，与 DB 一致）
 * @param chunkIndex 切片序号（0 起；与 {@code SourceReference.chunkIndex} 同口径）
 * @param text 原始切片文本（高亮需要）
 * @param embedding 向量值，维度必须与集合维度一致
 * @param metadata 业务元数据（pageNo/rowIndex/knowledgeBaseId/category…）；禁止塞二进制大字段
 */
public record VectorRecord(
    String id,
    String documentId,
    String chunkId,
    Integer chunkIndex,
    String text,
    float[] embedding,
    Map<String, Object> metadata) {

  /**
   * 构造自检：id/documentId/chunkId 必填，embedding 必须非空。
   *
   * @throws IllegalArgumentException 关键标识或向量为空时抛出
   */
  public VectorRecord {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("VectorRecord.id 不能为空");
    }
    if (documentId == null || documentId.isBlank()) {
      throw new IllegalArgumentException("VectorRecord.documentId 不能为空");
    }
    if (chunkId == null || chunkId.isBlank()) {
      throw new IllegalArgumentException("VectorRecord.chunkId 不能为空");
    }
    if (embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("VectorRecord.embedding 不能为空");
    }
  }
}
