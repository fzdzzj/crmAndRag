package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 物理级联删前的子宿主多态附件清理（fix-backlog-batch3 任务 4.2 / fix-backlog-batch4 任务 1）。
 *
 * <p><b>为什么必须由调用方显式补删</b>：{@code approval_attachment} 是多态附件表（{@code and_id} + {@code model_name}
 * 判别）、无 {@code is_deleted} 列，删除语义是「DB 物理删 + 磁盘文件清理」；而 {@code ForeignKeyConfig} 的级联机器只能按单列外键递归， 既带不了
 * {@code model_name} 判别（裸 {@code and_id} 级联会误删其他模型同 id 的附件），也承载不了磁盘 IO。因此物理级联删的调用方在 {@code
 * deleteCascade} 前先用本类算出「将被级联删掉的子宿主 id 集」，再走 canonical {@link
 * ApprovalAttachmentService#removeByAndIds} 精确清理。
 *
 * <p><b>id 集口径</b>：与 {@code deleteCascade} 的递归图一致——直接子（{@code company_id}）+ 经联系人/商机间接挂上的活动与任务；
 * 宿主路径最多两层（公司→联系人/商机→活动/任务），两级展开即等价于递归闭包。
 *
 * <p><b>{@code sales_stage_approval} 域</b>（batch4）：审批行挂在商机下一层，附件以 {@code
 * model_name=approval_attachment} 落库（与 {@code
 * SalesOpportunityServiceImpl#deleteStageApprovalAttachments} 先例同口径），故公司/联系人两入口用已算出的 {@code
 * opportunities} 反查审批行补删；<b>商机入口不重复接线</b>（该先例在商机删除路径已覆盖，再删是冗余第二轮 IO）。
 *
 * <p><b>不做什么</b>：不动 {@code ForeignKeyConfig} 关联图（红线）；不做逐附件鉴权（调用方已完成业务对象级删除权校验）。
 */
@Slf4j
@Component
public class ChildHostAttachmentCleaner {

  /** 公司维度的外键列（与 {@code CUSTOMER_COMPANY_RELATIONS} 一致，此处只读引用、不改关联图）。 */
  private static final String COL_COMPANY_ID = "company_id";

  /** 联系人维度的外键列（与 {@code CUSTOMER_CONTACT_RELATIONS} 一致）。 */
  private static final String COL_CONTACT_ID = "contact_id";

  /** 商机维度的外键列（与 {@code SALES_OPPORTUNITY_RELATIONS} 一致）。 */
  private static final String COL_OPPORTUNITY_ID = "opportunity_id";

  private final BusinessActivityMapper businessActivityMapper;

  private final ContactTaskMapper contactTaskMapper;

  private final CustomerContactMapper customerContactMapper;

  private final SalesOpportunityMapper salesOpportunityMapper;

  private final SalesStageApprovalMapper salesStageApprovalMapper;

  private final ApprovalAttachmentService approvalAttachmentService;

  /** 构造注入（三处调用方各持一份，生产单例）。 */
  public ChildHostAttachmentCleaner(
      BusinessActivityMapper businessActivityMapper,
      ContactTaskMapper contactTaskMapper,
      CustomerContactMapper customerContactMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      SalesStageApprovalMapper salesStageApprovalMapper,
      ApprovalAttachmentService approvalAttachmentService) {
    this.businessActivityMapper = businessActivityMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.customerContactMapper = customerContactMapper;
    this.salesOpportunityMapper = salesOpportunityMapper;
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.approvalAttachmentService = approvalAttachmentService;
  }

  /**
   * 公司物理级联删前的附件清理入口（{@code CustomerCompanyServiceImpl#deleteByIds}）。
   *
   * @param companyIds 待物理删除的公司 id 清单（可为 null/空，空则整体跳过）
   */
  public void cleanBeforeCompanyPhysicalDelete(List<Long> companyIds) {
    List<Long> companies = safe(companyIds);
    // 公司 → 联系人；公司 → 商机；联系人 → 商机（联系人名下商机随联系人级联删）
    List<Long> contacts = selectIds(customerContactMapper, COL_COMPANY_ID, companies);
    List<Long> opportunities =
        merge(
            selectIds(salesOpportunityMapper, COL_COMPANY_ID, companies),
            selectIds(salesOpportunityMapper, COL_CONTACT_ID, contacts));
    // 商机 → 审批行（二级），审批行的附件随审批行级联删后成孤儿
    removeStageApprovalAttachments(opportunities);
    // 活动/任务的三个来源：直接挂公司、挂联系人名下任务、挂商机（含联系人名下商机）
    removeHostAttachments(
        merge(
            selectIds(businessActivityMapper, COL_COMPANY_ID, companies),
            selectIds(businessActivityMapper, COL_OPPORTUNITY_ID, opportunities)),
        merge(
            selectIds(contactTaskMapper, COL_COMPANY_ID, companies),
            selectIds(contactTaskMapper, COL_CONTACT_ID, contacts),
            selectIds(contactTaskMapper, COL_OPPORTUNITY_ID, opportunities)));
  }

  /**
   * 联系人物理级联删前的附件清理入口（{@code CustomerContactServiceImpl#deleteByIds}）。
   *
   * @param contactIds 待物理删除的联系人 id 清单（可为 null/空，空则整体跳过）
   */
  public void cleanBeforeContactPhysicalDelete(List<Long> contactIds) {
    List<Long> contacts = safe(contactIds);
    // 联系人 → 商机；商机 → 活动/任务；联系人 → 任务（contact_id 直挂）
    List<Long> opportunities = selectIds(salesOpportunityMapper, COL_CONTACT_ID, contacts);
    // 商机 → 审批行（二级），审批行的附件随审批行级联删后成孤儿
    removeStageApprovalAttachments(opportunities);
    removeHostAttachments(
        selectIds(businessActivityMapper, COL_OPPORTUNITY_ID, opportunities),
        merge(
            selectIds(contactTaskMapper, COL_CONTACT_ID, contacts),
            selectIds(contactTaskMapper, COL_OPPORTUNITY_ID, opportunities)));
  }

  /**
   * 商机物理级联删前的附件清理入口（{@code SalesOpportunityServiceImpl#delete}）。
   *
   * @param opportunityIds 待物理删除的商机 id 清单（可为 null/空，空则整体跳过）
   */
  public void cleanBeforeOpportunityPhysicalDelete(List<Long> opportunityIds) {
    List<Long> opportunities = safe(opportunityIds);
    removeHostAttachments(
        selectIds(businessActivityMapper, COL_OPPORTUNITY_ID, opportunities),
        selectIds(contactTaskMapper, COL_OPPORTUNITY_ID, opportunities));
  }

  /**
   * 清理销售阶段审批域附件（{@code and_id} = 审批行 id，{@code model_name = approval_attachment}）。
   *
   * <p>只被公司/联系人两入口调用；商机入口由 {@code SalesOpportunityServiceImpl#deleteStageApprovalAttachments}
   * 先例覆盖，见类注释「{@code sales_stage_approval} 域附件的归属」。
   *
   * @param opportunities 已算出的商机 id 一级集（为空则不产生任何 SQL）
   */
  private void removeStageApprovalAttachments(List<Long> opportunities) {
    List<Long> approvalIds = selectIds(salesStageApprovalMapper, COL_OPPORTUNITY_ID, opportunities);
    if (!approvalIds.isEmpty()) {
      log.info("物理级联删前清理销售阶段审批附件，审批记录数：{}", approvalIds.size());
      approvalAttachmentService.removeByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT);
    }
  }

  /** 按模型判别清理两类子宿主的多态附件（含磁盘文件，空集不动）。 */
  private void removeHostAttachments(List<Long> activityIds, List<Long> taskIds) {
    if (!activityIds.isEmpty()) {
      log.info("物理级联删前清理商业活动附件，活动数：{}", activityIds.size());
      approvalAttachmentService.removeByAndIds(activityIds, ModelName.BUSINESS_ACTIVITY);
    }
    if (!taskIds.isEmpty()) {
      log.info("物理级联删前清理联络任务附件，任务数：{}", taskIds.size());
      approvalAttachmentService.removeByAndIds(taskIds, ModelName.CONTACT_TASK);
    }
  }

  /** 按单列外键收集子表 id（空 values 直接返回空表，不产生任何 SQL）。 */
  private <T> List<Long> selectIds(BaseMapper<T> mapper, String column, List<Long> values) {
    final List<Long> result;
    if (values.isEmpty()) {
      result = List.of();
    } else {
      QueryWrapper<T> wrapper = new QueryWrapper<>();
      wrapper.select("id").in(column, values);
      result =
          mapper.selectObjs(wrapper).stream()
              .filter(Objects::nonNull)
              .map(value -> ((Number) value).longValue())
              .toList();
    }
    return result;
  }

  /** 多组 id 合并去重（保序），避免同一子宿主被重复清理。 */
  @SafeVarargs
  private static List<Long> merge(List<Long>... groups) {
    Set<Long> merged = new LinkedHashSet<>();
    for (List<Long> group : groups) {
      merged.addAll(group);
    }
    return new ArrayList<>(merged);
  }

  /** null 归一为空表（三处调用方的入参在服务层可能为 null）。 */
  private static List<Long> safe(List<Long> ids) {
    return ids == null ? List.of() : ids;
  }
}
