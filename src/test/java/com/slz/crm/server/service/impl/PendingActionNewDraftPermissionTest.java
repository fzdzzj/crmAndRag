package com.slz.crm.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 新草稿类型的确认链权限语义验证：枚举映射自动生效 + 无权限确认拒绝 12002（extend-ai-draft-and-eval 任务 1.4，确认链零改动）。 */
@ExtendWith(MockitoExtension.class)
class PendingActionNewDraftPermissionTest {

  @Mock private PermissionService permissionService;

  @Test
  void contactTaskDraft_requiresTaskCreateTask_andRejects12002() {
    assertThat(ActionTypeEnum.getRequiredPermission("CREATE_CONTACT_TASK"))
        .isEqualTo(PermissionOperates.TASK_CREATE_TASK);
    when(permissionService.hasPermission(2L, PermissionOperates.TASK_CREATE_TASK))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                PendingActionGuards.requirePermission("CREATE_CONTACT_TASK", 2L, permissionService))
        .isInstanceOf(BaseException.class)
        .satisfies(
            e ->
                assertThat(((BaseException) e).getCode())
                    .isEqualTo(
                        com.slz.crm.common.enumeration.ErrorCode.PERMISSION_DENIED.getCode()))
        .hasMessageContaining("无权限");
  }

  @Test
  void businessActivityDraft_requiresSalesCreateBusinessActivity_andRejects12002() {
    assertThat(ActionTypeEnum.getRequiredPermission("CREATE_BUSINESS_ACTIVITY"))
        .isEqualTo(PermissionOperates.SALES_CREATE_BUSINESS_ACTIVITY);
    when(permissionService.hasPermission(2L, PermissionOperates.SALES_CREATE_BUSINESS_ACTIVITY))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                PendingActionGuards.requirePermission(
                    "CREATE_BUSINESS_ACTIVITY", 2L, permissionService))
        .isInstanceOf(BaseException.class)
        .satisfies(
            e ->
                assertThat(((BaseException) e).getCode())
                    .isEqualTo(
                        com.slz.crm.common.enumeration.ErrorCode.PERMISSION_DENIED.getCode()))
        .hasMessageContaining("无权限");
  }

  @Test
  void unknownActionType_noMapping_throwsParamRequired() {
    assertThat(ActionTypeEnum.getRequiredPermission("UNKNOWN_TYPE")).isNull();

    assertThatThrownBy(
            () -> PendingActionGuards.requirePermission("UNKNOWN_TYPE", 2L, permissionService))
        .isInstanceOf(BaseException.class)
        .satisfies(
            e ->
                assertThat(((BaseException) e).getCode())
                    .isEqualTo(com.slz.crm.common.enumeration.ErrorCode.PARAM_REQUIRED.getCode()))
        .hasMessageContaining("不支持的操作类型");
  }
}
