package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ai.AiBusinessActivityDraftPayloadDTO;
import com.slz.crm.pojo.dto.ai.AiContactTaskDraftPayloadDTO;
import com.slz.crm.pojo.dto.ai.AiDraftResult;
import com.slz.crm.server.ai.InsightToActionService.InsightToActionRequest;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.ai.insight.CustomerInsightHealthLevel;
import com.slz.crm.server.ai.insight.CustomerInsightResult;
import com.slz.crm.server.ai.insight.CustomerInsightService;
import com.slz.crm.server.service.PendingActionService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * InsightToActionService 单测（add-ai-insight-to-action-conversion 任务 4.1）。
 *
 * <p>锁死 payload 与 {@code AiDraftToolExecutors} 既有草稿消费形状对齐：直接反序列化提交的 payloadJson 到 {@code
 * AiContactTaskDraftPayloadDTO} / {@code AiBusinessActivityDraftPayloadDTO}（执行器消费格式），并断言关键字段。
 *
 * <p>覆盖：默认/显式 actionType 全路径、payload 形状、空建议/越界/非法 actionType 拒绝、pendingId 返回。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 诊断建议转 Action 服务")
class InsightToActionServiceTest {

  @Mock private CustomerInsightService customerInsightService;

  @Mock private PendingActionService pendingActionService;

  private InsightToActionService service;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    service = new InsightToActionService(customerInsightService, pendingActionService);
    BaseUnit.setCurrentRole(role(42L));
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("默认 CREATE_CONTACT_TASK：payload 含 companyId + taskTitle，返回 pendingId")
  void contactTaskDefaultPath() throws Exception {
    when(customerInsightService.diagnose(5L)).thenReturn(insight(List.of("建议尽快安排客户拜访，恢复跟进节奏")));
    when(pendingActionService.submitDraft(
            isNull(), eq(42L), eq(ActionTypeEnum.CREATE_CONTACT_TASK.getValue()), any()))
        .thenReturn(AiDraftResult.builder().pendingId("PD-1").status("PENDING").build());

    InsightToActionService.InsightToActionResult result =
        service.toAction(
            5L, new InsightToActionRequest(0, ActionTypeEnum.CREATE_CONTACT_TASK.getValue()));

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(pendingActionService)
        .submitDraft(
            isNull(),
            eq(42L),
            eq(ActionTypeEnum.CREATE_CONTACT_TASK.getValue()),
            payloadCaptor.capture());
    AiContactTaskDraftPayloadDTO payload =
        objectMapper.readValue(payloadCaptor.getValue(), AiContactTaskDraftPayloadDTO.class);
    assertThat(payload.getCompanyId()).isEqualTo(5L);
    assertThat(payload.getTaskTitle()).isEqualTo("建议尽快安排客户拜访，恢复跟进节奏");
    assertThat(payload.getCompanyName()).isNull();

    assertThat(result.pendingId()).isEqualTo("PD-1");
    assertThat(result.actionType()).isEqualTo(ActionTypeEnum.CREATE_CONTACT_TASK.getValue());
    assertThat(result.suggestion()).isEqualTo("建议尽快安排客户拜访，恢复跟进节奏");
  }

  @Test
  @DisplayName("CREATE_BUSINESS_ACTIVITY：payload 以 activityTitle 承载建议，opportunity 交由确认流补齐")
  void businessActivityPath() throws Exception {
    when(customerInsightService.diagnose(5L)).thenReturn(insight(List.of("建议推动停滞商机阶段流转")));
    when(pendingActionService.submitDraft(
            isNull(), eq(42L), eq(ActionTypeEnum.CREATE_BUSINESS_ACTIVITY.getValue()), any()))
        .thenReturn(AiDraftResult.builder().pendingId("PD-2").status("DRAFTING").build());

    service.toAction(
        5L, new InsightToActionRequest(0, ActionTypeEnum.CREATE_BUSINESS_ACTIVITY.getValue()));

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(pendingActionService)
        .submitDraft(
            isNull(),
            eq(42L),
            eq(ActionTypeEnum.CREATE_BUSINESS_ACTIVITY.getValue()),
            payloadCaptor.capture());
    AiBusinessActivityDraftPayloadDTO payload =
        objectMapper.readValue(payloadCaptor.getValue(), AiBusinessActivityDraftPayloadDTO.class);
    assertThat(payload.getActivityTitle()).isEqualTo("建议推动停滞商机阶段流转");
    assertThat(payload.getOpportunityId()).isNull();
  }

  @Test
  @DisplayName("缺省 actionType 无 null 值：解析为 CREATE_CONTACT_TASK")
  void defaultActionTypeWhenNull() throws Exception {
    when(customerInsightService.diagnose(5L)).thenReturn(insight(List.of("保持定期跟进")));
    when(pendingActionService.submitDraft(any(), any(), any(), any()))
        .thenReturn(AiDraftResult.builder().pendingId("PD-3").status("PENDING").build());

    InsightToActionService.InsightToActionResult result =
        service.toAction(5L, new InsightToActionRequest(0, null));

    assertThat(result.actionType()).isEqualTo(ActionTypeEnum.CREATE_CONTACT_TASK.getValue());
    verify(pendingActionService)
        .submitDraft(isNull(), eq(42L), eq(ActionTypeEnum.CREATE_CONTACT_TASK.getValue()), any());
  }

  @Test
  @DisplayName("空建议列表：抛出 PARAM_OUT_OF_RANGE 业务异常，不静默")
  void emptySuggestionsRejected() {
    when(customerInsightService.diagnose(5L)).thenReturn(insight(List.of()));

    assertThatThrownBy(() -> service.toAction(5L, new InsightToActionRequest(0, null)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_OUT_OF_RANGE.getCode());
  }

  @Test
  @DisplayName("index 越界：抛出 PARAM_OUT_OF_RANGE，不静默")
  void outOfRangeRejected() {
    when(customerInsightService.diagnose(5L)).thenReturn(insight(List.of("仅一条建议")));

    assertThatThrownBy(() -> service.toAction(5L, new InsightToActionRequest(1, null)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_OUT_OF_RANGE.getCode());
  }

  @Test
  @DisplayName("index 为负：抛出 PARAM_REQUIRED，不静默")
  void negativeIndexRejected() {
    assertThatThrownBy(() -> service.toAction(5L, new InsightToActionRequest(-1, null)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_REQUIRED.getCode());
  }

  @Test
  @DisplayName("非法 actionType：抛出 PARAM_FORMAT_ERROR，不静默")
  void illegalActionTypeRejected() {
    assertThatThrownBy(() -> service.toAction(5L, new InsightToActionRequest(0, "CREATE_ORDER")))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
  }

  private static CustomerInsightResult insight(List<String> suggestions) {
    return new CustomerInsightResult(
        5L,
        "测试客户",
        72,
        CustomerInsightHealthLevel.HEALTHY,
        "摘要",
        List.of(),
        suggestions,
        List.of(),
        List.of(),
        false);
  }

  private static RoleAO role(Long id) {
    RoleAO role = new RoleAO();
    role.setId(id);
    return role;
  }
}
