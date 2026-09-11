package com.slz.crm.platform.config;

/**
 * 动态配置操作人（写操作/审计的身份来源）。
 *
 * @param ref         跨域引用 user:&lt;id&gt;
 * @param userId      用户主键
 * @param roleId      角色主键（超级管理员固定 roleId=1）
 * @param displayName 可读姓名（可能为空）
 */
public record ConfigOperator(String ref, Long userId, Long roleId, String displayName) {

    /**
     * @return 是否超级管理员（roleId=1，对齐 {@code UserContext.isSuperAdmin()} 口径）
     */
    public boolean isSuperAdmin() {
        return roleId != null && roleId == 1L;
    }
}
