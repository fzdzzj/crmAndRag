package com.slz.crm.pojo.dto;

import lombok.Data;

/** 知识库检索 dry-run 请求（admin）。 默认稀疏（useVector=false），零外呼 embedding。 */
@Data
public class KnowledgeAdminRetrievalRequest {
  /** 知识库ID（可选，null 则按用户可见全部） */
  private Long kbId;

  /** 查询文本 */
  private String query;

  /** 返回 topK，默认 5 */
  private Integer topK = 5;

  /** 是否走真向量（默认 false 走稀疏/BM25，授权节点才 true） */
  private Boolean useVector = false;
}
