package com.slz.crm.pojo.dto;

import lombok.Data;

/** 管理员重置用户密码请求DTO */
@Data
public class ResetUserPasswordDTO {
  /** 目标用户ID */
  private Long userId;

  /** 新密码 */
  private String newPassword;
}
