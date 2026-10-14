package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增业务活动草稿 payload（actionType = CREATE_BUSINESS_ACTIVITY） 商机双通道：opportunityId 或 opportunityName
 * 至少其一（由 AiEntityResolver 解析） extend-ai-draft-and-eval 任务 2.2
 */
@Data
public class AiBusinessActivityDraftPayloadDTO {
  @AskQuestion("这笔业务活动挂在哪个商机下？请提供商机编号或名称")
  private Long opportunityId;

  @AskQuestion("这笔业务活动挂在哪个商机下？请提供商机名称")
  private String opportunityName;

  @NotBlank
  @AskQuestion("业务活动的标题是什么？")
  private String activityTitle;

  @AskQuestion("业务活动的内容是什么？")
  private String activityContent;

  /** 活动时间（格式 yyyy-MM-dd HH:mm:ss，选填，缺省当天） */
  private String activityTime;

  @AskQuestion("业务活动类型是什么？")
  private String activityType;

  /** 活动持续时间（分钟，选填） */
  private Integer activityDuration;

  private String remark;
}
