package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增联系人草稿 payload（actionType = CREATE_CONTACT） 客户双通道：companyId 或 companyName 至少其一（由
 * AiEntityResolver 解析）
 */
@Data
public class AiContactDraftPayloadDTO {
  @AskQuestion("这位联系人属于哪个客户公司？请提供公司编号或名称")
  private Long companyId;

  @AskQuestion("这位联系人属于哪个客户公司？请提供公司名称")
  private String companyName;

  @NotBlank
  @AskQuestion("联系人姓名是什么？")
  private String name;

  @AskQuestion("联系人的职位是什么？")
  private String position;

  private String dept;

  @AskQuestion("联系人电话是多少？")
  private String phone;

  @AskQuestion("联系人手机是多少？")
  private String mobile;

  private String email;

  /** 关系级别（1-5，选填） */
  private Integer relationLevel;
}
