package com.slz.crm.knowledge.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 销售商机语义摄取服务（add-sales-opportunity-semantic-retrieval 任务 2.1，对齐 {@link
 * BusinessActivityIngestionService} 先例）。
 *
 * <p>单条链路：拼语义文本（{@link
 * SalesOpportunitySemanticTextSupport}，含商机名称/关联客户/主要联系人/阶段/金额/来源/预计结单/需求描述/负责人） →
 * EmbeddingService.embed → CrmEntityVectorStore.upsert（crm_entity 集合）。payload 对齐冻结 metadata 约定（B4）：
 * entityType(String→keyword) 固定 {@code
 * SALES_OPPORTUNITY}；resourceId/ownerUserId/ownerDeptId/companyId(Long→long match)、title(String
 * 展示补充键)；null 值不入 payload（键缺失即无值，过滤时自然不命中）。
 *
 * <p>ownerUserId 取 {@code sales_opportunity.owner_id}（跟进销售；ResourceTypeConstant 登记的
 * sales_opportunity 用户字段 creator_id+owner_id，页面列表 SELF/DEPT 档判权字段同源，owner 为业务主归属锚）；ownerDeptId 为
 * owner_id 反查 sys_user.dept_id 的反范式缓存（DEPT 档过滤依赖），owner 不存在时该键缺省。
 *
 * <p>关联语义段：客户公司名（company_id 反查 customer_company.company_name）与主要联系人（contact_id 反查
 * customer_contact.name）、跟进负责人名（owner_id 反查 sys_user.name）；相关 mapper 缺失（评测/单测注入 null）时对应段跳过，
 * payload 与 pointId 派生规则零改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesOpportunityIngestionService {

  /**
   * payload entityType 固定值（冻结 metadata 约定：String→keyword match）；public——业务检索实现
   * （BusinessRetrievalService）同源引用，防双处字面量漂移（对齐 {@link
   * BusinessActivityIngestionService#ENTITY_TYPE}）。
   */
  public static final String ENTITY_TYPE = "SALES_OPPORTUNITY";

  /** 全量回埋批大小（id 游标分页）。 */
  private static final int REINGEST_BATCH_SIZE = 200;

  private final EmbeddingService embeddingService;

  private final CrmEntityVectorStore vectorStore;

  private final UserMapper userMapper;

  private final SalesOpportunityMapper salesOpportunityMapper;

  private final CustomerCompanyMapper customerCompanyMapper;

  private final CustomerContactMapper customerContactMapper;

  /** 全量回埋结果（任务 2.3 端点返回）：total=扫描条数、success/failure=摄取成功/失败计数。 */
  public record ReingestResult(int total, int success, int failure) {}

  /**
   * 单条摄取：拼语义文本（含关联客户/联系人/负责人段）→ 嵌入 → upsert crm_entity。失败直接抛出（调用方 hook 捕获 WARN 不阻断业务，回埋端点兜底）。
   *
   * @param entity 销售商机实体（需含 id 与语义字段）
   */
  public void ingest(SalesOpportunityEntity entity) {
    if (entity == null || entity.getId() == null) {
      throw new IllegalArgumentException("销售商机实体或 id 不能为空");
    }
    String text =
        SalesOpportunitySemanticTextSupport.buildSemanticText(
            entity,
            resolveCompanyName(entity.getCompanyId()),
            resolveContactName(entity.getContactId()),
            resolveUserName(entity.getOwnerId()));
    if (text.isBlank()) {
      throw new IllegalArgumentException("销售商机语义文本为空，跳过嵌入（id=" + entity.getId() + "）");
    }
    Long ownerDeptId = resolveOwnerDeptId(entity.getOwnerId());
    float[] vector = embeddingService.embed(text);
    vectorStore.upsert(
        new VectorRecord(
            SalesOpportunitySemanticTextSupport.pointId(entity.getId()),
            SalesOpportunitySemanticTextSupport.documentId(entity.getId()),
            SalesOpportunitySemanticTextSupport.documentId(entity.getId()),
            0,
            text,
            vector,
            buildPayload(entity, ownerDeptId)));
  }

  /**
   * 删除向量：documentId = SALES_OPPORTUNITY:{id}（删除/关闭商机时同步失效）。
   *
   * @param id 商机主键
   */
  public void deleteByOpportunityId(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("销售商机 id 不能为空");
    }
    vectorStore.deleteByDocumentId(SalesOpportunitySemanticTextSupport.documentId(id));
  }

  /**
   * 全量回埋（任务 2.3）：分页游标（id 递增）逐条重嵌；单条失败计数不中断，返回成功/失败计数。
   *
   * @return 回埋结果
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  // 豁免理由（对齐 add-customer-semantic-retrieval 任务 4.3）：回埋契约=单条失败计数不中断，失败类型不可枚举
  // （嵌入外呼/向量库/拼文本均可能抛非受检异常），逐条 catch Exception 记失败计数由端点返回，不吞中断语义。
  public ReingestResult reingestAll() {
    int total = 0;
    int success = 0;
    int failure = 0;
    long lastId = 0L;
    while (true) {
      List<SalesOpportunityEntity> batch =
          salesOpportunityMapper.selectList(
              new LambdaQueryWrapper<SalesOpportunityEntity>()
                  .gt(SalesOpportunityEntity::getId, lastId)
                  .orderByAsc(SalesOpportunityEntity::getId)
                  .last("LIMIT " + REINGEST_BATCH_SIZE));
      if (batch.isEmpty()) {
        break;
      }
      for (SalesOpportunityEntity entity : batch) {
        total++;
        lastId = entity.getId();
        try {
          ingest(entity);
          success++;
        } catch (Exception e) {
          failure++;
          log.warn("销售商机语义全量回埋单条失败（id={}），计入失败计数", entity.getId(), e);
        }
      }
    }
    return new ReingestResult(total, success, failure);
  }

  /** 关联客户公司名称（company_id 为空或查无此公司返回 null）。 */
  private String resolveCompanyName(Long companyId) {
    String result = null;
    if (companyId != null && customerCompanyMapper != null) {
      CustomerCompanyEntity company = customerCompanyMapper.selectById(companyId);
      result = company == null ? null : company.getCompanyName();
    }
    return result;
  }

  /** 主要联系人姓名（contact_id 为空或查无此联系人返回 null）。 */
  private String resolveContactName(Long contactId) {
    String result = null;
    if (contactId != null && customerContactMapper != null) {
      CustomerContactEntity contact = customerContactMapper.selectById(contactId);
      result = contact == null ? null : contact.getName();
    }
    return result;
  }

  /** 跟进负责人姓名（owner_id 为空或查无此人返回 null）。 */
  private String resolveUserName(Long ownerId) {
    String result = null;
    if (ownerId != null && userMapper != null) {
      UserEntity owner = userMapper.selectById(ownerId);
      result = owner == null ? null : owner.getRealName();
    }
    return result;
  }

  /** owner_id 反查 sys_user.dept_id（反范式缓存）；owner 为空或查无此人返回 null。 */
  private Long resolveOwnerDeptId(Long ownerId) {
    Long result = null;
    if (ownerId != null && userMapper != null) {
      UserEntity owner = userMapper.selectById(ownerId);
      result = owner == null ? null : owner.getDeptId();
    }
    return result;
  }

  /**
   * payload 键集：entityType 固定值 + 业务/权限/关联元数据；null 值不入键（键缺失即无值，过滤时自然不命中）。
   *
   * @param entity 销售商机实体
   * @param ownerDeptId 负责人部门反范式缓存（可空）
   * @return payload
   */
  private Map<String, Object> buildPayload(SalesOpportunityEntity entity, Long ownerDeptId) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("entityType", ENTITY_TYPE);
    payload.put("resourceId", entity.getId());
    if (entity.getOwnerId() != null) {
      payload.put("ownerUserId", entity.getOwnerId());
    }
    if (ownerDeptId != null) {
      payload.put("ownerDeptId", ownerDeptId);
    }
    if (entity.getCompanyId() != null) {
      payload.put("companyId", entity.getCompanyId());
    }
    if (entity.getOpportunityName() != null && !entity.getOpportunityName().isBlank()) {
      payload.put("title", entity.getOpportunityName().trim());
    }
    return payload;
  }
}
