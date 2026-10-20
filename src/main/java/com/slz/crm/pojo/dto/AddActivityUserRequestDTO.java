package com.slz.crm.pojo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 添加用户到活动请求DTO */
@Data
public class AddActivityUserRequestDTO {

  /** 用户ID（增删关联的身份字段：为空时旧实现静默跳过、让调用方误以为已生效） */
  @NotNull(message = "用户ID不能为空")
  private Long userId;

  /** 用户在活动中的角色（如：主持人/主讲人/记录人/陪同人），可选 */
  private String userRole;
}
