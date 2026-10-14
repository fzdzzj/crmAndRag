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

/** BusinessActivityActionValidator 单测：商机双通道解析 + 必填活动标题（extend-ai-draft-and-eval 任务 2.4） */
@ExtendWith(MockitoExtension.class)
class BusinessActivityActionValidatorTest {

  private static final Validator JSR_VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Mock private AiEntityResolver entityResolver;

  private BusinessActivityActionValidator validator;

  @BeforeEach
  void setUp() {
    validator = new BusinessActivityActionValidator();
    ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
    ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
  }

  @Test
  void validBusinessActivity_passes() {
    when(entityResolver.resolveOpportunity(eq(5L), any(), any(), any())).thenReturn(resolved(5L));
    String payload =
        "{\"opportunityId\":5,\"activityTitle\":\"客户需求调研\",\"activityType\":\"电话沟通\",\"activityDuration\":30}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
    assertThat(result.getMissingFields()).isEmpty();
  }

  @Test
  void missingActivityTitle_failsWithAskQuestion() {
    when(entityResolver.resolveOpportunity(eq(5L), any(), any(), any())).thenReturn(resolved(5L));

    AiValidationResult result = validator.validate("{\"opportunityId\":5}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("activityTitle");
    assertThat(result.getQuestions()).contains("业务活动的标题是什么？");
  }

  @Test
  void missingOpportunityAnchor_failsWithAskQuestion() {
    when(entityResolver.resolveOpportunity(any(), any(), any(), any()))
        .thenReturn(
            new AiEntityResolver.Resolution(AiEntityResolver.Status.MISSING, null, List.of()));

    AiValidationResult result = validator.validate("{\"activityTitle\":\"客户需求调研\"}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("opportunityId");
    assertThat(result.getQuestions()).contains("这笔业务活动挂在哪个商机下？请提供商机编号或名称");
  }

  @Test
  void opportunityNameResolved_writesBackOpportunityId() {
    when(entityResolver.resolveOpportunity(any(), eq("智能制造 MES 项目"), any(), any()))
        .thenReturn(resolved(8L));
    String payload = "{\"opportunityName\":\"智能制造 MES 项目\",\"activityTitle\":\"客户需求调研\"}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
    assertThat(result.getResolvedPayload()).contains("\"opportunityId\":8");
  }

  private AiEntityResolver.Resolution resolved(Long id) {
    return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
  }
}
