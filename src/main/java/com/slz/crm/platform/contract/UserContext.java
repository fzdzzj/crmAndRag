package com.slz.crm.platform.contract;

import com.slz.crm.common.enumeration.DataScopeLevel;

/**
 * 当前登录用户身份快照（跨 lane 冻结契约）。
 *
 * <p>职责：把“登录态是谁、属于哪个部门、能看多大范围”统一为一个不可变值对象， 供数据权限（A）、知识库授权（B）、助手（C）、治理（D/E）共同消费，取代各模块各自 从
 * ThreadLocal 取 RoleAO 再自行解释的碎片做法。
 *
 * <p>不可变值对象 = 天然线程安全：异步旁路（意图/摘要、记忆加工、SSE 写出）会把上下文 带到别的线程，只有不可变快照才能避免“读到被改一半的身份”；跨线程传递用 {@link
 * UserContextHolder}。
 *
 * <p>口径：{@link #userId()} 为数据库主键（Long，MyBatis-Plus 直查）；{@link #userIdRef()} 为跨域引用 {@code
 * user:<id>}，与知识库/治理侧 {@code createdBy/ownerId} 列口径一致（统一 String(100)）。
 *
 * @param userId 用户主键，必须大于 0
 * @param roleId 所属角色主键，必须大于 0（超级管理员固定 roleId=1）
 * @param deptId 所属部门主键，必须大于 0；数据权限按此推导可见部门集合
 * @param dataScope 数据范围级别；{@link DataScopeLevel#NONE} 表示“普通操作权限”，不参与行级过滤
 * @param displayName 用于审计/日志展示的可读姓名，允许为空
 */
public record UserContext(
    Long userId, Long roleId, Long deptId, DataScopeLevel dataScope, String displayName) {

  /**
   * 构造并做契约自检。
   *
   * @throws IllegalArgumentException 当 userId/roleId/deptId 为空或小于等于 0 时抛出， 保证任何 lane
   *     拿到的上下文都可直接用于权限计算
   */
  public UserContext {
    if (userId == null || userId <= 0) {
      throw new IllegalArgumentException("UserContext.userId 必须为正数");
    }
    if (roleId == null || roleId <= 0) {
      throw new IllegalArgumentException("UserContext.roleId 必须为正数");
    }
    if (deptId == null || deptId <= 0) {
      throw new IllegalArgumentException("UserContext.deptId 必须为正数（数据权限依赖部门维度）");
    }
    if (dataScope == null) {
      dataScope = DataScopeLevel.NONE;
    }
  }

  /**
   * @return 跨域统一引用串 {@code user:<id>}，例：{@code user:10001}
   */
  public String userIdRef() {
    return "user:" + userId;
  }

  /**
   * @return 是否超级管理员（roleId=1）；治理侧的 Actuator 敏感端点、动态配置写入都用它判定
   */
  public boolean isSuperAdmin() {
    return roleId == 1L;
  }

  /**
   * @return 日志/审计用的紧凑串，例如 {@code user:10001(role:2,dept:11,scope:SELF)}
   */
  public String auditString() {
    return userIdRef() + "(role:" + roleId + ",dept:" + deptId + ",scope:" + dataScope + ")";
  }
}
