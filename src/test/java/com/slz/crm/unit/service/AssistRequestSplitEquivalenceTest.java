package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.pojo.vo.AssistVO;
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
import com.slz.crm.server.service.impl.AssistRequestServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

/**
 * AssistRequestServiceImpl 巨类拆分配套反向用例（tighten-pmd-residual-325 任务 6.5，F-4 批）。
 *
 * <p>两段式等价基准：先在拆分前的原实现上跑绿，拆分落地后原样复跑，必须逐条保持绿。断言只落在公共行为、 落库实体与冻结快照上，不依赖任何内部类结构，因此同一份用例对拆分前后都成立。
 *
 * <p>覆盖三条主干：状态机迁移（创建待协助 → 处理通过/拒绝）与落库结果、reapply 幂等与越权拒绝、 快照与列表内容装配的关键字段保真（拆分最重的 buildRecordSnapshot
 * / fillRecordContent 两只）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("协助申请拆分等价基准（F-4 批）")
class AssistRequestSplitEquivalenceTest {

  @BeforeAll
  static void initializeAssistRequestTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), AssistRequestEntity.class);
  }

  @Mock private UserMapper userMapper;

  @Mock private DataConvertService dataConvertService;

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

  // ===== 状态机主干：创建 → 待处理 → 处理通过 / 拒绝 =====

  @Test
  @DisplayName("状态机主干·创建：落库为待协助(0)、pendingKey=PENDING、未冻结快照、无父链")
  void createPersistsPendingStatus() {
    authenticateAs(9L);
    when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(2L, "张三", 1)));
    doReturnEmptyPendingList();
    doReturn(true).when(assistService).save(any(AssistRequestEntity.class));

    assistService.createAssists(
        ModelName.BUSINESS_ACTIVITY, 10L, 9L, List.of(applyItem(2L, "补充材料", "请补充所需材料")));

    ArgumentCaptor<AssistRequestEntity> captor = ArgumentCaptor.forClass(AssistRequestEntity.class);
    verify(assistService).save(captor.capture());
    AssistRequestEntity saved = captor.getValue();
    assertAll(
        () -> assertEquals(ModelName.BUSINESS_ACTIVITY, saved.getModelName()),
        () -> assertEquals(10L, saved.getRecordId()),
        () -> assertEquals(9L, saved.getApplicantId()),
        () -> assertEquals(2L, saved.getAssistUserId()),
        () -> assertEquals(0, saved.getAssistStatus()),
        () -> assertEquals("PENDING", saved.getPendingKey()),
        () -> assertNull(saved.getRecordSnapshot()),
        () -> assertNull(saved.getParentId()),
        () -> assertEquals("补充材料", saved.getApplyPurpose()),
        () -> assertEquals("请补充所需材料", saved.getApplyRequirement()),
        () -> assertNotNull(saved.getCreateTime()));
    verify(assistMessageService).appendSystemMessage(null, "已发起协助申请");
  }

  @Test
  @DisplayName("状态机主干·通过：待协助(0)→已协助(1)，内容落库、pendingKey 清空、快照冻结")
  void handleApprovalMovesPendingToCompleted() {
    authenticateAs(2L);
    AssistRequestEntity pending = pendingEntity(ModelName.BUSINESS_ACTIVITY, 10L, 2L);
    pending.setAssistContent("处理前的残留内容");
    doReturn(pending).when(assistService).getById(1L);
    doReturn(true).when(assistService).updateById(any(AssistRequestEntity.class));
    stubBusinessActivitySnapshot(pending);

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(1);
    dto.setAssistContent("  已完成协助  ");

    assertTrue(assistService.handleAssist(dto));

    assertAll(
        () -> assertEquals(1, pending.getAssistStatus()),
        () -> assertNull(pending.getPendingKey()),
        () -> assertEquals("已完成协助", pending.getAssistContent()),
        () -> assertNull(pending.getRejectReason()),
        () -> assertEquals("{\"version\":2}", pending.getRecordSnapshot()),
        () -> assertNotNull(pending.getAssistTime()));
    verify(assistService).updateById(pending);
    verify(assistMessageService).appendSystemMessage(1L, "协助已完成");
  }

  @Test
  @DisplayName("状态机主干·拒绝：待协助(0)→已拒绝(3)，理由落库、原有内容清空")
  void handleRejectionMovesPendingToRejected() {
    authenticateAs(2L);
    AssistRequestEntity pending = pendingEntity(ModelName.BUSINESS_ACTIVITY, 10L, 2L);
    pending.setAssistContent("处理前的残留内容");
    doReturn(pending).when(assistService).getById(1L);
    doReturn(true).when(assistService).updateById(any(AssistRequestEntity.class));
    stubBusinessActivitySnapshot(pending);

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(3);
    dto.setRejectReason("  材料不足  ");

    assertTrue(assistService.handleAssist(dto));

    assertAll(
        () -> assertEquals(3, pending.getAssistStatus()),
        () -> assertNull(pending.getPendingKey()),
        () -> assertEquals("材料不足", pending.getRejectReason()),
        () -> assertNull(pending.getAssistContent()),
        () -> assertEquals("{\"version\":2}", pending.getRecordSnapshot()),
        () -> assertNotNull(pending.getAssistTime()));
    verify(assistMessageService).appendSystemMessage(1L, "协助已拒绝");
  }

  @Test
  @DisplayName("状态机主干·越权与终态：非协助人不能处理，已处理记录不能重复处理")
  void handleRejectsNonParticipantAndTerminalRecord() {
    authenticateAs(8L);
    AssistRequestEntity pending = pendingEntity(ModelName.CONTACT_TASK, 10L, 2L);
    doReturn(pending).when(assistService).getById(1L);

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(1);
    dto.setAssistContent("已完成协助");

    BaseException denied = assertThrows(BaseException.class, () -> assistService.handleAssist(dto));
    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), denied.getCode());

    authenticateAs(2L);
    AssistRequestEntity terminal = pendingEntity(ModelName.CONTACT_TASK, 10L, 2L);
    terminal.setAssistStatus(1);
    doReturn(terminal).when(assistService).getById(1L);

    BaseException processed =
        assertThrows(BaseException.class, () -> assistService.handleAssist(dto));
    assertTrue(processed.getMessage().contains("已处理"));
    verify(assistService, never()).updateById(any(AssistRequestEntity.class));
  }

  // ===== reapply：幂等路径与越权拒绝 =====

  @Test
  @DisplayName("reapply 幂等：原协助人仍有待协助记录时拒绝重复发起，不落库")
  void reapplyRejectedWhenPendingAssistExists() {
    authenticateAs(9L);
    doReturn(rejectedOriginal()).when(assistService).getById(10L);
    doReturn(1L).when(assistService).count(any(LambdaQueryWrapper.class));

    BaseException error =
        assertThrows(
            BaseException.class,
            () -> assistService.reapply(10L, List.of(applyItem(3L, "补充材料", "请补充"))));

    assertTrue(error.getMessage().contains("该协助人已有待协助申请"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("reapply 幂等：重申请链已续（parentId 命中）时要求针对最新驳回记录，不落库")
  void reapplyRejectedWhenChainAlreadyContinued() {
    authenticateAs(9L);
    doReturn(rejectedOriginal()).when(assistService).getById(10L);
    doReturn(0L).doReturn(1L).when(assistService).count(any(LambdaQueryWrapper.class));

    BaseException error =
        assertThrows(
            BaseException.class,
            () -> assistService.reapply(10L, List.of(applyItem(3L, "补充材料", "请补充"))));

    assertTrue(error.getMessage().contains("请针对最新驳回记录"));
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("reapply 越权：非申请人且非业务负责人被拒绝")
  void reapplyDeniedForNonParticipant() {
    authenticateAs(77L);
    doReturn(rejectedOriginal()).when(assistService).getById(10L);
    when(contactTaskMapper.selectById(100L)).thenReturn(task(100L, 8L, 3L, 6L));

    BaseException error =
        assertThrows(
            BaseException.class,
            () -> assistService.reapply(10L, List.of(applyItem(3L, "补充材料", "请补充"))));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), error.getCode());
    verify(assistService, never()).save(any(AssistRequestEntity.class));
  }

  @Test
  @DisplayName("reapply 成功：挂 parentId 形成重申请链，状态回到待协助(0)")
  void reapplyBuildsChildEntityOnSuccess() {
    authenticateAs(9L);
    doReturn(rejectedOriginal()).when(assistService).getById(10L);
    doReturn(0L).when(assistService).count(any(LambdaQueryWrapper.class));
    when(userMapper.selectById(3L)).thenReturn(user(3L, "李四", 1));
    doReturn(true).when(assistService).save(any(AssistRequestEntity.class));

    assistService.reapply(10L, List.of(applyItem(3L, "补充材料", "请补充所需材料")));

    ArgumentCaptor<AssistRequestEntity> captor = ArgumentCaptor.forClass(AssistRequestEntity.class);
    verify(assistService).save(captor.capture());
    AssistRequestEntity saved = captor.getValue();
    assertAll(
        () -> assertEquals(ModelName.CONTACT_TASK, saved.getModelName()),
        () -> assertEquals(100L, saved.getRecordId()),
        () -> assertEquals(9L, saved.getApplicantId()),
        () -> assertEquals(3L, saved.getAssistUserId()),
        () -> assertEquals(0, saved.getAssistStatus()),
        () -> assertEquals("PENDING", saved.getPendingKey()),
        () -> assertEquals(10L, saved.getParentId()));
    verify(assistMessageService).appendSystemMessage(null, "已重新发起协助申请");
  }

  // ===== 快照装配保真（buildRecordSnapshot） =====

  @Test
  @DisplayName("快照保真·业务活动：来源字段与附件元数据逐字段冻结，且不含交付物键")
  void businessActivitySnapshotKeepsFieldsAndAttachmentMetadata() {
    Map<String, Object> snapshot = captureBusinessActivitySnapshot("客户现场拜访", "确认项目范围", true);

    Map<String, Object> record = recordOf(snapshot);
    assertAll(
        () -> assertEquals(2, snapshot.get("version")),
        () -> assertEquals(ModelName.BUSINESS_ACTIVITY, snapshot.get("modelName")),
        () -> assertEquals(10L, snapshot.get("recordId")),
        () -> assertEquals("businessActivity", record.get("type")),
        () -> assertEquals(10L, record.get("activityId")),
        () -> assertEquals("客户现场拜访", record.get("title")),
        () -> assertEquals("确认项目范围", record.get("content")),
        () -> assertEquals(45, record.get("activityDuration")),
        () -> assertEquals(60L, record.get("companyId")),
        () -> assertEquals(50L, record.get("opportunityId")),
        () -> assertEquals(8L, record.get("creatorId")),
        () -> assertEquals(11L, record.get("taskId")),
        () -> assertNotNull(snapshot.get("capturedAt")),
        () -> assertFalse(snapshot.containsKey("deliveryAttachments")),
        () -> assertTrue(snapshot.containsKey("related")),
        () -> assertTrue(snapshot.containsKey("opportunity")));
    List<Map<String, Object>> attachments = attachmentsOf(record);
    assertEquals(1, attachments.size());
    Map<String, Object> attachment = attachments.get(0);
    assertAll(
        () -> assertEquals(900L, attachment.get("attachmentId")),
        () -> assertEquals(ModelName.BUSINESS_ACTIVITY, attachment.get("modelName")),
        () -> assertEquals(10L, attachment.get("recordId")),
        () -> assertEquals("现场照片.png", attachment.get("fileName")),
        () -> assertEquals(2048L, attachment.get("fileSize")),
        () -> assertEquals("image/png", attachment.get("fileType")),
        () -> assertEquals("张三", attachment.get("uploaderName")),
        () -> assertFalse(attachment.containsKey("downloadUrl")));
  }

  @Test
  @DisplayName("快照保真·联络任务：relatedActivities 只取 task_id 明确关联，不按商机反查")
  void contactTaskSnapshotKeepsLinkedActivitiesOnly() {
    Map<String, Object> snapshot = captureContactTaskSnapshot();

    Map<String, Object> record = recordOf(snapshot);
    assertEquals("contactTask", record.get("type"));
    assertEquals(10L, record.get("taskId"));
    assertEquals("整理报价材料", record.get("title"));
    assertEquals("补充技术报价附件", record.get("content"));
    List<Map<String, Object>> linked = activitiesOf(record);
    assertEquals(1, linked.size());
    assertEquals(11L, linked.get(0).get("activityId"));
    assertEquals("补充报价说明", linked.get(0).get("activityTitle"));
    assertEquals(10L, linked.get(0).get("taskId"));
    // 业务活动/联络任务只冻结当前来源记录：不得按商机反查该商机下的全部活动。
    verify(businessActivityMapper, never()).selectByOpportunityId(anyLong());
  }

  // ===== 列表内容装配保真（fillRecordContent） =====

  @Test
  @DisplayName("内容装配保真·业务活动：列表回填标题/内容/时间与商机公司联系人名")
  void listAssistsFillsBusinessActivityBrief() {
    authenticateAs(2L);
    AssistRequestEntity mine = pendingEntity(ModelName.BUSINESS_ACTIVITY, 10L, 2L);
    mine.setApplicantId(9L);
    Page<AssistRequestEntity> pageResult = new Page<>(1, 10, 1);
    pageResult.setRecords(List.of(mine));
    doReturn(pageResult).when(assistService).page(any(Page.class), any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any()))
        .thenReturn(List.of(user(9L, "王申请", 1), user(2L, "张三", 1)));
    when(dataConvertService.getDeptNames(any())).thenReturn(Map.of(20L, "销售一部"));

    BusinessActivityEntity activity = activity(10L, "客户现场拜访", "确认项目范围");
    activity.setActivityTime(LocalDateTime.of(2026, 9, 20, 10, 0));
    when(businessActivityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(50L);
    opportunity.setOpportunityName("北京科创年度服务采购");
    opportunity.setCompanyId(60L);
    opportunity.setContactId(70L);
    when(salesOpportunityMapper.selectBatchIds(any())).thenReturn(List.of(opportunity));
    when(dataConvertService.getCompanyNames(any())).thenReturn(Map.of(60L, "北京科创"));
    when(dataConvertService.getContactNames(any())).thenReturn(Map.of(70L, "李华"));

    Page<AssistVO> result = assistService.pageMyAssists(1, 10, null);

    assertEquals(1, result.getRecords().size());
    AssistVO vo = result.getRecords().get(0);
    assertAll(
        () -> assertEquals(1L, vo.getId()),
        () -> assertEquals("业务活动：客户现场拜访", vo.getRecordTitle()),
        () -> assertEquals("确认项目范围", vo.getRecordContent()),
        () -> assertEquals(LocalDateTime.of(2026, 9, 20, 10, 0), vo.getRecordTime()),
        () -> assertEquals(50L, vo.getOpportunityId()),
        () -> assertEquals("北京科创年度服务采购", vo.getOpportunityName()),
        () -> assertEquals(60L, vo.getCompanyId()),
        () -> assertEquals("北京科创", vo.getCompanyName()),
        () -> assertEquals(70L, vo.getContactId()),
        () -> assertEquals("李华", vo.getContactName()),
        () -> assertEquals("王申请", vo.getApplicantName()),
        () -> assertEquals("张三", vo.getAssistUserName()),
        () -> assertEquals("销售一部", vo.getAssistUserDeptName()));
  }

  @Test
  @DisplayName("内容装配保真·待处理：待协助列表也回填实时摘要，模型不匹配的记录跳过")
  void pendingListFillsBriefOnlyForMatchingModel() {
    authenticateAs(2L);
    AssistRequestEntity taskAssist = pendingEntity(ModelName.BUSINESS_ACTIVITY, 10L, 2L);
    taskAssist.setApplicantId(9L);
    taskAssist.setRecordId(null);
    Page<AssistRequestEntity> pageResult = new Page<>(1, 10, 1);
    pageResult.setRecords(List.of(taskAssist));
    doReturn(pageResult).when(assistService).page(any(Page.class), any(LambdaQueryWrapper.class));
    when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(2L, "张三", 1)));

    Page<AssistVO> result = assistService.pageMyAssists(1, 10, 0);

    AssistVO vo = result.getRecords().get(0);
    assertAll(
        () -> assertNull(vo.getRecordTitle()),
        () -> assertNull(vo.getRecordContent()),
        () -> assertNull(vo.getOpportunityName()),
        () -> assertEquals("张三", vo.getAssistUserName()));
  }

  // ===== 辅助 =====

  private void stubBusinessActivitySnapshot(AssistRequestEntity assist) {
    when(assistRelatedRecordResolver.resolve(assist)).thenReturn(new AssistRelatedRecordVO());
    BusinessActivityEntity activity = activity(10L, "客户现场拜访", "确认项目范围");
    when(businessActivityMapper.selectById(10L)).thenReturn(activity);
    when(approvalAttachmentService.getByAndIdsForAssist(
            List.of(10L), ModelName.BUSINESS_ACTIVITY, 1L))
        .thenReturn(Collections.emptyList());
    stubSerialization();
  }

  private Map<String, Object> captureBusinessActivitySnapshot(
      String title, String content, boolean withAttachment) {
    authenticateAs(2L);
    AssistRequestEntity pending = pendingEntity(ModelName.BUSINESS_ACTIVITY, 10L, 2L);
    doReturn(pending).when(assistService).getById(1L);
    doReturn(true).when(assistService).updateById(any(AssistRequestEntity.class));
    when(assistRelatedRecordResolver.resolve(pending)).thenReturn(new AssistRelatedRecordVO());
    BusinessActivityEntity activity = activity(10L, title, content);
    when(businessActivityMapper.selectById(10L)).thenReturn(activity);
    when(approvalAttachmentService.getByAndIdsForAssist(
            List.of(10L), ModelName.BUSINESS_ACTIVITY, 1L))
        .thenReturn(withAttachment ? List.of(attachment(900L, 10L)) : Collections.emptyList());
    stubSerialization();

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(1);
    dto.setAssistContent("已完成协助");
    assertTrue(assistService.handleAssist(dto));
    return capturedSnapshotMap();
  }

  private Map<String, Object> captureContactTaskSnapshot() {
    authenticateAs(2L);
    AssistRequestEntity pending = pendingEntity(ModelName.CONTACT_TASK, 10L, 2L);
    doReturn(pending).when(assistService).getById(1L);
    doReturn(true).when(assistService).updateById(any(AssistRequestEntity.class));
    when(assistRelatedRecordResolver.resolve(pending)).thenReturn(new AssistRelatedRecordVO());
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(10L);
    task.setTaskTitle("整理报价材料");
    task.setTaskContent("补充技术报价附件");
    when(contactTaskMapper.selectById(10L)).thenReturn(task);
    when(approvalAttachmentService.getByAndIdsForAssist(List.of(10L), ModelName.CONTACT_TASK, 1L))
        .thenReturn(Collections.emptyList());
    BusinessActivityEntity linked = activity(11L, "补充报价说明", "材料补充");
    linked.setTaskId(10L);
    when(businessActivityMapper.selectByTaskId(10L)).thenReturn(List.of(linked));
    when(approvalAttachmentService.getByAndIds(List.of(11L), ModelName.BUSINESS_ACTIVITY))
        .thenReturn(Collections.emptyList());
    stubSerialization();

    AssistHandleDTO dto = new AssistHandleDTO();
    dto.setId(1L);
    dto.setAssistStatus(1);
    dto.setAssistContent("已完成协助");
    assertTrue(assistService.handleAssist(dto));
    return capturedSnapshotMap();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> capturedSnapshotMap() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    try {
      verify(objectMapper).writeValueAsString(captor.capture());
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
    return (Map<String, Object>) captor.getValue();
  }

  private void stubSerialization() {
    try {
      when(objectMapper.writeValueAsString(any())).thenReturn("{\"version\":2}");
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> recordOf(Map<String, Object> snapshot) {
    return (Map<String, Object>) snapshot.get("record");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> attachmentsOf(Map<String, Object> record) {
    return (List<Map<String, Object>>) record.get("attachments");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> activitiesOf(Map<String, Object> record) {
    return (List<Map<String, Object>>) record.get("relatedActivities");
  }

  private void doReturnEmptyPendingList() {
    doReturn(Collections.emptyList()).when(assistService).list(any(LambdaQueryWrapper.class));
  }

  private AssistRequestEntity pendingEntity(String modelName, Long recordId, Long assistUserId) {
    AssistRequestEntity entity = new AssistRequestEntity();
    entity.setId(1L);
    entity.setModelName(modelName);
    entity.setRecordId(recordId);
    entity.setApplicantId(9L);
    entity.setAssistUserId(assistUserId);
    entity.setAssistStatus(0);
    entity.setPendingKey("PENDING");
    entity.setApplyPurpose("补充材料");
    entity.setApplyRequirement("请补充所需材料");
    entity.setCreateTime(LocalDateTime.of(2026, 9, 20, 9, 0));
    return entity;
  }

  private AssistRequestEntity rejectedOriginal() {
    AssistRequestEntity original = new AssistRequestEntity();
    original.setId(10L);
    original.setModelName(ModelName.CONTACT_TASK);
    original.setRecordId(100L);
    original.setApplicantId(9L);
    original.setAssistUserId(3L);
    original.setAssistStatus(2);
    return original;
  }

  private AssistApplyItem applyItem(Long assistUserId, String purpose, String requirement) {
    AssistApplyItem item = new AssistApplyItem();
    item.setAssistUserId(assistUserId);
    item.setApplyPurpose(purpose);
    item.setApplyRequirement(requirement);
    return item;
  }

  private AssistApplyItem applyItem(Long assistUserId) {
    return applyItem(assistUserId, "补充材料", "请补充所需材料");
  }

  private BusinessActivityEntity activity(Long id, String title, String content) {
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(id);
    activity.setActivityTitle(title);
    activity.setActivityContent(content);
    activity.setActivityType("拜访");
    activity.setActivityTime(LocalDateTime.of(2026, 9, 20, 14, 30));
    activity.setActivityDuration(45);
    activity.setCompanyId(60L);
    activity.setOpportunityId(50L);
    activity.setCreatorId(8L);
    activity.setRemark("备注");
    activity.setTaskId(11L);
    return activity;
  }

  private ApprovalAttachmentVO attachment(Long id, Long recordId) {
    ApprovalAttachmentVO vo = new ApprovalAttachmentVO();
    vo.setId(id);
    vo.setAndId(recordId);
    vo.setModelName(ModelName.BUSINESS_ACTIVITY);
    vo.setFileName("现场照片.png");
    vo.setFilePath("/data/files/现场照片.png");
    vo.setFileSize(2048L);
    vo.setFileType("image/png");
    vo.setUploaderName("张三");
    return vo;
  }

  private ContactTaskEntity task(Long id, Long creatorId, Long assignerId, Long assigneeId) {
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(id);
    task.setTaskTitle("整理报价材料");
    task.setTaskContent("补充技术报价附件");
    task.setCreatorId(creatorId);
    task.setAssignerId(assignerId);
    task.setAssigneeId(assigneeId);
    return task;
  }

  private UserEntity user(Long id, String realName, Integer status) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setRealName(realName);
    user.setStatus(status);
    user.setDeptId(20L);
    return user;
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }
}
