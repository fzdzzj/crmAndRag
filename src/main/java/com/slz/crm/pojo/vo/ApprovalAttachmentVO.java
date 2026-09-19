package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import java.time.LocalDateTime;
import lombok.Data;

/** 审批附件视图对象（通用附件VO，支持多种模块） */
@Data
public class ApprovalAttachmentVO implements Privacy {

  /** 附件ID */
  private Long id;

  /** 关联表ID（通用字段，用于关联不同模块的数据） */
  private Long andId;

  /** 关联模块名字（使用ModelName常量类的值） 例如：approval_attachment、business_activity */
  private String modelName;

  /** 文件名称（原始文件名） */
  private String fileName;

  /** 文件存储路径（服务器存储地址） */
  private String filePath;

  /** 文件大小（单位：字节） */
  private Long fileSize;

  /** 文件类型（如：image/png、application/pdf） */
  private String fileType;

  /** 上传时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime uploadTime;

  /** 文件数据 */
  private byte[] fileData;

  /** 下载URL（包含JWT令牌的公开下载链接） */
  private String downloadUrl;

  /** 上传人ID */
  private Long uploaderId;

  /** 上传人姓名 */
  private String uploaderName;
}
