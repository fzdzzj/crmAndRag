package com.slz.crm.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.AiPendingActionEntity;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.ai.enums.PendingActionStatus;
import com.slz.crm.server.ai.executor.AiActionExecutor;
import com.slz.crm.server.ai.validation.AiActionValidator;
import com.slz.crm.server.mapper.AiPendingActionMapper;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * confirm 链路端到端写权限再审计（add-confirm-reaudit-coverage，D6 残留缺口）。
 *
 * <p>与 {@link PendingActionNewDraftPermissionTest} 的守卫级用例互补：后者直接调用 {@code
 * PendingActionGuards#requirePermission} 验证映射与拒绝分支，本类从 {@code PendingActionServiceImpl#confirm}
 * 入口穿过实体归属校验与终态幂等判定进入执行主体，验证无权限时 confirm 链路整体 fail-closed——三重断言（错误码 12002 + 状态不落 CONFIRMED +
 * executor 零交互）。
 */
@ExtendWith(MockitoExtension.class)
class PendingActionConfirmPermissionAuditTest {

  @Mock private AiPendingActionMapper mapper;

  @Mock private PermissionService permissionService;

  @Mock private AiActionValidator validator;

  @Mock private AiActionExecutor executor;

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
    // 策略注册已对 executor/validator 各产生一次 actionType() 调用；清零后断言只覆盖 confirm 链路内交互
    clearInvocations(executor, validator);
  }

  @Test
  void confirm_noPermission_failsClosedWithoutTouchingStateOrExecutor() {
    assertThat(ActionTypeEnum.getRequiredPermission("CREATE_ORDER"))
        .isEqualTo(PermissionOperates.SALES_APPEND_ORDER);

    AiPendingActionEntity entity = pending("{\"a\":1}");
    stubFind(entity);
    // 权限服务显式拒绝：confirm 链路必须在写库与派发执行器之前 fail-closed
    when(permissionService.hasPermission(2L, PermissionOperates.SALES_APPEND_ORDER))
        .thenReturn(false);
    // 即使 CAS 本可抢占成功，权限闸在 CAS 之前，任何状态写入都不应发生
    lenient().when(mapper.update(any(), any())).thenReturn(1);

    BaseException thrown =
        assertThrows(BaseException.class, () -> service.confirm(entity.getPendingId(), 2L));

    // ① 抛出权限拒绝异常，错误码精确为 12002
    assertThat(thrown.getCode()).isEqualTo(ErrorCode.PERMISSION_DENIED.getCode());
    assertThat(thrown.getMessage()).contains("无权限").contains("CREATE_ORDER");

    // ② 草稿实体状态不流转为 CONFIRMED，且未发生任何抢占式状态写
    assertThat(entity.getStatus()).isEqualTo(PendingActionStatus.PENDING.getValue());
    assertThat(entity.getStatus()).isNotEqualTo(PendingActionStatus.CONFIRMED.getValue());
    assertThat(entity.getConfirmedTime()).isNull();
    verify(mapper, never()).update(any(), any());

    // ③ 对应 executor 零交互（既不派发也不触碰执行结果），validator 同样不被调用
    verifyNoInteractions(executor);
    verifyNoInteractions(validator);
  }

  // ==================== 构造辅助 ====================

  /** getOwnedEntity 经 MP 查询，版本间可能走 selectOne/selectOne(w,false)/selectList，全部 stub */
  private void stubFind(AiPendingActionEntity entity) {
    lenient().when(mapper.selectOne(any())).thenReturn(entity);
    lenient().when(mapper.selectOne(any(), anyBoolean())).thenReturn(entity);
    lenient().when(mapper.selectList(any())).thenReturn(List.of(entity));
  }

  private AiPendingActionEntity pending(String payload) {
    AiPendingActionEntity entity = new AiPendingActionEntity();
    entity.setPendingId("PA20261016AUDIT001");
    entity.setSessionId(1L);
    entity.setUserId(2L);
    entity.setActionType("CREATE_ORDER");
    entity.setStatus(PendingActionStatus.PENDING.getValue());
    entity.setPayload(payload);
    entity.setAskRound(0);
    entity.setExpireTime(LocalDateTime.now().plusMinutes(30));
    return entity;
  }
}
