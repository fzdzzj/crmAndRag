package com.slz.crm.pojo.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/** 分片上传DTO */
@Data
public class ChunkUploadDTO {
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

  /** 当前分片索引（从0开始） */
  private Integer chunkIndex;

  /** 当前分片大小 */
  private Long chunkSize;

  /** 分片文件数据 */
  private MultipartFile chunkFile;
}
