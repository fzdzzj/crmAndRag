package com.slz.crm.pojo.dto;

import lombok.Data;

/** 分片合并DTO（通用分片合并，支持多种模块） */
@Data
public class ChunkMergeDTO {
  /** 文件唯一标识（MD5或UUID） */
  private String fileIdentifier;

  /** 文件名 */
  private String fileName;

  /** 文件类型 */
  private String fileType;

  /** 文件总大小 */
  private Long totalSize;

  /** 分片总数 */
  private Integer totalChunks;

  /** 关联表ID（通用字段，用于关联不同模块的数据） */
  private Long andId;

  /** 关联模块名称（使用ModelName常量类的值） 例如：ModelName.APPROVAL_ATTACHMENT 或 ModelName.BUSINESS_ACTIVITY */
  private String modelName;
}
