package com.slz.crm.integration.schema;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.knowledge.entity.BatchFileResultEntity;
import com.slz.crm.knowledge.entity.BatchTaskEntity;
import com.slz.crm.knowledge.entity.ChunkUploadSessionEntity;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.KbRetrievalStrategy;
import com.slz.crm.knowledge.entity.KbRetrievalStrategyHistory;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseMemberEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.audit.PlatformGovernanceAuditEntity;
import com.slz.crm.platform.config.entity.CostKeyChangeRequestEntity;
import com.slz.crm.platform.config.entity.DynamicConfigHistoryEntity;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.lifecycle.PlatformLifecycleEventEntity;
import com.slz.crm.platform.reconcile.PlatformReconcileItemEntity;
import com.slz.crm.platform.reconcile.PlatformReconcileReportEntity;
import com.slz.crm.platform.security.PlatformContentSecurityEventEntity;
import com.slz.crm.platform.token.PlatformTokenUsageEntity;
import com.slz.crm.pojo.entity.AiChatImageEntity;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.entity.AiPendingActionEntity;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.pojo.entity.AiToolCallLogEntity;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.AssistMessageEntity;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.pojo.entity.CompanyDeptEntity;
import com.slz.crm.pojo.entity.CompanyGroupEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerCompanyLogEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.pojo.entity.CustomerMergeLogEntity;
import com.slz.crm.pojo.entity.DataShareEntity;
import com.slz.crm.pojo.entity.InvoiceInfoEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.TageResourceBindingEntity;
import com.slz.crm.pojo.entity.TageRoleBindingEntity;
import com.slz.crm.pojo.entity.TagsEntity;
import com.slz.crm.pojo.entity.TaskCommentEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.entity.UserHandoverEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * 实体侧 schema 元数据提取器（audit-entity-table-drift 任务 1.1/1.2）。
 *
 * <p><b>提取路径</b>：ClassPath 扫描 {@code com.slz.crm} 下 {@code @TableName} 注解类 → MyBatis-Plus {@link
 * TableInfoHelper#initTableInfo} 走运行期同一套映射 （{@code @TableId} 计入、{@code @TableField} 显式列名优先、{@code
 * exist=false} 排除、驼峰转下划线）， 不做正则解析 Java 源码。
 *
 * <p><b>登记清单</b>：{@link #ENTITY_REGISTRY} 为 55 张实体表权威清单；扫描结果与登记不一致即抛错—— 新实体必须同步登记，防止漏审。
 */
public final class SchemaDriftAuditor {

  /** 内嵌/匿名类不入审计（防御：测试夹具类即使带 @TableName 也不污染扫描计数）。 */
  private static final String SCAN_BASE_PACKAGE = "com.slz.crm";

  /**
   * 实体登记清单（表名 → 实体类，按表名升序）：pojo/entity 38 + knowledge/entity 9 + platform 9 = 56（本卡新增
   * cost_key_change_request）。
   */
  public static final Map<String, Class<?>> ENTITY_REGISTRY = buildRegistry();

  private SchemaDriftAuditor() {}

  /**
   * ClassPath 扫描 {@code com.slz.crm} 下所有 {@code @TableName} 实体，逐一提列并做登记校验。
   *
   * @return 实体侧表清单（按表名升序）
   * @throws IllegalStateException 扫描集合与 {@link #ENTITY_REGISTRY} 不一致（漏登记 / 多出实体 / 类漂移）
   */
  public static List<EntityTable> scanAndExtract() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));

    Map<String, Class<?>> scanned = new LinkedHashMap<>();
    for (BeanDefinition bd : scanner.findCandidateComponents(SCAN_BASE_PACKAGE)) {
      Class<?> clazz = loadClass(bd.getBeanClassName());
      if (clazz.isMemberClass()
          || clazz.isLocalClass()
          || clazz.isAnonymousClass()
          || clazz.isSynthetic()) {
        continue;
      }
      TableName tableName = clazz.getAnnotation(TableName.class);
      if (tableName == null) {
        continue;
      }
      scanned.put(tableName.value(), clazz);
    }

    List<String> problems = new ArrayList<>();
    // 登记了但没扫到 → 实体缺失或类名漂移
    for (Map.Entry<String, Class<?>> entry : ENTITY_REGISTRY.entrySet()) {
      Class<?> found = scanned.get(entry.getKey());
      if (found == null) {
        problems.add("登记表 '" + entry.getKey() + "' 未在扫描结果中出现（实体缺失或 @TableName 表名改动）");
      } else if (!found.equals(entry.getValue())) {
        problems.add(
            "登记表 '"
                + entry.getKey()
                + "' 实体类漂移：登记="
                + entry.getValue().getName()
                + "，实际扫描="
                + found.getName());
      }
    }
    // 扫到但没登记 → 新实体未同步登记
    for (Map.Entry<String, Class<?>> entry : scanned.entrySet()) {
      if (!ENTITY_REGISTRY.containsKey(entry.getKey())) {
        problems.add(
            "扫描到未登记实体表 '"
                + entry.getKey()
                + "'（"
                + entry.getValue().getName()
                + "）——新实体必须同步登记进 ENTITY_REGISTRY");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "实体登记校验失败，共 " + problems.size() + " 处：\n  - " + String.join("\n  - ", problems));
    }

    List<EntityTable> result = new ArrayList<>();
    for (String table : new LinkedHashSet<>(ENTITY_REGISTRY.keySet())) {
      result.add(extractTable(ENTITY_REGISTRY.get(table)));
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * 用 MyBatis-Plus {@link TableInfoHelper#initTableInfo} 提取单实体的表名 + 物理列集。
   *
   * <p>表名直接取 {@code @TableName.value()}：实测带 tangzc {@code @AutoTable}/{@code @Table} 的 pojo 实体经
   * {@link TableInfoHelper} 解析时表名会退化为类名下划线（如 {@code approval_attachment_entity}）， 忽略显式
   * {@code @TableName}；本项目所有被扫实体都显式带 {@code @TableName}，故以注解值为准。
   *
   * <p>列集 = 主键列（若有）+ {@code TableInfo#getFieldList()} 各列，走 MP 运行期同一套映射； {@code exist=false} 字段不会出现在
   * fieldList，自动排除。
   *
   * <p><b>反引号归一化</b>：MySQL 保留字列（如 {@code DynamicConfigItemEntity.sensitive} 声明为
   * {@code @TableField("`sensitive`")}）MP 返回的列名带反引号，物理列实际无反引号——比对前统一剥除， 避免与库侧 {@code
   * information_schema.columns} 的裸列名错配造成误报。
   *
   * @param entityClass 实体类
   * @return 该实体的表映射元数据
   */
  public static EntityTable extractTable(Class<?> entityClass) {
    TableInfo tableInfo =
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);

    String tableName = entityClass.getAnnotation(TableName.class).value();
    Map<String, String> columnTypes = new LinkedHashMap<>();
    if (tableInfo.getKeyColumn() != null) {
      columnTypes.put(normalizeColumn(tableInfo.getKeyColumn()), tableInfo.getKeyType().getName());
    }
    for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
      columnTypes.put(
          normalizeColumn(fieldInfo.getColumn()), fieldInfo.getPropertyType().getName());
    }
    return new EntityTable(tableName, entityClass, columnTypes);
  }

  /** 剥除 MySQL 反引号（保留字列 {@code @TableField("`name`")} 场景），物理列名不含反引号。 */
  private static String normalizeColumn(String column) {
    return column == null ? null : column.replace("`", "");
  }

  private static Class<?> loadClass(String className) {
    try {
      return Class.forName(className, false, SchemaDriftAuditor.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("扫描候选类加载失败：" + className, e);
    }
  }

  private static Map<String, Class<?>> buildRegistry() {
    Map<String, Class<?>> registry = new LinkedHashMap<>();
    // AI 助手（pojo/entity）
    registry.put("ai_chat_image", AiChatImageEntity.class);
    registry.put("ai_conversation_memory", AiConversationMemoryEntity.class);
    registry.put("ai_message", AiMessageEntity.class);
    registry.put("ai_pending_action", AiPendingActionEntity.class);
    registry.put("ai_session", AiSessionEntity.class);
    registry.put("ai_tool_call_log", AiToolCallLogEntity.class);
    registry.put("approval_attachment", ApprovalAttachmentEntity.class);
    registry.put("assist_message", AssistMessageEntity.class);
    registry.put("assist_request", AssistRequestEntity.class);
    // 知识库（knowledge/entity）
    registry.put("batch_file_result", BatchFileResultEntity.class);
    registry.put("batch_task", BatchTaskEntity.class);
    registry.put("business_activity", BusinessActivityEntity.class);
    registry.put("business_activity_contact", BusinessActivityContactEntity.class);
    registry.put("business_activity_user", BusinessActivityUserEntity.class);
    registry.put("chunk_upload_session", ChunkUploadSessionEntity.class);
    registry.put("company_dept", CompanyDeptEntity.class);
    registry.put("company_group", CompanyGroupEntity.class);
    registry.put("contact_task", ContactTaskEntity.class);
    registry.put("contract", ContractEntity.class);
    registry.put("contract_order_item", ContractOrderItemEntity.class);
    registry.put("cost_key_change_request", CostKeyChangeRequestEntity.class);
    registry.put("customer_company", CustomerCompanyEntity.class);
    registry.put("customer_company_log", CustomerCompanyLogEntity.class);
    registry.put("customer_contact", CustomerContactEntity.class);
    registry.put("customer_contact_remark", CustomerContactRemarkEntity.class);
    registry.put("customer_merge_log", CustomerMergeLogEntity.class);
    registry.put("data_share", DataShareEntity.class);
    registry.put("document_vector_chunk", DocumentVectorChunkEntity.class);
    // 平台（platform/config）
    registry.put("dynamic_config_history", DynamicConfigHistoryEntity.class);
    registry.put("dynamic_config_item", DynamicConfigItemEntity.class);
    registry.put("invoice_info", InvoiceInfoEntity.class);
    registry.put("knowledge_base", KnowledgeBaseEntity.class);
    registry.put("knowledge_base_member", KnowledgeBaseMemberEntity.class);
    registry.put("kb_retrieval_strategy", KbRetrievalStrategy.class);
    registry.put("kb_retrieval_strategy_history", KbRetrievalStrategyHistory.class);
    registry.put("payment_record", PaymentRecordEntity.class);
    registry.put("permissions", PermissionsEntity.class);
    // 平台（platform）
    registry.put("platform_content_security_event", PlatformContentSecurityEventEntity.class);
    registry.put("platform_governance_audit", PlatformGovernanceAuditEntity.class);
    registry.put("platform_lifecycle_event", PlatformLifecycleEventEntity.class);
    registry.put("platform_reconcile_item", PlatformReconcileItemEntity.class);
    registry.put("platform_reconcile_report", PlatformReconcileReportEntity.class);
    registry.put("platform_token_usage", PlatformTokenUsageEntity.class);
    registry.put("project_file", ProjectFileEntity.class);
    registry.put("role_permissions", RolePermissionsEntity.class);
    registry.put("sales_opportunity", SalesOpportunityEntity.class);
    registry.put("sales_stage_approval", SalesStageApprovalEntity.class);
    registry.put("sys_dept", SysDeptEntity.class);
    registry.put("sys_role", RoleEntity.class);
    registry.put("sys_user", UserEntity.class);
    registry.put("tage", TagsEntity.class);
    registry.put("tage_resource_binding", TageResourceBindingEntity.class);
    registry.put("tage_role_binding", TageRoleBindingEntity.class);
    registry.put("task_comment", TaskCommentEntity.class);
    registry.put("uploaded_file", UploadedFileEntity.class);
    registry.put("user_handover", UserHandoverEntity.class);
    return Collections.unmodifiableMap(registry);
  }
}
