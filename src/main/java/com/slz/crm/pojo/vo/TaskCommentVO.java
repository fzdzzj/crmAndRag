package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.entity.TaskCommentEntity;
import java.time.LocalDateTime;
import lombok.Data;

/** 任务评论视图对象 */
@Data
public class TaskCommentVO {

  /** 评论ID */
  private Long id;

  /** 关联任务ID */
  private Long taskId;

  /** 评论内容 */
  private String content;

  /** 评论人ID */
  private Long creatorId;

  /** 评论人姓名 */
  private String creatorName;

  /** 评论时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  /**
   * 从Entity创建VO，需要传入创建人名称
   *
   * @param entity 任务评论实体
   * @param creatorName 创建人姓名
   * @return TaskCommentVO
   */
  public static TaskCommentVO fromEntity(TaskCommentEntity entity, String creatorName) {
    TaskCommentVO vo = null;
    if (entity != null) {
      vo = new TaskCommentVO();
      vo.setId(entity.getId());
      vo.setTaskId(entity.getTaskId());
      vo.setContent(entity.getContent());
      vo.setCreatorId(entity.getCreatorId());
      vo.setCreatorName(creatorName);
      vo.setCreateTime(entity.getCreateTime());
    }
    return vo;
  }
}
