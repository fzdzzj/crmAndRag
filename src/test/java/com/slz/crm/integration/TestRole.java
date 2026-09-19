package com.slz.crm.integration;

/** 多角色回归测试角色枚举。 */
public enum TestRole {
  ADMIN(1L, 1L, "超级管理员"),
  DIRECTOR(10L, 2L, "总监"),
  MANAGER(11L, 3L, "销售经理"),
  SALES(12L, 4L, "销售员A"),
  SALES_B(13L, 4L, "销售员B"),
  NORMAL(75L, 3L, "普通用户");

  private final Long userId;
  private final Long roleId;
  private final String displayName;

  TestRole(Long userId, Long roleId, String displayName) {
    this.userId = userId;
    this.roleId = roleId;
    this.displayName = displayName;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getRoleId() {
    return roleId;
  }

  public String getDisplayName() {
    return displayName;
  }
}
