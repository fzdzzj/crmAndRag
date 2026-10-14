package com.slz.crm.server.ai.enums;

import com.slz.crm.common.enumeration.PermissionOperates;
import lombok.Getter;

/** AI 草稿操作类型枚举 扩展新操作类型只需在此枚举新增一项 + 新增对应 Validator/Executor Bean */
@Getter
public enum ActionTypeEnum {
  CREATE_ORDER("CREATE_ORDER", PermissionOperates.SALES_APPEND_ORDER),
  CREATE_CONTRACT("CREATE_CONTRACT", PermissionOperates.SALES_CREATE_CONTRACT),
  CREATE_CUSTOMER("CREATE_CUSTOMER", PermissionOperates.CUSTOMER_ADD_COMPANY),
  CREATE_CONTACT("CREATE_CONTACT", PermissionOperates.CUSTOMER_ADD_CONTACT),
  CREATE_OPPORTUNITY("CREATE_OPPORTUNITY", PermissionOperates.SALES_CREATE_SALE_OPPORTUNITY),
  CREATE_PAYMENT("CREATE_PAYMENT", PermissionOperates.FINANCE_RECORD_PAYMENT),
  CREATE_INVOICE("CREATE_INVOICE", PermissionOperates.FINANCE_RECORD_INVOICE),
  /** extend-ai-draft-and-eval 任务 1.1：跟进任务草稿（确认链 requirePermission 自动映射既有写权限） */
  CREATE_CONTACT_TASK("CREATE_CONTACT_TASK", PermissionOperates.TASK_CREATE_TASK),
  /** extend-ai-draft-and-eval 任务 2.1：业务活动草稿（确认链 requirePermission 自动映射既有写权限） */
  CREATE_BUSINESS_ACTIVITY(
      "CREATE_BUSINESS_ACTIVITY", PermissionOperates.SALES_CREATE_BUSINESS_ACTIVITY);

  private final String value;
  private final PermissionOperates requiredPermission;

  ActionTypeEnum(String value, PermissionOperates requiredPermission) {
    this.value = value;
    this.requiredPermission = requiredPermission;
  }

  /** 按字符串值查找枚举（不存在时返回 null） */
  public static ActionTypeEnum fromValue(String value) {
    ActionTypeEnum result = null;
    if (value != null) {
      for (ActionTypeEnum e : values()) {
        if (e.value.equals(value)) {
          result = e;
          break;
        }
      }
    }
    return result;
  }

  /** 按 actionType 字符串获取所需权限（不存在时返回 null） */
  public static PermissionOperates getRequiredPermission(String actionType) {
    ActionTypeEnum e = fromValue(actionType);
    return e != null ? e.requiredPermission : null;
  }
}
