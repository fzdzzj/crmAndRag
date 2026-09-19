package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增客户公司草稿 payload（actionType = CREATE_CUSTOMER） belongGroup 为文本字段（三态：用户提及直接传 / 不确定时 LLM 先调
 * queryCompanyGroup / 未提及不传），不做存在性校验
 */
@Data
public class AiCustomerDraftPayloadDTO {
  @NotBlank
  @AskQuestion("客户公司名称是什么？")
  private String companyName;

  @AskQuestion("客户属于什么行业？")
  private String industry;

  @AskQuestion("客户类型是什么？（如：潜在客户/成交客户）")
  private String customerType;

  /** 所属集团（选填，文本透传） */
  @AskQuestion("客户所属哪个集团？（可不填，或从集团列表中选择）")
  private String belongGroup;

  @AskQuestion("客户属于哪个部门？")
  private String dept;

  @AskQuestion("客户地址在哪里？")
  private String address;

  @AskQuestion("客户联系电话是多少？")
  private String phone;

  private String website;

  @AskQuestion("客户等级是什么？（如：A/B/C）")
  private Integer grade;
}
