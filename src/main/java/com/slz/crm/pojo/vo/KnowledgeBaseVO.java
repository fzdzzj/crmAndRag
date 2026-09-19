package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/** 知识库列表项 VO（admin）。 */
@Data
public class KnowledgeBaseVO {
  private Long id;
  private String name;
  private String displayName;
  private String visibility;
  private String ownerUserId;
  private LocalDateTime createTime;
}
