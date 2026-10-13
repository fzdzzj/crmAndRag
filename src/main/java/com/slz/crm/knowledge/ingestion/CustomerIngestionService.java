package com.slz.crm.knowledge.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 客户公司语义摄取服务（add-customer-semantic-retrieval 任务 2.1 / 2.2；add-customer-contact-retrieval 任务 1.2）。
 *
 * <p>单条链路：拼语义文本（{@link CustomerSemanticTextSupport}，含所属联系人附属语义段）→ EmbeddingService.embed →
 * CrmEntityVectorStore.upsert（crm_entity 集合）。payload 五键对齐冻结 metadata 约定（B4）：
 * entityType(String→keyword)、resourceId/ownerUserId/ownerDeptId(Long→long match)、companyName(String
 * 补充键)； null 值不入 payload（键缺失即无值，过滤时自然不命中）。
 *
 * <p>ownerDeptId 为 owner_id 反查 sys_user.dept_id 的反范式缓存（DEPT 档过滤依赖）；owner 不存在时 该键缺省。
 *
 * <p>联系人附属语义段（add-customer-contact-retrieval 任务 1.2）：单条摄取时查所属 contact 列表（is_deleted=0、 按 id 升序、上限 5
 * 人）拼入语义文本；customerContactMapper 缺失（部分评测/单测场景注入 null）时跳过联系人段、 仅拼公司文本，payload 与 pointId 派生规则零改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerIngestionService {

  /**
   * payload entityType 固定值（冻结 metadata 约定：String→keyword match）； 任务 3.1 起改
   * public——业务检索实现（BusinessRetrievalService）同源引用，防双处字面量漂移。
   */
  public static final String ENTITY_TYPE = "CUSTOMER_COMPANY";

  /** 全量回埋批大小（id 游标分页）。 */
  private static final int REINGEST_BATCH_SIZE = 200;

  private final EmbeddingService embeddingService;

  private final CrmEntityVectorStore vectorStore;

  private final UserMapper userMapper;

  private final CustomerCompanyMapper customerCompanyMapper;

  /** 联系人来源（add-customer-contact-retrieval 任务 1.2；缺失时跳过联系人段，payload/pointId 规则不变）。 */
  private final CustomerContactMapper customerContactMapper;

  /** 全量回埋结果（任务 2.2 端点返回）：total=扫描条数、success/failure=摄取成功/失败计数。 */
  public record ReingestResult(int total, int success, int failure) {}

  /**
   * 单条摄取：拼语义文本（含联系人段）→ 嵌入 → upsert crm_entity。失败直接抛出（调用方 hook 捕获 WARN 不阻断业务， 回埋端点兜底）。
   *
   * @param entity 客户公司实体（需含 id 与语义字段）
   */
  public void ingest(CustomerCompanyEntity entity) {
    if (entity == null || entity.getId() == null) {
      throw new IllegalArgumentException("客户公司实体或 id 不能为空");
    }
    String text =
        CustomerSemanticTextSupport.buildSemanticText(entity, loadContacts(entity.getId()));
    if (text.isBlank()) {
      throw new IllegalArgumentException("客户公司语义文本为空，跳过嵌入（id=" + entity.getId() + "）");
    }
    Long ownerDeptId = resolveOwnerDeptId(entity.getOwnerId());
    float[] vector = embeddingService.embed(text);
    vectorStore.upsert(
        new VectorRecord(
            CustomerSemanticTextSupport.pointId(entity.getId()),
            CustomerSemanticTextSupport.documentId(entity.getId()),
            CustomerSemanticTextSupport.documentId(entity.getId()),
            0,
            text,
            vector,
            buildPayload(entity, ownerDeptId)));
  }

  /**
   * 加载公司所属联系人列表（add-customer-contact-retrieval 任务 1.2）：is_deleted=0、按 id 升序、上限 5 人
   * （防语义膨胀保险丝）。customerContactMapper 缺失（评测/单测注入 null）返回空列表——跳过联系人段，payload 与 pointId 派生规则零改动。
   *
   * @param companyId 客户公司主键
   * @return 有序联系人列表（可能为空）
   */
  private List<CustomerContactEntity> loadContacts(Long companyId) {
    List<CustomerContactEntity> result = List.of();
    if (customerContactMapper != null && companyId != null) {
      result =
          customerContactMapper.selectList(
              new LambdaQueryWrapper<CustomerContactEntity>()
                  .eq(CustomerContactEntity::getCompanyId, companyId)
                  .eq(CustomerContactEntity::getIsDeleted, false)
                  .orderByAsc(CustomerContactEntity::getId)
                  .last("LIMIT " + CustomerSemanticTextSupport.CONTACT_SEGMENT_MAX));
    }
    return result;
  }

  /** payload 五键（任务 2.1）：null 值不入键（键缺失即无值，过滤时自然不命中）。 */
  private Map<String, Object> buildPayload(CustomerCompanyEntity entity, Long ownerDeptId) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("entityType", ENTITY_TYPE);
    payload.put("resourceId", entity.getId());
    if (entity.getOwnerId() != null) {
      payload.put("ownerUserId", entity.getOwnerId());
    }
    if (ownerDeptId != null) {
      payload.put("ownerDeptId", ownerDeptId);
    }
    if (entity.getCompanyName() != null && !entity.getCompanyName().isBlank()) {
      payload.put("companyName", entity.getCompanyName().trim());
    }
    return payload;
  }

  /** owner_id 反查 sys_user.dept_id（反范式缓存）；owner 为空或查无此人返回 null。 */
  private Long resolveOwnerDeptId(Long ownerId) {
    Long result = null;
    if (ownerId != null) {
      UserEntity owner = userMapper.selectById(ownerId);
      result = owner == null ? null : owner.getDeptId();
    }
    return result;
  }

  /**
   * 删除向量：documentId = CUSTOMER_COMPANY:{id}（软删/物理删/合并失效均走此口径）。
   *
   * @param id 客户公司主键
   */
  public void deleteByCompanyId(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("客户公司 id 不能为空");
    }
    vectorStore.deleteByDocumentId(CustomerSemanticTextSupport.documentId(id));
  }

  /**
   * 全量回埋（任务 2.2）：分页游标（id 递增、is_deleted=0）逐条重嵌；单条失败计数不中断， 返回成功/失败计数。
   *
   * @return 回埋结果
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  // 豁免理由（add-customer-semantic-retrieval 任务 4.3）：回埋契约=单条失败计数不中断，失败类型不可枚举
  // （嵌入外呼/向量库/拼文本均可能抛非受检异常），逐条 catch Exception 记失败计数由端点返回，不吞中断语义。
  public ReingestResult reingestAll() {
    int total = 0;
    int success = 0;
    int failure = 0;
    long lastId = 0L;
    while (true) {
      List<CustomerCompanyEntity> batch =
          customerCompanyMapper.selectList(
              new LambdaQueryWrapper<CustomerCompanyEntity>()
                  .eq(CustomerCompanyEntity::getIsDeleted, false)
                  .gt(CustomerCompanyEntity::getId, lastId)
                  .orderByAsc(CustomerCompanyEntity::getId)
                  .last("LIMIT " + REINGEST_BATCH_SIZE));
      if (batch.isEmpty()) {
        break;
      }
      for (CustomerCompanyEntity entity : batch) {
        total++;
        lastId = entity.getId();
        try {
          ingest(entity);
          success++;
        } catch (Exception e) {
          failure++;
          log.warn("客户语义全量回埋单条失败（id={}），计入失败计数", entity.getId(), e);
        }
      }
    }
    return new ReingestResult(total, success, failure);
  }
}
