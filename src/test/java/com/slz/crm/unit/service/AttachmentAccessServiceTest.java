package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.impl.AttachmentAccessServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttachmentAccessServiceTest {

  @Mock private AssistRequestMapper assistRequestMapper;
  @Mock private BusinessActivityMapper businessActivityMapper;
  @Mock private BusinessActivityUserMapper businessActivityUserMapper;
  @Mock private ContactTaskMapper contactTaskMapper;
  @Mock private SalesOpportunityMapper salesOpportunityMapper;
  @Mock private ContractMapper contractMapper;
  @Mock private ContractOrderItemMapper contractOrderItemMapper;
  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;
  @Mock private UserMapper userMapper;
  @Mock private PermissionService permissionService;
  @Spy private ObjectMapper objectMapper = new ObjectMapper();
  @InjectMocks private AttachmentAccessServiceImpl service;

  @Test
  @DisplayName("仅有业务活动模块权限但没有活动记录权限时拒绝附件读取")
  void rejectsActivityAttachmentOutsideRecordScope() {
    UserEntity user = user(2L, 2L);
    when(userMapper.selectById(2L)).thenReturn(user);
    when(permissionService.hasPermission(2L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY))
        .thenReturn(true);
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setCreatorId(8L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);
    when(businessActivityUserMapper.existsByActivityIdAndUserId(100L, 2L)).thenReturn(0);

    ApprovalAttachmentEntity attachment = attachment(1L, 100L, "business_activity");

    assertThrows(BaseException.class, () -> service.assertCanRead(attachment, 2L));
  }

  @Test
  @DisplayName("活动创建人可在无协助关系时读取自己的附件")
  void allowsActivityCreatorWithoutAssistRelationship() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    when(permissionService.hasPermission(2L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY))
        .thenReturn(true);
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setCreatorId(2L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);

    assertDoesNotThrow(() -> service.assertCanRead(attachment(1L, 100L, "business_activity"), 2L));
  }

  @Test
  @DisplayName("仅协助身份不能通过普通活动附件下载入口")
  void rejectsActivityAttachmentForAssistParticipantOnOrdinaryEntry() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    when(permissionService.hasPermission(2L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY))
        .thenReturn(false);

    ApprovalAttachmentEntity attachment = attachment(1L, 100L, "business_activity");

    assertThrows(BaseException.class, () -> service.assertCanRead(attachment, 2L));
    verify(permissionService).hasPermission(2L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY);
  }

  @Test
  @DisplayName("普通活动附件写权限只给活动创建人，不因协助关系放行")
  void allowsOnlyActivityCreatorToWriteOrdinaryAttachments() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setCreatorId(2L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);

    assertDoesNotThrow(
        () -> {
          if (!service.canWriteAttachments("business_activity", 100L, 2L)) {
            throw new BaseException("活动创建人不应被拒绝");
          }
        });
    assertThrows(
        BaseException.class,
        () -> {
          if (!service.canWriteAttachments("business_activity", 100L, 8L)) {
            throw new BaseException("无关用户不应获得普通附件写权限");
          }
        });
  }

  @Test
  @DisplayName("普通联络任务附件写权限给创建人、指派人和执行人")
  void allowsContactTaskResponsibleUsersToWriteOrdinaryAttachments() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    var task = new com.slz.crm.pojo.entity.ContactTaskEntity();
    task.setId(101L);
    task.setCreatorId(8L);
    task.setAssignerId(2L);
    task.setAssigneeId(9L);
    when(contactTaskMapper.selectById(101L)).thenReturn(task);

    org.junit.jupiter.api.Assertions.assertTrue(
        service.canWriteAttachments("contact_task", 101L, 2L));
    org.junit.jupiter.api.Assertions.assertFalse(
        service.canWriteAttachments("contact_task", 101L, 7L));
  }

  @Test
  @DisplayName("待协助活动附件必须带协助上下文才可下载")
  void allowsPendingAssistSourceAttachmentWithAssistContext() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(50L);
    assist.setModelName("business_activity");
    assist.setRecordId(100L);
    assist.setApplicantId(8L);
    assist.setAssistUserId(2L);
    assist.setAssistStatus(0);
    when(assistRequestMapper.selectById(50L)).thenReturn(assist);

    assertDoesNotThrow(
        () ->
            service.assertCanReadAssistSource(attachment(1L, 100L, "business_activity"), 50L, 2L));
  }

  @Test
  @DisplayName("任务执行人可下载本条待协助任务的来源附件")
  void allowsTaskAssigneeToReadPendingAssistSourceAttachment() {
    when(userMapper.selectById(7L)).thenReturn(user(7L, 2L));
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(51L);
    assist.setModelName(ModelName.CONTACT_TASK);
    assist.setRecordId(101L);
    assist.setApplicantId(8L);
    assist.setAssistUserId(9L);
    assist.setAssistStatus(0);
    when(assistRequestMapper.selectById(51L)).thenReturn(assist);
    var task = new com.slz.crm.pojo.entity.ContactTaskEntity();
    task.setId(101L);
    task.setAssigneeId(7L);
    when(contactTaskMapper.selectById(101L)).thenReturn(task);

    assertDoesNotThrow(
        () ->
            service.assertCanReadAssistSource(
                attachment(1L, 101L, ModelName.CONTACT_TASK), 51L, 7L));
  }

  @Test
  @DisplayName("终态协助申请人或协助人仍可读取交付物附件")
  void allowsTerminalAssistDeliveryAttachmentForParticipant() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    when(assistRequestMapper.selectCount(any())).thenReturn(1L);

    assertDoesNotThrow(() -> service.assertCanRead(attachment(30L, 20L, "assist_request"), 2L));
  }

  @Test
  @DisplayName("交付物附件不能进入历史业务快照下载链路")
  void rejectsDeliveryAttachmentAsHistoricalSnapshotAttachment() {
    assertThrows(
        BaseException.class,
        () -> service.assertCanReadHistorical(attachment(30L, 20L, "assist_request"), 20L, 2L));
  }

  @Test
  @DisplayName("协助结束后不能再按协助关系读取审批附件")
  void rejectsTerminalApprovalAssistAttachment() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));

    ApprovalAttachmentEntity attachment = attachment(1L, 10L, "approval_attachment");

    assertThrows(BaseException.class, () -> service.assertCanRead(attachment, 2L));
    verify(permissionService).hasPermission(anyLong(), any(PermissionOperates.class));
  }

  @Test
  @DisplayName("终态协助仅可下载冻结快照中登记的历史附件")
  void allowsHistoricalAttachmentOnlyWhenItBelongsToTerminalSnapshot() {
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(20L);
    assist.setApplicantId(8L);
    assist.setAssistUserId(2L);
    assist.setAssistStatus(1);
    assist.setRecordSnapshot(
        """
                {"record":{"attachments":[{"attachmentId":10,"modelName":"business_activity","recordId":100}]}}
                """);
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    when(assistRequestMapper.selectById(20L)).thenReturn(assist);

    assertDoesNotThrow(
        () -> service.assertCanReadHistorical(attachment(10L, 100L, "business_activity"), 20L, 2L));
    assertThrows(
        BaseException.class,
        () -> service.assertCanReadHistorical(attachment(11L, 100L, "business_activity"), 20L, 2L));
  }

  @Test
  @DisplayName("判定矩阵：实时协助来源附件拒绝非参与人（申请人/协助人/超管/任务参与人之外）")
  void rejectsAssistSourceAttachmentForOutsider() {
    when(userMapper.selectById(7L)).thenReturn(user(7L, 2L));
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(52L);
    assist.setModelName(ModelName.CONTACT_TASK);
    assist.setRecordId(101L);
    assist.setApplicantId(8L);
    assist.setAssistUserId(9L);
    assist.setAssistStatus(0);
    when(assistRequestMapper.selectById(52L)).thenReturn(assist);
    var task = new com.slz.crm.pojo.entity.ContactTaskEntity();
    task.setId(101L);
    task.setAssigneeId(2L);
    when(contactTaskMapper.selectById(101L)).thenReturn(task);

    assertThrows(
        BaseException.class,
        () ->
            service.assertCanReadAssistSource(
                attachment(1L, 101L, ModelName.CONTACT_TASK), 52L, 7L));
  }

  @Test
  @DisplayName("判定矩阵：参与人读实时来源附件，但附件挂错记录时拒绝")
  void rejectsAssistSourceAttachmentNotBelongingToAssist() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(53L);
    assist.setModelName("business_activity");
    assist.setRecordId(100L);
    assist.setApplicantId(8L);
    assist.setAssistUserId(2L);
    assist.setAssistStatus(0);
    when(assistRequestMapper.selectById(53L)).thenReturn(assist);

    // 附件挂在 contact_task/101，与协助冻结的 business_activity/100 不一致
    assertThrows(
        BaseException.class,
        () ->
            service.assertCanReadAssistSource(
                attachment(1L, 101L, ModelName.CONTACT_TASK), 53L, 2L));
  }

  @Test
  @DisplayName("判定矩阵：历史附件拒绝非参与人（非超管/申请人/协助人）")
  void rejectsHistoricalAttachmentForOutsider() {
    when(userMapper.selectById(7L)).thenReturn(user(7L, 2L));
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(21L);
    assist.setApplicantId(8L);
    assist.setAssistUserId(9L);
    assist.setAssistStatus(1);
    assist.setRecordSnapshot("[{\"attachmentId\":10}]");
    when(assistRequestMapper.selectById(21L)).thenReturn(assist);

    assertThrows(
        BaseException.class,
        () -> service.assertCanReadHistorical(attachment(10L, 100L, "business_activity"), 21L, 7L));
  }

  @Test
  @DisplayName("判定矩阵：待协助状态走历史入口被拒，必须回实时授权")
  void rejectsHistoricalEntryForPendingAssist() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(22L);
    assist.setApplicantId(8L);
    assist.setAssistUserId(2L);
    assist.setAssistStatus(0);
    when(assistRequestMapper.selectById(22L)).thenReturn(assist);

    assertThrows(
        BaseException.class,
        () -> service.assertCanReadHistorical(attachment(10L, 100L, "business_activity"), 22L, 2L));
  }

  private ApprovalAttachmentEntity attachment(Long id, Long andId, String modelName) {
    ApprovalAttachmentEntity attachment = new ApprovalAttachmentEntity();
    attachment.setId(id);
    attachment.setAndId(andId);
    attachment.setModelName(modelName);
    return attachment;
  }

  @Test
  @DisplayName("项目文件：业务活动参与人可读")
  void allowsProjectFileForActivityParticipant() {
    when(userMapper.selectById(2L)).thenReturn(user(2L, 2L));
    when(permissionService.hasPermission(2L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY))
        .thenReturn(true);
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setCreatorId(8L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);
    when(businessActivityUserMapper.existsByActivityIdAndUserId(100L, 2L)).thenReturn(1);

    ProjectFileEntity file = projectFile(1L, 100L, null, null, null);

    assertTrue(service.canReadProjectFile(file, 2L));
  }

  @Test
  @DisplayName("项目文件：商机负责人可读")
  void allowsProjectFileForOpportunityOwner() {
    when(userMapper.selectById(3L)).thenReturn(user(3L, 2L));
    when(permissionService.hasPermission(3L, PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY))
        .thenReturn(true);
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(200L);
    opportunity.setOwnerId(3L);
    when(salesOpportunityMapper.selectById(200L)).thenReturn(opportunity);

    ProjectFileEntity file = projectFile(1L, null, 200L, null, null);

    assertTrue(service.canReadProjectFile(file, 3L));
  }

  @Test
  @DisplayName("项目文件：订单维度经订单项反查合同后由合同负责人可读")
  void allowsProjectFileForContractOwnerViaOrder() {
    when(userMapper.selectById(4L)).thenReturn(user(4L, 2L));
    when(permissionService.hasPermission(4L, PermissionOperates.SALES_VIEW_CONTRACT))
        .thenReturn(true);
    ContractOrderItemEntity orderItem = new ContractOrderItemEntity();
    orderItem.setId(300L);
    orderItem.setContractId(400L);
    when(contractOrderItemMapper.selectById(300L)).thenReturn(orderItem);
    ContractEntity contract = new ContractEntity();
    contract.setId(400L);
    contract.setOwnerId(4L);
    when(contractMapper.selectById(400L)).thenReturn(contract);

    ProjectFileEntity file = projectFile(1L, null, null, 400L, 300L);

    assertTrue(service.canReadProjectFile(file, 4L));
  }

  @Test
  @DisplayName("项目文件：无关用户所有维度均不命中时拒绝")
  void rejectsProjectFileForUnrelatedUser() {
    when(userMapper.selectById(5L)).thenReturn(user(5L, 2L));
    when(permissionService.hasPermission(5L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY))
        .thenReturn(true);
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setCreatorId(8L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);
    when(businessActivityUserMapper.existsByActivityIdAndUserId(100L, 5L)).thenReturn(0);

    ProjectFileEntity file = projectFile(1L, 100L, null, null, null);

    assertFalse(service.canReadProjectFile(file, 5L));
  }

  @Test
  @DisplayName("项目文件：独立上传文件仅上传人本人可见，超管豁免")
  void standaloneProjectFileOnlyUploaderAndSuperAdmin() {
    // 上传人本人可读
    when(userMapper.selectById(9L)).thenReturn(user(9L, 2L));
    ProjectFileEntity file = projectFile(1L, null, null, null, null);
    file.setUploaderId(9L);
    assertTrue(service.canReadProjectFile(file, 9L));

    // 其他用户不可读
    when(userMapper.selectById(6L)).thenReturn(user(6L, 2L));
    assertFalse(service.canReadProjectFile(file, 6L));

    // 超管豁免
    when(userMapper.selectById(1L)).thenReturn(user(1L, 1L));
    assertTrue(service.canReadProjectFile(file, 1L));
  }

  @Test
  @DisplayName(
      "项目文件：维度权限按判定时刻的当前用户（userId）判定，不复用早期读取的旧 roleId（update-project-file-list-auth-hotpath 安全等价修复）")
  void projectFileDimensionPermissionUsesCurrentUserRole() {
    // 早期状态闸读到用户 2 的 roleId=77（非常规值），但维度权限判定必须仍按 userId=2 实时取当前角色，
    // 不得复用旧 roleId=77——否则角色被改派后会按旧角色放行（安全等价回归）。
    when(userMapper.selectById(2L)).thenReturn(user(2L, 77L));
    when(permissionService.hasPermission(2L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY))
        .thenReturn(true);
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setCreatorId(8L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);
    when(businessActivityUserMapper.existsByActivityIdAndUserId(100L, 2L)).thenReturn(1);

    ProjectFileEntity file = projectFile(1L, 100L, null, null, null);

    assertTrue(service.canReadProjectFile(file, 2L));
    verify(permissionService).hasPermission(2L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY);
    verify(permissionService, never())
        .hasPermission(77L, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY);
  }

  private ProjectFileEntity projectFile(
      Long id, Long activityId, Long opportunityId, Long contractId, Long orderId) {
    ProjectFileEntity file = new ProjectFileEntity();
    file.setId(id);
    file.setActivityId(activityId);
    file.setOpportunityId(opportunityId);
    file.setContractId(contractId);
    file.setOrderId(orderId);
    return file;
  }

  private UserEntity user(Long id, Long roleId) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setRoleId(roleId);
    user.setStatus(1);
    return user;
  }
}
