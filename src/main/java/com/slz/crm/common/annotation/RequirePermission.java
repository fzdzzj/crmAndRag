package com.slz.crm.common.annotation;

import com.slz.crm.common.enumeration.PermissionOperates;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限验证注解
 * <p>使用统一的权限枚举常量作为注解值，实现编译时类型安全检查</p>
 * <p>示例：</p>
 * <pre>{@code
 * // 客户管理权限
 * @RequirePermission(PermissionOperates.EXCEL_ADD)
 * @RequirePermission(PermissionOperates.ADD_COMPANY)
 *
 * // 销售管理权限
 * @RequirePermission(PermissionOperates.CREATE_SALE_OPPORTUNITY)
 * @RequirePermission(PermissionOperates.CREATE_CONTRACT)
 *
 * // 任务管理权限
 * @RequirePermission(PermissionOperates.CREATE_TASK)
 * @RequirePermission(PermissionOperates.UPDATE_TASK)
 *
 * // 系统管理权限
 * @RequirePermission(PermissionOperates.CREATE_USER)
 * @RequirePermission(PermissionOperates.CREATE_ROLE)
 *
 * // 财务管理权限
 * @RequirePermission(PermissionOperates.RECORD_PAYMENT)
 * @RequirePermission(PermissionOperates.EDIT_PAYMENT)
 *
 * // 报表管理权限
 * @RequirePermission(PermissionOperates.VIEW_PRESET_REPORT)
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /**
     * 权限枚举常量
     */
    PermissionOperates value();
}
