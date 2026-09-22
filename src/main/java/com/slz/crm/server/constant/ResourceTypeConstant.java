package com.slz.crm.server.constant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源类型常量
 *
 * <p>用于三级数据权限系统中,定义需要权限管理的表和对应的用户字段
 *
 * <p>资源类型直接使用表名(String)进行标识
 */
public class ResourceTypeConstant {

  /** 需要权限管理的表名集合 */
  public static final Set<String> MANAGED_TABLES =
      Set.of(
          "customer_company",
          "customer_contact",
          "sales_opportunity",
          "contract_order_item",
          "contract",
          "business_activity",
          "contact_task",
          "sales_stage_approval",
          "payment_record",
          "project_file");

  /**
   * 每个表需要检查的用户字段
   *
   * <p>key: 表名, value: 需要检查的用户字段列表
   *
   * <p>用于第一级权限(仅查看自己的),检查这些字段是否等于当前用户ID
   */
  public static final Map<String, List<String>> TABLE_USER_FIELDS =
      Map.of(
          "customer_company", List.of("creator_id"),
          "customer_contact", List.of("creator_id"),
          "sales_opportunity", List.of("creator_id", "owner_id"),
          "contract_order_item", List.of("creator_id"),
          "contract", List.of("creator_id"),
          "business_activity", List.of("creator_id"),
          "contact_task", List.of("creator_id", "assignee_id", "assigner_id"),
          "sales_stage_approval", List.of("applicant_id", "approver_id"),
          "payment_record", List.of("creator_id"),
          "project_file", List.of("uploader_id"));

  /**
   * 判断表是否需要权限管理
   *
   * @param tableName 表名(如: customer_company)
   * @return true-需要权限管理, false-不需要
   */
  public static boolean isManagedTable(String tableName) {
    final boolean result;
    if (tableName == null) {
      result = false;
    } else {
      result = MANAGED_TABLES.contains(tableName.toLowerCase());
    }
    return result;
  }

  /**
   * 根据表名获取需要检查的用户字段
   *
   * @param tableName 表名
   * @return 用户字段列表,如果未找到默认返回 creator_id
   */
  public static List<String> getUserFieldsByTableName(String tableName) {
    final List<String> result;
    if (tableName == null) {
      result = List.of("creator_id");
    } else {
      result = TABLE_USER_FIELDS.getOrDefault(tableName.toLowerCase(), List.of("creator_id"));
    }
    return result;
  }
}
