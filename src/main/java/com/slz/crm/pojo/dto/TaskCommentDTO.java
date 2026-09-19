package com.slz.crm.pojo.dto;

import lombok.Data;

/** 任务评论数据传输对象 */
@Data
public class TaskCommentDTO {

  /** 评论ID */
  private Long id;

  /** 关联任务ID */
  private Long taskId;

  /** 评论内容 */
  private String content;
}
