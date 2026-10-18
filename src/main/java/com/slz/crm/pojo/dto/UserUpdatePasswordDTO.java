package com.slz.crm.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 自服务修改密码请求DTO（harden-user-password-and-privacy 任务 1.1） */
@Data
public class UserUpdatePasswordDTO {

  /** 原密码（Base64 加密传输） */
  @NotBlank(message = "原密码不能为空")
  private String oldPassword;

  /** 新密码（Base64 加密传输） */
  @NotBlank(message = "新密码不能为空")
  private String newPassword;
}
