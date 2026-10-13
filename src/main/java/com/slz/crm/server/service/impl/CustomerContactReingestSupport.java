package com.slz.crm.server.service.impl;

import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户联系人变更后所属公司语义重摄取协作类（add-customer-contact-retrieval 任务 1.3 / 任务 4.1 PMD NcssCount 拆分： 拆自 {@link
 * CustomerContactServiceImpl}，行为等价）。纯静态协作，无可变共享状态。
 *
 * <p>职责：联系人列表去重取公司 id、事务提交后对受影响公司批量回查重嵌（复用 {@link CustomerSemanticHookSupport#runAfterCommit} 的
 * afterCommit 闸与 {@link CustomerSemanticHookSupport#reingestById} 的回查重嵌）。无 embedding 配置 （ingestion
 * 缺失）时整体跳过，静默降级不致既有 IT 失败。
 */
final class CustomerContactReingestSupport {

  private CustomerContactReingestSupport() {}

  /**
   * 提取联系人列表所属公司 id 去重集合（null/空返回空集合）。用于批量重摄取去重，避免同一公司重复 upsert。
   *
   * @param contacts 联系人列表（可为 null/空）
   * @return 去重后的公司 id 集合（保序去重，LinkedHashSet 语义）；空输入返回空集合
   */
  static List<Long> distinctCompanyIds(List<CustomerContactEntity> contacts) {
    Set<Long> companyIds = new LinkedHashSet<>();
    if (contacts != null) {
      for (CustomerContactEntity c : contacts) {
        if (c != null && c.getCompanyId() != null) {
          companyIds.add(c.getCompanyId());
        }
      }
    }
    return new ArrayList<>(companyIds);
  }

  /**
   * 联系人 CRUD 变更后的事务提交重摄取入口：依赖未装配（部分单测场景）、companyIds 为空均跳过； 有依赖则委托 {@link
   * CustomerSemanticHookSupport#runAfterCommit} 注册 afterCommit，逐公司回查完整实体后 upsert。
   *
   * @param scene 场景描述（日志定位用）
   * @param companyIds 受影响公司 id 集合（可为 null/空）
   * @param ingestion 摄取服务（可为 null，此时整体跳过）
   * @param companyMapper 公司 mapper（回查 loader 用）
   */
  static void reingestAfterCommit(
      String scene,
      List<Long> companyIds,
      CustomerIngestionService ingestion,
      CustomerCompanyMapper companyMapper) {
    if (ingestion == null || companyMapper == null) {
      return;
    }
    List<Long> ids = companyIds == null ? List.of() : companyIds;
    CustomerSemanticHookSupport.runAfterCommit(
        scene,
        () -> {
          for (Long companyId : ids) {
            CustomerSemanticHookSupport.reingestById(
                companyId, ingestion, companyMapper::selectById);
          }
        });
  }

  /** 便捷入口：对联系人列表去重取公司后批量重摄取（空列表不触发）。 */
  static void reingestForContacts(
      String scene,
      List<CustomerContactEntity> contacts,
      CustomerIngestionService ingestion,
      CustomerCompanyMapper companyMapper) {
    reingestAfterCommit(scene, distinctCompanyIds(contacts), ingestion, companyMapper);
  }
}
