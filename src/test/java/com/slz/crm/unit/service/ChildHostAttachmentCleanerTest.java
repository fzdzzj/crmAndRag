package com.slz.crm.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.impl.ChildHostAttachmentCleaner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 物理级联删前附件清理的单测（fix-backlog-batch4 任务 1.2）。
 *
 * <p><b>锁什么</b>：审批域附件（{@code and_id} = 审批行 id）在<b>公司/联系人</b>两入口必须被清理，且判别值只能是 {@code
 * ModelName.APPROVAL_ATTACHMENT}——审批行与商机是两级关系，审批行的附件上传时以 {@code approval_attachment}
 * 落库；商机入口<b>不得</b>重复接线 （{@code SalesOpportunityServiceImpl#deleteStageApprovalAttachments}
 * 先例已覆盖），否则同一次删除要多跑一轮 select + 删除 IO。
 *
 * <p><b>桩的口径</b>：本类被测的是「按哪个外键列取哪个子集」，因此 selectObjs 的桩按 {@code QueryWrapper} 的 SQL 片段里的列名分桶，而不是按调用次数
 * 顺序——同一 mapper 在公司入口会以 {@code company_id} / {@code contact_id} 两种列被查，顺序桩会把断言变成对实现调用序的锁定。
 */
@DisplayName("级联删前附件清理：审批域补齐与商机入口不重复接线")
@ExtendWith(MockitoExtension.class)
class ChildHostAttachmentCleanerTest {

  private static final long COMPANY_ID = 11L;

  private static final long CONTACT_ID = 22L;

  private static final long OPPORTUNITY_ID = 33L;

  private static final long APPROVAL_ID = 44L;

  private static final long ACTIVITY_ID = 55L;

  private static final long TASK_ID = 66L;

  @Mock private BusinessActivityMapper businessActivityMapper;

  @Mock private ContactTaskMapper contactTaskMapper;

  @Mock private CustomerContactMapper customerContactMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;

  @Mock private ApprovalAttachmentService approvalAttachmentService;

  @InjectMocks private ChildHostAttachmentCleaner cleaner;

  @Test
  @DisplayName("公司入口：按 opportunities 反查审批行，以 approval_attachment 为判别值清理")
  void cleanBeforeCompanyPhysicalDelete_removesStageApprovalAttachments() {
    stubIdsByColumn(customerContactMapper, Map.of("company_id", List.of(CONTACT_ID)));
    stubIdsByColumn(
        salesOpportunityMapper,
        Map.of("company_id", List.of(OPPORTUNITY_ID), "contact_id", List.of()));
    stubIdsByColumn(salesStageApprovalMapper, Map.of("opportunity_id", List.of(APPROVAL_ID)));
    stubIdsByColumn(
        businessActivityMapper, Map.of("company_id", List.of(), "opportunity_id", List.of()));
    stubIdsByColumn(
        contactTaskMapper,
        Map.of("company_id", List.of(), "contact_id", List.of(), "opportunity_id", List.of()));

    cleaner.cleanBeforeCompanyPhysicalDelete(List.of(COMPANY_ID));

    verify(approvalAttachmentService)
        .removeByAndIds(List.of(APPROVAL_ID), ModelName.APPROVAL_ATTACHMENT);
    verifyNoMoreInteractions(approvalAttachmentService);
  }

  @Test
  @DisplayName("联系人入口：按 contact_id 下的商机反查审批行，以 approval_attachment 为判别值清理")
  void cleanBeforeContactPhysicalDelete_removesStageApprovalAttachments() {
    stubIdsByColumn(salesOpportunityMapper, Map.of("contact_id", List.of(OPPORTUNITY_ID)));
    stubIdsByColumn(salesStageApprovalMapper, Map.of("opportunity_id", List.of(APPROVAL_ID)));
    stubIdsByColumn(businessActivityMapper, Map.of("opportunity_id", List.of()));
    stubIdsByColumn(
        contactTaskMapper, Map.of("contact_id", List.of(), "opportunity_id", List.of()));

    cleaner.cleanBeforeContactPhysicalDelete(List.of(CONTACT_ID));

    verify(approvalAttachmentService)
        .removeByAndIds(List.of(APPROVAL_ID), ModelName.APPROVAL_ATTACHMENT);
    verifyNoMoreInteractions(approvalAttachmentService);
    // 联系人入口不查联系人表：联系人 id 是入参本身
    verifyNoInteractions(customerContactMapper);
  }

  @Test
  @DisplayName("商机入口：不碰审批域（先例 deleteStageApprovalAttachments 已覆盖，重复清理是冗余 IO）")
  void cleanBeforeOpportunityPhysicalDelete_doesNotTouchApprovalDomain() {
    stubIdsByColumn(businessActivityMapper, Map.of("opportunity_id", List.of(ACTIVITY_ID)));
    stubIdsByColumn(contactTaskMapper, Map.of("opportunity_id", List.of(TASK_ID)));

    cleaner.cleanBeforeOpportunityPhysicalDelete(List.of(OPPORTUNITY_ID));

    verify(approvalAttachmentService)
        .removeByAndIds(List.of(ACTIVITY_ID), ModelName.BUSINESS_ACTIVITY);
    verify(approvalAttachmentService).removeByAndIds(List.of(TASK_ID), ModelName.CONTACT_TASK);
    verify(approvalAttachmentService, never())
        .removeByAndIds(any(), eq(ModelName.APPROVAL_ATTACHMENT));
    verifyNoMoreInteractions(approvalAttachmentService);
    verifyNoInteractions(salesStageApprovalMapper);
  }

  @Test
  @DisplayName("审批行为空：不产生审批域删除调用（空集不动，避免空 IN 查询）")
  void cleanBeforeCompanyPhysicalDelete_noApprovals_leavesApprovalDomainUntouched() {
    stubIdsByColumn(customerContactMapper, Map.of("company_id", List.of()));
    stubIdsByColumn(
        salesOpportunityMapper,
        Map.of("company_id", List.of(OPPORTUNITY_ID), "contact_id", List.of()));
    stubIdsByColumn(salesStageApprovalMapper, Map.of("opportunity_id", List.of()));
    stubIdsByColumn(
        businessActivityMapper, Map.of("company_id", List.of(), "opportunity_id", List.of()));
    stubIdsByColumn(
        contactTaskMapper,
        Map.of("company_id", List.of(), "contact_id", List.of(), "opportunity_id", List.of()));

    cleaner.cleanBeforeCompanyPhysicalDelete(List.of(COMPANY_ID));

    verify(approvalAttachmentService, never()).removeByAndIds(any(), any());
    verifyNoMoreInteractions(approvalAttachmentService);
  }

  @Test
  @DisplayName("空入参：三入口都不触库、不动附件（服务层可能传 null/空表）")
  void emptyInputs_touchNothing() {
    cleaner.cleanBeforeCompanyPhysicalDelete(List.of());
    cleaner.cleanBeforeContactPhysicalDelete(null);
    cleaner.cleanBeforeOpportunityPhysicalDelete(List.of());

    verifyNoInteractions(
        businessActivityMapper,
        contactTaskMapper,
        customerContactMapper,
        salesOpportunityMapper,
        salesStageApprovalMapper,
        approvalAttachmentService);
  }

  /**
   * 按外键列名分桶的 selectObjs 桩：被测代码用 {@code wrapper.select("id").in(column, values)} 取 id，SQL 片段里带列名。
   *
   * @param mapper 目标 mapper（同一 mapper 可被不同入口用不同列查）
   * @param idsByColumn 列名 → 该列应返回的 id 集
   */
  private static <T> void stubIdsByColumn(
      BaseMapper<T> mapper, Map<String, List<Long>> idsByColumn) {
    Map<String, List<Long>> buckets = new LinkedHashMap<>(idsByColumn);
    when(mapper.selectObjs(any()))
        .thenAnswer(
            invocation -> {
              String segment = ((QueryWrapper<?>) invocation.getArgument(0)).getSqlSegment();
              return buckets.entrySet().stream()
                  .filter(entry -> segment != null && segment.contains(entry.getKey()))
                  .findFirst()
                  .map(Map.Entry::getValue)
                  .orElse(List.of());
            });
  }
}
