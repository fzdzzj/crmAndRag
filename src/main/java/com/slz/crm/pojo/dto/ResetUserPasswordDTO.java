package com.slz.crm.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 管理员重置用户密码请求DTO */
@Data
public class ResetUserPasswordDTO {
  /** 目标用户ID */
  @NotNull(message = "目标用户ID不能为空")
  private Long userId;

  /** 新密码 */
  @NotBlank(message = "新密码不能为空")
  private String newPassword;
}
