package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

/** '任务评论表'; */
@Data
@AutoTable
@Table(value = "task_comment", comment = "任务评论表")
@TableName("task_comment")
public class TaskCommentEntity {
  /** 评论ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "评论ID")
  private Long id;

  /** 关联任务ID */
  @Column(comment = "关联任务ID", type = "bigint", notNull = true)
  private Long taskId;

  /** 评论内容 */
  @Column(comment = "评论内容", type = "text", notNull = true)
  private String content;

  /** 评论人ID */
  @Column(comment = "评论人ID", type = "bigint", notNull = true)
  private Long creatorId;

  /** 评论时间 */
  @Column(comment = "评论时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;
}
