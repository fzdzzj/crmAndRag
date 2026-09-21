package com.slz.crm.common.annotation;

import com.slz.crm.common.enumeration.PermissionOperates;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限验证注解
 *
 * <p>使用统一的权限枚举常量作为注解值，实现编译时类型安全检查。 取值覆盖客户/销售/任务/系统/财务/报表各管理域，完整清单见 {@link PermissionOperates}。
 *
 * <p>示例：
 *
 * <pre>{@code
 * @RequirePermission(PermissionOperates.EXCEL_ADD)      // 客户管理
 * @RequirePermission(PermissionOperates.CREATE_TASK)    // 任务管理
 * @RequirePermission(PermissionOperates.RECORD_PAYMENT) // 财务管理
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
  /** 权限枚举常量 */
  PermissionOperates value();
}
