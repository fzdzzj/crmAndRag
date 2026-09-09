package com.slz.crm.unit.service;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.AddSalesStageApprovalDTO;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.impl.SalesStageApprovalServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 销售阶段审批服务单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("销售阶段审批服务")
class SalesStageApprovalServiceTest {

    @BeforeAll
    static void initializeSalesStageApprovalTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SalesStageApprovalEntity.class);
    }

    @Mock
    private SalesStageApprovalMapper salesStageApprovalMapper;
    @Mock
    private SalesOpportunityMapper salesOpportunityMapper;
    @Mock
    private AssistRequestService assistRequestService;
    @InjectMocks
    private SalesStageApprovalServiceImpl salesStageApprovalService;

    @BeforeEach
    void injectMyBatisBaseMapper() {
        ReflectionTestUtils.setField(salesStageApprovalService, "baseMapper", salesStageApprovalMapper);
    }

    @AfterEach
    void clearCurrentUser() {
        BaseUnit.removeCurrentId();
    }

    @Test
    @DisplayName("非指定审批人不得处理审批申请")
    void shouldRejectUpdateWhenCurrentUserIsNotAssignedApprover() {
        authenticateAs(3003L);
        SalesStageApprovalEntity pending = approval(5001L, 2001L, 1, 2, 0);
        pending.setApproverId(1002L);
        when(salesStageApprovalMapper.selectById(5001L)).thenReturn(pending);

        assertThrows(BaseException.class,
                () -> salesStageApprovalService.updateById(validUpdateRequest(5001L, 2)));

        verify(salesStageApprovalMapper).selectById(5001L);
        verify(salesStageApprovalMapper, never()).updateById(any(SalesStageApprovalEntity.class));
    }

    @Test
    @DisplayName("同一订单已有待审批推进时不得再次发起申请")
    void approveStageShouldRejectWhenOpportunityHasPendingApproval() {
        SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
        opportunity.setId(2001L);
        opportunity.setStage(1);
        when(salesOpportunityMapper.selectByIdForUpdate(2001L)).thenReturn(opportunity);
        when(salesStageApprovalMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BaseException.class,
                () -> salesStageApprovalService.approveStage(validApplyRequest(2001L)));

        verify(salesStageApprovalMapper, never()).insert(any(SalesStageApprovalEntity.class));
    }

    @Test
    @DisplayName("审批终态应取消该审批下尚未处理的协助")
    void updateShouldCancelPendingAssistsWhenApprovalEnds() {
        authenticateAs(1002L);
        SalesStageApprovalEntity pending = approval(5001L, 2001L, 1, 2, 0);
        pending.setApproverId(1002L);
        when(salesStageApprovalMapper.selectById(5001L)).thenReturn(pending);
        when(salesStageApprovalMapper.updateById(any(SalesStageApprovalEntity.class))).thenReturn(1);

        salesStageApprovalService.updateById(validUpdateRequest(5001L, 2));

        verify(assistRequestService).cancelPendingByRecord(
                eq("sales_stage_approval"), eq(5001L), contains("审批已结束"));
    }

    @Test
    @DisplayName("申请人可以重复保存未提交的阶段推进草稿")
    void updateDraftShouldPersistChangesWithoutSubmittingApproval() {
        authenticateAs(1001L);
        SalesStageApprovalEntity draft = approval(5002L, 2001L, 1, 2, 0);
        draft.setApplicantId(1001L);
        draft.setApprovalTriggered(false);
        when(salesStageApprovalMapper.selectById(5002L)).thenReturn(draft);
        SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
        opportunity.setId(2001L);
        opportunity.setOpportunityName("测试商机");
        opportunity.setStage(1);
        when(salesOpportunityMapper.selectByIdForUpdate(2001L)).thenReturn(opportunity);
        when(salesStageApprovalMapper.updateById(any(SalesStageApprovalEntity.class))).thenReturn(1);

        AddSalesStageApprovalDTO request = validApplyRequest(2001L);
        request.setApproverId(1002L);
        request.setMessage("修改后的备注");
        AssistApplyItem item = new AssistApplyItem();
        item.setAssistUserId(1003L);
        item.setApplyPurpose("补充资料");
        item.setApplyRequirement("提交资料");
        request.setAssistApplyList(List.of(item));

        salesStageApprovalService.updateDraft(5002L, request);

        verify(salesStageApprovalMapper).updateById(argThat(updated ->
                "修改后的备注".equals(updated.getMessage())
                        && Boolean.FALSE.equals(updated.getApprovalTriggered())));
        verify(assistRequestService).updatePendingAssists(
                eq("sales_stage_approval"), eq(5002L), eq(1001L), eq(List.of(item)));
    }

    @Test
    @DisplayName("已提交审批的记录不得按草稿修改")
    void updateDraftShouldRejectSubmittedApproval() {
        authenticateAs(1001L);
        SalesStageApprovalEntity submitted = approval(5003L, 2001L, 1, 2, 0);
        submitted.setApplicantId(1001L);
        submitted.setApprovalTriggered(true);
        when(salesStageApprovalMapper.selectById(5003L)).thenReturn(submitted);

        assertThrows(BaseException.class,
                () -> salesStageApprovalService.updateDraft(5003L, validApplyRequest(2001L)));

        verify(salesStageApprovalMapper, never()).updateById(any(SalesStageApprovalEntity.class));
        verifyNoInteractions(salesOpportunityMapper, assistRequestService);
    }


    @Test
    @DisplayName("申请人重新打开推进弹窗时可读取自己的未提交草稿")
    void shouldLoadCurrentUsersUnsubmittedDraft() {
        authenticateAs(1001L);
        SalesStageApprovalEntity draft = approval(5004L, 2001L, 1, 2, 0);
        draft.setApplicantId(1001L);
        draft.setApprovalTriggered(false);
        when(salesStageApprovalMapper.selectOne(any())).thenReturn(draft);
        SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
        opportunity.setId(2001L);
        opportunity.setOpportunityName("测试商机");
        opportunity.setStage(1);
        when(salesOpportunityMapper.selectById(2001L)).thenReturn(opportunity);

        var result = salesStageApprovalService.getDraftByOpportunityId(2001L);

        assertEquals(5004L, result.getId());
        assertEquals(2001L, result.getOpportunityId());
        assertEquals("测试商机", result.getOpportunityName());
        assertEquals(Boolean.FALSE, result.getApprovalTriggered());
        verify(assistRequestService).listAssistsByRecord("sales_stage_approval", 5004L);
    }

    // ===== 草稿所有权/幂等/乐观锁（评论 #12 要求的边界场景）=====

    @Test
    @DisplayName("同一商机重复保存草稿为幂等更新，不产生新记录")
    void saveDraftIsIdempotentWhenDraftExists() {
        authenticateAs(1001L);
        SalesStageApprovalEntity existingDraft = approval(5010L, 2001L, 1, 2, 0);
        existingDraft.setApplicantId(1001L);
        existingDraft.setApprovalTriggered(false);
        // 幂等查询命中已有草稿 → 转入 updateDraft，不新建记录
        when(salesStageApprovalMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingDraft);
        when(salesStageApprovalMapper.selectById(5010L)).thenReturn(existingDraft);
        SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
        opportunity.setId(2001L);
        opportunity.setOpportunityName("测试商机");
        opportunity.setStage(1);
        when(salesOpportunityMapper.selectByIdForUpdate(2001L)).thenReturn(opportunity);
        when(salesStageApprovalMapper.updateById(any(SalesStageApprovalEntity.class))).thenReturn(1);

        salesStageApprovalService.saveDraft(validApplyRequest(2001L));

        verify(salesStageApprovalMapper, never()).insert(any(SalesStageApprovalEntity.class));
        verify(salesStageApprovalMapper).updateById(any(SalesStageApprovalEntity.class));
    }

    @Test
    @DisplayName("并发修改草稿时乐观锁冲突报错，不静默覆盖")
    void updateDraftRejectsWhenOptimisticLockConflicts() {
        authenticateAs(1001L);
        SalesStageApprovalEntity draft = approval(5011L, 2001L, 1, 2, 0);
        draft.setApplicantId(1001L);
        draft.setApprovalTriggered(false);
        draft.setVersion(0);
        when(salesStageApprovalMapper.selectById(5011L)).thenReturn(draft);
        SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
        opportunity.setId(2001L);
        opportunity.setOpportunityName("测试商机");
        opportunity.setStage(1);
        when(salesOpportunityMapper.selectByIdForUpdate(2001L)).thenReturn(opportunity);
        // 乐观锁冲突：update 影响行数为 0（其他会话已先一步修改并自增 version）
        when(salesStageApprovalMapper.updateById(any(SalesStageApprovalEntity.class))).thenReturn(0);

        BaseException ex = assertThrows(BaseException.class,
                () -> salesStageApprovalService.updateDraft(5011L, validApplyRequest(2001L)));
        assertTrue(ex.getMessage().contains("草稿已被其他会话修改"));
        verify(assistRequestService, never()).updatePendingAssists(any(), any(), any(), any());
    }

    @Test
    @DisplayName("无权限用户不能修改他人草稿")
    void updateDraftRejectsNonOwner() {
        authenticateAs(9999L);
        SalesStageApprovalEntity draft = approval(5012L, 2001L, 1, 2, 0);
        draft.setApplicantId(1001L);
        draft.setApprovalTriggered(false);
        when(salesStageApprovalMapper.selectById(5012L)).thenReturn(draft);

        assertThrows(BaseException.class,
                () -> salesStageApprovalService.updateDraft(5012L, validApplyRequest(2001L)));
        verify(salesStageApprovalMapper, never()).updateById(any(SalesStageApprovalEntity.class));
    }

    @Test
    @DisplayName("无权限用户不能提交他人草稿")
    void submitDraftRejectsNonOwner() {
        authenticateAs(9999L);
        SalesStageApprovalEntity draft = approval(5013L, 2001L, 1, 2, 0);
        draft.setApplicantId(1001L);
        draft.setApprovalTriggered(false);
        when(salesStageApprovalMapper.selectById(5013L)).thenReturn(draft);

        assertThrows(BaseException.class, () -> salesStageApprovalService.submitDraft(5013L));
        verify(salesStageApprovalMapper, never()).updateById(any(SalesStageApprovalEntity.class));
    }

    @Test
    @DisplayName("草稿提交后置为正式审批，草稿查询不再返回")
    void submitDraftMarksApprovalTriggered() {
        authenticateAs(1001L);
        SalesStageApprovalEntity draft = approval(5014L, 2001L, 1, 2, 0);
        draft.setApplicantId(1001L);
        draft.setApprovalTriggered(false);
        when(salesStageApprovalMapper.selectById(5014L)).thenReturn(draft);
        SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
        opportunity.setId(2001L);
        opportunity.setStage(1);
        when(salesOpportunityMapper.selectByIdForUpdate(2001L)).thenReturn(opportunity);
        when(salesStageApprovalMapper.selectCount(any())).thenReturn(0L);
        when(salesStageApprovalMapper.updateById(any(SalesStageApprovalEntity.class))).thenReturn(1);

        salesStageApprovalService.submitDraft(5014L);

        verify(salesStageApprovalMapper).updateById(argThat(submitted ->
                Boolean.TRUE.equals(submitted.getApprovalTriggered())));
    }

    @Test
    @DisplayName("已存在待审批正式记录时提交旧草稿报错")
    void submitDraftRejectsWhenPendingApprovalExists() {
        authenticateAs(1001L);
        SalesStageApprovalEntity draft = approval(5015L, 2001L, 1, 2, 0);
        draft.setApplicantId(1001L);
        draft.setApprovalTriggered(false);
        when(salesStageApprovalMapper.selectById(5015L)).thenReturn(draft);
        SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
        opportunity.setId(2001L);
        opportunity.setStage(1);
        when(salesOpportunityMapper.selectByIdForUpdate(2001L)).thenReturn(opportunity);
        // 同商机已有其他待审批记录（正式提交后阶段变化，旧草稿不应再提交）
        when(salesStageApprovalMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BaseException.class, () -> salesStageApprovalService.submitDraft(5015L));
        verify(salesStageApprovalMapper, never()).updateById(any(SalesStageApprovalEntity.class));
    }

    @Test
    @DisplayName("草稿查询按申请人过滤，只能读取自己的草稿")
    void draftQueryFiltersByApplicant() {
        authenticateAs(1001L);
        when(salesStageApprovalMapper.selectOne(any())).thenReturn(null);

        assertNull(salesStageApprovalService.getDraftByOpportunityId(2001L));

        verify(salesStageApprovalMapper).selectOne(argThat(wrapper ->
                ((LambdaQueryWrapper<?>) wrapper).getSqlSegment().contains("applicant_id")));
    }

    private AddSalesStageApprovalDTO validApplyRequest(Long opportunityId) {
        AddSalesStageApprovalDTO request = new AddSalesStageApprovalDTO();
        request.setOpportunityId(opportunityId);
        request.setTargetStage(2);
        request.setMessage("申请推进到确认商机");
        return request;
    }

    private SalesStageApprovalDTO validUpdateRequest(Long id, Integer... statuses) {
        SalesStageApprovalDTO request = new SalesStageApprovalDTO();
        request.setId(id);
        request.setApprovalStatus(List.of(statuses));
        request.setApprovalOpinion("审批意见");
        return request;
    }

    private SalesStageApprovalEntity approval(Long id, Long opportunityId, Integer currentStage,
                                              Integer targetStage, Integer status) {
        SalesStageApprovalEntity entity = new SalesStageApprovalEntity();
        entity.setId(id);
        entity.setOpportunityId(opportunityId);
        entity.setCurrentStage(currentStage);
        entity.setTargetStage(targetStage);
        entity.setApprovalStatus(status);
        return entity;
    }

    private void authenticateAs(Long userId) {
        RoleAO role = new RoleAO();
        role.setId(userId);
        BaseUnit.setCurrentRole(role);
    }
}
