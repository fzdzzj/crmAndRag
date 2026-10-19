package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.SalesOpportunityDTO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.impl.SalesOpportunityServiceImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 销售机会服务单元测试——阶段跃迁审批阻断（harden-oppo-stage-approval 任务 2.1 四向）。
 *
 * <p>复用审批域既有流转合法性口径 {@code SalesStageApprovalSupport#isValidStageTransition} 判定「需审批阶段对」，
 * 目标阶段需审批且发生跃迁时校验已通过审批记录（approval_status=1），缺失拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("销售机会服务-阶段跃迁审批阻断")
class SalesOpportunityServiceTest {

  @Mock private SalesOpportunityMapper salesOpportunityMapper;
  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;
  @Mock private ApprovalAttachmentService approvalAttachmentService;
  @Mock private ForeignKeyDeleteUtil foreignKeyDeleteUtil;
  @InjectMocks private SalesOpportunityServiceImpl salesOpportunityService;

  @BeforeEach
  void injectBaseMapper() {
    ReflectionTestUtils.setField(salesOpportunityService, "baseMapper", salesOpportunityMapper);
  }

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("跃迁至需审批阶段且存在已通过审批记录时放行")
  void shouldAllowStageTransitionWhenApprovedRecordCoversTargetStage() {
    authenticateAs(1001L);
    long opportunityId = 2001L;
    when(salesOpportunityMapper.selectById(opportunityId))
        .thenReturn(opportunity(opportunityId, 1));
    // 已通过审批记录覆盖目标阶段 2（approval_status=1）
    when(salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(opportunityId))
        .thenReturn(List.of(approvedRecord(opportunityId, 2)));
    when(salesOpportunityMapper.updateById(any(SalesOpportunityEntity.class))).thenReturn(1);

    boolean updated = salesOpportunityService.update(dto(opportunityId, 2));

    assertTrue(updated);
    verify(salesOpportunityMapper).updateById(any(SalesOpportunityEntity.class));
  }

  @Test
  @DisplayName("跃迁至需审批阶段但无已通过审批记录时拒绝")
  void shouldRejectStageTransitionWhenNoApprovedRecordCoversTargetStage() {
    authenticateAs(1001L);
    long opportunityId = 2002L;
    when(salesOpportunityMapper.selectById(opportunityId))
        .thenReturn(opportunity(opportunityId, 1));
    // 无任何已通过审批记录
    when(salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(opportunityId))
        .thenReturn(List.of());

    BaseException ex =
        assertThrows(
            BaseException.class, () -> salesOpportunityService.update(dto(opportunityId, 2)));

    // 错误码复用审批域阶段流转失败语义 OPPORTUNITY_MUST_BE_CLOSED
    assertEquals(ErrorCode.OPPORTUNITY_MUST_BE_CLOSED.getCode(), ex.getCode());
    verify(salesOpportunityMapper, never()).updateById(any(SalesOpportunityEntity.class));
  }

  @Test
  @DisplayName("非需审批阶段变更（非合法正向流转）放行")
  void shouldAllowNonApprovalRequiredStageChange() {
    authenticateAs(1001L);
    long opportunityId = 2003L;
    // 当前阶段 2，目标阶段 1（倒流，按审批域口径不算需审批的合法正向流转）
    when(salesOpportunityMapper.selectById(opportunityId))
        .thenReturn(opportunity(opportunityId, 2));
    when(salesOpportunityMapper.updateById(any(SalesOpportunityEntity.class))).thenReturn(1);

    boolean updated = salesOpportunityService.update(dto(opportunityId, 1));

    assertTrue(updated);
    // 非需审批流转不查询已通过审批记录
    verify(salesStageApprovalMapper, never())
        .selectApprovedApprovalsByOpportunityId(any(Long.class));
    verify(salesOpportunityMapper).updateById(any(SalesOpportunityEntity.class));
  }

  @Test
  @DisplayName("stage 未变时放行")
  void shouldAllowWhenStageUnchanged() {
    authenticateAs(1001L);
    long opportunityId = 2004L;
    when(salesOpportunityMapper.selectById(opportunityId))
        .thenReturn(opportunity(opportunityId, 3));
    when(salesOpportunityMapper.updateById(any(SalesOpportunityEntity.class))).thenReturn(1);

    boolean updated = salesOpportunityService.update(dto(opportunityId, 3));

    assertTrue(updated);
    verify(salesStageApprovalMapper, never())
        .selectApprovedApprovalsByOpportunityId(any(Long.class));
    verify(salesOpportunityMapper).updateById(any(SalesOpportunityEntity.class));
  }

  @Test
  @DisplayName("删除商机：先按 (model_name=approval_attachment, approvalIds) 收集附件并 canonical 删除，再走级联")
  void shouldDeleteStageApprovalAttachmentsBeforeCascade() {
    long opportunityId = 3001L;
    when(salesOpportunityMapper.selectById(opportunityId))
        .thenReturn(opportunity(opportunityId, 5));

    SalesStageApprovalEntity first = new SalesStageApprovalEntity();
    first.setId(41L);
    SalesStageApprovalEntity second = new SalesStageApprovalEntity();
    second.setId(42L);
    when(salesStageApprovalMapper.selectAllApprovalsByOpportunityId(opportunityId))
        .thenReturn(List.of(first, second));

    ApprovalAttachmentEntity attachment = new ApprovalAttachmentEntity();
    attachment.setId(71L);
    when(approvalAttachmentService.listEntitiesByAndIds(
            List.of(41L, 42L), ModelName.APPROVAL_ATTACHMENT))
        .thenReturn(List.of(attachment));
    when(salesOpportunityMapper.logicalDeleteById(opportunityId)).thenReturn(Boolean.TRUE);

    assertTrue(salesOpportunityService.delete(opportunityId));

    // 补删必须走 canonical removeByIds（model 判别 + 磁盘清理），且必须发生在级联物理删审批之前
    InOrder order = inOrder(approvalAttachmentService, foreignKeyDeleteUtil);
    order
        .verify(approvalAttachmentService)
        .listEntitiesByAndIds(List.of(41L, 42L), ModelName.APPROVAL_ATTACHMENT);
    order
        .verify(approvalAttachmentService)
        .removeByIds(List.of(71L), ModelName.APPROVAL_ATTACHMENT);
    order
        .verify(foreignKeyDeleteUtil)
        .deleteCascade(SalesOpportunityEntity.class, List.of(opportunityId), 0);
  }

  @Test
  @DisplayName("删除商机：无阶段审批时不做附件补删")
  void shouldSkipAttachmentCleanupWhenNoStageApprovals() {
    long opportunityId = 3002L;
    when(salesOpportunityMapper.selectById(opportunityId))
        .thenReturn(opportunity(opportunityId, 5));
    when(salesStageApprovalMapper.selectAllApprovalsByOpportunityId(opportunityId))
        .thenReturn(List.of());
    when(salesOpportunityMapper.logicalDeleteById(opportunityId)).thenReturn(Boolean.TRUE);

    assertTrue(salesOpportunityService.delete(opportunityId));

    verify(approvalAttachmentService, never()).listEntitiesByAndIds(any(), any());
    verify(foreignKeyDeleteUtil)
        .deleteCascade(SalesOpportunityEntity.class, List.of(opportunityId), 0);
  }

  private SalesOpportunityDTO dto(Long id, int stage) {
    SalesOpportunityDTO dto = new SalesOpportunityDTO();
    dto.setId(id);
    dto.setStage(stage);
    return dto;
  }

  private SalesOpportunityEntity opportunity(Long id, int stage) {
    SalesOpportunityEntity entity = new SalesOpportunityEntity();
    entity.setId(id);
    entity.setStage(stage);
    return entity;
  }

  private SalesStageApprovalEntity approvedRecord(Long opportunityId, int targetStage) {
    SalesStageApprovalEntity entity = new SalesStageApprovalEntity();
    entity.setOpportunityId(opportunityId);
    entity.setTargetStage(targetStage);
    entity.setApprovalStatus(1);
    return entity;
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }
}
