package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/** 知识库文档项 VO（admin）。 */
@Data
public class KnowledgeFileVO {
  private Long id;
  private String documentId;
  private String originalFilename;
  private String fileType;
  private String status;
  private Integer segmentCount;
  private Integer vectorCount;
  private Long knowledgeBaseId;
  private LocalDateTime createTime;
  private String errorMessage;
}
