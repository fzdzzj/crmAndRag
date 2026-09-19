package com.slz.crm.server.config;

import com.slz.crm.pojo.entity.*;
import java.util.*;

/**
 * 外键关联关系配置类
 *
 * @author evi
 */
public class ForeignKeyConfig {
  // 审批表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> APPROVAL_ATTACHMENT_RELATIONS = new HashMap<>();
  // 活动表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> BUSINESS_ACTIVITY_RELATIONS = new HashMap<>();
  // 活动联系人表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> BUSINESS_ACTIVITY_CONTACT_RELATIONS =
      new HashMap<>();
  // 活动产品表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> BUSINESS_ACTIVITY_USER_RELATIONS =
      new HashMap<>();
  // 合同任务表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> CONTACT_TASK_RELATIONS = new HashMap<>();
  // 合同表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> CONTRACT_RELATIONS = new HashMap<>();
  // 合同订单项表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> CONTRACT_ORDER_ITEM_RELATIONS = new HashMap<>();
  // 客户公司表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> CUSTOMER_COMPANY_RELATIONS = new HashMap<>();
  // 客户公司日志表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> CUSTOMER_COMPANY_LOG_RELATIONS = new HashMap<>();
  // 客户联系人表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> CUSTOMER_CONTACT_RELATIONS = new HashMap<>();
  // 客户合并日志表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> CUSTOMER_MERGE_LOG_RELATIONS = new HashMap<>();
  // 数据分享表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> DATA_SHARE_RELATIONS = new HashMap<>();
  // 发票信息表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> INVOICE_INFO_RELATIONS = new HashMap<>();
  // 付款记录表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> PAYMENT_RECORD_RELATIONS = new HashMap<>();
  // 权限表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> PERMISSIONS_RELATIONS = new HashMap<>();
  // 角色-权限关联表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> ROLE_PERMISSIONS_RELATIONS = new HashMap<>();
  // 销售机会表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> SALES_OPPORTUNITY_RELATIONS = new HashMap<>();
  // 销售阶段审批表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> SALES_STAGE_APPROVAL_RELATIONS = new HashMap<>();
  // 角色表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> SYS_ROLE_RELATIONS = new HashMap<>();
  // 用户表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> SYS_USER_RELATIONS = new HashMap<>();
  // 任务评论表关联的外键表信息：<关联表实体类, 外键字段名>
  public static final Map<Class<?>, List<String>> TASK_COMMENT_RELATIONS = new HashMap<>();
  // 所有关联关系的统一映射：<实体类, <关联表实体类, 外键字段名>>
  public static final Map<Class<?>, Map<Class<?>, List<String>>> ALL_RELATIONS = new HashMap<>();

  static {

    // 初始化所有关联关系到统一映射
    ALL_RELATIONS.put(ApprovalAttachmentEntity.class, APPROVAL_ATTACHMENT_RELATIONS);
    ALL_RELATIONS.put(BusinessActivityEntity.class, BUSINESS_ACTIVITY_RELATIONS);
    ALL_RELATIONS.put(BusinessActivityContactEntity.class, BUSINESS_ACTIVITY_CONTACT_RELATIONS);
    ALL_RELATIONS.put(BusinessActivityUserEntity.class, BUSINESS_ACTIVITY_USER_RELATIONS);
    ALL_RELATIONS.put(ContactTaskEntity.class, CONTACT_TASK_RELATIONS);
    ALL_RELATIONS.put(ContractEntity.class, CONTRACT_RELATIONS);
    ALL_RELATIONS.put(ContractOrderItemEntity.class, CONTRACT_ORDER_ITEM_RELATIONS);
    ALL_RELATIONS.put(CustomerCompanyEntity.class, CUSTOMER_COMPANY_RELATIONS);
    ALL_RELATIONS.put(CustomerCompanyLogEntity.class, CUSTOMER_COMPANY_LOG_RELATIONS);
    ALL_RELATIONS.put(CustomerContactEntity.class, CUSTOMER_CONTACT_RELATIONS);
    ALL_RELATIONS.put(CustomerMergeLogEntity.class, CUSTOMER_MERGE_LOG_RELATIONS);
    ALL_RELATIONS.put(DataShareEntity.class, DATA_SHARE_RELATIONS);
    ALL_RELATIONS.put(InvoiceInfoEntity.class, INVOICE_INFO_RELATIONS);
    ALL_RELATIONS.put(PaymentRecordEntity.class, PAYMENT_RECORD_RELATIONS);
    ALL_RELATIONS.put(PermissionsEntity.class, PERMISSIONS_RELATIONS);
    ALL_RELATIONS.put(RolePermissionsEntity.class, ROLE_PERMISSIONS_RELATIONS);
    ALL_RELATIONS.put(SalesOpportunityEntity.class, SALES_OPPORTUNITY_RELATIONS);
    ALL_RELATIONS.put(SalesStageApprovalEntity.class, SALES_STAGE_APPROVAL_RELATIONS);
    ALL_RELATIONS.put(RoleEntity.class, SYS_ROLE_RELATIONS);
    ALL_RELATIONS.put(UserEntity.class, SYS_USER_RELATIONS);
    ALL_RELATIONS.put(TaskCommentEntity.class, TASK_COMMENT_RELATIONS);

    // 审批关联的表：空

    // 活动表关联的表：
    // 1. 商业活动-联系人关联表（business_activity_contact.activity_id）
    BUSINESS_ACTIVITY_RELATIONS.put(
        BusinessActivityContactEntity.class, Collections.singletonList("activityId"));
    // 2. 商业活动-用户关联表（business_activity_user.activity_id）
    BUSINESS_ACTIVITY_RELATIONS.put(
        BusinessActivityUserEntity.class, Collections.singletonList("activityId"));

    // 活动联系人表关联的表：空

    // 活动产品表关联的表：空

    // 合同任务表关联的表：
    // 1. 任务评论表（task_comment.task_id）
    CUSTOMER_CONTACT_RELATIONS.put(TaskCommentEntity.class, Collections.singletonList("taskId"));

    // 合同表关联的表：
    // 1. 合同订单项表（contract_order_item.contract_id）
    CONTRACT_RELATIONS.put(ContractOrderItemEntity.class, Collections.singletonList("contractId"));
    // 2. 支付记录表（payment_record.contract_id）
    CONTRACT_RELATIONS.put(PaymentRecordEntity.class, Collections.singletonList("contractId"));
    // 3. 发票信息表（invoice_info.contract_id）
    CONTRACT_RELATIONS.put(InvoiceInfoEntity.class, Collections.singletonList("contractId"));

    // 合同订单项表关联的表：
    // 1. 支付记录表（payment_record.order_item_id）
    CONTRACT_ORDER_ITEM_RELATIONS.put(
        PaymentRecordEntity.class, Collections.singletonList("orderItemId"));

    // 公司关联的表：
    // 1. 商业活动表（business_activity.company_id）
    CUSTOMER_COMPANY_RELATIONS.put(
        BusinessActivityEntity.class, Collections.singletonList("companyId"));
    // 2. 联络任务表（contact_task.company_id）
    CUSTOMER_COMPANY_RELATIONS.put(ContactTaskEntity.class, Collections.singletonList("companyId"));
    // 3. 合同表（contract.company_id）
    CUSTOMER_COMPANY_RELATIONS.put(ContractEntity.class, Collections.singletonList("companyId"));
    // 4. 联系人表（customer_contact.company_id）
    CUSTOMER_COMPANY_RELATIONS.put(
        CustomerContactEntity.class, Collections.singletonList("companyId"));
    // 5. 客户合并历史表（customer_merge_log.merged_company_id）
    List<String> fields =
        CUSTOMER_COMPANY_RELATIONS.computeIfAbsent(
            CustomerMergeLogEntity.class, k -> new ArrayList<>());
    fields.add("mergedCompanyId");
    fields.add("targetCompanyId");
    // 6. 销售机会表（sales_opportunity.company_id）
    CUSTOMER_COMPANY_RELATIONS.put(
        SalesOpportunityEntity.class, Collections.singletonList("companyId"));

    // 联系人关联的表：
    // 1. 联络任务表（contact_task.contact_id）
    CUSTOMER_CONTACT_RELATIONS.put(ContactTaskEntity.class, Collections.singletonList("contactId"));
    // 2. 销售机会表（sales_opportunity.contact_id）
    CUSTOMER_CONTACT_RELATIONS.put(
        SalesOpportunityEntity.class, Collections.singletonList("contactId"));
    // 3. 商业活动-联系人关联表（business_activity_contact.contact_id）
    CUSTOMER_CONTACT_RELATIONS.put(
        BusinessActivityContactEntity.class, Collections.singletonList("contactId"));

    // 付款记录表关联的表：
    // 1. 发票信息表（invoice_info.payment_id）
    PAYMENT_RECORD_RELATIONS.put(InvoiceInfoEntity.class, Collections.singletonList("paymentId"));

    // 角色关联的表：
    // 1. 角色-权限关联表（role_permissions.permission_id）
    PERMISSIONS_RELATIONS.put(PermissionsEntity.class, Collections.singletonList("permissionId"));

    // 销售机会关联的表：
    // 1. 商业活动表（business_activity.opportunity_id）
    SALES_OPPORTUNITY_RELATIONS.put(
        BusinessActivityEntity.class, Collections.singletonList("opportunityId"));
    // 2. 合同任务表（contact_task.opportunity_id）
    SALES_OPPORTUNITY_RELATIONS.put(
        ContactTaskEntity.class, Collections.singletonList("opportunityId"));
    // 3. 合同表（contract.opportunity_id）
    SALES_OPPORTUNITY_RELATIONS.put(
        ContractEntity.class, Collections.singletonList("opportunityId"));
    // 4. 销售机会表（sales_stage_approval.opportunity_id）
    SALES_OPPORTUNITY_RELATIONS.put(
        SalesStageApprovalEntity.class, Collections.singletonList("opportunityId"));

    // 销售阶段审批表关联的表：
    // 1. 审批附件表（approval_attachment.approval_id）
    SALES_STAGE_APPROVAL_RELATIONS.put(
        ApprovalAttachmentEntity.class, Collections.singletonList("approvalId"));

    // 角色-权限关联表关联的表：
    // 1. 角色-权限关联表（role_permissions.role_id）
    SYS_ROLE_RELATIONS.put(RolePermissionsEntity.class, Collections.singletonList("roleId"));
    // 2. 用户表（sys_user.role_id）
    SYS_ROLE_RELATIONS.put(UserEntity.class, Collections.singletonList("roleId"));

    //
    // 1. 商业活动表（business_activity.creator_id）
    SYS_USER_RELATIONS.put(BusinessActivityEntity.class, Collections.singletonList("creatorId"));
    // 2 商业活动-联系人关联表（business_activity_contact.creator_id）
    SYS_USER_RELATIONS.put(
        BusinessActivityContactEntity.class, Collections.singletonList("creatorId"));
    // 3. 商业活动-用户关联表（business_activity_user.creator_id）
    List<String> fields2 =
        SYS_USER_RELATIONS.computeIfAbsent(
            BusinessActivityUserEntity.class, k -> new ArrayList<>());
    fields2.add("creatorId");
    fields2.add("userId");
    // 4. 联络任务表（contact_task.creator_id）
    List<String> fields3 =
        SYS_USER_RELATIONS.computeIfAbsent(ContactTaskEntity.class, k -> new ArrayList<>());
    fields3.add("assigneeId");
    fields3.add("assignerId");
    fields3.add("creatorId");
    // 5. 合同表（contract.creator_id）
    List<String> fields4 =
        SYS_USER_RELATIONS.computeIfAbsent(ContractEntity.class, k -> new ArrayList<>());
    fields4.add("creatorId");
    fields4.add("ownerId");
    // 6. 客户公司表（customer_company.creator_id）
    List<String> fields5 =
        SYS_USER_RELATIONS.computeIfAbsent(CustomerCompanyEntity.class, k -> new ArrayList<>());
    fields5.add("creatorId");
    fields5.add("ownerId");
    // 7. 客户公司日志表（customer_company_log.creator_id）
    SYS_USER_RELATIONS.put(CustomerCompanyLogEntity.class, Collections.singletonList("creatorId"));
    // 8. 客户联系人表（customer_contact.creator_id）
    SYS_USER_RELATIONS.put(CustomerContactEntity.class, Collections.singletonList("creatorId"));
    // 9. 客户合并日志表（customer_merge_log.operator_id）
    SYS_USER_RELATIONS.put(CustomerMergeLogEntity.class, Collections.singletonList("operatorId"));
    // 10. 数据分享表（data_share.share_from）
    SYS_USER_RELATIONS.put(DataShareEntity.class, Collections.singletonList("shareFrom"));
    // 11. 发票信息表（invoice_info.creator_id）
    SYS_USER_RELATIONS.put(InvoiceInfoEntity.class, Collections.singletonList("creatorId"));
    // 12. 付款记录表（payment_record.creator_id）
    SYS_USER_RELATIONS.put(PaymentRecordEntity.class, Collections.singletonList("creatorId"));
    // 13. 角色-权限关联表（role_permissions.creator_id）
    SYS_USER_RELATIONS.put(RolePermissionsEntity.class, Collections.singletonList("creatorId"));
    // 14. 销售机会表（sales_stage_approval.creator_id）
    List<String> fields6 =
        SYS_USER_RELATIONS.computeIfAbsent(SalesOpportunityEntity.class, k -> new ArrayList<>());
    fields6.add("creatorId");
    fields6.add("ownerId");
    fields6.add("approverId");
    // 15. 销售阶段审批表（sales_stage_approval.creator_id）
    List<String> fields7 =
        SYS_USER_RELATIONS.computeIfAbsent(SalesStageApprovalEntity.class, k -> new ArrayList<>());
    fields7.add("applicationId");
    fields7.add("approverId");
    // 16. 用户表（sys_user.creator_id）
    SYS_USER_RELATIONS.put(UserEntity.class, Collections.singletonList("creatorId"));
    // 17. 任务评论表（task_comment.creator_id）
    SYS_USER_RELATIONS.put(TaskCommentEntity.class, Collections.singletonList("creatorId"));
  }
}
