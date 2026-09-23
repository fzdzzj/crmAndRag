package com.slz.crm.server.service;

import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import java.util.LinkedHashMap;
import java.util.Map;

/** 知识库管理 API 的实体与检索来源响应映射，保持编排服务只负责授权和调用顺序。 */
final class KnowledgeAdminResponseMapper {

  private KnowledgeAdminResponseMapper() {}

  static KnowledgeBaseVO toBaseVO(KnowledgeBaseEntity entity) {
    KnowledgeBaseVO result = new KnowledgeBaseVO();
    result.setId(entity.getId());
    result.setName(entity.getName());
    result.setDisplayName(entity.getDisplayName());
    result.setVisibility(entity.getVisibility() == null ? null : entity.getVisibility().name());
    result.setOwnerUserId(entity.getOwnerUserId());
    result.setCreateTime(entity.getCreateTime());
    return result;
  }

  static KnowledgeFileVO toFileVO(UploadedFileEntity entity) {
    KnowledgeFileVO result = new KnowledgeFileVO();
    result.setId(entity.getId());
    result.setDocumentId(entity.getDocumentId());
    result.setOriginalFilename(entity.getOriginalFilename());
    result.setFileType(entity.getFileType());
    result.setStatus(entity.getStatus());
    result.setSegmentCount(entity.getSegmentCount());
    result.setVectorCount(entity.getVectorCount());
    try {
      if (entity.getKnowledgeBase() != null) {
        result.setKnowledgeBaseId(Long.valueOf(entity.getKnowledgeBase()));
      }
    } catch (NumberFormatException ignored) {
      // 非数字知识库标识不应中断管理列表展示。
    }
    result.setCreateTime(entity.getCreateTime());
    result.setErrorMessage(entity.getErrorMessage());
    return result;
  }

  static KnowledgeAdminRetrievalResponse.Candidate toCandidate(RetrievalCandidate candidate) {
    VectorSearchHit hit = candidate.hit();
    Map<String, Object> metadata =
        hit.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(hit.metadata());
    KnowledgeAdminRetrievalResponse.Candidate result =
        new KnowledgeAdminRetrievalResponse.Candidate();
    result.setChunkId(hit.chunkId());
    result.setText(hit.text());
    result.setScore(candidate.rerankScore());
    result.setMetadata(metadata);
    result.setKnowledgeBaseId(toLong(metadata.get("knowledgeBaseId")));
    result.setFilename(toString(metadata.get("filename")));
    return result;
  }

  static KnowledgeAdminRetrievalResponse.Candidate toCandidate(SourceReference source) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("sourceType", source.sourceType());
    metadata.put("route", source.route());
    metadata.put("documentId", source.documentId());
    metadata.put("chunkIndex", source.chunkIndex());
    metadata.put("pageNo", source.pageNo());
    metadata.put("rowIndex", source.rowIndex());
    KnowledgeAdminRetrievalResponse.Candidate result =
        new KnowledgeAdminRetrievalResponse.Candidate();
    result.setChunkId(source.chunkId());
    result.setText(source.excerpt());
    result.setScore(source.relevanceScore());
    result.setMetadata(metadata);
    result.setFilename(source.filename());
    return result;
  }

  private static Long toLong(Object value) {
    Long result = null;
    if (value instanceof Number number) {
      result = number.longValue();
    } else if (value != null) {
      try {
        result = Long.valueOf(String.valueOf(value));
      } catch (NumberFormatException ignored) {
        // 非数字 metadata 不应中断检索结果展示。
      }
    }
    return result;
  }

  private static String toString(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
