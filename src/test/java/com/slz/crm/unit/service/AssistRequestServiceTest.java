package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistMessageService;
import com.slz.crm.server.service.AssistRelatedRecordResolver;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.impl.AssistRequestServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** 协助申请服务单元测试：创建/可见性/处理/分页/级联删除。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("协助申请服务")
class AssistRequestServiceTest {

  @BeforeAll
  static void initializeAssistRequestTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), AssistRequestEntity.class);
  }

  @Mock private UserMapper userMapper;

  @Mock private DataConvertService dataConvertService;

  @Mock private PermissionService permissionService;

  @Mock private AssistRequestMapper assistRequestMapper;

  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;

  @Mock private BusinessActivityMapper businessActivityMapper;

  @Mock private BusinessActivityUserMapper businessActivityUserMapper;

  @Mock private ContactTaskMapper contactTaskMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @Mock private ApprovalAttachmentService approvalAttachmentService;

  @Mock private AttachmentDownloadTokenUtil downloadTokenUtil;

  @Mock private HttpServletRequest httpRequest;

  @Mock private AssistRelatedRecordResolver assistRelatedRecordResolver;

  @Mock private ObjectMapper objectMapper;

  @Mock private AssistMessageService assistMessageService;

  @Spy @InjectMocks private AssistRequestServiceImpl assistService;

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  // ===== createAssists =====

  @Test
  @DisplayName("空协助人列表应跳过，不产生任何写入")
  void createAssistsSkipsWhenNoAssistUsers() {
    assistService.createAssists("contact_task", 1L, 9L, null);
    assistService.createAssists("contact_task", 1L, 9L, Collections.emptyList());

    verifyNoInteractions(userMapper);
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("同步草稿协助人时删除前端移除的待协助记录")
  void updatePendingAssistsShouldDeleteRemovedPendingUsers() {
    AssistRequestEntity pending = assistEntity(10L, "sales_stage_approval", 100L, 3L);
    pending.setApplicantId(9L);
    pending.setAssistStatus(0);
    doReturn(List.of(pending)).when(assistService).list(any(LambdaQueryWrapper.class));
    doReturn(true).when(assistService).removeByIds(List.of(10L));

    assistService.updatePendingAssists("sales_stage_approval", 100L, 9L, Collections.emptyList());

    verify(approvalAttachmentService).removeByAndIds(List.of(10L), ModelName.ASSIST_REQUEST);
    verify(assistService).removeByIds(List.of(10L));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("创建协助人：同一协助人重复选择时明确拒绝")
  void createAssistsShouldRejectRepeatedAssistUsers() {
    authenticateAs(9L);
    UserEntity active = user(2L, "张三", 1);
    BaseException error =
        assertThrows(
            BaseException.class,
            () ->
                assistService.createAssists(
                    "business_activity", 100L, 9L, List.of(applyItem(2L), applyItem(2L))));

    assertTrue(error.getMessage().contains("不能重复选择"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("申请人不得把自己添加为协助人")
  void createAssistsShouldRejectApplicantAsAssistUser() {
    BaseException exception =
        assertThrows(
            BaseException.class,
            () ->
                assistService.createAssists("business_activity", 100L, 9L, List.of(applyItem(9L))));

    assertTrue(exception.getMessage().contains("协助人不能是申请人自己"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("协助人不存在或非在职时拒绝创建")
  void createAssistsRejectsInactiveUser() {
    authenticateAs(9L);
    // 3L 不在返回集合中（视为不存在/离职）
    doReturn(Collections.emptyList()).when(assistService).list(any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(2L, "张三", 1)));

    BaseException ex =
        assertThrows(
            BaseException.class,
            () ->
                assistService.createAssists(
                    "business_activity", 100L, 9L, List.of(applyItem(2L), applyItem(3L))));
    assertTrue(ex.getMessage().contains("协助人不存在或已冻结/离职"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("冻结用户（status=0）不能作为协助人")
  void createAssistsRejectsFrozenUser() {
    authenticateAs(9L);
    doReturn(Collections.emptyList()).when(assistService).list(any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(4L, "李四", 0)));

    assertThrows(
        BaseException.class,
        () -> assistService.createAssists("contact_task", 1L, 9L, List.of(applyItem(4L))));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("协助人已有待协助申请时拒绝重复发起（幂等拦截）")
  void createAssistsRejectsWhenPendingAssistExists() {
    authenticateAs(9L);
    // 同一业务记录上已存在该协助人的待协助记录（并发/重复提交场景）
    doReturn(List.of(assistEntity(10L, "business_activity", 100L, 2L)))
        .when(assistService)
        .list(any(LambdaQueryWrapper.class));

    BaseException ex =
        assertThrows(
            BaseException.class,
            () ->
                assistService.createAssists("business_activity", 100L, 9L, List.of(applyItem(2L))));
    assertTrue(ex.getMessage().contains("已有待协助申请"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  // ===== reapply =====

  @Test
  @DisplayName("驳回后只能向原协助人逐条重新申请，并保留父记录链")
  void reapplyShouldCreateChildForOriginalAssistUserOnly() {
    authenticateAs(9L);
    AssistRequestEntity rejected = assistEntity(10L, "contact_task", 100L, 3L);
    rejected.setAssistStatus(2);
    doReturn(rejected).when(assistService).getById(10L);
    doReturn(0L).when(assistService).count(any(LambdaQueryWrapper.class));
    when(userMapper.selectById(3L)).thenReturn(user(3L, "张明", 1));
    doAnswer(
            invocation -> {
              invocation.getArgument(0, AssistRequestEntity.class).setId(20L);
              return true;
            })
        .when(assistService)
        .save(any(AssistRequestEntity.class));

    Long id = assistService.reapply(10L, List.of(applyItem(3L)));

    ArgumentCaptor<AssistRequestEntity> captor = ArgumentCaptor.forClass(AssistRequestEntity.class);
    verify(assistService).save(captor.capture());
    AssistRequestEntity created = captor.getValue();
    assertAll(
        () -> assertEquals(20L, id),
        () -> assertEquals(10L, created.getParentId()),
        () -> assertEquals(3L, created.getAssistUserId()),
        () -> assertEquals(0, created.getAssistStatus()),
        () -> assertEquals("补充材料", created.getApplyPurpose()),
        () -> assertEquals("请补充所需材料", created.getApplyRequirement()));
  }

  @Test
  @DisplayName("驳回后重新申请不得更换协助人或一次发给多人")
  void reapplyShouldRejectDifferentOrMultipleAssistUsers() {
    authenticateAs(9L);
    AssistRequestEntity rejected = assistEntity(10L, "contact_task", 100L, 3L);
    rejected.setAssistStatus(2);
    doReturn(rejected).when(assistService).getById(10L);

    BaseException differentUser =
        assertThrows(BaseException.class, () -> assistService.reapply(10L, List.of(applyItem(4L))));
    BaseException multipleUsers =
        assertThrows(
            BaseException.class,
            () -> assistService.reapply(10L, List.of(applyItem(3L), applyItem(4L))));

    assertTrue(differentUser.getMessage().contains("原协助人"));
    assertTrue(multipleUsers.getMessage().contains("逐条发起"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("申请人可以在原记录上追加新协助人且不修改原记录")
  void appendAssistsShouldCreateAdditionalPendingRecord() {
    authenticateAs(9L);
    AssistRequestEntity original = assistEntity(10L, ModelName.BUSINESS_ACTIVITY, 100L, 3L);
    original.setApplicantId(9L);
    original.setAssistStatus(1);
    doReturn(original).when(assistService).getById(10L);
    doReturn(Collections.emptyList()).when(assistService).list(any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(4L, "李四", 1)));
    doAnswer(
            invocation -> {
              invocation.getArgument(0, AssistRequestEntity.class).setId(20L);
              return true;
            })
        .when(assistService)
        .save(any(AssistRequestEntity.class));

    assistService.appendAssists(10L, List.of(applyItem(4L)));

    ArgumentCaptor<AssistRequestEntity> captor = ArgumentCaptor.forClass(AssistRequestEntity.class);
    verify(assistService).save(captor.capture());
    AssistRequestEntity appended = captor.getValue();
    assertAll(
        () -> assertEquals(ModelName.BUSINESS_ACTIVITY, appended.getModelName()),
        () -> assertEquals(100L, appended.getRecordId()),
        () -> assertEquals(9L, appended.getApplicantId()),
        () -> assertEquals(4L, appended.getAssistUserId()),
        () -> assertEquals(0, appended.getAssistStatus()));
    verify(assistService, never()).updateById(original);
  }

  @Test
  @DisplayName("非申请人不能追加协助人")
  void appendAssistsShouldRejectNonApplicant() {
    authenticateAs(8L);
    AssistRequestEntity original = assistEntity(10L, ModelName.CONTACT_TASK, 100L, 3L);
    original.setApplicantId(9L);
    doReturn(original).when(assistService).getById(10L);

    BaseException exception =
        assertThrows(
            BaseException.class, () -> assistService.appendAssists(10L, List.of(applyItem(4L))));

    assertTrue(exception.getMessage().contains("原申请人"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("业务活动协助可以读取其明确绑定的联络任务")
  void getRelatedTaskShouldResolveTaskFromBusinessActivity() {
    authenticateAs(3L);
    AssistRequestEntity assist = assistEntity(10L, ModelName.BUSINESS_ACTIVITY, 100L, 3L);
    assist.setApplicantId(9L);
    assist.setAssistStatus(0);
    doReturn(assist).when(assistService).getById(10L);

    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setTaskId(200L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);

    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(200L);
    task.setPriority(1);
    task.setStatus(0);
    when(contactTaskMapper.selectById(200L)).thenReturn(task);

    ContactTaskVO result = assistService.getRelatedTask(10L);

    assertEquals(200L, result.getId());
    verify(contactTaskMapper).selectById(200L);
  }

  // ===== getRelatedRecordIdsByUser =====

  @Test
  @DisplayName("返回协助记录对应的业务记录ID集合")
  void getRelatedRecordIdsByUserReturnsOnlyOwnRecords() {
    AssistRequestEntity mine = assistEntity(1L, "sales_stage_approval", 10L, 9L);
    // 模拟 DB 已按 assistUserId 过滤后的结果
    doReturn(List.of(mine)).when(assistService).list(any(LambdaQueryWrapper.class));

    Set<Long> ids = assistService.getRelatedRecordIdsByUser("sales_stage_approval", 9L);

    assertEquals(Set.of(10L), ids);
    verify(assistService).list(any(LambdaQueryWrapper.class));
  }

  // ===== getVisibleAssists =====

  @Test
  @DisplayName("可见性：申请人可见全部，协助人仅见自己的，无关人不可见")
  void getVisibleAssistsFiltersByRole() {
    AssistVO mine = assistVO(1L, 9L, 2L);
    // 同一业务允许历史上由不同申请人发起；任一申请人仍应看到该业务全部协助记录。
    AssistVO other = assistVO(2L, 7L, 3L);
    doReturn(List.of(mine, other))
        .when(assistService)
        .listAssistsByRecord(eq("business_activity"), eq(100L));

    // 申请人 9L：可见全部
    List<AssistVO> asApplicant = assistService.getVisibleAssists("business_activity", 100L, 9L);
    assertEquals(2, asApplicant.size());

    // 协助人 2L：仅见自己的
    List<AssistVO> asAssist = assistService.getVisibleAssists("business_activity", 100L, 2L);
    assertEquals(1, asAssist.size());
    assertEquals(1L, asAssist.get(0).getId());

    // 无关人 8L：空
    List<AssistVO> asStranger = assistService.getVisibleAssists("business_activity", 100L, 8L);
    assertTrue(asStranger.isEmpty());
  }

  @Test
  @DisplayName("活动参与人可查看该活动全部协助记录")
  void activityParticipantCanSeeAllAssists() {
    AssistVO first = assistVO(1L, 9L, 2L);
    AssistVO second = assistVO(2L, 9L, 3L);
    doReturn(List.of(first, second))
        .when(assistService)
        .listAssistsByRecord(ModelName.BUSINESS_ACTIVITY, 100L);
    when(userMapper.selectById(8L)).thenReturn(user(8L, "活动执行人", 1));
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);
    when(businessActivityUserMapper.existsByActivityIdAndUserId(100L, 8L)).thenReturn(1);

    assertEquals(2, assistService.getVisibleAssists(ModelName.BUSINESS_ACTIVITY, 100L, 8L).size());
  }

  @Test
  @DisplayName("活动参与人和联络任务执行人可以发起协助，无关用户被拒绝")
  void applyAssistsAllowsBusinessExecutorsOnly() {
    AssistApplyItem item = new AssistApplyItem();
    item.setAssistUserId(20L);
    item.setApplyPurpose("补充材料");
    item.setApplyRequirement("请上传报价文件");

    authenticateAs(8L);
    when(userMapper.selectById(8L)).thenReturn(user(8L, "执行人", 1));
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);
    when(businessActivityUserMapper.existsByActivityIdAndUserId(100L, 8L)).thenReturn(1);
    doNothing()
        .when(assistService)
        .createAssists(ModelName.BUSINESS_ACTIVITY, 100L, 8L, List.of(item));

    assistService.applyAssists(ModelName.BUSINESS_ACTIVITY, 100L, List.of(item));

    verify(assistService).createAssists(ModelName.BUSINESS_ACTIVITY, 100L, 8L, List.of(item));

    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(200L);
    task.setAssigneeId(8L);
    when(contactTaskMapper.selectById(200L)).thenReturn(task);
    doNothing().when(assistService).createAssists(ModelName.CONTACT_TASK, 200L, 8L, List.of(item));

    assistService.applyAssists(ModelName.CONTACT_TASK, 200L, List.of(item));

    verify(assistService).createAssists(ModelName.CONTACT_TASK, 200L, 8L, List.of(item));

    authenticateAs(9L);
    when(userMapper.selectById(9L)).thenReturn(user(9L, "无关用户", 1));
    when(businessActivityUserMapper.existsByActivityIdAndUserId(100L, 9L)).thenReturn(0);

    assertThrows(
        BaseException.class,
        () -> assistService.applyAssists(ModelName.BUSINESS_ACTIVITY, 100L, List.of(item)));
  }

  @Test
  @DisplayName("业务活动协助按后端关联链补齐商机公司和主要联系人")
  void getRelatedRecordResolvesBusinessActivityContext() {
    authenticateAs(2L);
    AssistRequestEntity assist = assistEntity(1L, "business_activity", 100L, 2L);
    doReturn(assist).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    AssistRelatedRecordVO resolved = new AssistRelatedRecordVO();
    resolved.setOpportunityId(50L);
    resolved.setOpportunityName("北京科创年度服务采购");
    resolved.setCompanyId(60L);
    resolved.setContactId(70L);
    when(assistRelatedRecordResolver.resolve(assist)).thenReturn(resolved);
    when(dataConvertService.getCompanyName(60L)).thenReturn("北京科创");
    when(dataConvertService.getContactName(70L)).thenReturn("李华");

    AssistRelatedRecordVO related = assistService.getRelatedRecord(1L);

    assertAll(
        () -> assertEquals(50L, related.getOpportunityId()),
        () -> assertEquals("北京科创年度服务采购", related.getOpportunityName()),
        () -> assertEquals(60L, related.getCompanyId()),
        () -> assertEquals("北京科创", related.getCompanyName()),
        () -> assertEquals(70L, related.getContactId()),
        () -> assertEquals("李华", related.getContactName()));
  }

  @Test
  @DisplayName("终态协助和无关用户不能读取实时关联详情")
  void getRelatedRecordRejectsTerminalOrUnrelatedAccess() {
    AssistRequestEntity terminal = assistEntity(1L, "business_activity", 100L, 2L);
    terminal.setAssistStatus(1);
    doReturn(terminal).when(assistService).getById(1L);

    authenticateAs(2L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));
    BaseException terminalError =
        assertThrows(BaseException.class, () -> assistService.getRelatedRecord(1L));
    assertTrue(terminalError.getMessage().contains("协助已结束"));

    terminal.setAssistStatus(0);
    authenticateAs(8L);
    when(userMapper.selectById(8L)).thenReturn(user(8L, "无关用户", 1));
    assertThrows(BaseException.class, () -> assistService.getRelatedRecord(1L));
  }

  @Test
  @DisplayName("审批协助详情只能按审批来源 assistId 读取")
  void getRelatedApprovalChecksSourceModel() {
    authenticateAs(2L);
    AssistRequestEntity assist = assistEntity(1L, ModelName.SALES_STAGE_APPROVAL, 100L, 2L);
    doReturn(assist).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    SalesStageApprovalEntity approval = new SalesStageApprovalEntity();
    approval.setId(100L);
    approval.setOpportunityId(50L);
    approval.setCurrentStage(1);
    approval.setTargetStage(2);
    approval.setApprovalStatus(0);
    when(salesStageApprovalMapper.selectById(100L)).thenReturn(approval);
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(50L);
    opportunity.setOpportunityName("测试商机");
    when(salesOpportunityMapper.selectById(50L)).thenReturn(opportunity);

    assertEquals("测试商机", assistService.getRelatedApproval(1L).getOpportunityName());
    assertEquals("潜在商机", assistService.getRelatedApproval(1L).getCurrentStage());

    assist.setModelName(ModelName.BUSINESS_ACTIVITY);
    assertThrows(BaseException.class, () -> assistService.getRelatedApproval(1L));
  }

  @Test
  @DisplayName("活动和任务协助详情拒绝跨来源 recordId")
  void getRelatedActivityAndTaskCheckSourceModel() {
    authenticateAs(2L);
    AssistRequestEntity activityAssist = assistEntity(1L, ModelName.BUSINESS_ACTIVITY, 100L, 2L);
    doReturn(activityAssist).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setActivityTitle("客户拜访");
    activity.setCreatorId(9L);
    when(businessActivityMapper.selectById(100L)).thenReturn(activity);
    when(dataConvertService.getUserName(9L)).thenReturn("创建人");

    assertEquals("客户拜访", assistService.getRelatedActivity(1L).getActivityTitle());

    activityAssist.setModelName(ModelName.CONTACT_TASK);
    assertThrows(BaseException.class, () -> assistService.getRelatedActivity(1L));

    AssistRequestEntity taskAssist = assistEntity(2L, ModelName.CONTACT_TASK, 200L, 2L);
    doReturn(taskAssist).when(assistService).getById(2L);
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(200L);
    task.setTaskTitle("跟进客户");
    task.setPriority(3);
    task.setStatus(0);
    task.setCreatorId(9L);
    task.setAssigneeId(2L);
    when(contactTaskMapper.selectById(200L)).thenReturn(task);
    assertEquals("跟进客户", assistService.getRelatedTask(2L).getTaskTitle());
  }

  @Test
  @DisplayName("业务活动协助只能读取来源活动附件")
  void getRelatedActivityAttachmentsChecksActivityScope() {
    authenticateAs(2L);
    AssistRequestEntity assist = assistEntity(1L, "business_activity", 100L, 2L);
    doReturn(assist).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    BusinessActivityEntity sourceActivity = new BusinessActivityEntity();
    sourceActivity.setId(100L);
    sourceActivity.setOpportunityId(50L);
    when(businessActivityMapper.selectById(100L)).thenReturn(sourceActivity);

    ApprovalAttachmentVO attachment = new ApprovalAttachmentVO();
    when(approvalAttachmentService.getByAndIdsForAssist(List.of(100L), "business_activity", 1L))
        .thenReturn(List.of(attachment));

    assertEquals(List.of(attachment), assistService.getRelatedActivityAttachments(1L, 100L));
    assertThrows(BaseException.class, () -> assistService.getRelatedActivityAttachments(1L, 101L));
    assertThrows(BaseException.class, () -> assistService.getRelatedActivityAttachments(1L, 102L));
    verify(approvalAttachmentService).getByAndIdsForAssist(List.of(100L), "business_activity", 1L);
    verify(approvalAttachmentService, never())
        .getByAndIdsForAssist(List.of(101L), "business_activity", 1L);
    verify(approvalAttachmentService, never())
        .getByAndIdsForAssist(List.of(102L), "business_activity", 1L);
  }

  @Test
  @DisplayName("联络任务协助不能读取关联业务活动附件")
  void taskAssistCannotReadRelatedActivityAttachments() {
    authenticateAs(2L);
    AssistRequestEntity assist = assistEntity(2L, ModelName.CONTACT_TASK, 200L, 2L);
    doReturn(assist).when(assistService).getById(2L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    assertThrows(BaseException.class, () -> assistService.getRelatedActivityAttachments(2L, 201L));
  }

  @Test
  @DisplayName("待协助的联络任务可读取并上传来源任务附件")
  void pendingTaskAssistCanReadAndUploadSourceAttachments() {
    authenticateAs(2L);
    AssistRequestEntity assist = assistEntity(3L, ModelName.CONTACT_TASK, 200L, 2L);
    doReturn(assist).when(assistService).getById(3L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));
    ApprovalAttachmentVO attachment = new ApprovalAttachmentVO();
    when(approvalAttachmentService.getByAndIdsForAssist(List.of(200L), ModelName.CONTACT_TASK, 3L))
        .thenReturn(List.of(attachment));

    assertEquals(List.of(attachment), assistService.getRelatedTaskAttachments(3L));
    ApprovalAttachmentDTO dto = new ApprovalAttachmentDTO();
    dto.setFileName("任务材料.pdf");
    assistService.uploadRelatedAttachments(3L, List.of(dto));
    verify(approvalAttachmentService).saveAttachments(200L, ModelName.CONTACT_TASK, List.of(dto));

    AttachmentDeleteResultVO deleteResult = new AttachmentDeleteResultVO();
    when(approvalAttachmentService.removeSourceAttachments(
            List.of(10L, 11L), ModelName.CONTACT_TASK, 200L))
        .thenReturn(deleteResult);
    assertSame(deleteResult, assistService.deleteRelatedAttachments(3L, List.of(10L, 11L)));
    verify(approvalAttachmentService)
        .removeSourceAttachments(List.of(10L, 11L), ModelName.CONTACT_TASK, 200L);

    assist.setAssistStatus(1);
    assertThrows(BaseException.class, () -> assistService.getRelatedTaskAttachments(3L));
  }

  @Test
  @DisplayName("待协助时任务执行人可通过协助入口读取并上传任务附件")
  void taskAssigneeCanReadAndUploadSourceAttachments() {
    authenticateAs(7L);
    AssistRequestEntity assist = assistEntity(4L, ModelName.CONTACT_TASK, 201L, 8L);
    assist.setApplicantId(9L);
    doReturn(assist).when(assistService).getById(4L);
    when(userMapper.selectById(7L)).thenReturn(user(7L, "任务执行人", 1));
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(201L);
    task.setAssigneeId(7L);
    when(contactTaskMapper.selectById(201L)).thenReturn(task);

    assertEquals(Collections.emptyList(), assistService.getRelatedTaskAttachments(4L));
    ApprovalAttachmentDTO dto = new ApprovalAttachmentDTO();
    dto.setFileName("执行材料.pdf");
    assistService.uploadRelatedAttachments(4L, List.of(dto));

    verify(approvalAttachmentService).saveAttachments(201L, ModelName.CONTACT_TASK, List.of(dto));
  }

  @Test
  @DisplayName("实时协助授权只查询待协助记录")
  void getRelatedRecordIdsByUserOnlyQueriesPendingAssists() {
    doReturn(Collections.emptyList()).when(assistService).list(any(LambdaQueryWrapper.class));

    assistService.getRelatedRecordIdsByUser("business_activity", 2L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<LambdaQueryWrapper<AssistRequestEntity>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(assistService).list(captor.capture());
    String sql = captor.getValue().getSqlSegment();
    assertTrue(sql.contains("assist_status"));
  }

  // ===== handleAssist =====

  @Test
  @DisplayName("处理协助：参数为空/状态非法/内容必填")
  void handleAssistValidatesParams() {
    assertThrows(BaseException.class, () -> assistService.handleAssist(null));

    AssistHandleDTO noId = new AssistHandleDTO();
    assertThrows(BaseException.class, () -> assistService.handleAssist(noId));

    AssistHandleDTO badStatus = new AssistHandleDTO();
    badStatus.setId(1L);
    badStatus.setAssistStatus(9);
    badStatus.setAssistContent("协助内容");
    assertThrows(BaseException.class, () -> assistService.handleAssist(badStatus));

    AssistHandleDTO noOpinion = new AssistHandleDTO();
    noOpinion.setId(1L);
    noOpinion.setAssistStatus(1);
    assertThrows(BaseException.class, () -> assistService.handleAssist(noOpinion));
  }

  @Test
  @DisplayName("处理协助：终态记录再次处理被明确拒绝")
  void handleAssistRejectsAlreadyProcessedAssist() {
    authenticateAs(2L);
    AssistRequestEntity done = assistEntity(10L, "business_activity", 100L, 2L);
    done.setAssistStatus(1);
    doReturn(done).when(assistService).getById(10L);

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(10L);
    dto.setAssistStatus(2);
    dto.setRejectReason("改主意了");

    BaseException ex = assertThrows(BaseException.class, () -> assistService.handleAssist(dto));
    assertTrue(ex.getMessage().contains("已处理"));
    verify(assistService, never()).updateById(any());
  }

  @Test
  @DisplayName("发起协助：空列表入参静默忽略，不产生任何写入")
  void createAssistsIgnoresEmptyApplyList() {
    authenticateAs(9L);
    assistService.createAssists("business_activity", 100L, 9L, Collections.emptyList());

    verify(assistService, never()).save(any(AssistRequestEntity.class));
    verify(assistService, never()).list(any(LambdaQueryWrapper.class));
    verifyNoInteractions(userMapper);
  }

  @Test
  @DisplayName("业务无任何协助记录时可见列表返回空，不产生越权查询")
  void getVisibleAssistsReturnsEmptyWhenNoRecords() {
    doReturn(Collections.emptyList())
        .when(assistService)
        .listAssistsByRecord("business_activity", 100L);

    List<AssistVO> result = assistService.getVisibleAssists("business_activity", 100L, 9L);

    assertTrue(result.isEmpty());
    verify(assistService).listAssistsByRecord("business_activity", 100L);
  }

  @Test
  @DisplayName("模型混用：联络任务协助单请求业务活动附件详情被拒绝")
  void getRelatedActivityAttachmentsRejectsCrossModelAssist() {
    authenticateAs(2L);
    // 协助单实际绑定联络任务，却携带 activityId 请求业务活动附件详情
    AssistRequestEntity taskAssist = assistEntity(10L, ModelName.CONTACT_TASK, 200L, 2L);
    doReturn(taskAssist).when(assistService).getById(10L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    BaseException ex =
        assertThrows(
            BaseException.class, () -> assistService.getRelatedActivityAttachments(10L, 100L));

    assertTrue(ex.getMessage().contains("不匹配"));
    verifyNoInteractions(approvalAttachmentService);
  }

  @Test
  @DisplayName("非本人不能处理他人的协助申请")
  void handleAssistRejectsNonOwner() {
    authenticateAs(9L);
    AssistRequestEntity entity = assistEntity(1L, "contact_task", 10L, 8L);
    doReturn(entity).when(assistService).getById(1L);

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(1);
    dto.setAssistContent("已协助");

    assertThrows(BaseException.class, () -> assistService.handleAssist(dto));
    verify(assistService, never()).updateById(any());
  }

  @Test
  @DisplayName("已处理的协助申请不能重复处理")
  void handleAssistRejectsAlreadyHandled() {
    authenticateAs(9L);
    AssistRequestEntity entity = assistEntity(1L, "contact_task", 10L, 9L);
    entity.setAssistStatus(1);
    doReturn(entity).when(assistService).getById(1L);

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(1);
    dto.setAssistContent("再处理");

    assertThrows(BaseException.class, () -> assistService.handleAssist(dto));
  }

  @Test
  @DisplayName("本人待协助状态可正常处理并更新")
  void handleAssistSucceeds() {
    authenticateAs(9L);
    AssistRequestEntity entity = assistEntity(1L, "contact_task", 10L, 9L);
    entity.setAssistStatus(0);
    doReturn(entity).when(assistService).getById(1L);
    doReturn(true).when(assistService).updateById(any(AssistRequestEntity.class));
    when(assistRelatedRecordResolver.resolve(entity)).thenReturn(new AssistRelatedRecordVO());
    try {
      when(objectMapper.writeValueAsString(any())).thenReturn("{\"version\":1}");
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(2);
    dto.setRejectReason("  时间冲突，无法协助  ");

    assertTrue(assistService.handleAssist(dto));
    assertEquals(2, entity.getAssistStatus());
    assertEquals("时间冲突，无法协助", entity.getRejectReason());
    assertNotNull(entity.getAssistTime());
    assertEquals("{\"version\":1}", entity.getRecordSnapshot());
  }

  @Test
  @DisplayName("仅凭协助关系时，终态协助不得继续上传附件")
  void terminalAssistCannotWriteAttachments() {
    AssistRequestEntity assist = assistEntity(1L, "contact_task", 10L, 2L);
    assist.setAssistStatus(1);
    doReturn(assist).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    assertFalse(assistService.canWriteAssistDelivery(1L, 2L));
  }

  @Test
  @DisplayName("待协助的协助人可以上传协助交付物")
  void pendingAssistCanWriteAssistDelivery() {
    AssistRequestEntity assist = assistEntity(1L, "contact_task", 10L, 2L);
    assist.setAssistStatus(0);
    doReturn(assist).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    assertTrue(assistService.canWriteAssistDelivery(1L, 2L));
  }

  @Test
  @DisplayName("待协助时申请人也可以上传协助附件")
  void applicantCanWriteAssistAttachmentsWhilePending() {
    AssistRequestEntity assist = assistEntity(1L, "contact_task", 10L, 2L);
    assist.setApplicantId(8L);
    assist.setAssistStatus(0);
    doReturn(assist).when(assistService).getById(1L);
    when(userMapper.selectById(8L)).thenReturn(user(8L, "申请人", 1));

    assertTrue(assistService.canWriteAssistDelivery(1L, 8L));
  }

  @Test
  @DisplayName("终态协助申请人或协助人仍可读取交付物，但不能因此获得写权限")
  void terminalAssistCanReadDeliveryAttachments() {
    AssistRequestEntity assist = assistEntity(1L, "contact_task", 10L, 2L);
    assist.setAssistStatus(1);
    doReturn(assist).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    assertTrue(assistService.isOperable("assist_request", 1L, 2L));
    assertFalse(assistService.canWriteAssistDelivery(1L, 2L));
  }

  @Test
  @DisplayName("终态详情只返回冻结快照，不回读实时业务")
  void terminalDetailUsesStoredSnapshotOnly() {
    authenticateAs(2L);
    AssistRequestEntity entity = assistEntity(1L, "contact_task", 10L, 2L);
    entity.setAssistStatus(1);
    entity.setRecordSnapshot("{\"version\":1}");
    entity.setOpportunitySnapshot("{\"legacy\":true}");
    doReturn(entity).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    AssistVO detail = new AssistVO();
    detail.setId(1L);
    doReturn(List.of(detail))
        .when(assistService)
        .listAssistsByRecords("contact_task", List.of(10L));

    AssistVO result = assistService.getDetail(1L);

    assertEquals("{\"version\":1}", result.getRecordSnapshot());
    assertNull(result.getSnapshot());
    assertFalse(result.getSnapshotMissing());
    verifyNoInteractions(contactTaskMapper, salesOpportunityMapper, approvalAttachmentService);
  }

  @Test
  @DisplayName("终态详情为快照中的历史附件重新签发短期下载链接")
  void terminalDetailHydratesHistoricalAttachmentLinks() throws Exception {
    authenticateAs(2L);
    AssistRequestEntity entity = assistEntity(1L, "contact_task", 10L, 2L);
    entity.setAssistStatus(1);
    entity.setRecordSnapshot(
        "{\"record\":{\"attachments\":[{\"attachmentId\":11,\"modelName\":\"contact_task\",\"recordId\":10}]}}");
    doReturn(entity).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    AssistVO detail = new AssistVO();
    detail.setId(1L);
    doReturn(List.of(detail))
        .when(assistService)
        .listAssistsByRecords("contact_task", List.of(10L));

    Map<String, Object> attachment = new java.util.LinkedHashMap<>();
    attachment.put("attachmentId", 11L);
    attachment.put("modelName", "contact_task");
    attachment.put("recordId", 10L);
    Map<String, Object> record = new java.util.LinkedHashMap<>();
    record.put("attachments", new java.util.ArrayList<>(List.of(attachment)));
    Map<String, Object> root = new java.util.LinkedHashMap<>();
    root.put("record", record);
    when(objectMapper.readValue(entity.getRecordSnapshot(), Object.class)).thenReturn(root);
    when(objectMapper.writeValueAsString(root)).thenReturn("hydrated");
    when(httpRequest.getContextPath()).thenReturn("");
    when(downloadTokenUtil.generateDownloadToken(
            11L, 2L, "approval_attachment", "contact_task", 1L))
        .thenReturn("history-token");

    AssistVO result = assistService.getDetail(1L);

    assertEquals("hydrated", result.getRecordSnapshot());
    verify(downloadTokenUtil)
        .generateDownloadToken(11L, 2L, "approval_attachment", "contact_task", 1L);
    verifyNoInteractions(contactTaskMapper, salesOpportunityMapper, approvalAttachmentService);
  }

  @Test
  @DisplayName("既有终态记录缺快照时明确标记，不能以实时数据代替")
  void terminalDetailMarksMissingLegacySnapshot() {
    authenticateAs(2L);
    AssistRequestEntity entity = assistEntity(1L, "contact_task", 10L, 2L);
    entity.setAssistStatus(2);
    doReturn(entity).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    AssistVO detail = new AssistVO();
    detail.setId(1L);
    doReturn(List.of(detail))
        .when(assistService)
        .listAssistsByRecords("contact_task", List.of(10L));

    AssistVO result = assistService.getDetail(1L);

    assertTrue(result.getSnapshotMissing());
    verifyNoInteractions(contactTaskMapper, salesOpportunityMapper, approvalAttachmentService);
  }

  @Test
  @DisplayName("审批协助完成时冻结完整商机快照")
  void completedApprovalAssistStoresFullOpportunitySnapshot() {
    Map<String, Object> snapshot = handleAssistAndCaptureSnapshot("sales_stage_approval");

    assertTerminalSnapshot(snapshot, "salesStageApproval");
    Map<String, Object> opportunity = (Map<String, Object>) snapshot.get("opportunity");
    assertEquals(1, ((List<?>) opportunity.get("activities")).size());
  }

  @Test
  @DisplayName("业务活动协助完成时只冻结当前活动，不包含同商机其他活动")
  void completedBusinessActivityAssistStoresOnlySourceActivity() {
    Map<String, Object> snapshot = handleAssistAndCaptureSnapshot("business_activity");

    assertScopedSnapshot(snapshot, "businessActivity");
    Map<String, Object> record = (Map<String, Object>) snapshot.get("record");
    Map<String, Object> opportunity = (Map<String, Object>) snapshot.get("opportunity");
    assertEquals(10L, record.get("activityId"));
    assertFalse(opportunity.containsKey("activities"));
  }

  @Test
  @DisplayName("联络任务协助完成时只冻结当前任务及明确关联活动")
  void completedContactTaskAssistStoresOnlyTaskScope() {
    Map<String, Object> snapshot = handleAssistAndCaptureSnapshot("contact_task");

    assertScopedSnapshot(snapshot, "contactTask");
    Map<String, Object> record = (Map<String, Object>) snapshot.get("record");
    Map<String, Object> opportunity = (Map<String, Object>) snapshot.get("opportunity");
    assertEquals(10L, record.get("taskId"));
    assertEquals(1, ((List<?>) record.get("relatedActivities")).size());
    assertFalse(opportunity.containsKey("activities"));
    verify(businessActivityMapper, never()).selectByOpportunityId(50L);
  }

  @Test
  @DisplayName("待协助审批详情仅回填实时摘要，不构建大体积商机快照")
  void pendingApprovalDetailDoesNotBuildSnapshot() throws Exception {
    authenticateAs(2L);
    AssistRequestEntity entity = assistEntity(1L, "sales_stage_approval", 10L, 2L);
    doReturn(entity).when(assistService).getById(1L);
    when(userMapper.selectById(2L)).thenReturn(user(2L, "协助人", 1));

    AssistVO detail = new AssistVO();
    detail.setId(1L);
    doReturn(List.of(detail))
        .when(assistService)
        .listAssistsByRecords("sales_stage_approval", List.of(10L));

    AssistVO result = assistService.getDetail(1L);

    assertNull(result.getSnapshot());
    assertNull(result.getRecordSnapshot());
    verify(objectMapper, never()).writeValueAsString(any());
  }

  // ===== pageMyAssists =====

  @Test
  @DisplayName("待我协助分页：仅返回自己的记录并组装姓名部门")
  void pageMyAssistsReturnsOwnRecordsWithNames() {
    authenticateAs(9L);
    AssistRequestEntity entity = assistEntity(1L, "business_activity", 100L, 9L);
    entity.setApplicantId(2L);
    Page<AssistRequestEntity> pageResult = new Page<>(1, 10, 1);
    pageResult.setRecords(List.of(entity));
    doReturn(pageResult).when(assistService).page(any(Page.class), any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any()))
        .thenReturn(List.of(user(9L, "申请人", 1), user(2L, "协助人", 1)));
    when(dataConvertService.getDeptNames(any())).thenReturn(Map.of(11L, "销售部"));
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setActivityTitle("客户回访");
    activity.setActivityContent("上门回访大客户");
    when(businessActivityMapper.selectBatchIds(any())).thenReturn(List.of(activity));

    Page<AssistVO> ans = assistService.pageMyAssists(1, 10, 0);

    assertEquals(1, ans.getTotal());
    assertEquals(1, ans.getRecords().size());
    AssistVO vo = ans.getRecords().get(0);
    assertEquals("协助人", vo.getApplicantName());
    assertEquals("申请人", vo.getAssistUserName());
    assertEquals("销售部", vo.getAssistUserDeptName());
    assertEquals("业务活动：客户回访", vo.getRecordTitle());
    assertEquals("上门回访大客户", vo.getRecordContent());
  }

  @Test
  @DisplayName("待我协助分页：审批来源填充商机名称与审批备注")
  void pageMyAssistsFillsApprovalContent() {
    authenticateAs(9L);
    AssistRequestEntity entity = assistEntity(1L, "sales_stage_approval", 200L, 9L);
    Page<AssistRequestEntity> pageResult = new Page<>(1, 10, 1);
    pageResult.setRecords(List.of(entity));
    doReturn(pageResult).when(assistService).page(any(Page.class), any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any()))
        .thenReturn(List.of(user(9L, "申请人", 1), user(2L, "协助人", 1)));
    when(dataConvertService.getDeptNames(any())).thenReturn(Collections.emptyMap());

    SalesStageApprovalEntity approval = new SalesStageApprovalEntity();
    approval.setId(200L);
    approval.setOpportunityId(50L);
    approval.setMessage("客户要求尽快推进");
    when(salesStageApprovalMapper.selectBatchIds(any())).thenReturn(List.of(approval));
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(50L);
    opportunity.setOpportunityName("大客户A");
    when(salesOpportunityMapper.selectBatchIds(any())).thenReturn(List.of(opportunity));

    AssistVO vo = assistService.pageMyAssists(1, 10, 0).getRecords().get(0);

    assertEquals("销售阶段推进审批", vo.getRecordTitle());
    assertEquals("商机：大客户A；备注：客户要求尽快推进", vo.getRecordContent());
  }

  @Test
  @DisplayName("待我协助分页：任务来源填充任务标题/内容/结束时间")
  void pageMyAssistsFillsTaskContent() {
    authenticateAs(9L);
    AssistRequestEntity entity = assistEntity(1L, "contact_task", 300L, 9L);
    Page<AssistRequestEntity> pageResult = new Page<>(1, 10, 1);
    pageResult.setRecords(List.of(entity));
    doReturn(pageResult).when(assistService).page(any(Page.class), any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any()))
        .thenReturn(List.of(user(9L, "申请人", 1), user(2L, "协助人", 1)));
    when(dataConvertService.getDeptNames(any())).thenReturn(Collections.emptyMap());

    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(300L);
    task.setTaskTitle("整理报价方案");
    task.setTaskContent("按客户需求整理三套报价");
    task.setEndTime(new Date(1760000000000L));
    when(contactTaskMapper.selectBatchIds(any())).thenReturn(List.of(task));

    AssistVO vo = assistService.pageMyAssists(1, 10, 0).getRecords().get(0);

    assertEquals("联络任务：整理报价方案", vo.getRecordTitle());
    assertEquals("按客户需求整理三套报价", vo.getRecordContent());
    assertNotNull(vo.getRecordTime());
  }

  // ===== deleteByRecords =====

  @Test
  @DisplayName("删除源业务时应先删除协助交付物再删除协助记录")
  void deleteByRecordsRemovesAssistAttachmentsBeforeRecords() {
    doReturn(List.of(assistEntity(11L, "contact_task", 1L, 2L)))
        .when(assistService)
        .list(any(LambdaQueryWrapper.class));
    doReturn(true).when(assistService).remove(any(LambdaQueryWrapper.class));

    assistService.deleteByRecords("contact_task", List.of(1L, 2L));

    var order = inOrder(approvalAttachmentService, assistService);
    order
        .verify(approvalAttachmentService)
        .removeByAndIds(List.of(11L), com.slz.crm.common.enumeration.ModelName.ASSIST_REQUEST);
    order.verify(assistService).remove(any(LambdaQueryWrapper.class));
    verify(assistService).remove(any(LambdaQueryWrapper.class));
  }

  // ===== helpers =====

  private UserEntity user(Long id, String name, Integer status) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setRealName(name);
    user.setDeptId(11L);
    user.setStatus(status);
    return user;
  }

  private AssistRequestEntity assistEntity(
      Long id, String modelName, Long recordId, Long assistUserId) {
    AssistRequestEntity entity = new AssistRequestEntity();
    entity.setId(id);
    entity.setModelName(modelName);
    entity.setRecordId(recordId);
    entity.setApplicantId(9L);
    entity.setAssistUserId(assistUserId);
    entity.setAssistStatus(0);
    return entity;
  }

  private AssistVO assistVO(Long id, Long applicantId, Long assistUserId) {
    AssistVO vo = new AssistVO();
    vo.setId(id);
    vo.setApplicantId(applicantId);
    vo.setAssistUserId(assistUserId);
    return vo;
  }

  private AssistApplyItem applyItem(Long assistUserId) {
    AssistApplyItem item = new AssistApplyItem();
    item.setAssistUserId(assistUserId);
    item.setApplyPurpose("补充材料");
    item.setApplyRequirement("请补充所需材料");
    return item;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleAssistAndCaptureSnapshot(String modelName) {
    authenticateAs(2L);
    AssistRequestEntity entity = assistEntity(1L, modelName, 10L, 2L);
    doReturn(entity).when(assistService).getById(1L);
    doReturn(true).when(assistService).updateById(any(AssistRequestEntity.class));

    AssistRelatedRecordVO related = new AssistRelatedRecordVO();
    related.setOpportunityId(50L);
    related.setCompanyId(60L);
    related.setContactId(70L);
    when(assistRelatedRecordResolver.resolve(entity)).thenReturn(related);
    when(dataConvertService.getCompanyName(60L)).thenReturn("北京科创");
    when(dataConvertService.getContactName(70L)).thenReturn("李华");

    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(50L);
    opportunity.setOpportunityName("北京科创年度服务采购");
    opportunity.setStage(3);
    opportunity.setAmount(new BigDecimal("120000.00"));
    opportunity.setSource("客户转介绍");
    opportunity.setDescription("年度服务采购项目");
    opportunity.setOwnerId(8L);
    opportunity.setCompanyId(60L);
    opportunity.setContactId(70L);
    when(salesOpportunityMapper.selectById(50L)).thenReturn(opportunity);
    if (ModelName.SALES_STAGE_APPROVAL.equals(modelName)) {
      when(businessActivityMapper.selectByOpportunityId(50L)).thenReturn(Collections.emptyList());
    }

    switch (modelName) {
      case "sales_stage_approval" -> {
        SalesStageApprovalEntity approval = new SalesStageApprovalEntity();
        approval.setId(10L);
        approval.setOpportunityId(50L);
        approval.setMessage("请协助准备材料");
        when(salesStageApprovalMapper.selectById(10L)).thenReturn(approval);
        BusinessActivityEntity activity = new BusinessActivityEntity();
        activity.setId(11L);
        activity.setOpportunityId(50L);
        activity.setActivityTitle("审批前沟通");
        when(businessActivityMapper.selectByOpportunityId(50L)).thenReturn(List.of(activity));
        when(approvalAttachmentService.getByAndIds(List.of(11L), ModelName.BUSINESS_ACTIVITY))
            .thenReturn(Collections.emptyList());
      }
      case "business_activity" -> {
        BusinessActivityEntity activity = new BusinessActivityEntity();
        activity.setId(10L);
        activity.setActivityTitle("客户现场拜访");
        activity.setActivityContent("确认项目范围");
        when(businessActivityMapper.selectById(10L)).thenReturn(activity);
        when(approvalAttachmentService.getByAndIdsForAssist(List.of(10L), "business_activity", 1L))
            .thenReturn(Collections.emptyList());
      }
      case "contact_task" -> {
        ContactTaskEntity task = new ContactTaskEntity();
        task.setId(10L);
        task.setTaskTitle("整理报价材料");
        task.setTaskContent("补充技术报价附件");
        when(contactTaskMapper.selectById(10L)).thenReturn(task);
        when(approvalAttachmentService.getByAndIdsForAssist(List.of(10L), "contact_task", 1L))
            .thenReturn(Collections.emptyList());
        BusinessActivityEntity linkedActivity = new BusinessActivityEntity();
        linkedActivity.setId(11L);
        linkedActivity.setTaskId(10L);
        linkedActivity.setActivityTitle("补充报价说明");
        when(businessActivityMapper.selectByTaskId(10L)).thenReturn(List.of(linkedActivity));
        when(approvalAttachmentService.getByAndIds(List.of(11L), ModelName.BUSINESS_ACTIVITY))
            .thenReturn(Collections.emptyList());
      }
      default -> throw new IllegalArgumentException("未知协助来源：" + modelName);
    }
    try {
      when(objectMapper.writeValueAsString(any())).thenReturn("{\"version\":2}");
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(1);
    dto.setAssistContent("已完成协助");
    assertTrue(assistService.handleAssist(dto));
    assertNull(entity.getOpportunitySnapshot());

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    try {
      verify(objectMapper, atLeastOnce()).writeValueAsString(captor.capture());
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
    return captor.getAllValues().stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .filter(value -> value.containsKey("modelName"))
        .findFirst()
        .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private void assertTerminalSnapshot(Map<String, Object> snapshot, String expectedRecordType) {
    Map<String, Object> opportunity = (Map<String, Object>) snapshot.get("opportunity");
    Map<String, Object> record = (Map<String, Object>) snapshot.get("record");
    assertAll(
        () -> assertEquals(2, snapshot.get("version")),
        () -> assertEquals(50L, opportunity.get("opportunityId")),
        () -> assertEquals("北京科创年度服务采购", opportunity.get("opportunityName")),
        () -> assertEquals(3, opportunity.get("stage")),
        () -> assertEquals("储备项目", opportunity.get("stageName")),
        () -> assertEquals(new BigDecimal("120000.00"), opportunity.get("amount")),
        () -> assertEquals("北京科创", opportunity.get("companyName")),
        () -> assertEquals("李华", opportunity.get("contactName")),
        () -> assertEquals(expectedRecordType, record.get("type")));
  }

  @SuppressWarnings("unchecked")
  private void assertScopedSnapshot(Map<String, Object> snapshot, String expectedRecordType) {
    Map<String, Object> opportunity = (Map<String, Object>) snapshot.get("opportunity");
    Map<String, Object> record = (Map<String, Object>) snapshot.get("record");
    assertAll(
        () -> assertEquals(2, snapshot.get("version")),
        () -> assertEquals(50L, opportunity.get("opportunityId")),
        () -> assertEquals("北京科创年度服务采购", opportunity.get("opportunityName")),
        () -> assertEquals("北京科创", opportunity.get("companyName")),
        () -> assertEquals("李华", opportunity.get("contactName")),
        () -> assertEquals(expectedRecordType, record.get("type")),
        () -> assertFalse(snapshot.containsKey("deliveryAttachments")));
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }
}
