package com.slz.crm.pojo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 添加联系人到活动请求DTO */
@Data
public class AddActivityContactRequestDTO {

  /** 联系人ID（增删关联的身份字段：为空时旧实现静默跳过、让调用方误以为已生效） */
  @NotNull(message = "联系人ID不能为空")
  private Long contactId;

  /** 联系人在活动中的角色（如：决策者/技术对接人/参会人），可选 */
  private String contactRole;
}
