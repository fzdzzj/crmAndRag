package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/** 联络任务DTO */
@Data
public class ContactTaskDTO {
  /** 任务ID */
  private Long id;

  /** 任务标题 */
  private String taskTitle;

  /** 关联客户公司ID */
  private Long companyId;

  /** 关联联系人ID */
  private Long contactId;

  /** 关联销售机会ID */
  private Long opportunityId;

  /** 任务类型 */
  private String taskType;

  /** 任务内容 */
  private String taskContent;

  /** 开始时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime startTime;

  /** 结束时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime endTime;

  /** 优先级（0-9低到高） */
  private Integer priority;

  /** 优先级字符串（低/中/高/紧急） 如果传入此字段，将自动转换为数字优先级 */
  private String priorityStr;

  /** 状态（0未开始/1进行中/2已完成/3已取消） */
  private Integer status;

  /** 状态字符串（未开始/进行中/已完成/已取消） 如果传入此字段，将自动转换为数字状态 */
  private String statusStr;

  /** 任务执行人ID */
  private Long assigneeId;

  /** 指派人ID（分配任务的人） */
  private Long assignerId;

  /** 协助申请明细（每位协助人各自的目的/要求）。 */
  private List<com.slz.crm.pojo.dto.AssistApplyItem> assistApplyList;
}
