package com.slz.crm.server.ai.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.slz.crm.server.ai.AiEntityResolver;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** ContactTaskActionValidator 单测：客户双通道解析 + 必填任务主题（extend-ai-draft-and-eval 任务 1.4） */
@ExtendWith(MockitoExtension.class)
class ContactTaskActionValidatorTest {

  private static final Validator JSR_VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Mock private AiEntityResolver entityResolver;

  private ContactTaskActionValidator validator;

  @BeforeEach
  void setUp() {
    validator = new ContactTaskActionValidator();
    ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
    ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
  }

  @Test
  void validContactTask_passes() {
    when(entityResolver.resolveCustomerCompany(eq(5L), any())).thenReturn(resolved(5L));
    String payload =
        "{\"companyId\":5,\"taskTitle\":\"下周拜访客户\",\"taskContent\":\"约见采购总监\",\"priorityStr\":\"高\"}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
    assertThat(result.getMissingFields()).isEmpty();
  }

  @Test
  void missingTaskTitle_failsWithAskQuestion() {
    when(entityResolver.resolveCustomerCompany(eq(5L), any())).thenReturn(resolved(5L));

    AiValidationResult result = validator.validate("{\"companyId\":5}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("taskTitle");
    assertThat(result.getQuestions()).contains("跟进任务的主题是什么？");
  }

  @Test
  void missingCompanyAnchor_failsWithAskQuestion() {
    when(entityResolver.resolveCustomerCompany(any(), any()))
        .thenReturn(
            new AiEntityResolver.Resolution(AiEntityResolver.Status.MISSING, null, List.of()));

    AiValidationResult result = validator.validate("{\"taskTitle\":\"下周拜访\"}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("companyId");
    assertThat(result.getQuestions()).contains("这笔跟进任务关联哪个客户公司？请提供公司编号或名称");
  }

  @Test
  void companyNameResolved_writesBackCompanyId() {
    when(entityResolver.resolveCustomerCompany(any(), eq("云创科技有限公司"))).thenReturn(resolved(8L));
    String payload = "{\"companyName\":\"云创科技有限公司\",\"taskTitle\":\"下周拜访\"}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
    assertThat(result.getResolvedPayload()).contains("\"companyId\":8");
  }

  private AiEntityResolver.Resolution resolved(Long id) {
    return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
  }
}
