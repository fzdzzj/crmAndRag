package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增跟进任务草稿 payload（actionType = CREATE_CONTACT_TASK） 客户双通道：companyId 或 companyName 至少其一（由
 * AiEntityResolver 解析） extend-ai-draft-and-eval 任务 1.2
 */
@Data
public class AiContactTaskDraftPayloadDTO {
  @AskQuestion("这笔跟进任务关联哪个客户公司？请提供公司编号或名称")
  private Long companyId;

  @AskQuestion("这笔跟进任务关联哪个客户公司？请提供公司名称")
  private String companyName;

  /** 关联联系人ID（选填） */
  private Long contactId;

  /** 关联销售机会ID（选填） */
  private Long opportunityId;

  @NotBlank
  @AskQuestion("跟进任务的主题是什么？")
  private String taskTitle;

  @AskQuestion("任务内容/要做的事是什么？")
  private String taskContent;

  /** 任务类型（选填） */
  private String taskType;

  /** 开始时间（格式 yyyy-MM-dd HH:mm:ss，选填） */
  private String startTime;

  /** 结束时间（格式 yyyy-MM-dd HH:mm:ss，选填） */
  private String endTime;

  /** 优先级（低/中/高/紧急，选填，缺省由业务层定为中） */
  private String priorityStr;

  /** 状态（未开始/进行中/已完成/已取消，选填，缺省未开始） */
  private String statusStr;

  /** 任务执行人ID（选填，缺省当前用户） */
  private Long assigneeId;
}
