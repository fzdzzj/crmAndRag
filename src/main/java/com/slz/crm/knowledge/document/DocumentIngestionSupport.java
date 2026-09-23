package com.slz.crm.knowledge.document;

import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 文档入库装配支持类：切片落库/父块分组/向量记录构建与文件实体装配，纯静态、无状态。 */
final class DocumentIngestionSupport {

  /** 切片角色（V23 chunk_role）：CHILD=检索单元，PARENT=生成单元父块行。 */
  static final String CHUNK_ROLE_CHILD = "CHILD";

  static final String CHUNK_ROLE_PARENT = "PARENT";

  private DocumentIngestionSupport() {}

  /** 切片落库 + 嵌入 + 向量写库的公共尾段（ingest 新文档与 reingest 重建共用）。 返回已落库子块行（即向量记录与计数口径；父块行不嵌入）。 */
  static List<DocumentVectorChunkEntity> chunkEmbedAndWrite(
      UploadedFileEntity file,
      List<DocumentChunk> chunks,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      DocumentVectorChunkMapper chunkMapper,
      DocumentService documentService) {
    vectorStore.deleteByDocumentId(file.getDocumentId());
    List<DocumentVectorChunkEntity> children =
        persistChunks(file, chunks, chunkMapper, documentService);
    List<VectorRecord> vectorRecords = new ArrayList<>(children.size());
    for (DocumentVectorChunkEntity entity : children) {
      // 块头只进嵌入输入（方案05）：文件名/类目/页级锚点给碎片块全局视野；
      // DB chunk_text 与 VectorRecord.text 保持原文，引用展示不受前缀污染（任务 2.1）
      String embedText =
          ChunkHeaderText.wrap(
              file.getOriginalFilename(),
              entity.getCategory(),
              entity.getPageNo(),
              entity.getRowIndex(),
              entity.getChunkText());
      float[] embedding = embeddingService.embed(embedText);
      vectorRecords.add(createVectorRecord(file, entity, embedding));
    }
    vectorStore.upsertAll(vectorRecords);
    return children;
  }

  /**
   * 切片落库（提案4 任务 3.2，双粒度索引）：子块行 + 语义切分产生的父块行。
   *
   * <p>父块分组规则：相邻且 parentText 逐字相同、页锚点一致的连续子块属同一逻辑段； 段被切成 ≥2
   * 个子块时才落一行父块（chunk_role=PARENT，chunk_index 从子块总数+1 起编号、 与子块序号空间隔离），子块挂 parent_chunk_id。恰好 1
   * 个子块的逻辑段自身即父块， 不落父块行、parent_chunk_id 保持空（fixed 策略全部子块如此，行为与升级前一致）。
   *
   * <p>父块行不嵌入、不产向量记录——父块是生成单元不是检索单元（稀疏召回与邻居 增强只消费 CHILD 行）。
   *
   * @return 已落库的子块行（按 chunkIndex 升序，即向量写库与计数的口径）
   */
  private static List<DocumentVectorChunkEntity> persistChunks(
      UploadedFileEntity file,
      List<DocumentChunk> chunks,
      DocumentVectorChunkMapper chunkMapper,
      DocumentService documentService) {
    List<DocumentVectorChunkEntity> children = new ArrayList<>(chunks.size());
    int index = 0;
    int parentOrdinal = 0;
    while (index < chunks.size()) {
      int runEnd = groupRunEnd(chunks, index);
      DocumentVectorChunkEntity parent = null;
      if (runEnd - index >= 2) {
        parent = createParentEntity(file, chunks.get(index), documentService);
        parent.setChunkIndex(chunks.size() + 1 + parentOrdinal);
        parentOrdinal++;
        chunkMapper.insert(parent);
      }
      for (int i = index; i < runEnd; i++) {
        DocumentVectorChunkEntity entity = createChunkEntity(file, chunks.get(i));
        if (parent != null) {
          entity.setParentChunkId(parent.getId());
        }
        chunkMapper.insert(entity);
        children.add(entity);
      }
      index = runEnd;
    }
    return children;
  }

  /** 从 index 起的同一逻辑段连续区段：parentText 非空逐字相同且页锚点一致才延续。 */
  private static int groupRunEnd(List<DocumentChunk> chunks, int index) {
    DocumentChunk first = chunks.get(index);
    int end = index + 1;
    if (first.parentText() != null) {
      while (end < chunks.size()) {
        DocumentChunk next = chunks.get(end);
        if (next.parentText() == null
            || !next.parentText().equals(first.parentText())
            || !Objects.equals(next.pageNo(), first.pageNo())
            || !Objects.equals(next.rowIndex(), first.rowIndex())) {
          break;
        }
        end++;
      }
    }
    return end;
  }

  /** 父块行：逻辑段全文；锚点/类目取段内子块口径（同段同页），关键词从段全文提取。 */
  private static DocumentVectorChunkEntity createParentEntity(
      UploadedFileEntity file, DocumentChunk first, DocumentService documentService) {
    String parentText = first.parentText();
    DocumentVectorChunkEntity parent = new DocumentVectorChunkEntity();
    parent.setDocumentId(file.getDocumentId());
    parent.setChunkText(parentText);
    parent.setChunkHash(sha256(parentText));
    parent.setFilename(file.getOriginalFilename());
    parent.setCategory(first.category());
    parent.setKeywords(String.join(",", documentService.extractKeywords(parentText)));
    parent.setPageNo(first.pageNo());
    parent.setRowIndex(first.rowIndex());
    parent.setChunkRole(CHUNK_ROLE_PARENT);
    return parent;
  }

  static DocumentVectorChunkEntity createChunkEntity(UploadedFileEntity file, DocumentChunk chunk) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setDocumentId(file.getDocumentId());
    entity.setChunkIndex(chunk.chunkIndex());
    entity.setChunkText(chunk.text());
    entity.setChunkHash(sha256(chunk.text()));
    entity.setFilename(file.getOriginalFilename());
    entity.setCategory(chunk.category());
    entity.setKeywords(String.join(",", chunk.keywords()));
    entity.setPageNo(chunk.pageNo());
    entity.setRowIndex(chunk.rowIndex());
    entity.setChunkRole(CHUNK_ROLE_CHILD);
    return entity;
  }

  /** point id 使用 UUID；chunkId 使用 DB 主键，保证前端引用与 DB 可回查。 */
  private static VectorRecord createVectorRecord(
      UploadedFileEntity file, DocumentVectorChunkEntity entity, float[] embedding) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", file.getKnowledgeBase());
    metadata.put("category", entity.getCategory() == null ? "" : entity.getCategory());
    metadata.put("filename", file.getOriginalFilename());
    metadata.put("fileType", file.getFileType());
    metadata.put("pageNo", entity.getPageNo() == null ? 0L : entity.getPageNo().longValue());
    metadata.put("rowIndex", entity.getRowIndex() == null ? 0L : entity.getRowIndex().longValue());
    metadata.put("chunkId", String.valueOf(entity.getId()));
    return new VectorRecord(
        UUID.randomUUID().toString(),
        entity.getDocumentId(),
        String.valueOf(entity.getId()),
        entity.getChunkIndex(),
        entity.getChunkText(),
        embedding,
        metadata);
  }

  static UploadedFileEntity createUploadedFile(
      DocumentIngestionCommand command, String documentId, String storageKey, String fileHash) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setUserId(command.user().userIdRef());
    file.setFilename(command.filename());
    file.setOriginalFilename(command.filename());
    file.setFileType(fileType(command.filename()));
    file.setDocumentId(documentId);
    file.setStorageKey(storageKey);
    file.setFileSize(command.fileSize());
    file.setContentType(command.contentType());
    file.setSegmentCount(0);
    file.setVectorCount(0);
    file.setStatus("PROCESSING");
    file.setFileHash(fileHash);
    file.setBatchTaskId(command.batchTaskId());
    file.setKnowledgeBase(String.valueOf(command.knowledgeBaseId()));
    return file;
  }

  static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("计算切片哈希失败", exception);
    }
  }

  static String fileType(String filename) {
    String result = "file";
    if (filename != null && filename.lastIndexOf('.') >= 0) {
      result = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
    }
    return result;
  }

  static String shortMessage(Exception exception) {
    String message = exception.getMessage();
    return message == null
        ? exception.getClass().getSimpleName()
        : message.substring(0, Math.min(900, message.length()));
  }
}
