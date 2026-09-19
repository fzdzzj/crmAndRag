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

/** AI 消息实体 */
@Data
@AutoTable
@Table(value = "ai_message", comment = "AI消息")
@TableName("ai_message")
@TableIndex(
    name = "idx_session",
    fields = {"sessionId", "id"})
public class AiMessageEntity {
  @TableId(type = IdType.AUTO)
  @Column(comment = "消息ID")
  private Long id;

  @Column(comment = "所属会话")
  private Long sessionId;

  @Column(comment = "角色 user/assistant/tool")
  private String role;

  @Column(comment = "消息类型 text/chart/actionCard/draftProgress/system")
  private String msgType;

  @Column(comment = "文本内容")
  private String content;

  @Column(comment = "附加结构化JSON")
  private String payload;

  @Column(comment = "token估算")
  private Integer tokenCount;

  @Column(comment = "创建时间")
  private LocalDateTime createdTime;
}
