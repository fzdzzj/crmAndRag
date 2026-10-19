package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.impl.AttachmentAccessServiceImpl;
import com.slz.crm.server.service.impl.ProjectFileServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 附件上传写权限越权拦截专项测试（harden-security-attachment-reset-and-invoice 任务 1.4，纯 JVM Mockito）。
 *
 * <p>覆盖两层防线：服务层 {@code uploadByActivity}/{@code uploadByOrder} 对 {@code canWriteAttachments} 判
 * 定结果的放行/拦截（12002 且零落盘零落库）；以及真实 {@link AttachmentAccessServiceImpl} 对 {@code
 * contract_order_item}（订单项经合同归属）与 {@code business_activity}（创建人）的记录级写权限路由。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectFileUploadSecurityTest 附件上传越权拦截专项测试")
class ProjectFileUploadSecurityTest {

  private static final Long CURRENT_USER_ID = 7L;
  private static final Long ACTIVITY_ID = 100L;
  private static final Long ORDER_ID = 200L;
  private static final Long CONTRACT_ID = 300L;

  @Mock private ProjectFileMapper projectFileMapper;

  @Mock private AttachmentAccessService attachmentAccessService;

  @Mock private BusinessActivityMapper businessActivityMapper;

  @Mock private ContractOrderItemMapper contractOrderItemMapper;

  @Mock private ContractMapper contractMapper;

  @Mock private UserMapper userMapper;

  @Mock private DataConvertService dataConvertService;

  @Mock private AttachmentDownloadTokenUtil downloadTokenUtil;

  @Mock private HttpServletRequest request;

  // 真实 AttachmentAccessServiceImpl 路由判定所需的其余依赖（路由用例只消费其中部分）
  @Mock private AssistRequestMapper assistRequestMapper;

  @Mock private BusinessActivityUserMapper businessActivityUserMapper;

  @Mock private ContactTaskMapper contactTaskMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;

  @Mock private PermissionService permissionService;

  @TempDir private Path tempDir;

  private ProjectFileServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new ProjectFileServiceImpl();
    ReflectionTestUtils.setField(service, "baseMapper", projectFileMapper);
    ReflectionTestUtils.setField(service, "attachmentAccessService", attachmentAccessService);
    ReflectionTestUtils.setField(service, "businessActivityMapper", businessActivityMapper);
    ReflectionTestUtils.setField(service, "contractOrderItemMapper", contractOrderItemMapper);
    ReflectionTestUtils.setField(service, "dataConvertService", dataConvertService);
    ReflectionTestUtils.setField(service, "downloadTokenUtil", downloadTokenUtil);
    ReflectionTestUtils.setField(service, "request", request);
    ReflectionTestUtils.setField(service, "basePath", tempDir.toString());
    RoleAO role = new RoleAO();
    role.setId(CURRENT_USER_ID);
    BaseUnit.setCurrentRole(role);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("1. 越权上传业务活动：canWriteAttachments 为 false 时拦截 12002 且零落盘零落库")
  void uploadByActivityDeniedWhenCannotWrite() {
    when(businessActivityMapper.selectById(ACTIVITY_ID)).thenReturn(activityOf(ACTIVITY_ID, 9L));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID))
        .thenReturn(false);

    BaseException thrown =
        assertThrows(
            BaseException.class, () -> service.uploadByActivity(ACTIVITY_ID, List.of(dtoOf())));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), thrown.getCode());
    assertEquals("无权向该业务活动上传附件", thrown.getMessage());
    verifyNoInteractions(projectFileMapper);
    assertFalse(tempDir.resolve(ModelName.PROJECT_FILE).toFile().exists(), "拦截路径不得创建任何落盘目录");
  }

  @Test
  @DisplayName("2. 越权上传订单项：canWriteAttachments 为 false 时拦截 12002 且零落盘零落库")
  void uploadByOrderDeniedWhenCannotWrite() {
    when(contractOrderItemMapper.selectById(ORDER_ID))
        .thenReturn(orderItemOf(ORDER_ID, CONTRACT_ID));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID))
        .thenReturn(false);

    BaseException thrown =
        assertThrows(BaseException.class, () -> service.uploadByOrder(ORDER_ID, List.of(dtoOf())));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), thrown.getCode());
    assertEquals("无权向该订单上传附件", thrown.getMessage());
    verifyNoInteractions(projectFileMapper);
    assertFalse(tempDir.resolve(ModelName.PROJECT_FILE).toFile().exists(), "拦截路径不得创建任何落盘目录");
  }

  @Test
  @DisplayName("3. 合法上传业务活动：放行后写盘+落库，上传人绑定当前登录用户")
  void uploadByActivityAllowedForAuthorizedUser() {
    when(businessActivityMapper.selectById(ACTIVITY_ID))
        .thenReturn(activityOf(ACTIVITY_ID, CURRENT_USER_ID));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID))
        .thenReturn(true);
    when(projectFileMapper.insert(any(ProjectFileEntity.class))).thenReturn(1);

    service.uploadByActivity(ACTIVITY_ID, List.of(dtoOf()));

    ArgumentCaptor<ProjectFileEntity> captor = ArgumentCaptor.forClass(ProjectFileEntity.class);
    verify(projectFileMapper, times(1)).insert(captor.capture());
    assertEquals(CURRENT_USER_ID, captor.getValue().getUploaderId(), "上传人必须是当前登录用户");
    assertEquals(ACTIVITY_ID, captor.getValue().getActivityId());
    File storedDir =
        tempDir.resolve(ModelName.PROJECT_FILE).resolve(String.valueOf(ACTIVITY_ID)).toFile();
    assertTrue(storedDir.isDirectory() && storedDir.listFiles().length == 1, "文件应真实落盘一次");
  }

  @Test
  @DisplayName("4. 合法上传订单项：放行后写盘+落库并绑定订单与合同")
  void uploadByOrderAllowedForAuthorizedUser() {
    when(contractOrderItemMapper.selectById(ORDER_ID))
        .thenReturn(orderItemOf(ORDER_ID, CONTRACT_ID));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID))
        .thenReturn(true);
    when(projectFileMapper.insert(any(ProjectFileEntity.class))).thenReturn(1);

    service.uploadByOrder(ORDER_ID, List.of(dtoOf()));

    ArgumentCaptor<ProjectFileEntity> captor = ArgumentCaptor.forClass(ProjectFileEntity.class);
    verify(projectFileMapper, times(1)).insert(captor.capture());
    assertEquals(CURRENT_USER_ID, captor.getValue().getUploaderId());
    assertEquals(ORDER_ID, captor.getValue().getOrderId());
    assertEquals(CONTRACT_ID, captor.getValue().getContractId());
  }

  @Test
  @DisplayName("5. 路由判定：合同负责人可向订单项上传（contract_order_item 经合同 ownerId 放行）")
  void routingContractOwnerCanWriteOrderItemUploads() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    stubActiveNonAdmin();
    when(contractOrderItemMapper.selectById(ORDER_ID))
        .thenReturn(orderItemOf(ORDER_ID, CONTRACT_ID));
    when(contractMapper.selectById(CONTRACT_ID))
        .thenReturn(contractOf(CONTRACT_ID, CURRENT_USER_ID, 9L));

    assertTrue(
        realAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID));
  }

  @Test
  @DisplayName("6. 路由判定：合同创建人可向订单项上传（creatorId 放行）")
  void routingContractCreatorCanWriteOrderItemUploads() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    stubActiveNonAdmin();
    when(contractOrderItemMapper.selectById(ORDER_ID))
        .thenReturn(orderItemOf(ORDER_ID, CONTRACT_ID));
    when(contractMapper.selectById(CONTRACT_ID))
        .thenReturn(contractOf(CONTRACT_ID, 5L, CURRENT_USER_ID));

    assertTrue(
        realAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID));
  }

  @Test
  @DisplayName("7. 路由判定：与合同无关的用户不得向订单项上传")
  void routingUnrelatedUserCannotWriteOrderItemUploads() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    stubActiveNonAdmin();
    when(contractOrderItemMapper.selectById(ORDER_ID))
        .thenReturn(orderItemOf(ORDER_ID, CONTRACT_ID));
    when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contractOf(CONTRACT_ID, 5L, 6L));

    assertFalse(
        realAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID));
  }

  @Test
  @DisplayName("8. 路由判定：订单项不存在时 fail-closed 拒绝")
  void routingMissingOrderItemCannotWrite() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    stubActiveNonAdmin();
    when(contractOrderItemMapper.selectById(ORDER_ID)).thenReturn(null);

    assertFalse(
        realAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID));
  }

  @Test
  @DisplayName("9. 路由判定：超管直通，不做业务记录级判定")
  void routingSuperAdminBypassesOrderItemRecordCheck() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    UserEntity admin = activeNonAdmin();
    admin.setRoleId(1L);
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(admin);

    assertTrue(
        realAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID));
    verifyNoInteractions(contractOrderItemMapper, contractMapper);
  }

  @Test
  @DisplayName("10. 路由判定：业务活动创建人可上传（business_activity 创建人放行）")
  void routingActivityCreatorCanWriteActivityUploads() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    stubActiveNonAdmin();
    when(businessActivityMapper.selectById(ACTIVITY_ID))
        .thenReturn(activityOf(ACTIVITY_ID, CURRENT_USER_ID));

    assertTrue(
        realAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID));
  }

  @Test
  @DisplayName("11. 路由判定：非创建人不得向业务活动上传")
  void routingNonCreatorActivityDenied() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    stubActiveNonAdmin();
    when(businessActivityMapper.selectById(ACTIVITY_ID)).thenReturn(activityOf(ACTIVITY_ID, 9L));

    assertFalse(
        realAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID));
  }

  @Test
  @DisplayName("12. 路由判定：非在职用户 fail-closed 整体拒绝")
  void routingInactiveUserDenied() {
    AttachmentAccessServiceImpl realAccessService = realAccessService();
    UserEntity inactive = activeNonAdmin();
    inactive.setStatus(0);
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(inactive);

    assertFalse(
        realAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID));
  }

  /** 真实授权服务（含 AttachmentModelScopeChecker 路由），依赖与本测试类的 mock 一致。 */
  private AttachmentAccessServiceImpl realAccessService() {
    return new AttachmentAccessServiceImpl(
        assistRequestMapper,
        businessActivityMapper,
        businessActivityUserMapper,
        contactTaskMapper,
        salesOpportunityMapper,
        contractMapper,
        contractOrderItemMapper,
        salesStageApprovalMapper,
        userMapper,
        permissionService,
        new ObjectMapper());
  }

  private void stubActiveNonAdmin() {
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(activeNonAdmin());
  }

  private UserEntity activeNonAdmin() {
    UserEntity user = new UserEntity();
    user.setId(CURRENT_USER_ID);
    user.setRoleId(2L);
    user.setStatus(1);
    return user;
  }

  private BusinessActivityEntity activityOf(Long id, Long creatorId) {
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(id);
    activity.setCreatorId(creatorId);
    return activity;
  }

  private ContractOrderItemEntity orderItemOf(Long id, Long contractId) {
    ContractOrderItemEntity item = new ContractOrderItemEntity();
    item.setId(id);
    item.setContractId(contractId);
    return item;
  }

  private ContractEntity contractOf(Long id, Long ownerId, Long creatorId) {
    ContractEntity contract = new ContractEntity();
    contract.setId(id);
    contract.setOwnerId(ownerId);
    contract.setCreatorId(creatorId);
    return contract;
  }

  private ProjectFileDTO dtoOf() {
    ProjectFileDTO dto = new ProjectFileDTO();
    dto.setCategory("VISIT_RECORD");
    dto.setFileData(
        new MockMultipartFile(
            "fileData", "evidence.txt", "text/plain", "upload-security-probe".getBytes()));
    return dto;
  }
}
