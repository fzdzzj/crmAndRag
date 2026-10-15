package com.slz.crm.knowledge.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 销售合同语义摄取服务（add-unified-business-semantic-retrieval 任务 3.1，对齐 {@link
 * SalesOpportunityIngestionService} 先例）。
 *
 * <p>单条链路：拼语义文本（{@link ContractSemanticTextSupport}，含合同基础信息 + 订单产品明细附属段 + 回款履约附属段） →
 * EmbeddingService.embed → CrmEntityVectorStore.upsert（crm_entity 集合）。payload 对齐冻结 metadata 约定（B4）：
 * entityType(String→keyword) 固定 {@code
 * CONTRACT}；resourceId/ownerUserId/ownerDeptId/companyId(Long→long match)、title(String 展示补充键)；null
 * 值不入 payload（键缺失即无值，过滤时自然不命中）。
 *
 * <p>ownerUserId 取 {@code contract.owner_id}（负责人；ResourceTypeConstant 登记的 contract 用户字段
 * creator_id+owner_id，owner 为业务主归属锚，对齐商机口径）；ownerDeptId 为 owner_id 反查 sys_user.dept_id 的反范式缓存（DEPT
 * 档过滤依赖），owner 不存在时该键缺省。
 *
 * <p>关联语义段：客户公司名（company_id 反查 customer_company.company_name）、商机名（opportunity_id 反查
 * sales_opportunity.opportunity_name）、负责人名（owner_id 反查 sys_user.name）；相关 mapper 缺失（评测/单测注入
 * null）时对应段跳过，payload 与 pointId 派生规则零改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractIngestionService {

  /**
   * payload entityType 固定值（冻结 metadata 约定：String→keyword match）；public——业务检索实现
   * （BusinessRetrievalService）同源引用，防双处字面量漂移。
   */
  public static final String ENTITY_TYPE = "CONTRACT";

  /** 全量回埋批大小（id 游标分页）。 */
  private static final int REINGEST_BATCH_SIZE = 200;

  private final EmbeddingService embeddingService;

  private final CrmEntityVectorStore vectorStore;

  private final UserMapper userMapper;

  private final ContractMapper contractMapper;

  private final ContractOrderItemMapper contractOrderItemMapper;

  private final PaymentRecordMapper paymentRecordMapper;

  private final CustomerCompanyMapper customerCompanyMapper;

  private final SalesOpportunityMapper salesOpportunityMapper;

  /** 全量回埋结果（任务 3.1 端点返回）：total=扫描条数、success/failure=摄取成功/失败计数。 */
  public record ReingestResult(int total, int success, int failure) {}

  /**
   * 单条摄取：拼语义文本（含订单明细/回款附属段）→ 嵌入 → upsert crm_entity。失败直接抛出（调用方 hook 捕获 WARN 不阻断业务，回埋端点兜底）。
   *
   * @param entity 合同实体（需含 id 与语义字段）
   */
  public void ingest(ContractEntity entity) {
    if (entity == null || entity.getId() == null) {
      throw new IllegalArgumentException("合同实体或 id 不能为空");
    }
    String text =
        ContractSemanticTextSupport.buildSemanticText(
            entity,
            resolveCompanyName(entity.getCompanyId()),
            resolveOpportunityName(entity.getOpportunityId()),
            resolveUserName(entity.getOwnerId()),
            ContractSemanticTextSupport.buildOrderItemSegment(loadOrderItems(entity.getId())),
            ContractSemanticTextSupport.buildPaymentSegment(loadPayments(entity.getId())));
    if (text.isBlank()) {
      throw new IllegalArgumentException("合同语义文本为空，跳过嵌入（id=" + entity.getId() + "）");
    }
    Long ownerDeptId = resolveOwnerDeptId(entity.getOwnerId());
    float[] vector = embeddingService.embed(text);
    vectorStore.upsert(
        new VectorRecord(
            ContractSemanticTextSupport.pointId(entity.getId()),
            ContractSemanticTextSupport.documentId(entity.getId()),
            ContractSemanticTextSupport.documentId(entity.getId()),
            0,
            text,
            vector,
            buildPayload(entity, ownerDeptId)));
  }

  /**
   * 加载合同订单明细（id 升序、上限 {@link ContractSemanticTextSupport#SEGMENT_MAX} 条）；mapper 缺失（评测/单测注入 null）或合同
   * id 为空返回空列表——跳过明细段，payload 与 pointId 派生规则零改动。
   *
   * @param contractId 合同主键
   * @return 有序订单明细列表（可能为空）
   */
  private List<ContractOrderItemEntity> loadOrderItems(Long contractId) {
    List<ContractOrderItemEntity> result = List.of();
    if (contractOrderItemMapper != null && contractId != null) {
      result =
          contractOrderItemMapper.selectList(
              new LambdaQueryWrapper<ContractOrderItemEntity>()
                  .eq(ContractOrderItemEntity::getContractId, contractId)
                  .eq(ContractOrderItemEntity::getIsDeleted, 0)
                  .orderByAsc(ContractOrderItemEntity::getId)
                  .last("LIMIT " + ContractSemanticTextSupport.SEGMENT_MAX));
    }
    return result;
  }

  /**
   * 加载合同回款记录（id 升序、上限 {@link ContractSemanticTextSupport#SEGMENT_MAX} 条）；mapper 缺失（评测/单测注入 null）或合同
   * id 为空返回空列表——跳过回款段，payload 与 pointId 派生规则零改动。
   *
   * @param contractId 合同主键
   * @return 有序回款记录列表（可能为空）
   */
  private List<PaymentRecordEntity> loadPayments(Long contractId) {
    List<PaymentRecordEntity> result = List.of();
    if (paymentRecordMapper != null && contractId != null) {
      result =
          paymentRecordMapper.selectList(
              new LambdaQueryWrapper<PaymentRecordEntity>()
                  .eq(PaymentRecordEntity::getContractId, contractId)
                  .orderByAsc(PaymentRecordEntity::getId)
                  .last("LIMIT " + ContractSemanticTextSupport.SEGMENT_MAX));
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

  /** 负责人姓名（owner_id 为空或查无此人返回 null）。 */
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
   * @param entity 合同实体
   * @param ownerDeptId 负责人部门反范式缓存（可空）
   * @return payload
   */
  private Map<String, Object> buildPayload(ContractEntity entity, Long ownerDeptId) {
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
    if (entity.getContractName() != null && !entity.getContractName().isBlank()) {
      payload.put("title", entity.getContractName().trim());
    }
    return payload;
  }

  /**
   * 删除向量：documentId = CONTRACT:{id}（删除合同时同步失效）。
   *
   * @param id 合同主键
   */
  public void deleteByContractId(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("合同 id 不能为空");
    }
    vectorStore.deleteByDocumentId(ContractSemanticTextSupport.documentId(id));
  }

  /**
   * 全量回埋（任务 3.1）：分页游标（id 递增）逐条重嵌；单条失败计数不中断，返回成功/失败计数。
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
      List<ContractEntity> batch =
          contractMapper.selectList(
              new LambdaQueryWrapper<ContractEntity>()
                  .gt(ContractEntity::getId, lastId)
                  .orderByAsc(ContractEntity::getId)
                  .last("LIMIT " + REINGEST_BATCH_SIZE));
      if (batch.isEmpty()) {
        break;
      }
      for (ContractEntity entity : batch) {
        total++;
        lastId = entity.getId();
        try {
          ingest(entity);
          success++;
        } catch (Exception e) {
          failure++;
          log.warn("合同语义全量回埋单条失败（id={}），计入失败计数", entity.getId(), e);
        }
      }
    }
    return new ReingestResult(total, success, failure);
  }
}
