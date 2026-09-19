package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.OpportunityDetailVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.AssistScopeService;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.SalesOpportunityServiceImpl;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("商机详情中的协助可见范围")
class SalesOpportunityAssistVisibilityTest {

  @Mock private SalesOpportunityMapper salesOpportunityMapper;
  @Mock private BusinessActivityMapper businessActivityMapper;
  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;
  @Mock private BusinessRecordAccessService businessRecordAccessService;
  @Mock private AssistScopeService assistScopeService;
  @Mock private AssistRequestService assistRequestService;
  @Mock private DataConvertService dataConvertService;
  @Mock private UserMapper userMapper;

  @InjectMocks private SalesOpportunityServiceImpl service;

  @BeforeEach
  void authenticateAsTaskAssistant() {
    RoleAO current = new RoleAO();
    current.setId(7L);
    current.setRoleId(4L);
    BaseUnit.setCurrentRole(current);
  }

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("联络任务协助人只能看到当前任务关联的活动，且不返回审批记录")
  void taskAssistantCanOnlySeeActivitiesLinkedToAssignedTask() {
    SalesOpportunityEntity opportunity = opportunity();
    when(salesOpportunityMapper.selectById(100L)).thenReturn(opportunity);
    when(businessRecordAccessService.assertCanReadOpportunity(opportunity)).thenReturn(true);

    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setModelName(ModelName.CONTACT_TASK);
    assist.setRecordId(90L);
    when(assistScopeService.visibleAssistsForOpportunity(7L, 100L)).thenReturn(List.of(assist));

    BusinessActivityEntity linked = activity(10L, 90L);
    BusinessActivityEntity unrelated = activity(11L, 91L);
    when(businessActivityMapper.selectByOpportunityId(100L)).thenReturn(List.of(linked, unrelated));
    when(salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(100L))
        .thenReturn(List.of());

    OpportunityDetailVO result = service.getOpportunityDetailById(100L);

    Set<Long> visibleActivityIds =
        result.getActivitiesByStage().values().stream()
            .flatMap(List::stream)
            .map(BusinessActivityVO::getId)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of(10L), visibleActivityIds);
    assertTrue(result.getStageChangeRecords().isEmpty());
    verify(salesStageApprovalMapper, never()).selectAllApprovalsByOpportunityId(100L);
  }

  @Test
  @DisplayName("业务活动协助人只能看到被协助的当前活动，且不返回审批记录")
  void activityAssistantCanOnlySeeAssignedActivity() {
    SalesOpportunityEntity opportunity = opportunity();
    when(salesOpportunityMapper.selectById(100L)).thenReturn(opportunity);
    when(businessRecordAccessService.assertCanReadOpportunity(opportunity)).thenReturn(true);

    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setModelName(ModelName.BUSINESS_ACTIVITY);
    assist.setRecordId(11L);
    when(assistScopeService.visibleAssistsForOpportunity(7L, 100L)).thenReturn(List.of(assist));

    when(businessActivityMapper.selectByOpportunityId(100L))
        .thenReturn(List.of(activity(10L, 90L), activity(11L, 91L)));
    when(salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(100L))
        .thenReturn(List.of());

    OpportunityDetailVO result = service.getOpportunityDetailById(100L);

    assertEquals(Set.of(11L), activityIds(result));
    assertTrue(result.getStageChangeRecords().isEmpty());
    verify(salesStageApprovalMapper, never()).selectAllApprovalsByOpportunityId(100L);
  }

  @Test
  @DisplayName("审批协助人可查看完整商机详情、全部活动和全部审批记录")
  void approvalAssistantCanSeeFullOpportunityDetail() {
    SalesOpportunityEntity opportunity = opportunity();
    when(salesOpportunityMapper.selectById(100L)).thenReturn(opportunity);
    when(businessRecordAccessService.assertCanReadOpportunity(opportunity)).thenReturn(true);

    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setModelName(ModelName.SALES_STAGE_APPROVAL);
    assist.setRecordId(20L);
    when(assistScopeService.visibleAssistsForOpportunity(7L, 100L)).thenReturn(List.of(assist));

    when(businessActivityMapper.selectByOpportunityId(100L))
        .thenReturn(List.of(activity(10L, 90L), activity(11L, 91L)));
    SalesStageApprovalEntity approval = new SalesStageApprovalEntity();
    approval.setId(20L);
    approval.setOpportunityId(100L);
    approval.setCurrentStage(0);
    approval.setTargetStage(1);
    approval.setApprovalStatus(0);
    when(salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(100L))
        .thenReturn(List.of());
    when(salesStageApprovalMapper.selectAllApprovalsByOpportunityId(100L))
        .thenReturn(List.of(approval));
    when(dataConvertService.getUserNames(any())).thenReturn(java.util.Collections.emptyMap());
    when(assistRequestService.listAssistsByRecords(any(), any())).thenReturn(List.of());

    OpportunityDetailVO result = service.getOpportunityDetailById(100L);

    assertEquals(Set.of(10L, 11L), activityIds(result));
    assertEquals(1, result.getStageChangeRecords().size());
    assertEquals(20L, result.getStageChangeRecords().getFirst().getId());
  }

  @Test
  @DisplayName("正常数据权限用户仍可查看全部活动和审批记录")
  void normalDataScopeKeepsFullDetail() {
    SalesOpportunityEntity opportunity = opportunity();
    when(salesOpportunityMapper.selectById(100L)).thenReturn(opportunity);
    when(businessRecordAccessService.assertCanReadOpportunity(opportunity)).thenReturn(false);
    when(businessActivityMapper.selectByOpportunityId(100L))
        .thenReturn(List.of(activity(10L, 90L), activity(11L, 91L)));
    when(salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(100L))
        .thenReturn(List.of());
    when(salesStageApprovalMapper.selectAllApprovalsByOpportunityId(100L)).thenReturn(List.of());

    OpportunityDetailVO result = service.getOpportunityDetailById(100L);

    assertEquals(Set.of(10L, 11L), activityIds(result));
  }

  private SalesOpportunityEntity opportunity() {
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(100L);
    opportunity.setOpportunityName("年度服务采购");
    opportunity.setStage(1);
    opportunity.setCreatorId(1L);
    opportunity.setOwnerId(1L);
    return opportunity;
  }

  private Set<Long> activityIds(OpportunityDetailVO result) {
    return result.getActivitiesByStage().values().stream()
        .flatMap(List::stream)
        .map(BusinessActivityVO::getId)
        .collect(java.util.stream.Collectors.toSet());
  }

  private BusinessActivityEntity activity(Long id, Long taskId) {
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(id);
    activity.setOpportunityId(100L);
    activity.setTaskId(taskId);
    activity.setCreatorId(1L);
    return activity;
  }
}
