package com.slz.crm.server.service.impl;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.ai.AiDraftResult;
import com.slz.crm.pojo.entity.AiPendingActionEntity;
import com.slz.crm.pojo.vo.AiConfirmResultVO;
import com.slz.crm.server.ai.executor.AiActionExecutor;
import com.slz.crm.server.ai.executor.AiExecutionResult;
import com.slz.crm.server.ai.validation.AiActionValidator;
import com.slz.crm.server.ai.validation.AiValidationResult;
import com.slz.crm.server.mapper.AiPendingActionMapper;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PendingActionServiceImpl 状态机单测：提交/合并/确认/取消/过期流转与幂等
 */
@ExtendWith(MockitoExtension.class)
class PendingActionServiceImplTest {

    @Mock
    private AiPendingActionMapper mapper;

    @Mock
    private PermissionService permissionService;

    @Mock
    private AiActionValidator validator;

    @Mock
    private AiActionExecutor executor;

    @Captor
    private ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.slz.crm.pojo.entity.AiPendingActionEntity>> updateWrapperCaptor;

    private PendingActionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PendingActionServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "validators", List.of(validator));
        ReflectionTestUtils.setField(service, "executors", List.of(executor));
        ReflectionTestUtils.setField(service, "self", service);
        lenient().when(validator.actionType()).thenReturn("CREATE_ORDER");
        lenient().when(executor.actionType()).thenReturn("CREATE_ORDER");
        service.initStrategyMaps();
    }

    @Test
    void submitDraft_valid_becomesPending() {
        when(validator.validate(any())).thenReturn(AiValidationResult.ok());

        AiDraftResult result = service.submitDraft(1L, 2L, "CREATE_ORDER", "{\"a\":1}");

        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getPendingId()).startsWith("PA");
        ArgumentCaptor<AiPendingActionEntity> captor = ArgumentCaptor.forClass(AiPendingActionEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getPreview()).isNotNull();
    }

    @Test
    void submitDraft_invalid_becomesDrafting() {
        when(validator.validate(any()))
                .thenReturn(AiValidationResult.fail(List.of("productName"), List.of("订单包含哪些产品？")));

        AiDraftResult result = service.submitDraft(1L, 2L, "CREATE_ORDER", "{\"a\":1}");

        assertThat(result.getStatus()).isEqualTo("DRAFTING");
        assertThat(result.getMissingFields()).contains("productName");
        assertThat(result.getQuestions()).contains("订单包含哪些产品？");
    }

    @Test
    void submitDraft_unknownActionType_throws() {
        assertThatThrownBy(() -> service.submitDraft(1L, 2L, "UNKNOWN_TYPE", "{}"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不支持的操作类型");
    }

    @Test
    void mergeDraft_fillParams_becomesPending() {
        AiPendingActionEntity entity = drafting("{\"contractId\":5}");
        stubFind(entity);
        when(validator.validate(any())).thenReturn(AiValidationResult.ok());

        AiDraftResult result = service.mergeDraft(entity.getPendingId(), 2L, "{\"productName\":\"产品A\"}");

        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getAskRound()).isEqualTo(1);
        assertThat(entity.getPayload()).contains("productName");
        verify(mapper).updateById(entity);
    }

    @Test
    void mergeDraft_nonDrafting_throws() {
        AiPendingActionEntity entity = drafting("{\"a\":1}");
        entity.setStatus("PENDING");
        stubFind(entity);

        assertThatThrownBy(() -> service.mergeDraft(entity.getPendingId(), 2L, "{\"b\":2}"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不可合并");
    }

    @Test
    void mergeDraft_notOwned_treatedAsNotFound() {
        AiPendingActionEntity entity = drafting("{\"a\":1}");
        stubFind(entity);

        assertThatThrownBy(() -> service.mergeDraft(entity.getPendingId(), 3L, "{\"b\":2}"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("操作不存在");
    }

    @Test
    void confirm_notOwned_treatedAsNotFound() {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        stubFind(entity);

        assertThatThrownBy(() -> service.confirm(entity.getPendingId(), 3L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("操作不存在");
    }

    @Test
    void cancel_notOwned_treatedAsNotFound() {
        AiPendingActionEntity entity = drafting("{\"a\":1}");
        stubFind(entity);

        assertThatThrownBy(() -> service.cancel(entity.getPendingId(), 3L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("操作不存在");
    }

    @Test
    void edit_notOwned_treatedAsNotFound() {
        AiPendingActionEntity entity = drafting("{\"a\":1}");
        stubFind(entity);

        assertThatThrownBy(() -> service.edit(entity.getPendingId(), 3L, "{\"b\":2}"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("操作不存在");
    }

    @Test
    void confirm_pending_executesOnce() throws Exception {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        stubFind(entity);
        when(permissionService.hasPermission(2L, com.slz.crm.common.enumeration.PermissionOperates.SALES_APPEND_ORDER)).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(executor.execute("{\"a\":1}")).thenReturn(AiExecutionResult.of("{\"created\":1}"));

        AiConfirmResultVO result = service.confirm(entity.getPendingId(), 2L);

        assertThat(result.getResult()).isEqualTo("{\"created\":1}");
        assertThat(result.getReferences()).isEmpty();
        verify(executor).execute("{\"a\":1}");
    }

    @Test
    void confirm_terminal_idempotent() throws Exception {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        entity.setStatus("CONFIRMED");
        entity.setResult("{\"created\":1}");
        stubFind(entity);

        AiConfirmResultVO result = service.confirm(entity.getPendingId(), 2L);

        assertThat(result.getResult()).isEqualTo("{\"created\":1}");
        assertThat(result.getReferences()).isEmpty();
        verify(executor, never()).execute(any());
    }

    @Test
    void confirm_concurrentLose_throws() {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        stubFind(entity);
        when(permissionService.hasPermission(eq(2L), any())).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(entity.getPendingId(), 2L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("已被处理");
    }

    @Test
    void confirm_noPermission_throws() {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        stubFind(entity);
        when(permissionService.hasPermission(eq(2L), any())).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(entity.getPendingId(), 2L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("无权限");
    }

    @Test
    void confirm_expired_marksExpiredAndThrows() {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        entity.setExpireTime(LocalDateTime.now().minusMinutes(1));
        stubFind(entity);
        when(permissionService.hasPermission(eq(2L), any())).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.confirm(entity.getPendingId(), 2L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void confirm_executorFails_marksFailed() throws Exception {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        stubFind(entity);
        when(permissionService.hasPermission(eq(2L), any())).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(executor.execute(any())).thenThrow(new RuntimeException("合同不存在"));

        assertThatThrownBy(() -> service.confirm(entity.getPendingId(), 2L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("执行失败");

        verify(mapper, times(2)).update(isNull(), any());
    }

    @Test
    void confirm_executorFails_defersMarkFailedUntilRollback() throws Exception {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        stubFind(entity);
        when(permissionService.hasPermission(2L, com.slz.crm.common.enumeration.PermissionOperates.SALES_APPEND_ORDER)).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(executor.execute(any())).thenThrow(new RuntimeException("合同不存在"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.confirm(entity.getPendingId(), 2L))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("执行失败");

            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            verify(mapper, times(1)).update(isNull(), any());

            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(mapper, times(1)).update(isNull(), any());

            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(mapper, times(2)).update(isNull(), updateWrapperCaptor.capture());

            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.slz.crm.pojo.entity.AiPendingActionEntity> wrapper =
                    updateWrapperCaptor.getAllValues().get(1);
            assertThat(wrapper.getSqlSet()).contains("status").contains("result");
            assertThat(wrapper.getSqlSegment()).contains("status");
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @Test
    void confirm_executorFails_sanitizesUserAndPersistedError() throws Exception {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        stubFind(entity);
        when(permissionService.hasPermission(eq(2L), any())).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(executor.execute(any())).thenThrow(new RuntimeException("SQLSyntaxErrorException: secret-table"));

        assertThatThrownBy(() -> service.confirm(entity.getPendingId(), 2L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("执行失败，请稍后重试")
                .hasMessageNotContaining("SQLSyntaxErrorException")
                .hasMessageNotContaining("secret-table");

        Object persistedError = ReflectionTestUtils.invokeMethod(service, "buildErrorJson");
        assertThat(persistedError).isEqualTo("{\"error\":\"执行失败\"}");
    }

    @Test
    void cancel_drafting_updatesStatus() {
        AiPendingActionEntity entity = drafting("{\"a\":1}");
        stubFind(entity);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.cancel(entity.getPendingId(), 2L);

        verify(mapper).update(isNull(), any());
    }

    @Test
    void cancel_terminal_noUpdate() {
        AiPendingActionEntity entity = pending("{\"a\":1}");
        entity.setStatus("CONFIRMED");
        stubFind(entity);

        service.cancel(entity.getPendingId(), 2L);

        verify(mapper, never()).update(isNull(), any());
    }

    // ==================== 构造辅助 ====================

    /** getOwnedEntity 经 getOne 查询：不同 MP 版本可能走 selectOne/selectOne(w,false)/selectList，全部 stub */
    private void stubFind(AiPendingActionEntity entity) {
        lenient().when(mapper.selectOne(any())).thenReturn(entity);
        lenient().when(mapper.selectOne(any(), anyBoolean())).thenReturn(entity);
        lenient().when(mapper.selectList(any())).thenReturn(List.of(entity));
    }

    private AiPendingActionEntity drafting(String payload) {
        AiPendingActionEntity entity = new AiPendingActionEntity();
        entity.setPendingId("PA20260828TEST0001");
        entity.setSessionId(1L);
        entity.setUserId(2L);
        entity.setActionType("CREATE_ORDER");
        entity.setStatus("DRAFTING");
        entity.setPayload(payload);
        entity.setAskRound(0);
        entity.setExpireTime(LocalDateTime.now().plusMinutes(30));
        return entity;
    }

    private AiPendingActionEntity pending(String payload) {
        AiPendingActionEntity entity = drafting(payload);
        entity.setStatus("PENDING");
        return entity;
    }
}
