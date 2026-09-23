package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.PermissionOperates;
import java.util.Map;

/**
 * 表名到数据权限枚举映射的静态持有类（tighten-pmd-residual-325 任务 6.4 批C： 拆自 {@link
 * DataScopeServiceImpl}，映射内容一字未改，行为等价）。
 *
 * <p>key: 表名, value: [ONLY_MY权限, TAGE权限, ALL权限, DEPT权限, DEPT_AND_CHILD权限]， 下标语义见本类 PERMISSION_* 常量。
 */
final class DataScopeTablePermissions {

  private DataScopeTablePermissions() {}

  /** TABLE_PERMISSIONS 中“仅本人”权限的下标。 */
  static final int PERMISSION_SELF = 0;

  /** TABLE_PERMISSIONS 中“标签”权限的下标。 */
  static final int PERMISSION_TAGE = 1;

  /** TABLE_PERMISSIONS 中“全部”权限的下标。 */
  static final int PERMISSION_ALL = 2;

  /** TABLE_PERMISSIONS 中“本部门”权限的下标。 */
  static final int PERMISSION_DEPT = 3;

  /** TABLE_PERMISSIONS 中“本部门及以下”权限的下标。 */
  static final int PERMISSION_DEPT_AND_CHILD = 4;

  private static final Map<String, PermissionOperates[]> TABLE_PERMISSIONS =
      Map.of(
          "customer_company",
              new PermissionOperates[] {
                PermissionOperates.CUSTOMER_VIEW_COMPANY_ONLY_MY,
                PermissionOperates.CUSTOMER_VIEW_COMPANY_TAGE,
                PermissionOperates.CUSTOMER_VIEW_COMPANY_ALL,
                PermissionOperates.CUSTOMER_VIEW_COMPANY_DEPT,
                PermissionOperates.CUSTOMER_VIEW_COMPANY_DEPT_AND_SUB
              },
          "sales_opportunity",
              new PermissionOperates[] {
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_TAGE,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ALL,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_DEPT,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_DEPT_AND_SUB
              },
          "contract_order_item",
              new PermissionOperates[] {
                PermissionOperates.SALES_VIEW_ORDER_ONLY_MY,
                PermissionOperates.SALES_VIEW_ORDER_TAGE,
                PermissionOperates.SALES_VIEW_ORDER_ALL,
                PermissionOperates.SALES_VIEW_ORDER_DEPT,
                PermissionOperates.SALES_VIEW_ORDER_DEPT_AND_SUB
              },
          "contract",
              new PermissionOperates[] {
                PermissionOperates.SALES_VIEW_CONTRACT_ONLY_MY,
                PermissionOperates.SALES_VIEW_CONTRACT_TAGE,
                PermissionOperates.SALES_VIEW_CONTRACT_ALL,
                PermissionOperates.SALES_VIEW_CONTRACT_DEPT,
                PermissionOperates.SALES_VIEW_CONTRACT_DEPT_AND_SUB
              },
          "business_activity",
              new PermissionOperates[] {
                PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_ONLY_MY,
                PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_TAGE,
                PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_ALL,
                PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_DEPT,
                PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_DEPT_AND_SUB
              },
          "contact_task",
              new PermissionOperates[] {
                PermissionOperates.TASK_VIEW_TASK_ONLY_MY,
                PermissionOperates.TASK_VIEW_TASK_TAGE,
                PermissionOperates.TASK_VIEW_TASK_ALL,
                PermissionOperates.TASK_VIEW_TASK_DEPT,
                PermissionOperates.TASK_VIEW_TASK_DEPT_AND_SUB
              },
          "sales_stage_approval",
              new PermissionOperates[] {
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_ONLY_MY,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_TAGE,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_ALL,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT,
                PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT_AND_SUB
              },
          "payment_record",
              new PermissionOperates[] {
                PermissionOperates.FINANCE_VIEW_PAYMENT_ONLY_MY,
                PermissionOperates.FINANCE_VIEW_PAYMENT_TAGE,
                PermissionOperates.FINANCE_VIEW_PAYMENT_ALL,
                PermissionOperates.FINANCE_VIEW_PAYMENT_DEPT,
                PermissionOperates.FINANCE_VIEW_PAYMENT_DEPT_AND_SUB
              },
          "project_file",
              new PermissionOperates[] {
                PermissionOperates.SALES_VIEW_PROJECT_FILE_ONLY_MY,
                PermissionOperates.SALES_VIEW_PROJECT_FILE_TAGE,
                PermissionOperates.SALES_VIEW_PROJECT_FILE_ALL,
                PermissionOperates.SALES_VIEW_PROJECT_FILE_DEPT,
                PermissionOperates.SALES_VIEW_PROJECT_FILE_DEPT_AND_SUB
              });

  static PermissionOperates[] permissionsFor(String resourceType) {
    return TABLE_PERMISSIONS.get(resourceType);
  }
}
