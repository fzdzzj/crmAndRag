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

/** PaymentActionValidator 单测：合同双通道解析 + 必填回款金额 */
@ExtendWith(MockitoExtension.class)
class PaymentActionValidatorTest {

  private static final Validator JSR_VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Mock private AiEntityResolver entityResolver;

  private PaymentActionValidator validator;

  @BeforeEach
  void setUp() {
    validator = new PaymentActionValidator();
    ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
    ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
  }

  @Test
  void validPayment_passes() {
    when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));
    String payload = "{\"contractId\":5,\"paymentAmount\":1000,\"paymentMethod\":\"银行转账\"}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
  }

  @Test
  void missingAmount_fails() {
    when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));

    AiValidationResult result = validator.validate("{\"contractId\":5}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("paymentAmount");
  }

  @Test
  void negativeAmount_fails() {
    when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));

    AiValidationResult result = validator.validate("{\"contractId\":5,\"paymentAmount\":-100}");

    assertThat(result.isValid()).isFalse();
    assertThat(result.getMissingFields()).contains("paymentAmount");
  }

  @Test
  void contractNameResolved_writesBack() {
    when(entityResolver.resolveContract(any(), eq("某合同"))).thenReturn(resolved(8L));
    String payload = "{\"contractName\":\"某合同\",\"paymentAmount\":1000}";

    AiValidationResult result = validator.validate(payload);

    assertThat(result.isValid()).isTrue();
    assertThat(result.getResolvedPayload()).contains("\"contractId\":8");
  }

  private AiEntityResolver.Resolution resolved(Long id) {
    return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
  }
}
