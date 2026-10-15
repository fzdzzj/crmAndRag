package com.slz.crm.knowledge.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.ContactTaskMapper;
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
 * 跟进待办任务语义摄取服务（add-unified-business-semantic-retrieval 任务 3.2，对齐 {@link ContractIngestionService}
 * 先例）。
 *
 * <p>单条链路：拼语义文本（{@link ContactTaskSemanticTextSupport}，含任务主题/类型/优先级/状态/起止时间/客户/商机/联系人/执行人/
 * task_content 富文本详细描述）→ EmbeddingService.embed → CrmEntityVectorStore.upsert（crm_entity
 * 集合）。payload 对齐冻结 metadata 约定（B4）：entityType(String→keyword) 固定 {@code
 * CONTACT_TASK}；resourceId/ownerUserId/ownerDeptId/companyId/ opportunityId(Long→long
 * match)、title(String 展示补充键)；null 值不入 payload（键缺失即无值，过滤时自然不命中）。
 *
 * <p>ownerUserId 取 {@code contact_task.assignee_id}（任务执行人；页面列表 SELF/DEPT 档判权字段同源，执行人为业务主归属锚）；
 * ownerDeptId 为 assignee_id 反查 sys_user.dept_id 的反范式缓存（DEPT 档过滤依赖），assignee 不存在时该键缺省。
 *
 * <p>关联语义段：客户公司名（company_id 反查 customer_company.company_name）、商机名（opportunity_id 反查
 * sales_opportunity.opportunity_name）、联系人姓名（contact_id 反查 customer_contact.name）、执行人姓名 （assignee_id
 * 反查 sys_user.name）；相关 mapper 缺失（评测/单测注入 null）时对应段跳过，payload 与 pointId 派生规则零改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactTaskIngestionService {

  /**
   * payload entityType 固定值（冻结 metadata 约定：String→keyword match）；public——业务检索实现
   * （BusinessRetrievalService）同源引用，防双处字面量漂移。
   */
  public static final String ENTITY_TYPE = "CONTACT_TASK";

  /** 全量回埋批大小（id 游标分页）。 */
  private static final int REINGEST_BATCH_SIZE = 200;

  private final EmbeddingService embeddingService;

  private final CrmEntityVectorStore vectorStore;

  private final UserMapper userMapper;

  private final ContactTaskMapper contactTaskMapper;

  private final CustomerCompanyMapper customerCompanyMapper;

  private final SalesOpportunityMapper salesOpportunityMapper;

  private final CustomerContactMapper customerContactMapper;

  /** 全量回埋结果（任务 3.2 端点返回）：total=扫描条数、success/failure=摄取成功/失败计数。 */
  public record ReingestResult(int total, int success, int failure) {}

  /**
   * 单条摄取：拼语义文本（含富文本任务内容）→ 嵌入 → upsert crm_entity。失败直接抛出（调用方 hook 捕获 WARN 不阻断业务，回埋端点兜底）。
   *
   * @param entity 跟进任务实体（需含 id 与语义字段）
   */
  public void ingest(ContactTaskEntity entity) {
    if (entity == null || entity.getId() == null) {
      throw new IllegalArgumentException("跟进任务实体或 id 不能为空");
    }
    String text =
        ContactTaskSemanticTextSupport.buildSemanticText(
            entity,
            resolveCompanyName(entity.getCompanyId()),
            resolveOpportunityName(entity.getOpportunityId()),
            resolveContactName(entity.getContactId()),
            resolveUserName(entity.getAssigneeId()));
    if (text.isBlank()) {
      throw new IllegalArgumentException("跟进任务语义文本为空，跳过嵌入（id=" + entity.getId() + "）");
    }
    Long ownerDeptId = resolveOwnerDeptId(entity.getAssigneeId());
    float[] vector = embeddingService.embed(text);
    vectorStore.upsert(
        new VectorRecord(
            ContactTaskSemanticTextSupport.pointId(entity.getId()),
            ContactTaskSemanticTextSupport.documentId(entity.getId()),
            ContactTaskSemanticTextSupport.documentId(entity.getId()),
            0,
            text,
            vector,
            buildPayload(entity, ownerDeptId)));
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

  /** 关联联系人姓名（contact_id 为空或查无此联系人返回 null）。 */
  private String resolveContactName(Long contactId) {
    String result = null;
    if (contactId != null && customerContactMapper != null) {
      CustomerContactEntity contact = customerContactMapper.selectById(contactId);
      result = contact == null ? null : contact.getName();
    }
    return result;
  }

  /** 执行人姓名（assignee_id 为空或查无此人返回 null）。 */
  private String resolveUserName(Long assigneeId) {
    String result = null;
    if (assigneeId != null && userMapper != null) {
      UserEntity assignee = userMapper.selectById(assigneeId);
      result = assignee == null ? null : assignee.getRealName();
    }
    return result;
  }

  /** assignee_id 反查 sys_user.dept_id（反范式缓存）；assignee 为空或查无此人返回 null。 */
  private Long resolveOwnerDeptId(Long assigneeId) {
    Long result = null;
    if (assigneeId != null && userMapper != null) {
      UserEntity assignee = userMapper.selectById(assigneeId);
      result = assignee == null ? null : assignee.getDeptId();
    }
    return result;
  }

  /**
   * payload 键集：entityType 固定值 + 业务/权限/关联元数据；null 值不入键（键缺失即无值，过滤时自然不命中）。
   *
   * @param entity 跟进任务实体
   * @param ownerDeptId 执行人部门反范式缓存（可空）
   * @return payload
   */
  private Map<String, Object> buildPayload(ContactTaskEntity entity, Long ownerDeptId) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("entityType", ENTITY_TYPE);
    payload.put("resourceId", entity.getId());
    if (entity.getAssigneeId() != null) {
      payload.put("ownerUserId", entity.getAssigneeId());
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
    if (entity.getTaskTitle() != null && !entity.getTaskTitle().isBlank()) {
      payload.put("title", entity.getTaskTitle().trim());
    }
    return payload;
  }

  /**
   * 删除向量：documentId = CONTACT_TASK:{id}（删除任务时同步失效）。
   *
   * @param id 任务主键
   */
  public void deleteByTaskId(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("跟进任务 id 不能为空");
    }
    vectorStore.deleteByDocumentId(ContactTaskSemanticTextSupport.documentId(id));
  }

  /**
   * 全量回埋（任务 3.2）：分页游标（id 递增）逐条重嵌；单条失败计数不中断，返回成功/失败计数。
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
      List<ContactTaskEntity> batch =
          contactTaskMapper.selectList(
              new LambdaQueryWrapper<ContactTaskEntity>()
                  .gt(ContactTaskEntity::getId, lastId)
                  .orderByAsc(ContactTaskEntity::getId)
                  .last("LIMIT " + REINGEST_BATCH_SIZE));
      if (batch.isEmpty()) {
        break;
      }
      for (ContactTaskEntity entity : batch) {
        total++;
        lastId = entity.getId();
        try {
          ingest(entity);
          success++;
        } catch (Exception e) {
          failure++;
          log.warn("跟进任务语义全量回埋单条失败（id={}），计入失败计数", entity.getId(), e);
        }
      }
    }
    return new ReingestResult(total, success, failure);
  }
}
