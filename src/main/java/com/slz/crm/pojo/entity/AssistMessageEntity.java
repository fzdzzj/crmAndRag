package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

/** 协助申请过程消息。 */
@Data
@AutoTable
@Table(value = "assist_message", comment = "协助申请过程消息")
@TableName("assist_message")
@TableIndex(
    name = "idx_assist_message_time",
    fields = {"assistId", "createTime"})
public class AssistMessageEntity {

  @TableId(type = IdType.AUTO)
  @Column(comment = "消息ID")
  private Long id;

  @Column(comment = "协助记录ID", type = "bigint", notNull = true)
  private Long assistId;

  @Column(comment = "发送人ID；系统消息为空", type = "bigint")
  private Long senderId;

  @Column(comment = "消息内容", type = "text", notNull = true)
  private String content;

  @Column(comment = "消息类型（TEXT/SYSTEM）", type = "varchar(16)", notNull = true)
  private String messageType;

  @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;
}
