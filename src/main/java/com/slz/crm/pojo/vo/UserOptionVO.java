package com.slz.crm.pojo.vo;

import lombok.Data;

/** 用户选择项 VO（协助人选择器等轻量场景，仅暴露最少字段，不含手机/邮箱） */
@Data
public class UserOptionVO {
  /** 用户ID */
  private Long id;

  /** 姓名 */
  private String realName;

  /** 部门ID */
  private Long deptId;

  /** 部门名称 */
  private String deptName;
}
