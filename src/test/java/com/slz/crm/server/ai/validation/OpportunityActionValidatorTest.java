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

/** OpportunityActionValidator 单测：客户双通道解析 + 必填商机名称 */
@ExtendWith(MockitoExtension.class)
class OpportunityActionValidatorTest {

  private static final Validator JSR_VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Mock private AiEntityResolver entityResolver;

  private OpportunityActionValidator validator;

  @BeforeEach
  void setUp() {
    validator = new OpportunityActionValidator();
    ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
    ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
  }

  @Test
  void validOpportunity_passes() {
    when(entityResolver.resolveCustomerCompany(eq(5L), any())).thenReturn(resolved(5L));
    String payload = "{\"companyId\":5,\"opportunityName\":\"商机A\",\"amount\":10000}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
  }

  @Test
  void missingOpportunityName_fails() {
    when(entityResolver.resolveCustomerCompany(eq(5L), any())).thenReturn(resolved(5L));

    AiValidationResult result = validator.validate("{\"companyId\":5}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("opportunityName");
  }

  @Test
  void ambiguousCompany_asksCandidates() {
    when(entityResolver.resolveCustomerCompany(any(), eq("公司")))
        .thenReturn(
            new AiEntityResolver.Resolution(
                AiEntityResolver.Status.AMBIGUOUS, null, List.of("公司A", "公司B")));

    AiValidationResult result =
        validator.validate("{\"companyName\":\"公司\",\"opportunityName\":\"商机A\"}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("companyId");
  }

  private AiEntityResolver.Resolution resolved(Long id) {
    return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
  }
}
