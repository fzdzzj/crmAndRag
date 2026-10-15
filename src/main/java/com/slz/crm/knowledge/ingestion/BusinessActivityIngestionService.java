package com.slz.crm.knowledge.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 商业活动语义摄取服务（add-business-activity-semantic-retrieval 任务 2.1，对齐 {@link CustomerIngestionService}
 * 先例）。
 *
 * <p>单条链路：拼语义文本（{@link BusinessActivitySemanticTextSupport}，含关联客户/商机/参与人/联系人附属语义段） →
 * EmbeddingService.embed → CrmEntityVectorStore.upsert（crm_entity 集合）。payload 对齐冻结 metadata 约定（B4）：
 * entityType(String→keyword) 固定 {@code
 * BUSINESS_ACTIVITY}；resourceId/ownerUserId/ownerDeptId/companyId/ opportunityId(Long→long
 * match)、title(String 展示补充键)；null 值不入 payload（键缺失即无值，过滤时自然不命中）。
 *
 * <p>ownerUserId 取 {@code business_activity.creator_id}（ResourceTypeConstant 登记的 business_activity
 * 用户字段， 与页面列表 SELF/DEPT 档判权字段同源）；ownerDeptId 为 creator_id 反查 sys_user.dept_id 的反范式缓存（DEPT 档
 * 过滤依赖），creator 不存在时该键缺省——与客户侧 owner_id 反查口径一致。
 *
 * <p>附属语义段：参与人（business_activity_user，按关联 id 升序、上限 5）与联系人（business_activity_contact 关联
 * customer_contact 姓名 + contact_role，同一上限）；相关 mapper 缺失（评测/单测注入 null）时对应段跳过， payload 与 pointId
 * 派生规则零改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessActivityIngestionService {

  /**
   * payload entityType 固定值（冻结 metadata 约定：String→keyword match）；public——业务检索实现
   * （BusinessRetrievalService）同源引用，防双处字面量漂移（对齐 {@link CustomerIngestionService#ENTITY_TYPE}）。
   */
  public static final String ENTITY_TYPE = "BUSINESS_ACTIVITY";

  /** 全量回埋批大小（id 游标分页）。 */
  private static final int REINGEST_BATCH_SIZE = 200;

  private final EmbeddingService embeddingService;

  private final CrmEntityVectorStore vectorStore;

  private final UserMapper userMapper;

  private final BusinessActivityMapper businessActivityMapper;

  private final CustomerCompanyMapper customerCompanyMapper;

  private final SalesOpportunityMapper salesOpportunityMapper;

  private final CustomerContactMapper customerContactMapper;

  private final BusinessActivityUserMapper businessActivityUserMapper;

  private final BusinessActivityContactMapper businessActivityContactMapper;

  /** 全量回埋结果（任务 2.3 端点返回）：total=扫描条数、success/failure=摄取成功/失败计数。 */
  public record ReingestResult(int total, int success, int failure) {}

  /**
   * 单条摄取：拼语义文本（含关联实体与人员段）→ 嵌入 → upsert crm_entity。失败直接抛出（调用方 hook 捕获 WARN 不阻断业务，回埋端点兜底）。
   *
   * @param entity 商业活动实体（需含 id 与语义字段）
   */
  public void ingest(BusinessActivityEntity entity) {
    if (entity == null || entity.getId() == null) {
      throw new IllegalArgumentException("商业活动实体或 id 不能为空");
    }
    String text =
        BusinessActivitySemanticTextSupport.buildSemanticText(
            entity,
            resolveCompanyName(entity.getCompanyId()),
            resolveOpportunityName(entity.getOpportunityId()),
            BusinessActivitySemanticTextSupport.buildParticipantSegment(
                loadParticipants(entity.getId())),
            buildContactSegment(entity.getId()));
    if (text.isBlank()) {
      throw new IllegalArgumentException("商业活动语义文本为空，跳过嵌入（id=" + entity.getId() + "）");
    }
    Long ownerDeptId = resolveOwnerDeptId(entity.getCreatorId());
    float[] vector = embeddingService.embed(text);
    vectorStore.upsert(
        new VectorRecord(
            BusinessActivitySemanticTextSupport.pointId(entity.getId()),
            BusinessActivitySemanticTextSupport.documentId(entity.getId()),
            BusinessActivitySemanticTextSupport.documentId(entity.getId()),
            0,
            text,
            vector,
            buildPayload(entity, ownerDeptId)));
  }

  /**
   * 加载活动参与人关联（按关联记录 id 升序、上限 {@link BusinessActivitySemanticTextSupport#SEGMENT_MAX} 条）； mapper
   * 缺失（评测/单测注入 null）或活动 id 为空返回空列表。
   *
   * @param activityId 商业活动主键
   * @return 有序参与人关联列表（可能为空）
   */
  private List<BusinessActivityUserEntity> loadParticipants(Long activityId) {
    List<BusinessActivityUserEntity> result = List.of();
    if (businessActivityUserMapper != null && activityId != null) {
      result =
          businessActivityUserMapper.selectList(
              new LambdaQueryWrapper<BusinessActivityUserEntity>()
                  .eq(BusinessActivityUserEntity::getActivityId, activityId)
                  .orderByAsc(BusinessActivityUserEntity::getId)
                  .last("LIMIT " + BusinessActivitySemanticTextSupport.SEGMENT_MAX));
    }
    return result;
  }

  /**
   * 拼活动联系人段：按关联记录 id 升序取活动联系人，反查 customer_contact 姓名 + 活动内角色 （contact_role）；contact mapper
   * 缺失时该段跳过（返回空串）。
   *
   * @param activityId 商业活动主键
   * @return 联系人段文本；无可用联系人返回空串
   */
  private String buildContactSegment(Long activityId) {
    String result = "";
    if (businessActivityContactMapper != null
        && customerContactMapper != null
        && activityId != null) {
      List<BusinessActivityContactEntity> relations =
          businessActivityContactMapper.selectList(
              new LambdaQueryWrapper<BusinessActivityContactEntity>()
                  .eq(BusinessActivityContactEntity::getActivityId, activityId)
                  .orderByAsc(BusinessActivityContactEntity::getId)
                  .last("LIMIT " + BusinessActivitySemanticTextSupport.SEGMENT_MAX));
      List<String> names = new ArrayList<>();
      List<String> roles = new ArrayList<>();
      for (BusinessActivityContactEntity relation : relations) {
        if (relation != null && relation.getContactId() != null) {
          CustomerContactEntity contact = customerContactMapper.selectById(relation.getContactId());
          if (contact != null && contact.getName() != null && !contact.getName().isBlank()) {
            names.add(contact.getName());
            roles.add(relation.getContactRole());
          }
        }
      }
      result = BusinessActivitySemanticTextSupport.buildContactSegment(names, roles);
    }
    return result;
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

  /** 关联商机名称（opportunity_id 为空或查无此商机返回 null）。 */
  private String resolveOpportunityName(Long opportunityId) {
    String result = null;
    if (opportunityId != null && salesOpportunityMapper != null) {
      SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(opportunityId);
      result = opportunity == null ? null : opportunity.getOpportunityName();
    }
    return result;
  }

  /**
   * payload 键集：entityType 固定值 + 业务/权限/关联元数据；null 值不入键（键缺失即无值，过滤时自然不命中）。
   *
   * @param entity 商业活动实体
   * @param ownerDeptId 创建人部门反范式缓存（可空）
   * @return payload
   */
  private Map<String, Object> buildPayload(BusinessActivityEntity entity, Long ownerDeptId) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("entityType", ENTITY_TYPE);
    payload.put("resourceId", entity.getId());
    if (entity.getCreatorId() != null) {
      payload.put("ownerUserId", entity.getCreatorId());
    }
    if (ownerDeptId != null) {
      payload.put("ownerDeptId", ownerDeptId);
    }
    if (entity.getCompanyId() != null) {
      payload.put("companyId", entity.getCompanyId());
    }
    if (entity.getOpportunityId() != null) {
      payload.put("opportunityId", entity.getOpportunityId());
    }
    if (entity.getActivityTitle() != null && !entity.getActivityTitle().isBlank()) {
      payload.put("title", entity.getActivityTitle().trim());
    }
    return payload;
  }

  /** creator_id 反查 sys_user.dept_id（反范式缓存）；creator 为空或查无此人返回 null。 */
  private Long resolveOwnerDeptId(Long creatorId) {
    Long result = null;
    if (creatorId != null && userMapper != null) {
      UserEntity creator = userMapper.selectById(creatorId);
      result = creator == null ? null : creator.getDeptId();
    }
    return result;
  }

  /**
   * 删除向量：documentId = BUSINESS_ACTIVITY:{id}（删除活动时同步失效）。
   *
   * @param id 商业活动主键
   */
  public void deleteByActivityId(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("商业活动 id 不能为空");
    }
    vectorStore.deleteByDocumentId(BusinessActivitySemanticTextSupport.documentId(id));
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
      List<BusinessActivityEntity> batch =
          businessActivityMapper.selectList(
              new LambdaQueryWrapper<BusinessActivityEntity>()
                  .gt(BusinessActivityEntity::getId, lastId)
                  .orderByAsc(BusinessActivityEntity::getId)
                  .last("LIMIT " + REINGEST_BATCH_SIZE));
      if (batch.isEmpty()) {
        break;
      }
      for (BusinessActivityEntity entity : batch) {
        total++;
        lastId = entity.getId();
        try {
          ingest(entity);
          success++;
        } catch (Exception e) {
          failure++;
          log.warn("商业活动语义全量回埋单条失败（id={}），计入失败计数", entity.getId(), e);
        }
      }
    }
    return new ReingestResult(total, success, failure);
  }
}
