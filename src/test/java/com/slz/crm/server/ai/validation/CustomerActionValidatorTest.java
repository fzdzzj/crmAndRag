package com.slz.crm.server.ai.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** CustomerActionValidator 单测：正反例（belongGroup 文本透传，不做存在性校验） */
class CustomerActionValidatorTest {

  private static final Validator JSR_VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  private CustomerActionValidator validator;

  @BeforeEach
  void setUp() {
    validator = new CustomerActionValidator();
    ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
  }

  @Test
  void actionType_isCreateCustomer() {
    assertThat(validator.actionType()).isEqualTo("CREATE_CUSTOMER");
  }

  @Test
  void validCustomer_passes() {
    String payload = "{\"companyName\":\"测试公司\",\"industry\":\"制造\",\"belongGroup\":\"华东集团\"}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
  }

  @Test
  void missingCompanyName_fails() {
    AiValidationResult result = validator.validate("{\"industry\":\"制造\"}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("companyName");
    assertThat(result.getQuestions()).isNotEmpty();
  }

  @Test
  void invalidJson_fails() {
    AiValidationResult result = validator.validate("not-json");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("companyName");
  }
}
